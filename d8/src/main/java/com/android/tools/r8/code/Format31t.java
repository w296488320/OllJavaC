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

public abstract class Format31t extends Base3Format {

  public final short AA;
  protected /* offset */ int BBBBBBBB;

  private static void specify(StructuralSpecification<Format31t, ?> spec) {
    spec.withInt(i -> i.AA).withInt(i -> i.BBBBBBBB);
  }

  // vAA | op | +BBBBlo | +BBBBhi
  Format31t(int high, BytecodeStream stream) {
    super(stream);
    AA = (short) high;
    BBBBBBBB = readSigned32BitValue(stream);
  }

  Format31t(int register, int payloadOffset) {
    assert 0 <= register && register <= Constants.U8BIT_MAX;
    AA = (short) register;
    BBBBBBBB = payloadOffset;
  }

  @Override
  public void write(
      ShortBuffer dest,
      ProgramMethod context,
      GraphLens graphLens,
      ObjectToOffsetMapping mapping,
      LensCodeRewriterUtils rewriter) {
    writeFirst(AA, dest);
    assert (getOffset() + BBBBBBBB) % 2 == 0;
    write32BitValue(BBBBBBBB, dest);
  }

  @Override
  public boolean hasPayload() {
    return true;
  }

  @Override
  public int getPayloadOffset() {
    return BBBBBBBB;
  }

  public void setPayloadOffset(int offset) {
    BBBBBBBB = offset;
  }

  @Override
  public final int hashCode() {
    return ((BBBBBBBB << 8) | AA) ^ getClass().hashCode();
  }

  @Override
  final int internalAcceptCompareTo(Instruction other, CompareToVisitor visitor) {
    return visitor.visit(this, (Format31t) other, Format31t::specify);
  }

  @Override
  public String toString(ClassNameMapper naming) {
    return formatString("v" + AA + ", " + formatRelativeOffset(BBBBBBBB));
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
