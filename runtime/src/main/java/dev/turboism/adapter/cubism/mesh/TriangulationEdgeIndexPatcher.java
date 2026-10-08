package dev.turboism.adapter.cubism.mesh;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Surgical weaver for the host triangulator's per-edge triangle lookup.
 *
 * <p>{@code TriangleList.a(j)} scans the whole {@code LinkedHashSet} on every call (the
 * {@code HashMap$HashIterator} leaf dominating the triangulation window on reviewed hosts). The
 * patch installs {@link TriangulationEdgeIndex} bookkeeping at the three set-mutation call sites
 * and prepends an index probe to {@code a(j)}; the entire original body stays as the automatic
 * fallback, so the change can only slow-path back to official behaviour, never diverge from
 * it.</p>
 *
 * <p>Transforms (method bodies only; class shape otherwise untouched):</p>
 * <ul>
 *   <li>{@code a(l)Z}: before the single {@code LinkedHashSet.add} invoke, emit the three
 *       vertex-index extractions ({@code l.a()/b()/c()} + {@code TriPoint.getIndex()}), then call
 *       {@code TriangulationEdgeIndex.add(LinkedHashSet,Object,int,int,int)} instead. The
 *       official null-check and debug-log prologue is untouched.</li>
 *   <li>{@code b(l)Z}: swap the single {@code LinkedHashSet.remove} invoke for
 *       {@code TriangulationEdgeIndex.remove(LinkedHashSet,Object)}. Identical stack shape.</li>
 *   <li>{@code c()V}: swap the single {@code LinkedHashSet.clear} invoke for
 *       {@code TriangulationEdgeIndex.clear(LinkedHashSet)}. Identical stack shape.</li>
 *   <li>{@code c(l)Z}: swap the single {@code LinkedHashSet.contains} invoke for a positive
 *       identity shortcut; unknown or stale membership still invokes native contains.</li>
 *   <li>{@code a(j)List}: after the leading
 *       {@code aload_1; ldc ""; Intrinsics.checkNotNullParameter} prologue emit
 *       {@code r = TriangulationEdgeIndex.tryQuery(b, j.a().getIndex(), j.b().getIndex());
 *       dup; ifnull ORIG; areturn; ORIG: pop;} — the original scan runs whenever the index
 *       declines.</li>
 * </ul>
 *
 * <p>Fail-closed by construction: every one of the five method gates must match exactly once —
 * one {@code LinkedHashSet.add}/{@code remove}/{@code clear}/{@code contains} call site each and one leading
 * {@code a(j)} prologue sequence — or {@link NotApplicable} is thrown and the caller keeps the
 * original bytes.</p>
 *
 * <p>COMPUTE_FRAMES recomputes frames on the methods that gain control flow ({@code a(j)}) or
 * shifted branch offsets ({@code a(l)}); {@code getCommonSuperClass} falls back to
 * {@code java/lang/Object} so the writer never loads a host class during the transform.</p>
 */
public final class TriangulationEdgeIndexPatcher {

    /** Owner class the patch applies to. */
    static final String OWNER = "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";

    static final String EDGE = "com/live2d/graphics3d/editableMesh/triangulation/j";
    static final String TRIANGLE = "com/live2d/graphics3d/editableMesh/triangulation/l";
    static final String POINT = "com/live2d/graphics3d/editableMesh/triangulation/TriPoint";
    /** The private final {@code LinkedHashSet} field inside {@link #OWNER}. */
    static final String SET_FIELD = "b";
    /** The woven helper; resolved on the host classpath like any other plugin class. */
    static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/TriangulationEdgeIndex";

    private static final String SET = "java/util/LinkedHashSet";
    private static final String OBJ = "Ljava/lang/Object;";
    private static final String SET_DESC = "Ljava/util/LinkedHashSet;";
    private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";
    private static final String CHECK_NOT_NULL = "checkNotNullParameter";
    private static final String A_L_DESC = "(L" + TRIANGLE + ";)Z";
    private static final String A_J_DESC = "(L" + EDGE + ";)Ljava/util/List;";
    private static final String POINT_DESC = "L" + POINT + ";";

    /** Thrown when the class is not exactly the reviewed shape; the caller keeps the original. */
    public static final class NotApplicable extends RuntimeException {
        private static final long serialVersionUID = 1L;

        NotApplicable(final String message) {
            super(message);
        }
    }

    /** Returns patched bytes, or throws {@link NotApplicable} when the shape is not recognised. */
    public byte[] patch(final byte[] original) {
        verify(original);
        return emit(original);
    }

    // ------------------------------------------------------------------ shape gate

    /** Counts the pinned features; the class is only patched when every count is exactly one. */
    private static final class Survey {
        boolean aLFound, bLFound, cFound, aJFound;
        int aLAddSites, bLRemoveSites, cClearSites, aJPrologues, cLContainsSites;
    }

    private void verify(final byte[] original) {
        final Survey survey = new Survey();
        try {
            new ClassReader(original)
                    .accept(
                            new ClassVisitor(Opcodes.ASM9) {
                                private String internalName;

                                @Override
                                public void visit(
                                        final int version,
                                        final int access,
                                        final String name,
                                        final String signature,
                                        final String superName,
                                        final String[] interfaces) {
                                    internalName = name;
                                }

                                @Override
                                public MethodVisitor visitMethod(
                                        final int access,
                                        final String name,
                                        final String desc,
                                        final String signature,
                                        final String[] exceptions) {
                                    if ("a".equals(name) && A_L_DESC.equals(desc)) {
                                        survey.aLFound = true;
                                        return countInvoke(() -> survey.aLAddSites++, "add", "(" + OBJ + ")Z");
                                    }
                                    if ("b".equals(name) && A_L_DESC.equals(desc)) {
                                        survey.bLFound = true;
                                        return countInvoke(() -> survey.bLRemoveSites++, "remove", "(" + OBJ + ")Z");
                                    }
                                    if ("c".equals(name) && A_L_DESC.equals(desc)) {
                                        return countInvoke(
                                                () -> survey.cLContainsSites++, "contains", "(" + OBJ + ")Z");
                                    }
                                    if ("c".equals(name) && "()V".equals(desc)) {
                                        survey.cFound = true;
                                        return countInvoke(() -> survey.cClearSites++, "clear", "()V");
                                    }
                                    if ("a".equals(name) && A_J_DESC.equals(desc)) {
                                        survey.aJFound = true;
                                        return prologueProbe(survey);
                                    }
                                    return null;
                                }

                                private MethodVisitor countInvoke(
                                        final Runnable hit, final String method, final String desc) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String name,
                                                final String descriptor,
                                                final boolean isInterface) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && SET.equals(owner)
                                                    && method.equals(name)
                                                    && desc.equals(descriptor)) {
                                                hit.run();
                                            }
                                        }
                                    };
                                }

                                /** Counts only the leading aload_1;ldc "";checkNotNullParameter run. */
                                private MethodVisitor prologueProbe(final Survey target) {
                                    return new MethodVisitor(Opcodes.ASM9) {
                                        int index = -1;
                                        final int[] trail = new int[3]; // kinds of last 3 real insns

                                        void shift(final int kind) {
                                            index++;
                                            trail[0] = trail[1];
                                            trail[1] = trail[2];
                                            trail[2] = kind;
                                        }

                                        void anyInsn() {
                                            shift(0);
                                        }

                                        @Override
                                        public void visitInsn(final int op) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitIntInsn(final int op, final int operand) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitVarInsn(final int op, final int variable) {
                                            shift(op == Opcodes.ALOAD && variable == 1 ? 1 : 0);
                                        }

                                        @Override
                                        public void visitLdcInsn(final Object value) {
                                            shift("".equals(value) ? 2 : 0);
                                        }

                                        @Override
                                        public void visitFieldInsn(
                                                final int op, final String o, final String n, final String d) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitJumpInsn(final int op, final Label l) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitIincInsn(final int v, final int i) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitTypeInsn(final int op, final String t) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitTableSwitchInsn(
                                                final int a, final int b2, final Label d, final Label... l) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitLookupSwitchInsn(
                                                final Label d, final int[] k, final Label[] l) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitMultiANewArrayInsn(final String d, final int n) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitInvokeDynamicInsn(
                                                final String n, final String d, final Handle h, final Object... a) {
                                            anyInsn();
                                        }

                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String name,
                                                final String descriptor,
                                                final boolean isInterface) {
                                            shift(
                                                    op == Opcodes.INVOKESTATIC
                                                                    && INTRINSICS.equals(owner)
                                                                    && CHECK_NOT_NULL.equals(name)
                                                                    && ("(" + OBJ + "Ljava/lang/String;)V")
                                                                            .equals(descriptor)
                                                            ? 3
                                                            : 0);
                                            if (trail[0] == 1 && trail[1] == 2 && trail[2] == 3 && index <= 3) {
                                                target.aJPrologues++;
                                            }
                                        }
                                    };
                                }

                                @Override
                                public void visitEnd() {
                                    if (!OWNER.equals(internalName)) {
                                        throw new NotApplicable("unexpected class: " + internalName);
                                    }
                                }
                            },
                            0);
        } catch (NotApplicable rejected) {
            throw rejected;
        } catch (RuntimeException malformed) {
            throw new NotApplicable(
                    "class bytes are not parseable: " + malformed.getClass().getSimpleName());
        }
        if (!survey.aLFound) throw new NotApplicable("method a(l) not found");
        if (!survey.bLFound) throw new NotApplicable("method b(l) not found");
        if (!survey.cFound) throw new NotApplicable("method c() not found");
        if (!survey.aJFound) throw new NotApplicable("method a(j) not found");
        if (survey.aLAddSites != 1) throw new NotApplicable("a(l) LinkedHashSet.add sites=" + survey.aLAddSites);
        if (survey.bLRemoveSites != 1)
            throw new NotApplicable("b(l) LinkedHashSet.remove sites=" + survey.bLRemoveSites);
        if (survey.cClearSites != 1) throw new NotApplicable("c() LinkedHashSet.clear sites=" + survey.cClearSites);
        if (survey.cLContainsSites != 1)
            throw new NotApplicable("c(l) LinkedHashSet.contains sites=" + survey.cLContainsSites);
        if (survey.aJPrologues != 1) throw new NotApplicable("a(j) leading prologue sequences=" + survey.aJPrologues);
    }

    // ------------------------------------------------------------------ emit

    private byte[] emit(final byte[] original) {
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String a, final String b) {
                return "java/lang/Object";
            }
        };
        new ClassReader(original)
                .accept(
                        new ClassVisitor(Opcodes.ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (mv == null) return null;

                                if ("a".equals(name) && A_L_DESC.equals(desc)) {
                                    return new MethodVisitor(Opcodes.ASM9, mv) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String n,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && SET.equals(owner)
                                                    && "add".equals(n)
                                                    && ("(" + OBJ + ")Z").equals(d)) {
                                                // stack [set, l] -> push ia,ib,ic then static call
                                                emitVertexIndices(this, 1);
                                                super.visitMethodInsn(
                                                        Opcodes.INVOKESTATIC,
                                                        BRIDGE,
                                                        "add",
                                                        "(Ljava/util/LinkedHashSet;" + OBJ + "III)Z",
                                                        false);
                                                return;
                                            }
                                            super.visitMethodInsn(op, owner, n, d, itf);
                                        }
                                    };
                                }
                                if ("b".equals(name) && A_L_DESC.equals(desc)) {
                                    return new MethodVisitor(Opcodes.ASM9, mv) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String n,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && SET.equals(owner)
                                                    && "remove".equals(n)
                                                    && ("(" + OBJ + ")Z").equals(d)) {
                                                super.visitMethodInsn(
                                                        Opcodes.INVOKESTATIC,
                                                        BRIDGE,
                                                        "remove",
                                                        "(Ljava/util/LinkedHashSet;" + OBJ + ")Z",
                                                        false);
                                                return;
                                            }
                                            super.visitMethodInsn(op, owner, n, d, itf);
                                        }
                                    };
                                }
                                if ("c".equals(name) && A_L_DESC.equals(desc)) {
                                    return new MethodVisitor(Opcodes.ASM9, mv) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String n,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && SET.equals(owner)
                                                    && "contains".equals(n)
                                                    && ("(" + OBJ + ")Z").equals(d)) {
                                                super.visitMethodInsn(
                                                        Opcodes.INVOKESTATIC,
                                                        BRIDGE,
                                                        "contains",
                                                        "(Ljava/util/LinkedHashSet;" + OBJ + ")Z",
                                                        false);
                                                return;
                                            }
                                            super.visitMethodInsn(op, owner, n, d, itf);
                                        }
                                    };
                                }
                                if ("c".equals(name) && "()V".equals(desc)) {
                                    return new MethodVisitor(Opcodes.ASM9, mv) {
                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String n,
                                                final String d,
                                                final boolean itf) {
                                            if (op == Opcodes.INVOKEVIRTUAL
                                                    && SET.equals(owner)
                                                    && "clear".equals(n)
                                                    && "()V".equals(d)) {
                                                super.visitMethodInsn(
                                                        Opcodes.INVOKESTATIC,
                                                        BRIDGE,
                                                        "clear",
                                                        "(Ljava/util/LinkedHashSet;)V",
                                                        false);
                                                return;
                                            }
                                            super.visitMethodInsn(op, owner, n, d, itf);
                                        }
                                    };
                                }
                                if ("a".equals(name) && A_J_DESC.equals(desc)) {
                                    return new MethodVisitor(Opcodes.ASM9, mv) {
                                        int index = -1;
                                        final int[] trail = new int[3];
                                        boolean injected;

                                        void shift(final int kind) {
                                            index++;
                                            trail[0] = trail[1];
                                            trail[1] = trail[2];
                                            trail[2] = kind;
                                        }

                                        void anyInsn() {
                                            shift(0);
                                        }

                                        @Override
                                        public void visitInsn(final int op) {
                                            anyInsn();
                                            super.visitInsn(op);
                                        }

                                        @Override
                                        public void visitIntInsn(final int op, final int v) {
                                            anyInsn();
                                            super.visitIntInsn(op, v);
                                        }

                                        @Override
                                        public void visitVarInsn(final int op, final int v) {
                                            shift(op == Opcodes.ALOAD && v == 1 ? 1 : 0);
                                            super.visitVarInsn(op, v);
                                        }

                                        @Override
                                        public void visitLdcInsn(final Object v) {
                                            shift("".equals(v) ? 2 : 0);
                                            super.visitLdcInsn(v);
                                        }

                                        @Override
                                        public void visitFieldInsn(
                                                final int op, final String o, final String n, final String d) {
                                            anyInsn();
                                            super.visitFieldInsn(op, o, n, d);
                                        }

                                        @Override
                                        public void visitJumpInsn(final int op, final Label l) {
                                            anyInsn();
                                            super.visitJumpInsn(op, l);
                                        }

                                        @Override
                                        public void visitIincInsn(final int v, final int i) {
                                            anyInsn();
                                            super.visitIincInsn(v, i);
                                        }

                                        @Override
                                        public void visitTypeInsn(final int op, final String t) {
                                            anyInsn();
                                            super.visitTypeInsn(op, t);
                                        }

                                        @Override
                                        public void visitTableSwitchInsn(
                                                final int a, final int b2, final Label d, final Label... l) {
                                            anyInsn();
                                            super.visitTableSwitchInsn(a, b2, d, l);
                                        }

                                        @Override
                                        public void visitLookupSwitchInsn(
                                                final Label d, final int[] k, final Label[] l) {
                                            anyInsn();
                                            super.visitLookupSwitchInsn(d, k, l);
                                        }

                                        @Override
                                        public void visitMultiANewArrayInsn(final String d, final int n) {
                                            anyInsn();
                                            super.visitMultiANewArrayInsn(d, n);
                                        }

                                        @Override
                                        public void visitInvokeDynamicInsn(
                                                final String n, final String d, final Handle h, final Object... a) {
                                            anyInsn();
                                            super.visitInvokeDynamicInsn(n, d, h, a);
                                        }

                                        @Override
                                        public void visitMethodInsn(
                                                final int op,
                                                final String owner,
                                                final String n,
                                                final String d,
                                                final boolean itf) {
                                            shift(
                                                    op == Opcodes.INVOKESTATIC
                                                                    && INTRINSICS.equals(owner)
                                                                    && CHECK_NOT_NULL.equals(n)
                                                                    && ("(" + OBJ + "Ljava/lang/String;)V").equals(d)
                                                            ? 3
                                                            : 0);
                                            super.visitMethodInsn(op, owner, n, d, itf);
                                            if (!injected
                                                    && trail[0] == 1
                                                    && trail[1] == 2
                                                    && trail[2] == 3
                                                    && index <= 3) {
                                                injected = true;
                                                injectTryQuery(mv);
                                            }
                                        }
                                    };
                                }
                                return mv;
                            }
                        },
                        0);
        return writer.toByteArray();
    }

    /** Emits {@code aload_v; l.<getter>(); TriPoint.getIndex()} for the vertex getters a/b/c. */
    private static void emitVertexIndices(final MethodVisitor mv, final int variable) {
        for (final String getter : new String[] {"a", "b", "c"}) {
            mv.visitVarInsn(Opcodes.ALOAD, variable);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, TRIANGLE, getter, "()" + POINT_DESC, false);
            mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
        }
    }

    /**
     * Emits the {@code tryQuery} dispatch: empty stack pushes the probe result, a null falls
     * through to the untouched original body, a non-null returns the snapshot list.
     */
    private static void injectTryQuery(final MethodVisitor mv) {
        final Label original = new Label();
        mv.visitVarInsn(Opcodes.ALOAD, 0);
        mv.visitFieldInsn(Opcodes.GETFIELD, OWNER, SET_FIELD, SET_DESC);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EDGE, "a", "()" + POINT_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
        mv.visitVarInsn(Opcodes.ALOAD, 1);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, EDGE, "b", "()" + POINT_DESC, false);
        mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
        mv.visitMethodInsn(
                Opcodes.INVOKESTATIC, BRIDGE, "tryQuery", "(Ljava/util/LinkedHashSet;II)Ljava/util/List;", false);
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, original);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(original);
        mv.visitInsn(Opcodes.POP);
    }
}
