// Copyright (c) 2020, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.utils;

import com.google.common.collect.Iterables;
import com.google.common.collect.Iterators;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.Function;
import java.util.function.Predicate;

public class IterableUtils {

  public static <S, T> boolean any(
      Iterable<S> iterable, Function<S, T> transform, Predicate<T> predicate) {
    for (S element : iterable) {
      if (predicate.test(transform.apply(element))) {
        return true;
      }
    }
    return false;
  }

  public static <T> Iterable<T> append(Iterable<T> iterable, T element) {
    return Iterables.concat(iterable, singleton(element));
  }

  public static <T> List<T> ensureUnmodifiableList(Iterable<T> iterable) {
    List<T> list;
    if (iterable instanceof List<?>) {
      list = (List<T>) iterable;
    } else {
      list = toNewArrayList(iterable);
    }
    return Collections.unmodifiableList(list);
  }

  public static <T> T findOrDefault(Iterable<T> iterable, Predicate<T> predicate, T defaultValue) {
    for (T element : iterable) {
      if (predicate.test(element)) {
        return element;
      }
    }
    return defaultValue;
  }

  public static <T> int firstIndexMatching(Iterable<T> iterable, Predicate<T> tester) {
    int i = 0;
    for (T element : iterable) {
      if (tester.test(element)) {
        return i;
      }
      i++;
    }
    return -1;
  }

  public static <T> Iterable<T> filter(Iterable<T> iterable, Predicate<? super T> predicate) {
    return () -> IteratorUtils.filter(iterable.iterator(), predicate);
  }

  public static <T> boolean hasNext(Iterable<T> iterable) {
    return iterable.iterator().hasNext();
  }

  public static <T> int size(Iterable<T> iterable) {
    int result = 0;
    for (T element : iterable) {
      result++;
    }
    return result;
  }

  public static <T> List<T> toNewArrayList(Iterable<T> iterable) {
    List<T> result = new ArrayList<>();
    iterable.forEach(result::add);
    return result;
  }

  public static <S, T> Iterable<T> transform(Iterable<S> iterable, Function<S, T> fn) {
    return Iterables.transform(iterable, fn::apply);
  }

  public static <T> boolean isEmpty(Iterable<T> iterable) {
    return !iterable.iterator().hasNext();
  }

  public static <T> Iterable<T> singleton(T element) {
    return () -> Iterators.singletonIterator(element);
  }

  public static <T> Iterable<T> prependSingleton(T t, Iterable<T> iterable) {
    return Iterables.concat(singleton(t), iterable);
  }

  public static <T> T flatten(T init, BiFunction<T, T, T> combine, Iterable<? extends T> iterable) {
    T v = init;
    for (T t : iterable) {
      v = combine.apply(v, t);
    }
    return v;
  }

  public static int sumInt(Iterable<Integer> iterable) {
    return flatten(0, Integer::sum, iterable);
  }

  public static <F> int sumInt(Iterable<F> iterable, Function<? super F, Integer> fn) {
    Iterable<Integer> integers = Iterables.transform(iterable, fn::apply);
    return sumInt(integers);
  }

  public static <T, U> Iterable<U> flatMap(
      Iterable<T> iterable, Function<? super T, Iterable<U>> map) {
    return Iterables.concat(Iterables.transform(iterable, map::apply));
  }

  public static <T> Iterable<T> empty() {
    return Collections.emptyList();
  }

  public static <T> Iterable<T> emptyIf(Iterable<T> iterable, boolean condition) {
    if (condition) {
      return Collections.emptySet();
    } else {
      return iterable;
    }
  }

  /**
   * Utility method for testing the the elements in one and other pair-wise. Returns false if the
   * lengths differ.
   */
  public static <T> boolean testPairs(
      BiPredicate<T, T> predicate, Iterable<T> one, Iterable<T> other) {
    Iterator<T> iterator = other.iterator();
    for (T first : one) {
      if (!iterator.hasNext()) {
        return false;
      }
      T second = iterator.next();
      if (!predicate.test(first, second)) {
        return false;
      }
    }
    return !iterator.hasNext();
  }
}
