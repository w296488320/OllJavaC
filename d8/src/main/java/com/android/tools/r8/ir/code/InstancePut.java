// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.LoadStoreHelper;
import com.android.tools.r8.cf.code.CfFieldInstruction;
import com.android.tools.r8.code.Iput;
import com.android.tools.r8.code.IputBoolean;
import com.android.tools.r8.code.IputByte;
import com.android.tools.r8.code.IputChar;
import com.android.tools.r8.code.IputObject;
import com.android.tools.r8.code.IputShort;
import com.android.tools.r8.code.IputWide;
import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldResolutionResult.SuccessfulFieldResolutionResult;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.AnalysisAssumption;
import com.android.tools.r8.ir.analysis.ClassInitializationAnalysis.Query;
import com.android.tools.r8.ir.conversion.CfBuilder;
import com.android.tools.r8.ir.conversion.DexBuilder;
import com.android.tools.r8.ir.optimize.Inliner.ConstraintWithTarget;
import com.android.tools.r8.ir.optimize.InliningConstraints;
import com.android.tools.r8.ir.regalloc.RegisterAllocator;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.Arrays;

public class InstancePut extends FieldInstruction implements InstanceFieldInstruction {

  public InstancePut(DexField field, Value object, Value value) {
    this(field, object, value, false);
  }

  // During structural changes, IRCode is not valid from IR building until the point where
  // several passes, such as the lens code rewriter, has been run. At this point, it can happen,
  // for example in the context of enum unboxing, that some InstancePut have temporarily
  // a primitive type as the object. Skip assertions in this case.
  public static InstancePut createPotentiallyInvalid(DexField field, Value object, Value value) {
    return new InstancePut(field, object, value, true);
  }

  private InstancePut(DexField field, Value object, Value value, boolean skipAssertion) {
    super(field, null, Arrays.asList(object, value));
    if (!skipAssertion) {
      assert object().verifyCompatible(ValueType.OBJECT);
      assert value().verifyCompatible(ValueType.fromDexType(field.type));
    }
  }

  @Override
  public int opcode() {
    return Opcodes.INSTANCE_PUT;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public Value object() {
    return inValues.get(0);
  }

  @Override
  public Value value() {
    return inValues.get(1);
  }

  @Override
  public void buildDex(DexBuilder builder) {
    com.android.tools.r8.code.Instruction instruction;
    int valueRegister = builder.allocatedRegister(value(), getNumber());
    int objectRegister = builder.allocatedRegister(object(), getNumber());
    DexField field = getField();
    switch (getType()) {
      case INT:
      case FLOAT:
        instruction = new Iput(valueRegister, objectRegister, field);
        break;
      case LONG:
      case DOUBLE:
        instruction = new IputWide(valueRegister, objectRegister, field);
        break;
      case OBJECT:
        instruction = new IputObject(valueRegister, objectRegister, field);
        break;
      case BOOLEAN:
        instruction = new IputBoolean(valueRegister, objectRegister, field);
        break;
      case BYTE:
        instruction = new IputByte(valueRegister, objectRegister, field);
        break;
      case CHAR:
        instruction = new IputChar(valueRegister, objectRegister, field);
        break;
      case SHORT:
        instruction = new IputShort(valueRegister, objectRegister, field);
        break;
      default:
        throw new Unreachable("Unexpected type: " + getType());
    }
    builder.add(this, instruction);
  }

  @Override
  public boolean instructionTypeCanThrow() {
    return true;
  }

  @Override
  public boolean instructionMayHaveSideEffects(
      AppView<?> appView, ProgramMethod context, SideEffectAssumption assumption) {
    if (appView.appInfo().hasLiveness()) {
      AppView<AppInfoWithLiveness> appViewWithLiveness = appView.withLiveness();
      AppInfoWithLiveness appInfoWithLiveness = appViewWithLiveness.appInfo();

      SuccessfulFieldResolutionResult resolutionResult =
          appInfoWithLiveness.resolveField(getField()).asSuccessfulResolution();
      if (internalInstructionInstanceCanThrow(appView, context, assumption, resolutionResult)) {
        return true;
      }

      DexEncodedField encodedField = resolutionResult.getResolvedField();
      assert encodedField != null : "NoSuchFieldError (resolution failure) should be caught.";

      if (encodedField.type().isAlwaysNull(appViewWithLiveness)) {
        return false;
      }

      return appInfoWithLiveness.isFieldRead(encodedField)
          || isStoringObjectWithFinalizer(appViewWithLiveness, encodedField);
    }

    // In D8, we always have to assume that the field can be read, and thus have side effects.
    return true;
  }

  @Override
  public boolean identicalAfterRegisterAllocation(Instruction other, RegisterAllocator allocator) {
    if (!super.identicalAfterRegisterAllocation(other, allocator)) {
      return false;
    }

    if (allocator.options().canHaveIncorrectJoinForArrayOfInterfacesBug()) {
      InstancePut instancePut = other.asInstancePut();

      // If the value being written by this instruction is an array, then make sure that the value
      // being written by the other instruction is the exact same value. Otherwise, the verifier
      // may incorrectly join the types of these arrays to Object[].
      if (value().getType().isArrayType() && value() != instancePut.value()) {
        return false;
      }
    }

    return true;
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    if (!other.isInstancePut()) {
      return false;
    }
    InstancePut o = other.asInstancePut();
    return o.getField() == getField() && o.getType() == getType();
  }

  @Override
  public int maxInValueRegister() {
    return Constants.U4BIT_MAX;
  }

  @Override
  public int maxOutValueRegister() {
    assert false : "InstancePut instructions define no values.";
    return 0;
  }

  @Override
  public ConstraintWithTarget inliningConstraint(
      InliningConstraints inliningConstraints, ProgramMethod context) {
    return inliningConstraints.forInstancePut(getField(), context);
  }

  @Override
  public boolean isInstanceFieldInstruction() {
    return true;
  }

  @Override
  public InstanceFieldInstruction asInstanceFieldInstruction() {
    return this;
  }

  @Override
  public boolean isInstancePut() {
    return true;
  }

  @Override
  public InstancePut asInstancePut() {
    return this;
  }

  @Override
  public String toString() {
    return super.toString() + "; field: " + getField().toSourceString();
  }

  @Override
  public void insertLoadAndStores(InstructionListIterator it, LoadStoreHelper helper) {
    helper.loadInValues(this, it);
  }

  @Override
  public void buildCf(CfBuilder builder) {
    builder.add(
        new CfFieldInstruction(
            org.objectweb.asm.Opcodes.PUTFIELD, getField(), builder.resolveField(getField())));
  }

  @Override
  public boolean throwsNpeIfValueIsNull(Value value, AppView<?> appView, ProgramMethod context) {
    return object() == value;
  }

  @Override
  public boolean throwsOnNullInput() {
    return true;
  }

  @Override
  public Value getNonNullInput() {
    return object();
  }

  @Override
  public boolean definitelyTriggersClassInitialization(
      DexType clazz,
      ProgramMethod context,
      AppView<AppInfoWithLiveness> appView,
      Query mode,
      AnalysisAssumption assumption) {
    return ClassInitializationAnalysis.InstructionUtils.forInstancePut(
        this, clazz, appView, mode, assumption);
  }

  @Override
  public boolean instructionMayTriggerMethodInvocation(AppView<?> appView, ProgramMethod context) {
    return false;
  }
}
