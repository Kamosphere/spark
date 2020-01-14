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

package edu.ustc.nodb.GPUGraphX.algorithm.shm.PageRank

import edu.ustc.nodb.GPUGraphX.algorithm.PRPair
import edu.ustc.nodb.GPUGraphX.algorithm.shm.algoShmTemplete
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

import org.apache.spark.graphx.{Edge, Graph, VertexId}
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriter
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriterImpl._
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.shmWriterPackager
import org.apache.spark.util.LongAccumulator
import org.apache.spark.util.collection.BitSet



class pregel_PRShm(shmIdentifier: Array[String],
                   vertexSum: Long,
                   edgeSum: Long,
                   tol: Double, resetProb: Double,
                   parts: Int,
                   allSource: Option[VertexId] = None)
  extends algoShmTemplete[PRPair, Double, Double] {

  var controller: GPUControllerShm = _

  override var initSource: Array[VertexId] = _

  val personalized : Boolean = allSource.isDefined
  val initSrc : VertexId = allSource.getOrElse(-1L)

  override var partitionInnerData : collection.Map[Int, (Int, Int)] = _

  override var identifier: Array[String] = shmIdentifier

  override def fillPartitionInnerData(newMap: collection.Map[Int, (Int, Int)]) : Unit = {
    partitionInnerData = newMap
  }

  def graphInit[VD: ClassTag, ED: ClassTag]
  (graph: Graph[VD, ED]): Graph[(Double, Double), Double] = {
    graph.outerJoinVertices(graph.outDegrees) {
      (vid, vdata, deg) => deg.getOrElse(0)
    }
      // Set the weight on the edges based on the degree
      .mapTriplets( e => 1.0 / e.srcAttr )
      // Set the vertex attributes to (initialPR, delta = 0)
      .mapVertices { (id, attr) =>
      if (id == initSrc) (0.0, Double.NegativeInfinity) else (0.0, 0.0)
    }
      .persist()
  }

  override def repartition
  (g: Graph[PRPair, Double]): Graph[PRPair, Double] = {
    g
  }

  def vertexProgram(id: VertexId, attr: (Double, Double), msgSum: Double): (Double, Double) = {
    val (oldPR, lastDelta) = attr
    val newPR = oldPR + (1.0 - resetProb) * msgSum
    (newPR, newPR - oldPR)
  }

  def personalizedVertexProgram(id: VertexId, attr: (Double, Double),
                                msgSum: Double): (Double, Double) = {
    val (oldPR, lastDelta) = attr
    val newPR = if (lastDelta == Double.NegativeInfinity) {
      1.0
    } else {
      oldPR + (1.0 - resetProb) * msgSum
    }
    (newPR, newPR - oldPR)
  }

  override def lambda_initMessage: Double = {
    if (personalized) 0.0 else resetProb / (1.0 - resetProb)
  }

  override def lambda_globalVertexMergeFunc
  (id: VertexId, attr: PRPair, msgSum: Double) : PRPair = {
    if (personalized) {
      personalizedVertexProgram(id, attr, msgSum)
    } else {
      vertexProgram(id, attr, msgSum)
    }
  }

  override def lambda_globalReduceFunc
  (v1: Double, v2: Double): Double = {
    v1 + v2
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

    controller = new GPUControllerShm(vertexSum, preVertexLength, EdgeCount, initSrc, pid)

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
  (BitSet, Array[Double], Boolean) = {

    val vertexCount = partitionInnerData(pid)._1
    val edgeCount = partitionInnerData(pid)._2

    controller = new GPUControllerShm(vertexSum, vertexCount, edgeCount, initSrc, pid)

    val vertexIdIdentifier = writer.getNameByIndex(0)
    val vertexActiveIdentifier = writer.getNameByIndex(1)
    val vertexAttrPair1Identifier = writer.getNameByIndex(2)
    val vertexAttrPair2Identifier = writer.getNameByIndex(3)

    controller.GPUMsgExecute(vertexIdIdentifier, vertexActiveIdentifier,
      vertexAttrPair1Identifier, vertexAttrPair2Identifier,
      modifiedVertexAmount, global2local)

  }

  override def lambda_shmInit(ident: Array[String], pid: Int):
  (Array[shmArrayWriter], shmWriterPackager) = {

    val vertexCount = partitionInnerData(pid)._1

    val t = new Array[shmArrayWriter](4)
    t(0) = new shmArrayWriterLong(pid, vertexCount, ident(0))
    t(1) = new shmArrayWriterBoolean(pid, vertexCount, ident(1))
    t(2) = new shmArrayWriterDouble(pid, vertexCount, ident(2))
    t(3) = new shmArrayWriterDouble(pid, vertexCount, ident(3))

    val d = new shmWriterPackager(4)
    d.addName(t(0).shmName, vertexCount)
    d.addName(t(1).shmName, vertexCount)
    d.addName(t(2).shmName, vertexCount)
    d.addName(t(3).shmName, vertexCount)

    (t, d)
  }

  override def lambda_shmWrite(vid: VertexId, activeness: Boolean, vertexAttr: PRPair,
                               writer: Array[shmArrayWriter])
  : Unit = {

    writer(0) match {
      case longWriter: shmArrayWriterLong => longWriter.shmArrayWriterSet(vid)
    }
    writer(1) match {
      case booleanWriter: shmArrayWriterBoolean => booleanWriter.shmArrayWriterSet(activeness)
    }
    writer(2) match {
      case doubleWriter: shmArrayWriterDouble => doubleWriter.shmArrayWriterSet(vertexAttr._1)
    }
    writer(3) match {
      case doubleWriter: shmArrayWriterDouble => doubleWriter.shmArrayWriterSet(vertexAttr._2)
    }

  }

}
