package dev.turboism.validation.meshhash;

import java.util.ArrayList;
import java.util.List;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.FieldVisitor;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/**
 * Generates the fix for a constant-hash triangle-corner class.
 *
 * <p>The replacement {@code hashCode()} sums one permutation-invariant term per corner point,
 * derived from that point's <b>x/y only</b>: the host's corner {@code equals} compares x/y and
 * accepts all six permutations, while its own point {@code hashCode} mixes in a field that equals
 * ignores. Delegating to it would therefore reintroduce an equals/hashCode inconsistency, so the
 * generated code calls {@code getX()}/{@code getY()}/{@code Float.hashCode} directly.</p>
 *
 * <p>Fail-closed and idempotent by construction: only a class whose {@code hashCode()} body is
 * exactly {@code iconst_0; ireturn} with three same-typed corner fields {@code a}/{@code b}/{@code c}
 * is accepted. A different body, missing or mistyped fields, a foreign class name, or an
 * already-patched class throws {@link Rejected} and the caller keeps the original bytes.</p>
 */
public final class CornerTripleHashPatcher {
    private static final String[] CORNER_FIELDS = {"a", "b", "c"};

    private final String expectedInternalName;
    private final String expectedFieldDescriptor;

    public CornerTripleHashPatcher(final String expectedInternalName,
                                   final String expectedFieldDescriptor) {
        this.expectedInternalName = expectedInternalName;
        this.expectedFieldDescriptor = expectedFieldDescriptor;
    }

    /** Thrown when the input is not exactly the reviewed shape; the caller keeps the original. */
    public static final class Rejected extends RuntimeException {
        private static final long serialVersionUID = 1L;

        Rejected(final String message) {
            super(message);
        }
    }

    /** Returns patched class bytes, or throws {@link Rejected} when the shape is not recognised. */
    public byte[] patch(final byte[] original) {
        final String pointOwner;
        try {
            pointOwner = inspect(original);
        } catch (Rejected expected) {
            throw expected;
        } catch (RuntimeException malformed) {
            // Unparseable or truncated bytes are a rejection, never an escape hatch.
            throw new Rejected("class bytes are not parseable: " + malformed.getClass().getSimpleName());
        }
        final ClassWriter writer = new ClassWriter(new ClassReader(original),
            ClassWriter.COMPUTE_MAXS);
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                final MethodVisitor delegate =
                    super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!"hashCode".equals(name) || !"()I".equals(descriptor)) return delegate;
                return new ReplacementHash(delegate, expectedInternalName, pointOwner,
                    expectedFieldDescriptor);
            }
        }, 0);
        return writer.toByteArray();
    }

    /**
     * Replaces one method body with {@code h(a) + h(b) + h(c)}, where
     * {@code h(p) = Float.hashCode(p.getX()) * 31 + Float.hashCode(p.getY())}. Straight-line code
     * only, so no stack map frames are required and {@code COMPUTE_MAXS} suffices.
     */
    private static final class ReplacementHash extends MethodVisitor {
        private final String classOwner;
        private final String pointOwner;
        private final String pointDescriptor;

        ReplacementHash(final MethodVisitor delegate, final String classOwner,
                        final String pointOwner, final String pointDescriptor) {
            super(Opcodes.ASM9, delegate);
            this.classOwner = classOwner;
            this.pointOwner = pointOwner;
            this.pointDescriptor = pointDescriptor;
        }

        @Override
        public void visitCode() {
            super.visitCode();
            for (int index = 0; index < CORNER_FIELDS.length; index++) {
                emitPointHash(index);
                if (index > 0) mv.visitInsn(Opcodes.IADD);
            }
            mv.visitInsn(Opcodes.IRETURN);
        }

        private void emitPointHash(final int index) {
            for (final String accessor : new String[] {"getX", "getY"}) {
                mv.visitVarInsn(Opcodes.ALOAD, 0);
                mv.visitFieldInsn(Opcodes.GETFIELD, classOwner, CORNER_FIELDS[index],
                    pointDescriptor);
                mv.visitMethodInsn(Opcodes.INVOKEVIRTUAL, pointOwner, accessor, "()F", false);
                mv.visitMethodInsn(Opcodes.INVOKESTATIC, "java/lang/Float", "hashCode", "(F)I",
                    false);
                if ("getX".equals(accessor)) {
                    mv.visitIntInsn(Opcodes.BIPUSH, 31);
                    mv.visitInsn(Opcodes.IMUL);
                } else {
                    mv.visitInsn(Opcodes.IADD);
                }
            }
        }

        // Drop every original instruction of the constant-return body.
        @Override
        public void visitInsn(final int opcode) {
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
        }

        @Override
        public void visitVarInsn(final int opcode, final int index) {
        }

        @Override
        public void visitFieldInsn(final int opcode, final String owner, final String fieldName,
                                   final String fieldDescriptor) {
        }

        @Override
        public void visitMethodInsn(final int opcode, final String owner, final String methodName,
                                    final String methodDescriptor, final boolean isInterface) {
        }

        @Override
        public void visitLdcInsn(final Object value) {
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
        }

        @Override
        public void visitLabel(final Label label) {
        }
    }

    /** Validates the class shape and returns the internal name of the corner point type. */
    private String inspect(final byte[] original) {
        final String[] owner = new String[1];
        new ClassReader(original).accept(new ClassVisitor(Opcodes.ASM9) {
            private String internalName;
            private final List<String> fieldNames = new ArrayList<>();
            private final List<String> fieldTypes = new ArrayList<>();
            private boolean sawConstantHash;
            private int hashMethodCount;

            @Override
            public void visit(final int version, final int access, final String name,
                              final String signature, final String superName,
                              final String[] interfaces) {
                internalName = name;
            }

            @Override
            public FieldVisitor visitField(final int access, final String name,
                                           final String descriptor, final String signature,
                                           final Object value) {
                for (final String corner : CORNER_FIELDS) {
                    if (corner.equals(name)) {
                        fieldNames.add(name);
                        fieldTypes.add(descriptor);
                    }
                }
                return null;
            }

            @Override
            public MethodVisitor visitMethod(final int access, final String name,
                                             final String descriptor, final String signature,
                                             final String[] exceptions) {
                if (!"hashCode".equals(name) || !"()I".equals(descriptor)) return null;
                hashMethodCount++;
                return new MethodVisitor(Opcodes.ASM9) {
                    private final List<Integer> opcodes = new ArrayList<>();

                    @Override
                    public void visitInsn(final int opcode) {
                        opcodes.add(opcode);
                    }

                    @Override
                    public void visitEnd() {
                        sawConstantHash = opcodes.size() == 2
                            && opcodes.get(0) == Opcodes.ICONST_0
                            && opcodes.get(1) == Opcodes.IRETURN;
                    }
                };
            }

            @Override
            public void visitEnd() {
                if (!expectedInternalName.equals(internalName)) {
                    throw new Rejected("unexpected class: " + internalName);
                }
                if (hashMethodCount != 1 || !sawConstantHash) {
                    throw new Rejected("hashCode() body is not the reviewed constant return");
                }
                if (fieldNames.size() != CORNER_FIELDS.length) {
                    throw new Rejected("corner fields are missing");
                }
                String pointType = null;
                for (int index = 0; index < CORNER_FIELDS.length; index++) {
                    if (!CORNER_FIELDS[index].equals(fieldNames.get(index))) {
                        throw new Rejected("unexpected corner field: " + fieldNames.get(index));
                    }
                    if (!expectedFieldDescriptor.equals(fieldTypes.get(index))) {
                        throw new Rejected("unexpected corner field type: " + fieldTypes.get(index));
                    }
                    final String candidate = Type.getType(fieldTypes.get(index)).getInternalName();
                    if (pointType == null) {
                        pointType = candidate;
                    } else if (!pointType.equals(candidate)) {
                        throw new Rejected("corner fields do not share one point type");
                    }
                }
                owner[0] = pointType;
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        if (owner[0] == null) throw new Rejected("class was not inspected");
        return owner[0];
    }
}
