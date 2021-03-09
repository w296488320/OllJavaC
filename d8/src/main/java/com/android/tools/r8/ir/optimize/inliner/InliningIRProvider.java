// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.inliner;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.InvokeMethod;
import com.android.tools.r8.ir.code.NumberGenerator;
import com.android.tools.r8.ir.code.Position;
import com.android.tools.r8.ir.conversion.MethodProcessor;
import com.android.tools.r8.origin.Origin;
import java.util.IdentityHashMap;
import java.util.Map;

public class InliningIRProvider {

  private final AppView<?> appView;
  private final ProgramMethod context;
  private final NumberGenerator valueNumberGenerator;
  private final MethodProcessor methodProcessor;

  private final Map<InvokeMethod, IRCode> cache = new IdentityHashMap<>();

  public InliningIRProvider(
      AppView<?> appView, ProgramMethod context, IRCode code, MethodProcessor methodProcessor) {
    this.appView = appView;
    this.context = context;
    this.valueNumberGenerator = code.valueNumberGenerator;
    this.methodProcessor = methodProcessor;
  }

  public IRCode getInliningIR(InvokeMethod invoke, ProgramMethod method) {
    IRCode cached = cache.remove(invoke);
    if (cached != null) {
      return cached;
    }
    Position position = Position.getPositionForInlining(appView, invoke, context);
    Origin origin = method.getOrigin();
    return method.buildInliningIR(
        context, appView, valueNumberGenerator, position, origin, methodProcessor);
  }

  public IRCode getAndCacheInliningIR(InvokeMethod invoke, ProgramMethod method) {
    IRCode inliningIR = getInliningIR(invoke, method);
    cacheInliningIR(invoke, inliningIR);
    return inliningIR;
  }

  public void cacheInliningIR(InvokeMethod invoke, IRCode code) {
    IRCode existing = cache.put(invoke, code);
    assert existing == null;
  }

  public boolean verifyIRCacheIsEmpty() {
    assert cache.isEmpty();
    return true;
  }

  public boolean shouldApplyCodeRewritings(ProgramMethod method) {
    return methodProcessor.shouldApplyCodeRewritings(method);
  }
}
