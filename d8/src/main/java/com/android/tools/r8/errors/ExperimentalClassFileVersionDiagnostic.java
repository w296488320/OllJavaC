// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.errors;

import com.android.tools.r8.Diagnostic;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;

public class ExperimentalClassFileVersionDiagnostic implements Diagnostic {

  private final String message;
  private final Origin origin;

  public ExperimentalClassFileVersionDiagnostic(Origin origin, String message) {
    this.origin = origin;
    this.message = message;
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  @Override
  public Position getPosition() {
    return Position.UNKNOWN;
  }

  @Override
  public String getDiagnosticMessage() {
    return message;
  }
}
