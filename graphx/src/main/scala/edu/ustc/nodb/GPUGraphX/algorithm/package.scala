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

import org.apache.spark.graphx.VertexId

package object algorithm {

  // Define the vertex attribute in GPU-based SSSP project
  // mutable.LinkedHashMap stored the pairs of nearest distance from landmark in order
  type SPMap = Map[VertexId, Double]

  type LPAPair = Map[VertexId, Long]

  type PRPair = (Double, Double)

  // For executing script type, 0 for CPU, other for GPU
  val runningScriptType = 1

  // 0 for running in server with standalone, other for local
  val controller : Int = 1

  // For opening time consuming record into log4j
  var openTimeLog : Boolean = false
}
