/*
 *  // Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
 *  // for details. All rights reserved. Use of this source code is governed by a
 *  // BSD-style license that can be found in the LICENSE file.
 */

package com.android.tools.r8.horizontalclassmerging;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.utils.IteratorUtils;
import com.google.common.collect.Iterables;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class MergeGroup implements Iterable<DexProgramClass> {

  public static class Metadata {}

  private final LinkedList<DexProgramClass> classes;

  private DexField classIdField;
  private DexProgramClass target = null;
  private Metadata metadata = null;

  public MergeGroup() {
    this.classes = new LinkedList<>();
  }

  public MergeGroup(Collection<DexProgramClass> classes) {
    this();
    addAll(classes);
  }

  public void applyMetadataFrom(MergeGroup group) {
    if (metadata == null) {
      metadata = group.metadata;
    }
  }

  public void add(DexProgramClass clazz) {
    classes.add(clazz);
  }

  public void add(MergeGroup group) {
    classes.addAll(group.getClasses());
  }

  public void addAll(Collection<DexProgramClass> classes) {
    this.classes.addAll(classes);
  }

  public void addFirst(DexProgramClass clazz) {
    classes.addFirst(clazz);
  }

  public void forEachSource(Consumer<DexProgramClass> consumer) {
    assert hasTarget();
    for (DexProgramClass clazz : classes) {
      if (clazz != target) {
        consumer.accept(clazz);
      }
    }
  }

  public LinkedList<DexProgramClass> getClasses() {
    return classes;
  }

  public boolean hasClassIdField() {
    return classIdField != null;
  }

  public DexField getClassIdField() {
    assert hasClassIdField();
    return classIdField;
  }

  public void setClassIdField(DexField classIdField) {
    this.classIdField = classIdField;
  }

  public Iterable<DexProgramClass> getSources() {
    assert hasTarget();
    return Iterables.filter(classes, clazz -> clazz != target);
  }

  public boolean hasTarget() {
    return target != null;
  }

  public DexProgramClass getTarget() {
    return target;
  }

  public void setTarget(DexProgramClass target) {
    assert classes.contains(target);
    this.target = target;
  }

  public boolean isTrivial() {
    return size() < 2;
  }

  public boolean isEmpty() {
    return classes.isEmpty();
  }

  @Override
  public Iterator<DexProgramClass> iterator() {
    return classes.iterator();
  }

  public int size() {
    return classes.size();
  }

  public DexProgramClass removeFirst(Predicate<DexProgramClass> predicate) {
    return IteratorUtils.removeFirst(iterator(), predicate);
  }

  public boolean removeIf(Predicate<DexProgramClass> predicate) {
    return classes.removeIf(predicate);
  }

  public DexProgramClass removeLast() {
    return classes.removeLast();
  }
}
