// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info.initializer;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.google.common.collect.ImmutableMap;
import java.util.Map.Entry;

public class ContextSensitiveInstanceInitializerInfoCollection
    extends InstanceInitializerInfoCollection {

  private final ImmutableMap<InstanceInitializerInfoContext, NonTrivialInstanceInitializerInfo>
      infos;

  protected ContextSensitiveInstanceInitializerInfoCollection(
      ImmutableMap<InstanceInitializerInfoContext, NonTrivialInstanceInitializerInfo> infos) {
    assert !infos.isEmpty();
    this.infos = infos;
  }

  @Override
  public InstanceInitializerInfo getContextInsensitive() {
    NonTrivialInstanceInitializerInfo result =
        infos.get(AlwaysTrueInstanceInitializerInfoContext.getInstance());
    return result != null ? result : DefaultInstanceInitializerInfo.getInstance();
  }

  @Override
  public InstanceInitializerInfo get(InvokeDirect invoke) {
    assert infos.keySet().stream().filter(context -> context.isSatisfiedBy(invoke)).count() <= 1;
    for (Entry<InstanceInitializerInfoContext, NonTrivialInstanceInitializerInfo> entry :
        infos.entrySet()) {
      if (entry.getKey().isSatisfiedBy(invoke)) {
        return entry.getValue();
      }
    }
    return DefaultInstanceInitializerInfo.getInstance();
  }

  @Override
  public boolean isEmpty() {
    return false;
  }

  @Override
  public InstanceInitializerInfoCollection rewrittenWithLens(
      AppView<AppInfoWithLiveness> appView, GraphLens lens) {
    Builder builder = builder();
    infos.forEach((context, info) -> builder.put(context, info.rewrittenWithLens(appView, lens)));
    return builder.build();
  }
}
