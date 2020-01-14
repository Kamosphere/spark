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

import scala.reflect.ClassTag

import org.apache.spark.graphx.{Graph, VertexId}

abstract class algoTemplete[VD: ClassTag, ED: ClassTag, A: ClassTag] extends Serializable
with packagerTemplete[VD, ED, A] with pregelTemplete[VD, ED, A] with skipStepTemplete[VD, ED, A] {

  var partitionInnerData : collection.Map[Int, (Int, Int)]

  var initSource : Array[VertexId]

  def fillPartitionInnerData(newMap: collection.Map[Int, (Int, Int)]) : Unit

  def repartition
  (g: Graph[VD, ED]): Graph[VD, ED]

}
