// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.shaking.AppInfoWithLiveness;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

public abstract class TreeFixerBase {

  private final AppView<?> appView;
  private final DexItemFactory dexItemFactory;

  private final Map<DexType, DexProgramClass> programClassCache = new IdentityHashMap<>();
  private final Map<DexType, DexProgramClass> synthesizedFromClasses = new IdentityHashMap<>();
  private final Map<DexProto, DexProto> protoFixupCache = new IdentityHashMap<>();

  public TreeFixerBase(AppView<?> appView) {
    this.appView = appView;
    this.dexItemFactory = appView.dexItemFactory();
  }

  /** Mapping of a class type to a potentially new class type. */
  public abstract DexType mapClassType(DexType type);

  /** Callback invoked each time an encoded field changes field reference. */
  public abstract void recordFieldChange(DexField from, DexField to);

  /** Callback invoked each time an encoded method changes method reference. */
  public abstract void recordMethodChange(DexMethod from, DexMethod to);

  /** Callback invoked each time a program class definition changes type reference. */
  public abstract void recordClassChange(DexType from, DexType to);

  private DexProgramClass recordClassChange(DexProgramClass from, DexProgramClass to) {
    recordClassChange(from.getType(), to.getType());
    return to;
  }

  private DexEncodedField recordFieldChange(DexEncodedField from, DexEncodedField to) {
    recordFieldChange(from.field, to.field);
    return to;
  }

  /** Rewrite missing references */
  public void recordFailedResolutionChanges() {
    // In order for optimizations to correctly rewrite field and method references that do not
    // resolve, we create a mapping from each failed resolution target to its reference reference.
    if (!appView.appInfo().hasLiveness()) {
      return;
    }
    AppInfoWithLiveness appInfoWithLiveness = appView.appInfo().withLiveness();
    appInfoWithLiveness
        .getFailedFieldResolutionTargets()
        .forEach(
            field -> {
              DexField fixedUpField = fixupFieldReference(field);
              if (field != fixedUpField) {
                recordFieldChange(field, fixedUpField);
              }
            });
    appInfoWithLiveness
        .getFailedMethodResolutionTargets()
        .forEach(
            method -> {
              DexMethod fixedUpMethod = fixupMethodReference(method);
              if (method != fixedUpMethod) {
                recordMethodChange(method, fixedUpMethod);
              }
            });
  }

  /** Callback to allow custom handling when an encoded method changes. */
  public DexEncodedMethod recordMethodChange(DexEncodedMethod from, DexEncodedMethod to) {
    recordMethodChange(from.method, to.method);
    return to;
  }

  /** Fixup a collection of classes. */
  public List<DexProgramClass> fixupClasses(Collection<DexProgramClass> classes) {
    List<DexProgramClass> newProgramClasses = new ArrayList<>();
    for (DexProgramClass clazz : classes) {
      newProgramClasses.add(
          programClassCache.computeIfAbsent(clazz.getType(), ignore -> fixupClass(clazz)));
    }
    return newProgramClasses;
  }

  // Should remain private as the correctness of the fixup requires the lazy 'newProgramClasses'.
  private DexProgramClass fixupClass(DexProgramClass clazz) {
    DexProgramClass newClass =
        new DexProgramClass(
            fixupType(clazz.getType()),
            clazz.getOriginKind(),
            clazz.getOrigin(),
            clazz.getAccessFlags(),
            clazz.superType == null ? null : fixupType(clazz.superType),
            fixupTypeList(clazz.interfaces),
            clazz.getSourceFile(),
            fixupNestHost(clazz.getNestHostClassAttribute()),
            fixupNestMemberAttributes(clazz.getNestMembersClassAttributes()),
            fixupEnclosingMethodAttribute(clazz.getEnclosingMethodAttribute()),
            fixupInnerClassAttributes(clazz.getInnerClasses()),
            clazz.getClassSignature(),
            clazz.annotations(),
            DexEncodedField.EMPTY_ARRAY,
            DexEncodedField.EMPTY_ARRAY,
            DexEncodedMethod.EMPTY_ARRAY,
            DexEncodedMethod.EMPTY_ARRAY,
            dexItemFactory.getSkipNameValidationForTesting(),
            clazz.getChecksumSupplier(),
            fixupSynthesizedFrom(clazz.getSynthesizedFrom()));
    newClass.setInstanceFields(fixupFields(clazz.instanceFields()));
    newClass.setStaticFields(fixupFields(clazz.staticFields()));
    newClass.setDirectMethods(
        fixupMethods(
            clazz.getMethodCollection().directMethods(),
            clazz.getMethodCollection().numberOfDirectMethods()));
    newClass.setVirtualMethods(
        fixupMethods(
            clazz.getMethodCollection().virtualMethods(),
            clazz.getMethodCollection().numberOfVirtualMethods()));
    // Transfer properties that are not passed to the constructor.
    if (clazz.hasClassFileVersion()) {
      newClass.setInitialClassFileVersion(clazz.getInitialClassFileVersion());
    }
    if (clazz.isDeprecated()) {
      newClass.setDeprecated();
    }
    if (clazz.getKotlinInfo() != null) {
      newClass.setKotlinInfo(clazz.getKotlinInfo());
    }
    // If the class type changed, record the move in the lens.
    if (newClass.getType() != clazz.getType()) {
      return recordClassChange(clazz, newClass);
    }
    return newClass;
  }

  private EnclosingMethodAttribute fixupEnclosingMethodAttribute(
      EnclosingMethodAttribute enclosingMethodAttribute) {
    if (enclosingMethodAttribute == null) {
      return null;
    }
    DexType enclosingClassType = enclosingMethodAttribute.getEnclosingClass();
    if (enclosingClassType != null) {
      DexType newEnclosingClassType = fixupType(enclosingClassType);
      return newEnclosingClassType != enclosingClassType
          ? new EnclosingMethodAttribute(newEnclosingClassType)
          : enclosingMethodAttribute;
    }
    DexMethod enclosingMethod = enclosingMethodAttribute.getEnclosingMethod();
    assert enclosingMethod != null;
    DexMethod newEnclosingMethod =
        fixupMethodReference(enclosingMethodAttribute.getEnclosingMethod());
    return newEnclosingMethod != enclosingMethod
        ? new EnclosingMethodAttribute(newEnclosingMethod)
        : enclosingMethodAttribute;
  }

  /** Fixup a list of fields. */
  public DexEncodedField[] fixupFields(List<DexEncodedField> fields) {
    if (fields == null) {
      return DexEncodedField.EMPTY_ARRAY;
    }
    DexEncodedField[] newFields = new DexEncodedField[fields.size()];
    for (int i = 0; i < fields.size(); i++) {
      newFields[i] = fixupField(fields.get(i));
    }
    return newFields;
  }

  private DexEncodedField fixupField(DexEncodedField field) {
    DexField fieldReference = field.getReference();
    DexField newFieldReference = fixupFieldReference(fieldReference);
    if (newFieldReference != fieldReference) {
      return recordFieldChange(field, field.toTypeSubstitutedField(newFieldReference));
    }
    return field;
  }

  /** Fixup a field reference. */
  public DexField fixupFieldReference(DexField field) {
    DexType newType = fixupType(field.type);
    DexType newHolder = fixupType(field.holder);
    return dexItemFactory.createField(newHolder, newType, field.name);
  }

  private List<InnerClassAttribute> fixupInnerClassAttributes(
      List<InnerClassAttribute> innerClassAttributes) {
    if (innerClassAttributes.isEmpty()) {
      return innerClassAttributes;
    }
    boolean changed = false;
    List<InnerClassAttribute> newInnerClassAttributes = new ArrayList<>();
    for (InnerClassAttribute innerClassAttribute : innerClassAttributes) {
      DexType innerClassType = innerClassAttribute.getInner();
      DexType newInnerClassType = fixupTypeOrNull(innerClassType);
      DexType outerClassType = innerClassAttribute.getOuter();
      DexType newOuterClassType = fixupTypeOrNull(outerClassType);
      newInnerClassAttributes.add(
          new InnerClassAttribute(
              innerClassAttribute.getAccess(),
              newInnerClassType,
              newOuterClassType,
              innerClassAttribute.getInnerName()));
      changed |= newInnerClassType != innerClassType || newOuterClassType != outerClassType;
    }
    return changed ? newInnerClassAttributes : innerClassAttributes;
  }

  private DexEncodedMethod[] fixupMethods(Iterable<DexEncodedMethod> methods, int size) {
    if (size == 0) {
      return DexEncodedMethod.EMPTY_ARRAY;
    }
    int i = 0;
    DexEncodedMethod[] newMethods = new DexEncodedMethod[size];
    for (DexEncodedMethod method : methods) {
      newMethods[i++] = fixupMethod(method);
    }
    return newMethods;
  }
  /** Fixup a method definition. */
  public DexEncodedMethod fixupMethod(DexEncodedMethod method) {
    DexMethod methodReference = method.getReference();
    DexMethod newMethodReference = fixupMethodReference(methodReference);
    if (newMethodReference != methodReference) {
      return recordMethodChange(method, method.toTypeSubstitutedMethod(newMethodReference));
    }
    return method;
  }

  /** Fixup a method reference. */
  public DexMethod fixupMethodReference(DexMethod method) {
    return dexItemFactory.createMethod(
        fixupType(method.holder), fixupProto(method.proto), method.name);
  }

  private NestHostClassAttribute fixupNestHost(NestHostClassAttribute nestHostClassAttribute) {
    return nestHostClassAttribute != null
        ? new NestHostClassAttribute(fixupType(nestHostClassAttribute.getNestHost()))
        : null;
  }

  private List<NestMemberClassAttribute> fixupNestMemberAttributes(
      List<NestMemberClassAttribute> nestMemberAttributes) {
    if (nestMemberAttributes.isEmpty()) {
      return nestMemberAttributes;
    }
    boolean changed = false;
    List<NestMemberClassAttribute> newNestMemberAttributes =
        new ArrayList<>(nestMemberAttributes.size());
    for (NestMemberClassAttribute nestMemberAttribute : nestMemberAttributes) {
      DexType nestMemberType = nestMemberAttribute.getNestMember();
      DexType newNestMemberType = fixupType(nestMemberType);
      newNestMemberAttributes.add(new NestMemberClassAttribute(newNestMemberType));
      changed |= newNestMemberType != nestMemberType;
    }
    return changed ? newNestMemberAttributes : nestMemberAttributes;
  }

  /** Fixup a proto descriptor. */
  public DexProto fixupProto(DexProto proto) {
    DexProto result = protoFixupCache.get(proto);
    if (result == null) {
      DexType returnType = fixupType(proto.returnType);
      DexType[] arguments = fixupTypes(proto.parameters.values);
      result = dexItemFactory.createProto(returnType, arguments);
      protoFixupCache.put(proto, result);
    }
    return result;
  }

  // Should remain private as its correctness relies on the setup of 'newProgramClasses'.
  private Collection<DexProgramClass> fixupSynthesizedFrom(
      Collection<DexProgramClass> synthesizedFrom) {
    if (synthesizedFrom.isEmpty()) {
      return synthesizedFrom;
    }
    boolean changed = false;
    List<DexProgramClass> newSynthesizedFrom = new ArrayList<>(synthesizedFrom.size());
    for (DexProgramClass clazz : synthesizedFrom) {
      // TODO(b/165783399): What do we want to put here if the class that this was synthesized from
      //  is no longer in the application?
      Map<DexType, DexProgramClass> classes =
          appView.appInfo().definitionForWithoutExistenceAssert(clazz.getType()) != null
              ? programClassCache
              : synthesizedFromClasses;
      DexProgramClass newClass =
          classes.computeIfAbsent(clazz.getType(), ignore -> fixupClass(clazz));
      newSynthesizedFrom.add(newClass);
      changed |= newClass != clazz;
    }
    return changed ? newSynthesizedFrom : synthesizedFrom;
  }

  private DexType fixupTypeOrNull(DexType type) {
    return type != null ? fixupType(type) : null;
  }

  /** Fixup a type reference. */
  public DexType fixupType(DexType type) {
    if (type.isArrayType()) {
      DexType base = type.toBaseType(dexItemFactory);
      DexType fixed = fixupType(base);
      if (base == fixed) {
        return type;
      }
      return type.replaceBaseType(fixed, dexItemFactory);
    }
    if (type.isClassType()) {
      return mapClassType(type);
    }
    return type;
  }

  private DexType[] fixupTypes(DexType[] types) {
    boolean changed = false;
    DexType[] newTypes = new DexType[types.length];
    for (int i = 0; i < newTypes.length; i++) {
      DexType type = types[i];
      DexType newType = fixupType(types[i]);
      newTypes[i] = newType;
      changed |= newType != type;
    }
    return changed ? newTypes : types;
  }

  private DexTypeList fixupTypeList(DexTypeList types) {
    DexType[] newTypes = fixupTypes(types.values);
    return newTypes != types.values ? new DexTypeList(newTypes) : types;
  }

  /** Fixup a method signature. */
  public DexMethodSignature fixupMethodSignature(DexMethodSignature signature) {
    return signature.withProto(fixupProto(signature.getProto()));
  }
}
