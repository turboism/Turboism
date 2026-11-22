package dev.turboism.adapter.cubism.meshedit;

import dev.turboism.core.runtime.work.FatalErrors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/** Shared ASM framing, validation, and prefix-emission helpers for mesh-edit patchers. */
final class MeshEditAsmSupport {

    static final int ASM_API = Opcodes.ASM9;
    static final int STATUS_TRUE = 1;
    static final int STATUS_FALSE = 0;

    static final String OBJECT_INTERNAL = "java/lang/Object";
    static final String OBJECT_DESC = "Ljava/lang/Object;";
    private static final String MESHEDIT_BRIDGE_PREFIX = "dev/turboism/adapter/cubism/meshedit/";

    private MeshEditAsmSupport() {}

    /**
     * Fails closed when expected host bytecode structure does not match any supported shape.
     *
     * @param condition invariant required for safe bytecode patching
     * @param message diagnostic reason when rejected
     */
    static void requireShape(final boolean condition, final String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    /**
     * Creates a frame-computing writer that resolves host types without linking unloaded classes.
     *
     * @param reader source class reader used for constant-pool sharing
     * @param fallbackLoader nullable classloader capable of inspecting host superclasses
     * @return configured class writer computing frames and max stack/locals
     */
    static ClassWriter newClassWriter(final ClassReader reader, final ClassLoader fallbackLoader) {
        final ClassLoader loader =
                fallbackLoader != null ? fallbackLoader : MeshEditAsmSupport.class.getClassLoader();
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                if (OBJECT_INTERNAL.equals(type1) || OBJECT_INTERNAL.equals(type2)) {
                    return OBJECT_INTERNAL;
                }
                if (type1.equals(type2)) {
                    return type1;
                }
                try {
                    Class<?> first = Class.forName(type1.replace('/', '.'), false, loader);
                    final Class<?> second = Class.forName(type2.replace('/', '.'), false, loader);
                    if (first.isAssignableFrom(second)) {
                        return type1;
                    }
                    if (second.isAssignableFrom(first)) {
                        return type2;
                    }
                    if (first.isInterface() || second.isInterface()) {
                        return OBJECT_INTERNAL;
                    }
                    do {
                        first = first.getSuperclass();
                    } while (first != null && !first.isAssignableFrom(second));
                    return first != null ? first.getName().replace('.', '/') : OBJECT_INTERNAL;
                } catch (final Throwable failure) {
                    FatalErrors.rethrowIfFatal(failure);
                    return OBJECT_INTERNAL;
                }
            }
        };
    }

    /**
     * Parses a class without expanding frames before frame recomputation.
     *
     * @param classBytes raw JVM class bytes
     * @return reader instance
     */
    static ClassReader newClassReader(final byte[] classBytes) {
        return new ClassReader(classBytes);
    }

    /**
     * Checks whether any method already references a mesh-edit acceleration bridge class.
     *
     * @param reader parsed host class reader
     * @return {@code true} if the class was already instrumented
     */
    static boolean isAlreadyInstrumented(final ClassReader reader) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM_API) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        return new MethodVisitor(ASM_API) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
                                    final boolean isInterface) {
                                if (owner.startsWith(MESHEDIT_BRIDGE_PREFIX)) {
                                    found[0] = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * Emits early return handling when a bridge returns an object, sentinel null, or null fallback.
     *
     * @param mv target method visitor positioned before the original instructions
     * @param bridgeOwner internal name of the Java bridge class
     * @param sentinelField static sentinel field representing a resolved null result
     * @param targetTypeInternal internal class name returned after casting non-sentinel hits
     */
    static void emitSentinelObjectReturnOrFallback(
            final MethodVisitor mv,
            final String bridgeOwner,
            final String sentinelField,
            final String targetTypeInternal) {
        final Label hitLabel = new Label();
        final Label fallbackLabel = new Label();
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFNULL, fallbackLabel);
        mv.visitInsn(Opcodes.DUP);
        mv.visitFieldInsn(Opcodes.GETSTATIC, bridgeOwner, sentinelField, OBJECT_DESC);
        mv.visitJumpInsn(Opcodes.IF_ACMPNE, hitLabel);
        mv.visitInsn(Opcodes.POP);
        mv.visitInsn(Opcodes.ACONST_NULL);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(hitLabel);
        mv.visitTypeInsn(Opcodes.CHECKCAST, targetTypeInternal);
        mv.visitInsn(Opcodes.ARETURN);
        mv.visitLabel(fallbackLabel);
        mv.visitInsn(Opcodes.POP);
    }

    /**
     * Emits early boolean return for three-state bridges returning {@code -1} on fallback.
     *
     * @param mv target method visitor positioned after placing the int status on stack
     */
    static void emitTriStateBooleanReturnOrFallback(final MethodVisitor mv) {
        final Label trueLabel = new Label();
        final Label fallbackLabel = new Label();
        mv.visitInsn(Opcodes.DUP);
        mv.visitJumpInsn(Opcodes.IFLT, fallbackLabel);
        mv.visitJumpInsn(Opcodes.IFNE, trueLabel);
        mv.visitInsn(Opcodes.ICONST_0);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(trueLabel);
        mv.visitInsn(Opcodes.ICONST_1);
        mv.visitInsn(Opcodes.IRETURN);
        mv.visitLabel(fallbackLabel);
        mv.visitInsn(Opcodes.POP);
    }
}
