package dev.turboism.adapter.cubism.warpalt;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Objects;
import java.util.Optional;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

/** Reads the dispatcher's view without probing obfuscated, potentially mutating event methods. */
final class WarpAltMirrorActionContext {
    private final WarpAltMirrorHostProfile profile;
    private final ClassValue<Optional<Method>> getters = new ClassValue<>() {
        @Override
        protected Optional<Method> computeValue(Class<?> type) {
            if (!type.getName().equals(profile.actionDispatchOwner().replace('/', '.'))) return Optional.empty();
            try {
                Method getter = type.getDeclaredMethod(profile.actionViewMethod());
                int flags = getter.getModifiers();
                if (!Modifier.isPublic(flags)
                        || !Modifier.isFinal(flags)
                        || Modifier.isStatic(flags)
                        || !Type.getMethodDescriptor(getter).equals(profile.actionViewDescriptor())
                        || getter.getReturnType().getClassLoader() != type.getClassLoader()) return Optional.empty();
                return Optional.of(getter);
            } catch (NoSuchMethodException missing) {
                return Optional.empty();
            }
        }
    };

    WarpAltMirrorActionContext(WarpAltMirrorHostProfile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
    }

    Object view(Object manager) throws ReflectiveOperationException {
        if (manager == null) return null;
        Method getter = getters.get(manager.getClass())
                .orElseThrow(() -> new NoSuchMethodException(manager.getClass().getName() + "#"
                        + profile.actionViewMethod() + profile.actionViewDescriptor()));
        return getter.invoke(manager);
    }

    /** Admission requires exactly a field read, not just a compatible return type. */
    static boolean hasReadOnlyGetter(ClassReader reader, WarpAltMirrorHostProfile profile) {
        boolean[] matched = {false};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            int access, String name, String descriptor, String signature, String[] exceptions) {
                        if (!name.equals(profile.actionViewMethod())
                                || !descriptor.equals(profile.actionViewDescriptor())) return null;
                        int mask = Opcodes.ACC_PUBLIC
                                | Opcodes.ACC_FINAL
                                | Opcodes.ACC_STATIC
                                | Opcodes.ACC_NATIVE
                                | Opcodes.ACC_ABSTRACT
                                | Opcodes.ACC_SYNCHRONIZED;
                        if ((access & mask) != (Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL)) return null;
                        return new MethodVisitor(Opcodes.ASM9) {
                            int step;
                            boolean valid = true;

                            @Override
                            public void visitVarInsn(int opcode, int variable) {
                                valid &= step++ == 0 && opcode == Opcodes.ALOAD && variable == 0;
                            }

                            @Override
                            public void visitFieldInsn(int opcode, String owner, String field, String fieldDescriptor) {
                                valid &= step++ == 1
                                        && opcode == Opcodes.GETFIELD
                                        && owner.equals(profile.actionDispatchOwner())
                                        && fieldDescriptor.equals(
                                                Type.getReturnType(descriptor).getDescriptor());
                            }

                            @Override
                            public void visitInsn(int opcode) {
                                valid &= step++ == 2 && opcode == Opcodes.ARETURN;
                            }

                            @Override
                            public void visitIntInsn(int opcode, int operand) {
                                valid = false;
                            }

                            @Override
                            public void visitTypeInsn(int opcode, String type) {
                                valid = false;
                            }

                            @Override
                            public void visitMethodInsn(
                                    int opcode, String owner, String name, String descriptor, boolean isInterface) {
                                valid = false;
                            }

                            @Override
                            public void visitInvokeDynamicInsn(
                                    String name, String descriptor, Handle bootstrap, Object... arguments) {
                                valid = false;
                            }

                            @Override
                            public void visitJumpInsn(int opcode, Label label) {
                                valid = false;
                            }

                            @Override
                            public void visitLdcInsn(Object value) {
                                valid = false;
                            }

                            @Override
                            public void visitIincInsn(int variable, int increment) {
                                valid = false;
                            }

                            @Override
                            public void visitTableSwitchInsn(int min, int max, Label fallback, Label... labels) {
                                valid = false;
                            }

                            @Override
                            public void visitLookupSwitchInsn(Label fallback, int[] keys, Label[] labels) {
                                valid = false;
                            }

                            @Override
                            public void visitMultiANewArrayInsn(String descriptor, int dimensions) {
                                valid = false;
                            }

                            @Override
                            public void visitTryCatchBlock(Label start, Label end, Label handler, String type) {
                                valid = false;
                            }

                            @Override
                            public void visitEnd() {
                                matched[0] = valid && step == 3;
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return matched[0];
    }
}
