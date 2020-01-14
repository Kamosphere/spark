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

import edu.ustc.nodb.GPUGraphX.algorithm
import scala.sys.process.Process

import org.apache.spark.graphx.{Edge, VertexId}
import org.apache.spark.util.LongAccumulator


trait packagerTemplete [VD, ED, A] extends Serializable {

  def lambda_edgeImport
  (pid: Int, iter: Iterator[Edge[ED]])
  (iterTimes: Int,
   countOutDegree: collection.Map[VertexId, Int],
   counter: LongAccumulator):
  Unit

  def lambda_GPUExecute
  (pid: Int, idArr: Array[VertexId], activeArr: Array[Boolean], vertexAttr: Array[VD]):
  (Array[VertexId], Array[A], Boolean)

  def lambda_shutDown
  (pid: Int, iter: Iterator[(VertexId, VD)]):
  Unit = {

    var runningScript = ""

    // running script to close server in c++

    // diff in executing environment
    if (algorithm.controller == 0) {
      runningScript = "/usr/local/ssspexample/cpp_native/Graph_Algo/test/"
    }
    else {
      runningScript = "./cpp_native/Graph_Algo/test/"
    }

    runningScript += "ipcrm.sh"

    Process(runningScript).run()
  }

}
