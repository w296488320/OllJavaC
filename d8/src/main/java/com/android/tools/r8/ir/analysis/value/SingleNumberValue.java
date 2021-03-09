// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.value;

import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.ConstNumber;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.TypeAndLocalInfoSupplier;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.shaking.AppInfoWithLiveness;

public class SingleNumberValue extends SingleConstValue {

  private final long value;

  /** Intentionally package private, use {@link AbstractValueFactory} instead. */
  SingleNumberValue(long value) {
    this.value = value;
  }

  @Override
  public boolean isSingleBoolean() {
    return isFalse() || isTrue();
  }

  @Override
  public boolean isFalse() {
    return value == 0;
  }

  @Override
  public boolean isTrue() {
    return value == 1;
  }

  @Override
  public boolean isSingleNumberValue() {
    return true;
  }

  @Override
  public SingleNumberValue asSingleNumberValue() {
    return this;
  }

  public boolean getBooleanValue() {
    assert value == 0 || value == 1;
    return value != 0;
  }

  public double getDoubleValue() {
    return Double.longBitsToDouble(value);
  }

  public float getFloatValue() {
    return Float.intBitsToFloat((int) value);
  }

  public int getIntValue() {
    return (int) value;
  }

  public long getLongValue() {
    return value;
  }

  public long getValue() {
    return value;
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return System.identityHashCode(this);
  }

  @Override
  public String toString() {
    return "SingleNumberValue(" + value + ")";
  }

  @Override
  public Instruction createMaterializingInstruction(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      IRCode code,
      TypeAndLocalInfoSupplier info) {
    TypeElement typeLattice = info.getOutType();
    DebugLocalInfo debugLocalInfo = info.getLocalInfo();
    assert !typeLattice.isReferenceType() || value == 0;
    Value returnedValue =
        code.createValue(
            typeLattice.isReferenceType() ? TypeElement.getNull() : typeLattice, debugLocalInfo);
    return new ConstNumber(returnedValue, value);
  }

  @Override
  public boolean isMaterializableInContext(
      AppView<AppInfoWithLiveness> appView, ProgramMethod context) {
    return true;
  }

  @Override
  public boolean isMaterializableInAllContexts(AppView<AppInfoWithLiveness> appView) {
    return true;
  }

  @Override
  public SingleValue rewrittenWithLens(AppView<AppInfoWithLiveness> appView, GraphLens lens) {
    return this;
  }
}
