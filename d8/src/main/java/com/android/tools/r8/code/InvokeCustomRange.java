// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.code;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.OffsetToObjectMapping;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.conversion.IRBuilder;
import com.android.tools.r8.ir.conversion.LensCodeRewriterUtils;
import java.nio.ShortBuffer;

public class InvokeCustomRange extends Format3rc<DexCallSite> {

  public static final int OPCODE = 0xfd;
  public static final String NAME = "InvokeCustomRange";
  public static final String SMALI_NAME = "invoke-custom/range";

  InvokeCustomRange(int high, BytecodeStream stream, OffsetToObjectMapping mapping) {
    super(high, stream, mapping.getCallSiteMap());
  }

  public InvokeCustomRange(int firstArgumentRegister, int argumentCount, DexCallSite callSite) {
    super(firstArgumentRegister, argumentCount, callSite);
  }

  @Override
  public String getName() {
    return NAME;
  }

  @Override
  public String getSmaliName() {
    return SMALI_NAME;
  }

  @Override
  public int getOpcode() {
    return OPCODE;
  }

  @Override
  public void collectIndexedItems(
      IndexedItemCollection indexedItems,
      ProgramMethod context,
      GraphLens graphLens,
      LensCodeRewriterUtils rewriter) {
    DexCallSite rewritten = rewriter.rewriteCallSite(getCallSite(), context);
    rewritten.collectIndexedItems(indexedItems);
  }

  @Override
  public DexCallSite getCallSite() {
    return BBBB;
  }

  @Override
  public void registerUse(UseRegistry registry) {
    registry.registerCallSite(getCallSite());
  }

  @Override
  public void buildIR(IRBuilder builder) {
    builder.addInvokeCustomRange(getCallSite(), AA, CCCC);
  }

  @Override
  public boolean canThrow() {
    return true;
  }

  @Override
  public void write(
      ShortBuffer dest,
      ProgramMethod context,
      GraphLens graphLens,
      ObjectToOffsetMapping mapping,
      LensCodeRewriterUtils rewriter) {
    DexCallSite rewritten = rewriter.rewriteCallSite(getCallSite(), context);
    writeFirst(AA, dest);
    write16BitReference(rewritten, dest, mapping);
    write16BitValue(CCCC, dest);
  }
}
