// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import java.util.TreeSet;

public class SortedProgramPackage extends ProgramPackage {

  public SortedProgramPackage(String packageDescriptor) {
    super(packageDescriptor, () -> new TreeSet<>((a, b) -> a.getType().compareTo(b.getType())));
  }
}
