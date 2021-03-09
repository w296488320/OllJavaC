// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.library;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InstructionListIterator;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.ir.optimize.library.LibraryMethodModelCollection.State;
import java.util.Set;

/** Used to model the behavior of library methods for optimization purposes. */
public interface LibraryMethodModelCollection<T extends State> {

  default T createInitialState(MethodProcessor methodProcessor) {
    return null;
  }

  /**
   * The library class whose methods are being modeled by this collection of models. As an example,
   * {@link BooleanMethodOptimizer} is modeling {@link Boolean}).
   */
  DexType getType();

  /**
   * Invoked for instructions in {@param code} that invoke a method on the class returned by {@link
   * #getType()}. The given {@param singleTarget} is guaranteed to be non-null.
   */
  void optimize(
      IRCode code,
      InstructionListIterator instructionIterator,
      InvokeMethod invoke,
      DexClassAndMethod singleTarget,
      Set<Value> affectedValues,
      T state,
      MethodProcessingContext methodProcessingContext);

  @SuppressWarnings("unchecked")
  default void optimize(
      IRCode code,
      InstructionListIterator instructionIterator,
      InvokeMethod invoke,
      DexClassAndMethod singleTarget,
      Set<Value> affectedValues,
      Object state,
      MethodProcessingContext methodProcessingContext) {
    optimize(
        code,
        instructionIterator,
        invoke,
        singleTarget,
        affectedValues,
        (T) state,
        methodProcessingContext);
  }

  /** Thread local optimization state to allow caching, etc. */
  interface State {}
}
