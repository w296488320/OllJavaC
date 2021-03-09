// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.cf.code.CfArithmeticBinop;
import com.android.tools.r8.code.AddDouble;
import com.android.tools.r8.code.AddDouble2Addr;
import com.android.tools.r8.code.AddFloat;
import com.android.tools.r8.code.AddFloat2Addr;
import com.android.tools.r8.code.AddInt;
import com.android.tools.r8.code.AddInt2Addr;
import com.android.tools.r8.code.AddIntLit16;
import com.android.tools.r8.code.AddIntLit8;
import com.android.tools.r8.code.AddLong;
import com.android.tools.r8.code.AddLong2Addr;

public class Add extends ArithmeticBinop {

  public Add(NumericType type, Value dest, Value left, Value right) {
    super(type, dest, left, right);
  }

  @Override
  public int opcode() {
    return Opcodes.ADD;
  }

  @Override
  public <T> T accept(InstructionVisitor<T> visitor) {
    return visitor.visit(this);
  }

  @Override
  public boolean isCommutative() {
    return true;
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt(int dest, int left, int right) {
    return new AddInt(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong(int dest, int left, int right) {
    return new AddLong(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat(int dest, int left, int right) {
    return new AddFloat(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble(int dest, int left, int right) {
    return new AddDouble(dest, left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateInt2Addr(int left, int right) {
    return new AddInt2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateLong2Addr(int left, int right) {
    return new AddLong2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateFloat2Addr(int left, int right) {
    return new AddFloat2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateDouble2Addr(int left, int right) {
    return new AddDouble2Addr(left, right);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit8(int dest, int left, int constant) {
    return new AddIntLit8(dest, left, constant);
  }

  @Override
  public com.android.tools.r8.code.Instruction CreateIntLit16(int dest, int left, int constant) {
    return new AddIntLit16(dest, left, constant);
  }

  @Override
  public boolean identicalNonValueNonPositionParts(Instruction other) {
    return other.isAdd() && other.asAdd().type == type;
  }

  @Override
  int foldIntegers(int left, int right) {
    return left + right;
  }

  @Override
  long foldLongs(long left, long right) {
    return left + right;
  }

  @Override
  float foldFloat(float left, float right) {
    return left + right;
  }

  @Override
  double foldDouble(double left, double right) {
    return left + right;
  }

  @Override
  public boolean isAdd() {
    return true;
  }

  @Override
  public Add asAdd() {
    return this;
  }

  @Override
  CfArithmeticBinop.Opcode getCfOpcode() {
    return CfArithmeticBinop.Opcode.Add;
  }
}
