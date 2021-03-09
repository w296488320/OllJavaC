// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import com.android.tools.r8.utils.structural.StructuralItem;
import com.android.tools.r8.utils.structural.StructuralSpecification;
import java.util.function.BiPredicate;

public abstract class Format3rc<T extends IndexedDexItem & StructuralItem<T>> extends Base3Format {

  public final short AA;
  public final char CCCC;
  public T BBBB;

  private static <T extends IndexedDexItem & StructuralItem<T>> void specify(
      StructuralSpecification<Format3rc<T>, ?> spec) {
    spec.withInt(i -> i.AA).withInt(i -> i.CCCC).withItem(i -> i.BBBB);
  }

  // AA | op | [meth|type]@BBBBB | CCCC
  Format3rc(int high, BytecodeStream stream, T[] map) {
    super(stream);
    this.AA = (short) high;
    this.BBBB = map[read16BitValue(stream)];
    this.CCCC = read16BitValue(stream);
  }

  Format3rc(int firstArgumentRegister, int argumentCount, T dexItem) {
    assert 0 <= firstArgumentRegister && firstArgumentRegister <= Constants.U16BIT_MAX;
    assert 0 <= argumentCount && argumentCount <= Constants.U8BIT_MAX;
    this.CCCC = (char) firstArgumentRegister;
    this.AA = (short) argumentCount;
    BBBB = dexItem;
  }

  public T getItem() {
    return BBBB;
  }

  @Override
  public final int hashCode() {
    return ((CCCC << 24) | (BBBB.hashCode() << 4) | AA) ^ getClass().hashCode();
  }

  @SuppressWarnings("unchecked")
  @Override
  final int internalAcceptCompareTo(Instruction other, CompareToVisitor visitor) {
    return visitor.visit(this, (Format3rc<T>) other, Format3rc::specify);
  }

  private void appendRegisterRange(StringBuilder builder) {
    int firstRegister = CCCC;
    builder.append("{ ");
    builder.append("v").append(firstRegister);
    if (AA != 1) {
      builder.append(" .. v").append(firstRegister + AA - 1);
    }
    builder.append(" }");
  }

  @Override
  public String toString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterRange(builder);
    builder.append(" ");
    if (naming == null) {
      builder.append(BBBB.toSmaliString());
    } else {
      builder.append(naming.originalNameOf(BBBB));
    }
    return formatString(builder.toString());
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    StringBuilder builder = new StringBuilder();
    appendRegisterRange(builder);
    builder.append(", ");
    // TODO(sgjesse): Add support for smali name mapping.
    builder.append(BBBB.toSmaliString());
    return formatSmaliString(builder.toString());
  }

  @Override
  public boolean equals(Instruction other, BiPredicate<IndexedDexItem, IndexedDexItem> equality) {
    if (other == null || (this.getClass() != other.getClass())) {
      return false;
    }
    Format3rc<?> o = (Format3rc<?>) other;
    return o.AA == AA && o.CCCC == CCCC && equality.test(BBBB, o.BBBB);
  }
}
