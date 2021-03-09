// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.enums;

import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.optimize.enums.EnumInstanceFieldData.EnumInstanceFieldKnownData;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import java.util.Set;

public class EnumDataMap {
  private final ImmutableMap<DexType, EnumData> map;

  public static EnumDataMap empty() {
    return new EnumDataMap(ImmutableMap.of());
  }

  public EnumDataMap(ImmutableMap<DexType, EnumData> map) {
    this.map = map;
  }

  public boolean isUnboxedEnum(DexType type) {
    return map.containsKey(type);
  }

  public boolean isEmpty() {
    return map.isEmpty();
  }

  public Set<DexType> getUnboxedEnums() {
    return map.keySet();
  }

  public EnumInstanceFieldKnownData getInstanceFieldData(
      DexType enumType, DexField enumInstanceField) {
    assert map.containsKey(enumType);
    return map.get(enumType).getInstanceFieldData(enumInstanceField);
  }

  public boolean hasUnboxedValueFor(DexField enumStaticField) {
    return isUnboxedEnum(enumStaticField.holder)
        && map.get(enumStaticField.holder).hasUnboxedValueFor(enumStaticField);
  }

  public int getUnboxedValue(DexField enumStaticField) {
    assert map.containsKey(enumStaticField.holder);
    return map.get(enumStaticField.holder).getUnboxedValue(enumStaticField);
  }

  public int getValuesSize(DexType enumType) {
    assert map.containsKey(enumType);
    return map.get(enumType).getValuesSize();
  }

  public boolean matchesValuesField(DexField staticField) {
    assert map.containsKey(staticField.holder);
    return map.get(staticField.holder).matchesValuesField(staticField);
  }

  public static class EnumData {
    static final int INVALID_VALUES_SIZE = -1;

    // Map each enum instance field to the list of field known data.
    final ImmutableMap<DexField, EnumInstanceFieldKnownData> instanceFieldMap;
    // Map each enum instance (static field) to the unboxed integer value.
    final ImmutableMap<DexField, Integer> unboxedValues;
    // Fields matching the $VALUES content and type, usually one.
    final ImmutableSet<DexField> valuesFields;
    // Size of the $VALUES field, if the valuesFields set is empty, set to INVALID_VALUES_SIZE.
    final int valuesSize;

    public EnumData(
        ImmutableMap<DexField, EnumInstanceFieldKnownData> instanceFieldMap,
        ImmutableMap<DexField, Integer> unboxedValues,
        ImmutableSet<DexField> valuesFields,
        int valuesSize) {
      this.instanceFieldMap = instanceFieldMap;
      this.unboxedValues = unboxedValues;
      this.valuesFields = valuesFields;
      this.valuesSize = valuesSize;
    }

    public EnumInstanceFieldKnownData getInstanceFieldData(DexField enumInstanceField) {
      assert instanceFieldMap.containsKey(enumInstanceField);
      return instanceFieldMap.get(enumInstanceField);
    }

    public int getUnboxedValue(DexField field) {
      assert unboxedValues.containsKey(field);
      return unboxedValues.get(field);
    }

    public boolean hasUnboxedValueFor(DexField field) {
      return unboxedValues.get(field) != null;
    }

    public boolean matchesValuesField(DexField field) {
      return valuesFields.contains(field);
    }

    public int getValuesSize() {
      assert valuesSize != INVALID_VALUES_SIZE;
      return valuesSize;
    }
  }
}
