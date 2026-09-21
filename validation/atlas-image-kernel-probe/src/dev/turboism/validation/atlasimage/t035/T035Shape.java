package dev.turboism.validation.atlasimage.t035;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Label;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** Finite bytecode shape reader used for data-only gates and owned fixtures. */
final class T035Shape implements Opcodes {
    static final String RENDER_DESCRIPTOR = "(II[III[IIIII)V";
    static final String OFFICIAL_METHOD = "a";

    private T035Shape() {
    }

    static Shape inspect(final byte[] classBytes) {
        final OffsetReader reader = new OffsetReader(classBytes);
        final Collector collector = new Collector(reader);
        reader.accept(collector, ClassReader.EXPAND_FRAMES);
        if (collector.target == null) {
            throw new IllegalArgumentException("target method is missing");
        }
        final List<String> lines = collector.lines;
        final String canonical = String.join("\n", lines) + "\n";
        return new Shape(
            collector.version,
            collector.classAccess,
            collector.className,
            collector.methodCount,
            collector.target.access,
            collector.target.name,
            collector.target.descriptor,
            collector.target.maxStack,
            collector.target.maxLocals,
            collector.target.instructionCount,
            collector.target.handlerCount,
            lines,
            sha256(canonical)
        );
    }

    static String sha256(final byte[] value) {
        try {
            return hex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (final NoSuchAlgorithmException exception) {
            throw new AssertionError("SHA-256 is unavailable", exception);
        }
    }

    static String sha256(final String value) {
        return sha256(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String hex(final byte[] value) {
        final StringBuilder result = new StringBuilder(value.length * 2);
        for (final byte next : value) {
            result.append(String.format("%02x", next & 0xff));
        }
        return result.toString();
    }

    record Shape(
        int version,
        int classAccess,
        String className,
        int methodCount,
        int methodAccess,
        String methodName,
        String descriptor,
        int maxStack,
        int maxLocals,
        int instructionCount,
        int handlerCount,
        List<String> lines,
        String sha256
    ) {
        Shape {
            lines = List.copyOf(lines);
        }

        boolean identity(final String expectedName, final String expectedDescriptor) {
            return methodName.equals(expectedName) && descriptor.equals(expectedDescriptor);
        }

        boolean hasLine(final String expected) {
            return lines.contains(expected);
        }

        int countPrefix(final String prefix) {
            int count = 0;
            for (final String line : lines) {
                if (line.startsWith(prefix)) {
                    count++;
                }
            }
            return count;
        }

        int countContains(final String fragment) {
            int count = 0;
            for (final String line : lines) {
                if (line.contains(fragment)) {
                    count++;
                }
            }
            return count;
        }
    }

    /** ClassReader exposing the original instruction offset, without loading a class. */
    static final class OffsetReader extends ClassReader {
        private int offset;
        private final Map<Label, Integer> labelOffsets = new IdentityHashMap<>();

        OffsetReader(final byte[] classBytes) {
            super(classBytes);
        }

        int offset() {
            return offset;
        }

        Integer labelOffset(final Label label) {
            return labelOffsets.get(label);
        }

        @Override
        protected void readBytecodeInstructionOffset(final int value) {
            offset = value;
        }

        @Override
        protected Label readLabel(final int value, final Label[] labels) {
            final Label label = super.readLabel(value, labels);
            labelOffsets.put(label, value);
            return label;
        }
    }

    private static final class Collector extends ClassVisitor {
        private final OffsetReader reader;
        private final List<String> lines = new ArrayList<>();
        private String className;
        private int classAccess;
        private int version;
        private int methodCount;
        private Target target;

        private Collector(final OffsetReader reader) {
            super(ASM9);
            this.reader = reader;
        }

        @Override
        public void visit(
            final int version,
            final int access,
            final String name,
            final String signature,
            final String superName,
            final String[] interfaces
        ) {
            this.version = version;
            classAccess = access;
            className = name;
            lines.add("CLASS " + name + " version=" + version + " access=" + access);
        }

        @Override
        public MethodVisitor visitMethod(
            final int access,
            final String name,
            final String descriptor,
            final String signature,
            final String[] exceptions
        ) {
            methodCount++;
            final boolean officialTarget = name.equals(OFFICIAL_METHOD)
                && descriptor.equals(T035OfficialProfile.DESCRIPTOR);
            final boolean ownedTarget = name.equals("render")
                && descriptor.equals(RENDER_DESCRIPTOR);
            if (!officialTarget && !ownedTarget) {
                return null;
            }
            if (target != null) {
                return null;
            }
            lines.add("METHOD " + name + descriptor + " access=" + access);
            target = new Target(access, name, descriptor);
            return new MethodVisitor(ASM9) {
                private void line(final String value) {
                    lines.add(reader.offset() + ": " + value);
                }

                @Override
                public void visitFrame(
                    final int type,
                    final int numberOfLocals,
                    final Object[] locals,
                    final int numberOfStack,
                    final Object[] stack
                ) {
                    line("FRAME " + Arrays.toString(Arrays.copyOf(locals, numberOfLocals))
                        + " STACK " + Arrays.toString(Arrays.copyOf(stack, numberOfStack)));
                }

                @Override
                public void visitInsn(final int opcode) {
                    target.instructionCount++;
                    line("I " + opcode);
                }

                @Override
                public void visitIntInsn(final int opcode, final int operand) {
                    target.instructionCount++;
                    line("N " + opcode + " " + operand);
                }

                @Override
                public void visitVarInsn(final int opcode, final int variable) {
                    target.instructionCount++;
                    line("V " + opcode + " " + variable);
                }

                @Override
                public void visitTypeInsn(final int opcode, final String type) {
                    target.instructionCount++;
                    line("T " + opcode + " " + type);
                }

                @Override
                public void visitFieldInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor
                ) {
                    target.instructionCount++;
                    line("F " + opcode + " " + owner + "." + name + descriptor);
                }

                @Override
                public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface
                ) {
                    target.instructionCount++;
                    line("M " + opcode + " " + owner + "." + name + descriptor + " " + isInterface);
                }

                @Override
                public void visitJumpInsn(final int opcode, final Label label) {
                    target.instructionCount++;
                    line("J " + opcode + " " + reader.labelOffset(label));
                }

                @Override
                public void visitLdcInsn(final Object value) {
                    target.instructionCount++;
                    line("LDC " + value);
                }

                @Override
                public void visitIincInsn(final int variable, final int increment) {
                    target.instructionCount++;
                    line("INC " + variable + " " + increment);
                }

                @Override
                public void visitTableSwitchInsn(
                    final int min,
                    final int max,
                    final Label defaultLabel,
                    final Label... labels
                ) {
                    target.instructionCount++;
                    final StringBuilder value = new StringBuilder("TABLE ")
                        .append(min).append(' ').append(max).append(' ')
                        .append(reader.labelOffset(defaultLabel));
                    for (final Label label : labels) {
                        value.append(' ').append(reader.labelOffset(label));
                    }
                    line(value.toString());
                }

                @Override
                public void visitLookupSwitchInsn(
                    final Label defaultLabel,
                    final int[] keys,
                    final Label[] labels
                ) {
                    target.instructionCount++;
                    final StringBuilder value = new StringBuilder("LOOKUP ")
                        .append(reader.labelOffset(defaultLabel));
                    for (int index = 0; index < keys.length; index++) {
                        value.append(' ').append(keys[index]).append(' ')
                            .append(reader.labelOffset(labels[index]));
                    }
                    line(value.toString());
                }

                @Override
                public void visitMultiANewArrayInsn(final String descriptor, final int dimensions) {
                    target.instructionCount++;
                    line("MULTI " + descriptor + " " + dimensions);
                }

                @Override
                public void visitTryCatchBlock(
                    final Label start,
                    final Label end,
                    final Label handler,
                    final String type
                ) {
                    target.handlerCount++;
                    lines.add("HANDLER " + reader.labelOffset(start) + " "
                        + reader.labelOffset(end) + " " + reader.labelOffset(handler) + " " + type);
                }

                @Override
                public void visitMaxs(final int maxStack, final int maxLocals) {
                    target.maxStack = maxStack;
                    target.maxLocals = maxLocals;
                    lines.add("MAX " + maxStack + " " + maxLocals);
                }
            };
        }
    }

    private static final class Target {
        private final int access;
        private final String name;
        private final String descriptor;
        private int maxStack = -1;
        private int maxLocals = -1;
        private int instructionCount;
        private int handlerCount;

        private Target(final int access, final String name, final String descriptor) {
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }
    }
}
