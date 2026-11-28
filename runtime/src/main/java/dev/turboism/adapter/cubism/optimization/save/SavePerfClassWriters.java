package dev.turboism.adapter.cubism.optimization.save;

import static org.objectweb.asm.Opcodes.ASM9;

import dev.turboism.core.runtime.work.FatalErrors;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Provides classloader-aware ASM {@link ClassWriter} construction and structural bytecode scans
 * for save-path patchers.
 */
final class SavePerfClassWriters {

    private static final String OBJECT_INTERNAL = "java/lang/Object";

    private SavePerfClassWriters() {}

    /**
     * Creates a frame-computing {@link ClassWriter} anchored to the host {@link ClassLoader}.
     *
     * @param reader source class reader used for constant-pool copying
     * @param loader target host class loader, nullable
     * @return configured class writer using {@code COMPUTE_FRAMES}
     */
    public static ClassWriter newFrameWriter(final ClassReader reader, final ClassLoader loader) {
        return new ClassWriter(reader, ClassWriter.COMPUTE_FRAMES) {
            @Override
            protected String getCommonSuperClass(final String type1, final String type2) {
                return resolveCommonSuperClass(loader, type1, type2);
            }
        };
    }

    /**
     * Checks whether the inspected bytecode declares a method with the given name and descriptor.
     *
     * @param reader parsed class reader
     * @param methodName exact method name
     * @param methodDesc exact JVM method descriptor
     * @return {@code true} if the method is declared
     */
    public static boolean hasDeclaredMethod(
            final ClassReader reader, final String methodName, final String methodDesc) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        if (methodName.equals(name) && methodDesc.equals(descriptor)) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    /**
     * Checks whether any method in the class invokes a member on {@code ownerInternalName}.
     *
     * @param reader parsed class reader
     * @param ownerInternalName JVM internal name of the called class
     * @return {@code true} if at least one invocation targets {@code ownerInternalName}
     */
    public static boolean callsOwner(final ClassReader reader, final String ownerInternalName) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String mName,
                                    final String mDesc,
                                    final boolean isInterface) {
                                if (ownerInternalName.equals(owner)) {
                                    found[0] = true;
                                }
                            }
                        };
                    }
                },
                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return found[0];
    }

    private static String resolveCommonSuperClass(final ClassLoader loader, final String type1, final String type2) {
        final ClassLoader effective = loader != null ? loader : SavePerfClassWriters.class.getClassLoader();
        try {
            Class<?> class1 = Class.forName(type1.replace('/', '.'), false, effective);
            final Class<?> class2 = Class.forName(type2.replace('/', '.'), false, effective);
            if (class1.isAssignableFrom(class2)) {
                return type1;
            }
            if (class2.isAssignableFrom(class1)) {
                return type2;
            }
            if (class1.isInterface() || class2.isInterface()) {
                return OBJECT_INTERNAL;
            }
            do {
                class1 = class1.getSuperclass();
            } while (class1 != null && !class1.isAssignableFrom(class2));
            return class1 == null ? OBJECT_INTERNAL : class1.getName().replace('.', '/');
        } catch (final Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
            return OBJECT_INTERNAL;
        }
    }
}
