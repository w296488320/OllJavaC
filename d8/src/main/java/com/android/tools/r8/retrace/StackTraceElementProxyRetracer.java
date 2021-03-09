// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace;

import com.android.tools.r8.Keep;
import com.android.tools.r8.retrace.internal.StackTraceElementProxyRetracerImpl;
import java.util.stream.Stream;

@Keep
public interface StackTraceElementProxyRetracer<T, ST extends StackTraceElementProxy<T, ST>> {

  Stream<RetraceStackTraceProxy<T, ST>> retrace(ST element);

  static <T, ST extends StackTraceElementProxy<T, ST>>
      StackTraceElementProxyRetracer<T, ST> createDefault(Retracer retracer) {
    return new StackTraceElementProxyRetracerImpl<>(retracer);
  }
}
