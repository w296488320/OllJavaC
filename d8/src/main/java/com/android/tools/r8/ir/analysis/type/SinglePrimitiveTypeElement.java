// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.analysis.type;

/** A {@link TypeElement} that abstracts primitive types, which fit in 32 bits. */
public class SinglePrimitiveTypeElement extends PrimitiveTypeElement {

  private static final SinglePrimitiveTypeElement INSTANCE = new SinglePrimitiveTypeElement();

  SinglePrimitiveTypeElement() {
    super();
  }

  static SinglePrimitiveTypeElement getInstance() {
    return INSTANCE;
  }

  @Override
  public boolean isSinglePrimitive() {
    return true;
  }

  @Override
  public String toString() {
    return "SINGLE";
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(INSTANCE);
  }
}
