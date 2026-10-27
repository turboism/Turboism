package dev.turboism.adapter.cubism.interpolation;

import org.objectweb.asm.*;

/** Adds one exception-safe lookup scope around the exact pinned native normal rebuild. */
final class StructureLookupPatch {
    static final String OWNER = MeshInterpolationTransformer.OWNER;
    static final String SOURCE = "com/live2d/cubism/doc/model/ACParameterControllableSource";
    static final String FORM = "com/live2d/cubism/doc/model/ACForm";
    static final String SETUP = "setupFormStructure_Normal";
    static final String BODY = "turboism$setupFormStructure_Normal";
    static final String SETUP_DESC = "(L" + SOURCE + ";Z)V";
    static final String FIND = "setupFormStructure_Normal$local_findKeyform";
    static final String FIND_DESC = "(L" + SOURCE + ";L" + OWNER + ";Lcom/live2d/type/CFormGuid;)L" + FORM + ";";
    static final String BRIDGE = "dev/turboism/adapter/cubism/interpolation/NativeFormLookupBridge";

    private StructureLookupPatch() {}

    static byte[] patch(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] setups = {0}, finds = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        if (name.equals(BODY)) throw new IllegalArgumentException("lookup wrapper already present");
                        if (name.equals(SETUP) && desc.equals(SETUP_DESC)) {
                            if ((access & Opcodes.ACC_STATIC) != 0 || (access & Opcodes.ACC_PRIVATE) == 0)
                                throw new IllegalArgumentException("setup access");
                            setups[0]++;
                            return super.visitMethod(access, BODY, desc, signature, exceptions);
                        }
                        MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals(FIND) || !desc.equals(FIND_DESC)) return delegate;
                        if ((access & Opcodes.ACC_STATIC) == 0) throw new IllegalArgumentException("lookup access");
                        finds[0]++;
                        return new MethodVisitor(Opcodes.ASM9, delegate) {
                            @Override
                            public void visitCode() {
                                super.visitCode();
                                for (int slot = 0; slot < 3; slot++) visitVarInsn(Opcodes.ALOAD, slot);
                                visitMethodInsn(
                                        Opcodes.INVOKESTATIC,
                                        BRIDGE,
                                        "find",
                                        "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
                                        false);
                                visitInsn(Opcodes.DUP);
                                visitFieldInsn(Opcodes.GETSTATIC, BRIDGE, "UNSUPPORTED", "Ljava/lang/Object;");
                                Label official = new Label();
                                visitJumpInsn(Opcodes.IF_ACMPEQ, official);
                                visitTypeInsn(Opcodes.CHECKCAST, FORM);
                                visitInsn(Opcodes.ARETURN);
                                visitLabel(official);
                                visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Object"});
                                visitInsn(Opcodes.POP);
                            }
                        };
                    }

                    @Override
                    public void visitEnd() {
                        MethodVisitor mv = super.visitMethod(
                                Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, SETUP, SETUP_DESC, null, null);
                        mv.visitCode();
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "enter", "()Ljava/lang/Object;", false);
                        mv.visitVarInsn(Opcodes.ASTORE, 3);
                        Label start = new Label(), end = new Label(), failure = new Label();
                        mv.visitTryCatchBlock(start, end, failure, null);
                        mv.visitLabel(start);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ILOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, BODY, SETUP_DESC, false);
                        mv.visitLabel(end);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "exit", "(Ljava/lang/Object;)V", false);
                        mv.visitInsn(Opcodes.RETURN);
                        mv.visitLabel(failure);
                        mv.visitFrame(
                                Opcodes.F_FULL,
                                4,
                                new Object[] {OWNER, SOURCE, Opcodes.INTEGER, "java/lang/Object"},
                                1,
                                new Object[] {"java/lang/Throwable"});
                        mv.visitVarInsn(Opcodes.ASTORE, 4);
                        mv.visitVarInsn(Opcodes.ALOAD, 3);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "exit", "(Ljava/lang/Object;)V", false);
                        mv.visitVarInsn(Opcodes.ALOAD, 4);
                        mv.visitInsn(Opcodes.ATHROW);
                        mv.visitMaxs(0, 0);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                },
                0);
        if (setups[0] != 1 || finds[0] != 1) throw new IllegalArgumentException("structure lookup cardinality");
        return writer.toByteArray();
    }
}
