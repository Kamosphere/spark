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

package org.apache.spark.graphx.util.collection.shmManager.shmNamePackager

import java.nio.file.{Files, Paths}

import org.apache.spark.graphx.util.collection.shmManager.shmPackager

class shmWriterPackager(maxSize: Int) extends shmPackager(maxSize) {

  def addName(shmName: String, counter: Int): Boolean = {

    val pathExist = Files.exists(Paths.get("/dev/shm/" + shmName))

    if (! pathExist) false
    else if (isFull) false
    else {

      shmNameArr(pointer) = shmName
      shmSizeArr(pointer) = counter

      pointer = pointer + 1
      if(pointer == maxSize) isFull = true

      true

    }
  }

  def getNameByIndex(index: Int): String = {

    require(index < maxSize)

    shmNameArr(index)

  }

  def getSizeByIndex(index: Int): Int = {

    require(index < maxSize)

    shmSizeArr(index)

  }

  def getCapacity : Int = pointer
}
