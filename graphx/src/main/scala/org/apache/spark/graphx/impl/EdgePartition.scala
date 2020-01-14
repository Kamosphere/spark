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

package org.apache.spark.graphx.impl

import java.io.{File, PrintWriter}

import scala.reflect.ClassTag

import org.apache.spark.TaskContext
import org.apache.spark.graphx._
import org.apache.spark.graphx.util.collection.GraphXPrimitiveKeyOpenHashMap
import org.apache.spark.graphx.util.collection.shmManager.shmArrayWriter
import org.apache.spark.graphx.util.collection.shmManager.shmLineWriter.shmVertexWriter
import org.apache.spark.graphx.util.collection.shmManager.shmNamePackager.{shmReaderPackager, shmWriterPackager}
import org.apache.spark.internal.Logging
import org.apache.spark.util.LongAccumulator
import org.apache.spark.util.collection.BitSet

/**
 * A collection of edges, along with referenced vertex attributes and an optional active vertex set
 * for filtering computation on the edges.
 *
 * The edges are stored in columnar format in `localSrcIds`, `localDstIds`, and `data`. All
 * referenced global vertex ids are mapped to a compact set of local vertex ids according to the
 * `global2local` map. Each local vertex id is a valid index into `vertexAttrs`, which stores the
 * corresponding vertex attribute, and `local2global`, which stores the reverse mapping to global
 * vertex id. The global vertex ids that are active are optionally stored in `activeSet`.
 *
 * The edges are clustered by source vertex id, and the mapping from global vertex id to the index
 * of the corresponding edge cluster is stored in `index`.
 *
 * @tparam ED the edge attribute type
 * @tparam VD the vertex attribute type
 *
 * @param localSrcIds the local source vertex id of each edge as an index into `local2global` and
 *   `vertexAttrs`
 * @param localDstIds the local destination vertex id of each edge as an index into `local2global`
 *   and `vertexAttrs`
 * @param data the attribute associated with each edge
 * @param index a clustered index on source vertex id as a map from each global source vertex id to
 *   the offset in the edge arrays where the cluster for that vertex id begins
 * @param global2local a map from referenced vertex ids to local ids which index into vertexAttrs
 * @param local2global an array of global vertex ids where the offsets are local vertex ids
 * @param vertexAttrs an array of vertex attributes where the offsets are local vertex ids
 * @param activeSet an optional active vertex set for filtering computation on the edges
 */
// private[graphx]
class EdgePartition[
    @specialized(Char, Int, Boolean, Byte, Long, Float, Double) ED: ClassTag, VD: ClassTag](
    localSrcIds: Array[Int],
    localDstIds: Array[Int],
    data: Array[ED],
    index: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
    global2local: GraphXPrimitiveKeyOpenHashMap[VertexId, Int],
    local2global: Array[VertexId],
    vertexAttrs: Array[VD],
    activeSet: Option[VertexSet])
  extends Serializable with Logging{

  /** No-arg constructor for serialization. */
  private def this() = this(null, null, null, null, null, null, null, null)

  /** Return a new `EdgePartition` with the specified edge data. */
  def withData[ED2: ClassTag](data: Array[ED2]): EdgePartition[ED2, VD] = {
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, vertexAttrs, activeSet)
  }

  /** Return a new `EdgePartition` with the specified active set, provided as an iterator. */
  def withActiveSet(iter: Iterator[VertexId]): EdgePartition[ED, VD] = {
    val activeSet = new VertexSet
    while (iter.hasNext) { activeSet.add(iter.next()) }
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, vertexAttrs,
      Some(activeSet))
  }

  /** Return a new `EdgePartition` with updates to vertex attributes specified in `iter`. */
  def updateVertices(iter: Iterator[(VertexId, VD)]): EdgePartition[ED, VD] = {
    val newVertexAttrs = new Array[VD](vertexAttrs.length)
    System.arraycopy(vertexAttrs, 0, newVertexAttrs, 0, vertexAttrs.length)
    while (iter.hasNext) {
      val kv = iter.next()
      newVertexAttrs(global2local(kv._1)) = kv._2
    }
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, newVertexAttrs,
      activeSet)
  }

  /** Return a new `EdgePartition` without any locally cached vertex attributes. */
  def withoutVertexAttributes[VD2: ClassTag](): EdgePartition[ED, VD2] = {
    val newVertexAttrs = new Array[VD2](vertexAttrs.length)
    new EdgePartition(
      localSrcIds, localDstIds, data, index, global2local, local2global, newVertexAttrs,
      activeSet)
  }

  @inline private def srcIds(pos: Int): VertexId = local2global(localSrcIds(pos))

  @inline private def dstIds(pos: Int): VertexId = local2global(localDstIds(pos))

  @inline private def attrs(pos: Int): ED = data(pos)

  /** Look up vid in activeSet, throwing an exception if it is None. */
  def isActive(vid: VertexId): Boolean = {
    activeSet.get.contains(vid)
  }

  /** The number of active vertices, if any exist. */
  def numActives: Option[Int] = activeSet.map(_.size)

  /**
   * Reverse all the edges in this partition.
   *
   * @return a new edge partition with all edges reversed.
   */
  def reverse: EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet, size)
    var i = 0
    while (i < size) {
      val localSrcId = localSrcIds(i)
      val localDstId = localDstIds(i)
      val srcId = local2global(localSrcId)
      val dstId = local2global(localDstId)
      val attr = data(i)
      builder.add(dstId, srcId, localDstId, localSrcId, attr)
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * Construct a new edge partition by applying the function f to all
   * edges in this partition.
   *
   * Be careful not to keep references to the objects passed to `f`.
   * To improve GC performance the same object is re-used for each call.
   *
   * @param f a function from an edge to a new attribute
   * @tparam ED2 the type of the new attribute
   * @return a new edge partition with the result of the function `f`
   *         applied to each edge
   */
  def map[ED2: ClassTag](f: Edge[ED] => ED2): EdgePartition[ED2, VD] = {
    val newData = new Array[ED2](data.length)
    val edge = new Edge[ED]()
    val size = data.length
    var i = 0
    while (i < size) {
      edge.srcId = srcIds(i)
      edge.dstId = dstIds(i)
      edge.attr = data(i)
      newData(i) = f(edge)
      i += 1
    }
    this.withData(newData)
  }

  /**
   * Construct a new edge partition by using the edge attributes
   * contained in the iterator.
   *
   * @note The input iterator should return edge attributes in the
   * order of the edges returned by `EdgePartition.iterator` and
   * should return attributes equal to the number of edges.
   *
   * @param iter an iterator for the new attribute values
   * @tparam ED2 the type of the new attribute
   * @return a new edge partition with the attribute values replaced
   */
  def map[ED2: ClassTag](iter: Iterator[ED2]): EdgePartition[ED2, VD] = {
    // Faster than iter.toArray, because the expected size is known.
    val newData = new Array[ED2](data.length)
    var i = 0
    while (iter.hasNext) {
      newData(i) = iter.next()
      i += 1
    }
    assert(newData.length == i)
    this.withData(newData)
  }

  /**
   * Construct a new edge partition containing only the edges matching `epred` and where both
   * vertices match `vpred`.
   */
  def filter(
      epred: EdgeTriplet[VD, ED] => Boolean,
      vpred: (VertexId, VD) => Boolean): EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var i = 0
    while (i < size) {
      // The user sees the EdgeTriplet, so we can't reuse it and must create one per edge.
      val localSrcId = localSrcIds(i)
      val localDstId = localDstIds(i)
      val et = new EdgeTriplet[VD, ED]
      et.srcId = local2global(localSrcId)
      et.dstId = local2global(localDstId)
      et.srcAttr = vertexAttrs(localSrcId)
      et.dstAttr = vertexAttrs(localDstId)
      et.attr = data(i)
      if (vpred(et.srcId, et.srcAttr) && vpred(et.dstId, et.dstAttr) && epred(et)) {
        builder.add(et.srcId, et.dstId, localSrcId, localDstId, et.attr)
      }
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * Apply the function f to all edges in this partition.
   *
   * @param f an external state mutating user defined function.
   */
  def foreach(f: Edge[ED] => Unit) {
    iterator.foreach(f)
  }

  /**
   * Merge all the edges with the same src and dest id into a single
   * edge using the `merge` function
   *
   * @param merge a commutative associative merge operation
   * @return a new edge partition without duplicate edges
   */
  def groupEdges(merge: (ED, ED) => ED): EdgePartition[ED, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var currSrcId: VertexId = null.asInstanceOf[VertexId]
    var currDstId: VertexId = null.asInstanceOf[VertexId]
    var currLocalSrcId = -1
    var currLocalDstId = -1
    var currAttr: ED = null.asInstanceOf[ED]
    // Iterate through the edges, accumulating runs of identical edges using the curr* variables and
    // releasing them to the builder when we see the beginning of the next run
    var i = 0
    while (i < size) {
      if (i > 0 && currSrcId == srcIds(i) && currDstId == dstIds(i)) {
        // This edge should be accumulated into the existing run
        currAttr = merge(currAttr, data(i))
      } else {
        // This edge starts a new run of edges
        if (i > 0) {
          // First release the existing run to the builder
          builder.add(currSrcId, currDstId, currLocalSrcId, currLocalDstId, currAttr)
        }
        // Then start accumulating for a new run
        currSrcId = srcIds(i)
        currDstId = dstIds(i)
        currLocalSrcId = localSrcIds(i)
        currLocalDstId = localDstIds(i)
        currAttr = data(i)
      }
      i += 1
    }
    // Finally, release the last accumulated run
    if (size > 0) {
      builder.add(currSrcId, currDstId, currLocalSrcId, currLocalDstId, currAttr)
    }
    builder.toEdgePartition
  }

  /**
   * Apply `f` to all edges present in both `this` and `other` and return a new `EdgePartition`
   * containing the resulting edges.
   *
   * If there are multiple edges with the same src and dst in `this`, `f` will be invoked once for
   * each edge, but each time it may be invoked on any corresponding edge in `other`.
   *
   * If there are multiple edges with the same src and dst in `other`, `f` will only be invoked
   * once.
   */
  def innerJoin[ED2: ClassTag, ED3: ClassTag]
      (other: EdgePartition[ED2, _])
      (f: (VertexId, VertexId, ED, ED2) => ED3): EdgePartition[ED3, VD] = {
    val builder = new ExistingEdgePartitionBuilder[ED3, VD](
      global2local, local2global, vertexAttrs, activeSet)
    var i = 0
    var j = 0
    // For i = index of each edge in `this`...
    while (i < size && j < other.size) {
      val srcId = this.srcIds(i)
      val dstId = this.dstIds(i)
      // ... forward j to the index of the corresponding edge in `other`, and...
      while (j < other.size && other.srcIds(j) < srcId) { j += 1 }
      if (j < other.size && other.srcIds(j) == srcId) {
        while (j < other.size && other.srcIds(j) == srcId && other.dstIds(j) < dstId) { j += 1 }
        if (j < other.size && other.srcIds(j) == srcId && other.dstIds(j) == dstId) {
          // ... run `f` on the matching edge
          builder.add(srcId, dstId, localSrcIds(i), localDstIds(i),
            f(srcId, dstId, this.data(i), other.attrs(j)))
        }
      }
      i += 1
    }
    builder.toEdgePartition
  }

  /**
   * The number of edges in this partition
   *
   * @return size of the partition
   */
  val size: Int = localSrcIds.length

  /** The number of unique source vertices in the partition. */
  def indexSize: Int = index.size

  /** The number of all related vertices in the partition. */
  def allVertexSize: Int = vertexAttrs.length

  /**
   * Get an iterator over the edges in this partition.
   *
   * Be careful not to keep references to the objects from this iterator.
   * To improve GC performance the same object is re-used in `next()`.
   *
   * @return an iterator over edges in the partition
   */
  def iterator: Iterator[Edge[ED]] = new Iterator[Edge[ED]] {
    private[this] val edge = new Edge[ED]
    private[this] var pos = 0

    override def hasNext: Boolean = pos < EdgePartition.this.size

    override def next(): Edge[ED] = {
      edge.srcId = srcIds(pos)
      edge.dstId = dstIds(pos)
      edge.attr = data(pos)
      pos += 1
      edge
    }
  }

  /**
   * Get an iterator over the edge triplets in this partition.
   *
   * It is safe to keep references to the objects from this iterator.
   */
  def tripletIterator(
      includeSrc: Boolean = true, includeDst: Boolean = true)
      : Iterator[EdgeTriplet[VD, ED]] = new Iterator[EdgeTriplet[VD, ED]] {
    private[this] var pos = 0

    override def hasNext: Boolean = pos < EdgePartition.this.size

    override def next(): EdgeTriplet[VD, ED] = {
      val triplet = new EdgeTriplet[VD, ED]
      val localSrcId = localSrcIds(pos)
      val localDstId = localDstIds(pos)
      triplet.srcId = local2global(localSrcId)
      triplet.dstId = local2global(localDstId)
      if (includeSrc) {
        triplet.srcAttr = vertexAttrs(localSrcId)
      }
      if (includeDst) {
        triplet.dstAttr = vertexAttrs(localDstId)
      }
      triplet.attr = data(pos)
      pos += 1
      triplet
    }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
   * all edges sequentially.
   *
   * @param sendMsg generates messages to neighboring vertices of an edge
   * @param mergeMsg the combiner applied to messages destined to the same vertex
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateMessagesEdgeScan[A: ClassTag](
      sendMsg: EdgeContext[VD, ED, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[A](vertexAttrs.length)
    val bitset = new BitSet(vertexAttrs.length)

    var sumSearchingTime: Long = 0
    var sumCalculationTime: Long = 0
    var sumWritingTime: Long = 0
    var activatedEdge: Int = 0

    val ctx = new AggregatingEdgeContext[VD, ED, A](mergeMsg, aggregates, bitset)
    var i = 0
    while (i < size) {
      val startTime = System.nanoTime()

      val localSrcId = localSrcIds(i)
      val srcId = local2global(localSrcId)
      val localDstId = localDstIds(i)
      val dstId = local2global(localDstId)
      val edgeIsActive =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(srcId)
        else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
        else if (activeness == EdgeActiveness.Both) isActive(srcId) && isActive(dstId)
        else if (activeness == EdgeActiveness.Either) isActive(srcId) || isActive(dstId)
        else throw new Exception("unreachable")
      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (edgeIsActive) {
        val startTime2 = System.nanoTime()
        activatedEdge += 1
        val srcAttr = if (tripletFields.useSrc) vertexAttrs(localSrcId) else null.asInstanceOf[VD]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
        ctx.set(srcId, dstId, localSrcId, localDstId, srcAttr, dstAttr, data(i))
        val endTime2 = System.nanoTime()
        val startTime3 = System.nanoTime()
        sendMsg(ctx)
        val endTime3 = System.nanoTime()

        sumWritingTime += (endTime2 - startTime2)
        sumCalculationTime += (endTime3 - startTime3)
      }
      i += 1
    }

    val pid = TaskContext.getPartitionId()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into array: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing: " + sumCalculationTime)
    println("In partition " + pid +
      ", (activatedEdge) Amount of active edges: " + activatedEdge)
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into array: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing: " + sumCalculationTime)
    logInfo("In partition " + pid +
      ", (activatedEdge) Amount of active edges: " + activatedEdge)
/*
    val pid = TaskContext.getPartitionId()
    val writer = new PrintWriter(new File(
      "/home/liqi/IdeaProjects/GraphXwithGPU/logSpark/" +
        "testSparkPartitionResultLog_pid" + pid + "_" + System.nanoTime() + ".txt"))
    val iter = bitset.iterator
    while(iter.hasNext) {
      val temp = iter.next()
      var chars = ""
      chars = chars + " " + local2global(temp) + " : " + aggregates(temp)
      writer.write("In part " + pid + " , result data: "
        + chars + '\n')
    }
    writer.close()
*/
    bitset.iterator.map { localId => (local2global(localId), aggregates(localId)) }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by
   * filtering the source vertex index, then scanning each edge cluster.
   *
   * @param sendMsg generates messages to neighboring vertices of an edge
   * @param mergeMsg the combiner applied to messages destined to the same vertex
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateMessagesIndexScan[A: ClassTag](
      sendMsg: EdgeContext[VD, ED, A] => Unit,
      mergeMsg: (A, A) => A,
      tripletFields: TripletFields,
      activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[A](vertexAttrs.length)
    val bitset = new BitSet(vertexAttrs.length)

    var sumSearchingTime: Long = 0
    var sumWritingTime : Long = 0
    var sumCalculationTime: Long = 0
    var activatedEdge: Int = 0

    val ctx = new AggregatingEdgeContext[VD, ED, A](mergeMsg, aggregates, bitset)
    index.iterator.foreach { cluster =>

      val startTime = System.nanoTime()

      val clusterSrcId = cluster._1
      val clusterPos = cluster._2
      val clusterLocalSrcId = localSrcIds(clusterPos)

      val scanCluster =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.DstOnly) true
        else if (activeness == EdgeActiveness.Both) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.Either) true
        else throw new Exception("unreachable")

      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (scanCluster) {

        val startTime2 = System.nanoTime()

        var pos = clusterPos
        val srcAttr =
          if (tripletFields.useSrc) vertexAttrs(clusterLocalSrcId) else null.asInstanceOf[VD]

        val endTime2 = System.nanoTime()
        val startTimeWrite = System.nanoTime()

        ctx.setSrcOnly(clusterSrcId, clusterLocalSrcId, srcAttr)
        val endTimeWrite = System.nanoTime()
        sumWritingTime += (endTimeWrite - startTimeWrite)
        sumSearchingTime += (endTime2 - startTime2)
        while (pos < size && localSrcIds(pos) == clusterLocalSrcId) {

          val startTime3 = System.nanoTime()

          val localDstId = localDstIds(pos)
          val dstId = local2global(localDstId)
          val edgeIsActive =
            if (activeness == EdgeActiveness.Neither) true
            else if (activeness == EdgeActiveness.SrcOnly) true
            else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
            else if (activeness == EdgeActiveness.Both) isActive(dstId)
            else if (activeness == EdgeActiveness.Either) isActive(clusterSrcId) || isActive(dstId)
            else throw new Exception("unreachable")

          val endTime3 = System.nanoTime()
          sumSearchingTime += (endTime3 - startTime3)

          if (edgeIsActive) {

            val startTimeWrite2 = System.nanoTime()
            activatedEdge += 1

            val dstAttr =
              if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
            ctx.setRest(dstId, localDstId, dstAttr, data(pos))

            val endTimeWrite2 = System.nanoTime()
            sumWritingTime += (endTimeWrite2 - startTimeWrite2)

            val startTime4 = System.nanoTime()

            sendMsg(ctx)

            val endTime4 = System.nanoTime()
            sumCalculationTime += (endTime4 - startTime4)
          }
          pos += 1
        }
      }
    }

    val pid = TaskContext.getPartitionId()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into array: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing: " + sumCalculationTime)
    println("In partition " + pid +
      ", (activatedEdge) Amount of active edges: " + activatedEdge)
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into array: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing: " + sumCalculationTime)
    logInfo("In partition " + pid +
      ", (activatedEdge) Amount of active edges: " + activatedEdge)
/*
    val pid = TaskContext.getPartitionId()
    val writer = new PrintWriter(new File(
      "/home/liqi/IdeaProjects/GraphXwithGPU/logSpark/" +
      "testSparkPartitionResultLog_pid" + pid + "_" + System.nanoTime() + ".txt"))
    val iter = bitset.iterator
    while(iter.hasNext) {
      val temp = iter.next()
      var chars = ""
      chars = chars + " " + local2global(temp) + " : " + aggregates(temp)
      writer.write("In part " + pid + " , result data: "
        + chars + '\n')
    }
    writer.close()
*/
    bitset.iterator.map { localId => (local2global(localId), aggregates(localId)) }
  }

  // Old GPU array init with incomplete skip
  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
   * all edges sequentially, then copy the related vertices into GPU environment in array form.
   *
   * @param pid for the partition ID
   * @param counter for skipping steps, will be removed in the future
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUEdgeScan[A: ClassTag]
  (pid: Int, counter: LongAccumulator,
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Array[VertexId], Array[A], Boolean),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[VD](vertexAttrs.length)
    val bitSet = new BitSet(vertexAttrs.length)
    val activeStatus = new Array[Boolean](vertexAttrs.length)
    val globalVertexId = new Array[VertexId](vertexAttrs.length)
    for(i <- vertexAttrs.indices) {
      activeStatus(i) = false
      globalVertexId(i) = -1L
    }

    var i = 0
    while (i < size) {
      val localSrcId = localSrcIds(i)
      val srcId = local2global(localSrcId)
      val localDstId = localDstIds(i)
      val dstId = local2global(localDstId)
      val edgeIsActive =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(srcId)
        else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
        else if (activeness == EdgeActiveness.Both) isActive(srcId) && isActive(dstId)
        else if (activeness == EdgeActiveness.Either) isActive(srcId) || isActive(dstId)
        else throw new Exception("unreachable")
      if (edgeIsActive) {
        val srcAttr = if (tripletFields.useSrc) vertexAttrs(localSrcId) else null.asInstanceOf[VD]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
        aggregates(localSrcId) = srcAttr
        aggregates(localDstId) = dstAttr
        if(activeSet.isEmpty) {
          activeStatus(localSrcId) = true
          activeStatus(localDstId) = true
        }
        else {
          activeStatus(localSrcId) = isActive(srcId)
          activeStatus(localDstId) = isActive(dstId)
        }
        globalVertexId(localSrcId) = srcId
        globalVertexId(localDstId) = dstId
      }
      i += 1
    }
    // Input array is a skipped array
    // Output array should be a linear array
    val (globalVidResult, globalAggResult, needCombine) =
      gpuBridgeFunc(pid, globalVertexId, activeStatus, aggregates)
    val sortedAggregates = new Array[A](vertexAttrs.length)

    if(needCombine) {
      counter.add(1)
    }

    // fit linear array to bitSet indexed array
    // size of globalVidResult is duplicate, use globalAggResult to traverse

    for(j <- globalAggResult.indices) {
      val locals = global2local(globalVidResult(j))
      bitSet.set(locals)
      sortedAggregates(locals) = globalAggResult(j)
    }

    bitSet.iterator.map { localId => (local2global(localId), sortedAggregates(localId)) }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by
   * filtering the source vertex index, then scanning each edge cluster, then
   * copy the related vertices into GPU environment in array form.
   *
   * @param pid for the partition ID
   * @param counter for skipping steps, will be removed in the future
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUIndexScan[A: ClassTag]
  (pid: Int, counter: LongAccumulator,
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Array[VertexId], Array[A], Boolean),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(VertexId, A)] = {
    val aggregates = new Array[VD](vertexAttrs.length)
    val bitSet = new BitSet(vertexAttrs.length)
    val activeStatus = new Array[Boolean](vertexAttrs.length)
    val globalVertexId = new Array[VertexId](vertexAttrs.length)
    for(i <- vertexAttrs.indices) {
      activeStatus(i) = false
      globalVertexId(i) = -1L
    }

    index.iterator.foreach { cluster =>
      val clusterSrcId = cluster._1
      val clusterPos = cluster._2
      val clusterLocalSrcId = localSrcIds(clusterPos)

      val scanCluster =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.DstOnly) true
        else if (activeness == EdgeActiveness.Both) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.Either) true
        else throw new Exception("unreachable")

      if (scanCluster) {
        var pos = clusterPos
        val srcAttr =
          if (tripletFields.useSrc) vertexAttrs(clusterLocalSrcId) else null.asInstanceOf[VD]
        aggregates(clusterLocalSrcId) = srcAttr
        activeStatus(clusterLocalSrcId) = isActive(clusterSrcId)
        globalVertexId(clusterLocalSrcId) = clusterSrcId
        while (pos < size && localSrcIds(pos) == clusterLocalSrcId) {
          val localDstId = localDstIds(pos)
          val dstId = local2global(localDstId)
          val edgeIsActive =
            if (activeness == EdgeActiveness.Neither) true
            else if (activeness == EdgeActiveness.SrcOnly) true
            else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
            else if (activeness == EdgeActiveness.Both) isActive(dstId)
            else if (activeness == EdgeActiveness.Either) isActive(clusterSrcId) || isActive(dstId)
            else throw new Exception("unreachable")
          if (edgeIsActive) {
            val dstAttr =
              if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
            aggregates(localDstId) = dstAttr
            activeStatus(localDstId) = isActive(dstId)
            globalVertexId(localDstId) = dstId
          }
          pos += 1
        }
      }
    }
    // Input array is a skipped array
    // Output array should be a linear array
    val (globalVidResult, globalAggResult, needCombine) =
      gpuBridgeFunc(pid, globalVertexId, activeStatus, aggregates)
    val sortedAggregates = new Array[A](vertexAttrs.length)

    if(needCombine) {
      counter.add(1)
    }

    // fit linear array to bitSet indexed array
    // size of globalVidResult is duplicate, use globalAggResult to traverse

    for(j <- globalAggResult.indices) {
      val locals = global2local(globalVidResult(j))
      bitSet.set(locals)
      sortedAggregates(locals) = globalAggResult(j)
    }

    bitSet.iterator.map { localId => (local2global(localId), sortedAggregates(localId)) }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
   * all edges sequentially, then copy the related vertices into GPU environment in array form.
   * Used in step skipping.
   *
   * @param pid for the partition ID
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return partition status if could execute step skipping
   *         and active vertices number in partition scope
   */
  def aggregateIntoGPUSkipEdgeScan
  (pid: Int,
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Boolean, Int),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(Int, (Boolean, Int))] = {
    val aggregates = new Array[VD](vertexAttrs.length)
    val activeStatus = new Array[Boolean](vertexAttrs.length)
    val globalVertexId = new Array[VertexId](vertexAttrs.length)
    for(i <- vertexAttrs.indices) {
      activeStatus(i) = false
      globalVertexId(i) = -1L
    }

    var sumSearchingTime: Long = 0
    var sumWritingTime: Long = 0

    var i = 0
    while (i < size) {

      val startTime = System.nanoTime()

      val localSrcId = localSrcIds(i)
      val srcId = local2global(localSrcId)
      val localDstId = localDstIds(i)
      val dstId = local2global(localDstId)
      val edgeIsActive =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(srcId)
        else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
        else if (activeness == EdgeActiveness.Both) isActive(srcId) && isActive(dstId)
        else if (activeness == EdgeActiveness.Either) isActive(srcId) || isActive(dstId)
        else throw new Exception("unreachable")

      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (edgeIsActive) {

        val startTime2 = System.nanoTime()

        val srcAttr = if (tripletFields.useSrc) vertexAttrs(localSrcId) else null.asInstanceOf[VD]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]

        aggregates(localSrcId) = srcAttr
        aggregates(localDstId) = dstAttr
        if(activeSet.isEmpty) {
          activeStatus(localSrcId) = true
          activeStatus(localDstId) = true
        }
        else {
          activeStatus(localSrcId) = isActive(srcId)
          activeStatus(localDstId) = isActive(dstId)
        }
        globalVertexId(localSrcId) = srcId
        globalVertexId(localDstId) = dstId

        val endTime2 = System.nanoTime()
        sumWritingTime += (endTime2 - startTime2)
      }
      i += 1
    }

    val startTime2 = System.nanoTime()

    val (needMerge, activeCount) = gpuBridgeFunc(pid, globalVertexId, activeStatus, aggregates)

    val endTime2 = System.nanoTime()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into arr: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into arr: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))

    Iterator((pid, (needMerge, activeCount)))

  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by
   * filtering the source vertex index, then scanning each edge cluster, then
   * copy the related vertices into GPU environment in array form. Used in step skipping.
   *
   * @param pid for the partition ID
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return partition status if could execute step skipping
   *         and active vertices number in partition scope
   */
  def aggregateIntoGPUSkipIndexScan
  (pid: Int,
   gpuBridgeFunc: (Int, Array[VertexId], Array[Boolean], Array[VD])
     => (Boolean, Int),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(Int, (Boolean, Int))] = {
    val aggregates = new Array[VD](vertexAttrs.length)
    val activeStatus = new Array[Boolean](vertexAttrs.length)
    val globalVertexId = new Array[VertexId](vertexAttrs.length)
    for(i <- vertexAttrs.indices) {
      activeStatus(i) = false
      globalVertexId(i) = -1L
    }

    var sumSearchingTime: Long = 0
    var sumWritingTime: Long = 0

    index.iterator.foreach { cluster =>

      val startTime = System.nanoTime()

      val clusterSrcId = cluster._1
      val clusterPos = cluster._2
      val clusterLocalSrcId = localSrcIds(clusterPos)

      val scanCluster =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.DstOnly) true
        else if (activeness == EdgeActiveness.Both) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.Either) true
        else throw new Exception("unreachable")

      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (scanCluster) {

        val startTime2 = System.nanoTime()

        var pos = clusterPos
        val srcAttr =
          if (tripletFields.useSrc) vertexAttrs(clusterLocalSrcId) else null.asInstanceOf[VD]

        aggregates(clusterLocalSrcId) = srcAttr
        activeStatus(clusterLocalSrcId) = isActive(clusterSrcId)
        globalVertexId(clusterLocalSrcId) = clusterSrcId

        val endTime2 = System.nanoTime()
        sumWritingTime += (endTime2 - startTime2)

        while (pos < size && localSrcIds(pos) == clusterLocalSrcId) {

          val startTime3 = System.nanoTime()

          val localDstId = localDstIds(pos)
          val dstId = local2global(localDstId)
          val edgeIsActive =
            if (activeness == EdgeActiveness.Neither) true
            else if (activeness == EdgeActiveness.SrcOnly) true
            else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
            else if (activeness == EdgeActiveness.Both) isActive(dstId)
            else if (activeness == EdgeActiveness.Either) isActive(clusterSrcId) || isActive(dstId)
            else throw new Exception("unreachable")

          val endTime3 = System.nanoTime()
          sumSearchingTime += (endTime3 - startTime3)

          if (edgeIsActive) {

            val startTime4 = System.nanoTime()

            val dstAttr =
              if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]
            aggregates(localDstId) = dstAttr
            activeStatus(localDstId) = isActive(dstId)
            globalVertexId(localDstId) = dstId

            val endTime4 = System.nanoTime()
            sumWritingTime += (endTime4 - startTime4)
          }
          pos += 1
        }
      }
    }

    val startTime2 = System.nanoTime()
    // Input array is a skipped array
    // Output array should be a linear array
    val (needMerge, activeCount) =
    gpuBridgeFunc(pid, globalVertexId, activeStatus, aggregates)

    val endTime2 = System.nanoTime()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into arr: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into arr: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing from GPU env: "
      + (endTime2 - startTime2))

    Iterator((pid, (needMerge, activeCount)))
  }

  /**
   * Get related partition messages in the GPU environment defined in gpuFetchFunc
   *
   * @param pid for the partition ID
   * @param gpuFetchFunc which get the messages in GPU using related vertices
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUSkipFetch[A: ClassTag]
  (pid: Int,
   gpuFetchFunc: Int
     => (Array[VertexId], Array[A])): Iterator[(VertexId, A)] = {

    val startTime2 = System.nanoTime()

    val bitSet = new BitSet(vertexAttrs.length)
    val (globalVidResult, globalAggResult) = gpuFetchFunc(pid)
    val sortedAggregates = new Array[A](vertexAttrs.length)

    // fit linear array to bitSet indexed array
    // size of globalVidResult is duplicate, use globalAggResult to traverse

    for(j <- globalAggResult.indices) {
      val locals = global2local(globalVidResult(j))
      bitSet.set(locals)
      sortedAggregates(locals) = globalAggResult(j)
    }

    val endTime2 = System.nanoTime()
    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumFetchingTime) Time for getting messages from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumFetchingTime) Time for getting messages from GPU env: "
      + (endTime2 - startTime2))

    bitSet.iterator.map { localId => (local2global(localId), sortedAggregates(localId)) }
  }


  /**
   * Get related partition messages in the GPU environment defined in gpuFetchFunc
   * Will get merged messages in the last several iteration
   *
   * @param pid for the partition ID
   * @param gpuFetchFunc which get the messages in GPU using related vertices
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUSkipFetchOldMsg[A: ClassTag]
  (pid: Int,
   gpuFetchFunc: Int
     => (Array[VertexId], Array[Boolean], Array[Int], Array[A])):
  Iterator[(VertexId, (Boolean, Int, A))] = {

    val startTime2 = System.nanoTime()

    val bitSet = new BitSet(vertexAttrs.length)
    val (globalVidResult, lastActiveResult, timeStampResult, globalAggResult) = gpuFetchFunc(pid)
    val sortedAggregates = new Array[A](vertexAttrs.length)

    // fit linear array to bitSet indexed array
    // size of globalVidResult is duplicate, use globalAggResult to traverse

    for(j <- globalAggResult.indices) {
      val locals = global2local(globalVidResult(j))
      bitSet.set(locals)
      sortedAggregates(locals) = globalAggResult(j)
    }

    val endTime2 = System.nanoTime()
    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumFetchingTime) Time for getting oldmessages from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumFetchingTime) Time for getting oldmessages from GPU env: "
      + (endTime2 - startTime2))

    bitSet.iterator.map { localId => (local2global(localId),
      (lastActiveResult(localId), timeStampResult(localId), sortedAggregates(localId))) }
  }


  /**
   * Using the existed related vertices in GPU environment to generate messages.
   * Used in step skipping only if some condition satisfied.
   *
   * @param pid for the partition ID
   * @param iterTimes for the previous iteration of Pregel
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   *
   * @return partition status if could execute step skipping
   *         and active vertices number in partition scope
   */
  def aggregateIntoGPUSkipStep
  (pid: Int, iterTimes: Int,
   gpuBridgeFunc: (Int, Int) => (Boolean, Int)): Iterator[(Int, (Boolean, Int))] = {

    val startTime2 = System.nanoTime()

    val (needMerge, activeCount) = gpuBridgeFunc(pid, iterTimes)

    val endTime2 = System.nanoTime()
    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumCalculationTime) Time for skipping from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for skipping from GPU env: "
      + (endTime2 - startTime2))

    Iterator((pid, (needMerge, activeCount)))
  }

  // GPU shm init with incomplete skip
  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by scanning
   * all edges sequentially, then copy the related vertices into GPU environment in shm form.
   *
   * @param pid for the partition ID
   * @param counter for skipping steps, will be removed in the future
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUShmEdgeScan[A: ClassTag]
  (pid: Int, counter: LongAccumulator, identifierArr: Array[String],
   shmInitFunc: (Array[String], Int) => (Array[shmArrayWriter], shmWriterPackager),
   shmWriteFunc: (VertexId, Boolean, VD, Array[shmArrayWriter]) => Unit,
   gpuBridgeFunc: (Int, shmWriterPackager, Int, GraphXPrimitiveKeyOpenHashMap[VertexId, Int])
     => (BitSet, Array[A], Boolean),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(VertexId, A)] = {

    var sumSearchingTime: Long = 0
    var sumWritingTime: Long = 0

    val writerLineTest = new shmVertexWriter[VD](
      identifierArr, pid, shmInitFunc, shmWriteFunc)
    val filterBitSet = new BitSet(vertexAttrs.length)

    var i = 0
    while (i < size) {

      val startTime = System.nanoTime()

      val localSrcId = localSrcIds(i)
      val srcId = local2global(localSrcId)
      val localDstId = localDstIds(i)
      val dstId = local2global(localDstId)
      val edgeIsActive =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(srcId)
        else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
        else if (activeness == EdgeActiveness.Both) isActive(srcId) && isActive(dstId)
        else if (activeness == EdgeActiveness.Either) isActive(srcId) || isActive(dstId)
        else throw new Exception("unreachable")

      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (edgeIsActive) {

        val startTime2 = System.nanoTime()

        val srcAttr = if (tripletFields.useSrc) vertexAttrs(localSrcId) else null.asInstanceOf[VD]
        val dstAttr = if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]

        val srcActive = {
          if (activeSet.isEmpty) true
          else isActive(srcId)
        }
        val dstActive = {
          if (activeSet.isEmpty) true
          else isActive(dstId)
        }

        if (! filterBitSet.get(localSrcId)) {
          writerLineTest.input(srcId, srcActive, srcAttr)
          filterBitSet.set(localSrcId)
        }
        if (! filterBitSet.get(localDstId)) {
          writerLineTest.input(dstId, dstActive, dstAttr)
          filterBitSet.set(localDstId)
        }
        val endTime2 = System.nanoTime()
        sumWritingTime += (endTime2 - startTime2)

      }
      i += 1
    }

    val startTime2 = System.nanoTime()
    // Input array is a linear shm-like array
    // Output array should be a skipped array
    val (resultBitSet, resultSortedAgg, needCombine) =
    gpuBridgeFunc(pid, writerLineTest.returnPackager(),
      writerLineTest.modifiedVertexAmount, global2local)

    if(needCombine) {
      counter.add(1)
    }

    val endTime2 = System.nanoTime()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into shm: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into shm: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))

/*
    val writer = new PrintWriter(new File(
      "/home/liqi/IdeaProjects/GraphXwithGPU/logGPUShm/" +
        "testGPUPartitionResultLog_pid" + pid + "_" + System.nanoTime() + ".txt"))
    val iter = resultBitSet.iterator
    while(iter.hasNext) {
      val temp = iter.next()
      var chars = ""
      chars = chars + " " + local2global(temp) + " : " + resultSortedAgg(temp)
      writer.write("In part " + pid + " , result data: "
        + chars + '\n')
    }
    writer.close()
*/
    resultBitSet.iterator.map { localId => (local2global(localId), resultSortedAgg(localId)) }
  }

  /**
   * Send messages along edges and aggregate them at the receiving vertices. Implemented by
   * filtering the source vertex index, then scanning each edge cluster, then
   * copy the related vertices into GPU environment in shm form.
   *
   * @param pid for the partition ID
   * @param counter for skipping steps, will be removed in the future
   * @param gpuBridgeFunc which execute the message generate function in GPU using related vertices
   * @param tripletFields which triplet fields `sendMsg` uses
   * @param activeness criteria for filtering edges based on activeness
   *
   * @return iterator aggregated messages keyed by the receiving vertex id
   */
  def aggregateIntoGPUShmIndexScan[A: ClassTag]
  (pid: Int, counter: LongAccumulator, identifierArr: Array[String],
   shmInitFunc: (Array[String], Int) => (Array[shmArrayWriter], shmWriterPackager),
   shmWriteFunc: (VertexId, Boolean, VD, Array[shmArrayWriter]) => Unit,
   gpuBridgeFunc: (Int, shmWriterPackager, Int, GraphXPrimitiveKeyOpenHashMap[VertexId, Int])
     => (BitSet, Array[A], Boolean),
   tripletFields: TripletFields,
   activeness: EdgeActiveness): Iterator[(VertexId, A)] = {

    var sumSearchingTime: Long = 0
    var sumWritingTime: Long = 0

    val writerLineTest = new shmVertexWriter[VD](
      identifierArr, pid, shmInitFunc, shmWriteFunc)
    val filterBitSet = new BitSet(vertexAttrs.length)

    index.iterator.foreach { cluster =>

      val startTime = System.nanoTime()

      val clusterSrcId = cluster._1
      val clusterPos = cluster._2
      val clusterLocalSrcId = localSrcIds(clusterPos)

      val scanCluster =
        if (activeness == EdgeActiveness.Neither) true
        else if (activeness == EdgeActiveness.SrcOnly) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.DstOnly) true
        else if (activeness == EdgeActiveness.Both) isActive(clusterSrcId)
        else if (activeness == EdgeActiveness.Either) true
        else throw new Exception("unreachable")

      val endTime = System.nanoTime()
      sumSearchingTime += (endTime - startTime)

      if (scanCluster) {

        val startTime2 = System.nanoTime()

        var pos = clusterPos
        val srcAttr =
          if (tripletFields.useSrc) vertexAttrs(clusterLocalSrcId) else null.asInstanceOf[VD]

        if (! filterBitSet.get(clusterLocalSrcId)) {
          writerLineTest.input(clusterSrcId, isActive(clusterSrcId), srcAttr)
          filterBitSet.set(clusterLocalSrcId)
        }

        val endTime2 = System.nanoTime()
        sumWritingTime += (endTime2 - startTime2)

        while (pos < size && localSrcIds(pos) == clusterLocalSrcId) {

          val startTime3 = System.nanoTime()

          val localDstId = localDstIds(pos)
          val dstId = local2global(localDstId)
          val edgeIsActive =
            if (activeness == EdgeActiveness.Neither) true
            else if (activeness == EdgeActiveness.SrcOnly) true
            else if (activeness == EdgeActiveness.DstOnly) isActive(dstId)
            else if (activeness == EdgeActiveness.Both) isActive(dstId)
            else if (activeness == EdgeActiveness.Either) isActive(clusterSrcId) || isActive(dstId)
            else throw new Exception("unreachable")

          val endTime3 = System.nanoTime()
          sumSearchingTime += (endTime3 - startTime3)

          if (edgeIsActive) {

            val startTime4 = System.nanoTime()

            val dstAttr =
              if (tripletFields.useDst) vertexAttrs(localDstId) else null.asInstanceOf[VD]

            if (! filterBitSet.get(localDstId)) {
              writerLineTest.input(dstId, isActive(dstId), dstAttr)
              filterBitSet.set(localDstId)
            }

            val endTime4 = System.nanoTime()
            sumWritingTime += (endTime4 - startTime4)

          }
          pos += 1
        }
      }
    }

    val startTime2 = System.nanoTime()

    // Input array is a linear shm-like array
    // Output array should be a skipped array
    val (resultBitSet, resultSortedAgg, needCombine) =
    gpuBridgeFunc(pid, writerLineTest.returnPackager(),
      writerLineTest.modifiedVertexAmount, global2local)

    if(needCombine) {
      counter.add(1)
    }

    val endTime2 = System.nanoTime()

    /*
    // scalastyle:off println
    println("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    println("In partition " + pid +
      ", (sumWritingTime) Time for writing into shm: " + sumWritingTime)
    println("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))
    // scalastyle:on println

     */

    logInfo("In partition " + pid +
      ", (sumSearchingTime) Time for getting graph info from spark: " + sumSearchingTime)
    logInfo("In partition " + pid +
      ", (sumWritingTime) Time for writing into shm: " + sumWritingTime)
    logInfo("In partition " + pid +
      ", (sumCalculationTime) Time for executing and packaging from GPU env: "
      + (endTime2 - startTime2))

/*
    val writer = new PrintWriter(new File(
      "/home/liqi/IdeaProjects/GraphXwithGPU/logGPUShm/" +
        "testGPUPartitionResultLog_pid" + pid + "_" + System.nanoTime() + ".txt"))
    val iter = resultBitSet.iterator
    while(iter.hasNext) {
      val temp = iter.next()
      var chars = ""
      chars = chars + " " + local2global(temp) + " : " + resultSortedAgg(temp)
      writer.write("In part " + pid + " , result data: "
        + chars + '\n')
    }
    writer.close()
*/
    resultBitSet.iterator.map { localId => (local2global(localId), resultSortedAgg(localId)) }
  }
}

private class AggregatingEdgeContext[VD, ED, A](
    mergeMsg: (A, A) => A,
    aggregates: Array[A],
    bitset: BitSet)
  extends EdgeContext[VD, ED, A] {

  private[this] var _srcId: VertexId = _
  private[this] var _dstId: VertexId = _
  private[this] var _localSrcId: Int = _
  private[this] var _localDstId: Int = _
  private[this] var _srcAttr: VD = _
  private[this] var _dstAttr: VD = _
  private[this] var _attr: ED = _

  def set(
      srcId: VertexId, dstId: VertexId,
      localSrcId: Int, localDstId: Int,
      srcAttr: VD, dstAttr: VD,
      attr: ED) {
    _srcId = srcId
    _dstId = dstId
    _localSrcId = localSrcId
    _localDstId = localDstId
    _srcAttr = srcAttr
    _dstAttr = dstAttr
    _attr = attr
  }

  def setSrcOnly(srcId: VertexId, localSrcId: Int, srcAttr: VD) {
    _srcId = srcId
    _localSrcId = localSrcId
    _srcAttr = srcAttr
  }

  def setRest(dstId: VertexId, localDstId: Int, dstAttr: VD, attr: ED) {
    _dstId = dstId
    _localDstId = localDstId
    _dstAttr = dstAttr
    _attr = attr
  }

  override def srcId: VertexId = _srcId
  override def dstId: VertexId = _dstId
  override def srcAttr: VD = _srcAttr
  override def dstAttr: VD = _dstAttr
  override def attr: ED = _attr

  override def sendToSrc(msg: A) {
    send(_localSrcId, msg)
  }
  override def sendToDst(msg: A) {
    send(_localDstId, msg)
  }

  @inline private def send(localId: Int, msg: A) {
    if (bitset.get(localId)) {
      aggregates(localId) = mergeMsg(aggregates(localId), msg)
    } else {
      aggregates(localId) = msg
      bitset.set(localId)
    }
  }
}
