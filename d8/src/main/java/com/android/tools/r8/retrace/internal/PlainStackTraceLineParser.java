// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.retrace.internal;

import static com.android.tools.r8.retrace.internal.RetraceUtils.firstCharFromIndex;
import static com.android.tools.r8.retrace.internal.RetraceUtils.firstNonWhiteSpaceCharacterFromIndex;

import com.android.tools.r8.retrace.StackTraceLineParser;
import com.android.tools.r8.retrace.internal.StackTraceElementStringProxy.ClassNameType;
import com.android.tools.r8.retrace.internal.StackTraceElementStringProxy.StackTraceElementStringProxyBuilder;
import com.android.tools.r8.utils.DescriptorUtils;

public final class PlainStackTraceLineParser
    implements StackTraceLineParser<String, StackTraceElementStringProxy> {

  public PlainStackTraceLineParser() {}

  @Override
  public StackTraceElementStringProxy parse(String stackTraceLine) {
    return parseLine(stackTraceLine);
  }

  /**
   * Captures a stack trace line of the following formats:
   *
   * <ul>
   *   <li>com.android.r8.R8Exception
   *   <li>com.android.r8.R8Exception: Problem when compiling program
   *   <li>Caused by: com.android.r8.R8InnerException: You have to write the program first
   *   <li>com.android.r8.R8InnerException: You have to write the program first
   * </ul>
   *
   * <p>This will also contains false positives, such as
   *
   * <pre>
   *   W( 8207) VFY: unable to resolve static method 11: Lprivateinterfacemethods/I$-CC;....
   * </pre>
   *
   * <p>The only invalid chars for type-identifiers for a java type-name is ';', '[' and '/', so we
   * cannot really disregard the above line.
   *
   * <p>Caused by and Suppressed seems to not change based on locale, so we use these as markers.
   */
  private static class ExceptionLine {

    private static final String CAUSED_BY = "Caused by: ";
    private static final String SUPPRESSED = "Suppressed: ";

    private static StackTraceElementStringProxy tryParse(String line) {
      if (line.isEmpty()) {
        return null;
      }
      int firstNonWhiteSpaceChar = firstNonWhiteSpaceCharacterFromIndex(line, 0);
      String description = "";
      if (line.startsWith(CAUSED_BY, firstNonWhiteSpaceChar)) {
        description = CAUSED_BY;
      } else if (line.startsWith(SUPPRESSED, firstNonWhiteSpaceChar)) {
        description = SUPPRESSED;
      }
      int exceptionStartIndex = firstNonWhiteSpaceChar + description.length();
      int messageStartIndex = firstCharFromIndex(line, exceptionStartIndex, ':');
      String className = line.substring(exceptionStartIndex, messageStartIndex);
      if (!DescriptorUtils.isValidJavaType(className)) {
        return null;
      }
      return StackTraceElementStringProxy.builder(line)
          .registerClassName(exceptionStartIndex, messageStartIndex, ClassNameType.TYPENAME)
          .build();
    }
  }

  /**
   * Captures a stack trace line on the following form
   *
   * <ul>
   *   <li>at dalvik.system.NativeStart.main(NativeStart.java:99)
   *   <li>at dalvik.system.NativeStart.main(:99)
   *   <li>at dalvik.system.NativeStart.main(Foo.java:)
   *   <li>at dalvik.system.NativeStart.main(Native Method)
   *   <li>at classloader/named_module@version/foo.bar.baz(:20)
   *   <li>at classloader//foo.bar.baz(:20)
   * </ul>
   *
   * <p>Empirical evidence suggests that the "at" string is never localized.
   */
  private static class AtLine {

    private static StackTraceElementStringProxy tryParse(String line) {
      // Check that the line is indented with some amount of white space.
      if (line.length() == 0 || !Character.isWhitespace(line.charAt(0))) {
        return null;
      }
      // Find the first non-white space character and check that we have the sequence 'a', 't', ' '.
      int firstNonWhiteSpace = firstNonWhiteSpaceCharacterFromIndex(line, 0);
      if (firstNonWhiteSpace + 2 >= line.length()
          || line.charAt(firstNonWhiteSpace) != 'a'
          || line.charAt(firstNonWhiteSpace + 1) != 't'
          || line.charAt(firstNonWhiteSpace + 2) != ' ') {
        return null;
      }
      int classClassLoaderOrModuleStartIndex =
          firstNonWhiteSpaceCharacterFromIndex(line, firstNonWhiteSpace + 2);
      if (classClassLoaderOrModuleStartIndex >= line.length()
          || classClassLoaderOrModuleStartIndex != firstNonWhiteSpace + 3) {
        return null;
      }
      int parensStart = firstCharFromIndex(line, classClassLoaderOrModuleStartIndex, '(');
      if (parensStart >= line.length()) {
        return null;
      }
      int parensEnd = line.lastIndexOf(')');
      if (parensEnd <= parensStart) {
        return null;
      }
      if (firstNonWhiteSpaceCharacterFromIndex(line, parensEnd) == line.length()) {
        return null;
      }
      int methodSeparator = line.lastIndexOf('.', parensStart);
      if (methodSeparator <= classClassLoaderOrModuleStartIndex) {
        return null;
      }
      int classStartIndex = classClassLoaderOrModuleStartIndex;
      int classLoaderOrModuleEndIndex =
          firstCharFromIndex(line, classClassLoaderOrModuleStartIndex, '/');
      if (classLoaderOrModuleEndIndex < methodSeparator) {
        int moduleEndIndex = firstCharFromIndex(line, classLoaderOrModuleEndIndex + 1, '/');
        if (moduleEndIndex < methodSeparator) {
          // The stack trace contains both a class loader and module
          classStartIndex = moduleEndIndex + 1;
        } else {
          classStartIndex = classLoaderOrModuleEndIndex + 1;
        }
      }
      StackTraceElementStringProxyBuilder builder =
          StackTraceElementStringProxy.builder(line)
              .registerClassName(classStartIndex, methodSeparator, ClassNameType.TYPENAME)
              .registerMethodName(methodSeparator + 1, parensStart);
      // Check if we have a filename and position.
      int separatorIndex = line.lastIndexOf(':', parensEnd);
      if (separatorIndex > -1 && separatorIndex < parensEnd) {
        builder.registerSourceFile(parensStart + 1, separatorIndex);
        builder.registerLineNumber(separatorIndex + 1, parensEnd);
      } else {
        builder.registerSourceFile(parensStart + 1, parensEnd);
      }
      return builder.build();
    }
  }

  static class CircularReferenceLine {

    private static final String CIRCULAR_REFERENCE = "[CIRCULAR REFERENCE:";

    static StackTraceElementStringProxy tryParse(String line) {
      // Check that the line is indented with some amount of white space.
      if (line.length() == 0 || !Character.isWhitespace(line.charAt(0))) {
        return null;
      }
      // Find the first non-white space character and check that we have the sequence
      // '[CIRCULAR REFERENCE:Exception]'.
      int firstNonWhiteSpace = firstNonWhiteSpaceCharacterFromIndex(line, 0);
      if (!line.startsWith(CIRCULAR_REFERENCE, firstNonWhiteSpace)) {
        return null;
      }
      int exceptionStartIndex = firstNonWhiteSpace + CIRCULAR_REFERENCE.length();
      int lastBracketPosition = firstCharFromIndex(line, exceptionStartIndex, ']');
      if (lastBracketPosition == line.length()) {
        return null;
      }
      int onlyWhitespaceFromLastBracket =
          firstNonWhiteSpaceCharacterFromIndex(line, lastBracketPosition + 1);
      if (onlyWhitespaceFromLastBracket != line.length()) {
        return null;
      }
      return StackTraceElementStringProxy.builder(line)
          .registerClassName(exceptionStartIndex, lastBracketPosition, ClassNameType.TYPENAME)
          .build();
    }
  }

  private StackTraceElementStringProxy parseLine(String line) {
    // Most lines are 'at lines' so attempt to parse it first.
    StackTraceElementStringProxy parsedLine = AtLine.tryParse(line);
    if (parsedLine != null) {
      return parsedLine;
    }
    parsedLine = ExceptionLine.tryParse(line);
    if (parsedLine != null) {
      return parsedLine;
    }
    parsedLine = CircularReferenceLine.tryParse(line);
    if (parsedLine != null) {
      return parsedLine;
    }
    return StackTraceElementStringProxy.builder(line).build();
  }
}
