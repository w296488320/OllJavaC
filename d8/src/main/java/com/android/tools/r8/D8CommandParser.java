// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.origin.PathOrigin;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.ExceptionDiagnostic;
import com.android.tools.r8.utils.FlagFile;
import com.android.tools.r8.utils.StringDiagnostic;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

public class D8CommandParser extends BaseCompilerCommandParser<D8Command, D8Command.Builder> {

  private static final Set<String> OPTIONS_WITH_PARAMETER =
      ImmutableSet.of(
          "--output",
          "--lib",
          "--classpath",
          "--pg-map",
          MIN_API_FLAG,
          "--main-dex-rules",
          "--main-dex-list",
          "--main-dex-list-output",
          "--desugared-lib",
          THREAD_COUNT_FLAG);

  private static final String APK_EXTENSION = ".apk";
  private static final String JAR_EXTENSION = ".jar";
  private static final String ZIP_EXTENSION = ".zip";

  private static boolean isArchive(Path path) {
    String name = path.getFileName().toString().toLowerCase();
    return name.endsWith(APK_EXTENSION)
        || name.endsWith(JAR_EXTENSION)
        || name.endsWith(ZIP_EXTENSION);
  }

  static class OrderedClassFileResourceProvider implements ClassFileResourceProvider {
    static class Builder {
      private final ImmutableList.Builder<ClassFileResourceProvider> builder =
          ImmutableList.builder();
      boolean empty = true;

      OrderedClassFileResourceProvider build() {
        return new OrderedClassFileResourceProvider(builder.build());
      }

      Builder addClassFileResourceProvider(ClassFileResourceProvider provider) {
        builder.add(provider);
        empty = false;
        return this;
      }

      boolean isEmpty() {
        return empty;
      }
    }

    final List<ClassFileResourceProvider> providers;
    final Set<String> descriptors = Sets.newHashSet();

    private OrderedClassFileResourceProvider(ImmutableList<ClassFileResourceProvider> providers) {
      this.providers = providers;
      // Collect all descriptors that can be provided.
      this.providers.forEach(provider -> this.descriptors.addAll(provider.getClassDescriptors()));
    }

    static Builder builder() {
      return new Builder();
    }

    @Override
    public Set<String> getClassDescriptors() {
      return descriptors;
    }

    @Override
    public ProgramResource getProgramResource(String descriptor) {
      // Search the providers in order. Return the program resource from the first provider that
      // can provide it.
      for (ClassFileResourceProvider provider : providers) {
        if (provider.getClassDescriptors().contains(descriptor)) {
          return provider.getProgramResource(descriptor);
        }
      }
      return null;
    }
  }

  public static void main(String[] args) throws CompilationFailedException {
    D8Command command = parse(args, Origin.root()).build();
    if (command.isPrintHelp()) {
      System.out.println(USAGE_MESSAGE);
    } else {
      D8.run(command);
    }
  }

  static final String USAGE_MESSAGE =
      String.join(
          "\n",
          Iterables.concat(
              Arrays.asList(
                  "Usage: d8 [options] [@<argfile>] <input-files>",
                  " where <input-files> are any combination of dex, class, zip, jar, or apk files",
                  " and each <argfile> is a file containing additional arguments (one per line)",
                  " and options are:",
                  "  --debug                 # Compile with debugging information (default).",
                  "  --release               # Compile without debugging information.",
                  "  --output <file>         # Output result in <outfile>.",
                  "                          # <file> must be an existing directory or a zip file.",
                  "  --lib <file|jdk-home>   # Add <file|jdk-home> as a library resource.",
                  "  --classpath <file>      # Add <file> as a classpath resource.",
                  "  "
                      + MIN_API_FLAG
                      + " <number>      "
                      + "# Minimum Android API level compatibility, default: "
                      + AndroidApiLevel.getDefault().getLevel()
                      + ".",
                  "  --pg-map <file>         # Use <file> as a mapping file for distribution.",
                  "  --intermediate          # Compile an intermediate result intended for later",
                  "                          # merging.",
                  "  --file-per-class        # Produce a separate dex file per class.",
                  "                          # Synthetic classes are in their own file.",
                  "  --file-per-class-file   # Produce a separate dex file per input .class file.",
                  "                          # Synthetic classes are with their originating class.",
                  "  --no-desugaring         # Force disable desugaring.",
                  "  --desugared-lib <file>  # Specify desugared library configuration.",
                  "                          # <file> is a desugared library configuration (json).",
                  "  --main-dex-rules <file> # Proguard keep rules for classes to place in the",
                  "                          # primary dex file.",
                  "  --main-dex-list <file>  # List of classes to place in the primary dex file.",
                  "  --main-dex-list-output <file>",
                  "                          # Output resulting main dex list in <file>."),
              ASSERTIONS_USAGE_MESSAGE,
              THREAD_COUNT_USAGE_MESSAGE,
              MAP_DIAGNOSTICS_USAGE_MESSAGE,
              Arrays.asList(
                  "  --version               # Print the version of d8.",
                  "  --help                  # Print this message.")));
  /**
   * Parse the D8 command-line.
   *
   * <p>Parsing will set the supplied options or their default value if they have any.
   *
   * @param args Command-line arguments array.
   * @param origin Origin description of the command-line arguments.
   * @return D8 command builder with state set up according to parsed command line.
   */
  public static D8Command.Builder parse(String[] args, Origin origin) {
    return new D8CommandParser().parse(args, origin, D8Command.builder());
  }

  /**
   * Parse the D8 command-line.
   *
   * <p>Parsing will set the supplied options or their default value if they have any.
   *
   * @param args Command-line arguments array.
   * @param origin Origin description of the command-line arguments.
   * @param handler Custom defined diagnostics handler.
   * @return D8 command builder with state set up according to parsed command line.
   */
  public static D8Command.Builder parse(String[] args, Origin origin, DiagnosticsHandler handler) {
    return new D8CommandParser().parse(args, origin, D8Command.builder(handler));
  }

  private D8Command.Builder parse(String[] args, Origin origin, D8Command.Builder builder) {
    CompilationMode compilationMode = null;
    Path outputPath = null;
    OutputMode outputMode = null;
    boolean hasDefinedApiLevel = false;
    OrderedClassFileResourceProvider.Builder classpathBuilder =
        OrderedClassFileResourceProvider.builder();
    String[] expandedArgs = FlagFile.expandFlagFiles(args, builder::error);
    for (int i = 0; i < expandedArgs.length; i++) {
      String arg = expandedArgs[i].trim();
      String nextArg = null;
      if (OPTIONS_WITH_PARAMETER.contains(arg)) {
        if (++i < expandedArgs.length) {
          nextArg = expandedArgs[i];
        } else {
          builder.error(
              new StringDiagnostic("Missing parameter for " + expandedArgs[i - 1] + ".", origin));
          break;
        }
      }
      if (arg.length() == 0) {
        continue;
      } else if (arg.equals("--help")) {
        builder.setPrintHelp(true);
      } else if (arg.equals("--version")) {
        builder.setPrintVersion(true);
      } else if (arg.equals("--debug")) {
        if (compilationMode == CompilationMode.RELEASE) {
          builder.error(
              new StringDiagnostic("Cannot compile in both --debug and --release mode.", origin));
          continue;
        }
        compilationMode = CompilationMode.DEBUG;
      } else if (arg.equals("--release")) {
        if (compilationMode == CompilationMode.DEBUG) {
          builder.error(
              new StringDiagnostic("Cannot compile in both --debug and --release mode.", origin));
          continue;
        }
        compilationMode = CompilationMode.RELEASE;
      } else if (arg.equals("--file-per-class")) {
        outputMode = OutputMode.DexFilePerClass;
      } else if (arg.equals("--file-per-class-file")) {
        outputMode = OutputMode.DexFilePerClassFile;
      } else if (arg.equals("--classfile")) {
        outputMode = OutputMode.ClassFile;
      } else if (arg.equals("--pg-map")) {
        builder.setProguardInputMapFile(Paths.get(nextArg));
      } else if (arg.equals("--output")) {
        if (outputPath != null) {
          builder.error(
              new StringDiagnostic(
                  "Cannot output both to '" + outputPath.toString() + "' and '" + nextArg + "'",
                  origin));
          continue;
        }
        outputPath = Paths.get(nextArg);
      } else if (arg.equals("--lib")) {
        addLibraryArgument(builder, origin, nextArg);
      } else if (arg.equals("--classpath")) {
        Path file = Paths.get(nextArg);
        try {
          if (!Files.exists(file)) {
            throw new NoSuchFileException(file.toString());
          }
          if (isArchive(file)) {
            classpathBuilder.addClassFileResourceProvider(new ArchiveClassFileProvider(file));
          } else if (Files.isDirectory(file)) {
            classpathBuilder.addClassFileResourceProvider(
                DirectoryClassFileProvider.fromDirectory(file));
          } else {
            builder.error(
                new StringDiagnostic("Unsupported classpath file type", new PathOrigin(file)));
          }
        } catch (IOException e) {
          builder.error(new ExceptionDiagnostic(e, new PathOrigin(file)));
        }
      } else if (arg.equals("--main-dex-rules")) {
        builder.addMainDexRulesFiles(Paths.get(nextArg));
      } else if (arg.equals("--main-dex-list")) {
        builder.addMainDexListFiles(Paths.get(nextArg));
      } else if (arg.equals("--main-dex-list-output")) {
        builder.setMainDexListOutputPath(Paths.get(nextArg));
      } else if (arg.equals("--optimize-multidex-for-linearalloc")) {
        builder.setOptimizeMultidexForLinearAlloc(true);
      } else if (arg.equals(MIN_API_FLAG)) {
        if (hasDefinedApiLevel) {
          builder.error(
              new StringDiagnostic("Cannot set multiple " + MIN_API_FLAG + " options", origin));
        } else {
          parsePositiveIntArgument(
              builder::error, MIN_API_FLAG, nextArg, origin, builder::setMinApiLevel);
          hasDefinedApiLevel = true;
        }
      } else if (arg.equals(THREAD_COUNT_FLAG)) {
        parsePositiveIntArgument(
            builder::error, THREAD_COUNT_FLAG, nextArg, origin, builder::setThreadCount);
      } else if (arg.equals("--intermediate")) {
        builder.setIntermediate(true);
      } else if (arg.equals("--no-desugaring")) {
        builder.setDisableDesugaring(true);
      } else if (arg.equals("--desugared-lib")) {
        builder.addDesugaredLibraryConfiguration(StringResource.fromFile(Paths.get(nextArg)));
      } else if (arg.startsWith("--")) {
        if (tryParseAssertionArgument(builder, arg, origin)) {
          continue;
        }
        int argsConsumed = tryParseMapDiagnostics(builder, arg, expandedArgs, i, origin);
        if (argsConsumed >= 0) {
          i += argsConsumed;
          continue;
        }
        builder.error(new StringDiagnostic("Unknown option: " + arg, origin));
      } else if (arg.startsWith("@")) {
        builder.error(new StringDiagnostic("Recursive @argfiles are not supported: ", origin));
      } else {
        builder.addProgramFiles(Paths.get(arg));
      }
    }
    if (!classpathBuilder.isEmpty()) {
      builder.addClasspathResourceProvider(classpathBuilder.build());
    }
    if (compilationMode != null) {
      builder.setMode(compilationMode);
    }
    if (outputMode == null) {
      outputMode = OutputMode.DexIndexed;
    }
    if (outputPath == null) {
      outputPath = Paths.get(".");
    }
    return builder.setOutput(outputPath, outputMode);
  }
}
