// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import java.util.Collection;

public abstract class PolicyExecutor {

  /**
   * Given an initial collection of class groups which can potentially be merged, run all of the
   * policies registered to this policy executor on the class groups yielding a new collection of
   * class groups.
   */
  public abstract Collection<MergeGroup> run(
      Collection<MergeGroup> classes, Collection<Policy> policies);
}
