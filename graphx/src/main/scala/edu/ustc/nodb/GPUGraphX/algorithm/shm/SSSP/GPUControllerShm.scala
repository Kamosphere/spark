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

package edu.ustc.nodb.GPUGraphX.algorithm.shm.SSSP

import java.nio.file.{Files, Path, Paths}
import java.util

import edu.ustc.nodb.GPUGraphX.algorithm
import edu.ustc.nodb.GPUGraphX.algorithm.SPMap
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.Process

import org.apache.spark.graphx.VertexId
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.util.collection.shmManager.shmArrayReaderImpl.{shmArrayReaderDouble, shmArrayReaderLong}
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.{shmReaderPackager, shmWriterPackager}
import org.apache.spark.internal.Logging
import org.apache.spark.util.collection.BitSet

class GPUControllerShm(vertexSum: Long,
                       vertexCount: Int,
                       edgeCount: Int,
                       sourceList: ArrayBuffer[VertexId],
                       pid: Int) extends Serializable with Logging{

  // scalastyle:off println

  private def makeMap(x: (VertexId, Double)*) = Map(x: _*)

  def this(pid: Int) = this(0, 0, 0, new ArrayBuffer[VertexId], pid)

  // native interface
  val native = new GPUNativeShm

  val sourceSize: Int = sourceList.length

  System.loadLibrary("SSSP_lib_Shm")

  def GPUServerActive():
  Unit = {

    var runningScript = ""

    // running script to activate server in c++

    // diff in executing environment
    if (algorithm.controller == 0) {
      runningScript = "/usr/local/ssspexample/cpp_native/build/bin/"
    }
    else {
      runningScript = "./cpp_native/build/bin/"
    }

    // diff in executor
    if (algorithm.runningScriptType == 0) {
      runningScript += "srv_UtilServerTest_BellmanFord "
    }
    else {
      runningScript += "srv_UtilServerTest_BellmanFordGPU "
    }

    runningScript += vertexSum.toString + " " + edgeCount.toString + " " +
      sourceList.length.toString + " " + pid.toString

    Process(runningScript).run()

  }

  // before executing, run the server first
  def GPUEnvEdgeInit(filteredVertex: Array[Long],
                     EdgeSrc: String,
                     EdgeDst: String,
                     EdgeAttr: String):
  Unit = {

    GPUShutdown(0)

    GPUServerActive()

    // initialize the source id array
    val sourceId = new util.ArrayList[Long](sourceList.length + (sourceList.length >> 1))
    for(unit <- sourceList) {
      sourceId.add(unit)
    }

    val shmReader = new shmReaderPackager(3)

    shmReader.addName(EdgeSrc, edgeCount)
    shmReader.addName(EdgeDst, edgeCount)
    shmReader.addName(EdgeAttr, edgeCount)

    native.nativeEnvEdgeInit(filteredVertex, vertexSum, sourceId, pid, shmReader)

  }

  // execute SSSP algorithm
  def GPUMsgExecute(VertexID: String,
                    VertexActive: String,
                    VertexAttr: String,
                    modifiedVertexAmount: Int,
                    global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int]):
  (BitSet, Array[SPMap], Boolean) = {

    // create reader list to get input array in shm
    val shmReader = new shmReaderPackager(3)

    shmReader.addName(VertexID, modifiedVertexAmount)
    shmReader.addName(VertexActive, modifiedVertexAmount)
    shmReader.addName(VertexAttr, modifiedVertexAmount * sourceSize)

    // create writer list to return shm file names
    val shmWriter = new shmWriterPackager(2)

    val startTime = System.nanoTime()

    // pass reader and writer through JNI
    var underIndex = native.nativeStepMsgExecute(vertexSum,
      shmReader, shmWriter,
      vertexCount, edgeCount, sourceSize, pid)

    val needCombine = if (underIndex <= 0) false else true
    underIndex = math.abs(underIndex)

    val endTime = System.nanoTime()
    val startTime2 = System.nanoTime()

    // read files in writer list ( already written in c++ )
    val resultIDReader = new shmArrayReaderLong(
      shmWriter.getSizeByIndex(0), shmWriter.getNameByIndex(0))
    val resultAttrReader = new shmArrayReaderDouble(
      shmWriter.getSizeByIndex(1), shmWriter.getNameByIndex(1))

    val (resultBitSet, resultAttr) = vertexAttrPackage(
      underIndex, global2local, resultIDReader, resultAttrReader)

    val endTime2 = System.nanoTime()

    if (algorithm.openTimeLog) {
      println("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))
      println("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime2 - startTime2))

      logInfo("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))
      logInfo("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime2 - startTime2))
    }

    (resultBitSet, resultAttr, needCombine)

  }

  // after executing, close the server and release the shared memory
  def GPUShutdown(runningStep: Int): Boolean = {

    // 0 for the first iter, other for the times of iter
    if (runningStep == 0) {

    }
    else {
      // remove all shm files in file way
      val k = Files.newDirectoryStream( Paths.get("/dev/shm/") ).iterator()
      var pathTemp: Path = null
      while(k.hasNext) {
        pathTemp = k.next()
        Files.deleteIfExists(pathTemp)

      }
    }
    native.nativeEnvClose(pid)

  }

  def vertexAttrPackage(underIndex: Int,
                        global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
                        resultIDReader: shmArrayReaderLong,
                        resultAttrReader: shmArrayReaderDouble):
  (BitSet, Array[SPMap]) = {

    val resultBitSet = new BitSet(vertexCount)
    val resultAttr = new Array[SPMap](vertexCount)

    var tempAttr : SPMap = null
    for (i <- 0 until underIndex) {
      val globalId = resultIDReader.shmArrayReaderGetByIndex(i)
      tempAttr = makeMap()
      for (j <- 0 until sourceSize) {
        val mapValue = resultAttrReader.shmArrayReaderGetByIndex(i * sourceSize + j)
        if (mapValue < Int.MaxValue) {
          tempAttr. += ((sourceList(j), mapValue))
        }
      }
      val localId = global2local(globalId)
      resultBitSet.set(localId)
      resultAttr(localId) = tempAttr
    }

    (resultBitSet, resultAttr)
  }

  // scalastyle:on println
}
