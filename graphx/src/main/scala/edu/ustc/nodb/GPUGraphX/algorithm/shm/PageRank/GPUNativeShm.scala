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

import java.util

import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager._

class GPUNativeShm extends Serializable {

  /* the GPU-based method to execute the SSSP algorithm
  possibly modified for template */

  // native function to init the edge
  @native def nativeEnvEdgeInit(filteredVertex: Array[Long],
                                vertexSum: Long,
                                sourceId: util.ArrayList[Long],
                                pid: Int,
                                shmReader: shmReaderPackager):
  Boolean

  // native function to execute algorithm
  @native def nativeStepMsgExecute(vertexSum: Long,
                                   shmWriter: shmReaderPackager,
                                   shmReader: shmWriterPackager,
                                   vertexCount: Int,
                                   edgeCount: Int,
                                   markIdSize: Int,
                                   pid: Int):
  Int

  // native function to close server
  @native def nativeEnvClose(pid: Int):
  Boolean

}
