/*
 * Copyright 2016 The BigDL Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.intel.analytics.bigdl.nn

import java.util

import com.intel.analytics.bigdl.mkl.MKL
import com.intel.analytics.bigdl.nn.abstractnn.TensorModule
import com.intel.analytics.bigdl.tensor.{DoubleType, FloatType, Storage, Tensor}
import com.intel.analytics.bigdl.tensor.TensorNumericMath.TensorNumeric
import com.intel.analytics.bigdl.utils.Engine

import scala.concurrent.Future
import scala.math.exp
import scala.reflect.ClassTag

/**
 * The [[LogSoftMax]] module applies a LogSoftMax transformation to the input data
 * which is defined as:
 * f_i(x) = log(1 / a exp(x_i))
 * where a = sum_j[exp(x_j)]
 *
 * The input given in `forward(input)` must be either
 * a vector (1D tensor) or matrix (2D tensor).
 */
@SerialVersionUID(- 2954501946670913825L)
class LogSoftMax[T: ClassTag](
  implicit ev: TensorNumeric[T]) extends TensorModule[T] {
  @transient
  private var results: Array[Future[Unit]] = null
  @transient
  private var ones: Array[T] = null
  @transient
  private var buffer: Array[T] = null


  override def updateOutput(input: Tensor[T]): Tensor[T] = {
    require(input.dim() == 1 || input.dim() == 2,
      "LogSoftMax: " + ErrorInfo.constrainInputAsVectorOrBatch)
    output.resizeAs(input).copy(input)
    val (nframe, dim) =
      if (input.nDimension() == 1) (1, input.size(1)) else (input.size(1), input.size(2))

    if (nframe == 1) {
      updateOutputFrame(input, output)
    } else {
      if (results == null || results.length != nframe) {
        results = new Array[Future[Unit]](nframe)
      }
      var t = 1
      while (t <= nframe) {
        val _t = t
        results(_t - 1) = Engine.model.invoke(() => {
          updateOutputFrame(input.select(1, _t), output.select(1, _t))
        })
        t += 1
      }
      Engine.model.sync(results)
    }

    output
  }

  private def updateOutputFrame(in: Tensor[T], out: Tensor[T]): Unit = {
    if (ones == null || ones.length < in.nElement) {
      ones = Array.fill(in.nElement)(ev.fromType[Int](1))
    }
    if (buffer == null || buffer.length < in.nElement) {
      buffer = new Array[T](in.nElement)
    }

    ev.vExp(in.nElement,
      in.storage.array,
      in.storageOffset - 1,
      buffer,
      0)

    val dot = ev.dot(in.nElement,
      buffer,
      0,
      1,
      ones,
      0,
      1)

    val sum = ev.negative(ev.log(dot))

    ev.axpy(in.nElement,
      sum,
      ones,
      0,
      1,
      out.storage.array,
      out.storageOffset - 1,
      1)
  }

  override def updateGradInput(input: Tensor[T], gradOutput: Tensor[T]): Tensor[T] = {
    require(output.nDimension() == 1 || output.nDimension() == 2, "vector or matrix expected")
    require(gradOutput.dim() == input.dim(), "LogSoftMax: input and gradOutput shapes do not " +
      "match, input_dim: " + input.dim() + ", gradOutput_dim: " + gradOutput.dim())
    gradInput.resizeAs(input).copy(gradOutput)
    val (nframe, dim) =
      if (output.nDimension() == 1) (1, output.size(1)) else (output.size(1), output.size(2))


    if (nframe == 1) {
      updateGradInputFrame(output, gradInput)
    } else {
      if (results == null || results.length != nframe) {
        results = new Array[Future[Unit]](nframe)
      }
      var t = 1
      while (t <= nframe) {
        val _t = t
        results(_t - 1) = Engine.model.invoke(() => {
          updateGradInputFrame(output.select(1, _t), gradInput.select(1, _t))
        })
        t += 1
      }
      Engine.model.sync(results)
    }
    gradInput
  }

  private def updateGradInputFrame(out: Tensor[T], gradOut: Tensor[T]): Unit = {
    ev.vExp(out.nElement,
      out.storage.array,
      out.storageOffset - 1,
      buffer,
      0)

    val dot = ev.dot(gradOut.nElement,
      gradOut.storage.array,
      gradOut.storageOffset - 1,
      1,
      ones,
      0,
      1)

    val sum = ev.negative(dot)

    ev.axpy(gradOut.nElement,
      sum,
      buffer,
      0,
      1,
      gradOut.storage.array,
      gradOut.storageOffset - 1,
      1)
  }

  override def clearState() : this.type = {
    super.clearState()
    ones = null
    buffer = null
    results = null
    this
  }
}

object LogSoftMax {

  def apply[@specialized(Float, Double) T: ClassTag]()
    (implicit ev: TensorNumeric[T]) : LogSoftMax[T] = {
    new LogSoftMax[T]()
  }
  private val A0 = 1.0
  private val A1 = 0.125
  private val A2 = 0.0078125
  private val A3 = 0.00032552083
  private val A4 = 1.0172526e-5

  def expMinusApprox(x: Double): Double = {
    if (x < 0) {
      return exp(-x)
    } else {
      var y = 0.0
      if (x < 13.0) {
        y = A0 + x * (A1 + x * (A2 + x * (A3 + x * A4)))
        y *= y
        y *= y
        y *= y
        y = 1 / y
        return y
      }
    }

    return 0.0
  }
}
