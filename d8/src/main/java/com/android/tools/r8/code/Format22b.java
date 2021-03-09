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
import com.android.tools.r8.utils.StringUtils;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import com.android.tools.r8.utils.structural.StructuralSpecification;
import java.nio.ShortBuffer;

public abstract class Format22b extends Base2Format {

  public final short AA;
  public final short BB;
  public final byte CC;

  private static void specify(StructuralSpecification<Format22b, ?> spec) {
    spec.withInt(i -> i.AA).withInt(i -> i.BB).withInt(i -> i.CC);
  }

  // vAA | op | #+CC | VBB
  /*package*/ Format22b(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    CC = readSigned8BitValue(stream);
    BB = read8BitValue(stream);
  }

  /*package*/ Format22b(int AA, int BB, int CC) {
    assert 0 <= AA && AA <= Constants.U8BIT_MAX;
    assert 0 <= BB && BB <= Constants.U8BIT_MAX;
    assert Byte.MIN_VALUE <= CC && CC <= Byte.MAX_VALUE;
    this.AA = (short) AA;
    this.BB = (short) BB;
    this.CC = (byte) CC;
  }

  @Override
  public void write(
      ShortBuffer dest,
      ProgramMethod context,
      GraphLens graphLens,
      ObjectToOffsetMapping mapping,
      LensCodeRewriterUtils rewriter) {
    writeFirst(AA, dest);
    write16BitValue(combineBytes(CC, BB), dest);
  }

  @Override
  public final int hashCode() {
    return ((AA << 16) | (BB << 8) | CC) ^ getClass().hashCode();
  }

  @Override
  final int internalAcceptCompareTo(Instruction other, CompareToVisitor visitor) {
    return visitor.visit(this, (Format22b) other, Format22b::specify);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", v" + BB + ", #" + CC);
  }

  @Override
  public String toSmaliString(ClassNameMapper naming) {
    return formatSmaliString(
        "v" + AA + ", v" + BB + ", " + StringUtils.hexString(CC, 2) + "  # " + CC);
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
