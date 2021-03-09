// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import static com.android.tools.r8.shaking.MainDexInfo.MainDexGroup.MAIN_DEX_ROOT;
import static com.android.tools.r8.utils.LensUtils.rewriteAndApplyIfNotPrimitiveType;
import static com.android.tools.r8.utils.PredicateUtils.not;

import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexReference;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.ProgramDefinition;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.PrunedItems;
import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.SetUtils;
import com.google.common.collect.Sets;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public class MainDexInfo {

  private static final MainDexInfo NONE =
      new MainDexInfo(
          Collections.emptySet(),
          Collections.emptySet(),
          Collections.emptySet(),
          Collections.emptySet(),
          Collections.emptySet(),
          false);

  public enum MainDexGroup {
    MAIN_DEX_LIST,
    MAIN_DEX_ROOT,
    MAIN_DEX_DEPENDENCY,
    NOT_IN_MAIN_DEX
  }

  // Specific set of classes specified to be in main-dex
  private final Set<DexType> classList;
  private final Map<DexType, DexType> synthesizedClassesMap;
  // Traced roots are traced main dex references.
  private final Set<DexType> tracedRoots;
  // Traced method roots are the methods visited from an initial main dex root set. The set is
  // cleared after the mergers has run.
  private Set<DexMethod> tracedMethodRoots;
  // Traced dependencies are those classes that are directly referenced from traced roots, but will
  // not be loaded before loading the remaining dex files.
  private final Set<DexType> tracedDependencies;
  // Bit indicating if the traced methods are cleared.
  private boolean tracedMethodRootsCleared = false;

  private MainDexInfo(Set<DexType> classList) {
    this(
        classList,
        Collections.emptySet(),
        Collections.emptySet(),
        Collections.emptySet(),
        /* synthesized classes - cannot be emptyset */ Sets.newIdentityHashSet(),
        false);
  }

  private MainDexInfo(
      Set<DexType> classList,
      Set<DexType> tracedRoots,
      Set<DexMethod> tracedMethodRoots,
      Set<DexType> tracedDependencies,
      Set<DexType> synthesizedClasses,
      boolean tracedMethodRootsCleared) {
    this.classList = classList;
    this.tracedRoots = tracedRoots;
    this.tracedMethodRoots = tracedMethodRoots;
    this.tracedDependencies = tracedDependencies;
    this.synthesizedClassesMap = new ConcurrentHashMap<>();
    this.tracedMethodRootsCleared = tracedMethodRootsCleared;
    synthesizedClasses.forEach(type -> synthesizedClassesMap.put(type, type));
    assert tracedDependencies.stream().noneMatch(tracedRoots::contains);
    assert tracedRoots.containsAll(synthesizedClasses);
  }

  public boolean isNone() {
    assert none() == NONE;
    return this == NONE;
  }

  public boolean isMainDex(ProgramDefinition definition) {
    return isFromList(definition) || isTracedRoot(definition) || isDependency(definition);
  }

  public boolean isFromList(ProgramDefinition definition) {
    return isFromList(definition.getContextType());
  }

  private boolean isFromList(DexReference reference) {
    return classList.contains(reference.getContextType());
  }

  public boolean isTracedRoot(ProgramDefinition definition) {
    return isTracedRoot(definition.getContextType());
  }

  public boolean isTracedMethodRoot(DexMethod method) {
    assert !tracedMethodRootsCleared : "Traced method roots are cleared after mergers has run";
    return tracedMethodRoots.contains(method);
  }

  private boolean isTracedRoot(DexReference reference) {
    return tracedRoots.contains(reference.getContextType());
  }

  private boolean isDependency(ProgramDefinition definition) {
    return isDependency(definition.getContextType());
  }

  private boolean isDependency(DexReference reference) {
    return tracedDependencies.contains(reference.getContextType());
  }

  public boolean isTracedMethodRootsCleared() {
    return tracedMethodRootsCleared;
  }

  public void clearTracedMethodRoots() {
    this.tracedMethodRootsCleared = true;
    this.tracedMethodRoots = Sets.newIdentityHashSet();
  }

  public boolean canRebindReference(ProgramMethod context, DexReference referenceToTarget) {
    MainDexGroup holderGroup = getMainDexGroupInternal(context);
    if (holderGroup == MainDexGroup.NOT_IN_MAIN_DEX
        || holderGroup == MainDexGroup.MAIN_DEX_DEPENDENCY) {
      // We are always free to rebind/inline into something not in main-dex or traced dependencies.
      return true;
    }
    if (holderGroup == MainDexGroup.MAIN_DEX_LIST) {
      // If the holder is in the class list, we are not allowed to make any assumptions on the
      // holder being a root or a dependency. Therefore we cannot merge.
      return false;
    }
    assert holderGroup == MAIN_DEX_ROOT;
    // Otherwise we allow if either is both root.
    return getMainDexGroupInternal(referenceToTarget) == MAIN_DEX_ROOT;
  }

  public boolean canMerge(ProgramDefinition candidate) {
    return !isFromList(candidate);
  }

  public boolean canMerge(ProgramDefinition source, ProgramDefinition target) {
    return canMerge(source.getContextType(), target.getContextType());
  }

  private boolean canMerge(DexReference source, DexReference target) {
    MainDexGroup sourceGroup = getMainDexGroupInternal(source);
    MainDexGroup targetGroup = getMainDexGroupInternal(target);
    if (sourceGroup != targetGroup) {
      return false;
    }
    // If the holder is in the class list, we are not allowed to make any assumptions on the holder
    // being a root or a dependency. Therefore we cannot merge.
    return sourceGroup != MainDexGroup.MAIN_DEX_LIST;
  }

  public MainDexGroup getMergeKey(ProgramDefinition mergeCandidate) {
    MainDexGroup mainDexGroupInternal = getMainDexGroupInternal(mergeCandidate);
    return mainDexGroupInternal == MainDexGroup.MAIN_DEX_LIST ? null : mainDexGroupInternal;
  }

  private MainDexGroup getMainDexGroupInternal(ProgramDefinition definition) {
    return getMainDexGroupInternal(definition.getReference());
  }

  private MainDexGroup getMainDexGroupInternal(DexReference reference) {
    if (isFromList(reference)) {
      return MainDexGroup.MAIN_DEX_LIST;
    }
    if (isTracedRoot(reference)) {
      return MAIN_DEX_ROOT;
    }
    if (isDependency(reference)) {
      return MainDexGroup.MAIN_DEX_DEPENDENCY;
    }
    return MainDexGroup.NOT_IN_MAIN_DEX;
  }

  public boolean disallowInliningIntoContext(
      AppInfoWithClassHierarchy appInfo, ProgramDefinition context, ProgramMethod method) {
    if (context.getContextType() == method.getContextType()) {
      return false;
    }
    MainDexGroup mainDexGroupInternal = getMainDexGroupInternal(context);
    if (mainDexGroupInternal == MainDexGroup.NOT_IN_MAIN_DEX
        || mainDexGroupInternal == MainDexGroup.MAIN_DEX_DEPENDENCY) {
      return false;
    }
    if (mainDexGroupInternal == MainDexGroup.MAIN_DEX_LIST) {
      return MainDexDirectReferenceTracer.hasReferencesOutsideMainDexClasses(
          appInfo, method, not(this::isFromList));
    }
    assert mainDexGroupInternal == MAIN_DEX_ROOT;
    return MainDexDirectReferenceTracer.hasReferencesOutsideMainDexClasses(
        appInfo, method, not(this::isTracedRoot));
  }

  public boolean isEmpty() {
    assert !tracedRoots.isEmpty() || tracedDependencies.isEmpty();
    return tracedRoots.isEmpty() && classList.isEmpty();
  }

  public static MainDexInfo none() {
    return NONE;
  }

  // TODO(b/178127572): This mutates the MainDexClasses which otherwise should be immutable.
  public void addSyntheticClass(DexProgramClass clazz) {
    // TODO(b/178127572): This will add a synthesized type as long as the initial set is not empty.
    //  A better approach would be to use the context for the synthetic with a containment check.
    assert !isNone();
    if (!classList.isEmpty()) {
      synthesizedClassesMap.computeIfAbsent(
          clazz.type,
          type -> {
            classList.add(type);
            return type;
          });
    }
    if (!tracedRoots.isEmpty()) {
      synthesizedClassesMap.computeIfAbsent(
          clazz.type,
          type -> {
            tracedRoots.add(type);
            return type;
          });
    }
  }

  public void addLegacySyntheticClass(DexProgramClass clazz, ProgramDefinition context) {
    if (isTracedRoot(context) || isFromList(context) || isDependency(context)) {
      addSyntheticClass(clazz);
    }
  }

  public int size() {
    return classList.size() + tracedRoots.size() + tracedDependencies.size();
  }

  public void forEachExcludingDependencies(Consumer<DexType> fn) {
    // Prevent seeing duplicates in the list and roots.
    Set<DexType> seen = Sets.newIdentityHashSet();
    classList.forEach(ConsumerUtils.acceptIfNotSeen(fn, seen));
    tracedRoots.forEach(ConsumerUtils.acceptIfNotSeen(fn, seen));
  }

  public void forEach(Consumer<DexType> fn) {
    // Prevent seeing duplicates in the list and roots.
    Set<DexType> seen = Sets.newIdentityHashSet();
    classList.forEach(ConsumerUtils.acceptIfNotSeen(fn, seen));
    tracedRoots.forEach(ConsumerUtils.acceptIfNotSeen(fn, seen));
    tracedDependencies.forEach(ConsumerUtils.acceptIfNotSeen(fn, seen));
  }

  public MainDexInfo withoutPrunedItems(PrunedItems prunedItems) {
    if (prunedItems.isEmpty()) {
      return this;
    }
    Set<DexType> removedClasses = prunedItems.getRemovedClasses();
    Set<DexType> modifiedClassList = Sets.newIdentityHashSet();
    Set<DexType> modifiedSynthesized = Sets.newIdentityHashSet();
    classList.forEach(type -> ifNotRemoved(type, removedClasses, modifiedClassList::add));
    synthesizedClassesMap
        .keySet()
        .forEach(type -> ifNotRemoved(type, removedClasses, modifiedSynthesized::add));
    MainDexInfo.Builder builder = builder();
    tracedRoots.forEach(type -> ifNotRemoved(type, removedClasses, builder::addRoot));
    // TODO(b/169927809): Methods could be pruned without the holder being pruned, however, one has
    //  to have a reference for querying a root.
    tracedMethodRoots.forEach(
        method ->
            ifNotRemoved(
                method.getHolderType(), removedClasses, ignored -> builder.addRoot(method)));
    tracedDependencies.forEach(type -> ifNotRemoved(type, removedClasses, builder::addDependency));
    return builder.build(modifiedClassList, modifiedSynthesized);
  }

  private void ifNotRemoved(
      DexType type, Set<DexType> removedClasses, Consumer<DexType> notRemoved) {
    if (!removedClasses.contains(type)) {
      notRemoved.accept(type);
    }
  }

  public MainDexInfo rewrittenWithLens(GraphLens lens) {
    Set<DexType> modifiedClassList = Sets.newIdentityHashSet();
    Set<DexType> modifiedSynthesized = Sets.newIdentityHashSet();
    classList.forEach(
        type -> rewriteAndApplyIfNotPrimitiveType(lens, type, modifiedClassList::add));
    synthesizedClassesMap.keySet().forEach(type -> modifiedSynthesized.add(lens.lookupType(type)));
    MainDexInfo.Builder builder = builder();
    tracedRoots.forEach(type -> rewriteAndApplyIfNotPrimitiveType(lens, type, builder::addRoot));
    tracedMethodRoots.forEach(method -> builder.addRoot(lens.getRenamedMethodSignature(method)));
    tracedDependencies.forEach(
        type -> {
          if (lens.isSyntheticFinalizationGraphLens()) {
            // Synthetic finalization is allowed to merge identical classes into the same class. The
            // rewritten type of a traced dependency can therefore be finalized with a traced root.
            rewriteAndApplyIfNotPrimitiveType(lens, type, builder::addDependencyIfNotRoot);
          } else {
            rewriteAndApplyIfNotPrimitiveType(lens, type, builder::addDependency);
          }
        });
    return builder.build(modifiedClassList, modifiedSynthesized);
  }

  public Builder builder() {
    return new Builder(tracedMethodRootsCleared);
  }

  public static class Builder {

    private final Set<DexType> list = Sets.newIdentityHashSet();
    private final Set<DexType> roots = Sets.newIdentityHashSet();
    private final Set<DexMethod> methodRoots = Sets.newIdentityHashSet();
    private final Set<DexType> dependencies = Sets.newIdentityHashSet();
    private final boolean tracedMethodRootsCleared;

    private Builder(boolean tracedMethodRootsCleared) {
      this.tracedMethodRootsCleared = tracedMethodRootsCleared;
    }

    public void addList(DexProgramClass clazz) {
      addList(clazz.getType());
    }

    public void addList(DexType type) {
      list.add(type);
    }

    public void addRoot(DexProgramClass clazz) {
      addRoot(clazz.getType());
    }

    public void addRoot(DexType type) {
      assert !dependencies.contains(type);
      roots.add(type);
    }

    public void addRoot(DexMethod method) {
      methodRoots.add(method);
    }

    public void addDependency(DexProgramClass clazz) {
      addDependency(clazz.getType());
    }

    public void addDependency(DexType type) {
      assert !roots.contains(type);
      dependencies.add(type);
    }

    public void addDependencyIfNotRoot(DexType type) {
      if (roots.contains(type)) {
        return;
      }
      addDependency(type);
    }

    public boolean isTracedRoot(DexProgramClass clazz) {
      return isTracedRoot(clazz.getType());
    }

    public boolean isTracedRoot(DexType type) {
      return roots.contains(type);
    }

    public boolean isDependency(DexProgramClass clazz) {
      return isDependency(clazz.getType());
    }

    public boolean isDependency(DexType type) {
      return dependencies.contains(type);
    }

    public boolean contains(DexProgramClass clazz) {
      return contains(clazz.type);
    }

    public boolean contains(DexType type) {
      return isTracedRoot(type) || isDependency(type);
    }

    public Set<DexType> getRoots() {
      return roots;
    }

    public MainDexInfo buildList() {
      // When building without passing the list, the method roots and dependencies should
      // be empty since no tracing has been done.
      assert dependencies.isEmpty();
      assert roots.isEmpty();
      return new MainDexInfo(list);
    }

    public MainDexInfo build(Set<DexType> classList, Set<DexType> synthesizedClasses) {
      // Class can contain dependencies which we should not regard as roots.
      assert list.isEmpty();
      return new MainDexInfo(
          classList,
          SetUtils.unionIdentityHashSet(roots, synthesizedClasses),
          methodRoots,
          Sets.difference(dependencies, synthesizedClasses),
          synthesizedClasses,
          tracedMethodRootsCleared);
    }

    public MainDexInfo build(MainDexInfo previous) {
      return build(previous.classList, previous.synthesizedClassesMap.keySet());
    }
  }
}
