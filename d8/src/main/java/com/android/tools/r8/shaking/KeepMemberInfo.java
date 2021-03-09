// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.shaking.KeepInfo.Builder;

/** Immutable keep requirements for a member. */
public abstract class KeepMemberInfo<B extends Builder<B, K>, K extends KeepInfo<B, K>>
    extends KeepInfo<B, K> {

  KeepMemberInfo(B builder) {
    super(builder);
  }

  @Override
  public boolean isRepackagingAllowed(GlobalKeepInfoConfiguration configuration) {
    return configuration.isRepackagingEnabled()
        && !internalIsAccessModificationRequiredForRepackaging();
  }
}
