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

package org.apache.spark.ml.optim.aggregator

import org.apache.spark.broadcast.Broadcast
import org.apache.spark.ml.feature.{Instance, InstanceBlock}
import org.apache.spark.ml.linalg._

/**
 * HingeAggregator computes the gradient and loss for Hinge loss function as used in
 * binary classification for instances in sparse or dense vector in an online fashion.
 *
 * Two HingeAggregators can be merged together to have a summary of loss and gradient of
 * the corresponding joint dataset.
 *
 * This class standardizes feature values during computation using bcFeaturesStd.
 *
 * @param bcCoefficients The coefficients corresponding to the features.
 * @param fitIntercept Whether to fit an intercept term.
 */
private[ml] class HingeAggregator(
    numFeatures: Int,
    fitIntercept: Boolean)(bcCoefficients: Broadcast[Vector])
  extends DifferentiableLossAggregator[InstanceBlock, HingeAggregator] {

  private val numFeaturesPlusIntercept: Int = if (fitIntercept) numFeatures + 1 else numFeatures
  protected override val dim: Int = numFeaturesPlusIntercept
  @transient private lazy val coefficientsArray = bcCoefficients.value match {
    case DenseVector(values) => values
    case _ => throw new IllegalArgumentException(s"coefficients only supports dense vector" +
      s" but got type ${bcCoefficients.value.getClass}.")
  }

  @transient private lazy val linear = {
    if (fitIntercept) {
      new DenseVector(coefficientsArray.take(numFeatures))
    } else {
      new DenseVector(coefficientsArray)
    }
  }


  /**
   * Add a new training instance to this HingeAggregator, and update the loss and gradient
   * of the objective function.
   *
   * @param instance The instance of data point to be added.
   * @return This HingeAggregator object.
   */
  def add(instance: Instance): this.type = {
    instance match { case Instance(label, weight, features) =>
      require(numFeatures == features.size, s"Dimensions mismatch when adding new instance." +
        s" Expecting $numFeatures but got ${features.size}.")
      require(weight >= 0.0, s"instance weight, $weight has to be >= 0.0")

      if (weight == 0.0) return this
      val localCoefficients = coefficientsArray
      val localGradientSumArray = gradientSumArray

      val dotProduct = {
        var sum = 0.0
        features.foreachNonZero { (index, value) =>
          sum += localCoefficients(index) * value
        }
        if (fitIntercept) sum += localCoefficients(numFeaturesPlusIntercept - 1)
        sum
      }
      // Our loss function with {0, 1} labels is max(0, 1 - (2y - 1) (f_w(x)))
      // Therefore the gradient is -(2y - 1)*x
      val labelScaled = 2 * label - 1.0
      val loss = if (1.0 > labelScaled * dotProduct) {
        (1.0 - labelScaled * dotProduct) * weight
      } else {
        0.0
      }

      if (1.0 > labelScaled * dotProduct) {
        val gradientScale = -labelScaled * weight
        features.foreachNonZero { (index, value) =>
          localGradientSumArray(index) += value * gradientScale
        }
        if (fitIntercept) {
          localGradientSumArray(localGradientSumArray.length - 1) += gradientScale
        }
      }

      lossSum += loss
      weightSum += weight
      this
    }
  }

  /**
   * Add a new training instance block to this HingeAggregator, and update the loss and gradient
   * of the objective function.
   *
   * @param block The InstanceBlock to be added.
   * @return This HingeAggregator object.
   */
  def add(block: InstanceBlock): this.type = {
    require(numFeatures == block.numFeatures, s"Dimensions mismatch when adding new " +
      s"instance. Expecting $numFeatures but got ${block.numFeatures}.")
    require(block.weightIter.forall(_ >= 0),
      s"instance weights ${block.weightIter.mkString("[", ",", "]")} has to be >= 0.0")

    if (block.weightIter.forall(_ == 0)) return this
    val size = block.size
    val localGradientSumArray = gradientSumArray

    // vec here represents dotProducts
    val vec = if (fitIntercept && coefficientsArray.last != 0) {
      val intercept = coefficientsArray.last
      new DenseVector(Array.fill(size)(intercept))
    } else {
      new DenseVector(Array.ofDim[Double](size))
    }

    if (fitIntercept) {
      BLAS.gemv(1.0, block.matrix, linear, 1.0, vec)
    } else {
      BLAS.gemv(1.0, block.matrix, linear, 0.0, vec)
    }

    // in-place convert dotProducts to gradient scales
    // then, vec represents gradient scales
    var i = 0
    while (i < size) {
      val weight = block.getWeight(i)
      if (weight > 0) {
        weightSum += weight
        // Our loss function with {0, 1} labels is max(0, 1 - (2y - 1) (f_w(x)))
        // Therefore the gradient is -(2y - 1)*x
        val label = block.getLabel(i)
        val labelScaled = 2 * label - 1.0
        val loss = (1.0 - labelScaled * vec(i)) * weight
        if (loss > 0) {
          lossSum += loss
          val gradScale = -labelScaled * weight
          vec.values(i) = gradScale
        } else {
          vec.values(i) = 0.0
        }
      } else {
        vec.values(i) = 0.0
      }
      i += 1
    }

    // predictions are all correct, no gradient signal
    if (vec.values.forall(_ == 0)) return this

    if (fitIntercept) {
      // localGradientSumArray is of size numFeatures+1, so can not
      // be directly used as the output of BLAS.gemv
      val linearGradSumVec = new DenseVector(Array.ofDim[Double](numFeatures))
      BLAS.gemv(1.0, block.matrix.transpose, vec, 0.0, linearGradSumVec)
      linearGradSumVec.foreachNonZero { (i, v) => localGradientSumArray(i) += v }
      localGradientSumArray(numFeatures) += vec.values.sum
    } else {
      val gradSumVec = new DenseVector(localGradientSumArray)
      BLAS.gemv(1.0, block.matrix.transpose, vec, 1.0, gradSumVec)
    }

    this
  }
}
