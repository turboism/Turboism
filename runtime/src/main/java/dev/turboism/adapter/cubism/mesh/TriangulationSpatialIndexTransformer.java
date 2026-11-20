package dev.turboism.adapter.cubism.mesh;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Fail-closed structural ASM transformer accelerating Cubism triangulation hotspot classes
 * ({@code TriangleList}, {@code k}, {@code h}, and {@code a}) across Cubism 5.0–5.3 without
 * requiring dynamic attach or retransformation.
 */
public final class TriangulationSpatialIndexTransformer implements ClassFileTransformer, Opcodes {
    private static final String TRIANGULATION_PKG = "com/live2d/graphics3d/editableMesh/triangulation/";
    public static final String TARGET_LIST_INTERNAL_NAME = TRIANGULATION_PKG + "TriangleList";
    public static final String TARGET_EDGE_SET_INTERNAL_NAME = TRIANGULATION_PKG + "k";
    public static final String TARGET_SOLVER_INTERNAL_NAME = TRIANGULATION_PKG + "h";
    public static final String TARGET_BASE_SOLVER_INTERNAL_NAME = TRIANGULATION_PKG + "a";

    private static final String TRIANGLE = TRIANGULATION_PKG + "l";
    private static final String EDGE = TRIANGULATION_PKG + "j";
    private static final String POINT = TRIANGULATION_PKG + "TriPoint";
    private static final String CONTOUR_INPUT = TRIANGULATION_PKG + "c";
    private static final String GVECTOR2 = "com/live2d/graphics3d/type/GVector2";
    private static final String INTRINSICS = "kotlin/jvm/internal/Intrinsics";
    private static final String LINKED_HASH_SET_FIELD = "b";
    private static final String LINKED_HASH_SET_DESC = "Ljava/util/LinkedHashSet;";
    private static final String ARRAY_LIST_FIELD = "a";
    private static final String ARRAY_LIST_DESC = "Ljava/util/ArrayList;";
    private static final String EDGE_SET_DESC = "L" + TARGET_EDGE_SET_INTERNAL_NAME + ";";
    private static final String TRI_DESC = "L" + TRIANGLE + ";";
    private static final String EDGE_DESC = "L" + EDGE + ";";
    private static final String POINT_DESC = "L" + POINT + ";";
    private static final String LIST_DESC = "L" + TARGET_LIST_INTERNAL_NAME + ";";
    private static final String INPUT_DESC = "L" + CONTOUR_INPUT + ";";

    private static final String BRIDGE = "dev/turboism/adapter/cubism/mesh/TriangulationSpatialIndex";
    private static final String BRIDGE_ON_ADD_DESC = "(Ljava/lang/Object;Ljava/util/LinkedHashSet;Ljava/lang/Object;"
            + "Ljava/lang/Object;FFILjava/lang/Object;FFILjava/lang/Object;FFI)V";
    private static final String BRIDGE_ON_REMOVE_DESC =
            "(Ljava/lang/Object;Ljava/util/LinkedHashSet;Ljava/lang/Object;)V";
    private static final String BRIDGE_ON_CLEAR_DESC = "(Ljava/lang/Object;Ljava/util/LinkedHashSet;)V";
    private static final String BRIDGE_FIND_CONTAINING_DESC = "(Ljava/lang/Object;FFI)Ljava/lang/Object;";
    private static final String BRIDGE_FIND_INTERSECTING_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;)[Ljava/lang/Object;";
    private static final String BRIDGE_ON_EDGE_ADD_DESC =
            "(Ljava/lang/Object;Ljava/util/ArrayList;Ljava/lang/Object;IFFIFF)V";
    private static final String BRIDGE_ON_EDGE_MUTATE_DESC = "(Ljava/lang/Object;Ljava/util/ArrayList;)V";
    private static final String BRIDGE_CONTAINS_EDGE_DESC = "(Ljava/lang/Object;Ljava/util/ArrayList;IIZ)I";
    private static final String BRIDGE_FIND_COLLINEAR_DESC = "(Ljava/lang/Object;FFI)Ljava/lang/Object;";
    private static final String BRIDGE_PRUNE_CONTOURS_DESC = "(Ljava/util/List;Ljava/util/List;Ljava/lang/Iterable;)Z";

    private static final String SOLVER_A_LEGACY_DESC =
            "(Ljava/util/List;" + POINT_DESC + LIST_DESC + EDGE_SET_DESC + ")V";
    private static final String SOLVER_A_RECENT_DESC = "(" + POINT_DESC + LIST_DESC + EDGE_SET_DESC + ")V";
    private static final Set<String> EDGE_ITERATOR_METHODS = Set.of("b", "c");
    private static final int PROLOGUE_MAX_INDEX = 4;

    private volatile Outcome lastOutcome = Outcome.NONE;

    /** Result of the most recent transform invocation for diagnostics and testing. */
    public enum Outcome {
        NONE,
        PATCHED,
        SHAPE_REJECTED
    }

    /** Returns the most recent transformation outcome for diagnostics and tests. */
    public Outcome outcome() {
        return lastOutcome;
    }

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classBeingRedefined != null || classfileBuffer == null || className == null) {
            return null;
        }
        try {
            final byte[] patched =
                    switch (className) {
                        case TARGET_LIST_INTERNAL_NAME -> patchTriangleList(classfileBuffer);
                        case TARGET_EDGE_SET_INTERNAL_NAME -> patchEdgeSet(classfileBuffer);
                        case TARGET_SOLVER_INTERNAL_NAME -> patchSolver(classfileBuffer);
                        case TARGET_BASE_SOLVER_INTERNAL_NAME -> patchBaseSolver(classfileBuffer);
                        default -> null;
                    };
            if (patched != null) {
                lastOutcome = Outcome.PATCHED;
            }
            return patched;
        } catch (RuntimeException rejected) {
            lastOutcome = Outcome.SHAPE_REJECTED;
            return null;
        }
    }

    private static ClassWriter newFrameWriter() {
        return new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String a, final String b) {
                return "java/lang/Object";
            }
        };
    }

    // ------------------------------------------------------------------ TriangleList

    private static byte[] patchTriangleList(final byte[] original) {
        final TriangleListSurvey survey = surveyTriangleList(original);
        final ClassWriter writer = newFrameWriter();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (mv == null) {
                                    return null;
                                }
                                if ("a".equals(name) && ("(" + TRI_DESC + ")Z").equals(desc)) {
                                    final int ptLocal = survey.addMaxLocals;
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                final Label skip = new Label();
                                                super.visitInsn(DUP);
                                                super.visitJumpInsn(IFEQ, skip);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_LIST_INTERNAL_NAME,
                                                        LINKED_HASH_SET_FIELD,
                                                        LINKED_HASH_SET_DESC);
                                                super.visitVarInsn(ALOAD, 1);
                                                emitCorner(super.mv, 1, "a", ptLocal);
                                                emitCorner(super.mv, 1, "b", ptLocal);
                                                emitCorner(super.mv, 1, "c", ptLocal);
                                                super.visitMethodInsn(
                                                        INVOKESTATIC, BRIDGE, "onAdd", BRIDGE_ON_ADD_DESC, false);
                                                super.visitLabel(skip);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                if ("b".equals(name) && ("(" + TRI_DESC + ")Z").equals(desc)) {
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                final Label skip = new Label();
                                                super.visitInsn(DUP);
                                                super.visitJumpInsn(IFEQ, skip);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_LIST_INTERNAL_NAME,
                                                        LINKED_HASH_SET_FIELD,
                                                        LINKED_HASH_SET_DESC);
                                                super.visitVarInsn(ALOAD, 1);
                                                super.visitMethodInsn(
                                                        INVOKESTATIC, BRIDGE, "onRemove", BRIDGE_ON_REMOVE_DESC, false);
                                                super.visitLabel(skip);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                if ("c".equals(name) && "()V".equals(desc)) {
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == RETURN) {
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_LIST_INTERNAL_NAME,
                                                        LINKED_HASH_SET_FIELD,
                                                        LINKED_HASH_SET_DESC);
                                                super.visitMethodInsn(
                                                        INVOKESTATIC, BRIDGE, "onClear", BRIDGE_ON_CLEAR_DESC, false);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                return mv;
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static void emitCorner(final MethodVisitor mv, final int triVar, final String getter, final int ptLocal) {
        mv.visitVarInsn(ALOAD, triVar);
        mv.visitMethodInsn(INVOKEVIRTUAL, TRIANGLE, getter, "()" + POINT_DESC, false);
        mv.visitVarInsn(ASTORE, ptLocal);
        mv.visitVarInsn(ALOAD, ptLocal);
        mv.visitVarInsn(ALOAD, ptLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getX", "()F", false);
        mv.visitVarInsn(ALOAD, ptLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getY", "()F", false);
        mv.visitVarInsn(ALOAD, ptLocal);
        mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
    }

    private static TriangleListSurvey surveyTriangleList(final byte[] original) {
        final TriangleListSurvey s = new TriangleListSurvey();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9) {
                            private String owner;

                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public org.objectweb.asm.FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final Object value) {
                                if (LINKED_HASH_SET_FIELD.equals(name) && LINKED_HASH_SET_DESC.equals(desc)) {
                                    s.hasSetField = true;
                                }
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("a".equals(name) && ("(" + TRI_DESC + ")Z").equals(desc)) {
                                    s.addFound = true;
                                    return new MethodVisitor(ASM9) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                s.addReturns++;
                                            }
                                        }

                                        @Override
                                        public void visitMaxs(final int maxStack, final int maxLocals) {
                                            s.addMaxLocals = maxLocals;
                                        }
                                    };
                                }
                                if ("b".equals(name) && ("(" + TRI_DESC + ")Z").equals(desc)) {
                                    s.removeFound = true;
                                    return new MethodVisitor(ASM9) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                s.removeReturns++;
                                            }
                                        }
                                    };
                                }
                                if ("c".equals(name) && "()V".equals(desc)) {
                                    s.clearFound = true;
                                    return new MethodVisitor(ASM9) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == RETURN) {
                                                s.clearReturns++;
                                            }
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public void visitEnd() {
                                require(TARGET_LIST_INTERNAL_NAME.equals(owner), "unexpected TriangleList owner");
                            }
                        },
                        0);
        require(s.hasSetField && s.addFound && s.removeFound && s.clearFound, "TriangleList contract missing");
        require(s.addReturns >= 1 && s.removeReturns >= 1 && s.clearReturns >= 1, "TriangleList returns missing");
        return s;
    }

    private static final class TriangleListSurvey {
        boolean hasSetField, addFound, removeFound, clearFound;
        int addReturns, removeReturns, clearReturns, addMaxLocals;
    }

    // ------------------------------------------------------------------ EdgeSet (k)

    private static byte[] patchEdgeSet(final byte[] original) {
        final EdgeSetSurvey survey = surveyEdgeSet(original);
        final ClassWriter writer = newFrameWriter();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (mv == null) {
                                    return null;
                                }
                                if ("a".equals(name) && ("(" + EDGE_DESC + ")Z").equals(desc)) {
                                    final int paLocal = survey.addMaxLocals;
                                    final int pbLocal = survey.addMaxLocals + 1;
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                final Label skip = new Label();
                                                super.visitInsn(DUP);
                                                super.visitJumpInsn(IFEQ, skip);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_EDGE_SET_INTERNAL_NAME,
                                                        ARRAY_LIST_FIELD,
                                                        ARRAY_LIST_DESC);
                                                super.visitVarInsn(ALOAD, 1);
                                                super.visitVarInsn(ALOAD, 1);
                                                super.visitMethodInsn(
                                                        INVOKEVIRTUAL, EDGE, "a", "()" + POINT_DESC, false);
                                                super.visitVarInsn(ASTORE, paLocal);
                                                super.visitVarInsn(ALOAD, 1);
                                                super.visitMethodInsn(
                                                        INVOKEVIRTUAL, EDGE, "b", "()" + POINT_DESC, false);
                                                super.visitVarInsn(ASTORE, pbLocal);
                                                super.visitVarInsn(ALOAD, paLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
                                                super.visitVarInsn(ALOAD, paLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getX", "()F", false);
                                                super.visitVarInsn(ALOAD, paLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getY", "()F", false);
                                                super.visitVarInsn(ALOAD, pbLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
                                                super.visitVarInsn(ALOAD, pbLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getX", "()F", false);
                                                super.visitVarInsn(ALOAD, pbLocal);
                                                super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getY", "()F", false);
                                                super.visitMethodInsn(
                                                        INVOKESTATIC,
                                                        BRIDGE,
                                                        "onEdgeAdd",
                                                        BRIDGE_ON_EDGE_ADD_DESC,
                                                        false);
                                                super.visitLabel(skip);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                if ("b".equals(name) && ("(" + EDGE_DESC + ")Z").equals(desc)) {
                                    return new MethodVisitor(ASM9, mv) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                final Label skip = new Label();
                                                super.visitInsn(DUP);
                                                super.visitJumpInsn(IFEQ, skip);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitVarInsn(ALOAD, 0);
                                                super.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_EDGE_SET_INTERNAL_NAME,
                                                        ARRAY_LIST_FIELD,
                                                        ARRAY_LIST_DESC);
                                                super.visitMethodInsn(
                                                        INVOKESTATIC,
                                                        BRIDGE,
                                                        "onEdgeMutate",
                                                        BRIDGE_ON_EDGE_MUTATE_DESC,
                                                        false);
                                                super.visitLabel(skip);
                                            }
                                            super.visitInsn(opcode);
                                        }
                                    };
                                }
                                if ("a".equals(name) && ("(" + EDGE_DESC + "Z)Z").equals(desc)) {
                                    return new OrdinalMethodVisitor(ASM9, mv) {
                                        @Override
                                        protected void onInstruction(final int ordinal) {
                                            if (ordinal == survey.containsInjectOrd) {
                                                final Label fallback = new Label();
                                                mv.visitVarInsn(ALOAD, 0);
                                                mv.visitVarInsn(ALOAD, 0);
                                                mv.visitFieldInsn(
                                                        GETFIELD,
                                                        TARGET_EDGE_SET_INTERNAL_NAME,
                                                        ARRAY_LIST_FIELD,
                                                        ARRAY_LIST_DESC);
                                                mv.visitVarInsn(ALOAD, 1);
                                                mv.visitMethodInsn(INVOKEVIRTUAL, EDGE, "a", "()" + POINT_DESC, false);
                                                mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
                                                mv.visitVarInsn(ALOAD, 1);
                                                mv.visitMethodInsn(INVOKEVIRTUAL, EDGE, "b", "()" + POINT_DESC, false);
                                                mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
                                                mv.visitVarInsn(ILOAD, 2);
                                                mv.visitMethodInsn(
                                                        INVOKESTATIC,
                                                        BRIDGE,
                                                        "containsEdge",
                                                        BRIDGE_CONTAINS_EDGE_DESC,
                                                        false);
                                                mv.visitInsn(DUP);
                                                mv.visitJumpInsn(IFLT, fallback);
                                                mv.visitInsn(IRETURN);
                                                mv.visitLabel(fallback);
                                                mv.visitInsn(POP);
                                            }
                                        }
                                    };
                                }
                                return mv;
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static EdgeSetSurvey surveyEdgeSet(final byte[] original) {
        final EdgeSetSurvey s = new EdgeSetSurvey();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9) {
                            private String owner;

                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public org.objectweb.asm.FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final Object value) {
                                if (ARRAY_LIST_FIELD.equals(name) && ARRAY_LIST_DESC.equals(desc)) {
                                    s.hasListField = true;
                                }
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("a".equals(name) && ("(" + EDGE_DESC + ")Z").equals(desc)) {
                                    s.addFound = true;
                                    return new MethodVisitor(ASM9) {
                                        @Override
                                        public void visitInsn(final int opcode) {
                                            if (opcode == IRETURN) {
                                                s.addReturns++;
                                            }
                                        }

                                        @Override
                                        public void visitMaxs(final int maxStack, final int maxLocals) {
                                            s.addMaxLocals = maxLocals;
                                        }
                                    };
                                }
                                if ("a".equals(name) && ("(" + EDGE_DESC + "Z)Z").equals(desc)) {
                                    s.containsFound = true;
                                    return new OrdinalScanner() {
                                        @Override
                                        public void visitMethodInsn(
                                                final int opcode,
                                                final String owner,
                                                final String mName,
                                                final String mDesc,
                                                final boolean itf) {
                                            final int ord = nextOrd();
                                            if (s.containsInjectOrd < 0
                                                    && opcode == INVOKESTATIC
                                                    && INTRINSICS.equals(owner)
                                                    && "checkNotNullParameter".equals(mName)
                                                    && ord <= PROLOGUE_MAX_INDEX) {
                                                s.containsInjectOrd = ord + 1;
                                            }
                                        }
                                    };
                                }
                                return null;
                            }

                            @Override
                            public void visitEnd() {
                                require(TARGET_EDGE_SET_INTERNAL_NAME.equals(owner), "unexpected EdgeSet owner");
                            }
                        },
                        0);
        require(
                s.hasListField && s.addFound && s.containsFound && s.addReturns >= 1 && s.containsInjectOrd > 0,
                "EdgeSet contract mismatch");
        return s;
    }

    private static final class EdgeSetSurvey {
        boolean hasListField, addFound, containsFound;
        int addReturns, addMaxLocals;
        int containsInjectOrd = -1;
    }

    // ------------------------------------------------------------------ Solver (h)

    private static byte[] patchSolver(final byte[] original) {
        final SolverSurvey survey = surveySolver(original);
        final ClassWriter writer = newFrameWriter();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (mv == null) {
                                    return null;
                                }
                                if ("a".equals(name)
                                        && (SOLVER_A_RECENT_DESC.equals(desc) || SOLVER_A_LEGACY_DESC.equals(desc))) {
                                    return emitSolverPointLocate(mv, survey);
                                }
                                if ("c".equals(name) && "()V".equals(desc)) {
                                    return emitSolverConstraints(mv, survey);
                                }
                                if ("d".equals(name) && "()V".equals(desc)) {
                                    return emitSolverCollinear(mv, survey);
                                }
                                return mv;
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static MethodVisitor emitSolverPointLocate(final MethodVisitor mv, final SolverSurvey s) {
        final Label fastHit = new Label();
        final Label fastEnd = new Label();
        final Label nativePath = new Label();
        final int foundLocal = s.aMaxLocals;
        return new OrdinalMethodVisitor(ASM9, mv) {
            @Override
            protected void onInstruction(final int ordinal) {
                if (ordinal == s.aIterStartOrd) {
                    mv.visitVarInsn(ALOAD, s.aTrisVar);
                    mv.visitVarInsn(ALOAD, s.aPointVar);
                    mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getX", "()F", false);
                    mv.visitVarInsn(ALOAD, s.aPointVar);
                    mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getY", "()F", false);
                    mv.visitVarInsn(ALOAD, s.aPointVar);
                    mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
                    mv.visitMethodInsn(
                            INVOKESTATIC, BRIDGE, "findContainingTriangle", BRIDGE_FIND_CONTAINING_DESC, false);
                    mv.visitVarInsn(ASTORE, foundLocal);
                    mv.visitVarInsn(ALOAD, foundLocal);
                    mv.visitJumpInsn(IFNULL, fastEnd);
                    mv.visitVarInsn(ALOAD, foundLocal);
                    mv.visitFieldInsn(GETSTATIC, BRIDGE, "FALLBACK", "Ljava/lang/Object;");
                    mv.visitJumpInsn(IF_ACMPEQ, nativePath);
                    mv.visitInsn(ACONST_NULL);
                    mv.visitVarInsn(ASTORE, s.aIterLocal);
                    mv.visitVarInsn(ALOAD, foundLocal);
                    mv.visitTypeInsn(CHECKCAST, TRIANGLE);
                    mv.visitVarInsn(ASTORE, s.aTriLocal);
                    mv.visitJumpInsn(GOTO, fastHit);
                    mv.visitLabel(nativePath);
                } else if (ordinal == s.aHitOrd) {
                    mv.visitLabel(fastHit);
                } else if (ordinal == s.aLoopEndOrd) {
                    mv.visitLabel(fastEnd);
                }
            }
        };
    }

    private static MethodVisitor emitSolverConstraints(final MethodVisitor mv, final SolverSurvey s) {
        final Label nativePhase1 = new Label();
        final Label phase2 = new Label();
        final Label loopCheck = new Label();
        final int pairsLocal = s.cMaxLocals;
        final int idxLocal = s.cMaxLocals + 1;
        return new OrdinalMethodVisitor(ASM9, mv) {
            @Override
            protected void onInstruction(final int ordinal) {
                if (ordinal == s.cPhase1StartOrd) {
                    mv.visitVarInsn(ALOAD, s.cTrisVar);
                    mv.visitVarInsn(ALOAD, s.cKVar);
                    mv.visitMethodInsn(
                            INVOKESTATIC, BRIDGE, "findIntersectingEdgePairs", BRIDGE_FIND_INTERSECTING_DESC, false);
                    mv.visitVarInsn(ASTORE, pairsLocal);
                    mv.visitVarInsn(ALOAD, pairsLocal);
                    mv.visitJumpInsn(IFNULL, nativePhase1);
                    mv.visitInsn(ICONST_0);
                    mv.visitVarInsn(ISTORE, idxLocal);
                    mv.visitLabel(loopCheck);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitVarInsn(ALOAD, pairsLocal);
                    mv.visitInsn(ARRAYLENGTH);
                    mv.visitJumpInsn(IF_ICMPGE, phase2);
                    mv.visitVarInsn(ALOAD, s.cList7Var);
                    mv.visitTypeInsn(NEW, EDGE);
                    mv.visitInsn(DUP);
                    mv.visitVarInsn(ALOAD, pairsLocal);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(AALOAD);
                    mv.visitTypeInsn(CHECKCAST, POINT);
                    mv.visitVarInsn(ALOAD, pairsLocal);
                    mv.visitVarInsn(ILOAD, idxLocal);
                    mv.visitInsn(ICONST_1);
                    mv.visitInsn(IADD);
                    mv.visitInsn(AALOAD);
                    mv.visitTypeInsn(CHECKCAST, POINT);
                    mv.visitMethodInsn(INVOKESPECIAL, EDGE, "<init>", "(" + POINT_DESC + POINT_DESC + ")V", false);
                    mv.visitMethodInsn(INVOKEVIRTUAL, "java/util/ArrayList", "add", "(Ljava/lang/Object;)Z", false);
                    mv.visitInsn(POP);
                    mv.visitIincInsn(idxLocal, 2);
                    mv.visitJumpInsn(GOTO, loopCheck);
                    mv.visitLabel(nativePhase1);
                } else if (ordinal == s.cPhase2StartOrd) {
                    mv.visitLabel(phase2);
                }
            }
        };
    }

    private static MethodVisitor emitSolverCollinear(final MethodVisitor mv, final SolverSurvey s) {
        final Label fastLoopHead = new Label();
        final Label skipInnerLoop = new Label();
        final Label nativeIter = new Label();
        final int candLocal = s.dMaxLocals;
        return new OrdinalMethodVisitor(ASM9, mv) {
            @Override
            protected void onInstruction(final int ordinal) {
                if (ordinal == s.dIterCallOrd) {
                    mv.visitVarInsn(ALOAD, s.dEdgesVar);
                    mv.visitVarInsn(ALOAD, s.dPtVar);
                    mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getX", "()F", false);
                    mv.visitVarInsn(ALOAD, s.dPtVar);
                    mv.visitMethodInsn(INVOKEVIRTUAL, POINT, "getY", "()F", false);
                    mv.visitVarInsn(ILOAD, s.dIdxVar);
                    mv.visitMethodInsn(
                            INVOKESTATIC, BRIDGE, "findCollinearCandidates", BRIDGE_FIND_COLLINEAR_DESC, false);
                    mv.visitVarInsn(ASTORE, candLocal);
                    mv.visitVarInsn(ALOAD, candLocal);
                    mv.visitJumpInsn(IFNULL, skipInnerLoop);
                    mv.visitVarInsn(ALOAD, candLocal);
                    mv.visitFieldInsn(GETSTATIC, BRIDGE, "FALLBACK", "Ljava/lang/Object;");
                    mv.visitJumpInsn(IF_ACMPEQ, nativeIter);
                    mv.visitVarInsn(ALOAD, candLocal);
                    mv.visitTypeInsn(CHECKCAST, "java/util/Iterator");
                    mv.visitVarInsn(ASTORE, s.dIterVar);
                    mv.visitJumpInsn(GOTO, fastLoopHead);
                    mv.visitLabel(nativeIter);
                } else if (ordinal == s.dLoopHeadOrd) {
                    mv.visitLabel(fastLoopHead);
                } else if (ordinal == s.dAfterInnerOrd) {
                    mv.visitLabel(skipInnerLoop);
                }
            }
        };
    }

    private static SolverSurvey surveySolver(final byte[] original) {
        final SolverSurvey s = new SolverSurvey();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9) {
                            private String owner;

                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if ("a".equals(name)
                                        && (SOLVER_A_RECENT_DESC.equals(desc) || SOLVER_A_LEGACY_DESC.equals(desc))) {
                                    s.aFound = true;
                                    final boolean recent = SOLVER_A_RECENT_DESC.equals(desc);
                                    s.aPointVar = recent ? 1 : 2;
                                    s.aTrisVar = recent ? 2 : 3;
                                    return surveyPointLocate(s);
                                }
                                if ("c".equals(name) && "()V".equals(desc)) {
                                    s.cFound = true;
                                    return surveyConstraints(s);
                                }
                                if ("d".equals(name) && "()V".equals(desc)) {
                                    s.dFound = true;
                                    return surveyCollinear(s);
                                }
                                return null;
                            }

                            @Override
                            public void visitEnd() {
                                require(TARGET_SOLVER_INTERNAL_NAME.equals(owner), "unexpected solver owner");
                            }
                        },
                        0);
        require(
                s.aFound
                        && s.aIterStartOrd >= 0
                        && s.aIterLocal >= 0
                        && s.aTriLocal >= 0
                        && s.aHitOrd > s.aIterStartOrd
                        && s.aLoopEndOrd > s.aHitOrd,
                "solver a() contract mismatch");
        require(
                s.cFound
                        && s.cKVar >= 0
                        && s.cTrisVar >= 0
                        && s.cList7Var >= 0
                        && s.cPhase1StartOrd > 0
                        && s.cPhase2StartOrd > s.cPhase1StartOrd,
                "solver c() contract mismatch");
        require(
                s.dFound
                        && s.dEdgesVar >= 0
                        && s.dPtVar >= 0
                        && s.dIdxVar >= 0
                        && s.dIterVar >= 0
                        && s.dIterCallOrd > 0
                        && s.dLoopHeadOrd > s.dIterCallOrd
                        && s.dAfterInnerOrd > s.dLoopHeadOrd,
                "solver d() contract mismatch");
        return s;
    }

    private static MethodVisitor surveyPointLocate(final SolverSurvey s) {
        return new OrdinalScanner() {
            int lastVarOp = -1, lastVarSlot = -1;
            boolean sawIterCall, sawCheckHasNext, sawTriangleContainCall, sawTriangleIndexCall, awaitingTriStore;
            Label loopEndLabel, hitLabel;

            @Override
            public void visitLabel(final Label label) {
                if (label == hitLabel && s.aHitOrd < 0) {
                    s.aHitOrd = peekOrd();
                }
                if (label == loopEndLabel && s.aLoopEndOrd < 0) {
                    s.aLoopEndOrd = peekOrd();
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int var) {
                final int ord = nextOrd();
                if (sawIterCall && opcode == ASTORE && s.aIterLocal < 0) {
                    s.aIterLocal = var;
                    sawIterCall = false;
                } else if (awaitingTriStore && opcode == ASTORE && s.aTriLocal < 0) {
                    s.aTriLocal = var;
                    awaitingTriStore = false;
                }
                lastVarOp = opcode;
                lastVarSlot = var;
                if (opcode == ALOAD && var == s.aTrisVar) {
                    lastLoadTrisOrd = ord;
                }
            }

            int lastLoadTrisOrd = -1;

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                nextOrd();
                if (opcode == CHECKCAST && TRIANGLE.equals(type) && s.aTriLocal < 0) {
                    awaitingTriStore = true;
                }
            }

            @Override
            public void visitMethodInsn(
                    final int opcode, final String owner, final String name, final String desc, final boolean itf) {
                nextOrd();
                if (opcode == INVOKEVIRTUAL
                        && TARGET_LIST_INTERNAL_NAME.equals(owner)
                        && "iterator".equals(name)
                        && "()Ljava/util/Iterator;".equals(desc)
                        && lastLoadTrisOrd >= 0
                        && s.aIterStartOrd < 0) {
                    s.aIterStartOrd = lastLoadTrisOrd;
                    sawIterCall = true;
                } else if (opcode == INVOKEINTERFACE
                        && "java/util/Iterator".equals(owner)
                        && "hasNext".equals(name)
                        && s.aIterLocal >= 0
                        && lastVarOp == ALOAD
                        && lastVarSlot == s.aIterLocal) {
                    sawCheckHasNext = true;
                } else if (opcode == INVOKEVIRTUAL
                        && TRIANGLE.equals(owner)
                        && "a".equals(name)
                        && ("(L" + GVECTOR2 + ";)Z").equals(desc)) {
                    sawTriangleContainCall = true;
                } else if (sawTriangleContainCall
                        && opcode == INVOKEVIRTUAL
                        && TRIANGLE.equals(owner)
                        && "a".equals(name)
                        && "(I)Z".equals(desc)) {
                    sawTriangleIndexCall = true;
                }
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                nextOrd();
                if (sawCheckHasNext && opcode == IFEQ && loopEndLabel == null) {
                    loopEndLabel = label;
                    sawCheckHasNext = false;
                } else if (sawTriangleIndexCall && opcode == IFEQ && hitLabel == null) {
                    hitLabel = label;
                    sawTriangleIndexCall = false;
                }
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                s.aMaxLocals = maxLocals;
            }
        };
    }

    private static MethodVisitor surveyConstraints(final SolverSurvey s) {
        return new OrdinalScanner() {
            int lastVarOp = -1, lastVarSlot = -1;
            boolean expectingKStore, expectingTrisStore, arrayListNew, arrayListDup, arrayListInit, expectingList7Store;
            boolean sawList7IterKCall, expectingIterKStore, sawCheckHasNext;
            int kIterLocal = -1;
            Label phase2Label;

            @Override
            public void visitLabel(final Label label) {
                if (label == phase2Label && s.cPhase2StartOrd < 0) {
                    s.cPhase2StartOrd = peekOrd();
                }
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                nextOrd();
                if (opcode == NEW && "java/util/ArrayList".equals(type) && s.cPhase1StartOrd < 0) {
                    arrayListNew = true;
                    arrayListDup = false;
                    arrayListInit = false;
                } else {
                    arrayListNew = false;
                }
            }

            @Override
            public void visitInsn(final int opcode) {
                nextOrd();
                if (opcode == DUP && arrayListNew) {
                    arrayListDup = true;
                } else {
                    arrayListNew = false;
                    arrayListDup = false;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int var) {
                final int ord = nextOrd();
                if (expectingKStore && opcode == ASTORE) {
                    s.cKVar = var;
                    expectingKStore = false;
                } else if (expectingTrisStore && opcode == ASTORE) {
                    s.cTrisVar = var;
                    expectingTrisStore = false;
                } else if (expectingList7Store && opcode == ASTORE) {
                    s.cList7Var = var;
                    s.cPhase1StartOrd = ord + 1;
                    expectingList7Store = false;
                    sawList7IterKCall = true;
                } else if (expectingIterKStore && opcode == ASTORE) {
                    kIterLocal = var;
                    expectingIterKStore = false;
                }
                lastVarOp = opcode;
                lastVarSlot = var;
            }

            @Override
            public void visitMethodInsn(
                    final int opcode, final String owner, final String name, final String desc, final boolean itf) {
                nextOrd();
                if (opcode == INVOKEVIRTUAL
                        && CONTOUR_INPUT.equals(owner)
                        && "b".equals(name)
                        && ("()" + EDGE_SET_DESC).equals(desc)
                        && s.cKVar < 0) {
                    expectingKStore = true;
                } else if (opcode == INVOKEVIRTUAL
                        && CONTOUR_INPUT.equals(owner)
                        && "d".equals(name)
                        && ("()" + LIST_DESC).equals(desc)
                        && s.cTrisVar < 0) {
                    expectingTrisStore = true;
                } else if (arrayListDup
                        && opcode == INVOKESPECIAL
                        && "java/util/ArrayList".equals(owner)
                        && "<init>".equals(name)
                        && "()V".equals(desc)) {
                    arrayListInit = true;
                    arrayListNew = false;
                    arrayListDup = false;
                    expectingList7Store = true;
                } else if (sawList7IterKCall
                        && opcode == INVOKEVIRTUAL
                        && TARGET_EDGE_SET_INTERNAL_NAME.equals(owner)
                        && EDGE_ITERATOR_METHODS.contains(name)
                        && "()Ljava/util/Iterator;".equals(desc)
                        && kIterLocal < 0) {
                    sawList7IterKCall = false;
                    expectingIterKStore = true;
                } else if (kIterLocal >= 0
                        && phase2Label == null
                        && opcode == INVOKEINTERFACE
                        && "java/util/Iterator".equals(owner)
                        && "hasNext".equals(name)
                        && lastVarOp == ALOAD
                        && lastVarSlot == kIterLocal) {
                    sawCheckHasNext = true;
                }
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                nextOrd();
                if (sawCheckHasNext && opcode == IFEQ && phase2Label == null) {
                    phase2Label = label;
                    sawCheckHasNext = false;
                }
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                s.cMaxLocals = maxLocals;
            }
        };
    }

    private static MethodVisitor surveyCollinear(final SolverSurvey s) {
        return new OrdinalScanner() {
            int lastVarOp = -1, lastVarSlot = -1, lastAloadOrd = -1;
            boolean expectingEdgesStore, sawListGet, expectingPtStore, expectingIterStore, sawHasNext;
            Label afterInnerLabel;

            @Override
            public void visitLabel(final Label label) {
                if (label == afterInnerLabel && s.dAfterInnerOrd < 0) {
                    s.dAfterInnerOrd = peekOrd();
                }
            }

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                nextOrd();
                if (opcode == CHECKCAST && POINT.equals(type) && sawListGet && s.dPtVar < 0) {
                    expectingPtStore = true;
                    sawListGet = false;
                }
            }

            @Override
            public void visitVarInsn(final int opcode, final int var) {
                final int ord = nextOrd();
                if (expectingEdgesStore && opcode == ASTORE && s.dEdgesVar < 0) {
                    s.dEdgesVar = var;
                    expectingEdgesStore = false;
                } else if (expectingPtStore && opcode == ASTORE && s.dPtVar < 0) {
                    s.dPtVar = var;
                    expectingPtStore = false;
                } else if (expectingIterStore && opcode == ASTORE && s.dIterVar < 0) {
                    s.dIterVar = var;
                    s.dLoopHeadOrd = ord + 1;
                    expectingIterStore = false;
                }
                if (opcode == ALOAD) {
                    lastAloadOrd = ord;
                }
                lastVarOp = opcode;
                lastVarSlot = var;
            }

            @Override
            public void visitMethodInsn(
                    final int opcode, final String owner, final String name, final String desc, final boolean itf) {
                nextOrd();
                if (opcode == INVOKEVIRTUAL
                        && TARGET_LIST_INTERNAL_NAME.equals(owner)
                        && "b".equals(name)
                        && ("()" + EDGE_SET_DESC).equals(desc)
                        && s.dEdgesVar < 0) {
                    expectingEdgesStore = true;
                } else if (opcode == INVOKEINTERFACE
                        && "java/util/List".equals(owner)
                        && "get".equals(name)
                        && lastVarOp == ILOAD
                        && s.dIdxVar < 0) {
                    s.dIdxVar = lastVarSlot;
                    sawListGet = true;
                } else if (opcode == INVOKEVIRTUAL
                        && TARGET_EDGE_SET_INTERNAL_NAME.equals(owner)
                        && EDGE_ITERATOR_METHODS.contains(name)
                        && "()Ljava/util/Iterator;".equals(desc)
                        && s.dEdgesVar >= 0
                        && lastVarOp == ALOAD
                        && lastVarSlot == s.dEdgesVar
                        && s.dIterCallOrd < 0) {
                    s.dIterCallOrd = lastAloadOrd;
                    expectingIterStore = true;
                } else if (s.dIterVar >= 0
                        && afterInnerLabel == null
                        && opcode == INVOKEINTERFACE
                        && "java/util/Iterator".equals(owner)
                        && "hasNext".equals(name)
                        && lastVarOp == ALOAD
                        && lastVarSlot == s.dIterVar) {
                    sawHasNext = true;
                }
            }

            @Override
            public void visitJumpInsn(final int opcode, final Label label) {
                nextOrd();
                if (sawHasNext && opcode == IFEQ && afterInnerLabel == null) {
                    afterInnerLabel = label;
                    sawHasNext = false;
                }
            }

            @Override
            public void visitMaxs(final int maxStack, final int maxLocals) {
                s.dMaxLocals = maxLocals;
            }
        };
    }

    private static final class SolverSurvey {
        boolean aFound, cFound, dFound;
        int aPointVar = -1, aTrisVar = -1, aIterLocal = -1, aTriLocal = -1;
        int aIterStartOrd = -1, aHitOrd = -1, aLoopEndOrd = -1, aMaxLocals;
        int cKVar = -1, cTrisVar = -1, cList7Var = -1;
        int cPhase1StartOrd = -1, cPhase2StartOrd = -1, cMaxLocals;
        int dEdgesVar = -1, dPtVar = -1, dIdxVar = -1, dIterVar = -1;
        int dIterCallOrd = -1, dLoopHeadOrd = -1, dAfterInnerOrd = -1, dMaxLocals;
    }

    // ------------------------------------------------------------------ BaseSolver (a)

    private static byte[] patchBaseSolver(final byte[] original) {
        final int entryOrd = surveyBaseSolver(original);
        final ClassWriter writer = newFrameWriter();
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor mv = super.visitMethod(access, name, desc, signature, exceptions);
                                if (mv == null || !"b".equals(name) || !"()V".equals(desc)) {
                                    return mv;
                                }
                                return new OrdinalMethodVisitor(ASM9, mv) {
                                    @Override
                                    protected void onInstruction(final int ordinal) {
                                        if (ordinal == entryOrd) {
                                            final Label nativePip = new Label();
                                            mv.visitVarInsn(ALOAD, 0);
                                            mv.visitFieldInsn(
                                                    GETFIELD, TARGET_BASE_SOLVER_INTERNAL_NAME, "a", INPUT_DESC);
                                            mv.visitMethodInsn(
                                                    INVOKEVIRTUAL, CONTOUR_INPUT, "a", "()Ljava/util/List;", false);
                                            mv.visitVarInsn(ALOAD, 0);
                                            mv.visitFieldInsn(
                                                    GETFIELD, TARGET_BASE_SOLVER_INTERNAL_NAME, "a", INPUT_DESC);
                                            mv.visitMethodInsn(
                                                    INVOKEVIRTUAL, CONTOUR_INPUT, "c", "()Ljava/util/List;", false);
                                            mv.visitVarInsn(ALOAD, 0);
                                            mv.visitFieldInsn(
                                                    GETFIELD, TARGET_BASE_SOLVER_INTERNAL_NAME, "a", INPUT_DESC);
                                            mv.visitMethodInsn(
                                                    INVOKEVIRTUAL, CONTOUR_INPUT, "d", "()" + LIST_DESC, false);
                                            mv.visitMethodInsn(
                                                    INVOKESTATIC,
                                                    BRIDGE,
                                                    "pruneOutsideContours",
                                                    BRIDGE_PRUNE_CONTOURS_DESC,
                                                    false);
                                            mv.visitJumpInsn(IFEQ, nativePip);
                                            mv.visitInsn(RETURN);
                                            mv.visitLabel(nativePip);
                                        }
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_FRAMES);
        return writer.toByteArray();
    }

    private static int surveyBaseSolver(final byte[] original) {
        final int[] result = {-1};
        final boolean[] foundField = {false};
        final boolean[] foundMethod = {false};
        new ClassReader(original)
                .accept(
                        new ClassVisitor(ASM9) {
                            private String owner;

                            @Override
                            public void visit(
                                    final int version,
                                    final int access,
                                    final String name,
                                    final String signature,
                                    final String superName,
                                    final String[] interfaces) {
                                owner = name;
                            }

                            @Override
                            public org.objectweb.asm.FieldVisitor visitField(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final Object value) {
                                if ("a".equals(name) && INPUT_DESC.equals(desc)) {
                                    foundField[0] = true;
                                }
                                return null;
                            }

                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String desc,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!"b".equals(name) || !"()V".equals(desc)) {
                                    return null;
                                }
                                foundMethod[0] = true;
                                return new OrdinalScanner() {
                                    int lastAload0 = -1;
                                    boolean sawNullCheck;

                                    @Override
                                    public void visitVarInsn(final int opcode, final int var) {
                                        final int ord = nextOrd();
                                        if (opcode == ALOAD && var == 0) {
                                            lastAload0 = ord;
                                        }
                                    }

                                    @Override
                                    public void visitJumpInsn(final int opcode, final Label label) {
                                        nextOrd();
                                        if (opcode == IFNONNULL && !sawNullCheck) {
                                            sawNullCheck = true;
                                        }
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String mOwner,
                                            final String mName,
                                            final String mDesc,
                                            final boolean itf) {
                                        nextOrd();
                                        if (sawNullCheck
                                                && result[0] < 0
                                                && opcode == INVOKEVIRTUAL
                                                && CONTOUR_INPUT.equals(mOwner)
                                                && "d".equals(mName)
                                                && ("()" + LIST_DESC).equals(mDesc)) {
                                            result[0] = lastAload0;
                                        }
                                    }
                                };
                            }

                            @Override
                            public void visitEnd() {
                                require(TARGET_BASE_SOLVER_INTERNAL_NAME.equals(owner), "unexpected base solver owner");
                            }
                        },
                        0);
        require(foundField[0] && foundMethod[0] && result[0] > 0, "base solver b() contract mismatch");
        return result[0];
    }

    // ------------------------------------------------------------------ Shared ASM ordinal utilities

    private static void require(final boolean condition, final String reason) {
        if (!condition) {
            throw new IllegalArgumentException(reason);
        }
    }

    private abstract static class OrdinalScanner extends MethodVisitor {
        private int ord;

        OrdinalScanner() {
            super(ASM9);
        }

        int peekOrd() {
            return ord;
        }

        int nextOrd() {
            return ord++;
        }

        @Override
        public void visitInsn(final int opcode) {
            nextOrd();
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            nextOrd();
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            nextOrd();
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            nextOrd();
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            nextOrd();
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            nextOrd();
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final org.objectweb.asm.Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            nextOrd();
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            nextOrd();
        }

        @Override
        public void visitLdcInsn(final Object value) {
            nextOrd();
        }

        @Override
        public void visitIincInsn(final int var, final int increment) {
            nextOrd();
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            nextOrd();
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            nextOrd();
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            nextOrd();
        }
    }

    private abstract static class OrdinalMethodVisitor extends MethodVisitor {
        private int ord;

        OrdinalMethodVisitor(final int api, final MethodVisitor mv) {
            super(api, mv);
        }

        protected abstract void onInstruction(int ordinal);

        @Override
        public void visitInsn(final int opcode) {
            onInstruction(ord++);
            super.visitInsn(opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            onInstruction(ord++);
            super.visitIntInsn(opcode, operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            onInstruction(ord++);
            super.visitVarInsn(opcode, var);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            onInstruction(ord++);
            super.visitTypeInsn(opcode, type);
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String name, final String descriptor) {
            onInstruction(ord++);
            super.visitFieldInsn(opcode, owner, name, descriptor);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            onInstruction(ord++);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
        }

        @Override
        public void visitInvokeDynamicInsn(
                final String name,
                final String descriptor,
                final org.objectweb.asm.Handle bootstrapMethodHandle,
                final Object... bootstrapMethodArguments) {
            onInstruction(ord++);
            super.visitInvokeDynamicInsn(name, descriptor, bootstrapMethodHandle, bootstrapMethodArguments);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            onInstruction(ord++);
            super.visitJumpInsn(opcode, label);
        }

        @Override
        public void visitLdcInsn(final Object value) {
            onInstruction(ord++);
            super.visitLdcInsn(value);
        }

        @Override
        public void visitIincInsn(final int var, final int increment) {
            onInstruction(ord++);
            super.visitIincInsn(var, increment);
        }

        @Override
        public void visitTableSwitchInsn(final int min, final int max, final Label dflt, final Label... labels) {
            onInstruction(ord++);
            super.visitTableSwitchInsn(min, max, dflt, labels);
        }

        @Override
        public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            onInstruction(ord++);
            super.visitLookupSwitchInsn(dflt, keys, labels);
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            onInstruction(ord++);
            super.visitMultiANewArrayInsn(descriptor, numDimensions);
        }
    }
}
