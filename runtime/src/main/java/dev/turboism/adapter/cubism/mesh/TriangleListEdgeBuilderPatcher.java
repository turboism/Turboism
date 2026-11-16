package dev.turboism.adapter.cubism.mesh;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Exact native local-builder weave; its original membership/append branches remain intact. */
final class TriangleListEdgeBuilderPatcher implements Opcodes {
    private static final String OWNER = TriangulationEdgeIndexPatcher.OWNER;
    private static final String EDGE = TriangulationEdgeIndexPatcher.EDGE;
    private static final String POINT = TriangulationEdgeIndexPatcher.POINT;
    private static final String COLLECTION = "com/live2d/graphics3d/editableMesh/triangulation/k";
    private static final String DESC = "()L" + COLLECTION + ";";
    private static final String QUERY = "(L" + EDGE + ";Z)Z";
    private static final String HELPER = "dev/turboism/adapter/cubism/mesh/TriangulationBuilderEdges";
    private static final String LEASE = "dev/turboism/adapter/cubism/mesh/LazyTriangulationEdgeBridge";
    /** The reviewed builder body queries the fresh local collection once per triangle edge. */
    private static final int BUILDER_QUERY_SITES = 3;
    /** Tokens the weave reads back from the builder body; each names one reviewed instruction. */
    private static final String QUERY_CALL = COLLECTION + ".a" + QUERY;

    private static final String APPEND_CALL = COLLECTION + ".a(L" + EDGE + ";)Z";
    private static final String NEW_COLLECTION = "type " + NEW + ' ' + COLLECTION;
    private static final String FALSE_CONSTANT = "insn " + ICONST_0;
    private static final String LOAD_PREFIX = "var " + ALOAD + ' ', STORE_PREFIX = "var " + ASTORE + ' ';

    private TriangleListEdgeBuilderPatcher() {}

    /**
     * Verifies the builder body the weave depends on.
     *
     * <p>Every membership query must read the fresh method-local collection with the reviewed
     * constant argument, so the woven shortcut and its native fallback stay equivalent. Any
     * other receiver, argument or site count is outside the reviewed contract.</p>
     */
    private static void requireReviewedBuilderShape(final byte[] bytes) {
        final List<String> body = builderBody(bytes);
        final String collection = LOAD_PREFIX + freshCollectionLocal(body);
        int sites = 0;
        for (int index = 3; index < body.size(); index++) {
            if (!body.get(index).equals(QUERY_CALL)) continue;
            sites++;
            if (!body.get(index - 1).equals(FALSE_CONSTANT)
                    || !body.get(index - 2).startsWith(LOAD_PREFIX)
                    || !body.get(index - 3).equals(collection))
                throw new IllegalArgumentException("local builder query site outside the reviewed shape");
        }
        if (sites != BUILDER_QUERY_SITES) throw new IllegalArgumentException("local builder query sites=" + sites);
        if (!body.contains(APPEND_CALL)) throw new IllegalArgumentException("local builder append site missing");
    }

    /** Token stream of the builder method; a missing or duplicated method is rejected here. */
    private static List<String> builderBody(final byte[] bytes) {
        final List<List<String>> bodies = new ArrayList<>();
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(ASM9) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                if (!name.equals("b") || !descriptor.equals(DESC)) return null;
                                final List<String> tokens = new ArrayList<>();
                                bodies.add(tokens);
                                return new MethodVisitor(ASM9) {
                                    @Override
                                    public void visitInsn(final int opcode) {
                                        tokens.add("insn " + opcode);
                                    }

                                    @Override
                                    public void visitVarInsn(final int opcode, final int local) {
                                        tokens.add("var " + opcode + ' ' + local);
                                    }

                                    @Override
                                    public void visitTypeInsn(final int opcode, final String type) {
                                        tokens.add("type " + opcode + ' ' + type);
                                    }

                                    @Override
                                    public void visitMethodInsn(
                                            final int opcode,
                                            final String owner,
                                            final String method,
                                            final String descriptor,
                                            final boolean itf) {
                                        tokens.add(owner + '.' + method + descriptor);
                                    }
                                };
                            }
                        },
                        ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (bodies.size() != 1) throw new IllegalArgumentException("local builder method missing or duplicated");
        return bodies.get(0);
    }

    /** Local slot that holds the collection allocated at builder entry. */
    private static int freshCollectionLocal(final List<String> body) {
        for (int index = 0; index < body.size(); index++) {
            if (!body.get(index).equals(NEW_COLLECTION)) continue;
            for (int next = index + 1; next < body.size(); next++) {
                if (body.get(next).startsWith(STORE_PREFIX))
                    return Integer.parseInt(body.get(next).substring(STORE_PREFIX.length()));
            }
        }
        throw new IllegalArgumentException("local builder collection allocation missing");
    }

    static byte[] patch(final byte[] bytes) {
        if (!new ClassReader(bytes).getClassName().equals(OWNER)) {
            throw new IllegalArgumentException("unreviewed local builder owner");
        }
        requireReviewedBuilderShape(bytes);
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String a, final String b) {
                return "java/lang/Object";
            }
        };
        new ClassReader(bytes)
                .accept(
                        new ClassVisitor(ASM9, writer) {
                            @Override
                            public MethodVisitor visitMethod(
                                    final int access,
                                    final String name,
                                    final String descriptor,
                                    final String signature,
                                    final String[] exceptions) {
                                final MethodVisitor method =
                                        super.visitMethod(access, name, descriptor, signature, exceptions);
                                if (!name.equals("b") || !descriptor.equals(DESC)) return method;
                                return new Builder(method);
                            }
                        },
                        ClassReader.EXPAND_FRAMES);
        return writer.toByteArray();
    }

    private static final class Builder extends MethodVisitor {
        private static final int LEASE_LOCAL = 7,
                STATE_LOCAL = 8,
                EDGE_LOCAL = 9,
                COLLECTION_LOCAL = 10,
                RETURN_LOCAL = 11,
                FAILURE_LOCAL = 12,
                SEEN_LOCAL = 13;
        private final Label start = new Label(), end = new Label(), success = new Label(), failure = new Label();

        Builder(final MethodVisitor method) {
            super(ASM9, method);
        }

        @Override
        public void visitCode() {
            super.visitCode();
            super.visitLdcInsn(Type.getObjectType(OWNER));
            super.visitMethodInsn(
                    INVOKESTATIC, LEASE, "enterBuilder", "(Ljava/lang/Class;)Ljava/lang/AutoCloseable;", false);
            super.visitVarInsn(ASTORE, LEASE_LOCAL);
            super.visitTryCatchBlock(start, end, failure, "java/lang/Throwable");
            super.visitLabel(start);
            final Label inactive = new Label(), ready = new Label();
            super.visitVarInsn(ALOAD, LEASE_LOCAL);
            super.visitJumpInsn(IFNULL, inactive);
            super.visitMethodInsn(INVOKESTATIC, HELPER, "create", "()L" + HELPER + ";", false);
            super.visitJumpInsn(GOTO, ready);
            super.visitLabel(inactive);
            super.visitInsn(ACONST_NULL);
            super.visitLabel(ready);
            super.visitVarInsn(ASTORE, STATE_LOCAL);
        }

        @Override
        public void visitMethodInsn(
                final int opcode, final String owner, final String name, final String descriptor, final boolean itf) {
            if (opcode != INVOKEVIRTUAL
                    || !owner.equals(COLLECTION)
                    || !name.equals("a")
                    || !descriptor.equals(QUERY)) {
                super.visitMethodInsn(opcode, owner, name, descriptor, itf);
                return;
            }
            // The reviewed builder shape proves the boolean is false and this
            // receiver is the fresh method-local collection, never an escaped list.
            super.visitInsn(POP);
            super.visitVarInsn(ASTORE, EDGE_LOCAL);
            super.visitVarInsn(ASTORE, COLLECTION_LOCAL);
            final Label nativeQuery = new Label(), done = new Label();
            super.visitVarInsn(ALOAD, STATE_LOCAL);
            super.visitJumpInsn(IFNULL, nativeQuery);
            super.visitVarInsn(ALOAD, STATE_LOCAL);
            endpoint("a");
            endpoint("b");
            super.visitMethodInsn(INVOKESTATIC, HELPER, "seen", "(L" + HELPER + ";II)I", false);
            super.visitVarInsn(ISTORE, SEEN_LOCAL);
            super.visitVarInsn(ILOAD, SEEN_LOCAL);
            super.visitJumpInsn(IFLT, nativeQuery);
            super.visitVarInsn(ILOAD, SEEN_LOCAL);
            super.visitJumpInsn(GOTO, done);
            super.visitLabel(nativeQuery);
            super.visitVarInsn(ALOAD, COLLECTION_LOCAL);
            super.visitVarInsn(ALOAD, EDGE_LOCAL);
            super.visitInsn(ICONST_0);
            super.visitMethodInsn(opcode, owner, name, descriptor, itf);
            super.visitLabel(done);
        }

        private void endpoint(final String name) {
            super.visitVarInsn(ALOAD, EDGE_LOCAL);
            super.visitMethodInsn(INVOKEVIRTUAL, EDGE, name, "()L" + POINT + ";", false);
            super.visitMethodInsn(INVOKEVIRTUAL, POINT, "getIndex", "()I", false);
        }

        @Override
        public void visitInsn(final int opcode) {
            if (opcode == ARETURN) {
                super.visitVarInsn(ASTORE, RETURN_LOCAL);
                super.visitJumpInsn(GOTO, success);
            } else super.visitInsn(opcode);
        }

        @Override
        public void visitMaxs(final int stack, final int locals) {
            super.visitLabel(end);
            super.visitLabel(success);
            leave();
            super.visitVarInsn(ALOAD, RETURN_LOCAL);
            super.visitInsn(ARETURN);
            super.visitLabel(failure);
            super.visitVarInsn(ASTORE, FAILURE_LOCAL);
            leave();
            super.visitVarInsn(ALOAD, FAILURE_LOCAL);
            super.visitInsn(ATHROW);
            super.visitMaxs(0, 0);
        }

        private void leave() {
            super.visitVarInsn(ALOAD, LEASE_LOCAL);
            super.visitMethodInsn(INVOKESTATIC, LEASE, "leave", "(Ljava/lang/AutoCloseable;)V", false);
        }
    }
}
