// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import java.nio.ShortBuffer;

abstract class Format10t extends Base1Format {

  public /* offset */ byte AA;

  // +AA | op
  Format10t(int high, BytecodeStream stream) {
    super(stream);
    // AA is an offset, so convert to signed.
    AA = (byte) high;
  }

  protected Format10t(int AA) {
    assert Byte.MIN_VALUE <= AA && AA <= Byte.MAX_VALUE;
    this.AA = (byte) AA;
  }

  @Override
  public void write(
      ShortBuffer dest,
      ProgramMethod context,
      GraphLens graphLens,
      ObjectToOffsetMapping mapping,
      LensCodeRewriterUtils rewriter) {
    writeFirst(AA, dest);
  }

  @Override
  public final int hashCode() {
    return AA ^ getClass().hashCode();
  }

  @Override
  final int internalAcceptCompareTo(Instruction other, CompareToVisitor visitor) {
    return visitor.visitInt(AA, ((Format10t) other).AA);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString(formatRelativeOffset(AA));
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(":label_" + (getOffset() + AA));
  }

  @Override
  public void collectIndexedItems(
      IndexedItemCollection indexedItems,
      ProgramMethod context,
      GraphLens graphLens,
      LensCodeRewriterUtils rewriter) {
    // No references.
  }
}
