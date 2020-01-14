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

package org.apache.spark.graphx

import scala.reflect.ClassTag

import org.apache.spark.SparkConf
import org.apache.spark.graphx.impl._
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.util.collection.shmManager._
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.shmWriterPackager
import org.apache.spark.rdd.RDD
import org.apache.spark.util.{BoundedPriorityQueue, LongAccumulator}
import org.apache.spark.util.collection.{BitSet, OpenHashSet}

object GraphXUtils {

  /**
   * Registers classes that GraphX uses with Kryo.
   */
  def registerKryoClasses(conf: SparkConf) {
    conf.registerKryoClasses(Array(
      classOf[Edge[Object]],
      classOf[(VertexId, Object)],
      classOf[EdgePartition[Object, Object]],
      classOf[BitSet],
      classOf[VertexIdToIndexMap],
      classOf[VertexAttributeBlock[Object]],
      classOf[PartitionStrategy],
      classOf[BoundedPriorityQueue[Object]],
      classOf[EdgeDirection],
      classOf[GraphXPrimitiveKeyOpenHashMap[VertexId, Int]],
      classOf[OpenHashSet[Int]],
      classOf[OpenHashSet[Long]],
      classOf[shmArrayWriter],
      classOf[shmArrayReader],
      classOf[shmArrayPrototype],
      classOf[shmPackager]))
  }

  /**
   * A proxy method to map the obsolete API to the new one.
   */
  def mapReduceTriplets[VD: ClassTag, ED: ClassTag, A: ClassTag](
      g: Graph[VD, ED],
      mapFunc: EdgeTriplet[VD, ED] => Iterator[(VertexId, A)],
      reduceFunc: (A, A) => A,
      activeSetOpt: Option[(VertexRDD[_], EdgeDirection)] = None): VertexRDD[A] = {
    def sendMsg(ctx: EdgeContext[VD, ED, A]) {
      mapFunc(ctx.toEdgeTriplet).foreach { kv =>
        val id = kv._1
        val msg = kv._2
        if (id == ctx.srcId) {
          ctx.sendToSrc(msg)
        } else {
          assert(id == ctx.dstId)
          ctx.sendToDst(msg)
        }
      }
    }
    g.aggregateMessagesWithActiveSet(
      sendMsg, reduceFunc, TripletFields.All, activeSetOpt)
  }

  def mapReduceTripletsIntoGPU[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (g: Graph[VD, ED], counter: LongAccumulator,
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Array[VertexId], Array[A], Boolean),
   globalReduceFunc: (A, A) => A,
   activeSetOpt: Option[(VertexRDD[_], EdgeDirection)] = None):
  VertexRDD[A] = {
    g.aggregateIntoGPUWithActiveSet(counter, gpuBridgeFunc, globalReduceFunc,
      TripletFields.All, activeSetOpt)
  }

  def mapReduceTripletsIntoGPUSkip_normal[VD: ClassTag, ED: ClassTag]
  (g: Graph[VD, ED],
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Boolean, Int),
   activeSetOpt: Option[(VertexRDD[_], EdgeDirection)] = None):
  RDD[(PartitionID, (Boolean, Int))] = {
    g.aggregateIntoGPUSkipWithActiveSet(gpuBridgeFunc,
      TripletFields.All, activeSetOpt)
  }

  def mapReduceTripletsIntoGPUSkip_fetch[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (g: Graph[VD, ED],
   gpuBridgeFunc: Int
     => (Array[VertexId], Array[A]),
   globalReduceFunc: (A, A) => A):
  VertexRDD[A] = {
    g.aggregateIntoGPUSkipFetch(gpuBridgeFunc, globalReduceFunc)
  }

  def mapReduceTripletsIntoGPUSkip_fetchOldMsg[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (g: Graph[VD, ED],
   gpuBridgeFunc: Int
     => (Array[VertexId], Array[Boolean], Array[Int], Array[A]),
   globalReduceFunc: ((Boolean, Int, A), (Boolean, Int, A)) => (Boolean, Int, A)):
  VertexRDD[(Boolean, Int, A)] = {
    g.aggregateIntoGPUSkipFetchOldMsg(gpuBridgeFunc, globalReduceFunc)
  }

  def mapReduceTripletsIntoGPUSkip_skipping[VD: ClassTag, ED: ClassTag]
  (g: Graph[VD, ED], iterTimes: Int,
   gpuBridgeFunc: (Int, Int) => (Boolean, Int)):
  RDD[(PartitionID, (Boolean, Int))] = {
    g.aggregateIntoGPUSkipStep(gpuBridgeFunc, iterTimes)
  }

  def mapReduceTripletsIntoGPUInShm[VD: ClassTag, ED: ClassTag, A: ClassTag]
  (g: Graph[VD, ED], counter: LongAccumulator,
   identifierArr: Array[String],
   shmInitFunc: (Array[String], Int) => (Array[shmArrayWriter], shmWriterPackager),
   shmWriteFunc: (VertexId, Boolean, VD, Array[shmArrayWriter]) => Unit,
   gpuBridgeFunc: (Int, shmWriterPackager, Int, GraphXPrimitiveKeyOpenHashMap[VertexId, Int])
     => (BitSet, Array[A], Boolean),
   globalReduceFunc: (A, A) => A,
   activeSetOpt: Option[(VertexRDD[_], EdgeDirection)] = None):
  VertexRDD[A] = {
    g.aggregateIntoGPUShmWithActiveSet(counter, identifierArr,
      shmInitFunc, shmWriteFunc, gpuBridgeFunc, globalReduceFunc,
      TripletFields.All, activeSetOpt)
  }

  def innerPartitionVertexEdgeCount[VD: ClassTag, ED: ClassTag]
  (g: Graph[VD, ED]): collection.Map[Int, (Int, Int)] = {
    g.innerVerticesEdgesCount().collectAsMap()
  }
}
