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
import com.android.tools.r8.shaking.AppInfoWithLivenessModifier;
import com.android.tools.r8.utils.IteratorUtils;
import com.android.tools.r8.utils.StringUtils;
import java.util.BitSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

public class OptimizationFeedbackDelayed extends OptimizationFeedback {

  // Caching of updated optimization info and processed status.
  private final AppInfoWithLivenessModifier appInfoWithLivenessModifier =
      AppInfoWithLiveness.modifier();
  private final Map<DexEncodedField, MutableFieldOptimizationInfo> fieldOptimizationInfos =
      new IdentityHashMap<>();
  private final Map<DexEncodedMethod, UpdatableMethodOptimizationInfo> methodOptimizationInfos =
      new IdentityHashMap<>();
  private final Map<DexEncodedMethod, ConstraintWithTarget> processed = new IdentityHashMap<>();

  private synchronized MutableFieldOptimizationInfo getFieldOptimizationInfoForUpdating(
      DexEncodedField field) {
    MutableFieldOptimizationInfo info = fieldOptimizationInfos.get(field);
    if (info != null) {
      return info;
    }
    info = field.getOptimizationInfo().mutableCopy();
    fieldOptimizationInfos.put(field, info);
    return info;
  }

  private synchronized UpdatableMethodOptimizationInfo getMethodOptimizationInfoForUpdating(
      DexEncodedMethod method) {
    UpdatableMethodOptimizationInfo info = methodOptimizationInfos.get(method);
    if (info != null) {
      return info;
    }
    info = method.getOptimizationInfo().mutableCopy();
    methodOptimizationInfos.put(method, info);
    return info;
  }

  private UpdatableMethodOptimizationInfo getMethodOptimizationInfoForUpdating(
      ProgramMethod method) {
    return getMethodOptimizationInfoForUpdating(method.getDefinition());
  }

  @Override
  public void fixupOptimizationInfos(
      AppView<?> appView, ExecutorService executorService, OptimizationInfoFixer fixer)
      throws ExecutionException {
    updateVisibleOptimizationInfo();
    super.fixupOptimizationInfos(appView, executorService, fixer);
  }

  @Override
  public void modifyAppInfoWithLiveness(Consumer<AppInfoWithLivenessModifier> consumer) {
    consumer.accept(appInfoWithLivenessModifier);
  }

  public void refineAppInfoWithLiveness(AppInfoWithLiveness appInfo) {
    appInfoWithLivenessModifier.modify(appInfo);
  }

  public void updateVisibleOptimizationInfo() {
    // Remove methods that have become obsolete. A method may become obsolete, for example, as a
    // result of the class staticizer, which aims to transform virtual methods on companion classes
    // into static methods on the enclosing class of the companion class.
    IteratorUtils.removeIf(
        methodOptimizationInfos.entrySet().iterator(), entry -> entry.getKey().isObsolete());
    IteratorUtils.removeIf(processed.entrySet().iterator(), entry -> entry.getKey().isObsolete());

    // Update field optimization info.
    fieldOptimizationInfos.forEach(DexEncodedField::setOptimizationInfo);
    fieldOptimizationInfos.clear();

    // Update method optimization info.
    methodOptimizationInfos.forEach(DexEncodedMethod::setOptimizationInfo);
    methodOptimizationInfos.clear();

    // Mark the processed methods as processed.
    processed.forEach(DexEncodedMethod::markProcessed);
    processed.clear();
  }

  public boolean noUpdatesLeft() {
    assert appInfoWithLivenessModifier.isEmpty();
    assert fieldOptimizationInfos.isEmpty()
        : StringUtils.join(fieldOptimizationInfos.keySet(), ", ");
    assert methodOptimizationInfos.isEmpty()
        : StringUtils.join(methodOptimizationInfos.keySet(), ", ");
    assert processed.isEmpty()
        : StringUtils.join(processed.keySet(), ", ");
    return true;
  }

  // FIELD OPTIMIZATION INFO:

  @Override
  public void markFieldCannotBeKept(DexEncodedField field) {
    getFieldOptimizationInfoForUpdating(field).cannotBeKept();
  }

  @Override
  public void markFieldAsDead(DexEncodedField field) {
    getFieldOptimizationInfoForUpdating(field).markAsDead();
  }

  @Override
  public void markFieldAsPropagated(DexEncodedField field) {
    getFieldOptimizationInfoForUpdating(field).markAsPropagated();
  }

  @Override
  public void markFieldHasDynamicLowerBoundType(DexEncodedField field, ClassTypeElement type) {
    getFieldOptimizationInfoForUpdating(field).setDynamicLowerBoundType(type);
  }

  @Override
  public void markFieldHasDynamicUpperBoundType(DexEncodedField field, TypeElement type) {
    getFieldOptimizationInfoForUpdating(field).setDynamicUpperBoundType(type);
  }

  @Override
  public void markFieldBitsRead(DexEncodedField field, int bitsRead) {
    getFieldOptimizationInfoForUpdating(field).joinReadBits(bitsRead);
  }

  @Override
  public void recordFieldHasAbstractValue(
      DexEncodedField field, AppView<AppInfoWithLiveness> appView, AbstractValue abstractValue) {
    assert appView.appInfo().getFieldAccessInfoCollection().contains(field.field);
    assert !appView.appInfo().getFieldAccessInfoCollection().get(field.field).hasReflectiveAccess();
    if (appView.appInfo().mayPropagateValueFor(field.field)) {
      getFieldOptimizationInfoForUpdating(field).setAbstractValue(abstractValue);
    }
  }

  // METHOD OPTIMIZATION INFO:

  @Override
  public void markForceInline(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markForceInline();
  }

  @Override
  public synchronized void markInlinedIntoSingleCallSite(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markInlinedIntoSingleCallSite();
  }

  @Override
  public void markMethodCannotBeKept(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).cannotBeKept();
  }

  @Override
  public synchronized void methodInitializesClassesOnNormalExit(
      DexEncodedMethod method, Set<DexType> initializedClasses) {
    getMethodOptimizationInfoForUpdating(method)
        .markInitializesClassesOnNormalExit(initializedClasses);
  }

  @Override
  public synchronized void methodReturnsArgument(DexEncodedMethod method, int argument) {
    getMethodOptimizationInfoForUpdating(method).markReturnsArgument(argument);
  }

  @Override
  public synchronized void methodReturnsAbstractValue(
      DexEncodedMethod method, AppView<AppInfoWithLiveness> appView, AbstractValue value) {
    if (appView.appInfo().mayPropagateValueFor(method.method)) {
      getMethodOptimizationInfoForUpdating(method).markReturnsAbstractValue(value);
    }
  }

  @Override
  public synchronized void unsetAbstractReturnValue(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).unsetAbstractReturnValue();
  }

  @Override
  public synchronized void methodReturnsObjectWithUpperBoundType(
      DexEncodedMethod method, AppView<?> appView, TypeElement type) {
    getMethodOptimizationInfoForUpdating(method).markReturnsObjectWithUpperBoundType(appView, type);
  }

  @Override
  public synchronized void methodReturnsObjectWithLowerBoundType(
      DexEncodedMethod method, ClassTypeElement type) {
    getMethodOptimizationInfoForUpdating(method).markReturnsObjectWithLowerBoundType(type);
  }

  @Override
  public synchronized void methodNeverReturnsNormally(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markNeverReturnsNormally();
  }

  @Override
  public synchronized void methodMayNotHaveSideEffects(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markMayNotHaveSideEffects();
  }

  @Override
  public synchronized void methodReturnValueOnlyDependsOnArguments(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markReturnValueOnlyDependsOnArguments();
  }

  @Override
  public synchronized void markAsPropagated(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markAsPropagated();
  }

  @Override
  public synchronized void markProcessed(DexEncodedMethod method, ConstraintWithTarget state) {
    processed.put(method, state);
  }

  @Override
  public synchronized void markCheckNullReceiverBeforeAnySideEffect(
      DexEncodedMethod method, boolean mark) {
    getMethodOptimizationInfoForUpdating(method).markCheckNullReceiverBeforeAnySideEffect(mark);
  }

  @Override
  public synchronized void markTriggerClassInitBeforeAnySideEffect(
      DexEncodedMethod method, boolean mark) {
    getMethodOptimizationInfoForUpdating(method).markTriggerClassInitBeforeAnySideEffect(mark);
  }

  @Override
  public synchronized void setBridgeInfo(DexEncodedMethod method, BridgeInfo bridgeInfo) {
    getMethodOptimizationInfoForUpdating(method).setBridgeInfo(bridgeInfo);
  }

  @Override
  public synchronized void setClassInlinerMethodConstraint(
      ProgramMethod method, ClassInlinerMethodConstraint classInlinerConstraint) {
    getMethodOptimizationInfoForUpdating(method)
        .setClassInlinerMethodConstraint(classInlinerConstraint);
  }

  @Override
  public synchronized void setClassInlinerEligibility(
      DexEncodedMethod method, ClassInlinerEligibilityInfo eligibility) {
    getMethodOptimizationInfoForUpdating(method).setClassInlinerEligibility(eligibility);
  }

  @Override
  public synchronized void setInstanceInitializerInfoCollection(
      DexEncodedMethod method,
      InstanceInitializerInfoCollection instanceInitializerInfoCollection) {
    getMethodOptimizationInfoForUpdating(method)
        .setInstanceInitializerInfoCollection(instanceInitializerInfoCollection);
  }

  @Override
  public synchronized void setInitializerEnablingJavaVmAssertions(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).setInitializerEnablingJavaAssertions();
  }

  @Override
  public synchronized void setParameterUsages(
      DexEncodedMethod method, ParameterUsagesInfo parameterUsagesInfo) {
    getMethodOptimizationInfoForUpdating(method).setParameterUsages(parameterUsagesInfo);
  }

  @Override
  public synchronized void setNonNullParamOrThrow(DexEncodedMethod method, BitSet facts) {
    getMethodOptimizationInfoForUpdating(method).setNonNullParamOrThrow(facts);
  }

  @Override
  public synchronized void setNonNullParamOnNormalExits(DexEncodedMethod method, BitSet facts) {
    getMethodOptimizationInfoForUpdating(method).setNonNullParamOnNormalExits(facts);
  }

  @Override
  public synchronized void setSimpleInliningConstraint(
      ProgramMethod method, SimpleInliningConstraint constraint) {
    getMethodOptimizationInfoForUpdating(method).setSimpleInliningConstraint(constraint);
  }

  @Override
  public synchronized void classInitializerMayBePostponed(DexEncodedMethod method) {
    getMethodOptimizationInfoForUpdating(method).markClassInitializerMayBePostponed();
  }
}
