// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.value;

import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.analysis.type.TypeElement.stringClassType;

import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.ConstString;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.TypeAndLocalInfoSupplier;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.shaking.AppInfoWithLiveness;

public class SingleStringValue extends SingleConstValue {

  private final DexString string;

  /** Intentionally package private, use {@link AbstractValueFactory} instead. */
  SingleStringValue(DexString string) {
    this.string = string;
  }

  @Override
  public boolean isSingleStringValue() {
    return true;
  }

  @Override
  public SingleStringValue asSingleStringValue() {
    return this;
  }

  public DexString getDexString() {
    return string;
  }

  @Override
  public boolean equals(Object o) {
    return this == o;
  }

  @Override
  public int hashCode() {
    return string.hashCode();
  }

  @Override
  public String toString() {
    return "SingleStringValue(" + string + ")";
  }

  @Override
  public Instruction createMaterializingInstruction(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      IRCode code,
      TypeAndLocalInfoSupplier info) {
    TypeElement typeLattice = info.getOutType();
    DebugLocalInfo debugLocalInfo = info.getLocalInfo();
    assert typeLattice.isClassType();
    assert appView
        .isSubtype(appView.dexItemFactory().stringType, typeLattice.asClassType().getClassType())
        .isTrue();
    Value returnedValue =
        code.createValue(stringClassType(appView, definitelyNotNull()), debugLocalInfo);
    ConstString instruction = new ConstString(returnedValue, string);
    assert !instruction.instructionInstanceCanThrow();
    return instruction;
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
