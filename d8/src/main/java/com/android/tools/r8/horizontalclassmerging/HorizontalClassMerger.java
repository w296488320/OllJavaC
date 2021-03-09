// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.horizontalclassmerging;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DirectMappedDexApplication;
import com.android.tools.r8.horizontalclassmerging.policies.AllInstantiatedOrUninstantiated;
import com.android.tools.r8.horizontalclassmerging.policies.CheckAbstractClasses;
import com.android.tools.r8.horizontalclassmerging.policies.DontInlinePolicy;
import com.android.tools.r8.horizontalclassmerging.policies.DontMergeSynchronizedClasses;
import com.android.tools.r8.horizontalclassmerging.policies.IgnoreSynthetics;
import com.android.tools.r8.horizontalclassmerging.policies.LimitGroups;
import com.android.tools.r8.horizontalclassmerging.policies.MinimizeFieldCasts;
import com.android.tools.r8.horizontalclassmerging.policies.NoAnnotations;
import com.android.tools.r8.horizontalclassmerging.policies.NoClassInitializerWithObservableSideEffects;
import com.android.tools.r8.horizontalclassmerging.policies.NoClassesOrMembersWithAnnotations;
import com.android.tools.r8.horizontalclassmerging.policies.NoDirectRuntimeTypeChecks;
import com.android.tools.r8.horizontalclassmerging.policies.NoEnums;
import com.android.tools.r8.horizontalclassmerging.policies.NoIndirectRuntimeTypeChecks;
import com.android.tools.r8.horizontalclassmerging.policies.NoInnerClasses;
import com.android.tools.r8.horizontalclassmerging.policies.NoInterfaces;
import com.android.tools.r8.horizontalclassmerging.policies.NoKeepRules;
import com.android.tools.r8.horizontalclassmerging.policies.NoKotlinMetadata;
import com.android.tools.r8.horizontalclassmerging.policies.NoNativeMethods;
import com.android.tools.r8.horizontalclassmerging.policies.NoServiceLoaders;
import com.android.tools.r8.horizontalclassmerging.policies.NotMatchedByNoHorizontalClassMerging;
import com.android.tools.r8.horizontalclassmerging.policies.NotVerticallyMergedIntoSubtype;
import com.android.tools.r8.horizontalclassmerging.policies.PreserveMethodCharacteristics;
import com.android.tools.r8.horizontalclassmerging.policies.PreventMergeIntoDifferentMainDexGroups;
import com.android.tools.r8.horizontalclassmerging.policies.PreventMergeIntoMainDexList;
import com.android.tools.r8.horizontalclassmerging.policies.PreventMethodImplementation;
import com.android.tools.r8.horizontalclassmerging.policies.RespectPackageBoundaries;
import com.android.tools.r8.horizontalclassmerging.policies.SameFeatureSplit;
import com.android.tools.r8.horizontalclassmerging.policies.SameInstanceFields;
import com.android.tools.r8.horizontalclassmerging.policies.SameNestHost;
import com.android.tools.r8.horizontalclassmerging.policies.SameParentClass;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.FieldAccessInfoCollectionModifier;
import com.android.tools.r8.shaking.KeepInfoCollection;
import com.android.tools.r8.shaking.RuntimeTypeCheckInfo;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

public class HorizontalClassMerger {

  private final AppView<AppInfoWithLiveness> appView;

  public HorizontalClassMerger(AppView<AppInfoWithLiveness> appView) {
    this.appView = appView;
    assert appView.options().enableInlining;
  }

  // TODO(b/165577835): replace Collection<DexProgramClass> with MergeGroup
  public HorizontalClassMergerResult run(
      DirectMappedDexApplication.Builder appBuilder,
      RuntimeTypeCheckInfo runtimeTypeCheckInfo) {
    MergeGroup initialGroup = new MergeGroup(appView.appInfo().classesWithDeterministicOrder());

    // Run the policies on all program classes to produce a final grouping.
    List<Policy> policies = getPolicies(runtimeTypeCheckInfo);
    Collection<MergeGroup> groups =
        new SimplePolicyExecutor().run(Collections.singletonList(initialGroup), policies);

    // If there are no groups, then end horizontal class merging.
    if (groups.isEmpty()) {
      appView.setHorizontallyMergedClasses(HorizontallyMergedClasses.empty());
      return null;
    }

    HorizontallyMergedClasses.Builder mergedClassesBuilder =
        new HorizontallyMergedClasses.Builder();
    HorizontalClassMergerGraphLens.Builder lensBuilder =
        new HorizontalClassMergerGraphLens.Builder();

    // Set up a class merger for each group.
    List<ClassMerger> classMergers =
        initializeClassMergers(mergedClassesBuilder, lensBuilder, groups);
    Iterable<DexProgramClass> allMergeClasses =
        Iterables.concat(
            Iterables.transform(classMergers, classMerger -> classMerger.getGroup().getClasses()));

    // Merge the classes.
    SyntheticArgumentClass syntheticArgumentClass =
        new SyntheticArgumentClass.Builder(appBuilder, appView).build(allMergeClasses);
    applyClassMergers(classMergers, syntheticArgumentClass);

    // Generate the graph lens.
    HorizontallyMergedClasses mergedClasses = mergedClassesBuilder.build();
    appView.setHorizontallyMergedClasses(mergedClasses);
    HorizontalClassMergerGraphLens lens =
        createLens(mergedClasses, lensBuilder, syntheticArgumentClass);

    // Prune keep info.
    KeepInfoCollection keepInfo = appView.appInfo().getKeepInfo();
    keepInfo.mutate(mutator -> mutator.removeKeepInfoForPrunedItems(mergedClasses.getSources()));

    return new HorizontalClassMergerResult(createFieldAccessInfoCollectionModifier(groups), lens);
  }

  private FieldAccessInfoCollectionModifier createFieldAccessInfoCollectionModifier(
      Collection<MergeGroup> groups) {
    FieldAccessInfoCollectionModifier.Builder builder =
        new FieldAccessInfoCollectionModifier.Builder();
    for (MergeGroup group : groups) {
      DexProgramClass target = group.getTarget();
      target.forEachProgramInstanceInitializerMatching(
          definition -> definition.getCode().isHorizontalClassMergingCode(),
          method -> builder.recordFieldWrittenInContext(group.getClassIdField(), method));
      target.forEachProgramVirtualMethodMatching(
          definition -> definition.hasCode() && definition.getCode().isHorizontalClassMergingCode(),
          method -> builder.recordFieldReadInContext(group.getClassIdField(), method));
    }
    return builder.build();
  }

  private List<Policy> getPolicies(RuntimeTypeCheckInfo runtimeTypeCheckInfo) {
    return ImmutableList.of(
        new NotMatchedByNoHorizontalClassMerging(appView),
        new SameInstanceFields(appView),
        new NoInterfaces(),
        new NoAnnotations(),
        new NoEnums(appView),
        new CheckAbstractClasses(appView),
        new IgnoreSynthetics(appView),
        new NoClassesOrMembersWithAnnotations(appView),
        new NoInnerClasses(),
        new NoClassInitializerWithObservableSideEffects(),
        new NoNativeMethods(),
        new NoKeepRules(appView),
        new NoKotlinMetadata(),
        new NoServiceLoaders(appView),
        new NotVerticallyMergedIntoSubtype(appView),
        new NoDirectRuntimeTypeChecks(runtimeTypeCheckInfo),
        new NoIndirectRuntimeTypeChecks(appView, runtimeTypeCheckInfo),
        new PreventMethodImplementation(appView),
        new DontInlinePolicy(appView),
        new PreventMergeIntoMainDexList(appView),
        new PreventMergeIntoDifferentMainDexGroups(appView),
        new AllInstantiatedOrUninstantiated(appView),
        new SameParentClass(),
        new SameNestHost(),
        new PreserveMethodCharacteristics(appView),
        new SameFeatureSplit(appView),
        new RespectPackageBoundaries(appView),
        new DontMergeSynchronizedClasses(appView),
        new MinimizeFieldCasts(),
        new LimitGroups(appView));
  }

  /**
   * Prepare horizontal class merging by determining which virtual methods and constructors need to
   * be merged and how the merging should be performed.
   */
  private List<ClassMerger> initializeClassMergers(
      HorizontallyMergedClasses.Builder mergedClassesBuilder,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      Collection<MergeGroup> groups) {
    List<ClassMerger> classMergers = new ArrayList<>();

    // TODO(b/166577694): Replace Collection<DexProgramClass> with MergeGroup
    for (MergeGroup group : groups) {
      assert !group.isEmpty();
      ClassMerger merger =
          new ClassMerger.Builder(appView, group).build(mergedClassesBuilder, lensBuilder);
      classMergers.add(merger);
    }

    return classMergers;
  }

  /** Merges all class groups using {@link ClassMerger}. */
  private void applyClassMergers(
      Collection<ClassMerger> classMergers, SyntheticArgumentClass syntheticArgumentClass) {
    for (ClassMerger merger : classMergers) {
      merger.mergeGroup(syntheticArgumentClass);
    }
  }

  /**
   * Fix all references to merged classes using the {@link TreeFixer}. Construct a graph lens
   * containing all changes performed by horizontal class merging.
   */
  private HorizontalClassMergerGraphLens createLens(
      HorizontallyMergedClasses mergedClasses,
      HorizontalClassMergerGraphLens.Builder lensBuilder,
      SyntheticArgumentClass syntheticArgumentClass) {
    return new TreeFixer(appView, mergedClasses, lensBuilder, syntheticArgumentClass)
        .fixupTypeReferences();
  }
}
