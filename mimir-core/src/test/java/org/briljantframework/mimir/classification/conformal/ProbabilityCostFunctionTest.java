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
package org.briljantframework.mimir.classification.conformal;

import org.briljantframework.array.DoubleArray;
import org.briljantframework.data.vector.Vector;
import org.junit.Assert;
import org.junit.Test;

/**
 * @author Isak Karlsson <isak-kar@dsv.su.se>
 */
public class ProbabilityCostFunctionTest {

  @Test
  public void testMargin() throws Exception {
    DoubleArray x = DoubleArray.of(0, 0, 1, 1, 0.9, 0, 0, 0.1, 0).reshape(3, 3);
    ProbabilityCostFunction margin = ProbabilityCostFunction.margin();
    DoubleArray m =
        ProbabilityCostFunction.estimate(margin, x, Vector.of(1, 1, 1), Vector.of(0, 1, 2));
    Assert.assertArrayEquals(DoubleArray.of(0, 0.1, 1.0).data(), m.data(), 0.01);
  }

  @Test
  public void testInverseProbability() throws Exception {

  }
}
