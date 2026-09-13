package dev.turboism.validation.atlasimage.t035;

import java.util.ArrayList;
import java.util.List;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassReader;
import dev.turboism.validation.atlasimage.shaded.asm97.ClassVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Label;
import dev.turboism.validation.atlasimage.shaded.asm97.MethodVisitor;
import dev.turboism.validation.atlasimage.shaded.asm97.Opcodes;

/** In-memory inverse mapping check: remove only the known 21 inserted events. */
final class T035InstructionMapping implements Opcodes {
    static final String HELPER_OWNER = "dev/turboism/validation/atlasimage/t035/T035OfflineBounds";
    static final String HELPER_DESCRIPTOR = "(II[III[IIIIIZ)J";

    private T035InstructionMapping() {
    }

    static Mapping compare(final byte[] original, final byte[] candidate, final String methodName) {
        final List<String> before = events(original, methodName);
        final List<String> after = events(candidate, methodName);
        final List<String> stripped = stripPatch(after);
        normalizeBoundary(stripped, 35, 13, 9);
        normalizeBoundary(stripped, 36, 18, 8);
        return new Mapping(
            countInstructions(before),
            countInstructions(after),
            countInstructions(stripped),
            before.equals(stripped),
            before,
            after
        );
    }

    private static List<String> events(final byte[] bytes, final String methodName) {
        final List<String> events = new ArrayList<>();
        new ClassReader(bytes).accept(new ClassVisitor(ASM9) {
            @Override
            public MethodVisitor visitMethod(
                final int access,
                final String name,
                final String descriptor,
                final String signature,
                final String[] exceptions
            ) {
                if (!name.equals(methodName) || !descriptor.equals(T035OfficialProfile.DESCRIPTOR)) {
                    return null;
                }
                return new MethodVisitor(ASM9) {
                    @Override
                    public void visitLabel(final Label label) {
                        events.add("L");
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
                    public void visitVarInsn(final int opcode, final int variable) {
                        events.add("V:" + opcode + ":" + variable);
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
                        events.add("M:" + opcode + ":" + owner + ":" + name + ":"
                            + descriptor + ":" + isInterface);
                    }

                    @Override
                    public void visitJumpInsn(final int opcode, final Label label) {
                        events.add("J:" + opcode);
                    }

                    @Override
                    public void visitLdcInsn(final Object value) {
                        events.add("LDC:" + value);
                    }

                    @Override
                    public void visitIincInsn(final int variable, final int increment) {
                        events.add("INC:" + variable + ":" + increment);
                    }
                };
            }
        }, ClassReader.SKIP_FRAMES | ClassReader.SKIP_DEBUG);
        return events;
    }

    private static List<String> stripPatch(final List<String> candidate) {
        final String helperPrefix = "M:" + INVOKESTATIC + ":" + HELPER_OWNER + ":bounds:" + HELPER_DESCRIPTOR;
        int call = -1;
        for (int index = 0; index < candidate.size(); index++) {
            if (candidate.get(index).startsWith(helperPrefix)) {
                if (call != -1) {
                    throw new AssertionError("multiple boundary helper calls");
                }
                call = index;
            }
        }
        if (call < 11 || call + 9 >= candidate.size()) {
            throw new AssertionError("boundary helper insertion is not the expected 21 events");
        }
        final List<String> expectedBefore = List.of(
            "V:21:1", "V:21:2", "V:25:3", "V:21:4", "V:21:5",
            "V:25:6", "V:21:7", "V:21:8", "V:21:9", "V:21:10", "I:4"
        );
        final List<String> expectedAfter = List.of(
            "V:55:37", "V:22:37", "I:136", "V:54:36", "V:22:37",
            "N:16:32", "I:125", "I:136", "V:54:35"
        );
        if (!candidate.subList(call - 11, call).equals(expectedBefore)
                || !candidate.subList(call + 1, call + 10).equals(expectedAfter)) {
            throw new AssertionError("boundary helper event shape mismatch");
        }
        final List<String> result = new ArrayList<>(candidate);
        result.subList(call - 11, call + 10).clear();
        return result;
    }

    private static void normalizeBoundary(
        final List<String> events,
        final int replacement,
        final int endLocal,
        final int originalLocal
    ) {
        final String[] sequence = {"V:21:" + replacement, "I:4", "I:100", "V:54:" + endLocal};
        int match = -1;
        for (int index = 0; index <= events.size() - sequence.length; index++) {
            boolean equal = true;
            for (int offset = 0; offset < sequence.length; offset++) {
                if (!events.get(index + offset).equals(sequence[offset])) {
                    equal = false;
                    break;
                }
            }
            if (equal) {
                if (match != -1) {
                    throw new AssertionError("boundary local appears more than once: " + replacement);
                }
                match = index;
            }
        }
        if (match == -1) {
            throw new AssertionError("patched boundary local missing: " + replacement);
        }
        events.set(match, "V:21:" + originalLocal);
    }

    private static int countInstructions(final List<String> events) {
        int count = 0;
        for (final String event : events) {
            if (!event.equals("L")) {
                count++;
            }
        }
        return count;
    }

    record Mapping(
        int originalInstructionCount,
        int candidateInstructionCount,
        int inverseMappedInstructionCount,
        boolean inverseMappedOriginalInstructionsEqual,
        List<String> originalEvents,
        List<String> candidateEvents
    ) {
    }
}
