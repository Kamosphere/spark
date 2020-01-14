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

package edu.ustc.nodb.GPUGraphX.algorithm.array.SSSP

import edu.ustc.nodb.GPUGraphX.algorithm.SPMap
import edu.ustc.nodb.GPUGraphX.algorithm.array.algoTemplete
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.util.LongAccumulator

class pregel_SSSP(allSource: Broadcast[ArrayBuffer[VertexId]],
                  vertexSum: Long,
                  edgeSum: Long,
                  parts: Int) extends algoTemplete[SPMap, Double, SPMap] {

  var controller: GPUController = _

  private def makeMap(x: (VertexId, Double)*) = Map(x: _*)

  override var initSource: Array[VertexId] = allSource.value.toArray

  override var partitionInnerData : collection.Map[Int, (Int, Int)] = _

  override def fillPartitionInnerData(newMap: collection.Map[Int, (Int, Int)]) : Unit = {
    partitionInnerData = newMap
  }

  override def repartition
  (g: Graph[SPMap, Double]): Graph[SPMap, Double] = {
    g
  }

  override def lambda_initGraph
  (vid: VertexId, attr: VertexId): SPMap = {

    var partitionInit = makeMap()
    for (sid <- allSource.value.sorted) {
      if (vid == sid) {
        partitionInit += (sid -> 0)
      }
    }
    partitionInit
  }

  override def lambda_initMessage(v1: VertexId): SPMap = {
    if (initSource.contains(v1)) {
      makeMap(v1 -> 0)
    }
    else makeMap()
  }

  override def lambda_globalVertexFunc
  (v1: VertexId, v2: SPMap, v3: SPMap) : SPMap = {
    (v2.keySet ++ v3.keySet).map {
      k => k -> math.min(v2.getOrElse(k, Double.MaxValue), v3.getOrElse(k, Double.MaxValue))
    }(collection.breakOut)
  }

  override def lambda_globalReduceFunc
  (v1: SPMap, v2: SPMap): SPMap = {
    (v1.keySet ++ v2.keySet).map {
      k => k -> math.min(v1.getOrElse(k, Double.MaxValue), v2.getOrElse(k, Double.MaxValue))
    }(collection.breakOut)
  }

  override def lambda_edgeImport
  (pid: Int, iter: Iterator[Edge[Double]])
  (iterTimes: Int,
   countOutDegree: collection.Map[VertexId, Int],
   counter: LongAccumulator):
  Unit = {

    val startTimeA = System.nanoTime()

    val sourceList = allSource.value

    // pre allocate size
    val preParameter = partitionInnerData.get(pid)
    val preVertexLength = preParameter.get._1
    val preEdgeLength = preParameter.get._2

    // edge data array
    val pEdgeSrcIDTemp = new Array[Long](preEdgeLength)
    val pEdgeDstIDTemp = new Array[Long](preEdgeLength)
    val pEdgeAttrTemp = new Array[Double](preEdgeLength)
    // used to remove the abundant vertices and record outDegree
    val VertexNumList = new mutable.HashMap[Long, Int]

    var temp : Edge[Double] = null

    var EdgeCount = 0

    while(iter.hasNext) {
      temp = iter.next()

      pEdgeSrcIDTemp(EdgeCount) = temp.srcId
      pEdgeDstIDTemp(EdgeCount) = temp.dstId
      pEdgeAttrTemp(EdgeCount) = temp.attr
      EdgeCount = EdgeCount + 1

      // Out Degree count in partition
      if(! VertexNumList.contains(temp.srcId)) {
        VertexNumList.put(temp.srcId, 1)
      }
      else {
        val countTracker = VertexNumList.getOrElse(temp.srcId, 0)
        VertexNumList.update(temp.srcId, countTracker + 1)
      }

      if(! VertexNumList.contains(temp.dstId)) {
        VertexNumList.put(temp.dstId, 0)
      }

    }

    // Detect if a vertex could satisfy the skip condition
    val filteredVertex = new ArrayBuffer[Long]
    for (part <- VertexNumList) {
      // vertex has no out degree is also satisfy
      if (countOutDegree.getOrElse(part._1, 0) == part._2) {
        filteredVertex. += (part._1)
      }
    }

    val endTimeA = System.nanoTime()

    val startTimeB = System.nanoTime()

    controller = new GPUController(vertexSum, preVertexLength, EdgeCount, sourceList, pid)

    controller.GPUEnvEdgeInit(filteredVertex.toArray,
      pEdgeSrcIDTemp, pEdgeDstIDTemp, pEdgeAttrTemp)

    val endTimeB = System.nanoTime()
    // scalastyle:off println
    println("In iter " + iterTimes + " of part" + pid + ", Collecting data time: "
      + (endTimeA - startTimeA) + " Processing time: "
      + (endTimeB - startTimeB))
    // scalastyle:on println

  }

  override def lambda_GPUExecute
  (pid: Int, idArr: Array[VertexId], activeArr: Array[Boolean], attrArr: Array[SPMap]):
  (Array[VertexId], Array[SPMap], Boolean) = {

    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    val sourceArray = allSource.value
    controller = new GPUController(vertexSum, vertexCount, edgeCount, sourceArray, pid)

    val mapSize = sourceArray.length

    val vertexID = new Array[VertexId](vertexCount)
    val vertexActive = new Array[Boolean](vertexCount)
    val vertexAttr = new Array[Double](vertexCount * mapSize)

    var i = 0
    for (index <- vertexID.indices) {
      if (idArr(index) == -1) {
      }
      else {
        vertexID(i) = idArr(index)
        vertexActive(i) = activeArr(index)
        for (mapIndex <- allSource.value.indices) {
          vertexAttr(i * mapSize + mapIndex) =
            attrArr(index).getOrElse(sourceArray(mapIndex), Int.MaxValue)
        }
        i += 1
      }
    }

    controller.GPUMsgExecute(vertexID, vertexActive, vertexAttr, vertexCount)

  }

  override def lambda_VertexIntoGPU
  (pid: Int, idArr: Array[VertexId], activeArr: Array[Boolean], attrArr: Array[SPMap]):
  (Boolean, Int) = {

    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    val sourceArray = allSource.value
    controller = new GPUController(vertexSum, vertexCount, edgeCount, sourceArray, pid)

    val mapSize = sourceArray.length

    val vertexID = new Array[VertexId](vertexCount)
    val vertexActive = new Array[Boolean](vertexCount)
    val vertexAttr = new Array[Double](vertexCount * mapSize)

    var i = 0
    for (index <- vertexID.indices) {
      if (idArr(index) == -1) {
      }
      else {
        vertexID(i) = idArr(index)
        vertexActive(i) = activeArr(index)
        for (mapIndex <- allSource.value.indices) {
          vertexAttr(i * mapSize + mapIndex) =
            attrArr(index).getOrElse(sourceArray(mapIndex), Int.MaxValue)
        }
        i += 1
      }
    }

    controller.VertexIntoGPU(vertexID, vertexActive, vertexAttr, vertexCount)

  }

  override def lambda_getMessages
  (pid: Int): (Array[VertexId], Array[SPMap]) = {
    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    val sourceArray = allSource.value
    controller = new GPUController(vertexSum, vertexCount, edgeCount, sourceArray, pid)

    controller.getGPUMessages(vertexCount)
  }

  override def lambda_getOldMessages
  (pid: Int): (Array[VertexId], Array[Boolean], Array[Int], Array[SPMap]) = {
    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    val sourceArray = allSource.value
    controller = new GPUController(vertexSum, vertexCount, edgeCount, sourceArray, pid)

    controller.getOldMergedGPUMessages(vertexCount)
  }

  override def lambda_SkipVertexIntoGPU
  (pid: Int, iterTimes: Int): (Boolean, Int) = {
    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    val sourceArray = allSource.value
    controller = new GPUController(vertexSum, vertexCount, edgeCount, sourceArray, pid)

    controller.skipVertexIntoGPU(vertexCount, iterTimes)
  }

  override def lambda_globalOldMsgReduceFunc
  (v1: (Boolean, Int, SPMap), v2: (Boolean, Int, SPMap)): (Boolean, Int, SPMap) = {

    // for two same messages, detect last modified timestamp
    if (v1._3 == v2._3) {
      if (v1._2 > v2._2) {
        v2
      }
      else {
        v1
      }
    }

    // for different, check if merged result is same as someone
    else {
      val result = (v1._3.keySet ++ v2._3.keySet).map {
        k => k -> math.min(v1._3.getOrElse(k, Double.MaxValue), v2._3.getOrElse(k, Double.MaxValue))
      }.toMap

      if (result == v1._3) {
        v1
      }
      else if (result == v2._3) {
        v2
      }
      // different from both, then trigger iter must active this vertex, timestamp has no meaning
      else (true, 0, result)
    }

  }

}
