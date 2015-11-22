package org.mimirframework.classification.tune;

/**
 * @author Isak Karlsson <isak-kar@dsv.su.se>
 */
public interface ParameterUpdator<T> {

  boolean hasUpdate();

  Object update(T toUpdate);

}