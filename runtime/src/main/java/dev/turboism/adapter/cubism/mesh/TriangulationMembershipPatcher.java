package dev.turboism.adapter.cubism.mesh;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import org.objectweb.asm.AnnotationVisitor;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.TypePath;

/** Guarded fusion of two reviewed contains/add sites using only the admitted core ASM module. */
final class TriangulationMembershipPatcher implements Opcodes {
    private static final String P = "com/live2d/graphics3d/editableMesh/triangulation/";
    private static final String OWNER = P + "h";
    private static final String LIST = P + "TriangleList";
    private static final String ARG = "(L" + P + "l;)Z";
    private static final String METHOD = "(L" + P + "l;L" + P + "l;L" + P + "j;)Ljava/util/List;";

    private TriangulationMembershipPatcher() {}

    static byte[] patch(final byte[] bytes) {
        final ClassReader reader = new ClassReader(bytes);
        require(OWNER.equals(reader.getClassName()), "owner");
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        final int[] methods = {0};
        reader.accept(new ClassVisitor(ASM9, writer) {
            @Override
            public MethodVisitor visitMethod(final int access, final String name, final String descriptor,
                    final String signature, final String[] exceptions) {
                final MethodVisitor output = super.visitMethod(access, name, descriptor, signature, exceptions);
                if (!name.equals("a") || !descriptor.equals(METHOD)) return output;
                require(access == (ACC_PUBLIC | ACC_FINAL), "method access");
                methods[0]++;
                return new RecordingMethod(output);
            }
        }, ClassReader.EXPAND_FRAMES);
        require(methods[0] == 1, "method count");
        return writer.toByteArray();
    }

    // Record only the selected method. Labels retain identity when events are replayed;
    // no host class hierarchy resolution or additional ASM module is needed.
    private record Event(int opcode, int variable, String member, Label label, Object[] locals,
                         Consumer<MethodVisitor> replay) {}

    private static final class RecordingMethod extends MethodVisitor {
        private final List<Event> events = new ArrayList<>();

        RecordingMethod(final MethodVisitor output) { super(ASM9, output); }

        private void event(final int opcode, final int variable, final String member,
                final Label label, final Object[] locals, final Consumer<MethodVisitor> replay) {
            events.add(new Event(opcode, variable, member, label, locals, replay));
        }

        private void plain(final int opcode, final Consumer<MethodVisitor> replay) {
            event(opcode, -1, null, null, null, replay);
        }

        @Override public void visitInsn(final int opcode) { plain(opcode, v -> v.visitInsn(opcode)); }
        @Override public void visitIntInsn(final int opcode, final int operand) {
            plain(opcode, v -> v.visitIntInsn(opcode, operand));
        }
        @Override public void visitVarInsn(final int opcode, final int variable) {
            event(opcode, variable, null, null, null, v -> v.visitVarInsn(opcode, variable));
        }
        @Override public void visitTypeInsn(final int opcode, final String type) {
            plain(opcode, v -> v.visitTypeInsn(opcode, type));
        }
        @Override public void visitFieldInsn(final int opcode, final String owner, final String name,
                final String descriptor) {
            plain(opcode, v -> v.visitFieldInsn(opcode, owner, name, descriptor));
        }
        @Override public void visitMethodInsn(final int opcode, final String owner, final String name,
                final String descriptor, final boolean itf) {
            event(opcode, -1, itf ? null : owner + "." + name + descriptor, null, null,
                    v -> v.visitMethodInsn(opcode, owner, name, descriptor, itf));
        }
        @Override public void visitInvokeDynamicInsn(final String name, final String descriptor,
                final Handle handle, final Object... arguments) {
            final Object[] copied = arguments.clone();
            plain(INVOKEDYNAMIC, v -> v.visitInvokeDynamicInsn(name, descriptor, handle, copied));
        }
        @Override public void visitJumpInsn(final int opcode, final Label target) {
            event(opcode, -1, null, target, null, v -> v.visitJumpInsn(opcode, target));
        }
        @Override public void visitLabel(final Label label) {
            event(-1, -1, null, label, null, v -> v.visitLabel(label));
        }
        @Override public void visitFrame(final int type, final int nLocal, final Object[] local,
                final int nStack, final Object[] stack) {
            require(type == F_NEW, "expanded frame required");
            final Object[] locals = java.util.Arrays.copyOf(local, nLocal);
            final Object[] operands = nStack == 0 ? new Object[0] : java.util.Arrays.copyOf(stack, nStack);
            event(-2, nStack, null, null, locals,
                    v -> v.visitFrame(type, nLocal, locals, nStack, operands));
        }
        @Override public void visitLdcInsn(final Object value) { plain(LDC, v -> v.visitLdcInsn(value)); }
        @Override public void visitIincInsn(final int variable, final int increment) {
            plain(IINC, v -> v.visitIincInsn(variable, increment));
        }
        @Override public void visitTableSwitchInsn(final int min, final int max, final Label dflt,
                final Label... labels) {
            final Label[] copied = labels.clone();
            plain(TABLESWITCH, v -> v.visitTableSwitchInsn(min, max, dflt, copied));
        }
        @Override public void visitLookupSwitchInsn(final Label dflt, final int[] keys, final Label[] labels) {
            final int[] copiedKeys = keys.clone();
            final Label[] copiedLabels = labels.clone();
            plain(LOOKUPSWITCH, v -> v.visitLookupSwitchInsn(dflt, copiedKeys, copiedLabels));
        }
        @Override public void visitMultiANewArrayInsn(final String descriptor, final int dimensions) {
            plain(MULTIANEWARRAY, v -> v.visitMultiANewArrayInsn(descriptor, dimensions));
        }
        @Override public void visitTryCatchBlock(final Label start, final Label end, final Label handler,
                final String type) { throw new IllegalArgumentException("unexpected handlers"); }
        @Override public void visitLocalVariable(final String name, final String descriptor,
                final String signature, final Label start, final Label end, final int index) {
            plain(-3, v -> v.visitLocalVariable(name, descriptor, signature, start, end, index));
        }
        @Override public void visitLineNumber(final int line, final Label start) {
            plain(-3, v -> v.visitLineNumber(line, start));
        }
        @Override public AnnotationVisitor visitInsnAnnotation(final int ref, final TypePath path,
                final String descriptor, final boolean visible) {
            throw new IllegalArgumentException("unexpected instruction annotation");
        }
        @Override public AnnotationVisitor visitLocalVariableAnnotation(final int ref, final TypePath path,
                final Label[] start, final Label[] end, final int[] index, final String descriptor,
                final boolean visible) {
            throw new IllegalArgumentException("unexpected local annotation");
        }
        @Override public void visitMaxs(final int stack, final int locals) {
            plain(-3, v -> v.visitMaxs(stack, locals));
        }
        @Override
        @SuppressWarnings("ReferenceEquality") // Branch must land at this exact recorded instruction.
        public void visitEnd() {
            final List<Event> ops = events.stream().filter(e -> e.opcode >= 0).toList();
            final Map<Event, Event[]> sites = new IdentityHashMap<>();
            for (int i = 2; i + 6 < ops.size(); i++) {
                if (!call(ops.get(i), "c") || ops.get(i + 1).opcode != IFNE) continue;
                final Event[] block = ops.subList(i - 2, i + 6).toArray(Event[]::new);
                require(load(block[0], 4) && load(block[4], 4), "receiver local");
                final int argument = sites.isEmpty() ? 7 : 8;
                require(load(block[1], argument) && load(block[5], argument), "argument local");
                require(call(block[6], "a") && block[7].opcode == POP, "conditional add");
                require(nextAt(block[3].label) == ops.get(i + 6), "branch skips more than add");
                final Event frame = frameBefore(block[0]);
                require(frame.variable == 0, "nonempty join stack");
                for (final Object local : frame.locals) require(!(local instanceof Label), "uninitialized local");
                sites.put(block[0], block);
            }
            require(sites.size() == 2, "exactly two sites required");
            for (final Event event : events) {
                final Event[] block = sites.get(event);
                if (block != null) {
                    final Event frame = frameBefore(event);
                    final Label original = new Label();
                    mv.visitFieldInsn(GETSTATIC, P + "c", "a", "L" + P + "c$a;");
                    mv.visitMethodInsn(INVOKEVIRTUAL, P + "c$a", "b", "()Z", false);
                    mv.visitJumpInsn(IFNE, original);
                    mv.visitVarInsn(ALOAD, 4);
                    mv.visitVarInsn(ALOAD, block[1].variable);
                    mv.visitMethodInsn(INVOKEVIRTUAL, LIST, "a", ARG, false);
                    mv.visitInsn(POP);
                    mv.visitJumpInsn(GOTO, block[3].label);
                    mv.visitLabel(original);
                    mv.visitFrame(F_NEW, frame.locals.length, frame.locals, 0, new Object[0]);
                }
                event.replay.accept(mv);
            }
            super.visitEnd();
        }

        private Event frameBefore(final Event at) {
            for (int i = events.indexOf(at) - 1; i >= 0; i--) {
                final Event event = events.get(i);
                if (event.opcode == -2) return event;
                require(event.opcode < 0, "no frame at site boundary");
            }
            throw new IllegalArgumentException("missing join frame");
        }

        private Event nextAt(final Label label) {
            boolean seen = false;
            for (final Event event : events) {
                if (event.opcode == -1 && event.label == label) seen = true;
                if (seen && event.opcode >= 0) return event;
            }
            throw new IllegalArgumentException("missing target");
        }
    }

    private static boolean load(final Event event, final int variable) {
        return event.opcode == ALOAD && event.variable == variable;
    }
    private static boolean call(final Event event, final String name) {
        return event.opcode == INVOKEVIRTUAL && (LIST + "." + name + ARG).equals(event.member);
    }
    private static void require(final boolean condition, final String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
