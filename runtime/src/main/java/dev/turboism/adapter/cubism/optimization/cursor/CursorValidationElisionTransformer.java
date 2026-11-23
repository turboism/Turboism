package dev.turboism.adapter.cubism.optimization.cursor;

import dev.turboism.adapter.cubism.optimization.HostClassWriters;
import dev.turboism.adapter.cubism.optimization.HostCodeSource;
import dev.turboism.adapter.cubism.optimization.ReviewedMethodShape;
import dev.turboism.adapter.cubism.optimization.SlotBridgeEmitter;
import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.List;
import java.util.Objects;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Rewrites {@code java.awt.Container} and {@code java.awt.Component} to skip redundant native
 * cursor refreshes during Swing layout validation and out-of-bounds pointer queries.
 *
 * <p>Every rewritten site consults standard JDK functional interfaces in
 * {@link System#getProperties()} and falls back to the original JDK code path when the bridge is
 * absent. Both classes are attested as bootstrap/platform JDK classes and verified against the
 * running JVM's own JRT method shapes before any bytecode rewrite completes.</p>
 */
public final class CursorValidationElisionTransformer implements ClassFileTransformer {

    private static final String BOOLEAN_SUPPLIER = "java/util/function/BooleanSupplier";
    private static final String PREDICATE = "java/util/function/Predicate";
    private static final String RUNNABLE = "java/lang/Runnable";
    private static final String ACCESS_PREFIX = "access:";
    private static final int EXPECTED_CONTAINER_SITES = 2;
    private static final int EXPECTED_COMPONENT_SITES = 3;

    private final List<String> validateShape;
    private final List<String> mousePositionShape;
    private final List<String> setCursorShape;
    private final List<String> showShape;
    private final List<String> hideShape;

    private volatile String failure;
    private volatile int matches;
    private volatile int sites;

    /**
     * Captures the reference method shapes from the running JVM's JRT class bytes.
     *
     * @param containerReference JRT bytes of {@code java/awt/Container.class}
     * @param componentReference JRT bytes of {@code java/awt/Component.class}
     */
    public CursorValidationElisionTransformer(final byte[] containerReference, final byte[] componentReference) {
        Objects.requireNonNull(containerReference, "containerReference");
        Objects.requireNonNull(componentReference, "componentReference");
        validateShape = ReviewedMethodShape.read(
                containerReference,
                CursorValidationElisionTarget.CONTAINER_OWNER,
                CursorValidationElisionTarget.VALIDATE_METHOD,
                CursorValidationElisionTarget.VALIDATE_DESCRIPTOR);
        mousePositionShape = ReviewedMethodShape.read(
                containerReference,
                CursorValidationElisionTarget.CONTAINER_OWNER,
                CursorValidationElisionTarget.GET_MOUSE_POSITION_METHOD,
                CursorValidationElisionTarget.GET_MOUSE_POSITION_DESCRIPTOR);
        setCursorShape = ReviewedMethodShape.read(
                componentReference,
                CursorValidationElisionTarget.COMPONENT_OWNER,
                CursorValidationElisionTarget.SET_CURSOR_METHOD,
                CursorValidationElisionTarget.SET_CURSOR_DESCRIPTOR);
        showShape = ReviewedMethodShape.read(
                componentReference,
                CursorValidationElisionTarget.COMPONENT_OWNER,
                CursorValidationElisionTarget.SHOW_METHOD,
                CursorValidationElisionTarget.SHOW_DESCRIPTOR);
        hideShape = ReviewedMethodShape.read(
                componentReference,
                CursorValidationElisionTarget.COMPONENT_OWNER,
                CursorValidationElisionTarget.HIDE_METHOD,
                CursorValidationElisionTarget.HIDE_DESCRIPTOR);
        if (validateShape == null
                || mousePositionShape == null
                || setCursorShape == null
                || showShape == null
                || hideShape == null) {
            throw new IllegalArgumentException("reviewed AWT cursor methods absent from JRT reference");
        }
    }

    /** Returns the latest fail-closed rejection reason, or {@code null}. */
    public String failure() {
        return failure;
    }

    /** Returns the number of rewritten classes. */
    public int matches() {
        return matches;
    }

    /** Returns the total number of injected consult sites. */
    public int sites() {
        return sites;
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader actualLoader,
            final String name,
            final Class<?> type,
            final ProtectionDomain domain,
            final byte[] bytes) {
        if (bytes == null
                || (!CursorValidationElisionTarget.CONTAINER_OWNER.equals(name)
                        && !CursorValidationElisionTarget.COMPONENT_OWNER.equals(name))) {
            return null;
        }
        if (!HostCodeSource.isJdkBootOrPlatform(actualLoader, domain)) {
            failure = "non-JDK loader or code source for " + name;
            return null;
        }
        try {
            if (CursorValidationElisionTarget.CONTAINER_OWNER.equals(name)) {
                return rewriteContainer(bytes);
            }
            return rewriteComponent(bytes);
        } catch (final Exception | LinkageError rejected) {
            failure = rejected.toString();
            return null;
        }
    }

    private byte[] rewriteContainer(final byte[] bytes) {
        if (!matchesJrtShape(
                        validateShape,
                        ReviewedMethodShape.read(
                                bytes,
                                CursorValidationElisionTarget.CONTAINER_OWNER,
                                CursorValidationElisionTarget.VALIDATE_METHOD,
                                CursorValidationElisionTarget.VALIDATE_DESCRIPTOR))
                || !matchesJrtShape(
                        mousePositionShape,
                        ReviewedMethodShape.read(
                                bytes,
                                CursorValidationElisionTarget.CONTAINER_OWNER,
                                CursorValidationElisionTarget.GET_MOUSE_POSITION_METHOD,
                                CursorValidationElisionTarget.GET_MOUSE_POSITION_DESCRIPTOR))) {
            failure = "Container method shape mismatch";
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, ClassLoader.getPlatformClassLoader());
        final int[] localSites = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String methodName,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv =
                                super.visitMethod(access, methodName, descriptor, signature, exceptions);
                        if (CursorValidationElisionTarget.VALIDATE_METHOD.equals(methodName)
                                && CursorValidationElisionTarget.VALIDATE_DESCRIPTOR.equals(descriptor)) {
                            return wrapValidate(mv, localSites);
                        }
                        if (CursorValidationElisionTarget.GET_MOUSE_POSITION_METHOD.equals(methodName)
                                && CursorValidationElisionTarget.GET_MOUSE_POSITION_DESCRIPTOR.equals(descriptor)) {
                            return wrapGetMousePosition(mv, localSites);
                        }
                        return mv;
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (localSites[0] != EXPECTED_CONTAINER_SITES) {
            failure = "Container site count mismatch: " + localSites[0];
            return null;
        }
        matches++;
        sites += localSites[0];
        return writer.toByteArray();
    }

    private byte[] rewriteComponent(final byte[] bytes) {
        if (!matchesJrtShape(
                        setCursorShape,
                        ReviewedMethodShape.read(
                                bytes,
                                CursorValidationElisionTarget.COMPONENT_OWNER,
                                CursorValidationElisionTarget.SET_CURSOR_METHOD,
                                CursorValidationElisionTarget.SET_CURSOR_DESCRIPTOR))
                || !matchesJrtShape(
                        showShape,
                        ReviewedMethodShape.read(
                                bytes,
                                CursorValidationElisionTarget.COMPONENT_OWNER,
                                CursorValidationElisionTarget.SHOW_METHOD,
                                CursorValidationElisionTarget.SHOW_DESCRIPTOR))
                || !matchesJrtShape(
                        hideShape,
                        ReviewedMethodShape.read(
                                bytes,
                                CursorValidationElisionTarget.COMPONENT_OWNER,
                                CursorValidationElisionTarget.HIDE_METHOD,
                                CursorValidationElisionTarget.HIDE_DESCRIPTOR))) {
            failure = "Component method shape mismatch";
            return null;
        }
        final ClassReader reader = new ClassReader(bytes);
        final ClassWriter writer = HostClassWriters.newFrameWriter(reader, ClassLoader.getPlatformClassLoader());
        final int[] localSites = {0};
        reader.accept(
                new ClassVisitor(Opcodes.ASM9, writer) {
                    @Override
                    public MethodVisitor visitMethod(
                            final int access,
                            final String methodName,
                            final String descriptor,
                            final String signature,
                            final String[] exceptions) {
                        final MethodVisitor mv =
                                super.visitMethod(access, methodName, descriptor, signature, exceptions);
                        final boolean matchesSetCursor =
                                CursorValidationElisionTarget.SET_CURSOR_METHOD.equals(methodName)
                                        && CursorValidationElisionTarget.SET_CURSOR_DESCRIPTOR.equals(descriptor);
                        final boolean matchesShow = CursorValidationElisionTarget.SHOW_METHOD.equals(methodName)
                                && CursorValidationElisionTarget.SHOW_DESCRIPTOR.equals(descriptor);
                        final boolean matchesHide = CursorValidationElisionTarget.HIDE_METHOD.equals(methodName)
                                && CursorValidationElisionTarget.HIDE_DESCRIPTOR.equals(descriptor);
                        if (!matchesSetCursor && !matchesShow && !matchesHide) {
                            return mv;
                        }
                        localSites[0]++;
                        return wrapDirtyNotifier(mv);
                    }
                },
                ClassReader.EXPAND_FRAMES);
        if (localSites[0] != EXPECTED_COMPONENT_SITES) {
            failure = "Component site count mismatch: " + localSites[0];
            return null;
        }
        matches++;
        sites += localSites[0];
        return writer.toByteArray();
    }

    private static MethodVisitor wrapValidate(final MethodVisitor downstream, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                if (opcode == Opcodes.INVOKEVIRTUAL
                        && CursorValidationElisionTarget.CONTAINER_OWNER.equals(owner)
                        && CursorValidationElisionTarget.UPDATE_CURSOR_METHOD.equals(name)
                        && CursorValidationElisionTarget.UPDATE_CURSOR_DESCRIPTOR.equals(descriptor)) {
                    localSites[0]++;
                    SlotBridgeEmitter.emitGetSlot(mv, CursorValidationElisionBridge.VALIDATE_PROPERTY);
                    super.visitInsn(Opcodes.DUP);
                    super.visitTypeInsn(Opcodes.INSTANCEOF, BOOLEAN_SUPPLIER);
                    final Label fallback = new Label();
                    final Label callOriginal = new Label();
                    final Label finished = new Label();
                    super.visitJumpInsn(Opcodes.IFEQ, fallback);
                    super.visitTypeInsn(Opcodes.CHECKCAST, BOOLEAN_SUPPLIER);
                    super.visitMethodInsn(Opcodes.INVOKEINTERFACE, BOOLEAN_SUPPLIER, "getAsBoolean", "()Z", true);
                    super.visitJumpInsn(Opcodes.IFNE, callOriginal);
                    super.visitInsn(Opcodes.POP);
                    super.visitJumpInsn(Opcodes.GOTO, finished);
                    super.visitLabel(fallback);
                    super.visitInsn(Opcodes.POP);
                    super.visitLabel(callOriginal);
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    super.visitLabel(finished);
                    return;
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }
        };
    }

    private static MethodVisitor wrapGetMousePosition(final MethodVisitor downstream, final int[] localSites) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            private static final String MOUSE_INFO_OWNER = "java/awt/MouseInfo";
            private static final String GET_POINTER_INFO_METHOD = "getPointerInfo";
            private static final String GET_POINTER_INFO_DESCRIPTOR = "()Ljava/awt/PointerInfo;";
            private static final String PREDICATE_TEST_METHOD = "test";
            private static final String PREDICATE_TEST_DESCRIPTOR = "(Ljava/lang/Object;)Z";
            private boolean injected;

            @Override
            public void visitTypeInsn(final int opcode, final String type) {
                if (!injected
                        && opcode == Opcodes.NEW
                        && type.startsWith(CursorValidationElisionTarget.CONTAINER_POINTER_ACTION_PREFIX)) {
                    emitOutsideFastNull();
                }
                super.visitTypeInsn(opcode, type);
            }

            @Override
            public void visitMethodInsn(
                    final int opcode,
                    final String owner,
                    final String name,
                    final String descriptor,
                    final boolean isInterface) {
                // JDK 25 removes SecurityManager, so Container.getMousePosition calls
                // MouseInfo.getPointerInfo directly instead of creating a PrivilegedAction.
                if (!injected
                        && opcode == Opcodes.INVOKESTATIC
                        && !isInterface
                        && MOUSE_INFO_OWNER.equals(owner)
                        && GET_POINTER_INFO_METHOD.equals(name)
                        && GET_POINTER_INFO_DESCRIPTOR.equals(descriptor)) {
                    emitOutsideFastNull();
                }
                super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
            }

            private void emitOutsideFastNull() {
                injected = true;
                localSites[0]++;
                SlotBridgeEmitter.emitGetSlot(mv, CursorValidationElisionBridge.OUTSIDE_PROPERTY);
                super.visitInsn(Opcodes.DUP);
                super.visitTypeInsn(Opcodes.INSTANCEOF, PREDICATE);
                final Label fallback = new Label();
                final Label proceed = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, fallback);
                super.visitTypeInsn(Opcodes.CHECKCAST, PREDICATE);
                super.visitVarInsn(Opcodes.ALOAD, 0);
                super.visitMethodInsn(
                        Opcodes.INVOKEINTERFACE, PREDICATE, PREDICATE_TEST_METHOD, PREDICATE_TEST_DESCRIPTOR, true);
                super.visitJumpInsn(Opcodes.IFEQ, proceed);
                super.visitInsn(Opcodes.ACONST_NULL);
                super.visitInsn(Opcodes.ARETURN);
                super.visitLabel(fallback);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(proceed);
            }
        };
    }

    private static MethodVisitor wrapDirtyNotifier(final MethodVisitor downstream) {
        return new MethodVisitor(Opcodes.ASM9, downstream) {
            @Override
            public void visitCode() {
                super.visitCode();
                SlotBridgeEmitter.emitGetSlot(mv, CursorValidationElisionBridge.DIRTY_PROPERTY);
                super.visitInsn(Opcodes.DUP);
                super.visitTypeInsn(Opcodes.INSTANCEOF, RUNNABLE);
                final Label skip = new Label();
                final Label done = new Label();
                super.visitJumpInsn(Opcodes.IFEQ, skip);
                super.visitTypeInsn(Opcodes.CHECKCAST, RUNNABLE);
                super.visitMethodInsn(Opcodes.INVOKEINTERFACE, RUNNABLE, "run", "()V", true);
                super.visitJumpInsn(Opcodes.GOTO, done);
                super.visitLabel(skip);
                super.visitInsn(Opcodes.POP);
                super.visitLabel(done);
            }
        };
    }

    // Accepts JVMTI-reconstituted bytecode where HotSpot omits the synthetic Deprecated attribute flag.
    private static boolean matchesJrtShape(final List<String> expected, final List<String> actual) {
        if (actual == null || actual.size() != expected.size()) {
            return false;
        }
        final int expectedAccess = parseAccessFlags(expected.get(0));
        final int actualAccess = parseAccessFlags(actual.get(0));
        if (expectedAccess < 0
                || actualAccess < 0
                || (expectedAccess & ~Opcodes.ACC_DEPRECATED) != (actualAccess & ~Opcodes.ACC_DEPRECATED)) {
            return false;
        }
        return expected.subList(1, expected.size()).equals(actual.subList(1, actual.size()));
    }

    private static int parseAccessFlags(final String header) {
        if (!header.startsWith(ACCESS_PREFIX)) {
            return -1;
        }
        try {
            return Integer.parseInt(header.substring(ACCESS_PREFIX.length()));
        } catch (final NumberFormatException invalid) {
            return -1;
        }
    }
}
