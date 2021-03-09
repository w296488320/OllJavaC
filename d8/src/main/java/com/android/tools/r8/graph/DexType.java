// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;

import com.android.tools.r8.dex.IndexedItemCollection;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.structural.CompareToVisitor;
import com.android.tools.r8.utils.structural.HashingVisitor;
import com.android.tools.r8.utils.structural.StructuralMapping;
import com.google.common.collect.ImmutableList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

public class DexType extends DexReference implements NamingLensComparable<DexType> {
  public static final DexType[] EMPTY_ARRAY = {};

  // Bundletool is merging classes that may originate from a build with an old version of R8.
  // Allow merging of classes that use names from older versions of R8.
  private static List<String> OLD_SYNTHESIZED_NAMES =
      ImmutableList.of(
          "$r8$backportedMethods$utility",
          "$r8$java8methods$utility",
          "$r8$twr$utility",
          "$-DC",
          "$$ServiceLoaderMethods",
          "com.android.tools.r8.GeneratedOutlineSupport",
          "-$$Nest$Constructor",
          "-$$Lambda$",
          "-$$LambdaGroup$");

  public final DexString descriptor;
  private String toStringCache = null;

  DexType(DexString descriptor) {
    assert !descriptor.toString().contains(".") : "Malformed descriptor: " + descriptor.toString();
    this.descriptor = descriptor;
  }

  public ClassReference asClassReference() {
    return Reference.classFromDescriptor(toDescriptorString());
  }

  @Override
  public int compareTo(DexReference other) {
    if (other.isDexType()) {
      return compareTo(other.asDexType());
    }
    int comparisonResult = compareTo(other.getContextType());
    return comparisonResult != 0 ? comparisonResult : -1;
  }

  @Override
  public DexType self() {
    return this;
  }

  @Override
  public StructuralMapping<DexType> getStructuralMapping() {
    // Structural accept is never accessed as all accept methods are defined directly.
    throw new Unreachable();
  }

  // DexType overrides accept to ensure the visitors always gets a visitDexType callback.
  @Override
  public int acceptCompareTo(DexType other, CompareToVisitor visitor) {
    return visitor.visitDexType(this, other);
  }

  // DexType overrides accept to ensure the visitors always gets a visitDexType callback.
  @Override
  public void acceptHashing(HashingVisitor visitor) {
    visitor.visitDexType(this);
  }

  @Override
  public DexType getContextType() {
    return this;
  }

  public DexString getDescriptor() {
    return descriptor;
  }

  @Override
  public int computeHashCode() {
    return descriptor.hashCode();
  }

  @Override
  public boolean computeEquals(Object other) {
    if (other instanceof DexType) {
      return descriptor.equals(((DexType) other).descriptor);
    }
    return false;
  }

  public boolean classInitializationMayHaveSideEffectsInContext(
      AppView<?> appView, ProgramDefinition context) {
    DexClass clazz = appView.definitionFor(this);
    return clazz == null || clazz.classInitializationMayHaveSideEffectsInContext(appView, context);
  }

  final boolean internalClassOrInterfaceMayHaveInitializationSideEffects(
      AppView<?> appView,
      DexClass initialAccessHolder,
      Predicate<DexType> ignore,
      Set<DexType> seen) {
    DexClass clazz = appView.definitionFor(this);
    return clazz == null
        || clazz.internalClassOrInterfaceMayHaveInitializationSideEffects(
            appView, initialAccessHolder, ignore, seen);
  }

  public boolean isAlwaysNull(AppView<AppInfoWithLiveness> appView) {
    return isAlwaysNull(appView.appInfo());
  }

  public boolean isAlwaysNull(AppInfoWithLiveness appInfo) {
    if (!isClassType()) {
      return false;
    }
    DexProgramClass clazz = asProgramClassOrNull(appInfo.definitionFor(this));
    if (clazz == null) {
      return false;
    }
    if (appInfo.options().enableUninstantiatedTypeOptimizationForInterfaces) {
      return !appInfo.isInstantiatedDirectlyOrIndirectly(clazz);
    }
    return !clazz.isInterface() && !appInfo.isInstantiatedDirectlyOrIndirectly(clazz);
  }

  public boolean isSamePackage(DexType other) {
    return getPackageDescriptor().equals(other.getPackageDescriptor());
  }

  public String toDescriptorString() {
    return descriptor.toString();
  }

  public String toBinaryName() {
    String descriptor = toDescriptorString();
    assert descriptor.length() > 1
        && descriptor.charAt(0) == 'L'
        && descriptor.charAt(descriptor.length() - 1) == ';';
    return descriptor.substring(1, descriptor.length() - 1);
  }

  @Override
  public <T> T apply(
      Function<DexType, T> classConsumer,
      Function<DexField, T> fieldConsumer,
      Function<DexMethod, T> methodConsumer) {
    return classConsumer.apply(this);
  }

  @Override
  public void accept(
      Consumer<DexType> classConsumer,
      Consumer<DexField> fieldConsumer,
      Consumer<DexMethod> methodConsumer) {
    classConsumer.accept(this);
  }

  @Override
  public <T> void accept(
      BiConsumer<DexType, T> classConsumer,
      BiConsumer<DexField, T> fieldConsumer,
      BiConsumer<DexMethod, T> methodConsumer,
      T arg) {
    classConsumer.accept(this, arg);
  }

  public String getTypeName() {
    return toSourceString();
  }

  @Override
  public String toSourceString() {
    if (toStringCache == null) {
      // TODO(ager): Pass in a ProguardMapReader to map names back to original names.
      if (DexItemFactory.isInternalSentinel(this)) {
        toStringCache = descriptor.toString();
      } else {
        toStringCache = DescriptorUtils.descriptorToJavaType(toDescriptorString());
      }
    }
    return toStringCache;
  }

  public char toShorty() {
    char c = (char) descriptor.content[0];
    return c == '[' ? 'L' : c;
  }

  @Override
  public String toSmaliString() {
    return toDescriptorString();
  }

  @Override
  public String toString() {
    return toSourceString();
  }

  @Override
  public void collectIndexedItems(IndexedItemCollection collection) {
    if (collection.addType(this)) {
      collection.getRenamedDescriptor(this).collectIndexedItems(collection);
    }
  }

  @Override
  public void flushCachedValues() {
    super.flushCachedValues();
    toStringCache = null;
  }

  @Override
  public int getOffset(ObjectToOffsetMapping mapping) {
    return mapping.getOffsetFor(this);
  }

  @Override
  public boolean isDexType() {
    return true;
  }

  @Override
  public DexType asDexType() {
    return this;
  }

  public boolean isPrimitiveType() {
    return DescriptorUtils.isPrimitiveType((char) descriptor.content[0]);
  }

  public boolean isVoidType() {
    return (char) descriptor.content[0] == 'V';
  }

  public boolean isBooleanType() {
    return descriptor.content[0] == 'Z';
  }

  public boolean isByteType() {
    return descriptor.content[0] == 'B';
  }

  public boolean isCharType() {
    return descriptor.content[0] == 'C';
  }

  public boolean isShortType() {
    return descriptor.content[0] == 'S';
  }

  public boolean isIntType() {
    return descriptor.content[0] == 'I';
  }

  public boolean isFloatType() {
    return descriptor.content[0] == 'F';
  }

  public boolean isLongType() {
    return descriptor.content[0] == 'J';
  }

  public boolean isDoubleType() {
    return descriptor.content[0] == 'D';
  }

  public boolean isNullValueType() {
    boolean isNullValueType = descriptor.content[0] == 'N';
    assert !isNullValueType || this == DexItemFactory.nullValueType;
    return isNullValueType;
  }

  public boolean isArrayType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == '[';
  }

  public boolean isClassType() {
    char firstChar = (char) descriptor.content[0];
    return firstChar == 'L';
  }

  public boolean isReferenceType() {
    boolean isReferenceType = isArrayType() || isClassType();
    assert isReferenceType != isPrimitiveType() || isVoidType();
    return isReferenceType;
  }

  public boolean isPrimitiveArrayType() {
    if (!isArrayType()) {
      return false;
    }
    return DescriptorUtils.isPrimitiveType((char) descriptor.content[1]);
  }

  public boolean isWideType() {
    return isDoubleType() || isLongType();
  }

  public boolean isLegacySynthesizedTypeAllowedDuplication() {
    return oldSynthesizedName(toSourceString());
  }

  private boolean oldSynthesizedName(String name) {
    for (String synthesizedPrefix : OLD_SYNTHESIZED_NAMES) {
      if (name.contains(synthesizedPrefix)) {
        return true;
      }
    }
    return false;
  }

  public boolean isInterface(DexDefinitionSupplier definitionSupplier) {
    return definitionSupplier.definitionFor(this).isInterface();
  }

  public boolean isProgramType(DexDefinitionSupplier definitions) {
    DexClass clazz = definitions.definitionFor(this);
    return clazz != null && clazz.isProgramClass();
  }

  public boolean isResolvable(AppView<?> appView) {
    DexClass clazz = appView.definitionFor(this);
    return clazz != null && clazz.isResolvable(appView);
  }

  public int elementSizeForPrimitiveArrayType() {
    assert isPrimitiveArrayType();
    switch (descriptor.content[1]) {
      case 'Z': // boolean
      case 'B': // byte
        return 1;
      case 'S': // short
      case 'C': // char
        return 2;
      case 'I': // int
      case 'F': // float
        return 4;
      case 'J': // long
      case 'D': // double
        return 8;
      default:
        throw new Unreachable("Not array of primitives '" + descriptor + "'");
    }
  }

  public int getNumberOfLeadingSquareBrackets() {
    int leadingSquareBrackets = 0;
    while (descriptor.content[leadingSquareBrackets] == '[') {
      leadingSquareBrackets++;
    }
    return leadingSquareBrackets;
  }

  public DexType toBaseType(DexItemFactory dexItemFactory) {
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    if (leadingSquareBrackets == 0) {
      return this;
    }
    DexString newDesc =
        dexItemFactory.createString(
            descriptor.size - leadingSquareBrackets,
            Arrays.copyOfRange(
                descriptor.content, leadingSquareBrackets, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  // Similar to the method above, but performs a lookup only, allowing to use
  // this method also after strings are sorted in the ApplicationWriter.
  public DexType lookupBaseType(DexItemFactory dexItemFactory) {
    int leadingSquareBrackets = getNumberOfLeadingSquareBrackets();
    if (leadingSquareBrackets == 0) {
      return this;
    }
    DexString newDesc =
        dexItemFactory.lookupString(
            descriptor.size - leadingSquareBrackets,
            Arrays.copyOfRange(
                descriptor.content, leadingSquareBrackets, descriptor.content.length));
    return dexItemFactory.lookupType(newDesc);
  }

  public DexType replaceBaseType(DexType newBase, DexItemFactory dexItemFactory) {
    assert this.isArrayType();
    assert !newBase.isArrayType();
    return newBase.toArrayType(getNumberOfLeadingSquareBrackets(), dexItemFactory);
  }

  public DexType replacePackage(String newPackageDescriptor, DexItemFactory dexItemFactory) {
    assert isClassType();
    String descriptorString = toDescriptorString();
    String newDescriptorString = "L";
    if (!newPackageDescriptor.isEmpty()) {
      newDescriptorString += newPackageDescriptor + "/";
    }
    newDescriptorString += DescriptorUtils.getSimpleClassNameFromDescriptor(descriptorString) + ";";
    return dexItemFactory.createType(newDescriptorString);
  }

  public DexType addSuffix(String suffix, DexItemFactory dexItemFactory) {
    assert isClassType();
    String descriptorString = toDescriptorString();
    int endIndex = descriptorString.length() - 1;
    String newDescriptorString = descriptorString.substring(0, endIndex) + suffix + ";";
    return dexItemFactory.createType(newDescriptorString);
  }

  public DexType addSuffixId(int index, DexItemFactory dexItemFactory) {
    if (index == 0) {
      return this;
    }
    assert index > 0;
    return addSuffix("$" + index, dexItemFactory);
  }

  public DexType toArrayType(int dimensions, DexItemFactory dexItemFactory) {
    return dexItemFactory.createType(descriptor.toArrayDescriptor(dimensions, dexItemFactory));
  }

  public DexType toArrayElementType(DexItemFactory dexItemFactory) {
    assert isArrayType();
    DexString newDesc =
        dexItemFactory.createString(
            descriptor.size - 1,
            Arrays.copyOfRange(descriptor.content, 1, descriptor.content.length));
    return dexItemFactory.createType(newDesc);
  }

  private String getPackageOrName(boolean packagePart) {
    assert isClassType();
    String descriptor = toDescriptorString();
    int lastSeparator = descriptor.lastIndexOf('/');
    if (lastSeparator == -1) {
      return packagePart ? "" : descriptor.substring(1, descriptor.length() - 1);
    } else {
      return packagePart
          ? descriptor.substring(1, lastSeparator)
          : descriptor.substring(lastSeparator + 1, descriptor.length() - 1);
    }
  }

  public String getPackageDescriptor() {
    return getPackageOrName(true);
  }

  public String getName() {
    if (isPrimitiveType()) {
      return toSourceString();
    }
    return getPackageOrName(false);
  }

  public String getSimpleName() {
    assert isClassType();
    return DescriptorUtils.getSimpleClassNameFromDescriptor(toDescriptorString());
  }

  public DexType withSimpleName(String newSimpleName, DexItemFactory dexItemFactory) {
    assert isClassType();
    return dexItemFactory.createType(
        DescriptorUtils.replaceSimpleClassNameInDescriptor(toDescriptorString(), newSimpleName));
  }

  /** Get the fully qualified name using '/' in place of '.', aka the "internal type name" in ASM */
  public String getInternalName() {
    assert isClassType() || isArrayType();
    return DescriptorUtils.descriptorToInternalName(toDescriptorString());
  }

  public String getPackageName() {
    return DescriptorUtils.getPackageNameFromBinaryName(toBinaryName());
  }
}
