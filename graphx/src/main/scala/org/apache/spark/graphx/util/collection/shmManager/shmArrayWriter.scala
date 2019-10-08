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

import java.nio.MappedByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{Files, Paths, StandardOpenOption}

abstract class shmArrayWriter(preAllocateSize: Int)
  extends shmArrayPrototype(preAllocateSize) {

  var dataTypeSize : Int

  var shmName : String

  lazy val channel : FileChannel
  = Files.newByteChannel(Paths.get("/dev/shm/" + shmName),
    StandardOpenOption.READ, StandardOpenOption.WRITE, StandardOpenOption.CREATE)
    .asInstanceOf[FileChannel]

  lazy val buffer : MappedByteBuffer
  = channel.map(FileChannel.MapMode.READ_WRITE, 0, preAllocateSize * dataTypeSize)


  def shmWriterClose(): String = {
    // write the remained data
    buffer.force()
    if (channel.isOpen) {
      channel.close()
    }

    shmName
  }

  def shmArrayWriterSet(data: T): Unit
}
