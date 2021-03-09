// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.dex;

import static com.android.tools.r8.utils.LebUtils.sizeAsUleb128;

import com.android.tools.r8.ByteBufferProvider;
import com.android.tools.r8.code.Instruction;
import com.android.tools.r8.errors.CompilationError;
import com.android.tools.r8.errors.DefaultInterfaceMethodDiagnostic;
import com.android.tools.r8.errors.InvokeCustomDiagnostic;
import com.android.tools.r8.errors.PrivateInterfaceMethodDiagnostic;
import com.android.tools.r8.errors.StaticInterfaceMethodDiagnostic;
import com.android.tools.r8.graph.DexAnnotation;
import com.android.tools.r8.graph.DexAnnotationDirectory;
import com.android.tools.r8.graph.DexAnnotationElement;
import com.android.tools.r8.graph.DexAnnotationSet;
import com.android.tools.r8.graph.DexApplication;
import com.android.tools.r8.graph.DexCallSite;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexCode;
import com.android.tools.r8.graph.DexCode.Try;
import com.android.tools.r8.graph.DexCode.TryHandler;
import com.android.tools.r8.graph.DexCode.TryHandler.TypeAddrPair;
import com.android.tools.r8.graph.DexDebugInfo;
import com.android.tools.r8.graph.DexDebugInfoForWriting;
import com.android.tools.r8.graph.DexEncodedAnnotation;
import com.android.tools.r8.graph.DexEncodedArray;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMember;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexItem;
import com.android.tools.r8.graph.DexMember;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexMethodHandle;
import com.android.tools.r8.graph.DexMethodHandle.MethodHandleType;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.DexTypeList;
import com.android.tools.r8.graph.DexValue;
import com.android.tools.r8.graph.GraphLens;
import com.android.tools.r8.graph.IndexedDexItem;
import com.android.tools.r8.graph.ObjectToOffsetMapping;
import com.android.tools.r8.graph.ParameterAnnotationsList;
import com.android.tools.r8.graph.ProgramClassVisitor;
import com.android.tools.r8.graph.ProgramDexCode;
import com.android.tools.r8.graph.ProgramMethod;
import com.android.tools.r8.logging.Log;
import com.android.tools.r8.naming.ClassNameMapper;
import com.android.tools.r8.naming.MemberNaming.MethodSignature;
import com.android.tools.r8.naming.MemberNaming.Signature;
import com.android.tools.r8.naming.NamingLens;
import com.android.tools.r8.position.MethodPosition;
import com.android.tools.r8.synthesis.SyntheticNaming;
import com.android.tools.r8.utils.AndroidApiLevel;
import com.android.tools.r8.utils.DexVersion;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.IterableUtils;
import com.android.tools.r8.utils.LebUtils;
import com.google.common.collect.Sets;
import it.unimi.dsi.fastutil.objects.Object2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2IntMap;
import it.unimi.dsi.fastutil.objects.Reference2IntLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2IntMap;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.ToIntFunction;
import java.util.zip.Adler32;

public class FileWriter {

  /** Simple pair of a byte buffer and its written length. */
  public static class ByteBufferResult {

    // Ownership of the buffer is transferred to the receiver of this result structure.
    public final CompatByteBuffer buffer;
    public final int length;

    private ByteBufferResult(CompatByteBuffer buffer, int length) {
      this.buffer = buffer;
      this.length = length;
    }
  }

  private final ObjectToOffsetMapping mapping;
  private final MethodToCodeObjectMapping codeMapping;
  private final DexApplication application;
  private final InternalOptions options;
  private final GraphLens graphLens;
  private final NamingLens namingLens;
  private final DexOutputBuffer dest;
  private final MixedSectionOffsets mixedSectionOffsets;
  private final CodeToKeep desugaredLibraryCodeToKeep;
  private final Map<DexProgramClass, DexEncodedArray> staticFieldValues = new IdentityHashMap<>();

  public FileWriter(
      ByteBufferProvider provider,
      ObjectToOffsetMapping mapping,
      MethodToCodeObjectMapping codeMapping,
      DexApplication application,
      InternalOptions options,
      NamingLens namingLens,
      CodeToKeep desugaredLibraryCodeToKeep) {
    this.mapping = mapping;
    this.codeMapping = codeMapping;
    this.application = application;
    this.options = options;
    this.graphLens = mapping.getGraphLens();
    this.namingLens = namingLens;
    this.dest = new DexOutputBuffer(provider);
    this.mixedSectionOffsets = new MixedSectionOffsets(options, codeMapping);
    this.desugaredLibraryCodeToKeep = desugaredLibraryCodeToKeep;
  }

  public static void writeEncodedAnnotation(
      DexEncodedAnnotation annotation, DexOutputBuffer dest, ObjectToOffsetMapping mapping) {
    if (Log.ENABLED) {
      Log.verbose(FileWriter.class, "Writing encoded annotation @ %08x", dest.position());
    }
    List<DexAnnotationElement> elements = new ArrayList<>(Arrays.asList(annotation.elements));
    elements.sort((a, b) -> a.name.acceptCompareTo(b.name, mapping.getCompareToVisitor()));
    dest.putUleb128(mapping.getOffsetFor(annotation.type));
    dest.putUleb128(elements.size());
    for (DexAnnotationElement element : elements) {
      dest.putUleb128(mapping.getOffsetFor(element.name));
      element.value.writeTo(dest, mapping);
    }
  }

  public FileWriter collect() {
    // Use the class array from the mapping, as it has a deterministic iteration order.
    new ProgramClassDependencyCollector(application, mapping.getClasses())
        .run(mapping.getClasses());

    // Add the static values for all fields now that we have committed to their sorting.
    mixedSectionOffsets.getClassesWithData().forEach(this::addStaticFieldValues);

    // String data is not tracked by the MixedSectionCollection.new AppInfo(application, null)
    assert mixedSectionOffsets.stringData.size() == 0;
    for (DexString string : mapping.getStrings()) {
      mixedSectionOffsets.add(string);
    }
    // Neither are the typelists in protos...
    for (DexProto proto : mapping.getProtos()) {
      mixedSectionOffsets.add(proto.parameters);
    }

    DexItem.collectAll(mixedSectionOffsets, mapping.getCallSites());

    DexItem.collectAll(mixedSectionOffsets, mapping.getClasses());

    return this;
  }

  public ByteBufferResult generate() {
    // Check restrictions on interface methods.
    checkInterfaceMethods();

    // Check restriction on the names of fields, methods and classes
    assert verifyNames();

    Layout layout = Layout.from(mapping);
    layout.setCodesOffset(layout.dataSectionOffset);

    // Check code objects in the code-mapping are consistent with the collected code objects.
    assert codeMapping.verifyCodeObjects(mixedSectionOffsets.getCodes());

    // Sort the codes first, as their order might impact size due to alignment constraints.
    List<ProgramDexCode> codes = sortDexCodesByClassName();

    // Output the debug_info_items first, as they have no dependencies.
    dest.moveTo(layout.getCodesOffset() + sizeOfCodeItems(codes));
    if (mixedSectionOffsets.getDebugInfos().isEmpty()) {
      layout.setDebugInfosOffset(0);
    } else {
      // Ensure deterministic ordering of debug info by sorting consistent with the code objects.
      layout.setDebugInfosOffset(dest.align(1));
      Set<DexDebugInfo> seen = new HashSet<>(mixedSectionOffsets.getDebugInfos().size());
      for (ProgramDexCode code : codes) {
        DexDebugInfoForWriting info = code.getCode().getDebugInfoForWriting();
        if (info != null && seen.add(info)) {
          writeDebugItem(info, graphLens);
        }
      }
    }

    // Remember the typelist offset for later.
    layout.setTypeListsOffset(dest.align(4)); // type_list are aligned.

    // Now output the code.
    dest.moveTo(layout.getCodesOffset());
    assert dest.isAligned(4);
    writeItems(codes, layout::alreadySetOffset, this::writeCodeItem, 4);
    assert layout.getDebugInfosOffset() == 0 || dest.position() == layout.getDebugInfosOffset();

    // Now the type lists and rest.
    dest.moveTo(layout.getTypeListsOffset());
    writeItems(mixedSectionOffsets.getTypeLists(), layout::alreadySetOffset, this::writeTypeList);
    writeItems(mixedSectionOffsets.getStringData(), layout::setStringDataOffsets,
        this::writeStringData);
    writeItems(mixedSectionOffsets.getAnnotations(), layout::setAnnotationsOffset,
        this::writeAnnotation);
    writeItems(mixedSectionOffsets.getClassesWithData(), layout::setClassDataOffset,
        this::writeClassData);
    writeItems(mixedSectionOffsets.getEncodedArrays(), layout::setEncodedArrarysOffset,
        this::writeEncodedArray);
    writeItems(mixedSectionOffsets.getAnnotationSets(), layout::setAnnotationSetsOffset,
        this::writeAnnotationSet, 4);
    writeItems(mixedSectionOffsets.getAnnotationSetRefLists(),
        layout::setAnnotationSetRefListsOffset, this::writeAnnotationSetRefList, 4);
    writeItems(mixedSectionOffsets.getAnnotationDirectories(),
        layout::setAnnotationDirectoriesOffset, this::writeAnnotationDirectory, 4);

    // Add the map at the end
    layout.setMapOffset(dest.align(4));
    writeMap(layout);
    layout.setEndOfFile(dest.position());

    // Now that we have all mixedSectionOffsets, lets write the indexed items.
    dest.moveTo(Constants.TYPE_HEADER_ITEM_SIZE);
    writeFixedSectionItems(mapping.getStrings(), layout.stringIdsOffset, this::writeStringItem);
    writeFixedSectionItems(mapping.getTypes(), layout.typeIdsOffset, this::writeTypeItem);
    writeFixedSectionItems(mapping.getProtos(), layout.protoIdsOffset, this::writeProtoItem);
    writeFixedSectionItems(mapping.getFields(), layout.fieldIdsOffset, this::writeFieldItem);
    writeFixedSectionItems(mapping.getMethods(), layout.methodIdsOffset, this::writeMethodItem);
    writeFixedSectionItems(mapping.getClasses(), layout.classDefsOffset, this::writeClassDefItem);
    writeFixedSectionItems(mapping.getCallSites(), layout.callSiteIdsOffset, this::writeCallSite);
    writeFixedSectionItems(
        mapping.getMethodHandles(), layout.methodHandleIdsOffset, this::writeMethodHandle);

    // Fill in the header information.
    writeHeader(layout);
    writeSignature(layout);
    writeChecksum(layout);

    // Wrap backing buffer with actual length.
    return new ByteBufferResult(dest.stealByteBuffer(), layout.getEndOfFile());
  }

  private void checkInterfaceMethods() {
    for (DexProgramClass clazz : mapping.getClasses()) {
      if (clazz.isInterface()) {
        for (DexEncodedMethod method : clazz.directMethods()) {
          checkInterfaceMethod(method);
        }
        for (DexEncodedMethod method : clazz.virtualMethods()) {
          checkInterfaceMethod(method);
        }
      }
    }
  }

  // Ensures interface method comply with requirements imposed by Android runtime:
  //  -- in pre-N Android versions interfaces may only have class
  //     initializer and public abstract methods.
  //  -- starting with N interfaces may also have public or private
  //     static methods, as well as public non-abstract (default)
  //     and private instance methods.
  private void checkInterfaceMethod(DexEncodedMethod method) {
    if (application.dexItemFactory.isClassConstructor(method.method)) {
      return; // Class constructor is always OK.
    }
    if (method.accessFlags.isStatic()) {
      if (!options.canUseDefaultAndStaticInterfaceMethods()
          && !options.testing.allowStaticInterfaceMethodsForPreNApiLevel) {
        throw options.reporter.fatalError(
            new StaticInterfaceMethodDiagnostic(new MethodPosition(method.method)));
      }

    } else {
      if (method.isInstanceInitializer()) {
        throw new CompilationError(
            "Interface must not have constructors: " + method.method.toSourceString());
      }
      if (!method.accessFlags.isAbstract() && !method.accessFlags.isPrivate() &&
          !options.canUseDefaultAndStaticInterfaceMethods()) {
        throw options.reporter.fatalError(
            new DefaultInterfaceMethodDiagnostic(new MethodPosition(method.method)));
      }
    }

    if (method.accessFlags.isPrivate()) {
      if (options.canUsePrivateInterfaceMethods()) {
        return;
      }
      throw options.reporter.fatalError(
          new PrivateInterfaceMethodDiagnostic(new MethodPosition(method.method)));
    }

    if (!method.accessFlags.isPublic()) {
      throw new CompilationError("Interface methods must not be "
          + "protected or package private: " + method.method.toSourceString());
    }
  }

  private boolean verifyNames() {
    if (options.itemFactory.getSkipNameValidationForTesting()) {
      return true;
    }

    int apiLevel = options.minApiLevel;
    for (DexField field : mapping.getFields()) {
      assert field.name.isValidSimpleName(apiLevel);
    }
    for (DexMethod method : mapping.getMethods()) {
      assert method.name.isValidSimpleName(apiLevel);
    }
    for (DexType type : mapping.getTypes()) {
      if (type.isClassType()) {
        assert DexString.isValidSimpleName(apiLevel, type.getName());
        assert SyntheticNaming.verifyNotInternalSynthetic(type);
      }
    }

    return true;
  }

  private List<ProgramDexCode> sortDexCodesByClassName() {
    Map<ProgramDexCode, String> codeToSignatureMap = new IdentityHashMap<>();
    List<ProgramDexCode> codesSorted = new ArrayList<>();
    for (DexProgramClass clazz : mapping.getClasses()) {
      clazz.forEachProgramMethod(
          method -> {
            DexCode code = codeMapping.getCode(method.getDefinition());
            assert code != null || method.getDefinition().shouldNotHaveCode();
            if (code != null) {
              ProgramDexCode programCode = new ProgramDexCode(code, method);
              codesSorted.add(programCode);
              codeToSignatureMap.put(
                  programCode, getKeyForDexCodeSorting(method, application.getProguardMap()));
            }
          });
    }
    codesSorted.sort(Comparator.comparing(codeToSignatureMap::get));
    return codesSorted;
  }

  private static String getKeyForDexCodeSorting(ProgramMethod method, ClassNameMapper proguardMap) {
    // TODO(b/173999869): Could this instead compute sorting using dex items?
    Signature signature;
    String originalClassName;
    if (proguardMap != null) {
      signature = proguardMap.originalSignatureOf(method.getReference());
      originalClassName = proguardMap.originalNameOf(method.getHolderType());
    } else {
      signature = MethodSignature.fromDexMethod(method.getReference());
      originalClassName = method.getHolderType().toSourceString();
    }
    return originalClassName + signature;
  }

  private <T extends IndexedDexItem> void writeFixedSectionItems(
      Collection<T> items, int offset, Consumer<T> writer) {
    assert dest.position() == offset;
    for (T item : items) {
      writer.accept(item);
    }
  }

  private void writeFixedSectionItems(
      DexProgramClass[] items, int offset, Consumer<DexProgramClass> writer) {
    assert dest.position() == offset;
    for (DexProgramClass item : items) {
      writer.accept(item);
    }
  }

  private <T extends DexItem> void writeItems(Collection<T> items, Consumer<Integer> offsetSetter,
      Consumer<T> writer) {
    writeItems(items, offsetSetter, writer, 1);
  }

  private <T> void writeItems(
      Collection<T> items, Consumer<Integer> offsetSetter, Consumer<T> writer, int alignment) {
    if (items.isEmpty()) {
      offsetSetter.accept(0);
    } else {
      offsetSetter.accept(dest.align(alignment));
      items.forEach(writer);
    }
  }

  private int sizeOfCodeItems(Iterable<ProgramDexCode> codes) {
    int size = 0;
    for (ProgramDexCode code : codes) {
      size = alignSize(4, size);
      size += sizeOfCodeItem(code.getCode());
    }
    return size;
  }

  private int sizeOfCodeItem(DexCode code) {
    int result = 16;
    int insnSize = 0;
    for (Instruction insn : code.instructions) {
      insnSize += insn.getSize();
    }
    result += insnSize * 2;
    result += code.tries.length * 8;
    if (code.handlers.length > 0) {
      result = alignSize(4, result);
      result += LebUtils.sizeAsUleb128(code.handlers.length);
      for (TryHandler handler : code.handlers) {
        boolean hasCatchAll = handler.catchAllAddr != TryHandler.NO_HANDLER;
        result += LebUtils
            .sizeAsSleb128(hasCatchAll ? -handler.pairs.length : handler.pairs.length);
        for (TypeAddrPair pair : handler.pairs) {
          result += sizeAsUleb128(mapping.getOffsetFor(pair.getType(graphLens)));
          result += sizeAsUleb128(pair.addr);
        }
        if (hasCatchAll) {
          result += sizeAsUleb128(handler.catchAllAddr);
        }
      }
    }
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Computed size item %08d.", result);
    }
    return result;
  }

  private void writeStringItem(DexString string) {
    dest.putInt(mixedSectionOffsets.getOffsetFor(string));
  }

  private void writeTypeItem(DexType type) {
    DexString descriptor = namingLens.lookupDescriptor(type);
    dest.putInt(mapping.getOffsetFor(descriptor));
  }

  private void writeProtoItem(DexProto proto) {
    dest.putInt(mapping.getOffsetFor(proto.shorty));
    dest.putInt(mapping.getOffsetFor(proto.returnType));
    dest.putInt(mixedSectionOffsets.getOffsetFor(proto.parameters));
  }

  private void writeFieldItem(DexField field) {
    int classIdx = mapping.getOffsetFor(field.holder);
    assert (classIdx & 0xFFFF) == classIdx;
    dest.putShort((short) classIdx);
    int typeIdx = mapping.getOffsetFor(field.type);
    assert (typeIdx & 0xFFFF) == typeIdx;
    dest.putShort((short) typeIdx);
    DexString name = namingLens.lookupName(field);
    dest.putInt(mapping.getOffsetFor(name));
  }

  private void writeMethodItem(DexMethod method) {
    int classIdx = mapping.getOffsetFor(method.holder);
    assert (classIdx & 0xFFFF) == classIdx;
    dest.putShort((short) classIdx);
    int protoIdx = mapping.getOffsetFor(method.proto);
    assert (protoIdx & 0xFFFF) == protoIdx;
    dest.putShort((short) protoIdx);
    DexString name = namingLens.lookupName(method);
    dest.putInt(mapping.getOffsetFor(name));
  }

  private void writeClassDefItem(DexProgramClass clazz) {
    desugaredLibraryCodeToKeep.recordHierarchyOf(clazz);

    dest.putInt(mapping.getOffsetFor(clazz.type));
    dest.putInt(clazz.accessFlags.getAsDexAccessFlags());
    dest.putInt(
        clazz.superType == null ? Constants.NO_INDEX : mapping.getOffsetFor(clazz.superType));
    dest.putInt(mixedSectionOffsets.getOffsetFor(clazz.interfaces));
    dest.putInt(
        clazz.sourceFile == null ? Constants.NO_INDEX : mapping.getOffsetFor(clazz.sourceFile));
    dest.putInt(mixedSectionOffsets.getOffsetForAnnotationsDirectory(clazz));
    dest.putInt(
        clazz.hasMethodsOrFields() ? mixedSectionOffsets.getOffsetFor(clazz) : Constants.NO_OFFSET);
    dest.putInt(mixedSectionOffsets.getOffsetFor(staticFieldValues.get(clazz)));
  }

  private void writeDebugItem(DexDebugInfo debugInfo, GraphLens graphLens) {
    mixedSectionOffsets.setOffsetFor(debugInfo, dest.position());
    dest.putBytes(new DebugBytecodeWriter(debugInfo, mapping, graphLens).generate());
  }

  private void writeCodeItem(ProgramDexCode code) {
    writeCodeItem(code.getCode(), code.getMethod());
  }

  private void writeCodeItem(DexCode code, ProgramMethod method) {
    mixedSectionOffsets.setOffsetFor(code, dest.align(4));
    // Fixed size header information.
    dest.putShort((short) code.registerSize);
    dest.putShort((short) code.incomingRegisterSize);
    dest.putShort((short) code.outgoingRegisterSize);
    dest.putShort((short) code.tries.length);
    dest.putInt(mixedSectionOffsets.getOffsetFor(code.getDebugInfoForWriting()));
    // Jump over the size.
    int insnSizeOffset = dest.position();
    dest.forward(4);
    // Write instruction stream.
    dest.putInstructions(code, method, mapping, desugaredLibraryCodeToKeep);
    // Compute size and do the backward/forward dance to write the size at the beginning.
    int insnSize = dest.position() - insnSizeOffset - 4;
    dest.rewind(insnSize + 4);
    dest.putInt(insnSize / 2);
    dest.forward(insnSize);
    if (code.tries.length > 0) {
      // The tries need to be 4 byte aligned.
      int beginOfTriesOffset = dest.align(4);
      // First write the handlers, so that we know their mixedSectionOffsets.
      dest.forward(code.tries.length * 8);
      int beginOfHandlersOffset = dest.position();
      dest.putUleb128(code.handlers.length);
      short[] offsets = new short[code.handlers.length];
      int i = 0;
      for (TryHandler handler : code.handlers) {
        offsets[i++] = (short) (dest.position() - beginOfHandlersOffset);
        boolean hasCatchAll = handler.catchAllAddr != TryHandler.NO_HANDLER;
        dest.putSleb128(hasCatchAll ? -handler.pairs.length : handler.pairs.length);
        for (TypeAddrPair pair : handler.pairs) {
          dest.putUleb128(mapping.getOffsetFor(pair.getType(graphLens)));
          dest.putUleb128(pair.addr);
          desugaredLibraryCodeToKeep.recordClass(pair.getType(graphLens));
        }
        if (hasCatchAll) {
          dest.putUleb128(handler.catchAllAddr);
        }
      }
      int endOfCodeOffset = dest.position();
      // Now write the tries.
      dest.moveTo(beginOfTriesOffset);
      for (Try aTry : code.tries) {
        dest.putInt(aTry.startAddress);
        dest.putShort((short) aTry.instructionCount);
        dest.putShort(offsets[aTry.handlerIndex]);
      }
      // And move to the end.
      dest.moveTo(endOfCodeOffset);
    }
  }

  private void writeTypeList(DexTypeList list) {
    assert !list.isEmpty();
    mixedSectionOffsets.setOffsetFor(list, dest.align(4));
    DexType[] values = list.values;
    dest.putInt(values.length);
    for (DexType type : values) {
      dest.putShort((short) mapping.getOffsetFor(type));
    }
  }

  private void writeStringData(DexString string) {
    mixedSectionOffsets.setOffsetFor(string, dest.position());
    dest.putUleb128(string.size);
    dest.putBytes(string.content);
  }

  private void writeAnnotation(DexAnnotation annotation) {
    mixedSectionOffsets.setOffsetFor(annotation, dest.position());
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Writing Annotation @ 0x%08x.", dest.position());
    }
    dest.putByte((byte) annotation.visibility);
    writeEncodedAnnotation(annotation.annotation, dest, mapping);
  }

  private void writeAnnotationSet(DexAnnotationSet set) {
    mixedSectionOffsets.setOffsetFor(set, dest.align(4));
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Writing AnnotationSet @ 0x%08x.", dest.position());
    }
    List<DexAnnotation> annotations = new ArrayList<>(Arrays.asList(set.annotations));
    annotations.sort(
        (a, b) ->
            a.annotation.type.acceptCompareTo(b.annotation.type, mapping.getCompareToVisitor()));
    dest.putInt(annotations.size());
    for (DexAnnotation annotation : annotations) {
      dest.putInt(mixedSectionOffsets.getOffsetFor(annotation));
    }
  }

  private void writeAnnotationSetRefList(ParameterAnnotationsList parameterAnnotationsList) {
    assert !parameterAnnotationsList.isEmpty();
    mixedSectionOffsets.setOffsetFor(parameterAnnotationsList, dest.align(4));
    dest.putInt(parameterAnnotationsList.countNonMissing());
    for (int i = 0; i < parameterAnnotationsList.size(); i++) {
      if (parameterAnnotationsList.isMissing(i)) {
        // b/62300145: Maintain broken ParameterAnnotations attribute by only outputting the
        // non-missing annotation lists.
        continue;
      }
      dest.putInt(mixedSectionOffsets.getOffsetFor(parameterAnnotationsList.get(i)));
    }
  }

  private <D extends DexEncodedMember<D, R>, R extends DexMember<D, R>> void writeMemberAnnotations(
      List<D> items, ToIntFunction<D> getter) {
    for (D item : items) {
      dest.putInt(item.getReference().getOffset(mapping));
      dest.putInt(getter.applyAsInt(item));
    }
  }

  private void writeAnnotationDirectory(DexAnnotationDirectory annotationDirectory) {
    mixedSectionOffsets.setOffsetForAnnotationsDirectory(annotationDirectory, dest.align(4));
    dest.putInt(mixedSectionOffsets.getOffsetFor(annotationDirectory.getClazzAnnotations()));
    List<DexEncodedMethod> methodAnnotations =
        annotationDirectory.sortMethodAnnotations(mapping.getCompareToVisitor());
    List<DexEncodedMethod> parameterAnnotations =
        annotationDirectory.sortParameterAnnotations(mapping.getCompareToVisitor());
    List<DexEncodedField> fieldAnnotations =
        annotationDirectory.sortFieldAnnotations(mapping.getCompareToVisitor());
    dest.putInt(fieldAnnotations.size());
    dest.putInt(methodAnnotations.size());
    dest.putInt(parameterAnnotations.size());
    writeMemberAnnotations(
        fieldAnnotations, item -> mixedSectionOffsets.getOffsetFor(item.annotations()));
    writeMemberAnnotations(
        methodAnnotations, item -> mixedSectionOffsets.getOffsetFor(item.annotations()));
    writeMemberAnnotations(parameterAnnotations,
        item -> mixedSectionOffsets.getOffsetFor(item.parameterAnnotationsList));
  }

  private void writeEncodedFields(List<DexEncodedField> unsortedFields) {
    List<DexEncodedField> fields = new ArrayList<>(unsortedFields);
    fields.sort((a, b) -> a.field.acceptCompareTo(b.field, mapping.getCompareToVisitor()));
    int currentOffset = 0;
    for (DexEncodedField field : fields) {
      assert field.validateDexValue(application.dexItemFactory);
      int nextOffset = mapping.getOffsetFor(field.field);
      assert nextOffset - currentOffset >= 0;
      dest.putUleb128(nextOffset - currentOffset);
      currentOffset = nextOffset;
      dest.putUleb128(field.accessFlags.getAsDexAccessFlags());
      desugaredLibraryCodeToKeep.recordField(field.field);
    }
  }

  private void writeEncodedMethods(
      Iterable<DexEncodedMethod> unsortedMethods, boolean isSharedSynthetic) {
    List<DexEncodedMethod> methods = IterableUtils.toNewArrayList(unsortedMethods);
    methods.sort((a, b) -> a.method.acceptCompareTo(b.method, mapping.getCompareToVisitor()));
    int currentOffset = 0;
    for (DexEncodedMethod method : methods) {
      int nextOffset = mapping.getOffsetFor(method.method);
      assert nextOffset - currentOffset >= 0;
      dest.putUleb128(nextOffset - currentOffset);
      currentOffset = nextOffset;
      dest.putUleb128(method.accessFlags.getAsDexAccessFlags());
      DexCode code = codeMapping.getCode(method);
      desugaredLibraryCodeToKeep.recordMethod(method.method);
      if (code == null) {
        assert method.shouldNotHaveCode();
        dest.putUleb128(0);
      } else {
        dest.putUleb128(mixedSectionOffsets.getOffsetFor(code));
        // Writing the methods starts to take up memory so we are going to flush the
        // code objects since they are no longer necessary after this.
        codeMapping.clearCode(method, isSharedSynthetic);
      }
    }
  }

  private void writeClassData(DexProgramClass clazz) {
    assert clazz.hasMethodsOrFields();
    mixedSectionOffsets.setOffsetFor(clazz, dest.position());
    dest.putUleb128(clazz.staticFields().size());
    dest.putUleb128(clazz.instanceFields().size());
    dest.putUleb128(clazz.getMethodCollection().numberOfDirectMethods());
    dest.putUleb128(clazz.getMethodCollection().numberOfVirtualMethods());
    writeEncodedFields(clazz.staticFields());
    writeEncodedFields(clazz.instanceFields());
    boolean isSharedSynthetic = clazz.getSynthesizedFrom().size() > 1;
    writeEncodedMethods(clazz.directMethods(), isSharedSynthetic);
    writeEncodedMethods(clazz.virtualMethods(), isSharedSynthetic);
  }

  private void addStaticFieldValues(DexProgramClass clazz) {
    // We have collected the individual components of this array due to the data stored in
    // DexEncodedField#staticValues. However, we have to collect the DexEncodedArray itself
    // here.
    DexEncodedArray staticValues = clazz.computeStaticValuesArray(namingLens);
    if (staticValues != null) {
      staticFieldValues.put(clazz, staticValues);
      mixedSectionOffsets.add(staticValues);
    }
  }

  private void writeMethodHandle(DexMethodHandle methodHandle) {
    checkThatInvokeCustomIsAllowed();
    MethodHandleType methodHandleDexType;
    switch (methodHandle.type) {
      case INVOKE_SUPER:
        methodHandleDexType = MethodHandleType.INVOKE_DIRECT;
        break;
      default:
        methodHandleDexType = methodHandle.type;
        break;
    }
    assert dest.isAligned(4);
    dest.putShort(methodHandleDexType.getValue());
    dest.putShort((short) 0); // unused
    int fieldOrMethodIdx;
    if (methodHandle.isMethodHandle()) {
      fieldOrMethodIdx = mapping.getOffsetFor(methodHandle.asMethod());
    } else {
      assert methodHandle.isFieldHandle();
      fieldOrMethodIdx = mapping.getOffsetFor(methodHandle.asField());
    }
    assert (fieldOrMethodIdx & 0xFFFF) == fieldOrMethodIdx;
    dest.putShort((short) fieldOrMethodIdx);
    dest.putShort((short) 0); // unused
  }

  private void writeCallSite(DexCallSite callSite) {
    checkThatInvokeCustomIsAllowed();
    assert dest.isAligned(4);
    dest.putInt(mixedSectionOffsets.getOffsetFor(callSite.getEncodedArray()));
  }

  private void writeEncodedArray(DexEncodedArray array) {
    mixedSectionOffsets.setOffsetFor(array, dest.position());
    if (Log.ENABLED) {
      Log.verbose(getClass(), "Writing EncodedArray @ 0x%08x [%s].", dest.position(), array);
    }
    dest.putUleb128(array.values.length);
    for (DexValue value : array.values) {
      value.writeTo(dest, mapping);
    }
  }

  private int writeMapItem(int type, int offset, int length) {
    if (length == 0) {
      return 0;
    }
    if (Log.ENABLED) {
      Log.debug(getClass(), "Map entry 0x%04x @ 0x%08x # %08d.", type, offset, length);
    }
    dest.putShort((short) type);
    dest.putShort((short) 0);
    dest.putInt(length);
    dest.putInt(offset);
    return 1;
  }

  private void writeMap(Layout layout) {
    int startOfMap = dest.align(4);
    dest.forward(4); // Leave space for size;
    int size = 0;
    size += writeMapItem(Constants.TYPE_HEADER_ITEM, 0, 1);
    size += writeMapItem(Constants.TYPE_STRING_ID_ITEM, layout.stringIdsOffset,
        mapping.getStrings().size());
    size += writeMapItem(Constants.TYPE_TYPE_ID_ITEM, layout.typeIdsOffset,
        mapping.getTypes().size());
    size += writeMapItem(Constants.TYPE_PROTO_ID_ITEM, layout.protoIdsOffset,
        mapping.getProtos().size());
    size += writeMapItem(Constants.TYPE_FIELD_ID_ITEM, layout.fieldIdsOffset,
        mapping.getFields().size());
    size += writeMapItem(Constants.TYPE_METHOD_ID_ITEM, layout.methodIdsOffset,
        mapping.getMethods().size());
    size += writeMapItem(Constants.TYPE_CLASS_DEF_ITEM, layout.classDefsOffset,
        mapping.getClasses().length);
    size += writeMapItem(Constants.TYPE_CALL_SITE_ID_ITEM, layout.callSiteIdsOffset,
        mapping.getCallSites().size());
    size += writeMapItem(Constants.TYPE_METHOD_HANDLE_ITEM, layout.methodHandleIdsOffset,
        mapping.getMethodHandles().size());
    size += writeMapItem(Constants.TYPE_CODE_ITEM, layout.getCodesOffset(),
        mixedSectionOffsets.getCodes().size());
    size += writeMapItem(Constants.TYPE_DEBUG_INFO_ITEM, layout.getDebugInfosOffset(),
        mixedSectionOffsets.getDebugInfos().size());
    size += writeMapItem(Constants.TYPE_TYPE_LIST, layout.getTypeListsOffset(),
        mixedSectionOffsets.getTypeLists().size());
    size += writeMapItem(Constants.TYPE_STRING_DATA_ITEM, layout.getStringDataOffsets(),
        mixedSectionOffsets.getStringData().size());
    size += writeMapItem(Constants.TYPE_ANNOTATION_ITEM, layout.getAnnotationsOffset(),
        mixedSectionOffsets.getAnnotations().size());
    size += writeMapItem(Constants.TYPE_CLASS_DATA_ITEM, layout.getClassDataOffset(),
        mixedSectionOffsets.getClassesWithData().size());
    size += writeMapItem(Constants.TYPE_ENCODED_ARRAY_ITEM, layout.getEncodedArrarysOffset(),
        mixedSectionOffsets.getEncodedArrays().size());
    size += writeMapItem(Constants.TYPE_ANNOTATION_SET_ITEM, layout.getAnnotationSetsOffset(),
        mixedSectionOffsets.getAnnotationSets().size());
    size += writeMapItem(Constants.TYPE_ANNOTATION_SET_REF_LIST,
        layout.getAnnotationSetRefListsOffset(),
        mixedSectionOffsets.getAnnotationSetRefLists().size());
    size += writeMapItem(Constants.TYPE_ANNOTATIONS_DIRECTORY_ITEM,
        layout.getAnnotationDirectoriesOffset(),
        mixedSectionOffsets.getAnnotationDirectories().size());
    size += writeMapItem(Constants.TYPE_MAP_LIST, layout.getMapOffset(), 1);
    dest.moveTo(startOfMap);
    dest.putInt(size);
    dest.forward(size * Constants.TYPE_MAP_LIST_ITEM_SIZE);
  }

  private void writeHeader(Layout layout) {
    dest.moveTo(0);
    dest.putBytes(Constants.DEX_FILE_MAGIC_PREFIX);
    dest.putBytes(
        options.testing.forceDexVersionBytes != null
            ? options.testing.forceDexVersionBytes
            : DexVersion.getDexVersion(AndroidApiLevel.getAndroidApiLevel(options.minApiLevel))
                .getBytes());
    dest.putByte(Constants.DEX_FILE_MAGIC_SUFFIX);
    // Leave out checksum and signature for now.
    dest.moveTo(Constants.FILE_SIZE_OFFSET);
    dest.putInt(layout.getEndOfFile());
    dest.putInt(Constants.TYPE_HEADER_ITEM_SIZE);
    dest.putInt(Constants.ENDIAN_CONSTANT);
    dest.putInt(0);
    dest.putInt(0);
    dest.putInt(layout.getMapOffset());
    int numberOfStrings = mapping.getStrings().size();
    dest.putInt(numberOfStrings);
    dest.putInt(numberOfStrings == 0 ? 0 : layout.stringIdsOffset);
    int numberOfTypes = mapping.getTypes().size();
    dest.putInt(numberOfTypes);
    dest.putInt(numberOfTypes == 0 ? 0 : layout.typeIdsOffset);
    int numberOfProtos = mapping.getProtos().size();
    dest.putInt(numberOfProtos);
    dest.putInt(numberOfProtos == 0 ? 0 : layout.protoIdsOffset);
    int numberOfFields = mapping.getFields().size();
    dest.putInt(numberOfFields);
    dest.putInt(numberOfFields == 0 ? 0 : layout.fieldIdsOffset);
    int numberOfMethods = mapping.getMethods().size();
    dest.putInt(numberOfMethods);
    dest.putInt(numberOfMethods == 0 ? 0 : layout.methodIdsOffset);
    int numberOfClasses = mapping.getClasses().length;
    dest.putInt(numberOfClasses);
    dest.putInt(numberOfClasses == 0 ? 0 : layout.classDefsOffset);
    dest.putInt(layout.getDataSectionSize());
    dest.putInt(layout.dataSectionOffset);
    assert dest.position() == layout.stringIdsOffset;
  }

  private void writeSignature(Layout layout) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-1");
      md.update(dest.asArray(), Constants.FILE_SIZE_OFFSET,
          layout.getEndOfFile() - Constants.FILE_SIZE_OFFSET);
      md.digest(dest.asArray(), Constants.SIGNATURE_OFFSET, 20);
    } catch (Exception e) {
      throw new RuntimeException(e);
    }
  }

  private void writeChecksum(Layout layout) {
    Adler32 adler = new Adler32();
    adler.update(dest.asArray(), Constants.SIGNATURE_OFFSET,
        layout.getEndOfFile() - Constants.SIGNATURE_OFFSET);
    dest.moveTo(Constants.CHECKSUM_OFFSET);
    dest.putInt((int) adler.getValue());
  }

  private int alignSize(int bytes, int value) {
    int mask = bytes - 1;
    return (value + mask) & ~mask;
  }

  private static class Layout {

    private static final int NOT_SET = -1;

    // Fixed size constant pool sections
    final int stringIdsOffset;
    final int typeIdsOffset;
    final int protoIdsOffset;
    final int fieldIdsOffset;
    final int methodIdsOffset;
    final int classDefsOffset;
    final int callSiteIdsOffset;
    final int methodHandleIdsOffset;
    final int dataSectionOffset;

    // Mixed size sections
    private int codesOffset = NOT_SET; // aligned
    private int debugInfosOffset = NOT_SET;

    private int typeListsOffset = NOT_SET; // aligned
    private int stringDataOffsets = NOT_SET;
    private int annotationsOffset = NOT_SET;
    private int annotationSetsOffset = NOT_SET; // aligned
    private int annotationSetRefListsOffset = NOT_SET; // aligned
    private int annotationDirectoriesOffset = NOT_SET; // aligned
    private int classDataOffset = NOT_SET;
    private int encodedArrarysOffset = NOT_SET;
    private int mapOffset = NOT_SET;
    private int endOfFile = NOT_SET;

    private Layout(int stringIdsOffset, int typeIdsOffset, int protoIdsOffset, int fieldIdsOffset,
        int methodIdsOffset, int classDefsOffset, int callSiteIdsOffset, int methodHandleIdsOffset,
        int dataSectionOffset) {
      this.stringIdsOffset = stringIdsOffset;
      this.typeIdsOffset = typeIdsOffset;
      this.protoIdsOffset = protoIdsOffset;
      this.fieldIdsOffset = fieldIdsOffset;
      this.methodIdsOffset = methodIdsOffset;
      this.classDefsOffset = classDefsOffset;
      this.callSiteIdsOffset = callSiteIdsOffset;
      this.methodHandleIdsOffset = methodHandleIdsOffset;
      this.dataSectionOffset = dataSectionOffset;
      assert stringIdsOffset <= typeIdsOffset;
      assert typeIdsOffset <= protoIdsOffset;
      assert protoIdsOffset <= fieldIdsOffset;
      assert fieldIdsOffset <= methodIdsOffset;
      assert methodIdsOffset <= classDefsOffset;
      assert classDefsOffset <= dataSectionOffset;
      assert callSiteIdsOffset <= dataSectionOffset;
      assert methodHandleIdsOffset <= dataSectionOffset;
    }

    static Layout from(ObjectToOffsetMapping mapping) {
      int offset = 0;
      return new Layout(
          offset = Constants.TYPE_HEADER_ITEM_SIZE,
          offset += mapping.getStrings().size() * Constants.TYPE_STRING_ID_ITEM_SIZE,
          offset += mapping.getTypes().size() * Constants.TYPE_TYPE_ID_ITEM_SIZE,
          offset += mapping.getProtos().size() * Constants.TYPE_PROTO_ID_ITEM_SIZE,
          offset += mapping.getFields().size() * Constants.TYPE_FIELD_ID_ITEM_SIZE,
          offset += mapping.getMethods().size() * Constants.TYPE_METHOD_ID_ITEM_SIZE,
          offset += mapping.getClasses().length * Constants.TYPE_CLASS_DEF_ITEM_SIZE,
          offset += mapping.getCallSites().size() * Constants.TYPE_CALL_SITE_ID_ITEM_SIZE,
          offset += mapping.getMethodHandles().size() * Constants.TYPE_METHOD_HANDLE_ITEM_SIZE);
    }

    int getDataSectionSize() {
      int size = getEndOfFile() - dataSectionOffset;
      assert size % 4 == 0;
      return size;
    }

    private boolean isValidOffset(int value, boolean isAligned) {
      return value != NOT_SET && (!isAligned || value % 4 == 0);
    }

    public int getCodesOffset() {
      assert isValidOffset(codesOffset, true);
      return codesOffset;
    }

    public void setCodesOffset(int codesOffset) {
      assert this.codesOffset == NOT_SET;
      this.codesOffset = codesOffset;
    }

    public int getDebugInfosOffset() {
      assert isValidOffset(debugInfosOffset, false);
      return debugInfosOffset;
    }

    public void setDebugInfosOffset(int debugInfosOffset) {
      assert this.debugInfosOffset == NOT_SET;
      this.debugInfosOffset = debugInfosOffset;
    }

    public int getTypeListsOffset() {
      assert isValidOffset(typeListsOffset, true);
      return typeListsOffset;
    }

    public void setTypeListsOffset(int typeListsOffset) {
      assert this.typeListsOffset == NOT_SET;
      this.typeListsOffset = typeListsOffset;
    }

    public int getStringDataOffsets() {
      assert isValidOffset(stringDataOffsets, false);
      return stringDataOffsets;
    }

    public void setStringDataOffsets(int stringDataOffsets) {
      assert this.stringDataOffsets == NOT_SET;
      this.stringDataOffsets = stringDataOffsets;
    }

    public int getAnnotationsOffset() {
      assert isValidOffset(annotationsOffset, false);
      return annotationsOffset;
    }

    public void setAnnotationsOffset(int annotationsOffset) {
      assert this.annotationsOffset == NOT_SET;
      this.annotationsOffset = annotationsOffset;
    }

    public int getAnnotationSetsOffset() {
      assert isValidOffset(annotationSetsOffset, true);
      return annotationSetsOffset;
    }

    public void alreadySetOffset(int ignored) {
      // Intentionally empty.
    }

    public void setAnnotationSetsOffset(int annotationSetsOffset) {
      assert this.annotationSetsOffset == NOT_SET;
      this.annotationSetsOffset = annotationSetsOffset;
    }

    public int getAnnotationSetRefListsOffset() {
      assert isValidOffset(annotationSetRefListsOffset, true);
      return annotationSetRefListsOffset;
    }

    public void setAnnotationSetRefListsOffset(int annotationSetRefListsOffset) {
      assert this.annotationSetRefListsOffset == NOT_SET;
      this.annotationSetRefListsOffset = annotationSetRefListsOffset;
    }

    public int getAnnotationDirectoriesOffset() {
      assert isValidOffset(annotationDirectoriesOffset, true);
      return annotationDirectoriesOffset;
    }

    public void setAnnotationDirectoriesOffset(int annotationDirectoriesOffset) {
      assert this.annotationDirectoriesOffset == NOT_SET;
      this.annotationDirectoriesOffset = annotationDirectoriesOffset;
    }

    public int getClassDataOffset() {
      assert isValidOffset(classDataOffset, false);
      return classDataOffset;
    }

    public void setClassDataOffset(int classDataOffset) {
      assert this.classDataOffset == NOT_SET;
      this.classDataOffset = classDataOffset;
    }

    public int getEncodedArrarysOffset() {
      assert isValidOffset(encodedArrarysOffset, false);
      return encodedArrarysOffset;
    }

    public void setEncodedArrarysOffset(int encodedArrarysOffset) {
      assert this.encodedArrarysOffset == NOT_SET;
      this.encodedArrarysOffset = encodedArrarysOffset;
    }

    public int getMapOffset() {
      return mapOffset;
    }

    public void setMapOffset(int mapOffset) {
      this.mapOffset = mapOffset;
    }

    public int getEndOfFile() {
      return endOfFile;
    }

    public void setEndOfFile(int endOfFile) {
      this.endOfFile = endOfFile;
    }
  }

  /**
   * Encapsulates information on the offsets of items in the sections of the mixed data part of the
   * DEX file. Initially, items are collected using the {@link MixedSectionCollection} traversal and
   * all offsets are unset. When writing a section, the offsets of the written items are stored.
   * These offsets are then used to resolve cross-references between items from different sections
   * into a file offset.
   */
  private static class MixedSectionOffsets extends MixedSectionCollection {

    private static final int NOT_SET = -1;
    private static final int NOT_KNOWN = -2;

    private final MethodToCodeObjectMapping codeMapping;

    private final Reference2IntMap<DexCode> codes = createReference2IntMap();
    private final Object2IntMap<DexDebugInfo> debugInfos = createObject2IntMap();
    private final Object2IntMap<DexTypeList> typeLists = createObject2IntMap();
    private final Reference2IntMap<DexString> stringData = createReference2IntMap();
    private final Object2IntMap<DexAnnotation> annotations = createObject2IntMap();
    private final Object2IntMap<DexAnnotationSet> annotationSets = createObject2IntMap();
    private final Object2IntMap<ParameterAnnotationsList> annotationSetRefLists
        = createObject2IntMap();
    private final Object2IntMap<DexAnnotationDirectory> annotationDirectories
        = createObject2IntMap();
    private final Object2IntMap<DexProgramClass> classesWithData = createObject2IntMap();
    private final Object2IntMap<DexEncodedArray> encodedArrays = createObject2IntMap();
    private final Map<DexProgramClass, DexAnnotationDirectory> clazzToAnnotationDirectory
        = new HashMap<>();

    private final int minApiLevel;

    private static <T> Object2IntMap<T> createObject2IntMap() {
      Object2IntMap<T> result = new Object2IntLinkedOpenHashMap<>();
      result.defaultReturnValue(NOT_KNOWN);
      return result;
    }

    private static <T> Reference2IntMap<T> createReference2IntMap() {
      Reference2IntMap<T> result = new Reference2IntLinkedOpenHashMap<>();
      result.defaultReturnValue(NOT_KNOWN);
      return result;
    }

    private MixedSectionOffsets(InternalOptions options, MethodToCodeObjectMapping codeMapping) {
      this.minApiLevel = options.minApiLevel;
      this.codeMapping = codeMapping;
    }

    private <T> boolean add(Object2IntMap<T> map, T item) {
      if (!map.containsKey(item)) {
        map.put(item, NOT_SET);
        return true;
      }
      return false;
    }

    private <T> boolean add(Reference2IntMap<T> map, T item) {
      if (!map.containsKey(item)) {
        map.put(item, NOT_SET);
        return true;
      }
      return false;
    }

    @Override
    public boolean add(DexProgramClass aClassWithData) {
      return add(classesWithData, aClassWithData);
    }

    @Override
    public boolean add(DexEncodedArray encodedArray) {
      return add(encodedArrays, encodedArray);
    }

    @Override
    public boolean add(DexAnnotationSet annotationSet) {
      // Until we fully drop support for API levels < 17, we have to emit an empty annotation set to
      // work around a DALVIK bug. See b/36951668.
      if ((minApiLevel >= AndroidApiLevel.J_MR1.getLevel()) && annotationSet.isEmpty()) {
        return false;
      }
      return add(annotationSets, annotationSet);
    }

    @Override
    public void visit(DexEncodedMethod method) {
      method.collectMixedSectionItemsWithCodeMapping(this, codeMapping);
    }

    @Override
    public boolean add(DexCode code) {
      return add(codes, code);
    }

    @Override
    public boolean add(DexDebugInfo debugInfo) {
      return add(debugInfos, debugInfo);
    }

    @Override
    public boolean add(DexTypeList typeList) {
      if (typeList.isEmpty()) {
        return false;
      }
      return add(typeLists, typeList);
    }

    @Override
    public boolean add(ParameterAnnotationsList annotationSetRefList) {
      if (annotationSetRefList.isEmpty()) {
        return false;
      }
      return add(annotationSetRefLists, annotationSetRefList);
    }

    @Override
    public boolean add(DexAnnotation annotation) {
      return add(annotations, annotation);
    }

    @Override
    public boolean setAnnotationsDirectoryForClass(DexProgramClass clazz,
        DexAnnotationDirectory annotationDirectory) {
      DexAnnotationDirectory previous = clazzToAnnotationDirectory.put(clazz, annotationDirectory);
      assert previous == null;
      return add(annotationDirectories, annotationDirectory);
    }

    public boolean add(DexString string) {
      return add(stringData, string);
    }

    public Collection<DexCode> getCodes() {
      return codes.keySet();
    }

    public Collection<DexDebugInfo> getDebugInfos() {
      return debugInfos.keySet();
    }

    public Collection<DexTypeList> getTypeLists() {
      return typeLists.keySet();
    }

    public Collection<DexString> getStringData() {
      return stringData.keySet();
    }

    public Collection<DexAnnotation> getAnnotations() {
      return annotations.keySet();
    }

    public Collection<DexAnnotationSet> getAnnotationSets() {
      return annotationSets.keySet();
    }

    public Collection<ParameterAnnotationsList> getAnnotationSetRefLists() {
      return annotationSetRefLists.keySet();
    }

    public Collection<DexProgramClass> getClassesWithData() {
      return classesWithData.keySet();
    }

    public Collection<DexAnnotationDirectory> getAnnotationDirectories() {
      return annotationDirectories.keySet();
    }

    public Collection<DexEncodedArray> getEncodedArrays() {
      return encodedArrays.keySet();
    }

    private <T> int lookup(T item, Object2IntMap<T> table) {
      if (item == null) {
        return Constants.NO_OFFSET;
      }
      int offset = table.getInt(item);
      assert offset != NOT_SET && offset != NOT_KNOWN;
      return offset;
    }

    private <T> int lookup(T item, Reference2IntMap<T> table) {
      if (item == null) {
        return Constants.NO_OFFSET;
      }
      int offset = table.getInt(item);
      assert offset != NOT_SET && offset != NOT_KNOWN;
      return offset;
    }

    public int getOffsetFor(DexString item) {
      return lookup(item, stringData);
    }

    public int getOffsetFor(DexTypeList parameters) {
      if (parameters.isEmpty()) {
        return 0;
      }
      return lookup(parameters, typeLists);
    }

    public int getOffsetFor(DexProgramClass aClassWithData) {
      return lookup(aClassWithData, classesWithData);
    }

    public int getOffsetFor(DexEncodedArray encodedArray) {
      return lookup(encodedArray, encodedArrays);
    }

    public int getOffsetFor(DexDebugInfo debugInfo) {
      return lookup(debugInfo, debugInfos);
    }


    public int getOffsetForAnnotationsDirectory(DexProgramClass clazz) {
      if (!clazz.hasClassOrMemberAnnotations()) {
        return Constants.NO_OFFSET;
      }
      int offset = annotationDirectories.getInt(clazzToAnnotationDirectory.get(clazz));
      assert offset != NOT_KNOWN;
      return offset;
    }

    public int getOffsetFor(DexAnnotation annotation) {
      return lookup(annotation, annotations);
    }

    public int getOffsetFor(DexAnnotationSet annotationSet) {
      // Until we fully drop support for API levels < 17, we have to emit an empty annotation set to
      // work around a DALVIK bug. See b/36951668.
      if ((minApiLevel >= AndroidApiLevel.J_MR1.getLevel()) && annotationSet.isEmpty()) {
        return 0;
      }
      return lookup(annotationSet, annotationSets);
    }

    public int getOffsetFor(ParameterAnnotationsList annotationSetRefList) {
      if (annotationSetRefList.isEmpty()) {
        return 0;
      }
      return lookup(annotationSetRefList, annotationSetRefLists);
    }

    public int getOffsetFor(DexCode code) {
      return lookup(code, codes);
    }

    private <T> void setOffsetFor(T item, int offset, Object2IntMap<T> map) {
      int old = map.put(item, offset);
      assert old <= NOT_SET;
    }

    private <T> void setOffsetFor(T item, int offset, Reference2IntMap<T> map) {
      int old = map.put(item, offset);
      assert old <= NOT_SET;
    }

    void setOffsetFor(DexDebugInfo debugInfo, int offset) {
      setOffsetFor(debugInfo, offset, debugInfos);
    }

    void setOffsetFor(DexCode code, int offset) {
      setOffsetFor(code, offset, codes);
    }

    void setOffsetFor(DexTypeList typeList, int offset) {
      assert offset != 0 && !typeLists.isEmpty();
      setOffsetFor(typeList, offset, typeLists);
    }

    void setOffsetFor(DexString string, int offset) {
      setOffsetFor(string, offset, stringData);
    }

    void setOffsetFor(DexAnnotation annotation, int offset) {
      setOffsetFor(annotation, offset, annotations);
    }

    void setOffsetFor(DexAnnotationSet annotationSet, int offset) {
      // Until we fully drop support for API levels < 17, we have to emit an empty annotation set to
      // work around a DALVIK bug. See b/36951668.
      assert (minApiLevel < AndroidApiLevel.J_MR1.getLevel()) || !annotationSet.isEmpty();
      setOffsetFor(annotationSet, offset, annotationSets);
    }

    void setOffsetForAnnotationsDirectory(DexAnnotationDirectory annotationDirectory, int offset) {
      setOffsetFor(annotationDirectory, offset, annotationDirectories);
    }

    void setOffsetFor(DexProgramClass aClassWithData, int offset) {
      setOffsetFor(aClassWithData, offset, classesWithData);
    }

    void setOffsetFor(DexEncodedArray encodedArray, int offset) {
      setOffsetFor(encodedArray, offset, encodedArrays);
    }

    void setOffsetFor(ParameterAnnotationsList annotationSetRefList, int offset) {
      assert offset != 0 && !annotationSetRefList.isEmpty();
      setOffsetFor(annotationSetRefList, offset, annotationSetRefLists);
    }
  }

  private class ProgramClassDependencyCollector extends ProgramClassVisitor {

    private final Set<DexClass> includedClasses = Sets.newIdentityHashSet();

    ProgramClassDependencyCollector(DexApplication application, DexProgramClass[] includedClasses) {
      super(application);
      Collections.addAll(this.includedClasses, includedClasses);
    }

    @Override
    public void visit(DexType type) {
      // Intentionally left empty.
    }

    @Override
    public void visit(DexClass clazz) {
      // Only visit classes that are part of the current file.
      if (!includedClasses.contains(clazz)) {
        return;
      }
      clazz.addDependencies(mixedSectionOffsets);
    }
  }

  private void checkThatInvokeCustomIsAllowed() {
    if (!options.canUseInvokeCustom()) {
      throw options.reporter.fatalError(new InvokeCustomDiagnostic());
    }
  }
}
