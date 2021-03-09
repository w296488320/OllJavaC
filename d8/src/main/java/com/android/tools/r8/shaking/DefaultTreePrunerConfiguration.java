// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.graph.DexEncodedField;

public class DefaultTreePrunerConfiguration implements TreePrunerConfiguration {

  private static final DefaultTreePrunerConfiguration INSTANCE =
      new DefaultTreePrunerConfiguration();

  public DefaultTreePrunerConfiguration() {}

  public static DefaultTreePrunerConfiguration getInstance() {
    return INSTANCE;
  }

  @Override
  public boolean isReachableOrReferencedField(AppInfoWithLiveness appInfo, DexEncodedField field) {
    return appInfo.isFieldRead(field) || appInfo.isFieldWritten(field);
  }
}
