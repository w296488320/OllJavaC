// Copyright (c) 2018, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.staticizer;

import static com.android.tools.r8.graph.DexProgramClass.asProgramClassOrNull;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItemFactory;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.graph.ResolutionResult;
import com.android.tools.r8.graph.UseRegistry;
import com.android.tools.r8.ir.code.IRCode;
import com.android.tools.r8.ir.code.Instruction;
import com.android.tools.r8.ir.code.InvokeDirect;
import com.android.tools.r8.ir.code.InvokeMethodWithReceiver;
import com.android.tools.r8.ir.code.InvokeStatic;
import com.android.tools.r8.ir.code.NewInstance;
import com.android.tools.r8.ir.code.StaticGet;
import com.android.tools.r8.ir.code.StaticPut;
import com.android.tools.r8.ir.code.Value;
import com.android.tools.r8.ir.conversion.IRConverter;
import com.android.tools.r8.ir.optimize.info.OptimizationFeedback;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ListUtils;
import com.android.tools.r8.utils.SetUtils;
import com.android.tools.r8.utils.collections.LongLivedProgramMethodSetBuilder;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

public final class ClassStaticizer {

  private final AppView<AppInfoWithLiveness> appView;
  private final DexItemFactory factory;
  private final IRConverter converter;

  // Represents a staticizing candidate with all information
  // needed for staticizing.
  final class CandidateInfo {
    final DexProgramClass candidate;
    final DexEncodedField singletonField;
    final AtomicBoolean preserveRead = new AtomicBoolean(false);
    // Number of singleton field writes.
    final AtomicInteger fieldWrites = new AtomicInteger();
    // Number of instances created.
    final AtomicInteger instancesCreated = new AtomicInteger();
    final AtomicReference<DexEncodedMethod> constructor = new AtomicReference<>();
    final AtomicReference<DexEncodedMethod> getter = new AtomicReference<>();

    CandidateInfo(DexProgramClass candidate, DexEncodedField singletonField) {
      assert candidate != null;
      assert singletonField != null;
      this.candidate = candidate;
      this.singletonField = singletonField;

      // register itself
      candidates.put(candidate.type, this);
    }

    boolean isHostClassInitializer(ProgramMethod method) {
      return method.getDefinition().isClassInitializer() && method.getHolderType() == hostType();
    }

    DexType hostType() {
      return singletonField.getHolderType();
    }

    DexProgramClass hostClass() {
      DexProgramClass hostClass = asProgramClassOrNull(appView.definitionFor(hostType()));
      assert hostClass != null;
      return hostClass;
    }

    CandidateInfo invalidate() {
      candidates.remove(candidate.type);
      return null;
    }
  }

  final Map<CandidateInfo, LongLivedProgramMethodSetBuilder<?>> referencedFrom =
      new ConcurrentHashMap<>();

  // The map storing all the potential candidates for staticizing.
  final ConcurrentHashMap<DexType, CandidateInfo> candidates = new ConcurrentHashMap<>();

  public ClassStaticizer(AppView<AppInfoWithLiveness> appView, IRConverter converter) {
    this.appView = appView;
    this.factory = appView.dexItemFactory();
    this.converter = converter;
  }

  // Before doing any usage-based analysis we collect a set of classes that can be
  // candidates for staticizing. This analysis is very simple, but minimizes the
  // set of eligible classes staticizer tracks and thus time and memory it needs.
  public final void collectCandidates(DexApplication app) {
    Set<DexType> notEligible = Sets.newIdentityHashSet();
    Map<DexType, DexEncodedField> singletonFields = new HashMap<>();

    app.classes()
        .forEach(
            cls -> {
              // We only consider classes eligible for staticizing if there is just
              // one single static field in the whole app which has a type of this
              // class. This field will be considered to be a candidate for a singleton
              // field. The requirements for the initialization of this field will be
              // checked later.
              for (DexEncodedField field : cls.staticFields()) {
                DexType type = field.field.type;
                if (singletonFields.put(type, field) != null) {
                  // There is already candidate singleton field found.
                  markNotEligible(type, notEligible);
                }
              }

              // Don't allow fields with this candidate types.
              for (DexEncodedField field : cls.instanceFields()) {
                markNotEligible(field.field.type, notEligible);
              }

              // Don't allow methods that take a value of this type.
              for (DexEncodedMethod method : cls.methods()) {
                for (DexType parameter : method.getProto().parameters.values) {
                  markNotEligible(parameter, notEligible);
                }
                if (method.isSynchronized()) {
                  markNotEligible(cls.type, notEligible);
                }
              }

              // High-level limitations on what classes we consider eligible.
              if (cls.isInterface()
                  // Must not be an interface or an abstract class.
                  || cls.accessFlags.isAbstract()
                  // Don't support candidates with instance fields
                  || cls.instanceFields().size() > 0
                  // Only support classes directly extending java.lang.Object
                  || cls.superType != factory.objectType
                  // The class must not have instantiated subtypes.
                  || !cls.isEffectivelyFinal(appView)
                  // Staticizing classes implementing interfaces is more
                  // difficult, so don't support it until we really need it.
                  || !cls.interfaces.isEmpty()) {
                markNotEligible(cls.type, notEligible);
              }
            });

    // Finalize the set of the candidates.
    app.classes().forEach(cls -> {
      DexType type = cls.type;
      if (!notEligible.contains(type)) {
        DexEncodedField field = singletonFields.get(type);
        if (field != null && // Singleton field found
            !field.accessFlags.isVolatile() && // Don't remove volatile fields.
            !isPinned(cls, field)) { // Don't remove pinned objects.
          assert field.accessFlags.isStatic();
          // Note: we don't check that the field is final, since we will analyze
          //       later how and where it is initialized.
          new CandidateInfo(cls, field); // will self-register
        }
      }
    });
  }

  private void markNotEligible(DexType type, Set<DexType> notEligible) {
    if (type.isClassType()) {
      notEligible.add(type);
    }
  }

  private boolean isPinned(DexClass clazz, DexEncodedField singletonField) {
    AppInfoWithLiveness appInfo = appView.appInfo();
    if (appInfo.isPinned(clazz.type) || appInfo.isPinned(singletonField.field)) {
      return true;
    }
    for (DexEncodedMethod method : clazz.methods()) {
      if (!method.isStatic() && appInfo.isPinned(method.method)) {
        return true;
      }
    }
    return false;
  }

  // Check staticizing candidates' usages to ensure the candidate can be staticized.
  //
  // The criteria for type CANDIDATE to be eligible for staticizing fall into
  // these categories:
  //
  //  * checking that there is only one instance of the class created, and it is created
  //    inside the host class initializer, and it is guaranteed that nobody can access this
  //    field before it is assigned.
  //
  //  * no other singleton field writes (except for those used to store the only candidate
  //    class instance described above) are allowed.
  //
  //  * values read from singleton field should only be used for instance method calls.
  //
  // NOTE: there are more criteria eligible class needs to satisfy to be actually staticized,
  // those will be checked later in staticizeCandidates().
  //
  // This method also collects all DexEncodedMethod instances that need to be rewritten if
  // appropriate candidate is staticized. Essentially anything that references instance method
  // or field defined in the class.
  //
  // NOTE: can be called concurrently.
  public final void examineMethodCode(IRCode code) {
    ProgramMethod context = code.context();
    Set<Instruction> alreadyProcessed = Sets.newIdentityHashSet();

    CandidateInfo receiverClassCandidateInfo = candidates.get(context.getHolderType());
    Value receiverValue = code.getThis(); // NOTE: is null for static methods.
    if (receiverClassCandidateInfo != null) {
      if (receiverValue != null) {
        // We are inside an instance method of candidate class (not an instance initializer
        // which we will check later), check if all the references to 'this' are valid
        // (the call will invalidate the candidate if some of them are not valid).
        analyzeAllValueUsers(
            receiverClassCandidateInfo,
            receiverValue,
            factory.isConstructor(context.getReference()));

        // If the candidate is still valid, ignore all instructions
        // we treat as valid usages on receiver.
        if (candidates.get(context.getHolderType()) != null) {
          alreadyProcessed.addAll(receiverValue.uniqueUsers());
        }
      } else {
        // We are inside a static method of candidate class.
        // Check if this is a valid getter of the singleton field.
        if (context.getDefinition().returnType() == context.getHolderType()) {
          List<Instruction> examined = isValidGetter(receiverClassCandidateInfo, code);
          if (examined != null) {
            DexEncodedMethod getter = receiverClassCandidateInfo.getter.get();
            if (getter == null) {
              receiverClassCandidateInfo.getter.set(context.getDefinition());
              // Except for static-get and return, iterate other remaining instructions if any.
              alreadyProcessed.addAll(examined);
            } else {
              assert getter != context.getDefinition();
              // Not sure how to deal with many getters.
              receiverClassCandidateInfo.invalidate();
            }
          } else {
            // Invalidate the candidate if it has a static method whose return type is a candidate
            // type but doesn't return the singleton field (in a trivial way).
            receiverClassCandidateInfo.invalidate();
          }
        }
      }
    }

    // TODO(b/143375203): if fully implemented, the following iterator could be:
    //   InstructionListIterator iterator = code.instructionListIterator();
    ListIterator<Instruction> iterator =
        Lists.newArrayList(code.instructionIterator()).listIterator();
    while (iterator.hasNext()) {
      Instruction instruction = iterator.next();
      if (alreadyProcessed.contains(instruction)) {
        continue;
      }

      if (instruction.isNewInstance()) {
        // Check the class being initialized against valid staticizing candidates.
        NewInstance newInstance = instruction.asNewInstance();
        CandidateInfo info = processInstantiation(context, iterator, newInstance);
        if (info != null) {
          alreadyProcessed.addAll(newInstance.outValue().aliasedUsers());
          // For host class initializers having eligible instantiation we also want to
          // ensure that the rest of the initializer consist of code w/o side effects.
          // This must guarantee that removing field access will not result in missing side
          // effects, otherwise we can still staticize, but cannot remove singleton reads.
          while (iterator.hasNext()) {
            if (!isAllowedInHostClassInitializer(context.getHolderType(), iterator.next(), code)) {
              info.preserveRead.set(true);
              iterator.previous();
              break;
            }
            // Ignore just read instruction.
          }
          addReferencedFrom(info, context);
        }
        continue;
      }

      if (instruction.isStaticPut()) {
        // Check the field being written to: no writes to singleton fields are allowed
        // except for those processed in processInstantiation(...).
        DexType candidateType = instruction.asStaticPut().getField().type;
        CandidateInfo candidateInfo = candidates.get(candidateType);
        if (candidateInfo != null) {
          candidateInfo.invalidate();
        }
        continue;
      }

      if (instruction.isStaticGet()) {
        // Check the field being read: make sure all usages are valid.
        CandidateInfo info = processStaticFieldRead(instruction.asStaticGet());
        if (info != null) {
          addReferencedFrom(info, context);
          // If the candidate is still valid, ignore all usages in further analysis.
          Value value = instruction.outValue();
          if (value != null) {
            alreadyProcessed.addAll(value.aliasedUsers());
          }
        }
        continue;
      }

      if (instruction.isInvokeStatic()) {
        // Check if it is a static singleton getter.
        CandidateInfo info = processInvokeStatic(instruction.asInvokeStatic());
        if (info != null) {
          addReferencedFrom(info, context);
          // If the candidate is still valid, ignore all usages in further analysis.
          Value value = instruction.outValue();
          if (value != null) {
            alreadyProcessed.addAll(value.aliasedUsers());
          }
        }
        continue;
      }

      if (instruction.isInvokeMethodWithReceiver()) {
        DexMethod invokedMethod = instruction.asInvokeMethodWithReceiver().getInvokedMethod();
        CandidateInfo candidateInfo = candidates.get(invokedMethod.holder);
        if (candidateInfo != null) {
          // A call to instance method of the candidate class we don't know how to deal with.
          candidateInfo.invalidate();
        }
        continue;
      }

      if (instruction.isInvokeCustom()) {
        // Just invalidate any candidates referenced from non-static context.
        CallSiteReferencesInvalidator invalidator = new CallSiteReferencesInvalidator(factory);
        invalidator.registerCallSite(instruction.asInvokeCustom().getCallSite());
        continue;
      }

      if (instruction.isInstanceGet() || instruction.isInstancePut()) {
        DexField fieldReferenced = instruction.asFieldInstruction().getField();
        CandidateInfo candidateInfo = candidates.get(fieldReferenced.holder);
        if (candidateInfo != null) {
          // Reads/writes to instance field of the candidate class are not supported.
          candidateInfo.invalidate();
        }
        continue;
      }
    }
  }

  private void addReferencedFrom(CandidateInfo info, ProgramMethod context) {
    LongLivedProgramMethodSetBuilder<?> builder =
        referencedFrom.computeIfAbsent(
            info, ignore -> LongLivedProgramMethodSetBuilder.createConcurrentForIdentitySet());
    builder.add(context);
  }

  private boolean isAllowedInHostClassInitializer(
      DexType host, Instruction insn, IRCode code) {
    return (insn.isStaticPut() && insn.asStaticPut().getField().holder == host) ||
        insn.isConstNumber() ||
        insn.isConstString() ||
        (insn.isGoto() && insn.asGoto().isTrivialGotoToTheNextBlock(code)) ||
        insn.isReturn();
  }

  private CandidateInfo processInstantiation(
      ProgramMethod context, ListIterator<Instruction> iterator, NewInstance newInstance) {
    DexType candidateType = newInstance.clazz;
    CandidateInfo candidateInfo = candidates.get(candidateType);
    if (candidateInfo == null) {
      return null; // Not interested.
    }

    if (iterator.previousIndex() != 0) {
      // Valid new instance must be the first instruction in the class initializer
      return candidateInfo.invalidate();
    }

    if (!candidateInfo.isHostClassInitializer(context)) {
      // A valid candidate must only have one instantiation which is
      // done in the static initializer of the host class.
      return candidateInfo.invalidate();
    }

    if (candidateInfo.instancesCreated.incrementAndGet() > 1) {
      // Only one instance must be ever created.
      return candidateInfo.invalidate();
    }

    Value candidateValue = newInstance.dest();
    if (candidateValue == null) {
      // Must be assigned to a singleton field.
      return candidateInfo.invalidate();
    }

    if (candidateValue.numberOfPhiUsers() > 0) {
      return candidateInfo.invalidate();
    }

    if (candidateValue.numberOfUsers() < 2) {
      // We expect two special users for each instantiation: constructor call and static field
      // write. We allow the instance to have other users as well, as long as they are valid
      // according to the user analysis.
      return candidateInfo.invalidate();
    }

    // Check usages. Currently we only support the patterns like:
    //
    //     static constructor void <clinit>() {
    //        new-instance v0, <candidate-type>
    //  (opt) const/4 v1, #int 0 // (optional)
    //        invoke-direct {v0, ...}, void <candidate-type>.<init>(...)
    //        sput-object v0, <instance-field>
    //        ...
    //        ... // other usages that are valid according to the user analysis.
    //
    // In case we guarantee candidate constructor does not access <instance-field>
    // directly or indirectly we can guarantee that all the potential reads get
    // same non-null value.

    // Skip potential constant instructions
    while (iterator.hasNext() && isNonThrowingConstInstruction(iterator.next())) {
      // Intentionally empty.
    }
    iterator.previous();
    if (!iterator.hasNext()) {
      return candidateInfo.invalidate();
    }
    Set<Instruction> users = SetUtils.newIdentityHashSet(candidateValue.uniqueUsers());
    Instruction constructorCall = iterator.next();
    if (!isValidInitCall(candidateInfo, constructorCall, candidateValue, context)) {
      iterator.previous();
      return candidateInfo.invalidate();
    }
    boolean removedConstructorCall = users.remove(constructorCall);
    assert removedConstructorCall;
    if (!iterator.hasNext()) {
      return candidateInfo.invalidate();
    }
    Instruction staticPut = iterator.next();
    if (!isValidStaticPut(candidateInfo, staticPut)) {
      iterator.previous();
      return candidateInfo.invalidate();
    }
    boolean removedStaticPut = users.remove(staticPut);
    assert removedStaticPut;
    if (candidateInfo.fieldWrites.incrementAndGet() > 1) {
      return candidateInfo.invalidate();
    }
    if (!isSelectedValueUsersValid(candidateInfo, candidateValue, false, users)) {
      return candidateInfo.invalidate();
    }
    return candidateInfo;
  }

  private boolean isNonThrowingConstInstruction(Instruction instruction) {
    return instruction.isConstInstruction() && !instruction.instructionTypeCanThrow();
  }

  private boolean isValidInitCall(
      CandidateInfo info, Instruction instruction, Value candidateValue, ProgramMethod context) {
    if (!instruction.isInvokeDirect()) {
      return false;
    }

    // Check constructor.
    InvokeDirect invoke = instruction.asInvokeDirect();
    DexEncodedMethod methodInvoked =
        appView.appInfo().lookupDirectTarget(invoke.getInvokedMethod(), context);
    List<Value> values = invoke.inValues();

    if (ListUtils.lastIndexMatching(values, v -> v.getAliasedValue() == candidateValue) != 0
        || methodInvoked == null
        || methodInvoked.getHolderType() != info.candidate.type) {
      return false;
    }

    // Check arguments.
    for (int i = 1; i < values.size(); i++) {
      Value arg = values.get(i).getAliasedValue();
      if (arg.isPhi() || !arg.definition.isConstInstruction()) {
        return false;
      }
    }

    DexEncodedMethod previous = info.constructor.getAndSet(methodInvoked);
    assert previous == null;
    return true;
  }

  private boolean isValidStaticPut(CandidateInfo info, Instruction instruction) {
    if (!instruction.isStaticPut()) {
      return false;
    }
    // Allow single assignment to a singleton field.
    StaticPut staticPut = instruction.asStaticPut();
    DexEncodedField fieldAccessed = appView.appInfo().lookupStaticTarget(staticPut.getField());
    return fieldAccessed == info.singletonField;
  }

  // Only allow a very trivial pattern: load the singleton field and return it, which looks like:
  //
  //   v <- static-get singleton-field
  //   <assume instructions on v> // (optional)
  //   return v // or aliased value
  //
  // Returns a list of instructions that are examined (as long as the method is a trivial getter).
  private List<Instruction> isValidGetter(CandidateInfo info, IRCode code) {
    List<Instruction> instructions = new ArrayList<>();
    StaticGet staticGet = null;
    for (Instruction instr : code.instructions()) {
      if (instr.isStaticGet()) {
        staticGet = instr.asStaticGet();
        DexEncodedField fieldAccessed = appView.appInfo().lookupStaticTarget(staticGet.getField());
        if (fieldAccessed != info.singletonField) {
          return null;
        }
        instructions.add(instr);
        continue;
      }
      if (instr.isAssume() || instr.isReturn()) {
        Value v = instr.inValues().get(0).getAliasedValue();
        if (v.isPhi() || v.definition != staticGet) {
          return null;
        }
        instructions.add(instr);
        continue;
      }
      // All other instructions are not allowed.
      return null;
    }
    return instructions;
  }

  // Static field get: can be a valid singleton field for a
  // candidate in which case we should check if all the usages of the
  // value read are eligible.
  private CandidateInfo processStaticFieldRead(StaticGet staticGet) {
    DexField field = staticGet.getField();
    DexType candidateType = field.type;
    CandidateInfo candidateInfo = candidates.get(candidateType);
    if (candidateInfo == null) {
      return null;
    }

    assert candidateInfo.singletonField == appView.appInfo().lookupStaticTarget(field)
        : "Added reference after collectCandidates(...)?";

    Value singletonValue = staticGet.dest();
    if (singletonValue != null) {
      candidateInfo = analyzeAllValueUsers(candidateInfo, singletonValue, false);
    }
    return candidateInfo;
  }

  // Static getter: if this invokes a registered getter, treat it as static field get.
  // That is, we should check if all the usages of the out value are eligible.
  private CandidateInfo processInvokeStatic(InvokeStatic invoke) {
    DexType candidateType = invoke.getInvokedMethod().proto.returnType;
    CandidateInfo candidateInfo = candidates.get(candidateType);
    if (candidateInfo == null) {
      return null;
    }

    if (invoke.hasOutValue()
        && candidateInfo.getter.get() != null
        && candidateInfo.getter.get().method == invoke.getInvokedMethod()) {
      candidateInfo = analyzeAllValueUsers(candidateInfo, invoke.outValue(), false);
    }
    return candidateInfo;
  }

  private CandidateInfo analyzeAllValueUsers(
      CandidateInfo candidateInfo, Value value, boolean ignoreSuperClassInitInvoke) {
    assert value != null && value == value.getAliasedValue();
    if (value.numberOfPhiUsers() > 0) {
      return candidateInfo.invalidate();
    }
    if (!isSelectedValueUsersValid(
        candidateInfo, value, ignoreSuperClassInitInvoke, value.uniqueUsers())) {
      return candidateInfo.invalidate();
    }
    return candidateInfo;
  }

  private boolean isSelectedValueUsersValid(
      CandidateInfo candidateInfo,
      Value value,
      boolean ignoreSuperClassInitInvoke,
      Set<Instruction> currentUsers) {
    while (!currentUsers.isEmpty()) {
      Set<Instruction> indirectUsers = Sets.newIdentityHashSet();
      for (Instruction user : currentUsers) {
        if (!isValidValueUser(
            candidateInfo, value, ignoreSuperClassInitInvoke, indirectUsers, user)) {
          return false;
        }
      }
      currentUsers = indirectUsers;
    }
    return true;
  }

  private boolean isValidValueUser(
      CandidateInfo candidateInfo,
      Value value,
      boolean ignoreSuperClassInitInvoke,
      Set<Instruction> indirectUsers,
      Instruction user) {
    if (user.isAssume()) {
      if (user.outValue().numberOfPhiUsers() > 0) {
        return false;
      }
      indirectUsers.addAll(user.outValue().uniqueUsers());
      return true;
    }
    if (user.isInvokeVirtual() || user.isInvokeDirect() /* private methods */) {
      InvokeMethodWithReceiver invoke = user.asInvokeMethodWithReceiver();
      Predicate<Value> isAliasedValue = v -> v.getAliasedValue() == value;
      DexMethod methodReferenced = invoke.getInvokedMethod();
      if (factory.isConstructor(methodReferenced)) {
        assert user.isInvokeDirect();
        if (ignoreSuperClassInitInvoke
            && ListUtils.lastIndexMatching(invoke.inValues(), isAliasedValue) == 0
            && methodReferenced == factory.objectMembers.constructor) {
          // If we are inside candidate constructor and analyzing usages
          // of the receiver, we want to ignore invocations of superclass
          // constructor which will be removed after staticizing.
          return true;
        }
        return false;
      }
      AppInfoWithLiveness appInfo = appView.appInfo();
      ResolutionResult resolutionResult =
          appInfo.unsafeResolveMethodDueToDexFormat(methodReferenced);
      DexEncodedMethod methodInvoked =
          user.isInvokeDirect()
              ? resolutionResult.lookupInvokeDirectTarget(candidateInfo.candidate, appInfo)
              : resolutionResult.isVirtualTarget() ? resolutionResult.getSingleTarget() : null;
      if (ListUtils.lastIndexMatching(invoke.inValues(), isAliasedValue) == 0
          && methodInvoked != null
          && methodInvoked.getHolderType() == candidateInfo.candidate.type) {
        return true;
      }
    }

    // All other users are not allowed.
    return false;
  }

  // Perform staticizing candidates:
  //
  //  1. After filtering candidates based on usage, finalize the list of candidates by
  //  filtering out candidates which don't satisfy the requirements:
  //
  //    * there must be one instance of the class
  //    * constructor of the class used to create this instance must be a trivial one
  //    * class initializer should only be present if candidate itself is own host
  //    * no abstract or native instance methods
  //
  //  2. Rewrite instance methods of classes being staticized into static ones
  //  3. Rewrite methods referencing staticized members, also remove instance creation
  //
  public final void staticizeCandidates(
      OptimizationFeedback feedback, ExecutorService executorService) throws ExecutionException {
    new StaticizingProcessor(appView, this, converter).run(feedback, executorService);
  }

  private class CallSiteReferencesInvalidator extends UseRegistry {

    private CallSiteReferencesInvalidator(DexItemFactory factory) {
      super(factory);
    }

    private void registerMethod(DexMethod method) {
      registerTypeReference(method.holder);
      registerProto(method.proto);
    }

    private void registerField(DexField field) {
      registerTypeReference(field.holder);
      registerTypeReference(field.type);
    }

    @Override
    public void registerInitClass(DexType clazz) {
      registerTypeReference(clazz);
    }

    @Override
    public void registerInvokeVirtual(DexMethod method) {
      registerMethod(method);
    }

    @Override
    public void registerInvokeDirect(DexMethod method) {
      registerMethod(method);
    }

    @Override
    public void registerInvokeStatic(DexMethod method) {
      registerMethod(method);
    }

    @Override
    public void registerInvokeInterface(DexMethod method) {
      registerMethod(method);
    }

    @Override
    public void registerInvokeSuper(DexMethod method) {
      registerMethod(method);
    }

    @Override
    public void registerInstanceFieldWrite(DexField field) {
      registerField(field);
    }

    @Override
    public void registerInstanceFieldRead(DexField field) {
      registerField(field);
    }

    @Override
    public void registerNewInstance(DexType type) {
      registerTypeReference(type);
    }

    @Override
    public void registerStaticFieldRead(DexField field) {
      registerField(field);
    }

    @Override
    public void registerStaticFieldWrite(DexField field) {
      registerField(field);
    }

    @Override
    public void registerTypeReference(DexType type) {
      CandidateInfo candidateInfo = candidates.get(type);
      if (candidateInfo != null) {
        candidateInfo.invalidate();
      }
    }

    @Override
    public void registerInstanceOf(DexType type) {
      registerTypeReference(type);
    }
  }
}
