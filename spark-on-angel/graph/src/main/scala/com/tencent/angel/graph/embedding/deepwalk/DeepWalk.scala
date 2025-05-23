package com.tencent.angel.graph.embedding.deepwalk

import com.tencent.angel.graph.common.param.ModelContext
import com.tencent.angel.graph.data.neighbor.NeighborDataOps
import com.tencent.angel.graph.utils.params._
import com.tencent.angel.graph.utils.{GraphIO, Stats}
import com.tencent.angel.spark.context.PSContext
import org.apache.spark.SparkContext
import org.apache.spark.ml.Transformer
import org.apache.spark.ml.param.ParamMap
import org.apache.spark.ml.util.Identifiable
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.types.{StructType, _}
import org.apache.spark.sql.{DataFrame, Dataset, Row}
import org.apache.spark.storage.StorageLevel

import scala.collection.mutable.ArrayBuffer

class DeepWalk(override val uid: String) extends Transformer
  with HasSrcNodeIdCol with HasDstNodeIdCol with HasOutputNodeIdCol with HasOutputCoreIdCol
  with HasStorageLevel with HasPartitionNum with HasPSPartitionNum with HasMaxIteration with HasDynamicInitNeighbor
  with HasBatchSize with HasArrayBoundsPath with HasIsWeighted with HasWeightCol with HasUseBalancePartition
  with HasNeedReplicaEdge with HasUseEdgeBalancePartition with HasWalkLength with HasEpochNum with HasBalancePartitionPercent {

  private var output: String = _

  def this() = this(Identifiable.randomUID("DeepWalk"))

  def setOutputDir(in: String): Unit = {
    output = in
  }

  def initialize(dataset: Dataset[_]): (DeepWalkPSModel, RDD[DeepWalkGraphPartition]) = {
    //create origin edges RDD and data preprocessing

    val rawEdges_ = NeighborDataOps.loadEdgesWithWeight(dataset, $(srcNodeIdCol), $(dstNodeIdCol), $(weightCol), $(isWeighted), $(needReplicaEdge), true, false, false)
    val rawEdges = rawEdges_.repartition($(partitionNum)).persist(StorageLevel.DISK_ONLY)
    val (minId, maxId, numEdges) = Stats.summarizeWithWeight(rawEdges)
    println(s"minId=$minId maxId=$maxId numEdges=$numEdges level=${$(storageLevel)}")

    //ps process;create ps nodes adjacency matrix
    println("start to run ps")
    PSContext.getOrCreate(SparkContext.getOrCreate())

    // Create model
    val modelContext = new ModelContext($(psPartitionNum), minId, maxId + 1, -1,
      "deepwalk", SparkContext.getOrCreate().hadoopConfiguration)

    //    val data = edges.map(_._2._1) // ps loadBalance by in degree
    val data = rawEdges.flatMap(f => Iterator(f._1, f._2))
    //val model = DeepWalkPSModel.fromMinMax(minId, maxId, data, $(psPartitionNum), useBalancePartition = $(useBalancePartition))
    val model = DeepWalkPSModel(modelContext, data, $(useBalancePartition), $(balancePartitionPercent), $(dynamicInitNeighbor))

    val start = System.currentTimeMillis()

    val graphOri = if ($(dynamicInitNeighbor)) {
      println("Update the adjacency list using dynamic initialization")
      val degree = rawEdges.map(x => (x._1, 1)).reduceByKey(_ + _)
      degree.persist($(storageLevel))

      var startTime = System.currentTimeMillis()
      val initNumNodes = model.initNeighborsDegree(degree, $(batchSize), $(isWeighted))
      println(s"init $initNumNodes nodes' space to PS, cost ${System.currentTimeMillis() - startTime} ms.")

      startTime = System.currentTimeMillis()
      val numEdges = model.addNodeNei(rawEdges, $(batchSize))
      println(s"add $numEdges edges to PS, cost ${System.currentTimeMillis() - startTime} ms.")

      // pull weights and calculate alias and push to ps
      startTime = System.currentTimeMillis()
      val transNumNodes = model.createAlias(degree.map(_._1), $(batchSize))
      println(s"created $transNumNodes nodes' alias, cost ${System.currentTimeMillis() - startTime} ms.")

      val graphOri = degree.mapPartitionsWithIndex((index, edges) =>
        Iterator(DeepWalkGraphPartition.initNodePaths(index, edges.map(r => r._1).toArray, $(batchSize))))
      graphOri
    } else {
      println("Update the adjacency list using static initialization")
      // calc alias table for each node
      val edges = rawEdges.map { case (src, dst, w) => (src, (dst, w)) }
      val aliasTable = edges.groupByKey($(partitionNum)).map(x => (x._1, x._2.toArray.distinct))
        .mapPartitionsWithIndex { case (partId, iter) =>
          DeepWalk.calcAliasTable(partId, iter)
        }
      //push node adjacency list into ps matrix
      var startTime = System.currentTimeMillis()
      val numEdges = aliasTable.mapPartitionsWithIndex((index, adjTable) =>
        DeepWalkGraphPartition.initPSMatrix(model, index, adjTable, $(batchSize))).reduce(_ + _)
      println(s"init $numEdges edges to PS, cost ${System.currentTimeMillis() - startTime} ms.")

      // create graph with （node，sample path）
      val graphOri = aliasTable.mapPartitionsWithIndex((index, adjTable) =>
        Iterator(DeepWalkGraphPartition.initNodePaths(index, adjTable.map(r => r._1).toArray, $(batchSize))))
      graphOri
    }

    graphOri.persist($(storageLevel))
    //trigger action
    graphOri.foreachPartition(_ => Unit)
    println(s"initialize neighborTable cost ${(System.currentTimeMillis() - start) / 1000}s")
    // checkpoint
    model.checkpoint()
    (model, graphOri)
  }

  override def transform(dataset: Dataset[_]): DataFrame = {
    val (model, graphOri) = initialize(dataset)

    var epoch = 0
    while (epoch < $(epochNum)) {
      var graph = graphOri.map(x => x.deepClone())
      //sample paths with random walk
      var curIteration = 0
      var prev = graph
      val beginTime = System.currentTimeMillis()
      do {
        val beforeSample = System.currentTimeMillis()
        curIteration += 1
        graph = prev.map(_.process(model, curIteration, $(dynamicInitNeighbor)))
        graph.persist($(storageLevel))
        graph.count()
        prev.unpersist(true)
        prev = graph
        var sampleTime = (System.currentTimeMillis() - beforeSample)
        println(s"epoch $epoch, iter $curIteration, sampleTime: $sampleTime")
      } while (curIteration < $(walkLength) - 1)


      val EndTime = (System.currentTimeMillis() - beginTime)
      println(s"epoch $epoch, DeepWalkWithWeight all sampleTime: $EndTime")

      val temp = graph.flatMap(_.save())
      println(s"epoch $epoch, num path: ${temp.count()}")
      println(s"epoch $epoch, num invalid path: ${
        temp.filter(_.length != ${
          walkLength
        }).count()
      }")
      val tempRe = dataset.sparkSession.createDataFrame(temp.map(x => Row(x.mkString(" "))), transformSchema(dataset.schema))
      if (epoch == 0) {
        GraphIO.save(tempRe, output)
      }

      else {
        GraphIO.appendSave(tempRe, output)
      }
      println(s"epoch $epoch, saved results to $output")
      epoch += 1
      graph.unpersist()
    }

    val t = SparkContext.getOrCreate().parallelize(List("1", "2"), 1)
    dataset.sparkSession.createDataFrame(t.map(x => Row(x)), transformSchema(dataset.schema))
  }


  override def transformSchema(schema: StructType): StructType = {
    StructType(Seq(StructField("path", StringType, nullable = false)))
  }


  override def copy(extra: ParamMap): Transformer = defaultCopy(extra)

}

object DeepWalk {
  def calcAliasTable(partId: Int, iter: Iterator[(Long, Array[(Long, Float)])]): Iterator[(Long, Array[Long], Array[Float], Array[Int])] = {
    iter.map { case (src, neighbors) =>
      val (events, weights) = neighbors.unzip
      val weightsSum = weights.sum
      val len = weights.length
      val areaRatio = weights.map(_ / weightsSum * len)
      val (accept, alias) = createAliasTable(areaRatio)
      (src, events, accept, alias)
    }
  }

  def createAliasTable(areaRatio: Array[Float]): (Array[Float], Array[Int]) = {
    val len = areaRatio.length
    val small = ArrayBuffer[Int]()
    val large = ArrayBuffer[Int]()
    val accept = Array.fill(len)(0f)
    val alias = Array.fill(len)(0)

    for (idx <- areaRatio.indices) {
      if (areaRatio(idx) < 1.0) small.append(idx) else large.append(idx)
    }
    while (small.nonEmpty && large.nonEmpty) {
      val smallIndex = small.remove(small.size - 1)
      val largeIndex = large.remove(large.size - 1)
      accept(smallIndex) = areaRatio(smallIndex)
      alias(smallIndex) = largeIndex
      areaRatio(largeIndex) = areaRatio(largeIndex) - (1 - areaRatio(smallIndex))
      if (areaRatio(largeIndex) < 1.0) small.append(largeIndex) else large.append(largeIndex)
    }
    while (small.nonEmpty) {
      val smallIndex = small.remove(small.size - 1)
      accept(smallIndex) = 1
    }

    while (large.nonEmpty) {
      val largeIndex = large.remove(large.size - 1)
      accept(largeIndex) = 1
    }
    (accept, alias)
  }

}
