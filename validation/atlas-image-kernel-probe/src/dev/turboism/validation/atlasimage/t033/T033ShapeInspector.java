package dev.turboism.validation.atlasimage.t033;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Handle;
import dev.turboism.validation.atlasimage.shaded.asm97.Label;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

final class T033ShapeInspector implements Opcodes {
    private T033ShapeInspector() {
    }

    static Shape inspect(final byte[] classBytes) {
        final Collector collector = new Collector();
        new ClassReader(classBytes).accept(collector, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        final MethodShape method = collector.method;
        if (method == null) {
            throw new IllegalArgumentException("fixture render method is missing");
        }
        final String canonical = String.join("\n", method.events);
        return new Shape(
            collector.className,
            collector.classAccess,
            collector.methodCount,
            method.access,
            method.name,
            method.descriptor,
            method.maxLocals,
            method.events,
            canonical,
            sha256(canonical),
            adjacent(method.events, "V:" + ILOAD + ":9", "I:" + ICONST_1, "I:" + ISUB, "V:" + ISTORE + ":13"),
            adjacent(method.events, "V:" + ILOAD + ":8", "I:" + ICONST_1, "I:" + ISUB, "V:" + ISTORE + ":18"),
            countPrefix(method.events, "M:" + INVOKESTATIC + ":java/util/Arrays:fill:([IIII)V:"),
            count(method.events, "J:" + IF_ICMPGT + ":"),
            count(method.events, "J:" + IF_ICMPEQ + ":")
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

    private static int count(final List<String> events, final String prefix) {
        int result = 0;
        for (final String event : events) {
            if (event.startsWith(prefix)) {
                result++;
            }
        }
        return result;
    }

    private static int countPrefix(final List<String> events, final String prefix) {
        return count(events, prefix);
    }

    private static int adjacent(final List<String> events, final String... sequence) {
        int result = 0;
        for (int index = 0; index <= events.size() - sequence.length; index++) {
            boolean matches = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (!events.get(index + offset).equals(sequence[offset])) {
                    matches = false;
                    break;
                }
            }
            if (matches) {
                result++;
            }
        }
        return result;
    }

    record Shape(
        String className,
        int classAccess,
        int methodCount,
        int methodAccess,
        String methodName,
        String descriptor,
        int maxLocals,
        List<String> events,
        String canonical,
        String sha256,
        int heightBoundaryCandidates,
        int widthBoundaryCandidates,
        int fillCount,
        int loopEntryCount,
        int loopExitCount
    ) {
        Shape {
            events = Collections.unmodifiableList(new ArrayList<>(events));
        }

        boolean exactMethodIdentity() {
            return methodAccess == (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)
                && methodName.equals("render")
                && descriptor.equals(T033FixtureGenerator.RENDER_DESCRIPTOR);
        }

        boolean hasExpectedControlShape() {
            return maxLocals == 35
                && heightBoundaryCandidates == 1
                && widthBoundaryCandidates == 1
                && fillCount == 1
                && loopEntryCount == 4
                && loopExitCount == 4
                && contains(
                    "M:" + INVOKESTATIC + ":dev/turboism/validation/atlasimage/t033/T033FixtureEvents:afterClear:()V:false"
                )
                && countPrefix(events, "M:" + INVOKESTATIC + ":dev/turboism/validation/atlasimage/t033/T033FixtureEvents:beforeSourceRead:(I)V:") == 1
                && countPrefix(events, "M:" + INVOKESTATIC + ":dev/turboism/validation/atlasimage/t033/T033FixtureEvents:afterSourceRead:(I)V:") == 1
                && countPrefix(events, "M:" + INVOKESTATIC + ":dev/turboism/validation/atlasimage/t033/T033FixtureEvents:beforeTargetWrite:(I)V:") == 1
                && countPrefix(events, "M:" + INVOKESTATIC + ":dev/turboism/validation/atlasimage/t033/T033FixtureEvents:afterTargetWrite:(I)V:") == 1
                && count(events, "I:" + IALOAD) == 1
                && count(events, "I:" + IASTORE) == 1;
        }

        boolean contains(final String event) {
            return events.contains(event);
        }
    }

    private static final class Collector extends ClassVisitor {
        private String className;
        private int classAccess;
        private int methodCount;
        private MethodShape method;

        private Collector() {
            super(ASM9);
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
            className = name;
            classAccess = access;
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
            if (name.equals("render")) {
                final MethodShape next = new MethodShape(access, name, descriptor);
                method = next;
                return next;
            }
            return null;
        }
    }

    private static final class MethodShape extends MethodVisitor {
        private final int access;
        private final String name;
        private final String descriptor;
        private final List<String> events = new ArrayList<>();
        private final Map<Label, Integer> labels = new IdentityHashMap<>();
        private int maxLocals = -1;
        private int nextLabel;

        private MethodShape(final int access, final String name, final String descriptor) {
            super(ASM9);
            this.access = access;
            this.name = name;
            this.descriptor = descriptor;
        }

        @Override
        public void visitCode() {
            events.add("C");
        }

        @Override
        public void visitLabel(final Label label) {
            events.add("L:" + labelId(label));
        }

        @Override
        public void visitInsn(final int opcode) {
            events.add("I:" + opcode);
        }

        @Override
        public void visitIntInsn(final int opcode, final int operand) {
            events.add("N:" + opcode + ":" + operand);
        }

        @Override
        public void visitVarInsn(final int opcode, final int var) {
            events.add("V:" + opcode + ":" + var);
        }

        @Override
        public void visitTypeInsn(final int opcode, final String type) {
            events.add("T:" + opcode + ":" + type);
        }

        @Override
        public void visitFieldInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor
        ) {
            events.add("F:" + opcode + ":" + owner + ":" + name + ":" + descriptor);
        }

        @Override
        public void visitMethodInsn(
            final int opcode,
            final String owner,
            final String name,
            final String descriptor,
            final boolean isInterface
        ) {
            events.add("M:" + opcode + ":" + owner + ":" + name + ":" + descriptor + ":" + isInterface);
        }

        @Override
        public void visitInvokeDynamicInsn(
            final String name,
            final String descriptor,
            final Handle bootstrapMethodHandle,
            final Object... bootstrapMethodArguments
        ) {
            events.add("D:" + name + ":" + descriptor + ":" + bootstrapMethodHandle);
        }

        @Override
        public void visitJumpInsn(final int opcode, final Label label) {
            events.add("J:" + opcode + ":" + labelId(label));
        }

        @Override
        public void visitLdcInsn(final Object value) {
            events.add("LDC:" + value);
        }

        @Override
        public void visitIincInsn(final int var, final int increment) {
            events.add("INC:" + var + ":" + increment);
        }

        @Override
        public void visitTableSwitchInsn(
            final int min,
            final int max,
            final Label dflt,
            final Label... labels
        ) {
            final StringBuilder event = new StringBuilder("TABLE:")
                .append(min).append(':').append(max).append(':').append(labelId(dflt));
            for (final Label label : labels) {
                event.append(':').append(labelId(label));
            }
            events.add(event.toString());
        }

        @Override
        public void visitLookupSwitchInsn(
            final Label dflt,
            final int[] keys,
            final Label[] labels
        ) {
            final StringBuilder event = new StringBuilder("LOOKUP:").append(labelId(dflt));
            for (int index = 0; index < keys.length; index++) {
                event.append(':').append(keys[index]).append(':').append(labelId(labels[index]));
            }
            events.add(event.toString());
        }

        @Override
        public void visitMultiANewArrayInsn(final String descriptor, final int numDimensions) {
            events.add("MULTI:" + descriptor + ":" + numDimensions);
        }

        @Override
        public void visitMaxs(final int maxStack, final int maxLocals) {
            this.maxLocals = maxLocals;
            events.add("MAX:" + maxStack + ":" + maxLocals);
        }

        @Override
        public void visitEnd() {
            events.add("E");
        }

        private int labelId(final Label label) {
            return labels.computeIfAbsent(label, ignored -> nextLabel++);
        }
    }
}
