package dev.turboism.adapter.cubism.textureatlas;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Rewrites {@code editableMesh.b} to extract contours in {@code O(V + E)} and merge post-solve
 * triangle edges in {@code O(E + T)}.
 */
public final class TextureAtlasMeshSolverTransformer implements ClassFileTransformer, Opcodes {

    static final String MESH_PACKAGE = "com/live2d/graphics3d/editableMesh/";
    static final String MESH_OPS = MESH_PACKAGE + "b";
    static final String MESH = MESH_PACKAGE + "GEditableMesh2";
    static final String TRIANGULATION = MESH_PACKAGE + "triangulation/";
    static final String CAPTURE_MARKER = "turboismAtlasCaptureAvailable";
    static final String PROGRESS_PACKAGE_PREFIX = "com/live2d/util/";
    private static final Type MESH_TYPE = Type.getObjectType(MESH);
    private static final Type LIST_TYPE = Type.getObjectType("java/util/List");
    private static final int APPLY_PARAMETER_COUNT = 4;
    private static final int PROGRESS_PARAMETER_INDEX = 3;

    private static final String APPLY_METHOD = "a";
    private static final String OUTLINES_METHOD = "b";
    private static final String OUTLINES_DESC = "(L" + MESH + ";)Ljava/util/List;";
    private static final String CLEAR_AUTO_METHOD = "clearAutoTriangulation";
    private static final String ADD_EDGE_METHOD = "addEdgeIfNotExists$default";
    private static final String PROGRESS_DONE_METHOD = "d";
    private static final String FAST_OPS = Type.getInternalName(TextureAtlasMeshFastOps.class);
    private static final String EXTRACT_OUTLINES = "extractOutlines";
    private static final String EXTRACT_OUTLINES_DESC = "(Ljava/lang/Object;)Ljava/util/List;";
    private static final String MERGE_TRIS = "mergeTrianglesAfterClear";
    private static final String MERGE_TRIS_DESC = "(Ljava/lang/Object;[[I)Z";
    private static final String TRIS_INTERNAL_ARRAY = "[[I";
    private static final String LIST_INTERNAL = "java/util/List";
    private static final String VOID_DESC = "()V";
    private static final int EXPECTED_CLEAR_SITES = 1;
    private static final int EXPECTED_ADD_EDGE_SITES = 3;
    private static final int TRIS_SLOT = 6;
    private static final int PROGRESS_SLOT = 4;

    @Override
    public byte[] transform(
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        return transform(null, loader, className, classBeingRedefined, protectionDomain, bytes);
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] bytes) {
        if (bytes == null || classBeingRedefined != null || !MESH_OPS.equals(className)) {
            return null;
        }
        return transform(bytes);
    }

    /** Rewrites {@code editableMesh.b} when it matches the reviewed structure. */
    public static byte[] transform(final byte[] bytes) {
        if (bytes == null) return null;
        final String progress = progressType(bytes);
        if (progress == null) return null;
        final ClassReader reader = new ClassReader(bytes);
        if (!MESH_OPS.equals(reader.getClassName())) return null;
        final ShapeScan scan = new ShapeScan(progress);
        reader.accept(scan, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        if (scan.alreadyPatched || !scan.matches()) {
            return null;
        }
        final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final MeshOpsVisitor visitor = new MeshOpsVisitor(writer, progress);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return visitor.patched() ? writer.toByteArray() : null;
    }

    static String progressType(final byte[] bytes) {
        if (bytes == null) return null;
        final String[] detected = new String[1];
        final int[] matches = new int[1];
        try {
            final var reader = new ClassReader(bytes);
            if (!MESH_OPS.equals(reader.getClassName())) return null;
            reader.accept(
                    new ClassVisitor(ASM9) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String name,
                                final String desc,
                                final String signature,
                                final String[] errors) {
                            if ((access & (ACC_PUBLIC | ACC_ABSTRACT | ACC_NATIVE | ACC_STATIC)) != ACC_PUBLIC
                                    || !APPLY_METHOD.equals(name)) {
                                return null;
                            }
                            if (!Type.VOID_TYPE.equals(Type.getReturnType(desc))) return null;
                            final Type[] args = Type.getArgumentTypes(desc);
                            if (args.length != APPLY_PARAMETER_COUNT
                                    || !MESH_TYPE.equals(args[0])
                                    || !LIST_TYPE.equals(args[1])
                                    || !Type.BOOLEAN_TYPE.equals(args[2])
                                    || args[PROGRESS_PARAMETER_INDEX].getSort() != Type.OBJECT) {
                                return null;
                            }
                            final String progress = args[PROGRESS_PARAMETER_INDEX].getInternalName();
                            if (!progress.startsWith(PROGRESS_PACKAGE_PREFIX)) return null;
                            detected[0] = progress;
                            matches[0]++;
                            return null;
                        }
                    },
                    ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        } catch (RuntimeException malformed) {
            return null;
        }
        return matches[0] == 1 ? detected[0] : null;
    }

    private static final class ShapeScan extends ClassVisitor {
        private final String applyDesc;
        private boolean alreadyPatched;
        private int outlineMethods;
        private int applyMethods;
        private int clearSites;
        private int addEdgeSites;

        ShapeScan(final String progress) {
            super(ASM9);
            this.applyDesc = applyDescriptor(progress);
        }

        boolean matches() {
            return outlineMethods == 1
                    && applyMethods == 1
                    && clearSites == EXPECTED_CLEAR_SITES
                    && addEdgeSites == EXPECTED_ADD_EDGE_SITES;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] ex) {
            if (CAPTURE_MARKER.equals(name) && VOID_DESC.equals(desc)) {
                alreadyPatched = true;
            }
            if (OUTLINES_METHOD.equals(name) && OUTLINES_DESC.equals(desc)) {
                outlineMethods++;
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int op, final String o, final String m, final String d, final boolean itf) {
                        if (op == INVOKESTATIC && FAST_OPS.equals(o) && EXTRACT_OUTLINES.equals(m)) {
                            alreadyPatched = true;
                        }
                    }
                };
            }
            if (APPLY_METHOD.equals(name) && applyDesc.equals(desc)) {
                applyMethods++;
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitMethodInsn(
                            final int op, final String o, final String m, final String d, final boolean itf) {
                        if (op == INVOKEVIRTUAL && MESH.equals(o) && CLEAR_AUTO_METHOD.equals(m)) {
                            clearSites++;
                        } else if (op == INVOKESTATIC && MESH.equals(o) && ADD_EDGE_METHOD.equals(m)) {
                            addEdgeSites++;
                        } else if (op == INVOKESTATIC && FAST_OPS.equals(o) && MERGE_TRIS.equals(m)) {
                            alreadyPatched = true;
                        }
                    }
                };
            }
            return null;
        }
    }

    private static final class MeshOpsVisitor extends ClassVisitor {
        private final String progress;
        private final String applyDesc;
        private int outlinePatches;
        private int mergePatches;

        MeshOpsVisitor(final ClassWriter writer, final String progress) {
            super(ASM9, writer);
            this.progress = progress;
            this.applyDesc = applyDescriptor(progress);
        }

        boolean patched() {
            return outlinePatches == 1 && mergePatches == 1;
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String sig, final String[] errors) {
            final MethodVisitor output = super.visitMethod(access, name, desc, sig, errors);
            if (OUTLINES_METHOD.equals(name) && OUTLINES_DESC.equals(desc)) {
                outlinePatches++;
                return new MethodVisitor(ASM9, output) {
                    @Override
                    public void visitCode() {
                        super.visitCode();
                        final Label fallback = new Label();
                        super.visitVarInsn(ALOAD, 1);
                        super.visitMethodInsn(INVOKESTATIC, FAST_OPS, EXTRACT_OUTLINES, EXTRACT_OUTLINES_DESC, false);
                        super.visitInsn(DUP);
                        super.visitJumpInsn(IFNULL, fallback);
                        super.visitInsn(ARETURN);
                        super.visitLabel(fallback);
                        super.visitFrame(F_NEW, 2, new Object[] {MESH_OPS, MESH}, 1, new Object[] {LIST_INTERNAL});
                        super.visitInsn(POP);
                    }
                };
            }
            if (APPLY_METHOD.equals(name) && applyDesc.equals(desc)) {
                return new ApplyEdgeMergeVisitor(output, progress, () -> mergePatches++);
            }
            return output;
        }

        @Override
        public void visitEnd() {
            if (patched()) {
                final MethodVisitor marker = super.visitMethod(
                        ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, CAPTURE_MARKER, VOID_DESC, null, null);
                marker.visitCode();
                marker.visitInsn(RETURN);
                marker.visitMaxs(0, 0);
                marker.visitEnd();
            }
            super.visitEnd();
        }
    }

    private static final class ApplyEdgeMergeVisitor extends MethodVisitor {
        private final String progress;
        private final Runnable onPatched;
        private final Label afterEdgeLoop = new Label();
        private boolean skippingToProgressDone;
        private boolean patched;

        ApplyEdgeMergeVisitor(final MethodVisitor output, final String progress, final Runnable onPatched) {
            super(ASM9, output);
            this.progress = progress;
            this.onPatched = onPatched;
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String member, final String desc, final boolean itf) {
            if (!patched && opcode == INVOKEVIRTUAL && MESH.equals(owner) && CLEAR_AUTO_METHOD.equals(member)) {
                final Label nativeClear = new Label();
                super.visitVarInsn(ALOAD, TRIS_SLOT);
                super.visitMethodInsn(INVOKESTATIC, FAST_OPS, MERGE_TRIS, MERGE_TRIS_DESC, false);
                super.visitJumpInsn(IFEQ, nativeClear);
                super.visitJumpInsn(GOTO, afterEdgeLoop);
                super.visitLabel(nativeClear);
                super.visitFrame(
                        F_NEW,
                        7,
                        new Object[] {TOP, MESH, TOP, TOP, progress, TOP, TRIS_INTERNAL_ARRAY},
                        0,
                        new Object[0]);
                super.visitVarInsn(ALOAD, 1);
                super.visitMethodInsn(opcode, owner, member, desc, itf);
                skippingToProgressDone = true;
                patched = true;
                onPatched.run();
                return;
            }
            if (skippingToProgressDone
                    && opcode == INVOKEINTERFACE
                    && progress.equals(owner)
                    && PROGRESS_DONE_METHOD.equals(member)
                    && VOID_DESC.equals(desc)) {
                skippingToProgressDone = false;
                // Rebind afterEdgeLoop strictly before `ALOAD 4; INVOKEINTERFACE progress.d()` -> wait:
                // ALOAD 4 was emitted just before this instruction, so we catch ALOAD 4 in visitVarInsn instead!
            }
            super.visitMethodInsn(opcode, owner, member, desc, itf);
        }

        @Override
        public void visitVarInsn(final int opcode, final int varIndex) {
            if (skippingToProgressDone && opcode == ALOAD && varIndex == PROGRESS_SLOT) {
                skippingToProgressDone = false;
                super.visitLabel(afterEdgeLoop);
                super.visitFrame(
                        F_NEW,
                        7,
                        new Object[] {TOP, MESH, TOP, TOP, progress, TOP, TRIS_INTERNAL_ARRAY},
                        0,
                        new Object[0]);
            }
            super.visitVarInsn(opcode, varIndex);
        }
    }

    private static String applyDescriptor(final String progress) {
        return "(L" + MESH + ";Ljava/util/List;Z" + Type.getObjectType(progress).getDescriptor() + ")V";
    }
}
