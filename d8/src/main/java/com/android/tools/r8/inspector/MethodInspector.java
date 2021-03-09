// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.inspector;

import com.android.tools.r8.Keep;
import com.android.tools.r8.references.MethodReference;

/** Inspector for a method definition. */
@Keep
public interface MethodInspector {

  /** Get the method reference for the method of this inspector. */
  MethodReference getMethodReference();
}
