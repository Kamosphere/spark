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

package org.apache.spark.graphx.util.collection.shmManager.shmLineWriter

import org.apache.spark.graphx.VertexId
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriter
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.shmWriterPackager

class shmVertexWriter[VD]
(ident: Array[String], pid: Int,
 initFunc: (Array[String], Int) => (Array[shmArrayWriter], shmWriterPackager),
 writeFunc: (VertexId, Boolean, VD, Array[shmArrayWriter]) => Unit) {

  var modifiedVertexAmount : Int = 0

  val (writer, namePackager) : (Array[shmArrayWriter], shmWriterPackager) = initFunc(ident, pid)

  def input(vid: VertexId, activeness: Boolean, data: VD): Unit = {
    writeFunc(vid, activeness, data, writer)
    modifiedVertexAmount = modifiedVertexAmount + 1
  }

  def returnPackager(): shmWriterPackager = namePackager

}
