// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.Constants;
import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import com.android.tools.r8.utils.structural.StructuralSpecification;
import java.nio.ShortBuffer;

abstract class Format51l extends Base5Format {

  public final short AA;
  public final long BBBBBBBBBBBBBBBB;

  private static void specify(StructuralSpecification<Format51l, ?> spec) {
    spec.withInt(i -> i.AA).withLong(i -> i.BBBBBBBBBBBBBBBB);
  }

  // AA | op | BBBB | BBBB | BBBB | BBBB
  Format51l(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    BBBBBBBBBBBBBBBB = read64BitValue(stream);
  }

  public Format51l(int AA, long BBBBBBBBBBBBBBBB) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    this.AA = (short) AA;
    this.BBBBBBBBBBBBBBBB = BBBBBBBBBBBBBBBB;
  }

  @Override
  public void write(
      ShortBuffer dest,
      ProgramMethod context,
      GraphLens graphLens,
      ObjectToOffsetMapping mapping,
      LensCodeRewriterUtils rewriter) {
    writeFirst(AA, dest);
    write64BitValue(BBBBBBBBBBBBBBBB, dest);
  }

  @Override
  public final int hashCode() {
    return ((((int) BBBBBBBBBBBBBBBB) << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  final int internalAcceptCompareTo(Instruction other, CompareToVisitor visitor) {
    return visitor.visit(this, (Format51l) other, Format51l::specify);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", #" + BBBBBBBBBBBBBBBB);
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
