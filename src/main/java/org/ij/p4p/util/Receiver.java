package org.ij.p4p.util;

/**
 * Analogue to the Guava Supplier interface.
 */
public interface Receiver<T> {
  public void receive(T t);
}
