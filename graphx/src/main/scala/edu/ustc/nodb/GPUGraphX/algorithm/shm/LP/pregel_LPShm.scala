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

package edu.ustc.nodb.GPUGraphX.algorithm.shm.LP

import edu.ustc.nodb.GPUGraphX.algorithm.LPAPair
import edu.ustc.nodb.GPUGraphX.algorithm.shm.algoShmTemplete
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriter
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriterImpl.{shmArrayWriterBoolean, shmArrayWriterDouble, shmArrayWriterLong}
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.shmWriterPackager
import org.apache.spark.util.LongAccumulator
import org.apache.spark.util.collection.BitSet

class pregel_LPShm(shmIdentifier: Array[String],
                   vertexSum: Long,
                   edgeSum: Long,
                   parts: Int) extends algoShmTemplete[(VertexId, Long), Double, LPAPair] {

  var controller: GPUControllerShm = _

  override var initSource: Array[VertexId] = _

  override var partitionInnerData : collection.Map[Int, (Int, Int)] = _

  override var identifier: Array[String] = shmIdentifier

  override def fillPartitionInnerData(newMap: collection.Map[Int, (Int, Int)]) : Unit = {
    partitionInnerData = newMap
  }

  override def repartition
  (g: Graph[(VertexId, Long), Double]): Graph[(VertexId, Long), Double] = {
    g
  }

  override def lambda_initMessage: LPAPair = {
    Map[VertexId, Long]()
  }

  override def lambda_globalVertexMergeFunc
  (v1: VertexId, attr: (VertexId, Long), message: LPAPair) : (VertexId, Long) = {
    if (message.isEmpty) attr else message.maxBy(_._2)
  }

  override def lambda_globalReduceFunc
  (count1: LPAPair, count2: LPAPair): LPAPair = {
    (count1.keySet ++ count2.keySet).map { i =>
      val count1Val = count1.getOrElse(i, 0L)
      val count2Val = count2.getOrElse(i, 0L)
      i -> (count1Val + count2Val)
    }(collection.breakOut)
  }

  override def lambda_edgeImport
  (pid: Int, iter: Iterator[Edge[Double]])
  (iterTimes: Int,
   countOutDegree: collection.Map[VertexId, Int],
   counter: LongAccumulator):
  Unit = {

    val startTimeA = System.nanoTime()

    // pre allocate size
    val preParameter = partitionInnerData.get(pid)
    val preVertexLength = preParameter.get._1
    val preEdgeLength = preParameter.get._2

    // edge data array
    val pEdgeSrcIDShm = new shmArrayWriterLong(pid, preEdgeLength, "EdgeSrc")
    val pEdgeDstIDShm = new shmArrayWriterLong(pid, preEdgeLength, "EdgeDst")
    val pEdgeAttrShm = new shmArrayWriterDouble(pid, preEdgeLength, "EdgeAttr")
    // used to remove the abundant vertices and record outDegree
    val VertexNumList = new mutable.HashMap[Long, Int]

    var temp : Edge[Double] = null

    var EdgeCount = 0

    while(iter.hasNext) {
      temp = iter.next()

      pEdgeSrcIDShm.shmArrayWriterSet(temp.srcId)
      pEdgeDstIDShm.shmArrayWriterSet(temp.dstId)
      pEdgeAttrShm.shmArrayWriterSet(temp.attr)
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

    controller = new GPUControllerShm(vertexSum, preVertexLength, EdgeCount, pid)

    controller.GPUEnvEdgeInit(filteredVertex.toArray,
      pEdgeSrcIDShm.shmWriterClose(),
      pEdgeDstIDShm.shmWriterClose(),
      pEdgeAttrShm.shmWriterClose())

    val endTimeB = System.nanoTime()
    // scalastyle:off println
    println("In iter " + iterTimes + " of part" + pid + ", Collecting data time: "
      + (endTimeA - startTimeA) + " Processing time: "
      + (endTimeB - startTimeB))
    // scalastyle:on println

  }

  override def lambda_GPUExecute
  (pid: Int, writer: shmWriterPackager, modifiedVertexAmount: Int,
   global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int]):
  (BitSet, Array[LPAPair], Boolean) = {

    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    controller = new GPUControllerShm(vertexSum, vertexCount, edgeCount, pid)

    val vertexIdIdentifier = writer.getNameByIndex(0)
    val vertexActiveIdentifier = writer.getNameByIndex(1)
    val vertexAttrKeyIdentifier = writer.getNameByIndex(2)
    val vertexAttrValueIdentifier = writer.getNameByIndex(3)

    controller.GPUMsgExecute(vertexIdIdentifier, vertexActiveIdentifier,
      vertexAttrKeyIdentifier, vertexAttrValueIdentifier,
      modifiedVertexAmount, global2local)

  }

  override def lambda_shmInit(ident: Array[String], pid: Int):
  (Array[shmArrayWriter], shmWriterPackager) = {

    val vertexCount = partitionInnerData(pid)._1

    val t = new Array[shmArrayWriter](4)
    t(0) = new shmArrayWriterLong(pid, vertexCount, ident(0))
    t(1) = new shmArrayWriterBoolean(pid, vertexCount, ident(1))
    t(2) = new shmArrayWriterLong(pid, vertexCount, ident(2))
    t(3) = new shmArrayWriterLong(pid, vertexCount, ident(3))

    val d = new shmWriterPackager(4)
    d.addName(t(0).shmName, vertexCount)
    d.addName(t(1).shmName, vertexCount)
    d.addName(t(2).shmName, vertexCount)
    d.addName(t(3).shmName, vertexCount)

    (t, d)
  }

  override def lambda_shmWrite(vid: VertexId, activeness: Boolean,
                               vertexAttr: (VertexId, Long), writer: Array[shmArrayWriter])
  : Unit = {

    writer(0) match {
      case longWriter: shmArrayWriterLong => longWriter.shmArrayWriterSet(vid)
    }
    writer(1) match {
      case booleanWriter: shmArrayWriterBoolean => booleanWriter.shmArrayWriterSet(activeness)
    }
    writer(2) match {
      case longWriter: shmArrayWriterLong => longWriter.shmArrayWriterSet(vertexAttr._1)
    }
    writer(3) match {
      case longWriter: shmArrayWriterLong => longWriter.shmArrayWriterSet(vertexAttr._2)
    }

  }

}
