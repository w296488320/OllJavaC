// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace.internal;

import static com.google.common.base.Predicates.not;

import com.android.tools.r8.naming.ClassNamingForNameMapper.MappedRange;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.references.ClassReference;
import com.android.tools.r8.references.MethodReference;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.references.TypeReference;
import com.android.tools.r8.retrace.RetraceClassResult;
import com.android.tools.r8.retrace.RetraceClassResult.Element;
import com.android.tools.r8.retrace.RetraceSourceFileResult;
import com.android.tools.r8.retrace.RetracedClass;
import com.android.tools.r8.retrace.RetracedMethod;
import com.android.tools.r8.retrace.RetracedMethod.KnownRetracedMethod;
import com.android.tools.r8.retrace.Retracer;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.DescriptorUtils;
import com.google.common.collect.Sets;
import com.google.common.io.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

public class RetraceUtils {

  private static final Set<String> KEEP_SOURCEFILE_NAMES = Sets.newHashSet("Native Method");

  public static String methodDescriptionFromRetraceMethod(
      RetracedMethod methodReference, boolean appendHolder, boolean verbose) {
    StringBuilder sb = new StringBuilder();
    if (appendHolder) {
      sb.append(methodReference.getHolderClass().getTypeName());
      sb.append(".");
    }
    if (!verbose || methodReference.isUnknown()) {
      return sb.append(methodReference.getMethodName()).toString();
    }
    assert methodReference.isKnown();
    KnownRetracedMethod knownRef = methodReference.asKnown();
    sb.append(knownRef.isVoid() ? "void" : knownRef.getReturnType().getTypeName());
    sb.append(" ");
    sb.append(methodReference.getMethodName());
    sb.append("(");
    boolean seenFirstIndex = false;
    for (TypeReference formalType : knownRef.getFormalTypes()) {
      if (seenFirstIndex) {
        sb.append(",");
      }
      seenFirstIndex = true;
      sb.append(formalType.getTypeName());
    }
    sb.append(")");
    return sb.toString();
  }

  public static boolean hasPredictableSourceFileName(String originalClassName, String sourceFile) {
    String synthesizedSourceFileName = getOuterClassSimpleName(originalClassName) + ".java";
    return synthesizedSourceFileName.equals(sourceFile);
  }

  private static String getOuterClassSimpleName(String clazz) {
    int lastIndexOfPeriod = clazz.lastIndexOf(DescriptorUtils.JAVA_PACKAGE_SEPARATOR);
    // Check if we can find a subclass separator.
    int endIndex = clazz.indexOf(DescriptorUtils.INNER_CLASS_SEPARATOR, lastIndexOfPeriod);
    if (lastIndexOfPeriod > endIndex || endIndex < 0) {
      endIndex = clazz.length();
    }
    return clazz.substring(lastIndexOfPeriod + 1, endIndex);
  }

  static RetraceSourceFileResult getSourceFile(
      Element classElement, RetracedClass context, String sourceFile, Retracer retracer) {
    // If no context is specified always retrace using the found class element.
    if (context == null) {
      return classElement.retraceSourceFile(sourceFile);
    }
    if (context.equals(classElement.getRetracedClass())) {
      return classElement.retraceSourceFile(sourceFile);
    } else {
      RetraceClassResult contextClassResult = retracer.retraceClass(context.getClassReference());
      assert !contextClassResult.isAmbiguous();
      if (contextClassResult.hasRetraceResult()) {
        Box<RetraceSourceFileResult> retraceSourceFile = new Box<>();
        contextClassResult.forEach(
            element -> retraceSourceFile.set(element.retraceSourceFile(sourceFile)));
        return retraceSourceFile.get();
      } else {
        return new RetraceSourceFileResultImpl(
            synthesizeFileName(
                context.getTypeName(),
                classElement.getRetracedClass().getTypeName(),
                sourceFile,
                true),
            true);
      }
    }
  }

  public static String synthesizeFileName(
      String retracedClassName,
      String minifiedClassName,
      String sourceFile,
      boolean hasRetraceResult) {
    if (!hasRetraceResult || KEEP_SOURCEFILE_NAMES.contains(sourceFile)) {
      return sourceFile;
    }
    String extension = Files.getFileExtension(sourceFile);
    String newFileName = getOuterClassSimpleName(retracedClassName);
    if (newFileName.endsWith("Kt") && (extension.isEmpty() || extension.equals("kt"))) {
      newFileName = newFileName.substring(0, newFileName.length() - 2);
      extension = "kt";
    } else if (!extension.equals("kt")) {
      extension = "java";
    }
    return newFileName + "." + extension;
  }

  static MethodReference methodReferenceFromMappedRange(
      MappedRange mappedRange, ClassReference classReference) {
    MethodSignature signature = mappedRange.signature;
    ClassReference holder =
        signature.isQualified()
            ? Reference.classFromDescriptor(
                DescriptorUtils.javaTypeToDescriptor(signature.toHolderFromQualified()))
            : classReference;
    List<TypeReference> formalTypes = new ArrayList<>(signature.parameters.length);
    for (String parameter : signature.parameters) {
      formalTypes.add(Reference.typeFromTypeName(parameter));
    }
    TypeReference returnType =
        Reference.returnTypeFromDescriptor(DescriptorUtils.javaTypeToDescriptor(signature.type));
    return Reference.method(
        holder,
        signature.isQualified() ? signature.toUnqualifiedName() : signature.name,
        formalTypes,
        returnType);
  }

  public static int firstNonWhiteSpaceCharacterFromIndex(String line, int index) {
    return firstFromIndex(line, index, not(Character::isWhitespace));
  }

  public static int firstCharFromIndex(String line, int index, char ch) {
    return firstFromIndex(line, index, c -> c == ch);
  }

  public static int firstFromIndex(String line, int index, Predicate<Character> predicate) {
    for (int i = index; i < line.length(); i++) {
      if (predicate.test(line.charAt(i))) {
        return i;
      }
    }
    return line.length();
  }
}
