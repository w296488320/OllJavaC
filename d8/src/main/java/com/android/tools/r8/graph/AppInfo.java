// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.graph.FieldResolutionResult.SuccessfulFieldResolutionResult;
import com.android.tools.r8.ir.desugar.InterfaceMethodRewriter;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.MainDexInfo;
import com.android.tools.r8.synthesis.CommittedItems;
import com.android.tools.r8.synthesis.SyntheticItems;
import com.android.tools.r8.utils.BooleanBox;
import com.android.tools.r8.utils.InternalOptions;
import java.util.List;
import java.util.function.Consumer;

public class AppInfo implements DexDefinitionSupplier {

  private final DexApplication app;
  private final DexItemFactory dexItemFactory;
  private final MainDexInfo mainDexInfo;
  private final SyntheticItems syntheticItems;

  // Set when a new AppInfo replaces a previous one. All public methods should verify that the
  // current instance is not obsolete, to ensure that we almost use the most recent AppInfo.
  private final BooleanBox obsolete;

  public static AppInfo createInitialAppInfo(DexApplication application) {
    return createInitialAppInfo(application, MainDexInfo.none());
  }

  public static AppInfo createInitialAppInfo(DexApplication application, MainDexInfo mainDexInfo) {
    return new AppInfo(SyntheticItems.createInitialSyntheticItems(application), mainDexInfo);
  }

  public AppInfo(CommittedItems committedItems, MainDexInfo mainDexInfo) {
    this(
        committedItems.getApplication(),
        committedItems.toSyntheticItems(),
        mainDexInfo,
        new BooleanBox());
  }

  // For desugaring.
  // This is a view onto the app info and is the only place the pending synthetics are shared.
  AppInfo(AppInfoWithClassHierarchy.CreateDesugaringViewOnAppInfo witness, AppInfo appInfo) {
    this(appInfo.app, appInfo.syntheticItems, appInfo.mainDexInfo, appInfo.obsolete);
    assert witness != null;
  }

  private AppInfo(
      DexApplication application,
      SyntheticItems syntheticItems,
      MainDexInfo mainDexInfo,
      BooleanBox obsolete) {
    this.app = application;
    this.dexItemFactory = application.dexItemFactory;
    this.mainDexInfo = mainDexInfo;
    this.syntheticItems = syntheticItems;
    this.obsolete = obsolete;
  }

  public AppInfo prunedCopyFrom(PrunedItems prunedItems) {
    assert getClass() == AppInfo.class;
    assert checkIfObsolete();
    assert prunedItems.getPrunedApp() == app();
    if (prunedItems.isEmpty()) {
      return this;
    }
    return new AppInfo(
        getSyntheticItems().commitPrunedItems(prunedItems),
        getMainDexInfo().withoutPrunedItems(prunedItems));
  }

  public AppInfo rebuildWithMainDexInfo(MainDexInfo mainDexInfo) {
    assert checkIfObsolete();
    return new AppInfo(app, syntheticItems, mainDexInfo, new BooleanBox());
  }

  protected InternalOptions options() {
    return app.options;
  }

  public boolean isObsolete() {
    return obsolete.get();
  }

  public void markObsolete() {
    obsolete.set();
  }

  public void unsetObsolete() {
    obsolete.unset();
  }

  public boolean checkIfObsolete() {
    assert !isObsolete();
    return true;
  }

  public DexApplication app() {
    assert checkIfObsolete();
    return app;
  }

  @Override
  public DexItemFactory dexItemFactory() {
    assert checkIfObsolete();
    return dexItemFactory;
  }

  public MainDexInfo getMainDexInfo() {
    assert checkIfObsolete();
    return mainDexInfo;
  }

  public SyntheticItems getSyntheticItems() {
    assert checkIfObsolete();
    return syntheticItems;
  }

  public void addSynthesizedClass(DexProgramClass clazz, boolean addToMainDex) {
    assert checkIfObsolete();
    syntheticItems.addLegacySyntheticClass(clazz);
    if (addToMainDex) {
      mainDexInfo.addSyntheticClass(clazz);
    }
  }

  public void addSynthesizedClass(DexProgramClass clazz, ProgramDefinition context) {
    assert checkIfObsolete();
    syntheticItems.addLegacySyntheticClass(clazz);
    if (context != null) {
      mainDexInfo.addLegacySyntheticClass(clazz, context);
    }
  }

  public List<DexProgramClass> classes() {
    assert checkIfObsolete();
    return app.classes();
  }

  public List<DexProgramClass> classesWithDeterministicOrder() {
    assert checkIfObsolete();
    return app.classesWithDeterministicOrder();
  }

  public void forEachMethod(Consumer<ProgramMethod> consumer) {
    for (DexProgramClass clazz : classes()) {
      clazz.forEachProgramMethod(consumer);
    }
  }

  @Override
  public DexClass definitionFor(DexType type) {
    return definitionForWithoutExistenceAssert(type);
  }

  public final DexClass definitionForWithoutExistenceAssert(DexType type) {
    assert checkIfObsolete();
    return syntheticItems.definitionFor(type, app::definitionFor);
  }

  public DexClass definitionForDesugarDependency(DexClass dependent, DexType type) {
    if (dependent.type == type) {
      return dependent;
    }
    DexClass definition = definitionFor(type);
    if (definition != null && !definition.isLibraryClass() && !dependent.isLibraryClass()) {
      InterfaceMethodRewriter.reportDependencyEdge(dependent, definition, options());
    }
    return definition;
  }

  public DexProgramClass unsafeDirectProgramTypeLookup(DexType type) {
    return app.programDefinitionFor(type);
  }

  public Origin originFor(DexType type) {
    assert checkIfObsolete();
    DexClass definition = app.definitionFor(type);
    return definition == null ? Origin.unknown() : definition.origin;
  }

  /**
   * Lookup static method on the method holder, or answers null.
   *
   * @param method the method to lookup
   * @param context the method the invoke is contained in, i.e., the caller.
   * @return The actual target for {@code method} if on the holder, or {@code null}.
   */
  public final DexEncodedMethod lookupStaticTargetOnItself(
      DexMethod method, ProgramMethod context) {
    if (method.holder != context.getHolderType()) {
      return null;
    }
    DexEncodedMethod singleTarget = context.getHolder().lookupDirectMethod(method);
    if (singleTarget != null && singleTarget.isStatic()) {
      return singleTarget;
    }
    return null;
  }

  /**
   * Lookup direct method on the method holder, or answers null.
   *
   * @param method the method to lookup
   * @param context the method the invoke is contained in, i.e., the caller.
   * @return The actual target for {@code method} if on the holder, or {@code null}.
   */
  public final DexEncodedMethod lookupDirectTargetOnItself(
      DexMethod method, ProgramMethod context) {
    if (method.holder != context.getHolderType()) {
      return null;
    }
    DexEncodedMethod singleTarget = context.getHolder().lookupDirectMethod(method);
    if (singleTarget != null && !singleTarget.isStatic()) {
      return singleTarget;
    }
    return null;
  }

  public boolean hasClassHierarchy() {
    assert checkIfObsolete();
    return false;
  }

  public AppInfoWithClassHierarchy withClassHierarchy() {
    assert checkIfObsolete();
    return null;
  }

  public boolean hasLiveness() {
    assert checkIfObsolete();
    return false;
  }

  public AppInfoWithLiveness withLiveness() {
    assert checkIfObsolete();
    return null;
  }

  public final FieldResolutionResult resolveField(DexField field, ProgramMethod context) {
    return resolveFieldOn(field.holder, field, context);
  }

  public FieldResolutionResult resolveFieldOn(DexType type, DexField field, ProgramMethod context) {
    // Only allow resolution if the field is declared in the context.
    if (type != context.getHolderType()) {
      return FieldResolutionResult.failure();
    }
    DexProgramClass clazz = context.getHolder();
    DexEncodedField definition = clazz.lookupField(field);
    return definition != null
        ? new SuccessfulFieldResolutionResult(clazz, clazz, definition)
        : FieldResolutionResult.unknown();
  }
}
