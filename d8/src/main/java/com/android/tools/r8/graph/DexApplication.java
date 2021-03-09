// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import com.android.tools.r8.DataResourceProvider;
import com.android.tools.r8.dex.ApplicationReader.ProgramClassConflictResolver;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.synthesis.SyntheticDefinitionsProvider;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ProgramClassCollection;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.ImmutableList;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public abstract class DexApplication {

  public final ImmutableList<DataResourceProvider> dataResourceProviders;

  private final ClassNameMapper proguardMap;

  public final Timing timing;

  public final InternalOptions options;
  public final DexItemFactory dexItemFactory;

  // Information on the lexicographically largest string referenced from code.
  public final DexString highestSortingString;

  /** Constructor should only be invoked by the DexApplication.Builder. */
  DexApplication(
      ClassNameMapper proguardMap,
      ImmutableList<DataResourceProvider> dataResourceProviders,
      InternalOptions options,
      DexString highestSortingString,
      Timing timing) {
    this.proguardMap = proguardMap;
    this.dataResourceProviders = dataResourceProviders;
    this.options = options;
    this.dexItemFactory = options.itemFactory;
    this.highestSortingString = highestSortingString;
    this.timing = timing;
  }

  public abstract Builder<?> builder();

  public DexDefinitionSupplier getDefinitionsSupplier(
      SyntheticDefinitionsProvider syntheticDefinitionsProvider) {
    DexApplication self = this;
    return new DexDefinitionSupplier() {
      @Override
      public DexClass definitionFor(DexType type) {
        return syntheticDefinitionsProvider.definitionFor(type, self::definitionFor);
      }

      @Override
      public DexItemFactory dexItemFactory() {
        return self.dexItemFactory;
      }
    };
  }

  // Reorder classes randomly. Note that the order of classes in program or library
  // class collections should not matter for compilation of valid code and when running
  // with assertions enabled we reorder the classes randomly to catch possible issues.
  // Also note that the order may add to non-determinism in reporting errors for invalid
  // code, but this non-determinism exists even with the same order of classes since we
  // may process classes concurrently and fail-fast on the first error.
  private static class ReorderBox<T> {

    private List<T> classes;

    ReorderBox(List<T> classes) {
      this.classes = classes;
    }

    boolean reorderClasses() {
      if (!InternalOptions.DETERMINISTIC_DEBUGGING) {
        List<T> shuffled = new ArrayList<>(classes);
        Collections.shuffle(shuffled);
        classes = ImmutableList.copyOf(shuffled);
      }
      return true;
    }

    List<T> getClasses() {
      return classes;
    }
  }

  abstract List<DexProgramClass> programClasses();

  public List<DexProgramClass> classes() {
    ReorderBox<DexProgramClass> box = new ReorderBox<>(programClasses());
    assert box.reorderClasses();
    return box.getClasses();
  }

  public List<DexProgramClass> classesWithDeterministicOrder() {
    return classesWithDeterministicOrder(new ArrayList<>(programClasses()));
  }

  public static <T extends DexClass> List<T> classesWithDeterministicOrder(Collection<T> classes) {
    return classesWithDeterministicOrder(new ArrayList<>(classes));
  }

  public static <T extends DexClass> List<T> classesWithDeterministicOrder(List<T> classes) {
    // To keep the order deterministic, we sort the classes by their type, which is a unique key.
    classes.sort(Comparator.comparing(DexClass::getType));
    return classes;
  }

  public abstract DexClass definitionFor(DexType type);

  public abstract DexProgramClass programDefinitionFor(DexType type);

  @Override
  public abstract String toString();

  public ClassNameMapper getProguardMap() {
    return proguardMap;
  }

  public abstract static class Builder<T extends Builder<T>> {

    private final List<DexProgramClass> programClasses = new ArrayList<>();

    final List<DataResourceProvider> dataResourceProviders = new ArrayList<>();

    public final InternalOptions options;
    public final DexItemFactory dexItemFactory;
    ClassNameMapper proguardMap;
    final Timing timing;

    DexString highestSortingString;
    private final Collection<DexProgramClass> synthesizedClasses;

    public Builder(InternalOptions options, Timing timing) {
      this.options = options;
      this.dexItemFactory = options.itemFactory;
      this.timing = timing;
      this.synthesizedClasses = new ArrayList<>();
    }

    abstract T self();

    public Builder(DexApplication application) {
      programClasses.addAll(application.programClasses());
      dataResourceProviders.addAll(application.dataResourceProviders);
      proguardMap = application.getProguardMap();
      timing = application.timing;
      highestSortingString = application.highestSortingString;
      options = application.options;
      dexItemFactory = application.dexItemFactory;
      synthesizedClasses = new ArrayList<>();
    }

    public boolean isDirect() {
      return false;
    }

    public DirectMappedDexApplication.Builder asDirect() {
      return null;
    }

    public synchronized T setProguardMap(ClassNameMapper proguardMap) {
      assert this.proguardMap == null;
      this.proguardMap = proguardMap;
      return self();
    }

    public synchronized T replaceProgramClasses(Collection<DexProgramClass> newProgramClasses) {
      assert newProgramClasses != null;
      this.programClasses.clear();
      this.programClasses.addAll(newProgramClasses);
      return self();
    }

    public synchronized T addDataResourceProvider(DataResourceProvider provider) {
      dataResourceProviders.add(provider);
      return self();
    }

    public synchronized T setHighestSortingString(DexString value) {
      highestSortingString = value;
      return self();
    }

    public synchronized T addProgramClass(DexProgramClass clazz) {
      programClasses.add(clazz);
      return self();
    }

    public synchronized T addProgramClasses(Collection<DexProgramClass> classes) {
      programClasses.addAll(classes);
      return self();
    }

    public synchronized T addSynthesizedClass(DexProgramClass synthesizedClass) {
      assert synthesizedClass.isProgramClass() : "All synthesized classes must be program classes";
      addProgramClass(synthesizedClass);
      synthesizedClasses.add(synthesizedClass);
      return self();
    }

    public List<DexProgramClass> getProgramClasses() {
      return programClasses;
    }

    public Collection<DexProgramClass> getSynthesizedClasses() {
      return synthesizedClasses;
    }

    public abstract DexApplication build();
  }

  public static LazyLoadedDexApplication.Builder builder(InternalOptions options, Timing timing) {
    return builder(
        options, timing, ProgramClassCollection.defaultConflictResolver(options.reporter));
  }

  public static LazyLoadedDexApplication.Builder builder(
      InternalOptions options, Timing timing, ProgramClassConflictResolver resolver) {
    return new LazyLoadedDexApplication.Builder(resolver, options, timing);
  }

  public DirectMappedDexApplication asDirect() {
    throw new Unreachable("Cannot use a LazyDexApplication where a DirectDexApplication is"
        + " expected.");
  }

  public abstract DirectMappedDexApplication toDirect();

  public abstract boolean isDirect();
}
