// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.utils;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.utils.structural.Ordered;
import java.util.Arrays;
import java.util.List;

/** Android API level description */
public enum AndroidApiLevel implements Ordered<AndroidApiLevel> {
  B(1),
  B_1_1(2),
  C(3),
  D(4),
  E(5),
  E_0_1(6),
  E_MR1(7),
  F(8),
  G(9),
  G_MR1(10),
  H(11),
  H_MR1(12),
  H_MR2(13),
  I(14),
  I_MR1(15),
  J(16),
  J_MR1(17),
  J_MR2(18),
  K(19),
  K_WATCH(20),
  L(21),
  L_MR1(22),
  M(23),
  N(24),
  N_MR1(25),
  O(26),
  O_MR1(27),
  P(28),
  Q(29),
  R(30),
  S(31);

  public static final AndroidApiLevel LATEST = R;

  public static final int magicApiLevelUsedByAndroidPlatformBuild = 10000;

  private final int level;

  AndroidApiLevel(int level) {
    this.level = level;
  }

  public int getLevel() {
    return level;
  }

  public String getName() {
    return "Android " + name();
  }

  public static AndroidApiLevel getDefault() {
    return AndroidApiLevel.B;
  }

  public DexVersion getDexVersion() {
    return DexVersion.getDexVersion(this);
  }

  public static List<AndroidApiLevel> getAndroidApiLevelsSorted() {
    return Arrays.asList(AndroidApiLevel.values());
  }

  public static AndroidApiLevel getMinAndroidApiLevel(DexVersion dexVersion) {
    switch (dexVersion) {
      case V35:
        return AndroidApiLevel.B;
      case V37:
        return AndroidApiLevel.N;
      case V38:
        return AndroidApiLevel.O;
      case V39:
        return AndroidApiLevel.P;
      default:
        throw new Unreachable();
    }
  }

  public static AndroidApiLevel getAndroidApiLevel(int apiLevel) {
    switch (apiLevel) {
      case 0:
        // 0 is not supported, it should not happen
        throw new Unreachable();
      case 1:
        return B;
      case 2:
        return B_1_1;
      case 3:
        return C;
      case 4:
        return D;
      case 5:
        return E;
      case 6:
        return E_0_1;
      case 7:
        return E_MR1;
      case 8:
        return F;
      case 9:
        return G;
      case 10:
        return G_MR1;
      case 11:
        return H;
      case 12:
        return H_MR1;
      case 13:
        return H_MR2;
      case 14:
        return I;
      case 15:
        return I_MR1;
      case 16:
        return J;
      case 17:
        return J_MR1;
      case 18:
        return J_MR2;
      case 19:
        return K;
      case 20:
        return K_WATCH;
      case 21:
        return L;
      case 22:
        return L_MR1;
      case 23:
        return M;
      case 24:
        return N;
      case 25:
        return N_MR1;
      case 26:
        return O;
      case 27:
        return O_MR1;
      case 28:
        return P;
      case 29:
        return Q;
      default:
        return LATEST;
    }
  }
}
