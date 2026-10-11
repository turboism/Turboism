package dev.turboism.adapter.cubism.interpolation;

import org.objectweb.asm.*;

/** Used only after exact artifact/class admission by MeshInterpolationTransformer. */
final class DerivedCacheSerializationPatch {
    static final String OWNER = "com/live2d/serialize/UUSerialize";
    static final String SHA256 = "4b60f445804e6025b65961448145ec237a35996ed8b1c0264bdd22c0032fc887";
    static final String PROPERTY_OWNER = "com/live2d/serialize/impl/PropertySet";
    static final String PROPERTY_SHA256 = "12bd727513f5083893db206072675d6c96ec2412396fb21b530b39d47031aba0";
    static final String BRIDGE = "dev/turboism/adapter/cubism/interpolation/DerivedCacheSerializationBridge";
    private static final String METHOD = "serializeToDoc_exe";
    private static final String ORIGINAL = "turboism$serializeToDoc_original";
    private static final String DESC = "(Ljava/lang/Object;Lcom/live2d/serialize/XmlWriter;)Lorg/jdom/Document;";

    private DerivedCacheSerializationPatch() {}

    static byte[] property(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(PROPERTY_OWNER)) throw new IllegalArgumentException("property owner");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] reads = {0}, getters = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals("writeProperty")
                                || !desc.equals(
                                        "(Lorg/jdom/Element;Ljava/lang/Object;Lcom/live2d/serialize/XmlWriter;)Lorg/jdom/Element;"))
                            return mv;
                        return new MethodVisitor(Opcodes.ASM9, mv) {
                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String method, String descriptor, boolean iface) {
                                if (opcode == Opcodes.INVOKEVIRTUAL
                                        && owner.equals("java/lang/reflect/Field")
                                        && method.equals("get")
                                        && descriptor.equals("(Ljava/lang/Object;)Ljava/lang/Object;")) {
                                    reads[0]++;
                                    super.visitMethodInsn(
                                            Opcodes.INVOKESTATIC,
                                            BRIDGE,
                                            "fieldValue",
                                            "(Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;",
                                            false);
                                } else {
                                    super.visitMethodInsn(opcode, owner, method, descriptor, iface);
                                    if (opcode == Opcodes.INVOKEINTERFACE
                                            && owner.equals("kotlin/reflect/KProperty1$Getter")
                                            && method.equals("call")
                                            && descriptor.equals("([Ljava/lang/Object;)Ljava/lang/Object;")) {
                                        getters[0]++;
                                        super.visitVarInsn(Opcodes.ALOAD, 0);
                                        super.visitMethodInsn(
                                                Opcodes.INVOKEVIRTUAL,
                                                PROPERTY_OWNER,
                                                "getBackingField",
                                                "()Ljava/lang/reflect/Field;",
                                                false);
                                        super.visitVarInsn(Opcodes.ALOAD, 2);
                                        super.visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "getterValue",
                                                "(Ljava/lang/Object;Ljava/lang/reflect/Field;Ljava/lang/Object;)Ljava/lang/Object;",
                                                false);
                                    }
                                }
                            }
                        };
                    }
                },
                0);
        if (reads[0] != 1 || getters[0] != 1) throw new IllegalArgumentException("property field-read cardinality");
        return writer.toByteArray();
    }

    /** Preserve all official document/image/archive processing; bypass only XML String materialization. */
    private static MethodVisitor streamArchive(MethodVisitor original) {
        return new MethodVisitor(Opcodes.ASM9, original) {
            int xmlCalls;
            boolean guard, joinPending;
            final Label join = new Label();
            final Object[] locals = {
                Opcodes.TOP,
                Opcodes.TOP,
                Opcodes.TOP,
                "com/live2d/serialize/CompressOption",
                Opcodes.TOP,
                Opcodes.TOP,
                Opcodes.TOP,
                Opcodes.TOP,
                "com/live2d/serialize/XmlWriter",
                "org/jdom/Document"
            };

            @Override
            public void visitFieldInsn(int opcode, String owner, String name, String desc) {
                if (opcode == Opcodes.GETSTATIC
                        && owner.equals("com/live2d/util/aL")
                        && name.equals("a")
                        && ++xmlCalls == 2) {
                    Label official = new Label();
                    super.visitVarInsn(Opcodes.ALOAD, 8);
                    super.visitVarInsn(Opcodes.ALOAD, 9);
                    super.visitVarInsn(Opcodes.ALOAD, 3);
                    super.visitMethodInsn(
                            Opcodes.INVOKESTATIC,
                            "dev/turboism/adapter/cubism/interpolation/NativeXmlStreamingBridge",
                            "archive",
                            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)Z",
                            false);
                    super.visitJumpInsn(Opcodes.IFEQ, official);
                    super.visitInsn(Opcodes.ACONST_NULL);
                    super.visitVarInsn(Opcodes.ASTORE, 10);
                    super.visitJumpInsn(Opcodes.GOTO, join);
                    super.visitLabel(official);
                    super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, new Object[0]);
                    guard = true;
                }
                super.visitFieldInsn(opcode, owner, name, desc);
            }

            @Override
            public void visitMethodInsn(int opcode, String owner, String name, String desc, boolean iface) {
                super.visitMethodInsn(opcode, owner, name, desc, iface);
                if (owner.equals("com/live2d/serialize/archiver/ArchiveWriter") && name.equals("addFileEntry") && guard)
                    joinPending = true;
            }

            @Override
            public void visitInsn(int opcode) {
                super.visitInsn(opcode);
                if (joinPending) {
                    if (opcode != Opcodes.POP) throw new IllegalArgumentException("archive join shape");
                    super.visitLabel(join);
                    Object[] complete = java.util.Arrays.copyOf(locals, 11);
                    complete[9] = Opcodes.TOP;
                    complete[10] = "java/lang/String";
                    super.visitFrame(Opcodes.F_NEW, complete.length, complete, 0, new Object[0]);
                    joinPending = false;
                }
            }

            @Override
            public void visitEnd() {
                if (!guard || xmlCalls != 2 || joinPending)
                    throw new IllegalArgumentException("archive string site cardinality");
                super.visitEnd();
            }
        };
    }

    static byte[] serializer(byte[] bytes) {
        ClassReader reader = new ClassReader(bytes);
        if (!reader.getClassName().equals(OWNER)) throw new IllegalArgumentException("serializer owner");
        ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        int[] matches = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        if (name.equals(ORIGINAL)) throw new IllegalArgumentException("serializer already scoped");
                        if (name.equals(METHOD) && desc.equals(DESC)) {
                            if (access != (Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL))
                                throw new IllegalArgumentException("serializer access");
                            matches[0]++;
                            return super.visitMethod(
                                    access | Opcodes.ACC_SYNTHETIC, ORIGINAL, desc, signature, exceptions);
                        }
                        MethodVisitor visitor = super.visitMethod(access, name, desc, signature, exceptions);
                        if (name.equals("serialize_exe")) return streamArchive(visitor);
                        return visitor;
                    }

                    @Override
                    public void visitEnd() {
                        if (matches[0] != 1) throw new IllegalArgumentException("serializer method cardinality");
                        MethodVisitor mv =
                                super.visitMethod(Opcodes.ACC_PRIVATE | Opcodes.ACC_FINAL, METHOD, DESC, null, null);
                        mv.visitCode();
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "enter", "(Ljava/lang/Object;)V", false);
                        Label begin = new Label(), end = new Label(), failure = new Label();
                        mv.visitTryCatchBlock(begin, end, failure, "java/lang/Throwable");
                        mv.visitLabel(begin);
                        mv.visitVarInsn(Opcodes.ALOAD, 0);
                        mv.visitVarInsn(Opcodes.ALOAD, 1);
                        mv.visitVarInsn(Opcodes.ALOAD, 2);
                        mv.visitMethodInsn(Opcodes.INVOKESPECIAL, OWNER, ORIGINAL, DESC, false);
                        mv.visitInsn(Opcodes.DUP);
                        mv.visitMethodInsn(
                                Opcodes.INVOKESTATIC, BRIDGE, "documentResult", "(Ljava/lang/Object;)V", false);
                        mv.visitLabel(end);
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "exit", "()V", false);
                        mv.visitInsn(Opcodes.ARETURN);
                        mv.visitLabel(failure);
                        mv.visitFrame(Opcodes.F_SAME1, 0, null, 1, new Object[] {"java/lang/Throwable"});
                        mv.visitMethodInsn(Opcodes.INVOKESTATIC, BRIDGE, "exit", "()V", false);
                        mv.visitInsn(Opcodes.ATHROW);
                        mv.visitMaxs(3, 3);
                        mv.visitEnd();
                        super.visitEnd();
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }
}
