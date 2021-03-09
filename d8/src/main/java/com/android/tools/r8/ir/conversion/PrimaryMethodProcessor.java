// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.conversion;

import com.android.tools.r8.contexts.CompilationContext.MethodProcessingContext;
import com.android.tools.r8.contexts.CompilationContext.ProcessorContext;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.ir.conversion.CallGraph.Node;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.android.tools.r8.utils.Timing.TimingMerger;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.android.tools.r8.utils.collections.SortedProgramMethodSet;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * A {@link MethodProcessor} that processes methods in the whole program in a bottom-up manner,
 * i.e., from leaves to roots.
 */
class PrimaryMethodProcessor extends MethodProcessorWithWave {

  interface WaveStartAction {

    void notifyWaveStart(ProgramMethodSet wave);
  }

  private final AppView<?> appView;
  private final CallSiteInformation callSiteInformation;
  private final PostMethodProcessor.Builder postMethodProcessorBuilder;
  private final Deque<SortedProgramMethodSet> waves;

  private PrimaryMethodProcessor(
      AppView<AppInfoWithLiveness> appView,
      PostMethodProcessor.Builder postMethodProcessorBuilder,
      CallGraph callGraph) {
    this.appView = appView;
    this.callSiteInformation = callGraph.createCallSiteInformation(appView);
    this.postMethodProcessorBuilder = postMethodProcessorBuilder;
    this.waves = createWaves(appView, callGraph, callSiteInformation);
  }

  static PrimaryMethodProcessor create(
      AppView<AppInfoWithLiveness> appView,
      PostMethodProcessor.Builder postMethodProcessorBuilder,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    CallGraph callGraph = CallGraph.builder(appView).build(executorService, timing);
    return new PrimaryMethodProcessor(appView, postMethodProcessorBuilder, callGraph);
  }

  @Override
  public boolean isPrimaryMethodProcessor() {
    return true;
  }

  @Override
  public boolean shouldApplyCodeRewritings(ProgramMethod method) {
    assert !wave.contains(method);
    return !method.getDefinition().isProcessed();
  }

  @Override
  public CallSiteInformation getCallSiteInformation() {
    return callSiteInformation;
  }

  private Deque<SortedProgramMethodSet> createWaves(
      AppView<?> appView, CallGraph callGraph, CallSiteInformation callSiteInformation) {
    InternalOptions options = appView.options();
    Deque<SortedProgramMethodSet> waves = new ArrayDeque<>();
    Set<Node> nodes = callGraph.nodes;
    ProgramMethodSet reprocessing = ProgramMethodSet.create();
    int waveCount = 1;
    while (!nodes.isEmpty()) {
      SortedProgramMethodSet wave = callGraph.extractLeaves();
      wave.forEach(
          method -> {
            if (callSiteInformation.hasSingleCallSite(method) && options.enableInlining) {
              callGraph.cycleEliminationResult.forEachRemovedCaller(method, reprocessing::add);
            }
          });
      waves.addLast(wave);
      if (Log.ENABLED && Log.isLoggingEnabledFor(PrimaryMethodProcessor.class)) {
        Log.info(getClass(), "Wave #%d: %d", waveCount++, wave.size());
      }
    }
    if (!reprocessing.isEmpty()) {
      postMethodProcessorBuilder.put(reprocessing);
    }
    options.testing.waveModifier.accept(waves);
    return waves;
  }

  @FunctionalInterface
  public interface MethodAction<E extends Exception> {
    Timing apply(ProgramMethod method, MethodProcessingContext methodProcessingContext) throws E;
  }

  /**
   * Applies the given method to all leaf nodes of the graph.
   *
   * <p>As second parameter, a predicate that can be used to decide whether another method is
   * processed at the same time is passed. This can be used to avoid races in concurrent processing.
   */
  <E extends Exception> void forEachMethod(
      MethodAction<E> consumer,
      WaveStartAction waveStartAction,
      Consumer<ProgramMethodSet> waveDone,
      Timing timing,
      ExecutorService executorService)
      throws ExecutionException {
    TimingMerger merger =
        timing.beginMerger("primary-processor", ThreadUtils.getNumberOfThreads(executorService));
    while (!waves.isEmpty()) {
      ProcessorContext processorContext = appView.createProcessorContext();
      wave = waves.removeFirst();
      assert !wave.isEmpty();
      assert waveExtension.isEmpty();
      do {
        waveStartAction.notifyWaveStart(wave);
        Collection<Timing> timings =
            ThreadUtils.processItemsWithResults(
                wave,
                method -> {
                  Timing time =
                      consumer.apply(
                          method, processorContext.createMethodProcessingContext(method));
                  time.end();
                  return time;
                },
                executorService);
        merger.add(timings);
        waveDone.accept(wave);
        prepareForWaveExtensionProcessing();
      } while (!wave.isEmpty());
    }
    merger.end();
  }
}
