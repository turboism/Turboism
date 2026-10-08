package dev.turboism.adapter.cubism.mesh;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Exact native suffix weave. Preparation and mutation admission are required separately. */
final class NativeMeshEdgePatcher implements Opcodes {
    static final String MESH = "com/live2d/graphics3d/editableMesh/GEditableMesh2";
    private static final String LIST = "com/live2d/type/CArrayList";
    private static final String HELPER = "dev/turboism/adapter/cubism/mesh/NativeMeshEdgeLookup";
    private static final Set<String> PINS = Set.of(
            "734b9bde593f27816b72f63c585a371d724507f21c97ac41980cbf3f347c57ad",
            "d6fe4e690399d767019d113e82c62414da0d82d9a54aa799a74919d9e8693f7a");

    private NativeMeshEdgePatcher() {}

    static byte[] patch(byte[] raw) {
        require(raw != null && PINS.contains(TriangulationEdgeIndexTransformer.sha256(raw)), "native class pin");
        ClassReader reader = new ClassReader(raw);
        require(reader.getClassName().equals(MESH), "native owner");
        Map<String, Integer> locals = new HashMap<>();
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMaxs(int stack, int count) {
                                require(locals.put(name + descriptor, count) == null, "duplicate method");
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(String a, String b) {
                return "java/lang/Object";
            }
        };
        int[] totals = new int[4];
        reader.accept(
                new ClassVisitor(ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        MethodVisitor target = super.visitMethod(access, name, descriptor, signature, exceptions);
                        boolean loop = name.equals("autoConnect"),
                                typed = name.equals("checkExitingTypedEdge"),
                                add = name.equals("addEdge");
                        if (!loop && !typed && !add) return target;
                        int base = locals.get(name + descriptor);
                        return new MethodVisitor(ASM9, target) {
                            final Label start = new Label(),
                                    end = new Label(),
                                    done = new Label(),
                                    failed = new Label();
                            boolean suffix, pendingAppend;

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String field, String desc) {
                                if (loop && opcode == GETFIELD && field.equals("cached_indices")) {
                                    require(!suffix && owner.equals(MESH) && desc.equals("[I"), "index publication");
                                    // Keep the original receiver on the operand stack. A duplicate
                                    // enters the scope after all native progress callbacks.
                                    mv.visitInsn(DUP);
                                    mv.visitMethodInsn(
                                            INVOKESTATIC,
                                            HELPER,
                                            "enter",
                                            "(Ljava/lang/Object;)Ljava/lang/AutoCloseable;",
                                            false);
                                    mv.visitVarInsn(ASTORE, base);
                                    mv.visitLabel(start);
                                    suffix = true;
                                    totals[0]++;
                                }
                                super.visitFieldInsn(opcode, owner, field, desc);
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String method, String desc, boolean itf) {
                                if (loop && suffix && method.equals("addEdgeIfNotExists$default")) totals[1]++;
                                if (typed && owner.equals(MESH) && method.equals("chechExistingEdge_exe")) {
                                    require(
                                            opcode == INVOKESPECIAL && desc.equals("(IIZ)Ljava/lang/Integer;"),
                                            "lookup invocation");
                                    emitLookup(mv, base, opcode, owner, method, desc, itf);
                                    totals[2]++;
                                    return;
                                }
                                super.visitMethodInsn(opcode, owner, method, desc, itf);
                                if (add
                                        && owner.equals(LIST)
                                        && method.equals("add")
                                        && desc.equals("(Ljava/lang/Object;)Z")) {
                                    require(!pendingAppend, "append sequence");
                                    pendingAppend = true;
                                }
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                if (pendingAppend) {
                                    require(opcode == POP, "native append result");
                                    super.visitInsn(opcode);
                                    mv.visitVarInsn(ALOAD, 0);
                                    mv.visitMethodInsn(
                                            INVOKESTATIC, HELPER, "appended", "(Ljava/lang/Object;)V", false);
                                    pendingAppend = false;
                                    totals[3]++;
                                } else if (loop && suffix && opcode == RETURN) mv.visitJumpInsn(GOTO, done);
                                else super.visitInsn(opcode);
                            }

                            @Override
                            public void visitTryCatchBlock(Label from, Label to, Label handler, String type) {
                                require(!loop, "native loop handlers");
                                super.visitTryCatchBlock(from, to, handler, type);
                            }

                            @Override
                            public void visitMaxs(int stack, int count) {
                                if (loop) {
                                    require(suffix, "suffix missing");
                                    mv.visitLabel(end);
                                    mv.visitLabel(done);
                                    leave(mv, base);
                                    mv.visitInsn(RETURN);
                                    mv.visitLabel(failed);
                                    mv.visitVarInsn(ASTORE, base + 1);
                                    leave(mv, base);
                                    mv.visitVarInsn(ALOAD, base + 1);
                                    mv.visitInsn(ATHROW);
                                    mv.visitTryCatchBlock(start, end, failed, "java/lang/Throwable");
                                }
                                require(!pendingAppend, "unconsumed append");
                                super.visitMaxs(stack, count + (loop ? 2 : typed ? 5 : 0));
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        require(totals[0] == 1 && totals[1] == 3 && totals[2] == 1 && totals[3] == 1, "native insertion counts");
        return writer.toByteArray();
    }

    private static void emitLookup(
            MethodVisitor mv, int base, int opcode, String owner, String name, String desc, boolean itf) {
        int a = base + 1, b = base + 2, filtered = base + 3, result = base + 4;
        Label nativePath = new Label(), absent = new Label(), done = new Label();
        mv.visitVarInsn(ISTORE, filtered);
        mv.visitVarInsn(ISTORE, b);
        mv.visitVarInsn(ISTORE, a);
        mv.visitVarInsn(ASTORE, base);
        arguments(mv, base, a, b, filtered);
        mv.visitMethodInsn(INVOKESTATIC, HELPER, "find", "(Ljava/lang/Object;IIZ)I", false);
        mv.visitVarInsn(ISTORE, result);
        mv.visitVarInsn(ILOAD, result);
        mv.visitIntInsn(BIPUSH, NativeMeshEdgeTable.UNKNOWN);
        mv.visitJumpInsn(IF_ICMPEQ, nativePath);
        mv.visitVarInsn(ILOAD, result);
        mv.visitJumpInsn(IFLT, absent);
        mv.visitVarInsn(ILOAD, result);
        mv.visitMethodInsn(INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(absent);
        mv.visitInsn(ACONST_NULL);
        mv.visitJumpInsn(GOTO, done);
        mv.visitLabel(nativePath);
        arguments(mv, base, a, b, filtered);
        mv.visitMethodInsn(opcode, owner, name, desc, itf);
        mv.visitLabel(done);
    }

    private static void arguments(MethodVisitor mv, int receiver, int a, int b, int filtered) {
        mv.visitVarInsn(ALOAD, receiver);
        mv.visitVarInsn(ILOAD, a);
        mv.visitVarInsn(ILOAD, b);
        mv.visitVarInsn(ILOAD, filtered);
    }

    private static void leave(MethodVisitor mv, int lease) {
        mv.visitVarInsn(ALOAD, lease);
        mv.visitMethodInsn(
                INVOKESTATIC,
                "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge",
                "leave",
                "(Ljava/lang/AutoCloseable;)V",
                false);
    }

    private static void require(boolean condition, String reason) {
        if (!condition) throw new IllegalArgumentException(reason);
    }
}
