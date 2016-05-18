/**
 * The MIT License (MIT)
 *
 * Copyright (c) 2016 Isak Karlsson
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and
 * associated documentation files (the "Software"), to deal in the Software without restriction,
 * including without limitation the rights to use, copy, modify, merge, publish, distribute,
 * sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or
 * substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT
 * NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
 * NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM,
 * DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */
package org.briljantframework.mimir.shapelet;

import org.briljantframework.DoubleSequence;
import org.briljantframework.data.series.Series;

/**
 * A z-normalized sub sequence view of another MatrixLike
 * <p>
 * Created by Isak Karlsson on 28/09/14.
 */
public class NormalizedShapelet extends Shapelet {

  private final double sigma;
  private final double mean;

  public NormalizedShapelet(int start, int length, DoubleSequence timeSeries) {
    super(start, length, timeSeries);
    if (timeSeries instanceof NormalizedShapelet) {
      this.sigma = ((NormalizedShapelet) timeSeries).sigma;
      this.mean = ((NormalizedShapelet) timeSeries).mean;
    } else {
      double ex = 0;
      double ex2 = 0;
      int size = start + length;
      for (int i = start; i < size; i++) {
        double v = timeSeries.getDouble(i);
        ex += v;
        ex2 += v * v;
      }
      this.mean = ex / length;
      if (length == 1) {
        this.sigma = 0;
      } else {
        this.sigma = Math.sqrt(ex2 / length - mean * mean);
      }
    }
  }

  @Override
  public double getDouble(int i) {
    if (sigma == 0) {
      return 0;
    } else {
      return (super.getDouble(i) - mean) / sigma;
    }
  }
}
