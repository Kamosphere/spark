/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package edu.ustc.nodb.GPUGraphX

import edu.ustc.nodb.GPUGraphX.algorithm.array.algoTemplete
import scala.reflect.ClassTag

import org.apache.spark.TaskContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.util.PeriodicGraphCheckpointer
import org.apache.spark.internal.Logging
import org.apache.spark.rdd.RDD
import org.apache.spark.rdd.util.PeriodicRDDCheckpointer

object PregelGPUSkipping extends Logging{

  // scalastyle:off println

  def run[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (graph: Graph[VertexId, ED],
   activeDirection: EdgeDirection = EdgeDirection.Either,
   maxIterations: Int = Int.MaxValue)
  (algorithm: algoTemplete[VD, ED, A])
  : Graph[VD, ED] = {

    val startTime = System.nanoTime()

    val checkpointInterval = graph.vertices.sparkContext.getConf
      .getInt("spark.graphx.pregel.checkpointInterval", -1)

    // initiate the graph
    val spGraph = graph.mapVertices ((vid, attr) =>
      algorithm.lambda_initGraph(vid, attr))
    // var g = algorithm.repartition(spGraph)
    var g = spGraph
    val graphCheckPointer = new PeriodicGraphCheckpointer[VD, ED](
      checkpointInterval, graph.vertices.sparkContext)
    graphCheckPointer.update(g)

    val countOutDegree = g.outDegrees.collectAsMap()

    val sc = org.apache.spark.SparkContext.getOrCreate()

    val ifFilteredCounter = sc.longAccumulator("ifFilteredCounter")

    var iterTimes = 0

    var partitionSplit = g.innerVerticesEdgesCount().collectAsMap()

    // Input vertex count and edge count in order to init GPU env
    algorithm.fillPartitionInnerData(partitionSplit)

    // Input edge data into GPU
    g.edges.foreachPartition(iter => {
      val pid = TaskContext.getPartitionId()
      algorithm.lambda_edgeImport(pid, iter)(
        iterTimes, countOutDegree, ifFilteredCounter)
    })

    var checkSkippable = GraphXUtils.mapReduceTripletsIntoGPUSkip_normal(
      g, algorithm.lambda_VertexIntoGPU).collectAsMap()

    var messages = GraphXUtils.mapReduceTripletsIntoGPUSkip_fetch(g,
      algorithm.lambda_getMessages, algorithm.lambda_globalReduceFunc)

    val messageCheckPointer = new PeriodicRDDCheckpointer[(VertexId, A)](
      checkpointInterval, graph.vertices.sparkContext)
    messageCheckPointer.update(messages.asInstanceOf[RDD[(VertexId, A)]])

    // get the amount of active vertices
    var activeMessages = messages.count()

    val endTimeBeforeIter = System.nanoTime()

    logInfo("-------------------------")
    logInfo("First iteration time: " + (endTimeBeforeIter - startTime) +
      ", next iter active node amount: " + activeMessages)
    logInfo("-------------------------")

    println("-------------------------")
    println("First iteration time: " + (endTimeBeforeIter - startTime) +
      ", next iter active node amount: " + activeMessages)
    println("-------------------------")

    var prevG : Graph[VD, ED] = null

    val ifRepartition = false

    // loop
    while(activeMessages > 0 && iterTimes < maxIterations) {

      logInfo("Pregel Start iteration " + iterTimes)
      println("Pregel Start iteration " + iterTimes)

      val startTime = System.nanoTime()

      prevG = g

      // merge the messages into graph
      g = g.joinVertices(messages)((vid, v1, v2) =>
        algorithm.lambda_globalVertexFunc(vid, v1, v2))

      graphCheckPointer.update(g)

      if(ifRepartition) {

        partitionSplit = g.innerVerticesEdgesCount().collectAsMap()

        algorithm.fillPartitionInnerData(partitionSplit)

        g.triplets.foreachPartition(iter => {
          val pid = TaskContext.getPartitionId()
          algorithm.lambda_edgeImport(pid, iter)(
            iterTimes, countOutDegree, ifFilteredCounter)
        })
      }
      var oldMessages : VertexRDD[A] = null

      var skipable = true
      var activeMessagesInPartition = 0

      for (partition <- checkSkippable) {
        activeMessagesInPartition += partition._2._2
        if (partition._2._1) {
          skipable = false
        }
      }
      if (activeMessagesInPartition == 0 && skipable) {
        skipable = false
      }

      var everskip = false

      if (skipable) {
        everskip = true

        messages.unpersist(blocking = false)
      }

      var skipTimes = 1
      while (skipable) {
        checkSkippable = GraphXUtils.mapReduceTripletsIntoGPUSkip_skipping(
          g, skipTimes, algorithm.lambda_SkipVertexIntoGPU).collectAsMap()

        println(checkSkippable)

        activeMessagesInPartition = 0
        for (partition <- checkSkippable) {
          activeMessagesInPartition += partition._2._2
          if (partition._2._1) {
            skipable = false
          }
        }
        if (activeMessagesInPartition == 0 && skipable) {
          skipable = false
        }
        if (skipable) skipTimes += 1

      }

      if (everskip) {
        // all merged messages include inner old messages
        val messagesRemained = GraphXUtils.mapReduceTripletsIntoGPUSkip_fetchOldMsg(
          g, algorithm.lambda_getOldMessages,
          algorithm.lambda_globalOldMsgReduceFunc).flatMap(
          elem => if (elem._2._1) Some((elem._1, elem._2._3)) else Some((elem._1, elem._2._3)))

        messagesRemained.count()

        // merge the old messages into graph
        g = g.joinVertices(messagesRemained)((vid, v1, v2) =>
          algorithm.lambda_globalVertexFunc(vid, v1, v2))

        graphCheckPointer.update(g)

        messagesRemained.unpersist(blocking = false)

        // if last skip make the oldmessage zero, force skipable to false
        oldMessages = GraphXUtils.mapReduceTripletsIntoGPUSkip_fetch(g,
          algorithm.lambda_getMessages, algorithm.lambda_globalReduceFunc)
        messageCheckPointer.update(oldMessages.asInstanceOf[RDD[(VertexId, A)]])

        oldMessages.count()

        val prevSkippingDirection : EdgeDirection = null

        checkSkippable = GraphXUtils.mapReduceTripletsIntoGPUSkip_normal(g,
          algorithm.lambda_VertexIntoGPU, Some((oldMessages, prevSkippingDirection))).collectAsMap()
      }

      else {
        oldMessages = messages

        checkSkippable = GraphXUtils.mapReduceTripletsIntoGPUSkip_normal(g,
          algorithm.lambda_VertexIntoGPU, Some((oldMessages, activeDirection))).collectAsMap()
      }

      messages = GraphXUtils.mapReduceTripletsIntoGPUSkip_fetch(g,
        algorithm.lambda_getMessages, algorithm.lambda_globalReduceFunc)
      messageCheckPointer.update(messages.asInstanceOf[RDD[(VertexId, A)]])

      // get the amount of active vertices
      activeMessages = messages.count()

      /*
      something while need to repartition
       */

      oldMessages.unpersist(blocking = false)

      val endTime = System.nanoTime()

      logInfo("-------------------------")
      logInfo("Pregel finished iteration " + iterTimes)
      logInfo("Whole iteration time: " + (endTime - startTime) +
        ", next iter active node amount: " + activeMessages)
      logInfo("-------------------------")

      println("-------------------------")
      println("Pregel finished iteration " + iterTimes)
      println("Whole iteration time: " + (endTime - startTime) +
        ", next iter active node amount: " + activeMessages)
      println("-------------------------")

      iterTimes = iterTimes + 1

    }

    messageCheckPointer.unpersistDataSet()
    graphCheckPointer.deleteAllCheckpoints()
    messageCheckPointer.deleteAllCheckpoints()

    val endTime = System.nanoTime()

    logInfo("The whole Pregel process time: " + (endTime - startTime))
    println("The whole Pregel process time: " + (endTime - startTime))
    g

  }

  // after running algorithm, close the server
  def close[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (Graph: Graph[VD, ED], algorithm: algoTemplete[VD, ED, A]): Unit = {

    Graph.vertices.foreachPartition(g => {
      val pid = TaskContext.getPartitionId()
      algorithm.lambda_shutDown(pid, g)
      logInfo("GPU and shm resource released in partition " + pid)
    })
  }

  // scalastyle:on println
}
