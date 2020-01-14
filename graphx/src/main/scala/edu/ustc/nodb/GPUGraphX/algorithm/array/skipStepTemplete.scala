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

package edu.ustc.nodb.GPUGraphX.algorithm.array

import org.apache.spark.graphx.VertexId

trait skipStepTemplete [VD, ED, A] extends Serializable {

  def lambda_VertexIntoGPU
  (pid: Int, idArr: Array[VertexId], activeArr: Array[Boolean], vertexAttr: Array[VD]):
  (Boolean, Int)

  def lambda_getMessages
  (pid: Int): (Array[VertexId], Array[A])

  def lambda_getOldMessages
  (pid: Int): (Array[VertexId], Array[Boolean], Array[Int], Array[A])

  def lambda_SkipVertexIntoGPU
  (pid: Int, iterTimes: Int): (Boolean, Int)

  def lambda_globalOldMsgReduceFunc
  (v1: (Boolean, Int, A), v2: (Boolean, Int, A)): (Boolean, Int, A)

}
