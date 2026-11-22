package dev.turboism.adapter.cubism.exportperf;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Patches MOC3 assembly tables, cloner lookup scans, and {@code util.c.b} byte writing across all
 * supported Cubism versions using structural bytecode matching.
 */
final class MocBuilderPatcher {

    static final String UTIL_BYTE_STREAM_TARGET = "com/live2d/util/c/b";
    private static final String EXPORTER_PKG_PREFIX = "com/live2d/cubism/doc/model/exporter/";
    private static final String HELPER_INTERNAL = Type.getInternalName(MocExportIndexAccelerator.class);
    private static final String UTIL_L_OWNER = "com/live2d/util/l";
    private static final String UTIL_K_OWNER = "com/live2d/util/k";
    private static final String BYTE_ARRAY_OUT_INTERNAL = "java/io/ByteArrayOutputStream";
    private static final String ASSERTIONS_OWNER = "kotlin/_Assertions";
    private static final String ASSERTIONS_FIELD = "ENABLED";
    private static final String ROOT_DEFORMER_ANCHOR = "__root_deformer_for_model_export__";
    private static final String EXPORT_FORMAT_DESC = "(Lcom/live2d/cubism/doc/model/exporter/CMocExportFormat;)V";
    private static final String ARRAYLIST_DESC = "Ljava/util/ArrayList;";
    private static final String MAP_DESC = "Ljava/util/Map;";
    private static final String RETURN_LIST_SUFFIX = ")Ljava/util/List;";
    private static final String THROWABLE_INTERNAL = "java/lang/Throwable";
    private static final int MAX_EXPORTER_SIMPLE_NAME_LENGTH = 4;
    private static final int MAP_FOREACH_PROBE_WINDOW = 12;

    private MocBuilderPatcher() {}

    static boolean isSupportedTarget(final String internalName) {
        return UTIL_BYTE_STREAM_TARGET.equals(internalName)
                || (internalName != null && internalName.startsWith(EXPORTER_PKG_PREFIX));
    }

    static byte[] patch(final String internalName, final byte[] classBytes) {
        if (UTIL_BYTE_STREAM_TARGET.equals(internalName)) {
            return patchUtilByteStream(classBytes);
        }
        if (internalName == null || !internalName.startsWith(EXPORTER_PKG_PREFIX)) {
            return null;
        }
        final String simpleName = internalName.substring(EXPORTER_PKG_PREFIX.length());
        if (simpleName.length() > MAX_EXPORTER_SIMPLE_NAME_LENGTH) {
            return null;
        }
        final ClassReader reader = new ClassReader(classBytes);
        final ExporterScan scan = new ExporterScan(reader.getClassName());
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (!scan.needsTransform()) {
            return null;
        }

        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new ExporterTransformVisitor(writer, reader.getClassName(), scan), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static byte[] patchUtilByteStream(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!UTIL_BYTE_STREAM_TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ByteStreamScan scan = new ByteStreamScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.hasInit || !scan.hasWriteBytes) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String sig,
                            final String[] ex) {
                        final MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
                        if ("<init>".equals(name) && "()V".equals(desc) && scan.hasNewBaosPattern) {
                            return new ByteStreamInitVisitor(mv);
                        }
                        if ("a".equals(name) && "([B)V".equals(desc)) {
                            writeBulkByteArrayBody(mv);
                            return null;
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static void writeBulkByteArrayBody(final MethodVisitor mv) {
        if (mv == null) {
            return;
        }
        mv.visitCode();
        mv.visitVarInsn(ALOAD, 1);
        mv.visitLdcInsn("");
        mv.visitMethodInsn(
                INVOKESTATIC,
                "kotlin/jvm/internal/Intrinsics",
                "checkNotNullParameter",
                "(Ljava/lang/Object;Ljava/lang/String;)V",
                false);
        mv.visitVarInsn(ALOAD, 0);
        mv.visitFieldInsn(GETFIELD, UTIL_BYTE_STREAM_TARGET, "b", "Ljava/io/ByteArrayOutputStream;");
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ICONST_0);
        mv.visitVarInsn(ALOAD, 1);
        mv.visitInsn(ARRAYLENGTH);
        mv.visitMethodInsn(INVOKEVIRTUAL, BYTE_ARRAY_OUT_INTERNAL, "write", "([BII)V", false);
        mv.visitInsn(RETURN);
        mv.visitMaxs(4, 2);
        mv.visitEnd();
    }

    private static final class ByteStreamScan extends ClassVisitor {
        private boolean alreadyPatched;
        private boolean hasInit;
        private boolean hasWriteBytes;
        private boolean hasNewBaosPattern;

        ByteStreamScan() {
            super(ASM9);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final boolean isInit = "<init>".equals(name) && "()V".equals(desc);
            final boolean isWriteBytes = "a".equals(name) && "([B)V".equals(desc);
            if (isInit) {
                hasInit = true;
            }
            if (isWriteBytes) {
                hasWriteBytes = true;
            }
            return new MethodVisitor(ASM9) {
                private int patternState;

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    if (isInit && opcode == NEW && BYTE_ARRAY_OUT_INTERNAL.equals(type)) {
                        patternState = 1;
                    } else {
                        patternState = 0;
                    }
                }

                @Override
                public void visitInsn(final int opcode) {
                    if (isInit && patternState == 1 && opcode == DUP) {
                        patternState = 2;
                    } else {
                        patternState = 0;
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (HELPER_INTERNAL.equals(owner)) {
                        alreadyPatched = true;
                    }
                    if (isWriteBytes
                            && BYTE_ARRAY_OUT_INTERNAL.equals(owner)
                            && "write".equals(mName)
                            && "([BII)V".equals(mDesc)) {
                        alreadyPatched = true;
                    }
                    if (isInit
                            && patternState == 2
                            && opcode == INVOKESPECIAL
                            && BYTE_ARRAY_OUT_INTERNAL.equals(owner)
                            && "<init>".equals(mName)
                            && "()V".equals(mDesc)) {
                        hasNewBaosPattern = true;
                    }
                    patternState = 0;
                }
            };
        }
    }

    private static final class ByteStreamInitVisitor extends MethodVisitor {
        private int state;

        ByteStreamInitVisitor(final MethodVisitor delegate) {
            super(ASM9, delegate);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            if (state == 0 && opcode == NEW && BYTE_ARRAY_OUT_INTERNAL.equals(type)) {
                state = 1;
                return;
            }
            flushBuffered();
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (state == 1 && opcode == DUP) {
                state = 2;
                return;
            }
            flushBuffered();
            super.visitInsn(opcode);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (state == 2
                    && opcode == INVOKESPECIAL
                    && BYTE_ARRAY_OUT_INTERNAL.equals(owner)
                    && "<init>".equals(name)
                    && "()V".equals(desc)) {
                state = 0;
                super.visitMethodInsn(
                        INVOKESTATIC,
                        HELPER_INTERNAL,
                        "newByteArrayOutputStream",
                        "()Ljava/io/ByteArrayOutputStream;",
                        false);
                return;
            }
            flushBuffered();
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String desc) {
            flushBuffered();
            super.visitFieldInsn(opcode, owner, name, desc);
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            flushBuffered();
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            flushBuffered();
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            flushBuffered();
            super.visitLdcInsn(value);
        }

        private void flushBuffered() {
            if (state >= 1) {
                super.visitTypeInsn(NEW, BYTE_ARRAY_OUT_INTERNAL);
            }
            if (state >= 2) {
                super.visitInsn(DUP);
            }
            state = 0;
        }
    }

    private static final class ExporterScan extends ClassVisitor {
        private final String className;
        private boolean hasArrayListField;
        private boolean hasExportFormatMethod;
        private boolean usesUtilByteStreamInListMethod;
        private String mocTopMethodDesc;
        private String mocTopArgInternal;
        private boolean alreadyScopedMoc;
        private boolean hasKeyformBinding;
        private boolean hasKeyformMorph;
        private boolean hasBlendConstraint;
        private boolean hasAssertionError;
        private boolean hasAnchorString;
        private boolean hasLinearScanCall;
        private boolean hasAssertDMethod;
        private boolean alreadyGuardedAssertions;
        private int tailMapAloadOrdinal = -1;

        ExporterScan(final String className) {
            super(ASM9);
            this.className = className;
        }

        boolean needsTransform() {
            if (isMocTopSerializer() && !alreadyScopedMoc) {
                return true;
            }
            final TableKind kind = tableKind();
            if ((kind != TableKind.NONE || isClonerWithLinearScans()) && hasLinearScanCall) {
                return true;
            }
            return (kind == TableKind.KEYFORM_BINDING || kind == TableKind.KEYFORM_MORPH)
                    && hasAssertDMethod
                    && !alreadyGuardedAssertions;
        }

        boolean isMocTopSerializer() {
            return !className.contains("$")
                    && usesUtilByteStreamInListMethod
                    && hasExportFormatMethod
                    && mocTopMethodDesc != null;
        }

        boolean isClonerWithLinearScans() {
            return className.endsWith("$a") && hasAnchorString;
        }

        TableKind tableKind() {
            if (className.contains("$") || !hasArrayListField) {
                return TableKind.NONE;
            }
            if (hasKeyformBinding && hasAssertionError) {
                return TableKind.KEYFORM_BINDING;
            }
            if (hasKeyformMorph && hasAssertionError) {
                return TableKind.KEYFORM_MORPH;
            }
            if (hasBlendConstraint) {
                return TableKind.BLEND_CONSTRAINT;
            }
            return TableKind.NONE;
        }

        @Override
        public FieldVisitor visitField(
                final int access, final String name, final String desc, final String sig, final Object val) {
            if (ARRAYLIST_DESC.equals(desc)) {
                hasArrayListField = true;
            }
            return null;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if ("a".equals(name) && EXPORT_FORMAT_DESC.equals(desc)) {
                hasExportFormatMethod = true;
            }
            final boolean isCandidateMocMethod = "a".equals(name)
                    && desc.startsWith("(" + "L" + EXPORTER_PKG_PREFIX)
                    && desc.endsWith(RETURN_LIST_SUFFIX)
                    && Type.getArgumentTypes(desc).length == 1
                    && Type.getArgumentTypes(desc)[0].getSort() == Type.OBJECT;
            final boolean isAssertMethod = "d".equals(name) && "()V".equals(desc);
            final boolean scanLinear = !"<clinit>".equals(name) && !isAssertMethod;
            if (isAssertMethod) {
                hasAssertDMethod = true;
            }

            return new MethodVisitor(ASM9) {
                private boolean firstInsnSeen;
                private int aload0Count;
                private int justAload0Ordinal = -1;
                private int verifiedMapAloadOrdinal = -1;
                private int verifiedStepsRemaining;
                private boolean callsByteStream;
                private boolean callsHelper;

                private void stepProbe() {
                    if (verifiedMapAloadOrdinal >= 0) {
                        verifiedStepsRemaining--;
                        if (verifiedStepsRemaining <= 0) {
                            verifiedMapAloadOrdinal = -1;
                        }
                    }
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                    if (ROOT_DEFORMER_ANCHOR.equals(value)) {
                        hasAnchorString = true;
                    }
                }

                @Override
                public void visitVarInsn(final int opcode, final int var) {
                    firstInsnSeen = true;
                    stepProbe();
                    if (isAssertMethod && opcode == ALOAD && var == 0) {
                        justAload0Ordinal = aload0Count++;
                    } else {
                        justAload0Ordinal = -1;
                    }
                }

                @Override
                public void visitFieldInsn(
                        final int opcode, final String owner, final String fName, final String fDesc) {
                    if (isAssertMethod && !firstInsnSeen && opcode == GETSTATIC && ASSERTIONS_OWNER.equals(owner)) {
                        alreadyGuardedAssertions = true;
                    }
                    firstInsnSeen = true;
                    if (justAload0Ordinal >= 0
                            && opcode == GETFIELD
                            && className.equals(owner)
                            && MAP_DESC.equals(fDesc)) {
                        verifiedMapAloadOrdinal = justAload0Ordinal;
                        verifiedStepsRemaining = MAP_FOREACH_PROBE_WINDOW;
                    } else {
                        stepProbe();
                    }
                    justAload0Ordinal = -1;
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                    if ("java/lang/AssertionError".equals(type)) {
                        hasAssertionError = true;
                    } else if (type.endsWith("/KeyformBindingSource")) {
                        hasKeyformBinding = true;
                    } else if (type.endsWith("/KeyFormMorphTarget$a") || type.endsWith("/KeyFormMorphTarget")) {
                        hasKeyformMorph = true;
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    if (isAssertMethod
                            && verifiedMapAloadOrdinal >= 0
                            && "java/util/Map".equals(owner)
                            && "forEach".equals(mName)) {
                        tailMapAloadOrdinal = verifiedMapAloadOrdinal;
                        verifiedMapAloadOrdinal = -1;
                    } else {
                        stepProbe();
                    }
                    if (UTIL_BYTE_STREAM_TARGET.equals(owner)) {
                        callsByteStream = true;
                    }
                    if (HELPER_INTERNAL.equals(owner)) {
                        callsHelper = true;
                    }
                    if (owner.endsWith("/KeyformBindingSource") || mDesc.contains("/KeyformBindingSource;")) {
                        hasKeyformBinding = true;
                    } else if (owner.endsWith("/KeyFormMorphTarget$a")
                            || owner.endsWith("/KeyFormMorphTarget")
                            || mDesc.contains("/KeyFormMorphTarget;")) {
                        hasKeyformMorph = true;
                    } else if (owner.endsWith("/MorphTargetBlendWeightConstraintSet")
                            && "getConstraints".equals(mName)) {
                        hasBlendConstraint = true;
                    }
                    if (scanLinear && isLinearScanCall(opcode, owner, mName, mDesc)) {
                        hasLinearScanCall = true;
                    }
                }

                @Override
                public void visitInsn(final int opcode) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                }

                @Override
                public void visitIntInsn(final int opcode, final int operand) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                }

                @Override
                public void visitJumpInsn(final int opcode, final Label label) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                }

                @Override
                public void visitInvokeDynamicInsn(
                        final String dName,
                        final String dDesc,
                        final org.objectweb.asm.Handle bsm,
                        final Object... bsmArgs) {
                    firstInsnSeen = true;
                    justAload0Ordinal = -1;
                    stepProbe();
                }

                @Override
                public void visitEnd() {
                    if (isCandidateMocMethod && callsByteStream) {
                        usesUtilByteStreamInListMethod = true;
                        mocTopMethodDesc = desc;
                        mocTopArgInternal = Type.getArgumentTypes(desc)[0].getInternalName();
                        alreadyScopedMoc = callsHelper;
                    }
                }
            };
        }
    }

    private static final class ExporterTransformVisitor extends ClassVisitor {
        private final String className;
        private final ExporterScan scan;

        ExporterTransformVisitor(final ClassVisitor cv, final String className, final ExporterScan scan) {
            super(ASM9, cv);
            this.className = className;
            this.scan = scan;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            MethodVisitor mv = super.visitMethod(access, name, desc, sig, ex);
            if (scan.isMocTopSerializer()
                    && !scan.alreadyScopedMoc
                    && "a".equals(name)
                    && scan.mocTopMethodDesc.equals(desc)) {
                mv = new MocBufferScopeVisitor(mv, className, scan.mocTopArgInternal);
            }
            final TableKind kind = scan.tableKind();
            final boolean isAssertMethod = "d".equals(name) && "()V".equals(desc);
            if ((kind != TableKind.NONE || scan.isClonerWithLinearScans())
                    && scan.hasLinearScanCall
                    && !"<clinit>".equals(name)
                    && !isAssertMethod) {
                mv = new LinearScanRewriter(mv);
            }
            if ((kind == TableKind.KEYFORM_BINDING || kind == TableKind.KEYFORM_MORPH)
                    && isAssertMethod
                    && !scan.alreadyGuardedAssertions) {
                mv = new AssertionGuardVisitor(mv, className, scan.tailMapAloadOrdinal);
            }
            return mv;
        }
    }

    private static final class MocBufferScopeVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final String argInternal;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private boolean codeVisited;

        MocBufferScopeVisitor(final MethodVisitor delegate, final String ownerInternal, final String argInternal) {
            super(ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.argInternal = argInternal;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            codeVisited = true;
            super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, "enterMocBufferScope", "()V", false);
            super.visitLabel(tryStart);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == ARETURN) {
                super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, "exitMocBufferScope", "()V", false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (codeVisited) {
                super.visitLabel(tryEnd);
                super.visitLabel(catchHandler);
                super.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] {THROWABLE_INTERNAL});
                super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, "exitMocBufferScope", "()V", false);
                super.visitInsn(ATHROW);
                super.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);
            }
            super.visitMaxs(maxStack, maxLocals);
        }
    }

    private static final class LinearScanRewriter extends MethodVisitor {
        LinearScanRewriter(final MethodVisitor delegate) {
            super(ASM9, delegate);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String desc, final boolean itf) {
            if (isIndexOfCall(opcode, owner, name, desc)) {
                super.visitMethodInsn(
                        INVOKESTATIC, HELPER_INTERNAL, "indexOf", "(Ljava/util/List;Ljava/lang/Object;)I", false);
                return;
            }
            if (isInternCall(opcode, owner, name, desc)) {
                super.visitMethodInsn(
                        INVOKESTATIC,
                        HELPER_INTERNAL,
                        "intern",
                        "(Ljava/util/Collection;Ljava/lang/Object;)Ljava/lang/Object;",
                        false);
                return;
            }
            super.visitMethodInsn(opcode, owner, name, desc, itf);
        }
    }

    private static final class AssertionGuardVisitor extends MethodVisitor {
        private final String ownerInternal;
        private final int tailMapAloadOrdinal;
        private final Label branchTarget = new Label();
        private int aload0Count;

        AssertionGuardVisitor(final MethodVisitor delegate, final String ownerInternal, final int tailMapAloadOrdinal) {
            super(ASM9, delegate);
            this.ownerInternal = ownerInternal;
            this.tailMapAloadOrdinal = tailMapAloadOrdinal;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitFieldInsn(GETSTATIC, ASSERTIONS_OWNER, ASSERTIONS_FIELD, "Z");
            if (tailMapAloadOrdinal < 0) {
                super.visitJumpInsn(IFNE, branchTarget);
                super.visitInsn(RETURN);
                super.visitLabel(branchTarget);
                super.visitFrame(F_NEW, 1, new Object[] {ownerInternal}, 0, new Object[0]);
            } else {
                super.visitJumpInsn(IFEQ, branchTarget);
            }
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            if (tailMapAloadOrdinal >= 0 && opcode == ALOAD && var == 0) {
                final int ordinal = aload0Count++;
                if (ordinal == tailMapAloadOrdinal) {
                    super.visitInsn(NOP);
                    super.visitLabel(branchTarget);
                    super.visitFrame(F_NEW, 1, new Object[] {ownerInternal}, 0, new Object[0]);
                }
            }
            super.visitVarInsn(opcode, var);
        }
    }

    private static boolean isLinearScanCall(
            final int opcode, final String owner, final String name, final String desc) {
        return isIndexOfCall(opcode, owner, name, desc) || isInternCall(opcode, owner, name, desc);
    }

    private static boolean isIndexOfCall(final int opcode, final String owner, final String name, final String desc) {
        return (opcode == INVOKEVIRTUAL
                        && "java/util/ArrayList".equals(owner)
                        && "indexOf".equals(name)
                        && "(Ljava/lang/Object;)I".equals(desc))
                || (opcode == INVOKEINTERFACE
                        && "java/util/List".equals(owner)
                        && "indexOf".equals(name)
                        && "(Ljava/lang/Object;)I".equals(desc))
                || (opcode == INVOKESTATIC
                        && "kotlin/collections/CollectionsKt".equals(owner)
                        && "indexOf".equals(name)
                        && "(Ljava/util/List;Ljava/lang/Object;)I".equals(desc));
    }

    private static boolean isInternCall(final int opcode, final String owner, final String name, final String desc) {
        return opcode == INVOKESTATIC
                && (UTIL_L_OWNER.equals(owner) || UTIL_K_OWNER.equals(owner))
                && "b".equals(name)
                && "(Ljava/util/Collection;Ljava/lang/Object;)Ljava/lang/Object;".equals(desc);
    }

    private enum TableKind {
        NONE,
        KEYFORM_BINDING,
        KEYFORM_MORPH,
        BLEND_CONSTRAINT
    }
}
