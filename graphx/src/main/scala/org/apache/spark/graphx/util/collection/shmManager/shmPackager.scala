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

package org.apache.spark.graphx.util.collection.shmManager

abstract class shmPackager(maxSize: Int) {

  val shmNameArr : Array[String] = new Array[String](maxSize)
  val shmSizeArr : Array[Int] = new Array[Int](maxSize)

  var pointer : Int = 0
  var isFull : Boolean = false

  def addName(shmName: String, counter: Int): Boolean

  def getNameByIndex(index: Int): String

  def getSizeByIndex(index: Int): Int
}
