// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8;

import static com.android.tools.r8.utils.ExceptionUtils.unwrapExecutionException;

import com.android.tools.r8.dex.ApplicationReader;
import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppServices;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.SubtypingInfo;
import com.android.tools.r8.shaking.Enqueuer;
import com.android.tools.r8.shaking.EnqueuerFactory;
import com.android.tools.r8.shaking.MainDexInfo;
import com.android.tools.r8.shaking.MainDexListBuilder;
import com.android.tools.r8.shaking.RootSetUtils.MainDexRootSet;
import com.android.tools.r8.shaking.WhyAreYouKeepingConsumer;
import com.android.tools.r8.utils.AndroidApp;
import com.android.tools.r8.utils.Box;
import com.android.tools.r8.utils.ExceptionUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

@Keep
public class GenerateMainDexList {
  private final Timing timing = new Timing("maindex");
  private final InternalOptions options;

  public GenerateMainDexList(InternalOptions options) {
    this.options = options;
  }

  private List<String> run(AndroidApp app, ExecutorService executor)
      throws IOException {
    try {
      // TODO(b/178231294): Clean up this such that we do not both return the result and call the
      //  consumer.
      DexApplication application = new ApplicationReader(app, options, timing).read(executor);
      List<String> result = new ArrayList<>();
      traceMainDex(executor, application, MainDexInfo.none())
          .forEach(type -> result.add(type.toBinaryName() + ".class"));
      Collections.sort(result);
      if (options.mainDexListConsumer != null) {
        options.mainDexListConsumer.accept(String.join("\n", result), options.reporter);
        options.mainDexListConsumer.finished(options.reporter);
      }
      return result;
    } catch (ExecutionException e) {
      throw unwrapExecutionException(e);
    }
  }

  public MainDexInfo traceMainDex(
      ExecutorService executor, DexApplication application, MainDexInfo existingMainDexInfo)
      throws ExecutionException {
    AppView<? extends AppInfoWithClassHierarchy> appView =
        AppView.createForR8(application.toDirect(), existingMainDexInfo);
    appView.setAppServices(AppServices.builder(appView).build());

    MainDexListBuilder.checkForAssumedLibraryTypes(appView.appInfo());

    SubtypingInfo subtypingInfo = new SubtypingInfo(appView);

    MainDexRootSet mainDexRootSet =
        MainDexRootSet.builder(appView, subtypingInfo, options.mainDexKeepRules).build(executor);
    appView.setMainDexRootSet(mainDexRootSet);

    GraphConsumer graphConsumer = options.mainDexKeptGraphConsumer;
    WhyAreYouKeepingConsumer whyAreYouKeepingConsumer = null;
    if (!mainDexRootSet.reasonAsked.isEmpty()) {
      whyAreYouKeepingConsumer = new WhyAreYouKeepingConsumer(graphConsumer);
      graphConsumer = whyAreYouKeepingConsumer;
    }

    Enqueuer enqueuer =
        EnqueuerFactory.createForGenerateMainDexList(
            appView, executor, subtypingInfo, graphConsumer);
    MainDexInfo mainDexInfo = enqueuer.traceMainDex(executor, timing);
    R8.processWhyAreYouKeepingAndCheckDiscarded(
        mainDexRootSet,
        () -> {
          ArrayList<DexProgramClass> classes = new ArrayList<>();
          // TODO(b/131668850): This is not a deterministic order!
          mainDexInfo.forEach(
              type -> {
                DexClass clazz = appView.definitionFor(type);
                assert clazz.isProgramClass();
                classes.add(clazz.asProgramClass());
              });
          return classes;
        },
        whyAreYouKeepingConsumer,
        appView,
        enqueuer,
        true,
        options,
        timing,
        executor);

    return mainDexInfo;
  }

  /**
   * Main API entry for computing the main-dex list.
   *
   * The main-dex list is represented as a list of strings, each string specifies one class to
   * keep in the primary dex file (<code>classes.dex</code>).
   *
   * A class is specified using the following format: "com/example/MyClass.class". That is
   * "/" as separator between package components, and a trailing ".class".
   *
   * @param command main dex-list generator command.
   * @return classes to keep in the primary dex file.
   */
  public static List<String> run(GenerateMainDexListCommand command)
      throws CompilationFailedException {
    ExecutorService executorService = ThreadUtils.getExecutorService(command.getInternalOptions());
    try {
      return run(command, executorService);
    } finally {
      executorService.shutdown();
    }
  }

  /**
   * Main API entry for computing the main-dex list.
   *
   * The main-dex list is represented as a list of strings, each string specifies one class to
   * keep in the primary dex file (<code>classes.dex</code>).
   *
   * A class is specified using the following format: "com/example/MyClass.class". That is
   * "/" as separator between package components, and a trailing ".class".
   *
   * @param command main dex-list generator command.
   * @param executor executor service from which to get threads for multi-threaded processing.
   * @return classes to keep in the primary dex file.
   */
  public static List<String> run(GenerateMainDexListCommand command, ExecutorService executor)
      throws CompilationFailedException {
    AndroidApp app = command.getInputApp();
    InternalOptions options = command.getInternalOptions();
    Box<List<String>> result = new Box<>();
    ExceptionUtils.withMainDexListHandler(
        command.getReporter(),
        () -> {
          try {
            result.set(new GenerateMainDexList(options).run(app, executor));
          } finally {
            executor.shutdown();
          }
        });
    return result.get();
  }

  public static void main(String[] args) throws CompilationFailedException {
    GenerateMainDexListCommand.Builder builder = GenerateMainDexListCommand.parse(args);
    GenerateMainDexListCommand command = builder.build();
    if (command.isPrintHelp()) {
      System.out.println(GenerateMainDexListCommand.USAGE_MESSAGE);
      return;
    }
    if (command.isPrintVersion()) {
      System.out.println("MainDexListGenerator " + Version.LABEL);
      return;
    }
    List<String> result = run(command);
    if (command.getMainDexListConsumer() == null) {
      result.forEach(System.out::println);
    }
  }
}
