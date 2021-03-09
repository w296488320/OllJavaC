// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;

public class ConsumerUtils {

  public static <S, T> Function<S, Consumer<T>> curry(BiConsumer<S, T> function) {
    return arg -> arg2 -> function.accept(arg, arg2);
  }

  public static <S, T> Consumer<T> apply(BiConsumer<S, T> function, S arg) {
    return curry(function).apply(arg);
  }

  public static <T> Consumer<T> acceptIfNotSeen(Consumer<T> consumer, Set<T> seen) {
    return element -> {
      if (seen.add(element)) {
        consumer.accept(element);
      }
    };
  }

  public static <T> Consumer<T> emptyConsumer() {
    return ignore -> {};
  }

  public static <S, T> BiConsumer<S, T> emptyBiConsumer() {
    return (s, t) -> {};
  }

  public static <T> ThrowingConsumer<T, RuntimeException> emptyThrowingConsumer() {
    return ignore -> {};
  }
}
