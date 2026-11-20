package dev.turboism.adapter.cubism.textureatlas;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Keeps native mesh writes on the caller thread when it uses a prepared solver. */
final class TextureAtlasMeshSolverTransformer implements Opcodes {
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
    private static final String SOLVER = TRIANGULATION + "a";
    private static final String NATIVE_METHOD = "a";
    private static final String BRIDGE = Type.getInternalName(TextureAtlasParallelMeshes.class);
    private static final String CAPTURE_METHOD = "capture";
    private static final String CAPTURE_DESC = "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)Z";
    private static final String AWAIT_METHOD = "solve";
    private static final String AWAIT_DESC =
            "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/invoke/MethodHandle;)Ljava/lang/Object;";
    private static final String MARKER_DESC = "()V";
    private static final int MESH_SLOT = 1;
    private static final int SOLVER_SLOT = 5;
    private static final int EXPECTED_SOLVE_SITES = 1;
    private static final int APPLY_LOCALS = 6;
    private static final int SOLVE_OPERANDS = 2;

    private TextureAtlasMeshSolverTransformer() {}

    static byte[] transform(final byte[] bytes) {
        if (bytes == null) return null;
        final String progress = progressType(bytes);
        if (progress == null) return null;
        final var reader = new ClassReader(bytes);
        final var writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
        final var visitor = new MeshOpsVisitor(writer, progress);
        reader.accept(visitor, ClassReader.EXPAND_FRAMES);
        return visitor.solveSites == EXPECTED_SOLVE_SITES ? writer.toByteArray() : null;
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
                                    || !NATIVE_METHOD.equals(name)) {
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

    private static final class MeshOpsVisitor extends ClassVisitor {
        private final String progress;
        private final String applyDescriptor;
        private final String solveDescriptor;
        private final Handle solve;
        private int solveSites;

        MeshOpsVisitor(final ClassWriter writer, final String progress) {
            super(ASM9, writer);
            this.progress = progress;
            final String progressType = Type.getObjectType(progress).getDescriptor();
            applyDescriptor = "(L" + MESH + ";Ljava/util/List;Z" + progressType + ")V";
            solveDescriptor = "(" + progressType + ")V";
            solve = new Handle(H_INVOKEVIRTUAL, SOLVER, NATIVE_METHOD, solveDescriptor, false);
        }

        @Override
        public MethodVisitor visitMethod(
                final int access, final String name, final String desc, final String signature, final String[] errors) {
            final var output = super.visitMethod(access, name, desc, signature, errors);
            if (!NATIVE_METHOD.equals(name) || !applyDescriptor.equals(desc)) return output;
            return new MethodVisitor(ASM9, output) {
                @Override
                public void visitMethodInsn(
                        final int opcode,
                        final String owner,
                        final String member,
                        final String type,
                        final boolean itf) {
                    if (opcode != INVOKEVIRTUAL
                            || !SOLVER.equals(owner)
                            || !NATIVE_METHOD.equals(member)
                            || !solveDescriptor.equals(type)) {
                        super.visitMethodInsn(opcode, owner, member, type, itf);
                        return;
                    }
                    solveSites++;
                    final var apply = new Label();
                    super.visitInsn(DUP2);
                    super.visitLdcInsn(solve);
                    super.visitMethodInsn(INVOKESTATIC, BRIDGE, CAPTURE_METHOD, CAPTURE_DESC, false);
                    super.visitJumpInsn(IFEQ, apply);
                    super.visitInsn(POP2);
                    super.visitInsn(RETURN);
                    super.visitLabel(apply);
                    super.visitFrame(
                            F_NEW,
                            APPLY_LOCALS,
                            new Object[] {TOP, MESH, TOP, TOP, progress, SOLVER},
                            SOLVE_OPERANDS,
                            new Object[] {SOLVER, progress});
                    super.visitVarInsn(ALOAD, MESH_SLOT);
                    super.visitLdcInsn(solve);
                    super.visitMethodInsn(INVOKESTATIC, BRIDGE, AWAIT_METHOD, AWAIT_DESC, false);
                    super.visitTypeInsn(CHECKCAST, SOLVER);
                    super.visitVarInsn(ASTORE, SOLVER_SLOT);
                }
            };
        }

        @Override
        public void visitEnd() {
            if (solveSites == EXPECTED_SOLVE_SITES) {
                final var marker = super.visitMethod(
                        ACC_PUBLIC | ACC_STATIC | ACC_SYNTHETIC, CAPTURE_MARKER, MARKER_DESC, null, null);
                marker.visitCode();
                marker.visitInsn(RETURN);
                marker.visitMaxs(0, 0);
                marker.visitEnd();
            }
            super.visitEnd();
        }
    }
}
