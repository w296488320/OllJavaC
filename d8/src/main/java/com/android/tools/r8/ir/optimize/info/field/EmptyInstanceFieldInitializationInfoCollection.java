// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info.field;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.function.BiConsumer;

/**
 * Represents that no information is known about the way a constructor initializes the instance
 * fields of the newly created instance.
 */
public class EmptyInstanceFieldInitializationInfoCollection
    extends InstanceFieldInitializationInfoCollection {

  private static final EmptyInstanceFieldInitializationInfoCollection INSTANCE =
      new EmptyInstanceFieldInitializationInfoCollection();

  private EmptyInstanceFieldInitializationInfoCollection() {}

  public static EmptyInstanceFieldInitializationInfoCollection getInstance() {
    return INSTANCE;
  }

  @Override
  public void forEach(
      DexDefinitionSupplier definitions,
      BiConsumer<DexEncodedField, InstanceFieldInitializationInfo> consumer) {
    // Intentionally empty.
  }

  @Override
  public void forEachWithDeterministicOrder(
      DexDefinitionSupplier definitions,
      BiConsumer<DexEncodedField, InstanceFieldInitializationInfo> consumer) {
    // Intentionally empty.
  }

  @Override
  public InstanceFieldInitializationInfo get(DexEncodedField field) {
    return UnknownInstanceFieldInitializationInfo.getInstance();
  }

  @Override
  public boolean isEmpty() {
    return true;
  }

  @Override
  public InstanceFieldInitializationInfoCollection rewrittenWithLens(
      AppView<AppInfoWithLiveness> appView, GraphLens lens) {
    return this;
  }

  @Override
  public String toString() {
    return "EmptyInstanceFieldInitializationInfoCollection";
  }
}
