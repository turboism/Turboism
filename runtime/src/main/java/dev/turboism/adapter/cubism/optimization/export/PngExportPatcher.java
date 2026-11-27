package dev.turboism.adapter.cubism.optimization.export;

import static org.objectweb.asm.Opcodes.*;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Patches {@code exporter/b$a} to pre-render and PNG-encode atlas pages concurrently in batch.
 */
final class PngExportPatcher {

    static final String TARGET = "com/live2d/cubism/doc/model/exporter/b$a";
    private static final String METHOD_NAME = "a";
    private static final String RETURN_LIST_SUFFIX = ")Ljava/util/List;";
    private static final String WRITE_PNG_METHOD = "writeImageAsPng";
    private static final String HELPER_INTERNAL = Type.getInternalName(FastPngExporter.class);
    private static final String ENTER_METHOD = "enterAndPrepare";
    private static final String ENTER_DESC = "(Ljava/lang/Object;IZ)V";
    private static final String EXIT_METHOD = "exit";
    private static final String EXIT_DESC = "()V";
    private static final String THROWABLE_INTERNAL = "java/lang/Throwable";
    private static final int MIN_EXPORT_PARAM_COUNT = 13;
    private static final int BUNDLE_ARG_INDEX = 2;
    private static final int SCALE_ARG_INDEX = 4;

    private PngExportPatcher() {}

    static byte[] patch(final byte[] classBytes) {
        final ClassReader reader = new ClassReader(classBytes);
        if (!TARGET.equals(reader.getClassName())) {
            return null;
        }
        final ExportMethodScan scan = new ExportMethodScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (!scan.matches()) {
            return null;
        }
        final Type[] argTypes = Type.getArgumentTypes(scan.matchedDesc);
        final int bundleSlot = computeArgSlot(scan.matchedAccess, argTypes, BUNDLE_ARG_INDEX);
        final int scaleSlot = computeArgSlot(scan.matchedAccess, argTypes, SCALE_ARG_INDEX);

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
                        if (!METHOD_NAME.equals(name) || !scan.matchedDesc.equals(desc)) {
                            return mv;
                        }
                        return new ExportScopeMethodVisitor(mv, bundleSlot, scaleSlot);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static int computeParamSlotCount(final int access, final Type[] argTypes) {
        int slots = (access & ACC_STATIC) == 0 ? 1 : 0;
        for (final Type type : argTypes) {
            slots += type.getSize();
        }
        return slots;
    }

    private static int computeArgSlot(final int access, final Type[] argTypes, final int argIndex) {
        int slot = (access & ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < argIndex; i++) {
            slot += argTypes[i].getSize();
        }
        return slot;
    }

    private static final class ExportMethodScan extends ClassVisitor {
        private String matchedDesc;
        private int matchedAccess;
        private boolean alreadyPatched;
        private boolean overwritesParams;

        ExportMethodScan() {
            super(ASM9);
        }

        boolean matches() {
            return matchedDesc != null && !alreadyPatched && !overwritesParams;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!METHOD_NAME.equals(name) || !desc.endsWith(RETURN_LIST_SUFFIX)) {
                return null;
            }
            final Type[] args = Type.getArgumentTypes(desc);
            if (args.length < MIN_EXPORT_PARAM_COUNT || args[SCALE_ARG_INDEX].getSort() != Type.INT) {
                return null;
            }
            final int slotLimit = computeParamSlotCount(access, args);
            return new MethodVisitor(ASM9) {
                private boolean callsWritePng;
                private boolean hasHelperCall;
                private boolean writesParamSlot;

                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String mName,
                        final String mDesc,
                        final boolean itf) {
                    if (WRITE_PNG_METHOD.equals(mName)) {
                        callsWritePng = true;
                    }
                    if (HELPER_INTERNAL.equals(owner)) {
                        hasHelperCall = true;
                    }
                }

                @Override
                public void visitVarInsn(final int opcode, final int var) {
                    if (var < slotLimit && opcode >= ISTORE && opcode <= ASTORE) {
                        writesParamSlot = true;
                    }
                }

                @Override
                public void visitEnd() {
                    if (callsWritePng) {
                        matchedDesc = desc;
                        matchedAccess = access;
                        alreadyPatched = hasHelperCall;
                        overwritesParams = writesParamSlot;
                    }
                }
            };
        }
    }

    private static final class ExportScopeMethodVisitor extends MethodVisitor {
        private final int bundleSlot;
        private final int scaleSlot;
        private final Label tryStart = new Label();
        private final Label tryEnd = new Label();
        private final Label catchHandler = new Label();
        private boolean codeVisited;

        ExportScopeMethodVisitor(final MethodVisitor delegate, final int bundleSlot, final int scaleSlot) {
            super(ASM9, delegate);
            this.bundleSlot = bundleSlot;
            this.scaleSlot = scaleSlot;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            codeVisited = true;
            super.visitVarInsn(ALOAD, bundleSlot);
            super.visitVarInsn(ILOAD, scaleSlot);
            super.visitInsn(ICONST_1);
            super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, ENTER_METHOD, ENTER_DESC, false);
            super.visitLabel(tryStart);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == ARETURN) {
                super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, EXIT_METHOD, EXIT_DESC, false);
            }
            super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            if (codeVisited) {
                super.visitLabel(tryEnd);
                super.visitLabel(catchHandler);
                super.visitFrame(F_NEW, 0, new Object[0], 1, new Object[] {THROWABLE_INTERNAL});
                super.visitMethodInsn(INVOKESTATIC, HELPER_INTERNAL, EXIT_METHOD, EXIT_DESC, false);
                super.visitInsn(ATHROW);
                super.visitTryCatchBlock(tryStart, tryEnd, catchHandler, null);
            }
            super.visitMaxs(maxStack, maxLocals);
        }
    }
}
