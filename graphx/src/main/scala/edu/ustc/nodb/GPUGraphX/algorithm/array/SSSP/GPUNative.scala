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

import org.apache.spark.graphx.VertexId

class GPUNative extends Serializable {

  /* the GPU-based method to execute the SSSP algorithm
  possibly modified for template */

  // native function to init the edge
  @native def nativeEnvEdgeInit(filteredVertex: Array[Long],
                                vertexSum: Long,
                                EdgeSrc: Array[VertexId],
                                EdgeDst: Array[VertexId],
                                EdgeAttr: Array[Double],
                                sourceId: util.ArrayList[Long],
                                pid: Int):
  Boolean

  // native function to execute algorithm
  @native def nativeStepMsgExecute(vertexSum: Long,
                                   VertexID: Array[Long],
                                   VertexActive: Array[Boolean],
                                   VertexAttr: Array[Double],
                                   vertexCount: Int,
                                   edgeCount: Int,
                                   markIdSize: Int,
                                   pid: Int,
                                   resultID: Array[Long],
                                   resultAttr: Array[Double]):
  Int

  // native function to execute algorithm
  @native def nativeStepVertexInput(vertexSum: Long,
                                    VertexID: Array[Long],
                                    VertexActive: Array[Boolean],
                                    VertexAttr: Array[Double],
                                    vertexCount: Int,
                                    edgeCount: Int,
                                    markIdSize: Int,
                                    pid: Int):
  Int

  // native function to fetch message in executing
  @native def nativeStepGetMessages(vertexSum: Long,
                                    resultID: Array[Long],
                                    resultAttr: Array[Double],
                                    vertexCount: Int,
                                    edgeCount: Int,
                                    markIdSize: Int,
                                    pid: Int):
  Int

  // native function to fetch merged vertex info after several times executing
  @native def nativeStepGetOldMessages(vertexSum: Long,
                                       resultID: Array[Long],
                                       resultActive: Array[Boolean],
                                       resultTimeStamp: Array[Int],
                                       resultAttr: Array[Double],
                                       vertexCount: Int,
                                       edgeCount: Int,
                                       markIdSize: Int,
                                       pid: Int):
  Int

  // native function to execute algorithm when iter skipped
  @native def nativeSkipVertexInput(vertexSum: Long,
                                    vertexCount: Int,
                                    edgeCount: Int,
                                    markIdSize: Int,
                                    pid: Int,
                                    iterTimes: Int):
  Int

  // native function to close server
  @native def nativeEnvClose(pid: Int):
  Boolean

}
