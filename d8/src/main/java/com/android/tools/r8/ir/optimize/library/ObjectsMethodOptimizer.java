// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.library;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexItemFactory.ObjectsMethods;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.TypeElement;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.Value;
import java.util.Set;

public class ObjectsMethodOptimizer extends StatelessLibraryMethodModelCollection {

  private final AppView<?> appView;
  private final DexItemFactory dexItemFactory;
  private final ObjectsMethods objectsMethods;

  ObjectsMethodOptimizer(AppView<?> appView) {
    DexItemFactory dexItemFactory = appView.dexItemFactory();
    this.appView = appView;
    this.dexItemFactory = dexItemFactory;
    this.objectsMethods = dexItemFactory.objectsMethods;
  }

  @Override
  public DexType getType() {
    return dexItemFactory.objectsType;
  }

  @Override
  public void optimize(
      IRCode code,
      InstructionListIterator instructionIterator,
      InvokeMethod invoke,
      DexClassAndMethod singleTarget,
      Set<Value> affectedValues) {
    if (objectsMethods.isRequireNonNullMethod(singleTarget.getReference())) {
      optimizeRequireNonNull(instructionIterator, invoke, affectedValues);
    } else if (singleTarget.getReference() == objectsMethods.toStringWithObject) {
      optimizeToStringWithObject(code, instructionIterator, invoke, affectedValues);
    }
  }

  private void optimizeRequireNonNull(
      InstructionListIterator instructionIterator, InvokeMethod invoke, Set<Value> affectedValues) {
    Value inValue = invoke.getFirstArgument();
    if (inValue.getType().isDefinitelyNotNull()) {
      Value outValue = invoke.outValue();
      if (outValue != null) {
        affectedValues.addAll(outValue.affectedValues());
        outValue.replaceUsers(inValue);
      }
      // TODO(b/152853271): Debugging information is lost here (DebugLocalWrite may be required).
      instructionIterator.removeOrReplaceByDebugLocalRead();
    }
  }

  private void optimizeToStringWithObject(
      IRCode code,
      InstructionListIterator instructionIterator,
      InvokeMethod invoke,
      Set<Value> affectedValues) {
    Value object = invoke.getFirstArgument();
    TypeElement type = object.getType();

    // Optimize Objects.toString(null) into "null".
    if (type.isDefinitelyNull()) {
      instructionIterator.replaceCurrentInstructionWithConstString(appView, code, "null");
      if (invoke.hasOutValue()) {
        affectedValues.addAll(invoke.outValue().affectedValues());
      }
      return;
    }

    // Optimize Objects.toString(nonNullString) into nonNullString.
    if (type.isDefinitelyNotNull() && type.isStringType(dexItemFactory)) {
      if (invoke.hasOutValue()) {
        affectedValues.addAll(invoke.outValue().affectedValues());
        invoke.outValue().replaceUsers(object);
      }
      instructionIterator.removeOrReplaceByDebugLocalRead();
    }
  }
}
