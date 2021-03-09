// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.contexts.CompilationContext;
import com.android.tools.r8.contexts.CompilationContext.ProcessorContext;
import com.android.tools.r8.errors.dontwarn.DontWarnConfiguration;
import com.android.tools.r8.features.ClassToFeatureSplitMap;
import com.android.tools.r8.graph.DexValue.DexValueString;
import com.android.tools.r8.graph.GraphLens.NonIdentityGraphLens;
import com.android.tools.r8.graph.analysis.InitializedClassesInInstanceMethodsAnalysis.InitializedClassesInInstanceMethods;
import com.android.tools.r8.graph.classmerging.MergedClassesCollection;
import com.android.tools.r8.graph.classmerging.VerticallyMergedClasses;
import com.android.tools.r8.horizontalclassmerging.HorizontallyMergedClasses;
import com.android.tools.r8.ir.analysis.inlining.SimpleInliningConstraintFactory;
import com.android.tools.r8.ir.analysis.proto.EnumLiteProtoShrinker;
import com.android.tools.r8.ir.analysis.proto.GeneratedExtensionRegistryShrinker;
import com.android.tools.r8.ir.analysis.proto.GeneratedMessageLiteBuilderShrinker;
import com.android.tools.r8.ir.analysis.proto.GeneratedMessageLiteShrinker;
import com.android.tools.r8.ir.analysis.proto.ProtoShrinker;
import com.android.tools.r8.ir.analysis.value.AbstractValueFactory;
import com.android.tools.r8.ir.desugar.PrefixRewritingMapper;
import com.android.tools.r8.ir.optimize.CallSiteOptimizationInfoPropagator;
import com.android.tools.r8.ir.optimize.enums.EnumDataMap;
import com.android.tools.r8.ir.optimize.info.field.InstanceFieldInitializationInfoFactory;
import com.android.tools.r8.ir.optimize.library.LibraryMemberOptimizer;
import com.android.tools.r8.ir.optimize.library.LibraryMethodSideEffectModelCollection;
import com.android.tools.r8.optimize.MemberRebindingLens;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.KeepInfoCollection;
import com.android.tools.r8.shaking.LibraryModeledPredicate;
import com.android.tools.r8.shaking.MainDexInfo;
import com.android.tools.r8.shaking.ProguardCompatibilityActions;
import com.android.tools.r8.shaking.RootSetUtils.MainDexRootSet;
import com.android.tools.r8.shaking.RootSetUtils.RootSet;
import com.android.tools.r8.synthesis.SyntheticItems;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.InternalOptions.TestingOptions;
import com.android.tools.r8.utils.OptionalBool;
import com.android.tools.r8.utils.Reporter;
import com.android.tools.r8.utils.ThrowingConsumer;
import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

public class AppView<T extends AppInfo> implements DexDefinitionSupplier, LibraryModeledPredicate {

  private enum WholeProgramOptimizations {
    ON,
    OFF
  }

  private T appInfo;
  private AppInfoWithClassHierarchy appInfoForDesugaring;
  private AppServices appServices;
  private final DontWarnConfiguration dontWarnConfiguration;
  private final WholeProgramOptimizations wholeProgramOptimizations;
  private GraphLens graphLens;
  private InitClassLens initClassLens;
  private ProguardCompatibilityActions proguardCompatibilityActions;
  private RootSet rootSet;
  private MainDexRootSet mainDexRootSet = null;
  // This should perferably always be obtained via AppInfoWithLiveness.
  // Currently however the liveness may be downgraded thus loosing the computed keep info.
  private KeepInfoCollection keepInfo = null;

  private final AbstractValueFactory abstractValueFactory = new AbstractValueFactory();
  private final InstanceFieldInitializationInfoFactory instanceFieldInitializationInfoFactory =
      new InstanceFieldInitializationInfoFactory();
  private final SimpleInliningConstraintFactory simpleInliningConstraintFactory =
      new SimpleInliningConstraintFactory();

  // Desugaring.
  public final PrefixRewritingMapper rewritePrefix;

  // Modeling.
  private final LibraryMethodSideEffectModelCollection libraryMethodSideEffectModelCollection;

  // Optimizations.
  private final CallSiteOptimizationInfoPropagator callSiteOptimizationInfoPropagator;
  private final LibraryMemberOptimizer libraryMemberOptimizer;
  private final ProtoShrinker protoShrinker;

  // Optimization results.
  private boolean allCodeProcessed = false;
  private Predicate<DexType> classesEscapingIntoLibrary = Predicates.alwaysTrue();
  private InitializedClassesInInstanceMethods initializedClassesInInstanceMethods;
  private HorizontallyMergedClasses horizontallyMergedClasses;
  private VerticallyMergedClasses verticallyMergedClasses;
  private EnumDataMap unboxedEnums = EnumDataMap.empty();
  // TODO(b/169115389): Remove
  private Set<DexMethod> cfByteCodePassThrough = ImmutableSet.of();
  private Map<DexType, DexValueString> sourceDebugExtensions = new IdentityHashMap<>();

  // When input has been (partially) desugared these are the classes which has been library
  // desugared. This information is populated in the IR converter.
  private Set<DexType> alreadyLibraryDesugared = null;

  private final CompilationContext context;

  private final Thread mainThread = Thread.currentThread();

  private AppView(
      T appInfo,
      WholeProgramOptimizations wholeProgramOptimizations,
      PrefixRewritingMapper mapper) {
    assert appInfo != null;
    this.context = CompilationContext.createInitialContext(appInfo.options());
    this.appInfo = appInfo;
    this.dontWarnConfiguration = DontWarnConfiguration.create(options().getProguardConfiguration());
    this.wholeProgramOptimizations = wholeProgramOptimizations;
    this.graphLens = GraphLens.getIdentityLens();
    this.initClassLens = InitClassLens.getDefault();
    this.rewritePrefix = mapper;

    if (enableWholeProgramOptimizations() && options().callSiteOptimizationOptions().isEnabled()) {
      this.callSiteOptimizationInfoPropagator =
          new CallSiteOptimizationInfoPropagator(withLiveness());
    } else {
      this.callSiteOptimizationInfoPropagator = null;
    }

    this.libraryMethodSideEffectModelCollection = new LibraryMethodSideEffectModelCollection(this);
    this.libraryMemberOptimizer = new LibraryMemberOptimizer(this);

    if (enableWholeProgramOptimizations() && options().protoShrinking().isProtoShrinkingEnabled()) {
      this.protoShrinker = new ProtoShrinker(withLiveness());
    } else {
      this.protoShrinker = null;
    }
  }

  public boolean verifyMainThread() {
    assert mainThread == Thread.currentThread();
    return true;
  }

  @Override
  public boolean isModeled(DexType type) {
    return libraryMemberOptimizer.isModeled(type);
  }

  private static <T extends AppInfo> PrefixRewritingMapper defaultPrefixRewritingMapper(T appInfo) {
    InternalOptions options = appInfo.options();
    return options.desugaredLibraryConfiguration.getPrefixRewritingMapper();
  }

  public static <T extends AppInfo> AppView<T> createForD8(T appInfo) {
    return new AppView<>(
        appInfo, WholeProgramOptimizations.OFF, defaultPrefixRewritingMapper(appInfo));
  }

  public static <T extends AppInfo> AppView<T> createForD8(
      T appInfo, PrefixRewritingMapper mapper) {
    return new AppView<>(appInfo, WholeProgramOptimizations.OFF, mapper);
  }

  public static AppView<AppInfoWithClassHierarchy> createForR8(DexApplication application) {
    return createForR8(application, MainDexInfo.none());
  }

  public static AppView<AppInfoWithClassHierarchy> createForR8(
      DexApplication application, MainDexInfo mainDexInfo) {
    ClassToFeatureSplitMap classToFeatureSplitMap =
        ClassToFeatureSplitMap.createInitialClassToFeatureSplitMap(application.options);
    AppInfoWithClassHierarchy appInfo =
        AppInfoWithClassHierarchy.createInitialAppInfoWithClassHierarchy(
            application, classToFeatureSplitMap, mainDexInfo);
    return new AppView<>(
        appInfo, WholeProgramOptimizations.ON, defaultPrefixRewritingMapper(appInfo));
  }

  public static <T extends AppInfo> AppView<T> createForL8(
      T appInfo, PrefixRewritingMapper mapper) {
    return new AppView<>(appInfo, WholeProgramOptimizations.OFF, mapper);
  }

  public static <T extends AppInfo> AppView<T> createForRelocator(T appInfo) {
    return new AppView<>(
        appInfo, WholeProgramOptimizations.OFF, defaultPrefixRewritingMapper(appInfo));
  }

  public AbstractValueFactory abstractValueFactory() {
    return abstractValueFactory;
  }

  public InstanceFieldInitializationInfoFactory instanceFieldInitializationInfoFactory() {
    return instanceFieldInitializationInfoFactory;
  }

  public SimpleInliningConstraintFactory simpleInliningConstraintFactory() {
    return simpleInliningConstraintFactory;
  }

  public T appInfo() {
    assert !appInfo.hasClassHierarchy() || enableWholeProgramOptimizations();
    return appInfo;
  }

  public AppInfoWithLiveness appInfoWithLiveness() {
    return appInfo.hasLiveness() ? appInfo.withLiveness() : null;
  }

  public AppInfoWithClassHierarchy appInfoForDesugaring() {
    if (enableWholeProgramOptimizations()) {
      assert appInfo.hasClassHierarchy();
      return appInfo.withClassHierarchy();
    }
    assert !appInfo.hasClassHierarchy();
    if (appInfoForDesugaring == null) {
      appInfoForDesugaring = AppInfoWithClassHierarchy.createForDesugaring(appInfo());
    }
    return appInfoForDesugaring;
  }

  private void unsetAppInfoForDesugaring() {
    appInfoForDesugaring = null;
  }

  public <U extends T> AppView<U> setAppInfo(U appInfo) {
    assert !appInfo.isObsolete();
    AppInfo previous = this.appInfo;
    this.appInfo = appInfo;
    unsetAppInfoForDesugaring();
    if (appInfo != previous) {
      previous.markObsolete();
    }
    if (appInfo.hasLiveness()) {
      keepInfo = appInfo.withLiveness().getKeepInfo();
    }
    @SuppressWarnings("unchecked")
    AppView<U> appViewWithSpecializedAppInfo = (AppView<U>) this;
    return appViewWithSpecializedAppInfo;
  }

  public boolean isAllCodeProcessed() {
    return allCodeProcessed;
  }

  public void setAllCodeProcessed() {
    allCodeProcessed = true;
  }

  public GraphLens clearCodeRewritings() {
    return graphLens = graphLens.withCodeRewritingsApplied(dexItemFactory());
  }

  public AppServices appServices() {
    return appServices;
  }

  public void setAppServices(AppServices appServices) {
    this.appServices = appServices;
  }

  public DontWarnConfiguration getDontWarnConfiguration() {
    return dontWarnConfiguration;
  }

  public boolean isClassEscapingIntoLibrary(DexType type) {
    assert type.isClassType();
    return classesEscapingIntoLibrary.test(type);
  }

  public void setClassesEscapingIntoLibrary(Predicate<DexType> classesEscapingIntoLibrary) {
    this.classesEscapingIntoLibrary = classesEscapingIntoLibrary;
  }

  public void setSourceDebugExtensionForType(DexClass clazz, DexValueString sourceDebugExtension) {
    sourceDebugExtensions.put(clazz.type, sourceDebugExtension);
  }

  public DexValueString getSourceDebugExtensionForType(DexClass clazz) {
    return sourceDebugExtensions.get(clazz.type);
  }

  @Override
  public final DexClass definitionFor(DexType type) {
    return appInfo().definitionFor(type);
  }

  public OptionalBool isInterface(DexType type) {
    assert type.isClassType();
    // Without whole program information we should not assume anything about any other class than
    // the current holder in a given context.
    if (enableWholeProgramOptimizations()) {
      DexClass clazz = definitionFor(type);
      if (clazz == null) {
        return OptionalBool.unknown();
      }
      return OptionalBool.of(clazz.isInterface());
    }
    return OptionalBool.unknown();
  }

  @Override
  public DexItemFactory dexItemFactory() {
    return appInfo.dexItemFactory();
  }

  public boolean enableWholeProgramOptimizations() {
    return wholeProgramOptimizations == WholeProgramOptimizations.ON;
  }

  /**
   * Create a new processor context.
   *
   * <p>The order of processor contexts for a compilation must be deterministic so this is required
   * to be called on the main thread only.
   */
  public ProcessorContext createProcessorContext() {
    assert verifyMainThread();
    return context.createProcessorContext();
  }

  public SyntheticItems getSyntheticItems() {
    return appInfo.getSyntheticItems();
  }

  public CallSiteOptimizationInfoPropagator callSiteOptimizationInfoPropagator() {
    return callSiteOptimizationInfoPropagator;
  }

  public LibraryMemberOptimizer libraryMethodOptimizer() {
    return libraryMemberOptimizer;
  }

  public LibraryMethodSideEffectModelCollection getLibraryMethodSideEffectModelCollection() {
    return libraryMethodSideEffectModelCollection;
  }

  public ProtoShrinker protoShrinker() {
    return protoShrinker;
  }

  public <E extends Throwable> void withProtoShrinker(ThrowingConsumer<ProtoShrinker, E> consumer)
      throws E {
    if (protoShrinker != null) {
      consumer.accept(protoShrinker);
    }
  }

  public <U> U withProtoShrinker(Function<ProtoShrinker, U> consumer, U defaultValue) {
    if (protoShrinker != null) {
      return consumer.apply(protoShrinker);
    }
    return defaultValue;
  }

  public <U> U withProtoEnumShrinker(Function<EnumLiteProtoShrinker, U> fn, U defaultValue) {
    if (protoShrinker != null && options().protoShrinking().isEnumLiteProtoShrinkingEnabled()) {
      return fn.apply(protoShrinker.enumLiteProtoShrinker);
    }
    return defaultValue;
  }

  public <E extends Throwable> void withGeneratedExtensionRegistryShrinker(
      ThrowingConsumer<GeneratedExtensionRegistryShrinker, E> consumer) throws E {
    if (protoShrinker != null && protoShrinker.generatedExtensionRegistryShrinker != null) {
      consumer.accept(protoShrinker.generatedExtensionRegistryShrinker);
    }
  }

  public <U> U withGeneratedExtensionRegistryShrinker(
      Function<GeneratedExtensionRegistryShrinker, U> fn, U defaultValue) {
    if (protoShrinker != null && protoShrinker.generatedExtensionRegistryShrinker != null) {
      return fn.apply(protoShrinker.generatedExtensionRegistryShrinker);
    }
    return defaultValue;
  }

  public <E extends Throwable> void withGeneratedMessageLiteShrinker(
      ThrowingConsumer<GeneratedMessageLiteShrinker, E> consumer) throws E {
    if (protoShrinker != null && protoShrinker.generatedMessageLiteShrinker != null) {
      consumer.accept(protoShrinker.generatedMessageLiteShrinker);
    }
  }

  public <E extends Throwable> void withGeneratedMessageLiteBuilderShrinker(
      ThrowingConsumer<GeneratedMessageLiteBuilderShrinker, E> consumer) throws E {
    if (protoShrinker != null && protoShrinker.generatedMessageLiteBuilderShrinker != null) {
      consumer.accept(protoShrinker.generatedMessageLiteBuilderShrinker);
    }
  }

  public <U> U withGeneratedMessageLiteShrinker(
      Function<GeneratedMessageLiteShrinker, U> fn, U defaultValue) {
    if (protoShrinker != null && protoShrinker.generatedMessageLiteShrinker != null) {
      return fn.apply(protoShrinker.generatedMessageLiteShrinker);
    }
    return defaultValue;
  }

  public <U> U withGeneratedMessageLiteBuilderShrinker(
      Function<GeneratedMessageLiteBuilderShrinker, U> fn, U defaultValue) {
    if (protoShrinker != null && protoShrinker.generatedMessageLiteBuilderShrinker != null) {
      return fn.apply(protoShrinker.generatedMessageLiteBuilderShrinker);
    }
    return defaultValue;
  }

  public GraphLens graphLens() {
    return graphLens;
  }

  /** @return true if the graph lens changed, otherwise false. */
  public boolean setGraphLens(GraphLens graphLens) {
    if (graphLens != this.graphLens) {
      this.graphLens = graphLens;
      return true;
    }
    return false;
  }

  public boolean canUseInitClass() {
    return options().isShrinking() && !initClassLens.isFinal();
  }

  public InitClassLens initClassLens() {
    return initClassLens;
  }

  public boolean hasInitClassLens() {
    return initClassLens != null;
  }

  public void setInitClassLens(InitClassLens initClassLens) {
    this.initClassLens = initClassLens;
  }

  public void setInitializedClassesInInstanceMethods(
      InitializedClassesInInstanceMethods initializedClassesInInstanceMethods) {
    this.initializedClassesInInstanceMethods = initializedClassesInInstanceMethods;
  }

  public void setCfByteCodePassThrough(Set<DexMethod> cfByteCodePassThrough) {
    this.cfByteCodePassThrough = cfByteCodePassThrough;
  }

  public <U> U withInitializedClassesInInstanceMethods(
      Function<InitializedClassesInInstanceMethods, U> fn, U defaultValue) {
    if (initializedClassesInInstanceMethods != null) {
      return fn.apply(initializedClassesInInstanceMethods);
    }
    return defaultValue;
  }

  public InternalOptions options() {
    return appInfo.options();
  }

  public Reporter reporter() {
    return options().reporter;
  }

  public TestingOptions testing() {
    return options().testing;
  }

  public RootSet rootSet() {
    return rootSet;
  }

  public void setRootSet(RootSet rootSet) {
    assert this.rootSet == null : "Root set should never be recomputed";
    this.rootSet = rootSet;
  }

  public void setMainDexRootSet(MainDexRootSet mainDexRootSet) {
    assert mainDexRootSet != null : "Root set should never be recomputed";
    this.mainDexRootSet = mainDexRootSet;
  }

  public MainDexRootSet getMainDexRootSet() {
    return mainDexRootSet;
  }

  public KeepInfoCollection getKeepInfo() {
    return keepInfo;
  }

  public boolean hasProguardCompatibilityActions() {
    return proguardCompatibilityActions != null;
  }

  public ProguardCompatibilityActions getProguardCompatibilityActions() {
    return proguardCompatibilityActions;
  }

  public void setProguardCompatibilityActions(
      ProguardCompatibilityActions proguardCompatibilityActions) {
    assert options().forceProguardCompatibility;
    this.proguardCompatibilityActions = proguardCompatibilityActions;
  }

  public MergedClassesCollection allMergedClasses() {
    MergedClassesCollection collection = new MergedClassesCollection();
    if (horizontallyMergedClasses != null) {
      collection.add(horizontallyMergedClasses);
    }
    if (verticallyMergedClasses != null) {
      collection.add(verticallyMergedClasses);
    }
    return collection;
  }

  /**
   * Get the result of horizontal class merging. Returns null if horizontal class merging has not
   * been run.
   */
  public HorizontallyMergedClasses horizontallyMergedClasses() {
    return horizontallyMergedClasses;
  }

  public void setHorizontallyMergedClasses(HorizontallyMergedClasses horizontallyMergedClasses) {
    assert this.horizontallyMergedClasses == null;
    this.horizontallyMergedClasses = horizontallyMergedClasses;
    testing().horizontallyMergedClassesConsumer.accept(dexItemFactory(), horizontallyMergedClasses);
  }

  /**
   * Get the result of vertical class merging. Returns null if vertical class merging has not been
   * run.
   */
  public VerticallyMergedClasses verticallyMergedClasses() {
    return verticallyMergedClasses;
  }

  public void setVerticallyMergedClasses(VerticallyMergedClasses verticallyMergedClasses) {
    assert this.verticallyMergedClasses == null;
    this.verticallyMergedClasses = verticallyMergedClasses;
    testing().verticallyMergedClassesConsumer.accept(dexItemFactory(), verticallyMergedClasses);
  }

  public EnumDataMap unboxedEnums() {
    return unboxedEnums;
  }

  public void setUnboxedEnums(EnumDataMap unboxedEnums) {
    assert this.unboxedEnums.isEmpty();
    this.unboxedEnums = unboxedEnums;
    testing().unboxedEnumsConsumer.accept(dexItemFactory(), unboxedEnums);
  }

  public boolean validateUnboxedEnumsHaveBeenPruned() {
    for (DexType unboxedEnum : unboxedEnums.getUnboxedEnums()) {
      assert appInfo.definitionForWithoutExistenceAssert(unboxedEnum) == null
          : "Enum " + unboxedEnum + " has been unboxed but is still in the program.";
      assert appInfo().withLiveness().wasPruned(unboxedEnum)
          : "Enum " + unboxedEnum + " has been unboxed but was not pruned.";
    }
    return true;
  }

  @SuppressWarnings("unchecked")
  public AppView<AppInfoWithClassHierarchy> withClassHierarchy() {
    return appInfo.hasClassHierarchy()
        ? (AppView<AppInfoWithClassHierarchy>) this
        : null;
  }

  public boolean hasLiveness() {
    return appInfo().hasLiveness();
  }

  public AppView<AppInfoWithLiveness> withLiveness() {
    @SuppressWarnings("unchecked")
    AppView<AppInfoWithLiveness> appViewWithLiveness = (AppView<AppInfoWithLiveness>) this;
    return appViewWithLiveness;
  }

  public OptionalBool isSubtype(DexType subtype, DexType supertype) {
    // Even if we can compute isSubtype by having class hierarchy we may not be allowed to ask the
    // question for all code paths in D8. Having the check for liveness ensure that we are in R8
    // territory.
    return appInfo().hasLiveness()
        ? OptionalBool.of(appInfo().withLiveness().isSubtype(subtype, supertype))
        : OptionalBool.unknown();
  }

  public boolean isCfByteCodePassThrough(DexEncodedMethod method) {
    if (!options().isGeneratingClassFiles()) {
      return false;
    }
    if (cfByteCodePassThrough.contains(method.method)) {
      return true;
    }
    return options().testing.cfByteCodePassThrough != null
        && options().testing.cfByteCodePassThrough.test(method.method);
  }

  public boolean hasCfByteCodePassThroughMethods() {
    return !cfByteCodePassThrough.isEmpty();
  }

  public void pruneItems(PrunedItems prunedItems) {
    if (prunedItems.isEmpty()) {
      assert appInfo().app() == prunedItems.getPrunedApp();
      return;
    }
    if (appInfo.hasLiveness()) {
      AppView<AppInfoWithLiveness> self = withLiveness();
      self.setAppInfo(self.appInfo().prunedCopyFrom(prunedItems));
    } else if (appInfo.hasClassHierarchy()) {
      AppView<AppInfoWithClassHierarchy> self = withClassHierarchy();
      self.setAppInfo(self.appInfo().prunedCopyFrom(prunedItems));
    } else {
      pruneAppInfo(prunedItems, this);
    }
    if (appServices() != null) {
      setAppServices(appServices().prunedCopy(prunedItems));
    }
    if (hasProguardCompatibilityActions()) {
      setProguardCompatibilityActions(
          getProguardCompatibilityActions().withoutPrunedItems(prunedItems));
    }
    if (mainDexRootSet != null) {
      setMainDexRootSet(mainDexRootSet.withoutPrunedItems(prunedItems));
    }
  }

  @SuppressWarnings("unchecked")
  private static void pruneAppInfo(PrunedItems prunedItems, AppView<?> appView) {
    ((AppView<AppInfo>) appView).setAppInfo(appView.appInfo().prunedCopyFrom(prunedItems));
  }

  public void rewriteWithLens(NonIdentityGraphLens lens) {
    if (lens != null) {
      rewriteWithLens(lens, appInfo().app().asDirect(), withLiveness(), lens.getPrevious());
    }
  }

  public void rewriteWithLensAndApplication(
      NonIdentityGraphLens lens, DirectMappedDexApplication application) {
    rewriteWithLensAndApplication(lens, application, lens.getPrevious());
  }

  public void rewriteWithLensAndApplication(
      NonIdentityGraphLens lens, DirectMappedDexApplication application, GraphLens appliedLens) {
    assert lens != null;
    assert application != null;
    rewriteWithLens(lens, application, withLiveness(), appliedLens);
  }

  private static void rewriteWithLens(
      NonIdentityGraphLens lens,
      DirectMappedDexApplication application,
      AppView<AppInfoWithLiveness> appView,
      GraphLens appliedLens) {
    if (lens == null) {
      return;
    }

    boolean changed = appView.setGraphLens(lens);
    assert changed;
    assert application.verifyWithLens(appView.appInfo().app().asDirect(), lens);

    // The application has already been rewritten with the given applied lens. Therefore, we
    // temporarily replace that lens with a lens that does not have any rewritings to avoid the
    // overhead of traversing the entire lens chain upon each lookup during the rewriting.
    NonIdentityGraphLens firstUnappliedLens = lens;
    while (firstUnappliedLens.getPrevious() != appliedLens) {
      GraphLens previousLens = firstUnappliedLens.getPrevious();
      assert previousLens.isNonIdentityLens();
      firstUnappliedLens = previousLens.asNonIdentityLens();
    }

    // Insert a member rebinding lens above the first unapplied lens.
    MemberRebindingLens appliedMemberRebindingLens =
        firstUnappliedLens.findPrevious(GraphLens::isMemberRebindingLens);
    GraphLens newMemberRebindingLens =
        appliedMemberRebindingLens != null
            ? appliedMemberRebindingLens.toRewrittenFieldRebindingLens(
                appView.dexItemFactory(), appliedLens)
            : GraphLens.getIdentityLens();

    firstUnappliedLens.withAlternativeParentLens(
        newMemberRebindingLens,
        () -> {
          appView.setAppInfo(appView.appInfo().rewrittenWithLens(application, lens));
          appView.setAppServices(appView.appServices().rewrittenWithLens(lens));
          if (appView.hasInitClassLens()) {
            appView.setInitClassLens(appView.initClassLens().rewrittenWithLens(lens));
          }
          if (appView.hasProguardCompatibilityActions()) {
            appView.setProguardCompatibilityActions(
                appView.getProguardCompatibilityActions().rewrittenWithLens(lens));
          }
          if (appView.getMainDexRootSet() != null) {
            appView.setMainDexRootSet(appView.getMainDexRootSet().rewrittenWithLens(lens));
          }
        });
  }

  public void setAlreadyLibraryDesugared(Set<DexType> alreadyLibraryDesugared) {
    assert this.alreadyLibraryDesugared == null;
    this.alreadyLibraryDesugared = alreadyLibraryDesugared;
  }

  public boolean isAlreadyLibraryDesugared(DexProgramClass clazz) {
    if (!options().desugarSpecificOptions().allowAllDesugaredInput) {
      return false;
    }
    assert alreadyLibraryDesugared != null;
    return alreadyLibraryDesugared.contains(clazz.getType());
  }
}
