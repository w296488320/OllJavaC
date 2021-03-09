// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.kotlin;

import static com.android.tools.r8.kotlin.KotlinClassMetadataReader.hasKotlinClassMetadataAnnotation;
import static com.android.tools.r8.kotlin.KotlinMetadataUtils.NO_KOTLIN_INFO;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexDefinitionSupplier;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.EnclosingMethodAttribute;
import com.android.tools.r8.graph.ProgramDefinition;
import com.android.tools.r8.graph.analysis.EnqueuerAnalysis;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedback;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedbackSimple;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.Enqueuer.EnqueuerDefinitionSupplier;
import com.google.common.collect.Sets;
import java.util.Set;

public class KotlinMetadataEnqueuerExtension extends EnqueuerAnalysis {

  private static final OptimizationFeedback feedback = OptimizationFeedbackSimple.getInstance();

  private final AppView<?> appView;
  private final EnqueuerDefinitionSupplier enqueuerDefinitionSupplier;
  private final Set<DexType> prunedTypes;

  public KotlinMetadataEnqueuerExtension(
      AppView<?> appView,
      EnqueuerDefinitionSupplier enqueuerDefinitionSupplier,
      Set<DexType> prunedTypes) {
    this.appView = appView;
    this.enqueuerDefinitionSupplier = enqueuerDefinitionSupplier;
    this.prunedTypes = prunedTypes;
  }

  private KotlinMetadataDefinitionSupplier definitionsForContext(ProgramDefinition context) {
    return new KotlinMetadataDefinitionSupplier(context, enqueuerDefinitionSupplier, prunedTypes);
  }

  @Override
  public void done(Enqueuer enqueuer) {
    DexType kotlinMetadataType = appView.dexItemFactory().kotlinMetadataType;
    DexClass kotlinMetadataClass =
        appView.appInfo().definitionForWithoutExistenceAssert(kotlinMetadataType);
    // In the first round of tree shaking build up all metadata such that it can be traced later.
    boolean keepMetadata =
        kotlinMetadataClass == null
            || kotlinMetadataClass.isNotProgramClass()
            || enqueuer.isPinned(kotlinMetadataType);
    if (enqueuer.getMode().isInitialTreeShaking()) {
      Set<DexMethod> keepByteCodeFunctions = Sets.newIdentityHashSet();
      Set<DexProgramClass> localOrAnonymousClasses = Sets.newIdentityHashSet();
      enqueuer.forAllLiveClasses(
          clazz -> {
            assert clazz.getKotlinInfo().isNoKotlinInformation();
            if (!keepMetadata || !enqueuer.isPinned(clazz.getType())) {
              if (KotlinClassMetadataReader.isLambda(appView, clazz)
                  && clazz.hasClassInitializer()) {
                feedback.classInitializerMayBePostponed(clazz.getClassInitializer());
              }
              clazz.setKotlinInfo(NO_KOTLIN_INFO);
              clazz.removeAnnotations(
                  annotation -> annotation.getAnnotationType() == kotlinMetadataType);
            } else {
              clazz.setKotlinInfo(
                  KotlinClassMetadataReader.getKotlinInfo(
                      appView.dexItemFactory().kotlin,
                      clazz,
                      appView.dexItemFactory(),
                      appView.options().reporter,
                      method -> keepByteCodeFunctions.add(method.getReference())));
              if (clazz.getEnclosingMethodAttribute() != null
                  && clazz.getEnclosingMethodAttribute().getEnclosingMethod() != null) {
                localOrAnonymousClasses.add(clazz);
              }
            }
          });
      appView.setCfByteCodePassThrough(keepByteCodeFunctions);
      for (DexProgramClass localOrAnonymousClass : localOrAnonymousClasses) {
        EnclosingMethodAttribute enclosingAttribute =
            localOrAnonymousClass.getEnclosingMethodAttribute();
        DexClass holder =
            definitionsForContext(localOrAnonymousClass)
                .definitionForHolder(enclosingAttribute.getEnclosingMethod());
        if (holder == null) {
          continue;
        }
        DexEncodedMethod method = holder.lookupMethod(enclosingAttribute.getEnclosingMethod());
        // If we cannot lookup the method, the conservative choice is keep the byte code.
        if (method == null
            || (method.getKotlinMemberInfo().isFunction()
                && method.getKotlinMemberInfo().asFunction().hasCrossInlineParameter())) {
          localOrAnonymousClass.forEachProgramMethod(
              m -> keepByteCodeFunctions.add(m.getReference()));
        }
      }
    } else {
      assert verifyKotlinMetadataModeledForAllClasses(enqueuer, keepMetadata);
    }
    // Trace through the modeled kotlin metadata.
    enqueuer.forAllLiveClasses(
        clazz -> {
          clazz.getKotlinInfo().trace(definitionsForContext(clazz));
          clazz.forEachProgramMember(
              member ->
                  member
                      .getDefinition()
                      .getKotlinMemberInfo()
                      .trace(definitionsForContext(member)));
        });
  }

  private boolean verifyKotlinMetadataModeledForAllClasses(
      Enqueuer enqueuer, boolean keepMetadata) {
    enqueuer.forAllLiveClasses(
        clazz -> {
          // Trace through class and member definitions
          assert !hasKotlinClassMetadataAnnotation(clazz, definitionsForContext(clazz))
              || !keepMetadata
              || !enqueuer.isPinned(clazz.type)
              || clazz.getKotlinInfo() != NO_KOTLIN_INFO;
        });
    return true;
  }

  public class KotlinMetadataDefinitionSupplier implements DexDefinitionSupplier {

    private final ProgramDefinition context;
    private final EnqueuerDefinitionSupplier enqueuerDefinitionSupplier;
    private final Set<DexType> prunedTypes;

    private KotlinMetadataDefinitionSupplier(
        ProgramDefinition context,
        EnqueuerDefinitionSupplier enqueuerDefinitionSupplier,
        Set<DexType> prunedTypes) {
      this.context = context;
      this.enqueuerDefinitionSupplier = enqueuerDefinitionSupplier;
      this.prunedTypes = prunedTypes;
    }

    @Override
    public DexClass definitionFor(DexType type) {
      // TODO(b/157700128) Metadata cannot at this point keep anything alive. Therefore, if a type
      //  has been pruned it may still be referenced, so we do an early check here to ensure it will
      //  not end up as. Ideally, those types should be removed by a pass on the modeled data.
      if (prunedTypes != null && prunedTypes.contains(type)) {
        return null;
      }
      return enqueuerDefinitionSupplier.definitionFor(type, context);
    }

    @Override
    public DexItemFactory dexItemFactory() {
      return appView.dexItemFactory();
    }
  }
}
