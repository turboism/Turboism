package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.objectweb.asm.Opcodes.ASM9;

import dev.turboism.core.runtime.work.FatalErrors;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;

/**
 * Provides ASM frame-computing writers and idempotency probes without triggering host class loading.
 */
final class ModelOpenClassWriters {

    private static final String OBJECT_INTERNAL_NAME = "java/lang/Object";
    private static final String CLASS_RESOURCE_SUFFIX = ".class";
    private static final int DEFAULT_READ_FLAGS =
            ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES;
    private static final int FRAME_WRITER_FLAGS = ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS;

    private ModelOpenClassWriters() {}

    /** Creates a frame-computing {@link ClassWriter} that resolves hierarchies via bytecode streams. */
    static ClassWriter newFrameWriter(final ClassReader reader, final ClassLoader loader) {
        return new ClassWriter(reader, FRAME_WRITER_FLAGS) {
            @Override
            protected ClassLoader getClassLoader() {
                return loader != null ? loader : super.getClassLoader();
            }

            @Override
            protected String getCommonSuperClass(final String left, final String right) {
                return resolveCommonSuperClass(loader, left, right);
            }
        };
    }

    /** Returns {@code true} when {@code methodName} with {@code descriptor} already exists. */
    static boolean hasDeclaredMethod(final ClassReader reader, final String methodName, final String descriptor) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        if (methodName.equals(name) && (descriptor == null || descriptor.equals(desc))) {
                            found[0] = true;
                        }
                        return null;
                    }
                },
                DEFAULT_READ_FLAGS);
        return found[0];
    }

    /** Returns {@code true} when any method calls a member declared on {@code ownerInternalName}. */
    static boolean callsOwner(final ClassReader reader, final String ownerInternalName) {
        final boolean[] found = new boolean[1];
        reader.accept(
                new ClassVisitor(ASM9) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String name,
                            final String desc,
                            final String signature,
                            final String[] exceptions) {
                        return new MethodVisitor(ASM9) {
                            @Override
                            public void visitMethodInsn(
                                    final int opcode,
                                    final String owner,
                                    final String methodName,
                                    final String methodDesc,
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

    private static String resolveCommonSuperClass(final ClassLoader loader, final String left, final String right) {
        if (OBJECT_INTERNAL_NAME.equals(left) || OBJECT_INTERNAL_NAME.equals(right)) {
            return OBJECT_INTERNAL_NAME;
        }
        if (left.equals(right)) {
            return left;
        }
        final ClassLoader effectiveLoader = loader != null ? loader : ModelOpenClassWriters.class.getClassLoader();
        try {
            final Set<String> leftAncestors = new HashSet<>();
            String cursor = left;
            while (cursor != null) {
                if (cursor.equals(right)) {
                    return right;
                }
                leftAncestors.add(cursor);
                cursor = readSuperName(effectiveLoader, cursor);
            }
            cursor = right;
            while (cursor != null) {
                if (leftAncestors.contains(cursor)) {
                    return cursor;
                }
                cursor = readSuperName(effectiveLoader, cursor);
            }
            return OBJECT_INTERNAL_NAME;
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return OBJECT_INTERNAL_NAME;
        }
    }

    private static String readSuperName(final ClassLoader loader, final String internalName) {
        if (OBJECT_INTERNAL_NAME.equals(internalName) || loader == null) {
            return null;
        }
        try (InputStream stream = loader.getResourceAsStream(internalName + CLASS_RESOURCE_SUFFIX)) {
            if (stream == null) {
                return null;
            }
            final ClassReader cr = new ClassReader(stream);
            return cr.getSuperName();
        } catch (final IOException ignored) {
            return null;
        }
    }
}
