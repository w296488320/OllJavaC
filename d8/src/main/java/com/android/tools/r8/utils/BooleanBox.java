// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.util.function.BooleanSupplier;

public class BooleanBox {

  private boolean value;

  public BooleanBox() {}

  public BooleanBox(boolean initialValue) {
    set(initialValue);
  }

  public void computeIfNotSet(BooleanSupplier supplier) {
    if (isFalse()) {
      set(supplier.getAsBoolean());
    }
  }

  public boolean get() {
    return value;
  }

  public boolean isFalse() {
    return !get();
  }

  public boolean isTrue() {
    return get();
  }

  public void set() {
    set(true);
  }

  public void set(boolean value) {
    this.value = value;
  }

  public void unset() {
    set(false);
  }
}
