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
package org.briljantframework.mimir.classification.tune;

import java.util.List;

import org.briljantframework.data.dataframe.DataFrame;
import org.briljantframework.data.vector.Vector;
import org.briljantframework.mimir.evaluation.Validator;
import org.briljantframework.mimir.supervised.Predictor;

/**
 * @author Isak Karlsson
 */
public interface Tuner<P extends Predictor, O extends Predictor.Configurator<? extends Predictor.Learner<? extends P>>> {

  Tuner<P, O> setParameter(String name, UpdatableParameter<O> updater);

  Tuner<P, O> setValidator(Validator<P> validator);

  Validator<P> getValidator();

  /**
   * Optimize the paramters of the specified classifier over the specified data frame and labels
   *
   * @param toOptimize the classifier to optimize
   * @return the classifier configurations which has been optimized
   */
  List<Configuration<P>> tune(O toOptimize, DataFrame x, Vector y);

}
