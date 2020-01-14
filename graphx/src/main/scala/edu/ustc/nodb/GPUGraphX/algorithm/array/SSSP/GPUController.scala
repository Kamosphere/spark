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

import java.util

import edu.ustc.nodb.GPUGraphX.algorithm
import edu.ustc.nodb.GPUGraphX.algorithm.SPMap
import scala.collection.mutable.ArrayBuffer
import scala.sys.process.Process

import org.apache.spark.graphx.VertexId
import org.apache.spark.internal.Logging

class GPUController(vertexSum: Long,
                    vertexCount: Int,
                    edgeCount: Int,
                    sourceList: ArrayBuffer[VertexId],
                    pid: Int) extends Serializable with Logging{

  // scalastyle:off println

  // native interface
  val native = new GPUNative

  def this(pid: Int) = this(0, 0, 0, new ArrayBuffer[VertexId], pid)

  val sourceSize: Int = sourceList.length

  val resultID : Array[Long] = new Array[Long](vertexCount)
  val resultAttr : Array[Double] = new Array[Double](vertexCount * sourceSize)

  System.loadLibrary("SSSP_lib")

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
                     EdgeSrc: Array[VertexId],
                     EdgeDst: Array[VertexId],
                     EdgeAttr: Array[Double]):
  Unit = {

    GPUShutdown()

    GPUServerActive()

    // initialize the source id array
    val sourceId = new util.ArrayList[Long](sourceList.length + (sourceList.length >> 1))
    for(unit <- sourceList) {
      sourceId.add(unit)
    }

    native.nativeEnvEdgeInit(
        filteredVertex, vertexSum, EdgeSrc, EdgeDst, EdgeAttr, sourceId, pid)

  }

  // execute SSSP algorithm
  def GPUMsgExecute(VertexID: Array[Long],
                    VertexActive: Array[Boolean],
                    VertexAttr: Array[Double],
                    vertexCount: Int):
  (Array[VertexId], Array[SPMap], Boolean) = {

    val startTime = System.nanoTime()
    // pass vertices through JNI and get result array back
    var underIndex = native.nativeStepMsgExecute(vertexSum,
      VertexID, VertexActive, VertexAttr,
      vertexCount, edgeCount, sourceSize, pid,
      resultID, resultAttr)

    val needCombine = if (underIndex <= 0) false else true
    underIndex = math.abs(underIndex)

    val endTime = System.nanoTime()
    val startTime2 = System.nanoTime()

    val results = vertexAttrPackage(underIndex)

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

    (resultID, results, needCombine)

  }

  def VertexIntoGPU(VertexID: Array[Long],
                    VertexActive: Array[Boolean],
                    VertexAttr: Array[Double],
                    vertexCount: Int):
  (Boolean, Int) = {

    val startTime = System.nanoTime()
    // pass vertices through JNI and get result array back
    var underIndex = native.nativeStepVertexInput(vertexSum,
      VertexID, VertexActive, VertexAttr,
      vertexCount, edgeCount, sourceSize, pid)

    val needCombine = if (underIndex <= 0) false else true
    underIndex = math.abs(underIndex)

    val endTime = System.nanoTime()

    if (algorithm.openTimeLog) {
      println("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))

      logInfo("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))
    }

    (needCombine, underIndex)

  }

  def getGPUMessages(vertexCount: Int):
  (Array[VertexId], Array[SPMap]) = {

    val startTime = System.nanoTime()

    // pass vertices through JNI and get array back
    val underIndex = native.nativeStepGetMessages(vertexSum, resultID, resultAttr,
      vertexCount, edgeCount, sourceSize, pid)

    val results = vertexAttrPackage(underIndex)
    val endTime = System.nanoTime()

    if (algorithm.openTimeLog) {
      println("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime - startTime))

      logInfo("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime - startTime))
    }

    (resultID, results)
  }

  def getOldMergedGPUMessages(vertexCount: Int):
  (Array[VertexId], Array[Boolean], Array[Int], Array[SPMap]) = {

    val startTime = System.nanoTime()

    val resultActive = new Array[Boolean](vertexCount)
    val resultTimeStamp = new Array[Int](vertexCount)

    // pass vertices through JNI and get array back
    val underIndex = native.nativeStepGetOldMessages(vertexSum,
      resultID, resultActive, resultTimeStamp, resultAttr,
      vertexCount, edgeCount, sourceSize, pid)

    val results = vertexAttrPackage(underIndex)
    val endTime = System.nanoTime()

    if (algorithm.openTimeLog) {
      println("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime - startTime))

      logInfo("In partition " + pid +
        ", (PackagingTime) Time for packaging in JVM: " + (endTime - startTime))
    }

    (resultID, resultActive, resultTimeStamp, results)
  }

  // execute algorithm in GPU itself
  def skipVertexIntoGPU(vertexCount: Int, iterTimes: Int):
  (Boolean, Int) = {

    val startTime = System.nanoTime()

    // pass vertices through JNI and get array back
    var underIndex = native.nativeSkipVertexInput(vertexSum,
      vertexCount, edgeCount, sourceSize, pid, iterTimes)

    val needCombine = if (underIndex <= 0) false else true
    underIndex = math.abs(underIndex)

    val endTime = System.nanoTime()

    if (algorithm.openTimeLog) {
      println("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))

      logInfo("In partition " + pid +
        ", (GPUEnvTime) Time for executing from GPU env: " + (endTime - startTime))
    }

    (needCombine, underIndex)
  }

  def vertexAttrPackage(underIndex: Int):
  Array[SPMap] = {

    val results = new Array[SPMap](underIndex)

    var tempVertexAttr : SPMap = null

    for(i <- 0 until underIndex) {

      // package the vertex attr
      tempVertexAttr = makeMap()
      var invalidDetector = 0.0
      for(j <- sourceList.indices) {
        invalidDetector = resultAttr(i * sourceSize + j)
        if(invalidDetector < Int.MaxValue) {
          tempVertexAttr.+=((sourceList(j), resultAttr(i * sourceSize + j)))
        }
      }

      results(i) = tempVertexAttr
    }

    results
  }

  private def makeMap(x: (VertexId, Double)*) = Map(x: _*)

  // after executing, close the server
  def GPUShutdown(): Boolean = {

    native.nativeEnvClose(pid)

  }

  // scalastyle:on println
}
