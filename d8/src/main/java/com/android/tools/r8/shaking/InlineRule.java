// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.shaking;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.origin.Origin;
import com.android.tools.r8.position.Position;
import java.util.List;

public class InlineRule extends ProguardConfigurationRule {

  public static final Origin checkDiscardOrigin = new Origin(Origin.root()) {
    @Override
    public String part() {
      return "<SYNTHETIC_CHECK_DISCARD_RULE>";
    }
  };

  public enum Type {
    ALWAYS,
    FORCE,
    NEVER,
    NEVER_SINGLE_CALLER
  }

  public static class Builder extends ProguardConfigurationRule.Builder<InlineRule, Builder> {

    private Builder() {
      super();
    }

    Type type;

    @Override
    public Builder self() {
      return this;
    }

    public Builder setType(Type type) {
      this.type = type;
      return this;
    }

    @Override
    public InlineRule build() {
      return new InlineRule(
          origin,
          getPosition(),
          source,
          buildClassAnnotations(),
          classAccessFlags,
          negatedClassAccessFlags,
          classTypeNegated,
          classType,
          classNames,
          buildInheritanceAnnotations(),
          inheritanceClassName,
          inheritanceIsExtends,
          memberRules,
          type);
    }
  }

  private final Type type;

  protected InlineRule(
      Origin origin,
      Position position,
      String source,
      List<ProguardTypeMatcher> classAnnotations,
      ProguardAccessFlags classAccessFlags,
      ProguardAccessFlags negatedClassAccessFlags,
      boolean classTypeNegated,
      ProguardClassType classType,
      ProguardClassNameList classNames,
      List<ProguardTypeMatcher> inheritanceAnnotations,
      ProguardTypeMatcher inheritanceClassName,
      boolean inheritanceIsExtends,
      List<ProguardMemberRule> memberRules,
      Type type) {
    super(
        origin,
        position,
        source,
        classAnnotations,
        classAccessFlags,
        negatedClassAccessFlags,
        classTypeNegated,
        classType,
        classNames,
        inheritanceAnnotations,
        inheritanceClassName,
        inheritanceIsExtends,
        memberRules);
    this.type = type;
  }

  public static Builder builder() {
    return new Builder();
  }

  public Type getType() {
    return type;
  }

  public ProguardCheckDiscardRule asProguardCheckDiscardRule() {
    assert type == Type.FORCE;
    ProguardCheckDiscardRule.Builder builder = ProguardCheckDiscardRule.builder();
    builder.setOrigin(checkDiscardOrigin);
    builder.setSource(null);
    builder.addClassAnnotations(getClassAnnotations());
    builder.setClassAccessFlags(getClassAccessFlags());
    builder.setNegatedClassAccessFlags(getNegatedClassAccessFlags());
    builder.setClassTypeNegated(getClassTypeNegated());
    builder.setClassType(getClassType());
    builder.setClassNames(getClassNames());
    builder.addInheritanceAnnotations(getInheritanceAnnotations());
    builder.setInheritanceIsExtends(getInheritanceIsExtends());
    builder.setMemberRules(getMemberRules());
    return builder.build();
  }

  @Override
  String typeString() {
    switch (type) {
      case ALWAYS:
        return "alwaysinline";
      case FORCE:
        return "forceinline";
      case NEVER:
        return "neverinline";
      case NEVER_SINGLE_CALLER:
        return "neversinglecaller";
    }
    throw new Unreachable("Unknown inline type " + type);
  }
}
