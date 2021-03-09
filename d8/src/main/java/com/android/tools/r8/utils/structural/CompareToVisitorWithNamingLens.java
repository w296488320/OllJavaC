// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils.structural;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.utils.structural.StructuralItem.CompareToAccept;

public class CompareToVisitorWithNamingLens extends CompareToVisitorBase {

  public static <T> int run(T item1, T item2, NamingLens namingLens, StructuralMapping<T> visit) {
    return run(item1, item2, namingLens, (i1, i2, visitor) -> visitor.visit(i1, i2, visit));
  }

  public static <T> int run(
      T item1, T item2, NamingLens namingLens, CompareToAccept<T> compareToAccept) {
    CompareToVisitorWithNamingLens state = new CompareToVisitorWithNamingLens(namingLens);
    return compareToAccept.acceptCompareTo(item1, item2, state);
  }

  private final NamingLens namingLens;

  public CompareToVisitorWithNamingLens(NamingLens namingLens) {
    this.namingLens = namingLens;
  }

  @Override
  public int visitDexType(DexType type1, DexType type2) {
    return debug(
        namingLens
            .lookupDescriptor(type1)
            .acceptCompareTo(namingLens.lookupDescriptor(type2), this));
  }

  @Override
  public int visitDexField(DexField field1, DexField field2) {
    int order = field1.holder.acceptCompareTo(field2.holder, this);
    if (order != 0) {
      return debug(order);
    }
    order = namingLens.lookupName(field1).acceptCompareTo(namingLens.lookupName(field2), this);
    if (order != 0) {
      return debug(order);
    }
    return debug(field1.type.acceptCompareTo(field2.type, this));
  }

  @Override
  public int visitDexMethod(DexMethod method1, DexMethod method2) {
    int order = method1.holder.acceptCompareTo(method2.holder, this);
    if (order != 0) {
      return debug(order);
    }
    order = namingLens.lookupName(method1).acceptCompareTo(namingLens.lookupName(method2), this);
    if (order != 0) {
      return debug(order);
    }
    return debug(method1.proto.acceptCompareTo(method2.proto, this));
  }
}
