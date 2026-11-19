package dev.turboism.adapter.cubism.mesh;

import dev.turboism.adapter.cubism.textureatlas.TextureAtlasLoading;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Exact native d weave using the shared operation lease and original rejection branch. */
final class TriangulationAngleGuardPatcher implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String H = P + "h", R = P + "r";
    private static final String V = "com/live2d/graphics3d/type/GVector2";
    private static final String ANGLE = "(L" + V + ";L" + V + ";)F";
    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge";
    private static final String HELPER = "dev/turboism/adapter/cubism/mesh/TriangulationAngleGuard";
    private static final String ATLAS_BRIDGE = Type.getInternalName(TextureAtlasLoading.class);
    private static final String ATLAS_ACTIVE = "isInitializing";
    private static final String BOOLEAN_METHOD = "()Z";

    private TriangulationAngleGuardPatcher() {}

    static byte[] patch(final byte[] bytes) {
        return patch(bytes, false);
    }

    /** Uses the atlas initialization scope to preserve the native angle path in other tools. */
    static byte[] patchForAtlas(final byte[] bytes) {
        return patch(bytes, true);
    }

    private static byte[] patch(final byte[] bytes, final boolean atlasOnly) {
        final ClassReader reader = new ClassReader(bytes);
        require(reader.getClassName().equals(H), "unreviewed native d owner");
        final int[] maxLocals = {-1};
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (!name.equals("d") || !descriptor.equals("()V")) return null;
                        require(access == (ACC_PUBLIC | ACC_FINAL), "native d access");
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String name,
                                    final String descriptor,
                                    final boolean isInterface) {
                                require(!owner.equals(ATLAS_BRIDGE), "native d already uses the atlas bridge");
                            }

                            @Override
                            public void visitTryCatchBlock(
                                    final Label start, final Label end, final Label handler, final String type) {
                                throw new IllegalArgumentException("unexpected native d handler");
                            }

                            @Override
                            public void visitMaxs(final int stack, final int locals) {
                                maxLocals[0] = locals;
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(maxLocals[0] > 13, "native d locals");
        // The reader-backed writer copies non-target methods without recalculating their frames.
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String a, final String b) {
                return "java/lang/Object";
            }
        };
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                        return name.equals("d") && descriptor.equals("()V")
                                ? new Guard(output, maxLocals[0], atlasOnly)
                                : output;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class Guard extends MethodVisitor {
        private final int ready, misses, activation, failure;
        private final boolean atlasOnly;
        private final Label start = new Label(), end = new Label(), done = new Label(), failed = new Label();
        private final Label bypass = new Label();
        private Label nativeRejected;
        private int angles;
        private boolean findRejection, warmAfterStore;

        Guard(final MethodVisitor output, final int locals, final boolean atlasOnly) {
            super(ASM9, output);
            this.atlasOnly = atlasOnly;
            ready = locals;
            misses = locals + 1;
            activation = locals + 2;
            failure = locals + 3;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            if (atlasOnly) {
                super.visitMethodInsn(INVOKESTATIC, ATLAS_BRIDGE, ATLAS_ACTIVE, BOOLEAN_METHOD, false);
                super.visitVarInsn(ISTORE, activation);
            } else {
                super.visitLdcInsn(Type.getObjectType(H));
                super.visitMethodInsn(
                        INVOKESTATIC, BRIDGE, "enter", "(Ljava/lang/Class;)Ljava/lang/AutoCloseable;", false);
                super.visitVarInsn(ASTORE, activation);
                super.visitTryCatchBlock(start, end, failed, "java/lang/Throwable");
                super.visitLabel(start);
            }
            constant(ICONST_0, ready);
            constant(ICONST_0, misses);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            super.visitFieldInsn(opcode, owner, name, descriptor);
            if (opcode == GETSTATIC && owner.equals(R) && name.equals("a") && descriptor.equals("L" + R + ";")) {
                guard();
            }
        }

        private void guard() {
            final Label nativeCall = new Label(), reject = new Label();
            super.visitVarInsn(atlasOnly ? ILOAD : ALOAD, activation);
            super.visitJumpInsn(atlasOnly ? IFEQ : IFNULL, nativeCall);
            super.visitVarInsn(ILOAD, ready);
            super.visitJumpInsn(IFLE, nativeCall);
            getter(11, "getX");
            getter(12, "getY");
            super.visitInsn(FMUL);
            getter(11, "getY");
            getter(12, "getX");
            super.visitInsn(FMUL);
            super.visitInsn(FSUB);
            getter(11, "getX");
            getter(12, "getX");
            super.visitInsn(FMUL);
            getter(11, "getY");
            getter(12, "getY");
            super.visitInsn(FMUL);
            super.visitInsn(FADD);
            super.visitMethodInsn(INVOKESTATIC, HELPER, "reject", "(FF)Z", false);
            super.visitJumpInsn(IFNE, reject);
            super.visitIincInsn(misses, 1);
            super.visitVarInsn(ILOAD, misses);
            super.visitIntInsn(BIPUSH, 8);
            super.visitJumpInsn(IF_ICMPLT, nativeCall);
            constant(ICONST_M1, ready);
            super.visitJumpInsn(GOTO, nativeCall);
            super.visitLabel(reject);
            constant(ICONST_0, misses);
            super.visitInsn(POP); // original r singleton is below the scalar predicate operands
            super.visitJumpInsn(GOTO, bypass);
            super.visitLabel(nativeCall);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            if (opcode == INVOKEVIRTUAL && owner.equals(R) && name.equals("a") && descriptor.equals(ANGLE)) {
                angles++;
                findRejection = true;
                warmAfterStore = true;
            }
        }

        @Override
        public void visitVarInsn(final int opcode, final int local) {
            super.visitVarInsn(opcode, local);
            if (warmAfterStore && opcode == FSTORE && local == 13) {
                final Label already = new Label();
                super.visitVarInsn(ILOAD, ready);
                super.visitJumpInsn(IFNE, already);
                constant(ICONST_1, ready);
                super.visitLabel(already);
                warmAfterStore = false;
            }
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label target) {
            if (findRejection && opcode == IFGT) {
                nativeRejected = target;
                findRejection = false;
            }
            super.visitJumpInsn(opcode, target);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == RETURN && !atlasOnly) super.visitJumpInsn(GOTO, done);
            else super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            require(
                    angles == 1 && nativeRejected != null && !warmAfterStore && !findRejection,
                    "exact native angle/rejection stencil required");
            // The native rejection target is a backward loop label, already visited.
            super.visitLabel(bypass);
            super.visitJumpInsn(GOTO, nativeRejected);
            if (!atlasOnly) {
                super.visitLabel(end);
                super.visitLabel(done);
                leave();
                super.visitInsn(RETURN);
                super.visitLabel(failed);
                super.visitVarInsn(ASTORE, failure);
                leave();
                super.visitVarInsn(ALOAD, failure);
                super.visitInsn(ATHROW);
            }
            super.visitMaxs(0, 0);
        }

        private void getter(final int local, final String name) {
            super.visitVarInsn(ALOAD, local);
            super.visitMethodInsn(INVOKEVIRTUAL, V, name, "()F", false);
        }

        private void constant(final int opcode, final int local) {
            super.visitInsn(opcode);
            super.visitVarInsn(ISTORE, local);
        }

        private void leave() {
            super.visitVarInsn(ALOAD, activation);
            super.visitMethodInsn(INVOKESTATIC, BRIDGE, "leave", "(Ljava/lang/AutoCloseable;)V", false);
        }
    }

    private static void require(final boolean condition, final String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
