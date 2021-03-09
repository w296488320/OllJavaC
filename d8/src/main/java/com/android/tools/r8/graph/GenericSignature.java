// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.graph;

import static com.android.tools.r8.utils.DescriptorUtils.getClassBinaryNameFromDescriptor;
import static com.android.tools.r8.utils.DescriptorUtils.getDescriptorFromClassBinaryName;
import static com.google.common.base.Predicates.alwaysTrue;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.utils.DescriptorUtils;
import com.android.tools.r8.utils.Reporter;
import com.google.common.collect.ImmutableList;
import java.lang.reflect.GenericSignatureFormatError;
import java.nio.CharBuffer;
import java.util.List;
import java.util.function.Predicate;

/**
 * Internal encoding of the generics signature attribute as defined by JVMS 7 $ 4.3.4.
 * <pre>
 * ClassSignature ::=
 *     FormalTypeParameters? SuperclassSignature SuperinterfaceSignature*
 *
 *
 * FormalTypeParameters ::=
 *     < FormalTypeParameter+ >
 *
 * FormalTypeParameter ::=
 *     Identifier ClassBound InterfaceBound*
 *
 * ClassBound ::=
 *     : FieldTypeSignature?
 *
 * InterfaceBound ::=
 *     : FieldTypeSignature
 *
 * SuperclassSignature ::=
 *     ClassTypeSignature
 *
 * SuperinterfaceSignature ::=
 *     ClassTypeSignature
 *
 *
 * FieldTypeSignature ::=
 *     ClassTypeSignature
 *     ArrayTypeSignature
 *     TypeVariableSignature
 *
 *
 * ClassTypeSignature ::=
 *     L PackageSpecifier? SimpleClassTypeSignature ClassTypeSignatureSuffix* ;
 *
 * PackageSpecifier ::=
 *     Identifier / PackageSpecifier*
 *
 * SimpleClassTypeSignature ::=
 *     Identifier TypeArguments?
 *
 * ClassTypeSignatureSuffix ::=
 *     . SimpleClassTypeSignature
 *
 * TypeVariableSignature ::=
 *     T Identifier ;
 *
 * TypeArguments ::=
 *     < TypeArgument+ >
 *
 * TypeArgument ::=
 *     WildcardIndicator? FieldTypeSignature
 *     *
 *
 * WildcardIndicator ::=
 *     +
 *     -
 *
 * ArrayTypeSignature ::=
 *     [ TypeSignature
 *
 * TypeSignature ::=
 *     FieldTypeSignature
 *     BaseType
 *
 *
 * MethodTypeSignature ::=
 *     FormalTypeParameters? (TypeSignature*) ReturnType ThrowsSignature*
 *
 * ReturnType ::=
 *     TypeSignature
 *     VoidDescriptor
 *
 * ThrowsSignature ::=
 *     ^ ClassTypeSignature
 *     ^ TypeVariableSignature
 * </pre>
 */
public class GenericSignature {

  static final List<FormalTypeParameter> EMPTY_TYPE_PARAMS = ImmutableList.of();
  static final List<FieldTypeSignature> EMPTY_TYPE_ARGUMENTS = ImmutableList.of();
  static final List<ClassTypeSignature> EMPTY_SUPER_INTERFACES = ImmutableList.of();
  static final List<TypeSignature> EMPTY_TYPE_SIGNATURES = ImmutableList.of();

  interface DexDefinitionSignature<T extends DexDefinition> {

    default boolean isClassSignature() {
      return false;
    }

    default boolean isFieldTypeSignature() {
      return false;
    }

    default boolean isMethodTypeSignature() {
      return false;
    }

    default ClassSignature asClassSignature() {
      return null;
    }

    default FieldTypeSignature asFieldTypeSignature() {
      return null;
    }

    default MethodTypeSignature asMethodTypeSignature() {
      return null;
    }

    boolean hasSignature();

    default boolean hasNoSignature() {
      return !hasSignature();
    }

    default boolean isInvalid() {
      return false;
    }

    DexDefinitionSignature<T> toInvalid();
  }

  public static class FormalTypeParameter {

    final String name;
    final FieldTypeSignature classBound;
    final List<FieldTypeSignature> interfaceBounds;

    FormalTypeParameter(
        String name, FieldTypeSignature classBound, List<FieldTypeSignature> interfaceBounds) {
      this.name = name;
      this.classBound = classBound;
      this.interfaceBounds = interfaceBounds;
    }

    public String getName() {
      return name;
    }

    public FieldTypeSignature getClassBound() {
      return classBound;
    }

    public List<FieldTypeSignature> getInterfaceBounds() {
      return interfaceBounds;
    }

    public void visit(GenericSignatureVisitor visitor) {
      visitor.visitClassBound(classBound);
      if (interfaceBounds == null) {
        return;
      }
      for (FieldTypeSignature interfaceBound : interfaceBounds) {
        visitor.visitInterfaceBound(interfaceBound);
      }
    }
  }

  public static class ClassSignature implements DexDefinitionSignature<DexClass> {

    private static final ClassSignature NO_CLASS_SIGNATURE =
        new ClassSignature(EMPTY_TYPE_PARAMS, NO_FIELD_TYPE_SIGNATURE, EMPTY_SUPER_INTERFACES);

    final List<FormalTypeParameter> formalTypeParameters;
    final ClassTypeSignature superClassSignature;
    final List<ClassTypeSignature> superInterfaceSignatures;

    ClassSignature(
        List<FormalTypeParameter> formalTypeParameters,
        ClassTypeSignature superClassSignature,
        List<ClassTypeSignature> superInterfaceSignatures) {
      assert formalTypeParameters != null;
      assert superClassSignature != null;
      assert superInterfaceSignatures != null;
      this.formalTypeParameters = formalTypeParameters;
      this.superClassSignature = superClassSignature;
      this.superInterfaceSignatures = superInterfaceSignatures;
    }

    public ClassTypeSignature superClassSignature() {
      return superClassSignature;
    }

    public List<ClassTypeSignature> superInterfaceSignatures() {
      return superInterfaceSignatures;
    }

    @Override
    public boolean hasSignature() {
      return this != NO_CLASS_SIGNATURE;
    }

    @Override
    public InvalidClassSignature toInvalid() {
      // Since we could create the structure we are also able to generate a string for it.
      return new InvalidClassSignature(toString());
    }

    @Override
    public boolean isClassSignature() {
      return true;
    }

    @Override
    public ClassSignature asClassSignature() {
      return this;
    }

    public List<FormalTypeParameter> getFormalTypeParameters() {
      return formalTypeParameters;
    }

    public void visit(GenericSignatureVisitor visitor) {
      visitor.visitFormalTypeParameters(formalTypeParameters);
      visitor.visitSuperClass(superClassSignature);
      for (ClassTypeSignature superInterface : superInterfaceSignatures) {
        visitor.visitSuperInterface(superInterface);
      }
    }

    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      if (hasNoSignature()) {
        return null;
      }
      GenericSignaturePrinter genericSignaturePrinter =
          new GenericSignaturePrinter(namingLens, isTypeMissing);
      genericSignaturePrinter.visitClassSignature(this);
      return genericSignaturePrinter.toString();
    }

    @Override
    public String toString() {
      return toRenamedString(NamingLens.getIdentityLens(), alwaysTrue());
    }

    public static ClassSignature noSignature() {
      return NO_CLASS_SIGNATURE;
    }
  }

  private static class InvalidClassSignature extends ClassSignature {

    private final String genericSignatureString;

    InvalidClassSignature(String genericSignatureString) {
      super(EMPTY_TYPE_PARAMS, NO_FIELD_TYPE_SIGNATURE, EMPTY_SUPER_INTERFACES);
      this.genericSignatureString = genericSignatureString;
    }

    @Override
    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      return genericSignatureString;
    }

    @Override
    public String toString() {
      return genericSignatureString;
    }

    @Override
    public InvalidClassSignature toInvalid() {
      assert false : "Should not invoke toInvalid on an invalid signature";
      return this;
    }

    @Override
    public void visit(GenericSignatureVisitor visitor) {
      assert false : "Should not visit an invalid signature";
    }

    @Override
    public boolean isInvalid() {
      return true;
    }
  }

  public abstract static class TypeSignature {

    public boolean isFieldTypeSignature() {
      return false;
    }

    public FieldTypeSignature asFieldTypeSignature() {
      return null;
    }

    public boolean isBaseTypeSignature() {
      return false;
    }

    public BaseTypeSignature asBaseTypeSignature() {
      return null;
    }

    public ArrayTypeSignature toArrayTypeSignature() {
      return null;
    }
  }

  public enum WildcardIndicator {
    NOT_AN_ARGUMENT,
    NONE,
    NEGATIVE,
    POSITIVE
  }

  public abstract static class FieldTypeSignature
      extends TypeSignature implements DexDefinitionSignature<DexEncodedField> {

    private final WildcardIndicator wildcardIndicator;

    private FieldTypeSignature(WildcardIndicator wildcardIndicator) {
      this.wildcardIndicator = wildcardIndicator;
    }

    public final boolean isArgument() {
      return wildcardIndicator != WildcardIndicator.NOT_AN_ARGUMENT;
    }

    public WildcardIndicator getWildcardIndicator() {
      return wildcardIndicator;
    }

    @Override
    public boolean isFieldTypeSignature() {
      return true;
    }

    @Override
    public FieldTypeSignature asFieldTypeSignature() {
      return this;
    }

    public boolean isClassTypeSignature() {
      return false;
    }

    public ClassTypeSignature asClassTypeSignature() {
      return null;
    }

    public boolean isArrayTypeSignature() {
      return false;
    }

    public ArrayTypeSignature asArrayTypeSignature() {
      return null;
    }

    public boolean isTypeVariableSignature() {
      return false;
    }

    public TypeVariableSignature asTypeVariableSignature() {
      return null;
    }

    @Override
    public boolean hasSignature() {
      return this != GenericSignature.NO_FIELD_TYPE_SIGNATURE;
    }

    public abstract FieldTypeSignature asArgument(WildcardIndicator indicator);

    public boolean isStar() {
      return false;
    }

    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      if (hasNoSignature()) {
        return null;
      }
      GenericSignaturePrinter genericSignaturePrinter =
          new GenericSignaturePrinter(namingLens, isTypeMissing);
      genericSignaturePrinter.visitTypeSignature(this);
      return genericSignaturePrinter.toString();
    }

    @Override
    public String toString() {
      return toRenamedString(NamingLens.getIdentityLens(), alwaysTrue());
    }

    public static FieldTypeSignature noSignature() {
      return NO_FIELD_TYPE_SIGNATURE;
    }

    @Override
    public InvalidFieldTypeSignature toInvalid() {
      return new InvalidFieldTypeSignature(toString());
    }
  }

  private static class InvalidFieldTypeSignature extends FieldTypeSignature {

    private final String genericSignature;

    public InvalidFieldTypeSignature(String genericSignature) {
      super(WildcardIndicator.NONE);
      this.genericSignature = genericSignature;
    }

    @Override
    public FieldTypeSignature asArgument(WildcardIndicator indicator) {
      assert false : "Should not be called for an invalid signature";
      return this;
    }

    @Override
    public String toString() {
      return genericSignature;
    }

    @Override
    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      return genericSignature;
    }

    @Override
    public InvalidFieldTypeSignature toInvalid() {
      assert false : " Should not be called for an invalid signature";
      return this;
    }
  }

  static final class StarFieldTypeSignature extends FieldTypeSignature {

    static final StarFieldTypeSignature STAR_FIELD_TYPE_SIGNATURE = new StarFieldTypeSignature();

    private StarFieldTypeSignature() {
      super(WildcardIndicator.NONE);
    }

    @Override
    public FieldTypeSignature asArgument(WildcardIndicator indicator) {
      throw new Unreachable("Should not be called");
    }

    @Override
    public boolean isStar() {
      return true;
    }
  }

  private static final ClassTypeSignature NO_FIELD_TYPE_SIGNATURE =
      new ClassTypeSignature(DexItemFactory.nullValueType, EMPTY_TYPE_ARGUMENTS);

  public static class ClassTypeSignature extends FieldTypeSignature {

    final DexType type;

    // E.g., for Map<K, V>, a signature will indicate what types are for K and V.
    // Note that this could be nested, e.g., Map<K, Consumer<V>>.
    final List<FieldTypeSignature> typeArguments;

    // TODO(b/129925954): towards immutable structure?
    // Double-linked enclosing-inner relations.
    ClassTypeSignature enclosingTypeSignature;
    ClassTypeSignature innerTypeSignature;

    public ClassTypeSignature(DexType type) {
      this(type, EMPTY_TYPE_ARGUMENTS);
    }

    public ClassTypeSignature(DexType type, List<FieldTypeSignature> typeArguments) {
      this(type, typeArguments, WildcardIndicator.NOT_AN_ARGUMENT);
    }

    private ClassTypeSignature(
        DexType type, List<FieldTypeSignature> typeArguments, WildcardIndicator indicator) {
      super(indicator);
      assert type != null;
      assert typeArguments != null;
      this.type = type;
      this.typeArguments = typeArguments;
      assert typeArguments.stream().allMatch(FieldTypeSignature::isArgument);
    }

    public DexType type() {
      return type;
    }

    public List<FieldTypeSignature> typeArguments() {
      return typeArguments;
    }

    @Override
    public boolean isClassTypeSignature() {
      return true;
    }

    @Override
    public ClassTypeSignature asClassTypeSignature() {
      return this;
    }

    @Override
    public ClassTypeSignature asArgument(WildcardIndicator indicator) {
      assert indicator != WildcardIndicator.NOT_AN_ARGUMENT;
      ClassTypeSignature argument = new ClassTypeSignature(type, typeArguments, indicator);
      argument.innerTypeSignature = this.innerTypeSignature;
      argument.enclosingTypeSignature = this.enclosingTypeSignature;
      return argument;
    }

    @Override
    public ArrayTypeSignature toArrayTypeSignature() {
      return new ArrayTypeSignature(this);
    }

    static void link(ClassTypeSignature outer, ClassTypeSignature inner) {
      assert outer.innerTypeSignature == null && inner.enclosingTypeSignature == null;
      outer.innerTypeSignature = inner;
      inner.enclosingTypeSignature = outer;
    }

    public void visit(GenericSignatureVisitor visitor) {
      visitor.visitTypeArguments(typeArguments);
      if (innerTypeSignature != null) {
        visitor.visitSimpleClass(innerTypeSignature);
      }
    }
  }

  public static class ArrayTypeSignature extends FieldTypeSignature {

    final TypeSignature elementSignature;

    ArrayTypeSignature(TypeSignature elementSignature) {
      this(elementSignature, WildcardIndicator.NOT_AN_ARGUMENT);
    }

    private ArrayTypeSignature(TypeSignature elementSignature, WildcardIndicator indicator) {
      super(indicator);
      assert elementSignature != null;
      this.elementSignature = elementSignature;
    }

    public TypeSignature elementSignature() {
      return elementSignature;
    }

    @Override
    public boolean isArrayTypeSignature() {
      return true;
    }

    @Override
    public ArrayTypeSignature asArrayTypeSignature() {
      return this;
    }

    @Override
    public FieldTypeSignature asArgument(WildcardIndicator indicator) {
      assert indicator != WildcardIndicator.NOT_AN_ARGUMENT;
      return new ArrayTypeSignature(elementSignature, indicator);
    }

    @Override
    public ArrayTypeSignature toArrayTypeSignature() {
      return new ArrayTypeSignature(this);
    }

    public void visit(GenericSignatureVisitor visitor) {
      visitor.visitTypeSignature(elementSignature);
    }
  }

  public static class TypeVariableSignature extends FieldTypeSignature {

    final String typeVariable;

    private TypeVariableSignature(String typeVariable) {
      this(typeVariable, WildcardIndicator.NOT_AN_ARGUMENT);
    }

    private TypeVariableSignature(String typeVariable, WildcardIndicator indicator) {
      super(indicator);
      assert typeVariable != null;
      this.typeVariable = typeVariable;
    }

    @Override
    public boolean isTypeVariableSignature() {
      return true;
    }

    @Override
    public TypeVariableSignature asTypeVariableSignature() {
      return this;
    }

    @Override
    public FieldTypeSignature asArgument(WildcardIndicator indicator) {
      assert indicator != WildcardIndicator.NOT_AN_ARGUMENT;
      return new TypeVariableSignature(typeVariable, indicator);
    }

    @Override
    public ArrayTypeSignature toArrayTypeSignature() {
      return new ArrayTypeSignature(this);
    }

    public String typeVariable() {
      return typeVariable;
    }
  }

  // TODO(b/129925954): Canonicalization?
  public static class BaseTypeSignature extends TypeSignature {
    final DexType type;

    BaseTypeSignature(DexType type) {
      assert type != null;
      assert type.isPrimitiveType() : type.toDescriptorString();
      this.type = type;
    }

    @Override
    public boolean isBaseTypeSignature() {
      return true;
    }

    @Override
    public BaseTypeSignature asBaseTypeSignature() {
      return this;
    }

    @Override
    public ArrayTypeSignature toArrayTypeSignature() {
      assert !type.isVoidType();
      return new ArrayTypeSignature(this);
    }
  }

  public static class ReturnType {
    static final ReturnType VOID = new ReturnType(null);

    // `null` indicates that it's `void`.
    final TypeSignature typeSignature;

    ReturnType(TypeSignature typeSignature) {
      this.typeSignature = typeSignature;
    }

    public boolean isVoidDescriptor() {
      return typeSignature == null;
    }

    public TypeSignature typeSignature() {
      return typeSignature;
    }
  }

  public static class MethodTypeSignature implements DexDefinitionSignature<DexEncodedMethod> {

    private static final MethodTypeSignature NO_METHOD_TYPE_SIGNATURE =
        new MethodTypeSignature(
            EMPTY_TYPE_PARAMS, EMPTY_TYPE_SIGNATURES, ReturnType.VOID, EMPTY_TYPE_SIGNATURES);

    final List<FormalTypeParameter> formalTypeParameters;
    final List<TypeSignature> typeSignatures;
    final ReturnType returnType;
    final List<TypeSignature> throwsSignatures;

    public static MethodTypeSignature noSignature() {
      return NO_METHOD_TYPE_SIGNATURE;
    }

    MethodTypeSignature(
        final List<FormalTypeParameter> formalTypeParameters,
        List<TypeSignature> typeSignatures,
        ReturnType returnType,
        List<TypeSignature> throwsSignatures) {
      assert formalTypeParameters != null;
      assert typeSignatures != null;
      assert returnType != null;
      assert throwsSignatures != null;
      this.formalTypeParameters = formalTypeParameters;
      this.typeSignatures = typeSignatures;
      this.returnType = returnType;
      this.throwsSignatures = throwsSignatures;
    }

    public TypeSignature getParameterTypeSignature(int i) {
      if (typeSignatures.isEmpty() || i < 0 || i >= typeSignatures.size()) {
        return null;
      }
      return typeSignatures.get(i);
    }

    public ReturnType returnType() {
      return returnType;
    }

    public List<TypeSignature> throwsSignatures() {
      return throwsSignatures;
    }

    @Override
    public boolean isMethodTypeSignature() {
      return true;
    }

    @Override
    public boolean hasSignature() {
      return this != NO_METHOD_TYPE_SIGNATURE;
    }

    @Override
    public MethodTypeSignature asMethodTypeSignature() {
      return this;
    }

    public void visit(GenericSignatureVisitor visitor) {
      visitor.visitFormalTypeParameters(formalTypeParameters);
      visitor.visitMethodTypeSignatures(typeSignatures);
      visitor.visitReturnType(returnType);
      visitor.visitThrowsSignatures(throwsSignatures);
    }

    public List<FormalTypeParameter> getFormalTypeParameters() {
      return formalTypeParameters;
    }

    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      if (hasNoSignature()) {
        return null;
      }
      GenericSignaturePrinter genericSignaturePrinter =
          new GenericSignaturePrinter(namingLens, isTypeMissing);
      genericSignaturePrinter.visitMethodSignature(this);
      return genericSignaturePrinter.toString();
    }

    @Override
    public String toString() {
      return toRenamedString(NamingLens.getIdentityLens(), alwaysTrue());
    }

    @Override
    public InvalidMethodTypeSignature toInvalid() {
      return new InvalidMethodTypeSignature(toString());
    }
  }

  private static class InvalidMethodTypeSignature extends MethodTypeSignature {

    private final String genericSignature;

    public InvalidMethodTypeSignature(String genericSignature) {
      super(EMPTY_TYPE_PARAMS, EMPTY_TYPE_SIGNATURES, ReturnType.VOID, EMPTY_TYPE_SIGNATURES);
      this.genericSignature = genericSignature;
    }

    @Override
    public String toRenamedString(NamingLens namingLens, Predicate<DexType> isTypeMissing) {
      return genericSignature;
    }

    @Override
    public String toString() {
      return genericSignature;
    }

    @Override
    public boolean isInvalid() {
      return true;
    }

    @Override
    public void visit(GenericSignatureVisitor visitor) {
      assert false : "Should not visit an invalid signature";
    }

    @Override
    public InvalidMethodTypeSignature toInvalid() {
      assert false : "Should not be called for an invalid signature";
      return this;
    }
  }

  public static ClassSignature parseClassSignature(
      String className,
      String signature,
      Origin origin,
      DexItemFactory factory,
      Reporter reporter) {
    if (signature == null || signature.isEmpty()) {
      return ClassSignature.NO_CLASS_SIGNATURE;
    }
    Parser parser = new Parser(factory);
    try {
      return parser.parseClassSignature(signature);
    } catch (GenericSignatureFormatError e) {
      reporter.warning(
          GenericSignatureDiagnostic.invalidClassSignature(signature, className, origin, e));
      return ClassSignature.NO_CLASS_SIGNATURE;
    }
  }

  public static FieldTypeSignature parseFieldTypeSignature(
      String fieldName,
      String signature,
      Origin origin,
      DexItemFactory factory,
      Reporter reporter) {
    if (signature == null || signature.isEmpty()) {
      return NO_FIELD_TYPE_SIGNATURE;
    }
    Parser parser = new Parser(factory);
    try {
      return parser.parseFieldTypeSignature(signature);
    } catch (GenericSignatureFormatError e) {
      reporter.warning(
          GenericSignatureDiagnostic.invalidFieldSignature(signature, fieldName, origin, e));
      return GenericSignature.NO_FIELD_TYPE_SIGNATURE;
    }
  }

  public static MethodTypeSignature parseMethodSignature(
      String methodName,
      String signature,
      Origin origin,
      DexItemFactory factory,
      Reporter reporter) {
    if (signature == null || signature.isEmpty()) {
      return MethodTypeSignature.NO_METHOD_TYPE_SIGNATURE;
    }
    Parser parser = new Parser(factory);
    try {
      return parser.parseMethodTypeSignature(signature);
    } catch (GenericSignatureFormatError e) {
      reporter.warning(
          GenericSignatureDiagnostic.invalidMethodSignature(signature, methodName, origin, e));
      return MethodTypeSignature.NO_METHOD_TYPE_SIGNATURE;
    }
  }

  public static class Parser {

    /*
     * Parser:
     */
    private char symbol; // 0: eof; else valid term symbol or first char of identifier.

    private String identifier;

    /*
     * Scanner:
     * eof is private to the scan methods
     * and it's set only when a scan is issued at the end of the buffer.
     */
    private boolean eof;

    private char[] buffer;

    private int pos;

    private Parser(DexItemFactory factory) {
      this.factory = factory;
    }

    ClassSignature parseClassSignature(String signature) {
      try {
        setInput(signature);
        return parseClassSignature();
      } catch (GenericSignatureFormatError e) {
        throw e;
      } catch (Throwable t) {
        Error e = new GenericSignatureFormatError(
            "Unknown error parsing class signature: " + t.getMessage());
        e.addSuppressed(t);
        throw e;
      }
    }

    MethodTypeSignature parseMethodTypeSignature(String signature) {
      try {
        setInput(signature);
        return parseMethodTypeSignature();
      } catch (GenericSignatureFormatError e) {
        throw e;
      } catch (Throwable t) {
        Error e = new GenericSignatureFormatError(
            "Unknown error parsing method signature: " + t.getMessage());
        e.addSuppressed(t);
        throw e;
      }
    }

    FieldTypeSignature parseFieldTypeSignature(String signature) {
      try {
        setInput(signature);
        return parseFieldTypeSignature();
      } catch (GenericSignatureFormatError e) {
        throw e;
      } catch (Throwable t) {
        Error e = new GenericSignatureFormatError(
            "Unknown error parsing field signature: " + t.getMessage());
        e.addSuppressed(t);
        throw e;
      }
    }

    private void setInput(String input) {
      this.buffer = input.toCharArray();
      this.eof = false;
      pos = 0;
      symbol = 0;
      identifier = null;
      scanSymbol();
    }

    //
    // Action:
    //

    private final DexItemFactory factory;

    private DexType parsedTypeName(String name) {
      String originalDescriptor = getDescriptorFromClassBinaryName(name);
      return factory.createType(originalDescriptor);
    }

    private DexType parsedInnerTypeName(DexType enclosingType, String name) {
      if (enclosingType == null) {
        // We are writing inner type names
        return null;
      }
      assert enclosingType.isClassType();
      String enclosingDescriptor = enclosingType.toDescriptorString();
      return factory.createType(
          getDescriptorFromClassBinaryName(
              getClassBinaryNameFromDescriptor(enclosingDescriptor)
                  + DescriptorUtils.INNER_CLASS_SEPARATOR
                  + name));
    }

    //
    // Parser:
    //

    private ClassSignature parseClassSignature() {
      // ClassSignature ::= FormalTypeParameters? SuperclassSignature SuperinterfaceSignature*.

      List<FormalTypeParameter> formalTypeParameters = parseOptFormalTypeParameters();

      // SuperclassSignature ::= ClassTypeSignature.
      ClassTypeSignature superClassSignature = parseClassTypeSignature();

      ImmutableList.Builder<ClassTypeSignature> builder = ImmutableList.builder();
      while (symbol > 0) {
        // SuperinterfaceSignature ::= ClassTypeSignature.
        builder.add(parseClassTypeSignature());
      }

      return new ClassSignature(formalTypeParameters, superClassSignature, builder.build());
    }

    private List<FormalTypeParameter> parseOptFormalTypeParameters() {
      // FormalTypeParameters ::= "<" FormalTypeParameter+ ">".
      if (symbol != '<') {
        return EMPTY_TYPE_PARAMS;
      }
      scanSymbol();

      ImmutableList.Builder<FormalTypeParameter> builder = ImmutableList.builder();
      while ((symbol != '>') && (symbol > 0)) {
        builder.add(updateFormalTypeParameter());
      }
      expect('>');
      return builder.build();
    }

    private FormalTypeParameter updateFormalTypeParameter() {
      // FormalTypeParameter ::= Identifier ClassBound InterfaceBound*.
      scanIdentifier();
      assert identifier != null;

      String typeParameterIdentifier = identifier;

      // ClassBound ::= ":" FieldTypeSignature?.
      expect(':');

      FieldTypeSignature classBound = GenericSignature.NO_FIELD_TYPE_SIGNATURE;
      if (symbol == 'L' || symbol == '[' || symbol == 'T') {
        classBound = parseFieldTypeSignature();
      }

      // Only build the interfacebound builder, which is uncommon, if we actually see an interface.
      ImmutableList.Builder<FieldTypeSignature> builder = null;
      while (symbol == ':') {
        // InterfaceBound ::= ":" FieldTypeSignature.
        if (builder == null) {
          builder = ImmutableList.builder();
        }
        scanSymbol();
        builder.add(parseFieldTypeSignature());
      }
      if (builder == null) {
        return new FormalTypeParameter(typeParameterIdentifier, classBound, null);
      }
      return new FormalTypeParameter(typeParameterIdentifier, classBound, builder.build());
    }

    private FieldTypeSignature parseFieldTypeSignature() {
      // FieldTypeSignature ::= ClassTypeSignature | ArrayTypeSignature | TypeVariableSignature.
      switch (symbol) {
        case 'L':
          return parseClassTypeSignature();
        case '[':
          // ArrayTypeSignature ::= "[" TypeSignature.
          scanSymbol();
          TypeSignature baseTypeSignature = updateTypeSignature();
          return baseTypeSignature.toArrayTypeSignature().asFieldTypeSignature();
        case 'T':
          return updateTypeVariableSignature();
        default:
          parseError("Expected L, [ or T", pos);
      }
      throw new Unreachable("Either FieldTypeSignature is returned or a parse error is thrown.");
    }

    private ClassTypeSignature parseClassTypeSignature() {
      // ClassTypeSignature ::=
      //   "L" (Identifier "/")* Identifier TypeArguments? ("." Identifier TypeArguments?)* ";".
      expect('L');

      StringBuilder qualIdent = new StringBuilder();
      scanIdentifier();
      assert identifier != null;
      while (symbol == '/') {
        qualIdent.append(identifier).append(symbol);
        scanSymbol();
        scanIdentifier();
        assert identifier != null;
      }

      qualIdent.append(this.identifier);
      DexType parsedEnclosingType = parsedTypeName(qualIdent.toString());

      List<FieldTypeSignature> typeArguments = updateOptTypeArguments();
      ClassTypeSignature outerMostTypeSignature =
          new ClassTypeSignature(
              parsedEnclosingType, typeArguments.isEmpty() ? EMPTY_TYPE_ARGUMENTS : typeArguments);

      ClassTypeSignature outerTypeSignature = outerMostTypeSignature;
      ClassTypeSignature innerTypeSignature;
      while (symbol == '.') {
        // Deal with Member Classes.
        scanSymbol();
        scanIdentifier();
        assert identifier != null;
        parsedEnclosingType = parsedInnerTypeName(parsedEnclosingType, identifier);
        typeArguments = updateOptTypeArguments();
        innerTypeSignature =
            new ClassTypeSignature(
                parsedEnclosingType,
                typeArguments.isEmpty() ? EMPTY_TYPE_ARGUMENTS : typeArguments);
        ClassTypeSignature.link(outerTypeSignature, innerTypeSignature);
        outerTypeSignature = innerTypeSignature;
      }

      expect(';');
      return outerMostTypeSignature;
    }

    private List<FieldTypeSignature> updateOptTypeArguments() {
      ImmutableList.Builder<FieldTypeSignature> builder = ImmutableList.builder();
      // OptTypeArguments ::= "<" TypeArgument+ ">".
      if (symbol == '<') {
        scanSymbol();

        builder.add(updateTypeArgument());
        while ((symbol != '>') && (symbol > 0)) {
          builder.add(updateTypeArgument());
        }

        expect('>');
      }
      return builder.build();
    }

    private FieldTypeSignature updateTypeArgument() {
      // TypeArgument ::= (["+" | "-"] FieldTypeSignature) | "*".
      if (symbol == '*') {
        scanSymbol();
        return StarFieldTypeSignature.STAR_FIELD_TYPE_SIGNATURE;
      } else if (symbol == '+') {
        scanSymbol();
        return parseFieldTypeSignature().asArgument(WildcardIndicator.POSITIVE);
      } else if (symbol == '-') {
        scanSymbol();
        return parseFieldTypeSignature().asArgument(WildcardIndicator.NEGATIVE);
      } else {
        return parseFieldTypeSignature().asArgument(WildcardIndicator.NONE);
      }
    }

    private TypeVariableSignature updateTypeVariableSignature() {
      // TypeVariableSignature ::= "T" Identifier ";".
      expect('T');

      scanIdentifier();
      assert identifier != null;

      expect(';');
      return new TypeVariableSignature(identifier);
    }

    private TypeSignature updateTypeSignature() {
      switch (symbol) {
        case 'B':
        case 'C':
        case 'D':
        case 'F':
        case 'I':
        case 'J':
        case 'S':
        case 'Z':
          DexType type = factory.createType(String.valueOf(symbol));
          BaseTypeSignature baseTypeSignature = new BaseTypeSignature(type);
          scanSymbol();
          return baseTypeSignature;
        default:
          // Not an elementary type, but a FieldTypeSignature.
          return parseFieldTypeSignature();
      }
    }

    private MethodTypeSignature parseMethodTypeSignature() {
      // MethodTypeSignature ::=
      //     FormalTypeParameters? "(" TypeSignature* ")" ReturnType ThrowsSignature*.
      List<FormalTypeParameter> formalTypeParameters = parseOptFormalTypeParameters();

      expect('(');

      ImmutableList.Builder<TypeSignature> parameterSignatureBuilder = ImmutableList.builder();
      while (symbol != ')' && (symbol > 0)) {
        parameterSignatureBuilder.add(updateTypeSignature());
      }

      expect(')');

      ReturnType returnType = updateReturnType();

      ImmutableList.Builder<TypeSignature> throwsSignatureBuilder = ImmutableList.builder();
      if (symbol == '^') {
        do {
          scanSymbol();
          // ThrowsSignature ::= ("^" ClassTypeSignature) | ("^" TypeVariableSignature).
          if (symbol == 'T') {
            throwsSignatureBuilder.add(updateTypeVariableSignature());
          } else {
            throwsSignatureBuilder.add(parseClassTypeSignature());
          }
        } while (symbol == '^');
      }

      return new MethodTypeSignature(
          formalTypeParameters,
          parameterSignatureBuilder.build(),
          returnType,
          throwsSignatureBuilder.build());
    }

    private ReturnType updateReturnType() {
      // ReturnType ::= TypeSignature | "V".
      if (symbol != 'V') {
        return new ReturnType(updateTypeSignature());
      } else {
        scanSymbol();
        return ReturnType.VOID;
      }
    }

    //
    // Scanner:
    //

    private void scanSymbol() {
      if (!eof) {
        assert buffer != null;
        if (pos < buffer.length) {
          symbol = buffer[pos];
          pos++;
        } else {
          symbol = 0;
          eof = true;
        }
      } else {
        parseError("Unexpected end of signature", pos);
      }
    }

    private void expect(char c) {
      if (eof) {
        parseError("Unexpected end of signature", pos);
      }
      if (symbol == c) {
        scanSymbol();
      } else {
        parseError("Expected " + c, pos - 1);
      }
    }

    private boolean isStopSymbol(char ch) {
      switch (ch) {
        case ':':
        case '/':
        case ';':
        case '<':
        case '.':
          return true;
        default:
          return false;
      }
    }

    // PRE: symbol is the first char of the identifier.
    // POST: symbol = the next symbol AFTER the identifier.
    private void scanIdentifier() {
      if (!eof && pos < buffer.length) {
        StringBuilder identifierBuilder = new StringBuilder(32);
        if (!isStopSymbol(symbol)) {
          identifierBuilder.append(symbol);

          char[] bufferLocal = buffer;
          assert bufferLocal != null;
          do {
            char ch = bufferLocal[pos];
            if (((ch >= 'a') && (ch <= 'z')) || ((ch >= 'A') && (ch <= 'Z'))
                || !isStopSymbol(ch)) {
              identifierBuilder.append(bufferLocal[pos]);
              pos++;
            } else {
              identifier = identifierBuilder.toString();
              scanSymbol();
              return;
            }
          } while (pos != bufferLocal.length);
          identifier = identifierBuilder.toString();
          symbol = 0;
          eof = true;
        } else {
          // Identifier starts with incorrect char.
          symbol = 0;
          eof = true;
          parseError();
        }
      } else {
        parseError("Unexpected end of signature", pos);
      }
    }

    private void parseError() {
      parseError("Unexpected", pos);
    }

    private void parseError(String message, int pos) {
      String arrow = CharBuffer.allocate(pos).toString().replace('\0', ' ') + '^';
      throw new GenericSignatureFormatError(
          message + " at position " + (pos + 1) + System.lineSeparator()
              + String.valueOf(buffer) + System.lineSeparator()
              + arrow);
    }
  }
}
