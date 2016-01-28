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

import java.util.function.BiConsumer;

/**
 * @author Isak Karlsson <isak-kar@dsv.su.se>
 */
class UpdatableEnumerationParameter<T, V> implements UpdatableParameter<T> {

  private final V[] enumeration;
  private final BiConsumer<T, V> updater;

  public UpdatableEnumerationParameter(BiConsumer<T, V> updater, V[] enumeration) {
    this.enumeration = enumeration;
    this.updater = updater;
  }

  @Override
  public ParameterUpdator<T> updator() {
    return new ParameterUpdator<T>() {
      private int current = 0;

      @Override
      public boolean hasUpdate() {
        return current < enumeration.length;
      }

      @Override
      public Object update(T toUpdate) {
        V value = enumeration[current++];
        updater.accept(toUpdate, value);
        return value;
      }
    };
  }
}
