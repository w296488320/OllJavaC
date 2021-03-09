// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GenericSignature.MethodTypeSignature;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.ResolutionResult.SingleResolutionResult;
import com.android.tools.r8.ir.synthetic.AbstractSynthesizedCode;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.OptionalBool;
import com.android.tools.r8.utils.structural.Ordered;
import com.google.common.collect.Iterables;
import it.unimi.dsi.fastutil.ints.Int2ReferenceAVLTreeMap;
import it.unimi.dsi.fastutil.ints.Int2ReferenceSortedMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.util.ArrayList;
import java.util.List;

public class VirtualMethodMerger {
  private final DexItemFactory dexItemFactory;
  private final MergeGroup group;
  private final List<ProgramMethod> methods;
  private final AppView<AppInfoWithLiveness> appView;
  private final DexMethod superMethod;

  public VirtualMethodMerger(
      AppView<AppInfoWithLiveness> appView,
      MergeGroup group,
      List<ProgramMethod> methods,
      DexMethod superMethod) {
    this.dexItemFactory = appView.dexItemFactory();
    this.group = group;
    this.methods = methods;
    this.appView = appView;
    this.superMethod = superMethod;
  }

  public static class Builder {
    private final List<ProgramMethod> methods = new ArrayList<>();

    public Builder add(ProgramMethod constructor) {
      methods.add(constructor);
      return this;
    }

    /** Get the super method handle if this method overrides a parent method. */
    private DexMethod superMethod(AppView<AppInfoWithLiveness> appView, DexProgramClass target) {
      DexMethod template = methods.iterator().next().getReference();
      SingleResolutionResult resolutionResult =
          appView
              .appInfo()
              .resolveMethodOnClass(template, target.getSuperType())
              .asSingleResolution();

      if (resolutionResult == null || resolutionResult.getResolvedMethod().isAbstract()) {
        // If there is no super method or the method is abstract it should not be called.
        return null;
      }
      if (resolutionResult.getResolvedHolder().isInterface()) {
        // Ensure that invoke virtual isn't called on an interface method.
        return resolutionResult
            .getResolvedMethod()
            .getReference()
            .withHolder(target.getSuperType(), appView.dexItemFactory());
      }
      return resolutionResult.getResolvedMethod().getReference();
    }

    public VirtualMethodMerger build(AppView<AppInfoWithLiveness> appView, MergeGroup group) {
      // If not all the classes are in the merge group, find the fallback super method to call.
      DexMethod superMethod =
          methods.size() < group.size() ? superMethod(appView, group.getTarget()) : null;
      return new VirtualMethodMerger(appView, group, methods, superMethod);
    }
  }

  public DexMethod getMethodReference() {
    return methods.iterator().next().getReference();
  }

  public int getArity() {
    return getMethodReference().getArity();
  }

  private DexMethod moveMethod(ClassMethodsBuilder classMethodsBuilder, ProgramMethod oldMethod) {
    DexMethod oldMethodReference = oldMethod.getReference();
    DexMethod method =
        dexItemFactory.createFreshMethodName(
            oldMethodReference.name.toSourceString(),
            oldMethod.getHolderType(),
            oldMethodReference.proto,
            group.getTarget().getType(),
            classMethodsBuilder::isFresh);

    DexEncodedMethod encodedMethod = oldMethod.getDefinition().toTypeSubstitutedMethod(method);
    MethodAccessFlags flags = encodedMethod.getAccessFlags();
    flags.unsetProtected();
    flags.unsetPublic();
    flags.setPrivate();
    classMethodsBuilder.addDirectMethod(encodedMethod);

    return encodedMethod.method;
  }

  private MethodAccessFlags getAccessFlags() {
    Iterable<MethodAccessFlags> allFlags =
        Iterables.transform(methods, ProgramMethod::getAccessFlags);
    MethodAccessFlags result = allFlags.iterator().next().copy();
    assert Iterables.all(allFlags, flags -> !flags.isNative());
    assert !result.isStrict() || Iterables.all(allFlags, MethodAccessFlags::isStrict);
    assert !result.isSynchronized() || Iterables.all(allFlags, MethodAccessFlags::isSynchronized);
    if (result.isAbstract() && Iterables.any(allFlags, flags -> !flags.isAbstract())) {
      result.unsetAbstract();
    }
    if (result.isBridge() && Iterables.any(allFlags, flags -> !flags.isBridge())) {
      result.unsetBridge();
    }
    if (result.isFinal() && Iterables.any(allFlags, flags -> !flags.isFinal())) {
      result.unsetFinal();
    }
    if (result.isSynthetic() && Iterables.any(allFlags, flags -> !flags.isSynthetic())) {
      result.unsetSynthetic();
    }
    if (result.isVarargs() && Iterables.any(allFlags, flags -> !flags.isVarargs())) {
      result.unsetVarargs();
    }
    result.unsetDeclaredSynchronized();
    return result;
  }

  private DexMethod getNewMethodReference() {
    return ListUtils.first(methods).getReference().withHolder(group.getTarget(), dexItemFactory);
  }

  /**
   * If there is a super method and all methods are abstract, then we can simply remove all abstract
   * methods.
   */
  private boolean isNop() {
    return superMethod != null
        && Iterables.all(methods, method -> method.getDefinition().isAbstract());
  }

  /**
   * If the method is present on all classes in the merge group, and there is at most one
   * non-abstract method, then we can simply move that method (or the first abstract method) to the
   * target class.
   */
  private boolean isTrivial() {
    if (superMethod != null) {
      return false;
    }
    if (methods.size() == 1) {
      return true;
    }
    int numberOfNonAbstractMethods =
        Iterables.size(Iterables.filter(methods, method -> !method.getDefinition().isAbstract()));
    return numberOfNonAbstractMethods <= 1;
  }

  /**
   * If there is only a single method that does not override anything then it is safe to just move
   * it to the target type if it is not already in it.
   */
  private void mergeTrivial(
      ClassMethodsBuilder classMethodsBuilder, HorizontalClassMergerGraphLens.Builder lensBuilder) {
    DexMethod newMethodReference = getNewMethodReference();

    // Find the first non-abstract method. If all are abstract, then select the first method.
    ProgramMethod representative =
        Iterables.find(methods, method -> !method.getDefinition().isAbstract(), null);
    if (representative == null) {
      representative = ListUtils.first(methods);
    }

    for (ProgramMethod method : methods) {
      if (method.getReference() == representative.getReference()) {
        lensBuilder.moveMethod(method.getReference(), newMethodReference);
      } else {
        lensBuilder.mapMethod(method.getReference(), newMethodReference);
      }
    }

    DexEncodedMethod newMethod;
    if (representative.getHolder() == group.getTarget()) {
      newMethod = representative.getDefinition();
    } else {
      // If the method is not in the target type, move it.
      OptionalBool isLibraryMethodOverride =
          representative.getDefinition().isLibraryMethodOverride();
      newMethod =
          representative
              .getDefinition()
              .toTypeSubstitutedMethod(
                  newMethodReference,
                  builder -> builder.setIsLibraryMethodOverrideIfKnown(isLibraryMethodOverride));
    }

    newMethod.getAccessFlags().unsetFinal();

    classMethodsBuilder.addVirtualMethod(newMethod);
  }

  public void merge(
      ClassMethodsBuilder classMethodsBuilder,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      Reference2IntMap<DexType> classIdentifiers) {
    assert !methods.isEmpty();

    // Handle trivial merges.
    if (isNop() || isTrivial()) {
      mergeTrivial(classMethodsBuilder, lensBuilder);
      return;
    }


    Int2ReferenceSortedMap<DexMethod> classIdToMethodMap = new Int2ReferenceAVLTreeMap<>();

    CfVersion classFileVersion = null;
    ProgramMethod representative = null;
    for (ProgramMethod method : methods) {
      if (method.getDefinition().isAbstract()) {
        continue;
      }
      if (method.getDefinition().hasClassFileVersion()) {
        CfVersion methodVersion = method.getDefinition().getClassFileVersion();
        classFileVersion = Ordered.maxIgnoreNull(classFileVersion, methodVersion);
      }
      DexMethod newMethod = moveMethod(classMethodsBuilder, method);
      lensBuilder.recordNewMethodSignature(method.getReference(), newMethod);
      classIdToMethodMap.put(classIdentifiers.getInt(method.getHolderType()), newMethod);
      if (representative == null) {
        representative = method;
      }
    }

    assert representative != null;

    // Use the first of the original methods as the original method for the merged constructor.
    DexMethod originalMethodReference =
        appView.graphLens().getOriginalMethodSignature(representative.getReference());
    DexMethod bridgeMethodReference =
        dexItemFactory.createFreshMethodName(
            originalMethodReference.getName().toSourceString() + "$bridge",
            null,
            originalMethodReference.proto,
            originalMethodReference.getHolderType(),
            classMethodsBuilder::isFresh);

    DexMethod newMethodReference = getNewMethodReference();
    AbstractSynthesizedCode synthesizedCode =
        new VirtualMethodEntryPointSynthesizedCode(
            classIdToMethodMap,
            group.getClassIdField(),
            superMethod,
            newMethodReference,
            bridgeMethodReference);
    DexEncodedMethod newMethod =
        new DexEncodedMethod(
            newMethodReference,
            getAccessFlags(),
            MethodTypeSignature.noSignature(),
            DexAnnotationSet.empty(),
            ParameterAnnotationsList.empty(),
            synthesizedCode,
            true,
            classFileVersion);
    if (!representative.getDefinition().isLibraryMethodOverride().isUnknown()) {
      newMethod.setLibraryMethodOverride(representative.getDefinition().isLibraryMethodOverride());
    }

    // Map each old non-abstract method to the newly synthesized method in the graph lens.
    for (ProgramMethod oldMethod : methods) {
      lensBuilder.mapMethod(oldMethod.getReference(), newMethodReference);
    }

    // Add a mapping from a synthetic name to the synthetic merged method.
    lensBuilder.recordNewMethodSignature(bridgeMethodReference, newMethodReference);

    classMethodsBuilder.addVirtualMethod(newMethod);
  }
}
