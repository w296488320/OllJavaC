// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info.initializer;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.shaking.AppInfoWithLiveness;

public class ContextInsensitiveInstanceInitializerInfoCollection
    extends InstanceInitializerInfoCollection {

  private final NonTrivialInstanceInitializerInfo info;

  ContextInsensitiveInstanceInitializerInfoCollection(NonTrivialInstanceInitializerInfo info) {
    this.info = info;
  }

  @Override
  public NonTrivialInstanceInitializerInfo getContextInsensitive() {
    return info;
  }

  @Override
  public NonTrivialInstanceInitializerInfo get(InvokeDirect invoke) {
    return info;
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public ContextInsensitiveInstanceInitializerInfoCollection rewrittenWithLens(
      AppView<AppInfoWithLiveness> appView, GraphLens lens) {
    NonTrivialInstanceInitializerInfo rewrittenInfo = info.rewrittenWithLens(appView, lens);
    if (rewrittenInfo != info) {
      return new ContextInsensitiveInstanceInitializerInfoCollection(rewrittenInfo);
    }
    return this;
  }
}
