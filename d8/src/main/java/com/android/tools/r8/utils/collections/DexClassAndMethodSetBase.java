// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils.collections;

import com.android.tools.r8.graph.DexClassAndMethod;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.google.common.collect.Sets;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Stream;

public abstract class DexClassAndMethodSetBase<T extends DexClassAndMethod> implements Iterable<T> {

  protected final Map<DexMethod, T> backing;
  protected final Supplier<? extends Map<DexMethod, T>> backingFactory;

  protected DexClassAndMethodSetBase(Supplier<? extends Map<DexMethod, T>> backingFactory) {
    this(backingFactory, backingFactory.get());
  }

  protected DexClassAndMethodSetBase(
      Supplier<? extends Map<DexMethod, T>> backingFactory, Map<DexMethod, T> backing) {
    this.backing = backing;
    this.backingFactory = backingFactory;
  }

  public boolean add(T method) {
    T existing = backing.put(method.getReference(), method);
    assert existing == null || existing.isStructurallyEqualTo(method);
    return existing == null;
  }

  public void addAll(Iterable<T> methods) {
    methods.forEach(this::add);
  }

  public boolean contains(DexEncodedMethod method) {
    return backing.containsKey(method.getReference());
  }

  public boolean contains(T method) {
    return backing.containsKey(method.getReference());
  }

  public void clear() {
    backing.clear();
  }

  public boolean isEmpty() {
    return backing.isEmpty();
  }

  @Override
  public Iterator<T> iterator() {
    return backing.values().iterator();
  }

  public boolean remove(DexMethod method) {
    T existing = backing.remove(method);
    return existing != null;
  }

  public boolean remove(DexEncodedMethod method) {
    return remove(method.getReference());
  }

  public int size() {
    return backing.size();
  }

  public Stream<T> stream() {
    return backing.values().stream();
  }

  public Set<DexEncodedMethod> toDefinitionSet() {
    assert backing instanceof IdentityHashMap;
    Set<DexEncodedMethod> definitions = Sets.newIdentityHashSet();
    forEach(method -> definitions.add(method.getDefinition()));
    return definitions;
  }
}
