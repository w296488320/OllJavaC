// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.diagnostic.internal;

import com.android.tools.r8.diagnostic.MissingDefinitionContext;
import com.android.tools.r8.origin.Origin;

public abstract class MissingDefinitionContextBase implements MissingDefinitionContext {

  private final Origin origin;

  MissingDefinitionContextBase(Origin origin) {
    this.origin = origin;
  }

  @Override
  public Origin getOrigin() {
    return origin;
  }

  abstract static class Builder<B extends Builder<B>> {

    Origin origin;

    public B setOrigin(Origin origin) {
      this.origin = origin;
      return self();
    }

    abstract B self();

    public abstract MissingDefinitionContext build();

    public boolean validate() {
      assert origin != null;
      return true;
    }
  }
}
