// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.analysis.value;

import static com.android.tools.r8.ir.analysis.type.Nullability.definitelyNotNull;
import static com.android.tools.r8.ir.analysis.type.TypeElement.stringClassType;

import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DebugLocalInfo;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.DexItemBasedConstString;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.TypeAndLocalInfoSupplier;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.naming.dexitembasedstring.NameComputationInfo;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.Objects;

public class SingleDexItemBasedStringValue extends SingleConstValue {

  private final DexReference item;
  private final NameComputationInfo<?> nameComputationInfo;

  /** Intentionally package private, use {@link AbstractValueFactory} instead. */
  SingleDexItemBasedStringValue(DexReference item, NameComputationInfo<?> nameComputationInfo) {
    this.item = item;
    this.nameComputationInfo = nameComputationInfo;
  }

  @Override
  public boolean isSingleDexItemBasedStringValue() {
    return true;
  }

  @Override
  public SingleDexItemBasedStringValue asSingleDexItemBasedStringValue() {
    return this;
  }

  @Override
  public boolean equals(Object o) {
    if (o == null || getClass() != o.getClass()) {
      return false;
    }
    SingleDexItemBasedStringValue value = (SingleDexItemBasedStringValue) o;
    return item == value.item && nameComputationInfo == value.nameComputationInfo;
  }

  @Override
  public int hashCode() {
    return Objects.hash(item, nameComputationInfo);
  }

  @Override
  public String toString() {
    return "DexItemBasedConstString(" + item.toSourceString() + ")";
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
    DexItemBasedConstString instruction =
        new DexItemBasedConstString(returnedValue, item, nameComputationInfo);
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
    return appView
        .abstractValueFactory()
        .createSingleDexItemBasedStringValue(lens.rewriteReference(item), nameComputationInfo);
  }
}
