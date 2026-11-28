package dev.turboism.adapter.cubism.optimization.atlas;

import static org.objectweb.asm.Opcodes.*;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Type;

/**
 * Rewrites {@code GEditableMesh2.updateIndices} to apply off-EDT triangulation results directly
 * without repeating outline extraction or solver construction, and replaces {@code triangulation.g}'s
 * quadratic incidence loop with linear two-pass table construction.
 */
public final class TextureAtlasMeshApplyTransformer implements ClassFileTransformer {

    public static final String MESH = TextureAtlasMeshSolverTransformer.MESH;
    public static final String MESH_OPS = TextureAtlasMeshSolverTransformer.MESH_OPS;
    public static final String NON_DELAUNAY_SOLVER = TextureAtlasMeshSolverTransformer.TRIANGULATION + "g";

    private static final String UTIL_PREFIX = "com/live2d/util/";
    private static final String UPDATE_INDICES_METHOD = "updateIndices";
    private static final String USE_DELAUNAY_FIELD = "useDelaunayTriangulation";
    private static final String EDGE_VERSION_FIELD = "_edge_edit_version";
    private static final String CACHE_VERSION_FIELD = "cache_version_gl_indices";
    private static final String PARALLEL_BRIDGE = Type.getInternalName(TextureAtlasParallelMeshes.class);
    private static final String TRY_APPLY_METHOD = "tryApplyPrepared";
    private static final String TRY_APPLY_DESC = "(Ljava/lang/Object;)Z";
    private static final String FAST_OPS = Type.getInternalName(TextureAtlasMeshFastOps.class);
    private static final String BUILD_INCIDENCE = "buildIncidentEdgeTable";
    private static final String BUILD_INCIDENCE_DESC = "(Ljava/util/List;Ljava/util/List;)[[I";
    private static final String NON_DELAUNAY_METHOD = "a";
    private static final String SYSTEM_INTERNAL = "java/lang/System";
    private static final String ARRAYCOPY_METHOD = "arraycopy";
    private static final int INCIDENT_ARRAY_SLOT = 8;
    private static final int VISITED_ARRAY_SLOT = 7;
    private static final int BOUNDARY_LIST_SLOT = 4;
    private static final int POINTS_LIST_SLOT = 3;
    private static final int TEMP_INDEX_SLOT = 10;

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, classfileBuffer);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (classfileBuffer == null || classBeingRedefined != null) {
            return null;
        }
        return patch(className, classfileBuffer);
    }

    /** Patches {@code GEditableMesh2}, {@code editableMesh.b}, or {@code triangulation.g} when applicable. */
    public static byte[] patch(final String className, final byte[] bytes) {
        if (bytes == null) return null;
        if (MESH_OPS.equals(className)) {
            return TextureAtlasMeshSolverTransformer.transform(bytes);
        }
        if (MESH.equals(className)) {
            return patchEditableMesh(bytes);
        }
        if (NON_DELAUNAY_SOLVER.equals(className)) {
            return patchNonDelaunayIncidence(bytes);
        }
        return null;
    }

    static byte[] patchEditableMesh(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!MESH.equals(reader.getClassName())) return null;
        final MeshScan scan = new MeshScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(new MeshPatchVisitor(writer, scan.progressType), ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    static byte[] patchNonDelaunayIncidence(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        if (!NON_DELAUNAY_SOLVER.equals(reader.getClassName())) return null;
        final NonDelaunayScan scan = new NonDelaunayScan();
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        reader.accept(
                new NonDelaunayPatchVisitor(writer, scan.methodDesc),
                ClassReader.EXPAND_FRAMES | ClassReader.SKIP_DEBUG);
        return writer.toByteArray();
    }

    private static boolean isUpdateIndicesDesc(final String desc) {
        return desc.startsWith("(L" + UTIL_PREFIX) && desc.endsWith(";)V");
    }

    private static String extractProgressInternal(final String desc) {
        return desc.substring(2, desc.length() - 3);
    }

    private static boolean isNonDelaunaySolve(final String name, final String desc) {
        return NON_DELAUNAY_METHOD.equals(name)
                && desc.startsWith("(L" + MESH + ";L" + UTIL_PREFIX)
                && desc.endsWith(";)V");
    }

    private static final class MeshScan extends ClassVisitor {
        private String progressType;
        private boolean alreadyPatched;
        private int updateMethods;
        private int delaunayChecks;
        private int triFallbackCalls;
        private int versionAssigns;
        private boolean sawDelaunayGet;
        private boolean sawEdgeVersionGet;

        MeshScan() {
            super(ASM9);
        }

        boolean matches() {
            return progressType != null
                    && updateMethods == 1
                    && delaunayChecks == 1
                    && triFallbackCalls == 1
                    && versionAssigns == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!UPDATE_INDICES_METHOD.equals(name) || !isUpdateIndicesDesc(desc)) {
                return null;
            }
            updateMethods++;
            progressType = extractProgressInternal(desc);
            return new MethodVisitor(ASM9) {
                @Override
                public void visitFieldInsn(final int op, final String owner, final String f, final String fd) {
                    sawDelaunayGet = op == GETFIELD && MESH.equals(owner) && USE_DELAUNAY_FIELD.equals(f);
                    if (op == GETFIELD && MESH.equals(owner) && EDGE_VERSION_FIELD.equals(f)) {
                        sawEdgeVersionGet = true;
                        return;
                    }
                    if (sawEdgeVersionGet && op == PUTFIELD && MESH.equals(owner) && CACHE_VERSION_FIELD.equals(f)) {
                        versionAssigns++;
                    }
                    sawEdgeVersionGet = false;
                }

                @Override
                public void visitJumpInsn(final int op, final Label label) {
                    if (sawDelaunayGet && op == IFEQ) {
                        delaunayChecks++;
                    }
                    sawDelaunayGet = false;
                    sawEdgeVersionGet = false;
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    sawDelaunayGet = false;
                    sawEdgeVersionGet = false;
                    if (op == INVOKEVIRTUAL && NON_DELAUNAY_SOLVER.equals(owner) && NON_DELAUNAY_METHOD.equals(m)) {
                        triFallbackCalls++;
                    } else if (op == INVOKESTATIC && PARALLEL_BRIDGE.equals(owner) && TRY_APPLY_METHOD.equals(m)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class MeshPatchVisitor extends ClassVisitor {
        private final String expectedDesc;

        MeshPatchVisitor(final ClassWriter writer, final String progressType) {
            super(ASM9, writer);
            this.expectedDesc = "(L" + progressType + ";)V";
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!UPDATE_INDICES_METHOD.equals(name) || !expectedDesc.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                private final Label assignVersionLabel = new Label();
                private boolean afterDelaunayField;
                private boolean awaitingAssignVersion;
                private boolean assignedLabelPlaced;

                @Override
                public void visitFieldInsn(final int op, final String owner, final String f, final String fd) {
                    afterDelaunayField = op == GETFIELD && MESH.equals(owner) && USE_DELAUNAY_FIELD.equals(f);
                    super.visitFieldInsn(op, owner, f, fd);
                }

                @Override
                public void visitJumpInsn(final int op, final Label label) {
                    super.visitJumpInsn(op, label);
                    if (afterDelaunayField && op == IFEQ) {
                        afterDelaunayField = false;
                        super.visitVarInsn(ALOAD, 0);
                        super.visitMethodInsn(INVOKESTATIC, PARALLEL_BRIDGE, TRY_APPLY_METHOD, TRY_APPLY_DESC, false);
                        super.visitJumpInsn(IFNE, assignVersionLabel);
                    }
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    afterDelaunayField = false;
                    super.visitMethodInsn(op, owner, m, d, itf);
                    if (op == INVOKEVIRTUAL && NON_DELAUNAY_SOLVER.equals(owner) && NON_DELAUNAY_METHOD.equals(m)) {
                        awaitingAssignVersion = true;
                    }
                }

                @Override
                public void visitVarInsn(final int op, final int varIndex) {
                    afterDelaunayField = false;
                    if (awaitingAssignVersion && !assignedLabelPlaced && op == ALOAD && varIndex == 0) {
                        awaitingAssignVersion = false;
                        assignedLabelPlaced = true;
                        super.visitLabel(assignVersionLabel);
                    }
                    super.visitVarInsn(op, varIndex);
                }
            };
        }
    }

    private static final class NonDelaunayScan extends ClassVisitor {
        private String methodDesc;
        private boolean alreadyPatched;
        private int methodHits;
        private int arraycopyCalls;
        private int boolVisitedStores;
        private boolean afterBoolNewArray;

        NonDelaunayScan() {
            super(ASM9);
        }

        boolean matches() {
            return methodDesc != null && methodHits == 1 && arraycopyCalls == 1 && boolVisitedStores == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (!isNonDelaunaySolve(name, desc)) {
                return null;
            }
            methodHits++;
            methodDesc = desc;
            return new MethodVisitor(ASM9) {
                @Override
                public void visitIntInsn(final int op, final int operand) {
                    afterBoolNewArray = op == NEWARRAY && operand == T_BOOLEAN;
                }

                @Override
                public void visitVarInsn(final int op, final int varIndex) {
                    if (afterBoolNewArray && op == ASTORE && varIndex == VISITED_ARRAY_SLOT) {
                        boolVisitedStores++;
                    }
                    afterBoolNewArray = false;
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    afterBoolNewArray = false;
                    if (op == INVOKESTATIC && SYSTEM_INTERNAL.equals(owner) && ARRAYCOPY_METHOD.equals(m)) {
                        arraycopyCalls++;
                    } else if (op == INVOKESTATIC && FAST_OPS.equals(owner) && BUILD_INCIDENCE.equals(m)) {
                        alreadyPatched = true;
                    }
                }
            };
        }
    }

    private static final class NonDelaunayPatchVisitor extends ClassVisitor {
        private final String targetDesc;

        NonDelaunayPatchVisitor(final ClassWriter writer, final String targetDesc) {
            super(ASM9, writer);
            this.targetDesc = targetDesc;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, ex);
            if (!isNonDelaunaySolve(name, desc) || !targetDesc.equals(desc)) {
                return output;
            }
            return new MethodVisitor(ASM9, output) {
                private boolean afterBoolNewArray;
                private boolean skippingIncidence;
                private boolean sawArraycopy;

                @Override
                public void visitInsn(final int op) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitInsn(op);
                }

                @Override
                public void visitIntInsn(final int op, final int operand) {
                    afterBoolNewArray = !skippingIncidence && op == NEWARRAY && operand == T_BOOLEAN;
                    if (skippingIncidence) return;
                    super.visitIntInsn(op, operand);
                }

                @Override
                public void visitVarInsn(final int op, final int varIndex) {
                    if (skippingIncidence) {
                        afterBoolNewArray = false;
                        return;
                    }
                    super.visitVarInsn(op, varIndex);
                    if (afterBoolNewArray && op == ASTORE && varIndex == VISITED_ARRAY_SLOT) {
                        afterBoolNewArray = false;
                        super.visitVarInsn(ALOAD, POINTS_LIST_SLOT);
                        super.visitVarInsn(ALOAD, BOUNDARY_LIST_SLOT);
                        super.visitMethodInsn(INVOKESTATIC, FAST_OPS, BUILD_INCIDENCE, BUILD_INCIDENCE_DESC, false);
                        super.visitVarInsn(ASTORE, INCIDENT_ARRAY_SLOT);
                        skippingIncidence = true;
                        return;
                    }
                    afterBoolNewArray = false;
                }

                @Override
                public void visitTypeInsn(final int op, final String type) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitTypeInsn(op, type);
                }

                @Override
                public void visitFieldInsn(final int op, final String owner, final String f, final String fd) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitFieldInsn(op, owner, f, fd);
                }

                @Override
                public void visitMethodInsn(
                        final int op, final String owner, final String m, final String d, final boolean itf) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) {
                        if (op == INVOKESTATIC && SYSTEM_INTERNAL.equals(owner) && ARRAYCOPY_METHOD.equals(m)) {
                            sawArraycopy = true;
                        }
                        return;
                    }
                    super.visitMethodInsn(op, owner, m, d, itf);
                }

                @Override
                public void visitInvokeDynamicInsn(
                        final String m, final String d, final org.objectweb.asm.Handle h, final Object... args) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitInvokeDynamicInsn(m, d, h, args);
                }

                @Override
                public void visitJumpInsn(final int op, final Label label) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitJumpInsn(op, label);
                }

                @Override
                public void visitLabel(final Label label) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitLabel(label);
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitLdcInsn(value);
                }

                @Override
                public void visitIincInsn(final int varIndex, final int increment) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitIincInsn(varIndex, increment);
                }

                @Override
                public void visitTableSwitchInsn(
                        final int min, final int max, final Label dflt, final Label... labels) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitTableSwitchInsn(min, max, dflt, labels);
                }

                @Override
                public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitLookupSwitchInsn(dflt, keys, labels);
                }

                @Override
                public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) return;
                    super.visitMultiANewArrayInsn(descriptor, numDimensions);
                }

                @Override
                public void visitFrame(
                        final int type,
                        final int numLocal,
                        final Object[] local,
                        final int numStack,
                        final Object[] stack) {
                    afterBoolNewArray = false;
                    if (skippingIncidence) {
                        if (sawArraycopy) {
                            skippingIncidence = false;
                            sawArraycopy = false;
                        }
                        return;
                    }
                    super.visitFrame(type, numLocal, local, numStack, stack);
                }
            };
        }
    }
}
