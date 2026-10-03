package dev.turboism.adapter.cubism.mesh;

import org.objectweb.asm.*;

/** Exact invocation-local corner reuse; definition admission is supplied by the shared bridge. */
final class PointTriangleReusePatcher implements Opcodes {
    static final String OWNER = "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD$a";
    static final String VECTOR = "com/live2d/graphics3d/type/GVector2";
    static final String RESULT = "com/live2d/cubism/doc/model/drawable/artMesh/PointInTriangleD";
    static final String DESCRIPTOR = "(L" + VECTOR + ";[F[IIZ)L" + RESULT + ";";
    static final String PIN = "677d39e3eb6e7e3eccb161c01c36749ff14630ec704367ddb68ed784700769be";
    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge";

    private PointTriangleReusePatcher() {}

    static byte[] patch(byte[] raw) {
        require(raw != null && PIN.equals(TriangulationEdgeIndexTransformer.sha256(raw)), "point class pin");
        ClassReader reader = new ClassReader(raw);
        require(OWNER.equals(reader.getClassName()), "point owner");
        int[] locals = {-1};
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        if (!name.equals("a") || !desc.equals(DESCRIPTOR)) return null;
                        require(locals[0] == -1, "duplicate point method");
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMaxs(int stack, int count) {
                                locals[0] = count;
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(locals[0] >= 0, "point method missing");
        int base = locals[0], lease = base + 3, x = base + 4, y = base + 5, failure = base + 6;
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return a.equals(b) ? a : "java/lang/Object";
            }
        };
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String desc, String signature, String[] exceptions) {
                        MethodVisitor output = super.visitMethod(access, name, desc, signature, exceptions);
                        if (!name.equals("a") || !desc.equals(DESCRIPTOR)) return output;
                        return new MethodVisitor(ASM9, output) {
                            final Label start = new Label(),
                                    end = new Label(),
                                    nativePath = new Label(),
                                    handler = new Label();
                            int checks, constructions, concatenations, loggerAccesses;
                            boolean started, pendingDup, pendingCtor;

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                if (opcode == NEW && VECTOR.equals(type)) {
                                    require(started && !pendingCtor, "point construction sequence");
                                    pendingDup = pendingCtor = true;
                                    return;
                                }
                                super.visitTypeInsn(opcode, type);
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (!started && opcode == ICONST_3) {
                                    require(checks == 3, "point parameter checks");
                                    mv.visitLdcInsn(Type.getObjectType(OWNER));
                                    mv.visitMethodInsn(
                                            INVOKESTATIC,
                                            BRIDGE,
                                            "enterPoint",
                                            "(Ljava/lang/Class;)Ljava/lang/AutoCloseable;",
                                            false);
                                    mv.visitVarInsn(ASTORE, lease);
                                    mv.visitVarInsn(ALOAD, lease);
                                    mv.visitJumpInsn(IFNULL, nativePath);
                                    for (int i = 0; i < 3; i++) {
                                        mv.visitInsn(ACONST_NULL);
                                        mv.visitVarInsn(ASTORE, base + i);
                                    }
                                    mv.visitLabel(start);
                                    started = true;
                                }
                                if (pendingDup) {
                                    require(opcode == DUP, "point creation dup");
                                    pendingDup = false;
                                    return;
                                }
                                if (started && opcode == ARETURN) leave(mv, lease);
                                super.visitInsn(opcode);
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String method, String descriptor, boolean itf) {
                                if (!started
                                        && owner.equals("kotlin/jvm/internal/Intrinsics")
                                        && method.equals("checkNotNullParameter")) checks++;
                                if (pendingCtor
                                        && opcode == INVOKESPECIAL
                                        && owner.equals(VECTOR)
                                        && method.equals("<init>")) {
                                    require(descriptor.equals("(FF)V") && !pendingDup, "point constructor shape");
                                    reuse(mv, base + constructions++ % 3, x, y);
                                    pendingCtor = false;
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, method, descriptor, itf);
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String name, String descriptor) {
                                if (opcode == GETSTATIC && owner.equals("com/live2d/util/log/a")) {
                                    require(
                                            started && name.equals("a") && descriptor.equals("Lcom/live2d/util/log/a;"),
                                            "point logger access");
                                    // Class initialization is already a callback boundary. No corner
                                    // is reused after this branch, so release before even loading it.
                                    leave(mv, lease);
                                    loggerAccesses++;
                                }
                                super.visitFieldInsn(opcode, owner, name, descriptor);
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String name, String descriptor, Handle bootstrap, Object... arguments) {
                                // At the only logger branch, these corners are never mutated again.
                                // Release before stringification and the logger, so callbacks may mutate
                                // instrumentation without upgrading this operation's definition lease.
                                require(
                                        started
                                                && descriptor.equals("(L" + VECTOR + ";L" + VECTOR + ";L" + VECTOR
                                                        + ";)Ljava/lang/String;"),
                                        "point logger concat");
                                concatenations++;
                                super.visitInvokeDynamicInsn(name, descriptor, bootstrap, arguments);
                            }

                            @Override
                            public void visitTryCatchBlock(Label a, Label b, Label handler, String type) {
                                throw new IllegalArgumentException("unexpected native point handler");
                            }

                            @Override
                            public void visitMaxs(int stack, int count) {
                                require(
                                        started
                                                && constructions == 9
                                                && concatenations == 1
                                                && loggerAccesses == 1
                                                && !pendingCtor,
                                        "point patch counts");
                                mv.visitLabel(end);
                                mv.visitLabel(handler);
                                mv.visitVarInsn(ASTORE, failure);
                                leave(mv, lease);
                                mv.visitVarInsn(ALOAD, failure);
                                mv.visitInsn(ATHROW);
                                mv.visitTryCatchBlock(start, end, handler, "java/lang/Throwable");
                                mv.visitLabel(nativePath);
                                // A fresh reader gives the fallback its own labels. Preserve every
                                // native instruction; no restart or partial result on admission refusal.
                                new ClassReader(raw)
                                        .accept(
                                                new ClassVisitor(ASM9) {
                                                    @Override
                                                    public MethodVisitor visitMethod(
                                                            int a, String n, String d, String s, String[] e) {
                                                        if (!n.equals("a") || !d.equals(DESCRIPTOR)) return null;
                                                        return new MethodVisitor(ASM9, mv) {
                                                            @Override
                                                            public AnnotationVisitor visitAnnotation(
                                                                    String descriptor, boolean visible) {
                                                                return null;
                                                            }

                                                            @Override
                                                            public AnnotationVisitor visitTypeAnnotation(
                                                                    int ref,
                                                                    TypePath path,
                                                                    String descriptor,
                                                                    boolean visible) {
                                                                return null;
                                                            }

                                                            @Override
                                                            public AnnotationVisitor visitParameterAnnotation(
                                                                    int parameter, String descriptor, boolean visible) {
                                                                return null;
                                                            }

                                                            @Override
                                                            public void visitAnnotableParameterCount(
                                                                    int count, boolean visible) {}

                                                            @Override
                                                            public void visitAttribute(Attribute attribute) {}

                                                            @Override
                                                            public void visitCode() {}

                                                            @Override
                                                            public void visitMaxs(int s, int l) {}

                                                            @Override
                                                            public void visitEnd() {}
                                                        };
                                                    }
                                                },
                                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
                                super.visitMaxs(stack, failure + 1);
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static void reuse(MethodVisitor mv, int cache, int x, int y) {
        Label existing = new Label(), done = new Label();
        mv.visitVarInsn(FSTORE, y);
        mv.visitVarInsn(FSTORE, x);
        mv.visitVarInsn(ALOAD, cache);
        mv.visitJumpInsn(IFNONNULL, existing);
        mv.visitTypeInsn(NEW, VECTOR);
        mv.visitInsn(DUP);
        mv.visitVarInsn(FLOAD, x);
        mv.visitVarInsn(FLOAD, y);
        mv.visitMethodInsn(INVOKESPECIAL, VECTOR, "<init>", "(FF)V", false);
        mv.visitVarInsn(ASTORE, cache);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(existing);
        mv.visitVarInsn(ALOAD, cache);
        mv.visitVarInsn(FLOAD, x);
        mv.visitMethodInsn(INVOKEVIRTUAL, VECTOR, "setX", "(F)V", false);
        mv.visitVarInsn(ALOAD, cache);
        mv.visitVarInsn(FLOAD, y);
        mv.visitMethodInsn(INVOKEVIRTUAL, VECTOR, "setY", "(F)V", false);
        mv.visitLabel(done);
        mv.visitVarInsn(ALOAD, cache);
    }

    private static void leave(MethodVisitor mv, int lease) {
        mv.visitVarInsn(ALOAD, lease);
        mv.visitMethodInsn(INVOKESTATIC, BRIDGE, "leave", "(Ljava/lang/AutoCloseable;)V", false);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
