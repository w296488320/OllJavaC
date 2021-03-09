// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import static com.android.tools.r8.utils.DescriptorUtils.descriptorToJavaType;

import com.android.tools.r8.cf.code.CfConstString;
import com.android.tools.r8.cf.code.CfDexItemBasedConstString;
import com.android.tools.r8.cf.code.CfInstruction;
import com.android.tools.r8.code.ConstString;
import com.android.tools.r8.code.DexItemBasedConstString;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.Code;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.DexValue.DexItemBasedValueString;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.ProguardClassFilter;
import com.android.tools.r8.utils.ThreadUtils;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * Replaces all instances of DexItemBasedConstString by ConstString, and all instances of
 * DexItemBasedValueString by DexValueString.
 */
class IdentifierMinifier {

  private final AppView<AppInfoWithLiveness> appView;
  private final ProguardClassFilter adaptClassStrings;
  private final NamingLens lens;

  IdentifierMinifier(AppView<AppInfoWithLiveness> appView, NamingLens lens) {
    this.appView = appView;
    this.adaptClassStrings = appView.options().getProguardConfiguration().getAdaptClassStrings();
    this.lens = lens;
  }

  void run(ExecutorService executorService) throws ExecutionException {
    if (!adaptClassStrings.isEmpty()) {
      adaptClassStrings(executorService);
    }
    replaceDexItemBasedConstString(executorService);
  }

  private void adaptClassStrings(ExecutorService executorService) throws ExecutionException {
    ThreadUtils.processItems(
        appView.appInfo().classes(),
        clazz -> {
          if (adaptClassStrings.matches(clazz.type)) {
            for (DexEncodedField field : clazz.staticFields()) {
              adaptClassStringsInStaticField(field);
            }
            clazz.forEachMethod(this::adaptClassStringsInMethod);
          }
        },
        executorService
    );
  }

  private void adaptClassStringsInStaticField(DexEncodedField encodedField) {
    assert encodedField.accessFlags.isStatic();
    DexValue staticValue = encodedField.getStaticValue();
    if (staticValue.isDexValueString()) {
      DexString original = staticValue.asDexValueString().getValue();
      encodedField.setStaticValue(new DexValueString(getRenamedStringLiteral(original)));
    }
  }

  private void adaptClassStringsInMethod(DexEncodedMethod encodedMethod) {
    // Abstract methods do not have code_item.
    if (encodedMethod.shouldNotHaveCode()) {
      return;
    }
    Code code = encodedMethod.getCode();
    if (code == null) {
      return;
    }
    if (code.isDexCode()) {
      for (Instruction instruction : code.asDexCode().instructions) {
        if (instruction.isConstString()) {
          ConstString cnst = instruction.asConstString();
          cnst.BBBB = getRenamedStringLiteral(cnst.getString());
        }
      }
    } else {
      assert code.isCfCode();
      for (CfInstruction instruction : code.asCfCode().getInstructions()) {
        if (instruction.isConstString()) {
          CfConstString cnst = instruction.asConstString();
          cnst.setString(getRenamedStringLiteral(cnst.getString()));
        }
      }
    }
  }

  private DexString getRenamedStringLiteral(DexString originalLiteral) {
    DexString rewrittenString = lens.lookupDescriptorForJavaTypeName(originalLiteral.toString());
    return rewrittenString == null
        ? originalLiteral
        : appView.dexItemFactory().createString(descriptorToJavaType(rewrittenString.toString()));
  }

  private void replaceDexItemBasedConstString(ExecutorService executorService)
      throws ExecutionException {
    ThreadUtils.processItems(
        appView.appInfo().classes(),
        clazz -> {
          // Some const strings could be moved to field's static value (from <clinit>).
          for (DexEncodedField field : clazz.staticFields()) {
            replaceDexItemBasedConstStringInStaticField(field);
          }
          clazz
              .methods(DexEncodedMethod::hasCode)
              .forEach(this::replaceDexItemBasedConstStringInMethod);
        },
        executorService
    );
  }

  private void replaceDexItemBasedConstStringInStaticField(DexEncodedField encodedField) {
    assert encodedField.accessFlags.isStatic();
    DexValue staticValue = encodedField.getStaticValue();
    if (staticValue instanceof DexItemBasedValueString) {
      DexItemBasedValueString cnst = (DexItemBasedValueString) staticValue;
      DexString replacement =
          cnst.getNameComputationInfo()
              .computeNameFor(cnst.getValue(), appView, appView.graphLens(), lens);
      encodedField.setStaticValue(new DexValueString(replacement));
    }
  }

  private void replaceDexItemBasedConstStringInMethod(DexEncodedMethod encodedMethod) {
    Code code = encodedMethod.getCode();
    assert code != null;
    if (code.isDexCode()) {
      Instruction[] instructions = code.asDexCode().instructions;
      for (int i = 0; i < instructions.length; ++i) {
        Instruction instruction = instructions[i];
        if (instruction.isDexItemBasedConstString()) {
          DexItemBasedConstString cnst = instruction.asDexItemBasedConstString();
          DexString replacement =
              cnst.getNameComputationInfo()
                  .computeNameFor(cnst.getItem(), appView, appView.graphLens(), lens);
          ConstString constString = new ConstString(cnst.AA, replacement);
          constString.setOffset(instruction.getOffset());
          instructions[i] = constString;
        }
      }
    } else {
      assert code.isCfCode();
      List<CfInstruction> instructions = code.asCfCode().getInstructions();
      List<CfInstruction> newInstructions = null;
      for (int i = 0; i < instructions.size(); ++i) {
        CfInstruction instruction = instructions.get(i);
        if (instruction.isDexItemBasedConstString()) {
          CfDexItemBasedConstString cnst = instruction.asDexItemBasedConstString();
          DexString replacement =
              cnst.getNameComputationInfo()
                  .computeNameFor(cnst.getItem(), appView, appView.graphLens(), lens);
          if (newInstructions == null) {
            newInstructions = new ArrayList<>(instructions);
          }
          newInstructions.set(i, new CfConstString(replacement));
        }
      }
      if (newInstructions != null) {
        code.asCfCode().setInstructions(newInstructions);
      }
    }
  }
}
