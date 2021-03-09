// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.inlining.SimpleInliningConstraint;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.classinliner.ClassInlinerEligibilityInfo;
import com.android.tools.r8.ir.optimize.classinliner.constraint.ClassInlinerMethodConstraint;
import com.android.tools.r8.ir.optimize.info.bridge.BridgeInfo;
import com.android.tools.r8.ir.optimize.info.initializer.InstanceInitializerInfoCollection;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.BitSet;
import java.util.Set;

public abstract class OptimizationFeedbackIgnore extends OptimizationFeedback {

  private static final OptimizationFeedbackIgnore INSTANCE = new OptimizationFeedbackIgnore() {};

  protected OptimizationFeedbackIgnore() {}

  public static OptimizationFeedbackIgnore getInstance() {
    return INSTANCE;
  }

  // FIELD OPTIMIZATION INFO:

  @Override
  public void markFieldCannotBeKept(DexEncodedField field) {}

  @Override
  public void markFieldAsDead(DexEncodedField field) {}

  @Override
  public void markFieldAsPropagated(DexEncodedField field) {}

  @Override
  public void markFieldHasDynamicLowerBoundType(DexEncodedField field, ClassTypeElement type) {}

  @Override
  public void markFieldHasDynamicUpperBoundType(DexEncodedField field, TypeElement type) {}

  @Override
  public void markFieldBitsRead(DexEncodedField field, int bitsRead) {}

  @Override
  public void recordFieldHasAbstractValue(
      DexEncodedField field, AppView<AppInfoWithLiveness> appView, AbstractValue abstractValue) {}

  // METHOD OPTIMIZATION INFO:

  @Override
  public void markForceInline(DexEncodedMethod method) {}

  @Override
  public void markInlinedIntoSingleCallSite(DexEncodedMethod method) {}

  @Override
  public void markMethodCannotBeKept(DexEncodedMethod method) {}

  @Override
  public void methodInitializesClassesOnNormalExit(
      DexEncodedMethod method, Set<DexType> initializedClasses) {}

  @Override
  public void methodReturnsArgument(DexEncodedMethod method, int argument) {}

  @Override
  public void methodReturnsAbstractValue(
      DexEncodedMethod method, AppView<AppInfoWithLiveness> appView, AbstractValue value) {}

  @Override
  public void unsetAbstractReturnValue(DexEncodedMethod method) {}

  @Override
  public void methodReturnsObjectWithUpperBoundType(
      DexEncodedMethod method, AppView<?> appView, TypeElement type) {}

  @Override
  public void methodReturnsObjectWithLowerBoundType(
      DexEncodedMethod method, ClassTypeElement type) {}

  @Override
  public void methodMayNotHaveSideEffects(DexEncodedMethod method) {}

  @Override
  public void methodReturnValueOnlyDependsOnArguments(DexEncodedMethod method) {}

  @Override
  public void methodNeverReturnsNormally(DexEncodedMethod method) {}

  @Override
  public void markAsPropagated(DexEncodedMethod method) {}

  @Override
  public void markProcessed(DexEncodedMethod method, ConstraintWithTarget state) {}

  @Override
  public void markCheckNullReceiverBeforeAnySideEffect(DexEncodedMethod method, boolean mark) {}

  @Override
  public void markTriggerClassInitBeforeAnySideEffect(DexEncodedMethod method, boolean mark) {}

  @Override
  public void setBridgeInfo(DexEncodedMethod method, BridgeInfo bridgeInfo) {}

  @Override
  public void setClassInlinerMethodConstraint(
      ProgramMethod method, ClassInlinerMethodConstraint classInlinerConstraint) {}

  @Override
  public void setClassInlinerEligibility(
      DexEncodedMethod method, ClassInlinerEligibilityInfo eligibility) {}

  @Override
  public void setInstanceInitializerInfoCollection(
      DexEncodedMethod method,
      InstanceInitializerInfoCollection instanceInitializerInfoCollection) {}

  @Override
  public void setInitializerEnablingJavaVmAssertions(DexEncodedMethod method) {}

  @Override
  public void setParameterUsages(DexEncodedMethod method, ParameterUsagesInfo parameterUsagesInfo) {
  }

  @Override
  public void setNonNullParamOrThrow(DexEncodedMethod method, BitSet facts) {}

  @Override
  public void setNonNullParamOnNormalExits(DexEncodedMethod method, BitSet facts) {}

  @Override
  public void setSimpleInliningConstraint(
      ProgramMethod method, SimpleInliningConstraint constraint) {}

  @Override
  public void classInitializerMayBePostponed(DexEncodedMethod method) {}
}
