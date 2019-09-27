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

package org.apache.spark.graphx.lib

import java.io.{File, PrintWriter}

import org.apache.spark.{SparkContext, SparkFunSuite}
import org.apache.spark.graphx._
import org.apache.spark.rdd.RDD

class LabelPropagationSuite extends SparkFunSuite with LocalSparkContext {
  test("Label Propagation") {
    withSpark { sc =>
      // Construct a graph with two cliques connected by a single edge
      val n = 5
      val clique1 = for (u <- 0L until n; v <- 0L until n) yield Edge(u, v, 1)
      val clique2 = for (u <- 0L to n; v <- 0L to n) yield Edge(u + n, v + n, 1)
      val twoCliques = sc.parallelize(clique1 ++ clique2 :+ Edge(0L, n, 1))
      val graph = Graph.fromEdges(twoCliques, 1)
      // Run label propagation
      val labels = LabelPropagation.run(graph, n * 4).cache()

      // All vertices within a clique should have the same label
      val clique1Labels = labels.vertices.filter(_._1 < n).map(_._2).collect.toArray
      assert(clique1Labels.forall(_ == clique1Labels(0)))
      val clique2Labels = labels.vertices.filter(_._1 >= n).map(_._2).collect.toArray
      assert(clique2Labels.forall(_ == clique2Labels(0)))
      // The two cliques should have different labels
      assert(clique1Labels(0) != clique2Labels(0))
    }
  }

  test("Sample data on Label Propagation") {
    withSpark { sc =>
      // Construct a graph with two cliques connected by a single edge
      val n = 50
      val sourceFile =
        "/home/liqi/IdeaProjects/GraphXwithGPU/testGraphDivideSmall.txt"
      val inputGraph = readFile(sc, sourceFile)

      val dynamicRanksTest = LabelPropagation.run(inputGraph, n)
      val result = dynamicRanksTest.vertices.collect()

      val writer = new PrintWriter(new File("/home/liqi/IdeaProjects/GraphXwithGPU/" +
        "testGraphInLPA.txt"))

      for(input <- result) {
        // println(input._1 + " " + input._2)
        writer.write(input._1 + " " + input._2 + '\n')
      }

      writer.close()
      assert(1 == 1)

    }
  }

  // read graph file
  def readFile(sc: SparkContext, sourceFile: String)
              (implicit parts: Int = 4): Graph[Long, Double] = {

    val vertex: RDD[(VertexId, VertexId)] = sc.textFile(sourceFile).map{
      lines => {
        val para = lines.split(" ")
        (para(0).toLong, para(0).toLong)
      }
    }.repartition(parts)
    val edge: RDD[Edge[Double]] = sc.textFile(sourceFile).map{
      lines => {
        val para = lines.split(" ")
        val q = para(2).toDouble
        Edge(para(0).toLong, para(1).toLong, q)
      }
    }.repartition(parts)

    val graph = Graph(vertex, edge)

    // trigger of spark RDD
    graph.edges.count()

    graph
  }
}
