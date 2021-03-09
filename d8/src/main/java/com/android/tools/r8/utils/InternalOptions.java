// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;


import com.android.tools.r8.ClassFileConsumer;
import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.DataResourceConsumer;
import com.android.tools.r8.DesugarGraphConsumer;
import com.android.tools.r8.DexFilePerClassFileConsumer;
import com.android.tools.r8.DexIndexedConsumer;
import com.android.tools.r8.DumpOptions;
import com.android.tools.r8.FeatureSplit;
import com.android.tools.r8.ProgramConsumer;
import com.android.tools.r8.StringConsumer;
import com.android.tools.r8.Version;
import com.android.tools.r8.cf.CfVersion;
import com.android.tools.r8.dex.Marker;
import com.android.tools.r8.dex.Marker.Backend;
import com.android.tools.r8.dex.Marker.Tool;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.ExperimentalClassFileVersionDiagnostic;
import com.android.tools.r8.errors.IncompleteNestNestDesugarDiagnosic;
import com.android.tools.r8.errors.InterfaceDesugarMissingTypeDiagnostic;
import com.android.tools.r8.errors.InvalidDebugInfoException;
import com.android.tools.r8.errors.InvalidLibrarySuperclassDiagnostic;
import com.android.tools.r8.errors.MissingNestHostNestDesugarDiagnostic;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.features.FeatureSplitConfiguration;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexClasspathClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexLibraryClass;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.classmerging.VerticallyMergedClasses;
import com.android.tools.r8.horizontalclassmerging.HorizontallyMergedClasses;
import com.android.tools.r8.inspector.internal.InspectorImpl;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.desugar.DesugaredLibraryConfiguration;
import com.android.tools.r8.ir.desugar.nest.Nest;
import com.android.tools.r8.ir.optimize.Inliner;
import com.android.tools.r8.ir.optimize.enums.EnumDataMap;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import com.android.tools.r8.references.Reference;
import com.android.tools.r8.repackaging.Repackaging.DefaultRepackagingConfiguration;
import com.android.tools.r8.repackaging.Repackaging.RepackagingConfiguration;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.GlobalKeepInfoConfiguration;
import com.android.tools.r8.shaking.ProguardConfiguration;
import com.android.tools.r8.shaking.ProguardConfigurationRule;
import com.android.tools.r8.utils.IROrdering.IdentityIROrdering;
import com.android.tools.r8.utils.IROrdering.NondeterministicIROrdering;
import com.android.tools.r8.utils.collections.DexClassAndMethodSet;
import com.android.tools.r8.utils.collections.SortedProgramMethodSet;
import com.android.tools.r8.utils.structural.Ordered;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Equivalence.Wrapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.BiConsumer;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import org.objectweb.asm.Opcodes;

public class InternalOptions implements GlobalKeepInfoConfiguration {

  // Set to true to run compilation in a single thread and without randomly shuffling the input.
  // This makes life easier when running R8 in a debugger.
  public static final boolean DETERMINISTIC_DEBUGGING = false;

  public enum LineNumberOptimization {
    OFF,
    ON
  }

  public enum DesugarState {
    OFF,
    ON;

    public boolean isOff() {
      return this == OFF;
    }

    public boolean isOn() {
      return this == ON;
    }
  }

  public static final CfVersion SUPPORTED_CF_VERSION = CfVersion.V15;
  public static final CfVersion EXPERIMENTAL_CF_VERSION = CfVersion.V12;

  public static final int SUPPORTED_DEX_VERSION =
      AndroidApiLevel.LATEST.getDexVersion().getIntValue();

  public static final int ASM_VERSION = Opcodes.ASM9;

  public final DexItemFactory itemFactory;

  public DexItemFactory dexItemFactory() {
    return itemFactory;
  }

  public boolean hasProguardConfiguration() {
    return proguardConfiguration != null;
  }

  public ProguardConfiguration getProguardConfiguration() {
    return proguardConfiguration;
  }

  private final ProguardConfiguration proguardConfiguration;
  public final Reporter reporter;

  // TODO(zerny): Make this private-final once we have full program-consumer support.
  public ProgramConsumer programConsumer = null;

  public DataResourceConsumer dataResourceConsumer;
  public FeatureSplitConfiguration featureSplitConfiguration;

  public List<Consumer<InspectorImpl>> outputInspections = Collections.emptyList();

  // Constructor for testing and/or other utilities.
  public InternalOptions() {
    reporter = new Reporter();
    itemFactory = new DexItemFactory();
    proguardConfiguration = null;
    enableTreeShaking = false;
    enableMinification = false;
  }

  // Constructor for D8.
  public InternalOptions(DexItemFactory factory, Reporter reporter) {
    assert reporter != null;
    assert factory != null;
    this.reporter = reporter;
    itemFactory = factory;
    proguardConfiguration = null;
    enableTreeShaking = false;
    enableMinification = false;
    disableGlobalOptimizations();
  }

  // Constructor for R8.
  public InternalOptions(ProguardConfiguration proguardConfiguration, Reporter reporter) {
    assert reporter != null;
    assert proguardConfiguration != null;
    this.reporter = reporter;
    this.proguardConfiguration = proguardConfiguration;
    itemFactory = proguardConfiguration.getDexItemFactory();
    enableTreeShaking = proguardConfiguration.isShrinking();
    enableMinification = proguardConfiguration.isObfuscating();
    // TODO(b/171457102): Avoid the need for this.
    // -dontoptimize disables optimizations by flipping related flags.
    if (!proguardConfiguration.isOptimizing()) {
      disableAllOptimizations();
    }
    configurationDebugging = proguardConfiguration.isConfigurationDebugging();
    if (proguardConfiguration.isProtoShrinkingEnabled()) {
      enableProtoShrinking();
    }
  }

  void enableProtoShrinking() {
    applyInliningToInlinee = true;
    enableFieldBitAccessAnalysis = true;
    protoShrinking.enableGeneratedMessageLiteShrinking = true;
    protoShrinking.enableGeneratedMessageLiteBuilderShrinking = true;
    protoShrinking.enableGeneratedExtensionRegistryShrinking = true;
    protoShrinking.enableEnumLiteProtoShrinking = true;
  }

  void disableAllOptimizations() {
    disableGlobalOptimizations();
    enableNameReflectionOptimization = false;
    enableStringConcatenationOptimization = false;
  }

  public void disableGlobalOptimizations() {
    enableArgumentRemoval = false;
    enableInlining = false;
    enableClassInlining = false;
    enableClassStaticizer = false;
    enableDevirtualization = false;
    horizontalClassMergerOptions.disable();
    enableVerticalClassMerging = false;
    enableEnumUnboxing = false;
    enableUninstantiatedTypeOptimization = false;
    outline.enabled = false;
    enableEnumValueOptimization = false;
    enableValuePropagation = false;
    enableSideEffectAnalysis = false;
    enableTreeShakingOfLibraryMethodOverrides = false;
    callSiteOptimizationOptions.disableOptimization();
  }

  public boolean printTimes = System.getProperty("com.android.tools.r8.printtimes") != null;
  // To print memory one also have to enable printtimes.
  public boolean printMemory = System.getProperty("com.android.tools.r8.printmemory") != null;

  public String dumpInputToFile = System.getProperty("com.android.tools.r8.dumpinputtofile");
  public String dumpInputToDirectory =
      System.getProperty("com.android.tools.r8.dumpinputtodirectory");

  // Flag to toggle if DEX code objects should pass-through without IR processing.
  public boolean passthroughDexCode = false;

  // Flag to toggle if the prefix based merge restriction should be enforced.
  public boolean enableNeverMergePrefixes = true;
  public Set<String> neverMergePrefixes = ImmutableSet.of("j$.");

  public boolean classpathInterfacesMayHaveStaticInitialization = false;
  public boolean libraryInterfacesMayHaveStaticInitialization = false;

  // Optimization-related flags. These should conform to -dontoptimize and disableAllOptimizations.
  public boolean enableFieldAssignmentTracker = true;
  public boolean enableFieldBitAccessAnalysis =
      System.getProperty("com.android.tools.r8.fieldBitAccessAnalysis") != null;
  public boolean enableVerticalClassMerging = true;
  public boolean enableArgumentRemoval = true;
  public boolean enableUnusedInterfaceRemoval = true;
  public boolean enableDevirtualization = true;
  public boolean enableInlining =
      !Version.isDevelopmentVersion()
          || System.getProperty("com.android.tools.r8.disableinlining") == null;
  public boolean enableEnumUnboxing = true;
  // TODO(b/141451716): Evaluate the effect of allowing inlining in the inlinee.
  public boolean applyInliningToInlinee =
      System.getProperty("com.android.tools.r8.applyInliningToInlinee") != null;
  public int applyInliningToInlineeMaxDepth = 0;
  public boolean enableInliningOfInvokesWithClassInitializationSideEffects = true;
  public boolean enableInliningOfInvokesWithNullableReceivers = true;
  public boolean disableInliningOfLibraryMethodOverrides = true;
  public boolean enableSimpleInliningConstraints = true;
  public int simpleInliningConstraintThreshold = 0;
  public boolean enableClassInlining = true;
  public boolean enableClassStaticizer = true;
  public boolean enableInitializedClassesAnalysis = true;
  public boolean enableSideEffectAnalysis = true;
  public boolean enableDeterminismAnalysis = true;
  public boolean enableServiceLoaderRewriting = true;
  public boolean enableNameReflectionOptimization = true;
  public boolean enableStringConcatenationOptimization = true;
  public boolean enableTreeShakingOfLibraryMethodOverrides = false;
  public boolean encodeChecksums = false;
  public BiPredicate<String, Long> dexClassChecksumFilter = (name, checksum) -> true;
  public boolean cfToCfDesugar = false;
  // TODO(b/172496438): Temporarily enable publicizing package-private overrides.
  public boolean enablePackagePrivateAwarePublicization = false;

  public int callGraphLikelySpuriousCallEdgeThreshold = 50;

  public int verificationSizeLimitInBytes() {
    if (testing.verificationSizeLimitInBytesOverride > -1) {
      return testing.verificationSizeLimitInBytesOverride;
    }
    // For CF we use the defined limit in the spec. For DEX we use the limit of the static verifier
    // https://android.googlesource.com/platform/art/+/android10-release/compiler/compiler.cc#48
    return isGeneratingClassFiles() ? 65534 : 16383;
  }

  public int minimumVerificationSizeLimitInBytes() {
    if (testing.verificationSizeLimitInBytesOverride > -1) {
      return testing.verificationSizeLimitInBytesOverride;
    }
    return 16383;
  }

  // TODO(b/141719453): The inlining limit at least should be consistent with normal inlining.
  public int classInliningInstructionLimit = 10;
  public int classInliningInstructionAllowance = 50;
  // This defines the limit of instructions in the inlinee
  public int inliningInstructionLimit = 3;
  // This defines how many instructions of inlinees we can inlinee overall.
  public int inliningInstructionAllowance = 1500;
  // Maximum number of distinct values in a method that may be used in a monitor-enter instruction.
  public int inliningMonitorEnterValuesAllowance = 4;
  // Maximum number of control flow resolution blocks that setup the register state before
  // the actual catch handler allowed when inlining. Threshold found empirically by testing on
  // GMS Core.
  public int inliningControlFlowResolutionBlocksThreshold = 15;
  public boolean enableSwitchRewriting = true;
  public boolean enableStringSwitchConversion = true;
  public int minimumStringSwitchSize = 3;
  public boolean enableEnumValueOptimization = true;
  public boolean enableEnumSwitchMapRemoval = true;
  public final OutlineOptions outline = new OutlineOptions();
  public boolean enableInitializedClassesInInstanceMethodsAnalysis = true;
  public boolean enableRedundantFieldLoadElimination = true;
  public boolean enableValuePropagation = true;
  public boolean enableValuePropagationForInstanceFields = true;
  public boolean enableUninstantiatedTypeOptimization = true;
  // Currently disabled, see b/146957343.
  public boolean enableUninstantiatedTypeOptimizationForInterfaces = false;
  // TODO(b/138917494): Disable until we have numbers on potential performance penalties.
  public boolean enableRedundantConstNumberOptimization = false;

  public boolean enablePcDebugInfoOutput = false;

  public String synthesizedClassPrefix = "";

  // Number of threads to use while processing the dex files.
  public int threadCount = DETERMINISTIC_DEBUGGING ? 1 : ThreadUtils.NOT_SPECIFIED;
  // Print smali disassembly.
  public boolean useSmaliSyntax = false;
  // Verbose output.
  public boolean verbose = false;
  // Silencing output.
  public boolean quiet = false;
  // Throw exception if there is a warning about invalid debug info.
  public boolean invalidDebugInfoFatal = false;
  // Don't gracefully recover from invalid debug info.
  public boolean invalidDebugInfoStrict =
      System.getProperty("com.android.tools.r8.strictdebuginfo") != null;

  // When dexsplitting we ignore main dex classes missing in the application. These will be
  // fused together by play store when shipped for pre-L devices.
  public boolean ignoreMainDexMissingClasses = false;

  // Boolean value indicating that byte code pass through may be enabled.
  public boolean enableCfByteCodePassThrough = false;

  // Contain the contents of the build properties file from the compiler command.
  public DumpOptions dumpOptions;

  // Hidden marker for classes.dex
  private boolean hasMarker = false;
  private Marker marker;

  public void setMarker(Marker marker) {
    this.hasMarker = true;
    this.marker = marker;
  }

  public Marker getMarker(Tool tool) {
    if (hasMarker) {
      return marker;
    }
    return createMarker(tool);
  }

  // Compute the marker to be placed in the main dex file.
  private Marker createMarker(Tool tool) {
    if (tool == Tool.D8 && testing.dontCreateMarkerInD8) {
      return null;
    }
    Marker marker =
        new Marker(tool)
            .setVersion(Version.LABEL)
            .setCompilationMode(debug ? CompilationMode.DEBUG : CompilationMode.RELEASE)
            .setBackend(isGeneratingClassFiles() ? Backend.CF : Backend.DEX)
            .setHasChecksums(encodeChecksums);
    // The marker records the min API if any desugaring happens or if the compiler generates dex
    // since the output depends on the min API in this case. There is basically no min API entry
    // in R8 cf to cf.
    if (isGeneratingDex() || desugarState == DesugarState.ON) {
      marker.setMinApi(minApiLevel);
    }
    if (desugaredLibraryConfiguration.getIdentifier() != null) {
      marker.setDesugaredLibraryIdentifiers(desugaredLibraryConfiguration.getIdentifier());
    }
    if (Version.isDevelopmentVersion()) {
      marker.setSha1(VersionProperties.INSTANCE.getSha());
    }
    if (tool == Tool.R8) {
      marker.setR8Mode(forceProguardCompatibility ? "compatibility" : "full");
    }
    return marker;
  }

  public boolean hasConsumer() {
    return programConsumer != null;
  }

  public InternalOutputMode getInternalOutputMode() {
    assert hasConsumer();
    if (isGeneratingDexIndexed()) {
      return InternalOutputMode.DexIndexed;
    } else if (isGeneratingDexFilePerClassFile()) {
      return InternalOutputMode.DexFilePerClassFile;
    } else if (isGeneratingClassFiles()) {
      return InternalOutputMode.ClassFile;
    }
    throw new UnsupportedOperationException("Cannot find internal output mode.");
  }

  public boolean isDesugaredLibraryCompilation() {
    return desugaredLibraryConfiguration.isLibraryCompilation();
  }

  public boolean isRelocatorCompilation() {
    return relocatorCompilation;
  }

  public boolean shouldBackportMethods() {
    return !hasConsumer() || isGeneratingDex() || cfToCfDesugar;
  }

  public boolean shouldKeepStackMapTable() {
    assert cfToCfDesugar || isRelocatorCompilation() || getProguardConfiguration() != null;
    return cfToCfDesugar
        || isRelocatorCompilation()
        || getProguardConfiguration().getKeepAttributes().stackMapTable;
  }

  public boolean shouldRerunEnqueuer() {
    return isShrinking() || isMinifying() || getProguardConfiguration().hasApplyMappingFile();
  }

  public boolean isGeneratingDex() {
    return isGeneratingDexIndexed() || isGeneratingDexFilePerClassFile();
  }

  public boolean isGeneratingDexIndexed() {
    return programConsumer instanceof DexIndexedConsumer;
  }

  public boolean isGeneratingDexFilePerClassFile() {
    return programConsumer instanceof DexFilePerClassFileConsumer;
  }

  public boolean isGeneratingClassFiles() {
    return programConsumer instanceof ClassFileConsumer;
  }

  public boolean isDesugaring() {
    return !isGeneratingClassFiles() || cfToCfDesugar;
  }

  public DexIndexedConsumer getDexIndexedConsumer() {
    return (DexIndexedConsumer) programConsumer;
  }

  public DexFilePerClassFileConsumer getDexFilePerClassFileConsumer() {
    return (DexFilePerClassFileConsumer) programConsumer;
  }

  public ClassFileConsumer getClassFileConsumer() {
    return (ClassFileConsumer) programConsumer;
  }

  public void signalFinishedToConsumers() {
    if (programConsumer != null) {
      programConsumer.finished(reporter);
      if (dataResourceConsumer != null) {
        dataResourceConsumer.finished(reporter);
      }
    }
    if (featureSplitConfiguration != null) {
      for (FeatureSplit featureSplit : featureSplitConfiguration.getFeatureSplits()) {
        ProgramConsumer programConsumer = featureSplit.getProgramConsumer();
        programConsumer.finished(reporter);
        DataResourceConsumer dataResourceConsumer = programConsumer.getDataResourceConsumer();
        if (dataResourceConsumer != null) {
          dataResourceConsumer.finished(reporter);
        }
      }
    }
    if (desugarGraphConsumer != null) {
      desugarGraphConsumer.finished();
    }
  }

  public boolean shouldDesugarNests() {
    return !canUseNestBasedAccess();
  }

  public boolean canUseRecords() {
    // TODO(b/169645628): Replace by true when records are supported.
    return testing.canUseRecords;
  }

  public Set<String> extensiveLoggingFilter = getExtensiveLoggingFilter();
  public Set<String> extensiveInterfaceMethodMinifierLoggingFilter =
      getExtensiveInterfaceMethodMinifierLoggingFilter();

  public List<String> methodsFilter = ImmutableList.of();
  public int minApiLevel = AndroidApiLevel.getDefault().getLevel();
  // Skipping min_api check and compiling an intermediate result intended for later merging.
  // Intermediate builds also emits or update synthesized classes mapping.
  public boolean intermediate = false;
  public boolean readCompileTimeAnnotations = true;
  public List<String> logArgumentsFilter = ImmutableList.of();

  // Flag to turn on/offLoad/store optimization in the Cf back-end.
  public boolean enableLoadStoreOptimization = true;
  // Flag to turn on/off desugaring in D8/R8.
  public DesugarState desugarState = DesugarState.ON;
  // Flag to turn on/off reduction of nest to improve class merging optimizations.
  public boolean enableNestReduction = true;
  // Defines interface method rewriter behavior.
  public OffOrAuto interfaceMethodDesugaring = OffOrAuto.Auto;
  // Defines try-with-resources rewriter behavior.
  public OffOrAuto tryWithResourcesDesugaring = OffOrAuto.Auto;
  // Flag to turn on/off processing of @dalvik.annotation.codegen.CovariantReturnType and
  // @dalvik.annotation.codegen.CovariantReturnType$CovariantReturnTypes.
  public boolean processCovariantReturnTypeAnnotations = true;
  // Flag to control library/program class lookup order.
  // TODO(120884788): Enable this flag as the default.
  public boolean lookupLibraryBeforeProgram = false;
  // TODO(120884788): Leave this system property as a stop-gap for some time.
  // public boolean lookupLibraryBeforeProgram =
  //     System.getProperty("com.android.tools.r8.lookupProgramBeforeLibrary") == null;

  // Whether or not to check for valid multi-dex builds.
  //
  // For min-api levels that did not support native multi-dex the user should provide a main dex
  // list. However, DX, didn't check that this was the case. Therefore, for CompatDX we have a flag
  // to disable the check that the build makes sense for multi-dexing.
  public boolean enableMainDexListCheck = true;

  private final boolean enableTreeShaking;
  private final boolean enableMinification;

  public boolean isRelease() {
    return !debug;
  }

  public boolean isShrinking() {
    assert proguardConfiguration == null
        || enableTreeShaking == proguardConfiguration.isShrinking();
    return enableTreeShaking;
  }

  public boolean isMinifying() {
    assert proguardConfiguration == null
        || enableMinification == proguardConfiguration.isObfuscating();
    return enableMinification;
  }

  @Override
  public boolean isTreeShakingEnabled() {
    return isShrinking();
  }

  @Override
  public boolean isMinificationEnabled() {
    return isMinifying();
  }

  @Override
  public boolean isRepackagingEnabled() {
    return proguardConfiguration.getPackageObfuscationMode().isSome()
        && (isShrinking() || isMinifying());
  }

  @Override
  public boolean isForceProguardCompatibilityEnabled() {
    return forceProguardCompatibility;
  }

  @Override
  public boolean isKeepAttributesSignatureEnabled() {
    return proguardConfiguration.getKeepAttributes().signature;
  }

  /**
   * If any non-static class merging is enabled, information about types referred to by instanceOf
   * and check cast instructions needs to be collected.
   */
  public boolean isClassMergingExtensionRequired() {
    return horizontalClassMergerOptions.isEnabled() || enableVerticalClassMerging;
  }

  @Override
  public boolean isAccessModificationEnabled() {
    return getProguardConfiguration() != null
        && getProguardConfiguration().isAccessModificationAllowed();
  }

  public boolean keepInnerClassStructure() {
    return getProguardConfiguration().getKeepAttributes().signature
        || getProguardConfiguration().getKeepAttributes().innerClasses;
  }

  public boolean canUseInputStackMaps() {
    return testing.readInputStackMaps ? testing.readInputStackMaps : isGeneratingClassFiles();
  }

  public boolean printCfg = false;
  public String printCfgFile;
  public boolean ignoreMissingClasses = false;
  public boolean reportMissingClassesInEnclosingMethodAttribute = false;
  public boolean reportMissingClassesInInnerClassAttributes = false;

  // EXPERIMENTAL flag to get behaviour as close to Proguard as possible.
  public boolean forceProguardCompatibility = false;
  public AssertionConfigurationWithDefault assertionsConfiguration = null;
  public boolean configurationDebugging = false;

  // Don't convert Code objects to IRCode.
  public boolean skipIR = false;

  public boolean debug = false;

  private final CallSiteOptimizationOptions callSiteOptimizationOptions =
      new CallSiteOptimizationOptions();
  private final HorizontalClassMergerOptions horizontalClassMergerOptions =
      new HorizontalClassMergerOptions();
  private final ProtoShrinkingOptions protoShrinking = new ProtoShrinkingOptions();
  private final KotlinOptimizationOptions kotlinOptimizationOptions =
      new KotlinOptimizationOptions();
  private final DesugarSpecificOptions desugarSpecificOptions = new DesugarSpecificOptions();
  public final TestingOptions testing = new TestingOptions();

  public List<ProguardConfigurationRule> mainDexKeepRules = ImmutableList.of();
  public boolean minimalMainDex;
  /**
   * Enable usage of InheritanceClassInDexDistributor for multidex legacy builds. This allows
   * distribution of classes to minimize DexOpt LinearAlloc usage by minimizing linking errors
   * during DexOpt and controlling the load of classes with linking issues. This has the consequence
   * of making minimal main dex not absolutely minimal regarding runtime execution constraints
   * because it's adding classes in the main dex to satisfy also DexOpt constraints.
   */
  public boolean enableInheritanceClassInDexDistributor = true;

  public LineNumberOptimization lineNumberOptimization = LineNumberOptimization.ON;

  public CallSiteOptimizationOptions callSiteOptimizationOptions() {
    return callSiteOptimizationOptions;
  }

  public HorizontalClassMergerOptions horizontalClassMergerOptions() {
    return horizontalClassMergerOptions;
  }

  public ProtoShrinkingOptions protoShrinking() {
    return protoShrinking;
  }

  public KotlinOptimizationOptions kotlinOptimizationOptions() {
    return kotlinOptimizationOptions;
  }

  public DesugarSpecificOptions desugarSpecificOptions() {
    return desugarSpecificOptions;
  }

  public static boolean shouldEnableKeepRuleSynthesisForRecompilation() {
    return System.getProperty("com.android.tools.r8.keepRuleSynthesisForRecompilation") != null;
  }

  private static Set<String> getExtensiveLoggingFilter() {
    String property = System.getProperty("com.android.tools.r8.extensiveLoggingFilter");
    if (property != null) {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      for (String method : property.split(";")) {
        builder.add(method);
      }
      return builder.build();
    }
    return ImmutableSet.of();
  }

  private static Set<String> getExtensiveInterfaceMethodMinifierLoggingFilter() {
    String property =
        System.getProperty("com.android.tools.r8.extensiveInterfaceMethodMinifierLoggingFilter");
    if (property != null) {
      ImmutableSet.Builder<String> builder = ImmutableSet.builder();
      for (String method : property.split(";")) {
        builder.add(method);
      }
      return builder.build();
    }
    return ImmutableSet.of();
  }

  public static class InvalidParameterAnnotationInfo {

    final DexMethod method;
    final int expectedParameterCount;
    final int actualParameterCount;

    public InvalidParameterAnnotationInfo(
        DexMethod method, int expectedParameterCount, int actualParameterCount) {
      this.method = method;
      this.expectedParameterCount = expectedParameterCount;
      this.actualParameterCount = actualParameterCount;
    }
  }

  private static class TypeVersionPair {

    final CfVersion version;
    final DexType type;

    public TypeVersionPair(CfVersion version, DexType type) {
      this.version = version;
      this.type = type;
    }
  }

  private final Map<Origin, List<TypeVersionPair>> missingEnclosingMembers = new HashMap<>();

  private final Map<Origin, List<InvalidParameterAnnotationInfo>> warningInvalidParameterAnnotations
      = new HashMap<>();

  private final Map<Origin, List<Pair<ProgramMethod, String>>> warningInvalidDebugInfo =
      new HashMap<>();

  // Don't read code from dex files. Used to extract non-code information from vdex files where
  // the code contains unsupported byte codes.
  public boolean skipReadingDexCode = false;

  // If null, no main-dex list needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer mainDexListConsumer = null;

  // If null, no proguad map needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer proguardMapConsumer = null;

  // If null, no usage information needs to be computed.
  // If non-null, it must be and is passed to the consumer.
  public StringConsumer usageInformationConsumer = null;

  public boolean hasUsageInformationConsumer() {
    return usageInformationConsumer != null;
  }

  // If null, no proguad seeds info needs to be computed.
  // If non null it must be and passed to the consumer.
  public StringConsumer proguardSeedsConsumer = null;

  // If null, no configuration information needs to be printed.
  // If non-null, configuration must be passed to the consumer.
  public StringConsumer configurationConsumer = null;

  // If null, no desugaring of library is performed.
  // If non null it contains flags describing library desugaring.
  public DesugaredLibraryConfiguration desugaredLibraryConfiguration =
      DesugaredLibraryConfiguration.empty();

  public boolean relocatorCompilation = false;

  // If null, no keep rules are recorded.
  // If non null it records desugared library APIs used by the program.
  public StringConsumer desugaredLibraryKeepRuleConsumer = null;

  // If null, no graph information needs to be provided for the keep/inclusion of classes
  // in the output. If non-null, each edge pertaining to kept parts of the resulting program
  // must be reported to the consumer.
  public GraphConsumer keptGraphConsumer = null;

  // If null, no graph information needs to be provided for the keep/inclusion of classes
  // in the main-dex output. If non-null, each edge pertaining to kept parts in the main-dex output
  // of the resulting program must be reported to the consumer.
  public GraphConsumer mainDexKeptGraphConsumer = null;

  // If null, no desugaring dependencies need to be provided. If non-null, each dependency between
  // code objects needed for correct desugaring needs to be provided to the consumer.
  public DesugarGraphConsumer desugarGraphConsumer = null;

  public Consumer<List<ProguardConfigurationRule>> syntheticProguardRulesConsumer = null;

  public static boolean assertionsEnabled() {
    boolean assertionsEnabled = false;
    assert assertionsEnabled = true; // Intentional side-effect.
    return assertionsEnabled;
  }

  public static void checkAssertionsEnabled() {
    if (!assertionsEnabled()) {
      throw new Unreachable();
    }
  }

  /** A set of dexitems we have reported missing to dedupe warnings. */
  private final Set<DexItem> reportedMissingForDesugaring = Sets.newConcurrentHashSet();

  private final Set<DexItem> invalidLibraryClasses = Sets.newConcurrentHashSet();

  public RuntimeException errorMissingNestHost(DexClass clazz) {
    throw reporter.fatalError(
        new MissingNestHostNestDesugarDiagnostic(
            clazz.getOrigin(), Position.UNKNOWN, messageErrorMissingNestHost(clazz)));
  }

  private static String messageErrorMissingNestHost(DexClass compiledClass) {
    String nestHostName = compiledClass.getNestHost().getName();
    return "Class "
        + compiledClass.type.getName()
        + " requires its nest host "
        + nestHostName
        + " to be on program or class path.";
  }

  public RuntimeException errorMissingNestMember(Nest nest) {
    throw reporter.fatalError(
        new IncompleteNestNestDesugarDiagnosic(
            nest.getHostClass().getOrigin(), Position.UNKNOWN, messageErrorIncompleteNest(nest)));
  }

  private static String messageErrorIncompleteNest(Nest nest) {
    List<DexProgramClass> programClassesFromNest = new ArrayList<>();
    List<DexClasspathClass> classpathClassesFromNest = new ArrayList<>();
    List<DexLibraryClass> libraryClassesFromNest = new ArrayList<>();
    nest.getHostClass()
        .accept(
            programClassesFromNest::add,
            classpathClassesFromNest::add,
            libraryClassesFromNest::add);
    for (DexClass memberClass : nest.getMembers()) {
      memberClass.accept(
          programClassesFromNest::add, classpathClassesFromNest::add, libraryClassesFromNest::add);
    }
    StringBuilder stringBuilder =
        new StringBuilder("Compilation of classes ")
            .append(StringUtils.join(", ", programClassesFromNest, DexClass::getTypeName))
            .append(" requires its nest mates ");
    if (nest.hasMissingMembers()) {
      stringBuilder
          .append(StringUtils.join(", ", nest.getMissingMembers(), DexType::getTypeName))
          .append(" (unavailable) ");
    }
    if (!libraryClassesFromNest.isEmpty()) {
      stringBuilder
          .append(StringUtils.join(", ", libraryClassesFromNest, DexClass::getTypeName))
          .append(" (on library path) ");
    }
    stringBuilder.append("to be on program or class path.");
    if (!classpathClassesFromNest.isEmpty()) {
      stringBuilder
          .append("(Classes ")
          .append(StringUtils.join(", ", classpathClassesFromNest, DexClass::getTypeName))
          .append(" from the same nest are on class path).");
    }
    return stringBuilder.toString();
  }

  public void warningMissingTypeForDesugar(
      Origin origin, Position position, DexType missingType, DexMethod context) {
    if (reportedMissingForDesugaring.add(missingType)) {
      reporter.warning(
          new InterfaceDesugarMissingTypeDiagnostic(
              origin,
              position,
              Reference.classFromDescriptor(missingType.toDescriptorString()),
              Reference.classFromDescriptor(context.holder.toDescriptorString()),
              null));
    }
  }

  public void warningMissingInterfaceForDesugar(
      DexClass classToDesugar, DexClass implementing, DexType missing) {
    if (reportedMissingForDesugaring.add(missing)) {
      reporter.warning(
          new InterfaceDesugarMissingTypeDiagnostic(
              classToDesugar.getOrigin(),
              Position.UNKNOWN,
              Reference.classFromDescriptor(missing.toDescriptorString()),
              Reference.classFromDescriptor(classToDesugar.getType().toDescriptorString()),
              classToDesugar == implementing
                  ? null
                  : Reference.classFromDescriptor(implementing.getType().toDescriptorString())));
    }
  }

  public void warningInvalidLibrarySuperclassForDesugar(
      Origin origin,
      DexType libraryType,
      DexType invalidSuperType,
      String message,
      DexClassAndMethodSet retarget) {
    if (invalidLibraryClasses.add(invalidSuperType)) {
      reporter.warning(
          new InvalidLibrarySuperclassDiagnostic(
              origin,
              Reference.classFromDescriptor(libraryType.toDescriptorString()),
              Reference.classFromDescriptor(invalidSuperType.toDescriptorString()),
              message,
              Lists.newArrayList(
                  Iterables.transform(
                      retarget, method -> method.getReference().asMethodReference()))));
    }
  }

  public void warningMissingEnclosingMember(DexType clazz, Origin origin, CfVersion version) {
    TypeVersionPair pair = new TypeVersionPair(version, clazz);
    synchronized (missingEnclosingMembers) {
      missingEnclosingMembers.computeIfAbsent(origin, k -> new ArrayList<>()).add(pair);
    }
  }

  public void warningInvalidParameterAnnotations(
      DexMethod method, Origin origin, int expected, int actual) {
    InvalidParameterAnnotationInfo info =
        new InvalidParameterAnnotationInfo(method, expected, actual);
    synchronized (warningInvalidParameterAnnotations) {
      warningInvalidParameterAnnotations.computeIfAbsent(origin, k -> new ArrayList<>()).add(info);
    }
  }

  public void warningInvalidDebugInfo(
      ProgramMethod method, Origin origin, InvalidDebugInfoException e) {
    if (invalidDebugInfoFatal) {
      throw new CompilationError("Fatal warning: Invalid debug info", e);
    }
    synchronized (warningInvalidDebugInfo) {
      warningInvalidDebugInfo.computeIfAbsent(
          origin, k -> new ArrayList<>()).add(new Pair<>(method, e.getMessage()));
    }
  }

  private final Box<Boolean> reportedExperimentClassFileVersion = new Box<>(false);

  public void warningExperimentalClassFileVersion(Origin origin) {
    synchronized (reportedExperimentClassFileVersion) {
      if (reportedExperimentClassFileVersion.get()) {
        return;
      }
      reportedExperimentClassFileVersion.set(true);
      reporter.warning(
          new ExperimentalClassFileVersionDiagnostic(
              origin,
              "One or more classes has class file version >= "
                  + EXPERIMENTAL_CF_VERSION.major()
                  + " which is not officially supported."));
    }
  }

  public boolean printWarnings() {
    boolean printed = false;
    boolean printOutdatedToolchain = false;
    if (warningInvalidParameterAnnotations.size() > 0) {
      // TODO(b/67626202): Add a regression test with a program that hits this issue.
      reporter.info(
          new StringDiagnostic(
              "Invalid parameter counts in MethodParameter attributes. "
                  + "This is likely due to Proguard having removed a parameter."));
      for (Origin origin : new TreeSet<>(warningInvalidParameterAnnotations.keySet())) {
        StringBuilder builder =
            new StringBuilder("Methods with invalid MethodParameter attributes:");
        for (InvalidParameterAnnotationInfo info : warningInvalidParameterAnnotations.get(origin)) {
          builder
              .append("\n  ")
              .append(info.method)
              .append(" expected count: ")
              .append(info.expectedParameterCount)
              .append(" actual count: ")
              .append(info.actualParameterCount);
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
    }
    if (warningInvalidDebugInfo.size() > 0) {
      int count = 0;
      for (List<Pair<ProgramMethod, String>> methods : warningInvalidDebugInfo.values()) {
        count += methods.size();
      }
      reporter.info(
          new StringDiagnostic(
              "Stripped invalid locals information from "
                  + count
                  + (count == 1 ? " method." : " methods.")));
      for (Origin origin : new TreeSet<>(warningInvalidDebugInfo.keySet())) {
        StringBuilder builder = new StringBuilder("Methods with invalid locals information:");
        for (Pair<ProgramMethod, String> method : warningInvalidDebugInfo.get(origin)) {
          builder.append("\n  ").append(method.getFirst().toSourceString());
          builder.append("\n  ").append(method.getSecond());
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
      printOutdatedToolchain = true;
    }
    if (missingEnclosingMembers.size() > 0) {
      reporter.info(
          new StringDiagnostic(
              "InnerClasses attribute has entries missing a corresponding "
                  + "EnclosingMethod attribute. "
                  + "Such InnerClasses attribute entries are ignored."));
      for (Origin origin : new TreeSet<>(missingEnclosingMembers.keySet())) {
        StringBuilder builder = new StringBuilder("Classes with missing EnclosingMethod: ");
        boolean first = true;
        for (TypeVersionPair pair : missingEnclosingMembers.get(origin)) {
          if (first) {
            first = false;
          } else {
            builder.append(", ");
          }
          builder.append(pair.type);
          printOutdatedToolchain |= pair.version.isLessThan(CfVersion.V1_5);
        }
        reporter.info(new StringDiagnostic(builder.toString(), origin));
      }
      printed = true;
    }
    if (printOutdatedToolchain) {
      reporter.info(
          new StringDiagnostic(
              "Some warnings are typically a sign of using an outdated Java toolchain."
                  + " To fix, recompile the source with an updated toolchain."));
    }
    return printed;
  }

  public boolean hasMethodsFilter() {
    return methodsFilter.size() > 0;
  }

  public boolean methodMatchesFilter(DexEncodedMethod method) {
    // Not specifying a filter matches all methods.
    if (!hasMethodsFilter()) {
      return true;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return methodsFilter.indexOf(qualifiedName) >= 0;
  }

  public boolean methodMatchesLogArgumentsFilter(DexEncodedMethod method) {
    // Not specifying a filter matches no methods.
    if (logArgumentsFilter.size() == 0) {
      return false;
    }
    // Currently the filter is simple string equality on the qualified name.
    String qualifiedName = method.qualifiedName();
    return logArgumentsFilter.indexOf(qualifiedName) >= 0;
  }

  public enum PackageObfuscationMode {
    // No package obfuscation.
    NONE,
    // Strategy based on ordinary package obfuscation when no package-obfuscation mode is specified
    // by the users. In practice this falls back to FLATTEN but with keeping package-names.
    MINIFICATION,
    // Repackaging all classes into the single user-given (or top-level) package.
    REPACKAGE,
    // Repackaging all packages into the single user-given (or top-level) package.
    FLATTEN;

    public boolean isNone() {
      return this == NONE;
    }

    public boolean isFlattenPackageHierarchy() {
      return this == FLATTEN;
    }

    public boolean isRepackageClasses() {
      return this == REPACKAGE;
    }

    public boolean isMinification() {
      return this == MINIFICATION;
    }

    public boolean isSome() {
      return !isNone();
    }
  }

  public static class OutlineOptions {
    public boolean enabled = true;
    public int minSize = 3;
    public int maxSize = 99;
    public int threshold = 20;
  }

  public static class KotlinOptimizationOptions {
    public boolean disableKotlinSpecificOptimizations =
        System.getProperty("com.android.tools.r8.disableKotlinSpecificOptimizations") != null;
  }

  // Temporary desugar specific options to make progress on b/147485959
  // All options should be including bugs to either fix the underlying issue or extend the api.
  public static class DesugarSpecificOptions {
    // b/172508621
    public boolean sortMethodsOnCfOutput =
        System.getProperty("com.android.tools.r8.sortMethodsOnCfWriting") != null;
    // Desugaring is not fully idempotent. With this option turned on all desugared input is
    // allowed, and if it is detected that the desugared input cannot be reprocessed, that input
    // will be passed-through without the problematic rewritings applied.
    public boolean allowAllDesugaredInput =
        System.getProperty("com.android.tools.r8.allowAllDesugaredInput") != null;
  }

  public static class CallSiteOptimizationOptions {

    // Each time we see an invoke with more dispatch targets than the threshold, we stop call site
    // propagation for all these dispatch targets. The motivation for this is that it is expensive
    // and that we are somewhat unlikely to have precise knowledge about the value of arguments when
    // there are many (possibly spurious) call graph edges.
    private final int maxNumberOfDispatchTargetsBeforeAbandoning = 10;

    // TODO(b/69963623): enable if everything is ready, including signature rewriting at call sites.
    private boolean enableConstantPropagation = false;
    private boolean enableTypePropagation = true;

    private void disableOptimization() {
      enableConstantPropagation = false;
      enableTypePropagation = false;
    }

    public void disableTypePropagationForTesting() {
      enableTypePropagation = false;
    }

    // TODO(b/69963623): Remove this once enabled.
    @VisibleForTesting
    public static void enableConstantPropagationForTesting(InternalOptions options) {
      assert !options.callSiteOptimizationOptions().isConstantPropagationEnabled();
      options.callSiteOptimizationOptions().enableConstantPropagation = true;
    }

    public int getMaxNumberOfDispatchTargetsBeforeAbandoning() {
      return maxNumberOfDispatchTargetsBeforeAbandoning;
    }

    public boolean isEnabled() {
      return enableConstantPropagation || enableTypePropagation;
    }

    public boolean isConstantPropagationEnabled() {
      return enableConstantPropagation;
    }

    public boolean isTypePropagationEnabled() {
      return enableTypePropagation;
    }
  }

  public static class HorizontalClassMergerOptions {

    public boolean enable = true;
    public boolean enableConstructorMerging = true;
    // TODO(b/174809311): Update or remove the option and its tests after new lambdas synthetics.
    public boolean enableJavaLambdaMerging = false;

    public int syntheticArgumentCount = 3;
    public int maxGroupSize = 30;

    // TODO(b/179019716): Add support for merging in presence of annotations.
    public boolean skipNoClassesOrMembersWithAnnotationsPolicyForTesting = false;

    public void disable() {
      enable = false;
    }

    public void enable() {
      enable = true;
    }

    public void enableIf(boolean enable) {
      this.enable = enable;
    }

    public void enableJavaLambdaMerging() {
      enableJavaLambdaMerging = true;
    }

    public int getMaxGroupSize() {
      return maxGroupSize;
    }

    public int getSyntheticArgumentCount() {
      return syntheticArgumentCount;
    }

    public boolean isConstructorMergingEnabled() {
      return enableConstructorMerging;
    }

    public boolean isDisabled() {
      return !isEnabled();
    }

    public boolean isEnabled() {
      return enable;
    }

    public boolean isJavaLambdaMergingEnabled() {
      return enableJavaLambdaMerging;
    }
  }

  public static class ProtoShrinkingOptions {

    public boolean enableGeneratedExtensionRegistryShrinking = false;
    public boolean enableGeneratedMessageLiteShrinking = false;
    public boolean enableGeneratedMessageLiteBuilderShrinking = false;
    public boolean traverseOneOfAndRepeatedProtoFields = false;
    public boolean enableEnumLiteProtoShrinking = false;
    // Breaks the Chrome build if this is not enabled because of MethodToInvoke switchMaps.
    // See b/174530756 for more details.
    public boolean enableProtoEnumSwitchMapShrinking = true;

    public boolean enableRemoveProtoEnumSwitchMap() {
      return isProtoShrinkingEnabled() && enableProtoEnumSwitchMapShrinking;
    }

    public boolean isProtoShrinkingEnabled() {
      return enableGeneratedExtensionRegistryShrinking
          || enableGeneratedMessageLiteShrinking
          || enableGeneratedMessageLiteBuilderShrinking
          || enableEnumLiteProtoShrinking;
    }

    public boolean isEnumLiteProtoShrinkingEnabled() {
      return enableEnumLiteProtoShrinking;
    }
  }

  public static class TestingOptions {

    public static void allowExperimentClassFileVersion(InternalOptions options) {
      options.reportedExperimentClassFileVersion.set(true);
    }

    public static int NO_LIMIT = -1;

    // Force writing the specified bytes as the DEX version content.
    public byte[] forceDexVersionBytes = null;

    public IROrdering irOrdering =
        InternalOptions.assertionsEnabled() && !InternalOptions.DETERMINISTIC_DEBUGGING
            ? NondeterministicIROrdering.getInstance()
            : IdentityIROrdering.getInstance();

    public BiConsumer<AppInfoWithLiveness, Enqueuer.Mode> enqueuerInspector = null;

    public Consumer<String> processingContextsConsumer = null;

    public Function<AppView<AppInfoWithLiveness>, RepackagingConfiguration>
        repackagingConfigurationFactory = DefaultRepackagingConfiguration::new;

    public BiConsumer<DexItemFactory, HorizontallyMergedClasses> horizontallyMergedClassesConsumer =
        ConsumerUtils.emptyBiConsumer();

    public BiConsumer<DexItemFactory, EnumDataMap> unboxedEnumsConsumer =
        ConsumerUtils.emptyBiConsumer();

    public BiConsumer<DexItemFactory, VerticallyMergedClasses> verticallyMergedClassesConsumer =
        ConsumerUtils.emptyBiConsumer();

    public Consumer<Deque<SortedProgramMethodSet>> waveModifier = waves -> {};

    /**
     * If this flag is enabled, we will also compute the set of possible targets for invoke-
     * interface and invoke-virtual instructions that target a library method, and add the
     * corresponding edges to the call graph.
     *
     * <p>Setting this flag leads to more call graph edges, which can be good for size (e.g., it
     * increases the likelihood that virtual methods have been processed by the time their call
     * sites are processed, which allows more inlining).
     *
     * <p>However, the set of possible targets for such invokes can be very large. As an example,
     * consider the instruction {@code invoke-virtual {v0, v1}, `void Object.equals(Object)`}).
     * Therefore, tracing such invokes comes at a considerable performance penalty.
     */
    public boolean addCallEdgesForLibraryInvokes = false;

    public boolean allowCheckDiscardedErrors = false;
    public boolean allowInjectedAnnotationMethods = false;
    public boolean allowTypeErrors =
        !Version.isDevelopmentVersion()
            || System.getProperty("com.android.tools.r8.allowTypeErrors") != null;
    public boolean allowInvokeErrors = false;
    public boolean allowUnnecessaryDontWarnWildcards = true;
    public boolean allowUnusedDontWarnRules = true;
    public boolean disableL8AnnotationRemoval = false;
    public boolean reportUnusedProguardConfigurationRules = false;
    public boolean alwaysUseExistingAccessInfoCollectionsInMemberRebinding = true;
    public boolean alwaysUsePessimisticRegisterAllocation = false;
    public boolean enableCheckCastAndInstanceOfRemoval = true;
    public boolean enableDeadSwitchCaseElimination = true;
    public boolean enableInvokeSuperToInvokeVirtualRewriting = true;
    public boolean enableSwitchToIfRewriting = true;
    public boolean enableEnumUnboxingDebugLogs = false;
    public boolean forceRedundantConstNumberRemoval = false;
    public boolean canUseRecords = false;
    public boolean invertConditionals = false;
    public boolean placeExceptionalBlocksLast = false;
    public boolean dontCreateMarkerInD8 = false;
    public boolean forceJumboStringProcessing = false;
    public Set<Inliner.Reason> validInliningReasons = null;
    public boolean noLocalsTableOnInput = false;
    public boolean forceNameReflectionOptimization = false;
    public boolean enableNarrowAndWideningingChecksInD8 = false;
    public Consumer<IRCode> irModifier = null;
    public Consumer<IRCode> inlineeIrModifier = null;
    public int basicBlockMuncherIterationLimit = NO_LIMIT;
    public boolean dontReportFailingCheckDiscarded = false;
    public PrintStream whyAreYouNotInliningConsumer = System.out;
    public boolean trackDesugaredAPIConversions =
        System.getProperty("com.android.tools.r8.trackDesugaredAPIConversions") != null;
    public boolean enumUnboxingRewriteJavaCGeneratedMethod = false;
    // TODO(b/154793333): Enable assertions always when resolved.
    public boolean assertConsistentRenamingOfSignature = false;
    public boolean allowStaticInterfaceMethodsForPreNApiLevel = false;
    public int verificationSizeLimitInBytesOverride = -1;
    public boolean forceIRForCfToCfDesugar =
        System.getProperty("com.android.tools.r8.forceIRForCfToCfDesugar") != null;
    public boolean disableMappingToOriginalProgramVerification = false;
    public boolean allowInvalidCfAccessFlags =
        System.getProperty("com.android.tools.r8.allowInvalidCfAccessFlags") != null;
    // TODO(b/177333791): Set to true
    public boolean checkForNotExpandingMainDexTracingResult = false;
    public Set<String> allowedUnusedDontWarnPatterns = new HashSet<>();

    public boolean allowConflictingSyntheticTypes = false;

    // Flag to allow processing of resources in D8. A data resource consumer still needs to be
    // specified.
    public boolean enableD8ResourcesPassThrough = false;

    // TODO(b/144781417): This is disabled by default as some test apps appear to have such classes.
    public boolean allowNonAbstractClassesWithAbstractMethods = true;

    public boolean verifyKeptGraphInfo = false;

    public boolean readInputStackMaps = true;
    public boolean disableStackMapVerification = false;

    // Force each call of application read to dump its inputs to a file, which is subsequently
    // deleted. Useful to check that our dump functionality does not cause compilation failure.
    public boolean dumpAll = false;

    // Option for testing outlining with interface array arguments, see b/132420510.
    public boolean allowOutlinerInterfaceArrayArguments = false;

    public int limitNumberOfClassesPerDex = -1;

    public MinifierTestingOptions minifier = new MinifierTestingOptions();

    // Testing hooks to trigger effects in various compiler places.
    public Runnable hookInIrConversion = null;

    public static class MinifierTestingOptions {

      public Comparator<DexMethod> interfaceMethodOrdering = null;

      public Comparator<Wrapper<DexEncodedMethod>> getInterfaceMethodOrderingOrDefault(
          Comparator<Wrapper<DexEncodedMethod>> comparator) {
        if (interfaceMethodOrdering != null) {
          return (a, b) ->
              interfaceMethodOrdering.compare(a.get().getReference(), b.get().getReference());
        }
        return comparator;
      }
    }

    public boolean measureProguardIfRuleEvaluations = false;
    public ProguardIfRuleEvaluationData proguardIfRuleEvaluationData =
        new ProguardIfRuleEvaluationData();

    public static class ProguardIfRuleEvaluationData {

      public int numberOfProguardIfRuleClassEvaluations = 0;
      public int numberOfProguardIfRuleMemberEvaluations = 0;
    }

    public Consumer<ProgramMethod> callSiteOptimizationInfoInspector = null;

    public Predicate<DexMethod> cfByteCodePassThrough = null;
  }

  @VisibleForTesting
  public void disableNameReflectionOptimization() {
    // Use this util to disable get*Name() computation if the main intention of tests is checking
    // const-class, e.g., canonicalization, or some test classes' only usages are get*Name().
    enableNameReflectionOptimization = false;
  }

  private boolean hasMinApi(AndroidApiLevel level) {
    return minApiLevel >= level.getLevel();
  }

  /**
   * Allow access modification of synthetic lambda implementation methods in D8 to avoid generating
   * an excessive amount of accessibility bridges. In R8, the lambda implementation methods are
   * inlined into the synthesized accessibility bridges, thus we don't allow access modification.
   */
  public boolean canAccessModifyLambdaImplementationMethods(AppView<?> appView) {
    return !appView.enableWholeProgramOptimizations();
  }

  /**
   * Dex2Oat issues a warning for abstract methods on non-abstract classes, so we never allow this.
   *
   * <p>Note that having an invoke instruction that targets an abstract method on a non-abstract
   * class will fail with a verification error on Dalvik. Therefore, this must not be more
   * permissive than {@code return minApiLevel >= AndroidApiLevel.L.getLevel()}.
   *
   * <p>See b/132953944.
   */
  @SuppressWarnings("ConstantConditions")
  public boolean canUseAbstractMethodOnNonAbstractClass() {
    boolean result = false;
    assert !(result && canHaveDalvikAbstractMethodOnNonAbstractClassVerificationBug());
    return result;
  }

  public boolean canUseConstClassInstructions(CfVersion cfVersion) {
    assert isGeneratingClassFiles();
    return cfVersion.isGreaterThanOrEqualTo(requiredCfVersionForConstClassInstructions());
  }

  public CfVersion requiredCfVersionForConstClassInstructions() {
    assert isGeneratingClassFiles();
    return CfVersion.V1_5;
  }

  public boolean canUseInvokePolymorphicOnVarHandle() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.P);
  }

  public boolean canUseInvokePolymorphic() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.O);
  }

  public boolean canUseConstantMethodHandle() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.P);
  }

  public boolean canUseConstantMethodType() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.P);
  }

  public boolean canUseInvokeCustom() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.O);
  }

  public boolean canUseDefaultAndStaticInterfaceMethods() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.N);
  }

  public boolean canUseNestBasedAccess() {
    return !isDesugaring();
  }

  public boolean canLeaveStaticInterfaceMethodInvokes() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.L);
  }

  public boolean canUseTwrCloseResourceMethod() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.K);
  }

  public boolean enableBackportedMethodRewriting() {
    // Disable rewriting if there are no methods to rewrite or if the API level is higher than
    // the highest known API level when the compiler is built. This ensures that when this is used
    // by the Android Platform build (which normally use an API level of 10000) there will be
    // no rewriting of backported methods. See b/147480264.
    return desugarState.isOn() && minApiLevel <= AndroidApiLevel.LATEST.getLevel();
  }

  public boolean enableTryWithResourcesDesugaring() {
    switch (tryWithResourcesDesugaring) {
      case Off:
        return false;
      case Auto:
        return desugarState.isOn() && !canUseTwrCloseResourceMethod();
    }
    throw new Unreachable();
  }

  public boolean canUsePrivateInterfaceMethods() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.N);
  }

  public boolean canUseDexPcAsDebugInformation() {
    // TODO(b/37830524): Enable for min-api 26 (OREO) and above.
    return enablePcDebugInfoOutput;
  }

  public boolean isInterfaceMethodDesugaringEnabled() {
    // This condition is to filter out tests that never set program consumer.
    if (!hasConsumer()) {
      return false;
    }
    return desugarState == DesugarState.ON
        && interfaceMethodDesugaring == OffOrAuto.Auto
        && !canUseDefaultAndStaticInterfaceMethods();
  }

  public boolean isSwitchRewritingEnabled() {
    return enableSwitchRewriting && !debug;
  }

  public boolean isStringSwitchConversionEnabled() {
    return enableStringSwitchConversion && !debug;
  }

  public boolean canUseMultidex() {
    assert isGeneratingDex();
    return intermediate || hasMinApi(AndroidApiLevel.L);
  }

  public boolean canUseJavaUtilObjects() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.K);
  }

  public boolean canUseRequireNonNull() {
    return isGeneratingDex() && hasMinApi(AndroidApiLevel.K);
  }

  public boolean canUseSuppressedExceptions() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.K);
  }

  public boolean canUseAssertionErrorTwoArgumentConstructor() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.K);
  }

  public CfVersion classFileVersionAfterDesugaring(CfVersion version) {
    if (!isDesugaring()) {
      return version;
    }
    CfVersion maxVersionAfterDesugar =
        canUseDefaultAndStaticInterfaceMethods() ? CfVersion.V1_8 : CfVersion.V1_7;
    return Ordered.min(maxVersionAfterDesugar, version);
  }

  // The Apache Harmony-based AssertionError constructor which takes an Object on API 15 and older
  // calls the Error supertype constructor with null as the exception cause. This prevents
  // subsequent calls to initCause() because its implementation checks that cause==this before
  // allowing a cause to be set.
  //
  // https://android.googlesource.com/platform/libcore/+/refs/heads/ics-mr1/luni/src/main/java/java/lang/AssertionError.java#56
  public boolean canInitCauseAfterAssertionErrorObjectConstructor() {
    return !isDesugaring() || hasMinApi(AndroidApiLevel.J);
  }

  // Dalvik x86-atom backend had a bug that made it crash on filled-new-array instructions for
  // arrays of objects. This is unfortunate, since this never hits arm devices, but we have
  // to disallow filled-new-array of objects for dalvik until kitkat. The buggy code was
  // removed during the jelly-bean release cycle and is not there from kitkat.
  //
  // Buggy code that accidentally call code that only works on primitives arrays.
  //
  // https://android.googlesource.com/platform/dalvik/+/ics-mr0/vm/mterp/out/InterpAsm-x86-atom.S#25106
  public boolean canUseFilledNewArrayOfObjects() {
    assert isGeneratingDex();
    return hasMinApi(AndroidApiLevel.K);
  }

  // Art had a bug (b/68761724) for Android N and O in the arm32 interpreter
  // where an aget-wide instruction using the same register for the array
  // and the first register of the result could lead to the wrong exception
  // being thrown on out of bounds.
  public boolean canUseSameArrayAndResultRegisterInArrayGetWide() {
    assert isGeneratingDex();
    return minApiLevel > AndroidApiLevel.O_MR1.getLevel();
  }

  // Some Lollipop versions of Art found in the wild perform invalid bounds
  // check elimination. There is a fast path of loops and a slow path.
  // The bailout to the slow path is performed too early and therefore
  // the array-index variable might not be defined in the slow path code leading
  // to use of undefined registers as indices into arrays. The result
  // is ArrayIndexOutOfBounds exceptions.
  //
  // In an attempt to help these Art VMs, all single-width constants are initialized and not moved.
  //
  // There is no guarantee that this works, but it does make the problem
  // disappear on the one known instance of this problem.
  //
  // See b/69364976 and b/77996377.
  public boolean canHaveBoundsCheckEliminationBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // MediaTek JIT compilers for KitKat phones did not implement the not
  // instruction as it was not generated by DX. Therefore, apps containing
  // not instructions would crash if the code was JIT compiled. Therefore,
  // we can only use not instructions if we are targeting Art-based
  // phones.
  public boolean canUseNotInstruction() {
    return isGeneratingClassFiles() || hasMinApi(AndroidApiLevel.L);
  }

  // Art before M has a verifier bug where the type of the contents of the receiver register is
  // assumed to not change. If the receiver register is reused for something else the verifier
  // will fail and the code will not run.
  public boolean canHaveThisTypeVerifierBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // Art crashes if we do dead reference elimination of the receiver in release mode and Art
  // is asked for the |this| object over a JDWP connection at a point where the receiver
  // register has been clobbered.
  //
  // See b/116683601 and b/116837585.
  public boolean canHaveThisJitCodeDebuggingBug() {
    return minApiLevel < AndroidApiLevel.Q.getLevel();
  }

  // The dalvik jit had a bug where the long operations add, sub, or, xor and and would write
  // the first part of the result long before reading the second part of the input longs.
  public boolean canHaveOverlappingLongRegisterBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // Some dalvik versions found in the wild perform invalid JIT compilation of cmp-long
  // instructions where the result register overlaps with the input registers.
  // See b/74084493.
  //
  // The same dalvik versions also have a bug where the JIT compilation of code such as:
  //
  // void method(long l) {
  //  if (l < 0) throw new RuntimeException("less than");
  //  if (l == 0) throw new RuntimeException("equal");
  // }
  //
  // Will enter the case for l==0 even when l is non-zero. The code generated for this is of
  // the form:
  //
  // 0:   0x00: ConstWide16         v0, 0x0000000000000000 (0)
  // 1:   0x02: CmpLong             v2, v4, v0
  // 2:   0x04: IfLtz               v2, 0x0c (+8)
  // 3:   0x06: IfNez               v2, 0x0a (+4)
  //
  // However, the jit apparently clobbers the input register in the IfLtz instruction. Therefore,
  // for dalvik VMs we have to instead generate the following code:
  //
  // 0:   0x00: ConstWide16         v0, 0x0000000000000000 (0)
  // 1:   0x02: CmpLong             v2, v4, v0
  // 2:   0x04: IfLtz               v2, 0x0e (+10)
  // 3:   0x06: CmpLong             v2, v4, v0
  // 4:   0x08: IfNez               v2, 0x0c (+4)
  //
  // See b/75408029.
  public boolean canHaveCmpLongBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // Some Lollipop VMs crash if there is a const instruction between a cmp and an if instruction.
  //
  // Crashing code:
  //
  //    :goto_0
  //    cmpg-float v0, p0, p0
  //    const/4 v1, 0
  //    if-gez v0, :cond_0
  //    add-float/2addr p0, v1
  //    goto :goto_0
  //    :cond_0
  //    return p0
  //
  // Working code:
  //    :goto_0
  //    const/4 v1, 0
  //    cmpg-float v0, p0, p0
  //    if-gez v0, :cond_0
  //    add-float/2addr p0, v1
  //    goto :goto_0
  //    :cond_0
  //    return p0
  //
  // See b/115552239.
  public boolean canHaveCmpIfFloatBug() {
    return minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // Some Lollipop VMs incorrectly optimize code with mul2addr instructions. In particular,
  // the following hash code method produces wrong results after optimizations:
  //
  //    0:   0x00: IgetObject          v0, v3, Field java.lang.Class MultiClassKey.first
  //    1:   0x02: InvokeVirtual       { v0 } Ljava/lang/Object;->hashCode()I
  //    2:   0x05: MoveResult          v0
  //    3:   0x06: Const16             v1, 0x001f (31)
  //    4:   0x08: MulInt2Addr         v1, v0
  //    5:   0x09: IgetObject          v2, v3, Field java.lang.Class MultiClassKey.second
  //    6:   0x0b: InvokeVirtual       { v2 } Ljava/lang/Object;->hashCode()I
  //    7:   0x0e: MoveResult          v2
  //    8:   0x0f: AddInt2Addr         v1, v2
  //    9:   0x10: Return              v1
  //
  // It seems that the issue is the MulInt2Addr instructions. Avoiding that, the VM computes
  // hash codes correctly also after optimizations.
  //
  // This issue has only been observed on a Verizon Ellipsis 8 tablet. See b/76115465.
  public boolean canHaveMul2AddrBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // Some Marshmallow VMs create an incorrect doubly-linked list of instructions. When the VM
  // attempts to create a fixup for a Cortex 53 long add/sub issue, it may diverge due to the cyclic
  // list.
  //
  // See b/77842465.
  public boolean canHaveDex2OatLinkedListBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.N.getLevel();
  }

  // dex2oat on Marshmallow VMs does aggressive inlining which can eat up all the memory on
  // devices for self-recursive methods.
  //
  // See b/111960171
  public boolean canHaveDex2OatInliningIssue() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.N.getLevel();
  }

  // Art 7.0.0 and later Art JIT may perform an invalid optimization if a string new-instance does
  // not flow directly to the init call.
  //
  // See b/78493232 and b/80118070.
  public boolean canHaveArtStringNewInitBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.Q.getLevel();
  }

  // Dalvik tracing JIT may perform invalid optimizations when int/float values are converted to
  // double and used in arithmetic operations.
  //
  // See b/77496850.
  public boolean canHaveNumberConversionRegisterAllocationBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // Some Lollipop mediatek VMs have a peculiar bug where the inliner crashes if there is a
  // simple constructor that just forwards its arguments to the super constructor. Strangely,
  // this happens only for specific signatures: so far the only reproduction we have is for
  // a constructor accepting two doubles and one object.
  //
  // To workaround this we insert a materializing const instruction before the super init
  // call. Having a temporary register seems to disable the buggy optimizations.
  //
  // See b/68378480.
  public boolean canHaveForwardingInitInliningBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // Some Lollipop x86_64 VMs have a bug causing a segfault if an exception handler directly targets
  // a conditional-loop header. This cannot happen for debug builds as the existence of a
  // move-exception instruction will ensure a non-direct target.
  //
  // To workaround this in release builds, we insert a materializing nop instruction in the
  // exception handler forcing it not directly target any loop header.
  //
  // See b/111337896.
  public boolean canHaveExceptionTargetingLoopHeaderBug() {
    return isGeneratingDex() && !debug && minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // The Dalvik tracing JIT can trace past the end of the instruction stream and end up
  // parsing non-code bytes as code (typically leading to a crash). See b/117907456.
  //
  // In order to workaround this we insert a goto past the throw, and another goto after the throw
  // jumping back to the throw.

  // We used to insert a empty loop at the end, however, mediatek has an optimizer
  // on lollipop devices that cannot deal with an unreachable infinite loop, so we
  // couldn't do that. See b/119895393.
  // We also could not insert any dead code (e.g. a return) because that would make mediatek
  // dominator calculations on 7.0.0 crash. See b/128926846.
  public boolean canHaveTracingPastInstructionsStreamBug() {
    return minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // The art verifier incorrectly propagates type information for the following pattern:
  //
  //  move vA, vB
  //  instance-of vB, vA, Type
  //  if-eqz/nez vB
  //
  // In that case it will assume that vB has object type after the if. Therefore, if the
  // result of the instance-of operation is reused in a boolean context the verifier will
  // fail with a type conflict.
  //
  // In order to make sure that cannot happen, we insert a nop between the move and
  // the instance-of instruction so that this pattern in the art verifier does not
  // match.
  //
  //  move vA, vB
  //  nop
  //  instance-of vB, vA, Type
  //  if-eqz/nez vB
  //
  // This happens rarely, but it can happen in debug mode where the move
  // put a value into a new register which has associated locals information.
  //
  // Fixed in Android Q, see b/120985556.
  public boolean canHaveArtInstanceOfVerifierBug() {
    assert isGeneratingDex();
    return minApiLevel < AndroidApiLevel.Q.getLevel();
  }

  // Some Art Lollipop version do not deal correctly with long-to-int conversions.
  //
  // In particular, the following code performs an out of bounds array access when the
  // long loaded from the long array is very large (has non-zero values in the upper 32 bits).
  //
  //   aget-wide v9, v3, v1
  //   long-to-int v9, v9
  //   aget-wide v10, v3, v9
  //
  // The issue seems to be that the higher bits of the 64-bit register holding the long
  // are not cleared and the integer is therefore a 64-bit integer that is not truncated
  // to 32 bits.
  //
  // As a workaround, we do not allow long-to-int to have the same source and target register
  // for min-apis where lollipop devices could be targeted.
  //
  // See b/80262475.
  public boolean canHaveLongToIntBug() {
    // We have only seen this happening on Lollipop arm64 backends. We have tested on
    // Marshmallow and Nougat arm64 devices and they do not have the bug.
    return minApiLevel < AndroidApiLevel.M.getLevel();
  }

  // The Art VM for Android N through P has a bug in the JIT that means that if the same
  // exception block with a move-exception instruction is targeted with more than one type
  // of exception the JIT will incorrectly assume that the exception object has one of these
  // types and will optimize based on that one type instead of taking all the types into account.
  //
  // In order to workaround that, we always generate distinct move-exception instructions for
  // distinct dex types.
  //
  // See b/120164595.
  public boolean canHaveExceptionTypeBug() {
    return minApiLevel < AndroidApiLevel.Q.getLevel();
  }

  // Art 4.0.4 fails with a verification error when a null-literal is being passed directly to an
  // aget instruction. We therefore need to be careful when performing trivial check-cast
  // elimination of check-cast instructions where the value being cast is the constant null.
  // See b/123269162.
  public boolean canHaveArtCheckCastVerifierBug() {
    return minApiLevel < AndroidApiLevel.J.getLevel();
  }

  // The verifier will merge A[] and B[] to Object[], even when both A and B implement an interface
  // I, i.e., the join should have been I[]. This can lead to verification errors when the value is
  // used as an I[].
  //
  // See b/69826014.
  public boolean canHaveIncorrectJoinForArrayOfInterfacesBug() {
    return true;
  }

  // The dalvik verifier will crash the program if there is a try catch block with an exception
  // type that does not exist.
  // We don't do anything special about this, except that we don't inline methods that have a
  // catch handler with the ReflectiveOperationException type, i.e., if the program did not crash
  // in the non R8 case it should not in the R8 case.
  // Currently we handle only the ReflectiveOperationException, but there could be other exceptions.
  // We do this for all pre art version, in case we add more Exception types later on. The
  // problem is there for all dalvik vms, but the exception was added in api level 19
  // so we don't see it there.
  //
  // See b/131349148
  public boolean canHaveDalvikCatchHandlerVerificationBug() {
    return isGeneratingClassFiles() || minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // Having an invoke instruction that targets an abstract method on a non-abstract class will fail
  // with a verification error.
  //
  // See b/132953944.
  public boolean canHaveDalvikAbstractMethodOnNonAbstractClassVerificationBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // On dalvik we see issues when using an int value in places where a boolean, byte, char, or short
  // is expected.
  //
  // For example, if we inline the following method into the call site:
  //   public int value;
  //   public boolean getValue() {
  //     return value;
  //   }
  //
  // See also b/134304597 and b/124152497.
  public boolean canHaveDalvikIntUsedAsNonIntPrimitiveTypeBug() {
    return isGeneratingClassFiles() || minApiLevel < AndroidApiLevel.L.getLevel();
  }

  // The standard library prior to API 19 did not contain a ZipFile that implemented Closable.
  //
  // See b/177532008.
  public boolean canHaveZipFileWithMissingCloseableBug() {
    return isGeneratingClassFiles() || minApiLevel < AndroidApiLevel.K.getLevel();
  }

  // Some versions of Dalvik had a bug where a switch with a MAX_INT key would still go to
  // the default case when switching on the value MAX_INT.
  //
  // See b/177790310.
  public boolean canHaveSwitchMaxIntBug() {
    return isGeneratingDex() && minApiLevel < AndroidApiLevel.K.getLevel();
  }
}
