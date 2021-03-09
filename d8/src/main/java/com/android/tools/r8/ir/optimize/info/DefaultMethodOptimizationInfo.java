// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info;

import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.inlining.NeverSimpleInliningConstraint;
import com.android.tools.r8.ir.analysis.inlining.SimpleInliningConstraint;
import com.android.tools.r8.ir.analysis.type.ClassTypeElement;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.analysis.value.AbstractValue;
import com.android.tools.r8.ir.analysis.value.UnknownValue;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.optimize.classinliner.ClassInlinerEligibilityInfo;
import com.android.tools.r8.ir.optimize.classinliner.constraint.AlwaysFalseClassInlinerMethodConstraint;
import com.android.tools.r8.ir.optimize.classinliner.constraint.ClassInlinerMethodConstraint;
import com.android.tools.r8.ir.optimize.info.ParameterUsagesInfo.ParameterUsage;
import com.android.tools.r8.ir.optimize.info.bridge.BridgeInfo;
import com.android.tools.r8.ir.optimize.info.initializer.DefaultInstanceInitializerInfo;
import com.android.tools.r8.ir.optimize.info.initializer.InstanceInitializerInfo;
import com.google.common.collect.ImmutableSet;
import java.util.BitSet;
import java.util.Set;

public class DefaultMethodOptimizationInfo extends MethodOptimizationInfo {

  public static final DefaultMethodOptimizationInfo DEFAULT_INSTANCE =
      new DefaultMethodOptimizationInfo();

  static Set<DexType> UNKNOWN_INITIALIZED_CLASSES_ON_NORMAL_EXIT = ImmutableSet.of();
  static int UNKNOWN_RETURNED_ARGUMENT = -1;
  static boolean UNKNOWN_NEVER_RETURNS_NORMALLY = false;
  static AbstractValue UNKNOWN_ABSTRACT_RETURN_VALUE = UnknownValue.getInstance();
  static TypeElement UNKNOWN_TYPE = null;
  static ClassTypeElement UNKNOWN_CLASS_TYPE = null;
  static boolean UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT = false;
  static boolean UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT = false;
  static ClassInlinerEligibilityInfo UNKNOWN_CLASS_INLINER_ELIGIBILITY = null;
  static boolean UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS = false;
  static ParameterUsagesInfo UNKNOWN_PARAMETER_USAGE_INFO = null;
  static boolean UNKNOWN_MAY_HAVE_SIDE_EFFECTS = true;
  static boolean UNKNOWN_RETURN_VALUE_ONLY_DEPENDS_ON_ARGUMENTS = false;
  static BitSet NO_NULL_PARAMETER_OR_THROW_FACTS = null;
  static BitSet NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS = null;

  private DefaultMethodOptimizationInfo() {}

  public static DefaultMethodOptimizationInfo getInstance() {
    return DEFAULT_INSTANCE;
  }

  @Override
  public boolean isDefaultMethodOptimizationInfo() {
    return true;
  }

  @Override
  public boolean isUpdatableMethodOptimizationInfo() {
    return false;
  }

  @Override
  public UpdatableMethodOptimizationInfo asUpdatableMethodOptimizationInfo() {
    return null;
  }

  @Override
  public boolean cannotBeKept() {
    return false;
  }

  @Override
  public boolean classInitializerMayBePostponed() {
    return false;
  }

  @Override
  public ClassInlinerMethodConstraint getClassInlinerMethodConstraint() {
    return AlwaysFalseClassInlinerMethodConstraint.getInstance();
  }

  @Override
  public TypeElement getDynamicUpperBoundType() {
    return UNKNOWN_TYPE;
  }

  @Override
  public ClassTypeElement getDynamicLowerBoundType() {
    return UNKNOWN_CLASS_TYPE;
  }

  @Override
  public Set<DexType> getInitializedClassesOnNormalExit() {
    return UNKNOWN_INITIALIZED_CLASSES_ON_NORMAL_EXIT;
  }

  @Override
  public InstanceInitializerInfo getContextInsensitiveInstanceInitializerInfo() {
    return DefaultInstanceInitializerInfo.getInstance();
  }

  @Override
  public InstanceInitializerInfo getInstanceInitializerInfo(InvokeDirect invoke) {
    return DefaultInstanceInitializerInfo.getInstance();
  }

  @Override
  public ParameterUsage getParameterUsages(int parameter) {
    assert UNKNOWN_PARAMETER_USAGE_INFO == null;
    return null;
  }

  @Override
  public BitSet getNonNullParamOrThrow() {
    return NO_NULL_PARAMETER_OR_THROW_FACTS;
  }

  @Override
  public BitSet getNonNullParamOnNormalExits() {
    return NO_NULL_PARAMETER_ON_NORMAL_EXITS_FACTS;
  }

  @Override
  public boolean hasBeenInlinedIntoSingleCallSite() {
    return false;
  }

  @Override
  public boolean isReachabilitySensitive() {
    return false;
  }

  @Override
  public boolean returnsArgument() {
    return false;
  }

  @Override
  public int getReturnedArgument() {
    assert returnsArgument();
    return UNKNOWN_RETURNED_ARGUMENT;
  }

  @Override
  public boolean neverReturnsNormally() {
    return UNKNOWN_NEVER_RETURNS_NORMALLY;
  }

  @Override
  public BridgeInfo getBridgeInfo() {
    return null;
  }

  @Override
  public ClassInlinerEligibilityInfo getClassInlinerEligibility() {
    return UNKNOWN_CLASS_INLINER_ELIGIBILITY;
  }

  @Override
  public AbstractValue getAbstractReturnValue() {
    return UNKNOWN_ABSTRACT_RETURN_VALUE;
  }

  @Override
  public SimpleInliningConstraint getSimpleInliningConstraint() {
    return NeverSimpleInliningConstraint.getInstance();
  }

  @Override
  public boolean isInitializerEnablingJavaVmAssertions() {
    return UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS;
  }

  @Override
  public boolean forceInline() {
    return false;
  }

  @Override
  public boolean neverInline() {
    return false;
  }

  @Override
  public boolean checksNullReceiverBeforeAnySideEffect() {
    return UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT;
  }

  @Override
  public boolean triggersClassInitBeforeAnySideEffect() {
    return UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT;
  }

  @Override
  public boolean mayHaveSideEffects() {
    return UNKNOWN_MAY_HAVE_SIDE_EFFECTS;
  }

  @Override
  public boolean returnValueOnlyDependsOnArguments() {
    return UNKNOWN_RETURN_VALUE_ONLY_DEPENDS_ON_ARGUMENTS;
  }

  @Override
  public boolean returnValueHasBeenPropagated() {
    return false;
  }

  @Override
  public UpdatableMethodOptimizationInfo mutableCopy() {
    return new UpdatableMethodOptimizationInfo();
  }
}
