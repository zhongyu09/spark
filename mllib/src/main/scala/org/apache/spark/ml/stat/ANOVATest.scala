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

package org.apache.spark.ml.stat

import org.apache.commons.math3.distribution.FDistribution

import org.apache.spark.annotation.Since
import org.apache.spark.ml.linalg.{Vector, Vectors, VectorUDT}
import org.apache.spark.ml.util.{MetadataUtils, SchemaUtils}
import org.apache.spark.sql._
import org.apache.spark.sql.functions._
import org.apache.spark.util.collection.OpenHashMap


/**
 * ANOVA Test for continuous data.
 *
 * See <a href="https://en.wikipedia.org/wiki/Analysis_of_variance">Wikipedia</a> for more
 * information on ANOVA test.
 */
@Since("3.1.0")
object ANOVATest {

  /** Used to construct output schema of tests */
  private case class ANOVAResult(
      pValues: Vector,
      degreesOfFreedom: Array[Long],
      fValues: Vector)

  /**
   * @param dataset  DataFrame of categorical labels and continuous features.
   * @param featuresCol  Name of features column in dataset, of type `Vector` (`VectorUDT`)
   * @param labelCol  Name of label column in dataset, of any numerical type
   * @return DataFrame containing the test result for every feature against the label.
   *         This DataFrame will contain a single Row with the following fields:
   *          - `pValues: Vector`
   *          - `degreesOfFreedom: Array[Long]`
   *          - `fValues: Vector`
   *         Each of these fields has one value per feature.
   */
  @Since("3.1.0")
  def test(dataset: DataFrame, featuresCol: String, labelCol: String): DataFrame = {
    val spark = dataset.sparkSession
    val testResults = testClassification(dataset, featuresCol, labelCol)
    val pValues: Vector = Vectors.dense(testResults.map(_.pValue))
    val degreesOfFreedom: Array[Long] = testResults.map(_.degreesOfFreedom)
    val fValues: Vector = Vectors.dense(testResults.map(_.statistic))
    spark.createDataFrame(
      Seq(new ANOVAResult(pValues, degreesOfFreedom, fValues)))
  }

  /**
   * @param dataset  DataFrame of categorical labels and continuous features.
   * @param featuresCol  Name of features column in dataset, of type `Vector` (`VectorUDT`)
   * @param labelCol  Name of label column in dataset, of any numerical type
   * @return Array containing the ANOVATestResult for every feature against the
   *         label.
   */
  private[ml] def testClassification(
      dataset: Dataset[_],
      featuresCol: String,
      labelCol: String): Array[SelectionTestResult] = {

    val spark = dataset.sparkSession
    import spark.implicits._

    SchemaUtils.checkColumnType(dataset.schema, featuresCol, new VectorUDT)
    SchemaUtils.checkNumericType(dataset.schema, labelCol)

    val numFeatures = MetadataUtils.getNumFeatures(dataset, featuresCol)
    val Row(numSamples: Long, numClasses: Long) =
      dataset.select(count(labelCol), countDistinct(labelCol)).head

    dataset.select(col(labelCol).cast("double"), col(featuresCol))
      .as[(Double, Vector)]
      .rdd
      .flatMap { case (label, features) =>
        features.iterator.map { case (col, value) => (col, (label, value, value * value)) }
      }.aggregateByKey[(Double, Double, OpenHashMap[Double, Double], OpenHashMap[Double, Long])](
        (0.0, 0.0, new OpenHashMap[Double, Double], new OpenHashMap[Double, Long]))(
        seqOp = {
          case (
            // sums: mapOfSumPerClass (key: label, value: sum of features for each label)
            // counts: mapOfCountPerClass key: label, value: count of features for each label
            (sum: Double, sumOfSq: Double, sums, counts),
            (label, feature, featureSq)
           ) =>
            sums.changeValue(label, feature, _ + feature)
            counts.changeValue(label, 1L, _ + 1L)
            (sum + feature, sumOfSq + featureSq, sums, counts)
        },
        combOp = {
          case (
            (sum1, sumOfSq1, sums1, counts1),
            (sum2, sumOfSq2, sums2, counts2)
          ) =>
            sums2.foreach { case (v, w) =>
              sums1.changeValue(v, w, _ + w)
            }
            counts2.foreach { case (v, w) =>
              counts1.changeValue(v, w, _ + w)
            }
            (sum1 + sum2, sumOfSq1 + sumOfSq2, sums1, counts1)
        }
        ).map {
          case (col, (sum, sumOfSq, sums, counts)) =>
            // e.g. features are [3.3, 2.5, 1.0, 3.0, 2.0] and labels are [1, 2, 1, 3, 3]
            // sum: sum of all the features (3.3+2.5+1.0+3.0+2.0)
            // sumOfSq: sum of squares of all the features (3.3^2+2.5^2+1.0^2+3.0^2+2.0^2)
            // sums: mapOfSumPerClass (key: label, value: sum of features for each label)
            //                                         ( 1 -> 3.3 + 1.0, 2 -> 2.5, 3 -> 3.0 + 2.0 )
            // counts: mapOfCountPerClass (key: label, value: count of features for each label)
            //                                         ( 1 -> 2, 2 -> 2, 3 -> 2 )
            // sqSum: square of sum of all data ((3.3+2.5+1.0+3.0+2.0)^2)
            val sqSum = sum * sum
            val ssTot = sumOfSq - sqSum / numSamples

            // sumOfSqSumPerClass:
            //     sum( sq_sum_classes[k] / n_samples_per_class[k] for k in range(n_classes))
            //     e.g. ((3.3+1.0)^2 / 2 + 2.5^2 / 1 + (3.0+2.0)^2 / 2)
            val sumOfSqSumPerClass = sums.iterator
              .map { case (label, sum) => sum * sum / counts(label) }.sum
            // Sums of Squares Between
            val ssbn = sumOfSqSumPerClass - (sqSum / numSamples)
            // Sums of Squares Within
            val sswn = ssTot - ssbn
            // degrees of freedom between
            val dfbn = numClasses - 1
            // degrees of freedom within
            val dfwn = numSamples - numClasses
            // mean square between
            val msb = ssbn / dfbn
            // mean square within
            val msw = sswn / dfwn
            val fValue = msb / msw
            val pValue = 1 - new FDistribution(dfbn, dfwn).cumulativeProbability(fValue)
            (col, pValue, dfbn + dfwn, fValue)
        }.collect().sortBy(_._1).map {
          case (col, pValue, degreesOfFreedom, fValue) =>
            new ANOVATestResult(pValue, degreesOfFreedom, fValue)
        }
  }
}
