// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.DiagnosticsHandler;
import com.android.tools.r8.Keep;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.FieldReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.retrace.internal.RetracerImpl;

/** This is the main api interface for retrace. */
@Keep
public interface Retracer {

  RetraceClassResult retraceClass(ClassReference classReference);

  RetraceMethodResult retraceMethod(MethodReference methodReference);

  RetraceFrameResult retraceFrame(MethodReference methodReference, int position);

  RetraceFieldResult retraceField(FieldReference fieldReference);

  RetraceTypeResult retraceType(TypeReference typeReference);

  static Retracer createDefault(
      ProguardMapProducer proguardMapProducer, DiagnosticsHandler diagnosticsHandler) {
    return RetracerImpl.create(proguardMapProducer, diagnosticsHandler);
  }
}
