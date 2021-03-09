// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph.classmerging;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.collections.BidirectionalManyToOneMap;
import com.android.tools.r8.utils.collections.EmptyBidirectionalOneToOneMap;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

public class VerticallyMergedClasses implements MergedClasses {

  private final BidirectionalManyToOneMap<DexType, DexType> mergedClasses;

  public VerticallyMergedClasses(BidirectionalManyToOneMap<DexType, DexType> mergedClasses) {
    this.mergedClasses = mergedClasses;
  }

  public static VerticallyMergedClasses empty() {
    return new VerticallyMergedClasses(new EmptyBidirectionalOneToOneMap<>());
  }

  @Override
  public void forEachMergeGroup(BiConsumer<Set<DexType>, DexType> consumer) {
    mergedClasses.forEachManyToOneMapping(consumer);
  }

  public Map<DexType, DexType> getForwardMap() {
    return mergedClasses.getForwardMap();
  }

  public Collection<DexType> getSourcesFor(DexType type) {
    return mergedClasses.getKeys(type);
  }

  public DexType getTargetFor(DexType type) {
    assert mergedClasses.containsKey(type);
    return mergedClasses.get(type);
  }

  public DexType getTargetForOrDefault(DexType type, DexType defaultValue) {
    return mergedClasses.getOrDefault(type, defaultValue);
  }

  public boolean hasBeenMergedIntoSubtype(DexType type) {
    return mergedClasses.containsKey(type);
  }

  public boolean isEmpty() {
    return mergedClasses.isEmpty();
  }

  @Override
  public boolean isMergeTarget(DexType type) {
    return !getSourcesFor(type).isEmpty();
  }

  @Override
  public boolean hasBeenMergedIntoDifferentType(DexType type) {
    return hasBeenMergedIntoSubtype(type);
  }

  @Override
  public boolean verifyAllSourcesPruned(AppView<AppInfoWithLiveness> appView) {
    for (DexType source : mergedClasses.keySet()) {
      assert appView.appInfo().wasPruned(source)
          : "Expected vertically merged class `" + source.toSourceString() + "` to be absent";
    }
    return true;
  }
}
