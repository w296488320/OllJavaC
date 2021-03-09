// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.ir.analysis.type;

public class LongTypeElement extends WidePrimitiveTypeElement {

  private static final LongTypeElement INSTANCE = new LongTypeElement();

  static LongTypeElement getInstance() {
    return INSTANCE;
  }

  @Override
  public boolean isLong() {
    return true;
  }

  @Override
  public String toString() {
    return "LONG";
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(INSTANCE);
  }
}
