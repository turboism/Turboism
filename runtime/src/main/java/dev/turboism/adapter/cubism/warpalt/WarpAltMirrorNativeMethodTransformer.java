package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.adapter.cubism.optimization.ReviewedHostContract;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.instrument.ClassFileTransformer;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Exact-selector transformer for the Warp deformer Alt-symmetry hook.
 *
 * <p>Reviewed injection points, each verified against the 5.2.03, 5.3.02
 * and 5.3.03 artifact digests with exact owner/method/descriptor selectors:</p>
 *
 * <ul>
 *   <li>{@code WarpPointRef.moveToOnLocal(GVector2, float)} — head: the converged
 *       doc-level control-point write of every deformer-edit flow; the mirrored
 *       counterpart write lands inside the gesture's own undo envelope.</li>
 *   <li>{@code temporaryHandler.a.b(GVector2, aG)} — head: the dedicated bend-tool
 *       drag tick, carrying the handler, drag position and modifier event.</li>
 *   <li>{@code doc.selection.PointSelector.add(IPointRef, float, boolean)} and
 *       {@code setWeight(IPointRef, float)} — head: the converged weighted-
 *       selection writes; the mirrored counterpart write lands inside the
 *       caller's own selection/undo envelope.</li>
 * </ul>
 */
public final class WarpAltMirrorNativeMethodTransformer implements ClassFileTransformer {
    private static final String BRIDGE = "dev/turboism/adapter/cubism/warpalt/NativeWarpAltMirrorBridge";

    private final WarpAltMirrorHostProfile profile;
    private final ClassLoader expectedClassLoader;
    private final Path expectedArtifact;
    private final Map<String, String> pinnedClassSha256;
    private final Consumer<String> diagnostic;
    private final AtomicReference<ClassLoader> admittedClassLoader = new AtomicReference<>();
    private final AtomicReference<Outcome> outcome = new AtomicReference<>(Outcome.NONE);
    private final Set<String> transformedOwners = ConcurrentHashMap.newKeySet();
    private final AtomicBoolean active = new AtomicBoolean(true);

    public WarpAltMirrorNativeMethodTransformer(
            final WarpAltMirrorHostProfile profile, final ClassLoader expectedClassLoader) {
        this(profile, expectedClassLoader, null, ignored -> {});
    }

    public WarpAltMirrorNativeMethodTransformer(
            final WarpAltMirrorHostProfile profile,
            final ClassLoader expectedClassLoader,
            final Path expectedArtifact,
            final Consumer<String> diagnostic) {
        this(profile, expectedClassLoader, expectedArtifact, Map.of(), diagnostic);
    }

    /**
     * Creates a transformer with proof for the actual loaded class definitions.
     *
     * @param profile bound selector contract
     * @param expectedClassLoader defining loader, or null before first definition
     * @param expectedArtifact artifact path, or null for an isolated bytecode fixture
     * @param pinnedClassSha256 class pins from the resolved contract
     * @param diagnostic diagnostic consumer
     */
    public WarpAltMirrorNativeMethodTransformer(
            final WarpAltMirrorHostProfile profile,
            final ClassLoader expectedClassLoader,
            final Path expectedArtifact,
            final Map<String, String> pinnedClassSha256,
            final Consumer<String> diagnostic) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.expectedClassLoader = expectedClassLoader;
        this.expectedArtifact = expectedArtifact == null
                ? null
                : expectedArtifact.toAbsolutePath().normalize();
        this.pinnedClassSha256 = Map.copyOf(pinnedClassSha256);
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
    }

    @Override
    public byte[] transform(
            final Module module,
            final ClassLoader loader,
            final String className,
            final Class<?> classBeingRedefined,
            final ProtectionDomain protectionDomain,
            final byte[] classfileBuffer) {
        if (!active.get() || className == null || classfileBuffer == null) return null;
        final boolean isPointMove = profile.pointMoveOwner().equals(className);
        final boolean isDragTick = profile.dragTickOwner().equals(className);
        final boolean isStrip = profile.stripOwner().equals(className);
        final boolean isGreenTick = profile.greenTickOwner().equals(className);
        final boolean isWeightWrite = profile.weightWriteOwner().equals(className);
        final boolean isActionDispatch = profile.actionDispatchOwner().equals(className);
        final boolean isInputIngress = profile.inputIngressOwner().equals(className);
        if (!isPointMove
                && !isDragTick
                && !isGreenTick
                && !isStrip
                && !isWeightWrite
                && !isActionDispatch
                && !isInputIngress) return null;
        if (classBeingRedefined != null) {
            reject(Outcome.RETRANSFORM_REJECTED, "WARP_ALT_MIRROR_RETRANSFORM_REJECTED owner=" + className);
            return null;
        }
        if (!admit(loader, protectionDomain, className, classfileBuffer)) return null;

        final boolean[] transformed = {false};
        try {
            final ClassReader reader = new ClassReader(classfileBuffer);
            final ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES) {
                @Override
                protected String getCommonSuperClass(final String left, final String right) {
                    try {
                        final ClassLoader classLoader = admittedClassLoader.get() == null
                                ? WarpAltMirrorNativeMethodTransformer.class.getClassLoader()
                                : admittedClassLoader.get();
                        final Class<?> leftType = Class.forName(left.replace('/', '.'), false, classLoader);
                        final Class<?> rightType = Class.forName(right.replace('/', '.'), false, classLoader);
                        if (leftType.isAssignableFrom(rightType)) return left;
                        if (rightType.isAssignableFrom(leftType)) return right;
                        if (leftType.isInterface() || rightType.isInterface()) return "java/lang/Object";
                        Class<?> current = leftType;
                        do current = current.getSuperclass();
                        while (!current.isAssignableFrom(rightType));
                        return current.getName().replace('.', '/');
                    } catch (Throwable ignored) {
                        FatalErrors.rethrowIfFatal(ignored);
                        return "java/lang/Object";
                    }
                }
            };
            reader.accept(
                    new ClassVisitor(Opcodes.ASM9, writer) {
                        @Override
                        public MethodVisitor visitMethod(
                                final int access,
                                final String name,
                                final String descriptor,
                                final String signature,
                                final String[] exceptions) {
                            final MethodVisitor delegate =
                                    super.visitMethod(access, name, descriptor, signature, exceptions);

                            final boolean pointMoveHere = isPointMove
                                    && profile.pointMoveMethod().equals(name)
                                    && profile.pointMoveDescriptor().equals(descriptor);
                            final boolean dragTickHere = isDragTick
                                    && profile.dragTickMethod().equals(name)
                                    && profile.dragTickDescriptor().equals(descriptor);
                            final boolean stripMountHere = isStrip
                                    && profile.stripMountMethod().equals(name)
                                    && profile.stripMountDescriptor().equals(descriptor);
                            final boolean stripLayoutTail = isStrip
                                    && profile.stripLayoutMethod().equals(name)
                                    && profile.stripLayoutDescriptor().equals(descriptor);
                            final boolean greenTickHere = isGreenTick
                                    && profile.greenTickMethod().equals(name)
                                    && profile.greenTickDescriptor().equals(descriptor);
                            final boolean weightAddHere = isWeightWrite
                                    && profile.weightAddMethod().equals(name)
                                    && profile.weightAddDescriptor().equals(descriptor);
                            final boolean weightSetHere = isWeightWrite
                                    && profile.weightSetMethod().equals(name)
                                    && profile.weightSetDescriptor().equals(descriptor);
                            final boolean actionDispatchHere = isActionDispatch
                                    && profile.actionDispatchMethod().equals(name)
                                    && profile.actionDispatchDescriptor().equals(descriptor);
                            final boolean actionClaimHere = isActionDispatch
                                    && profile.actionClaimMethod().equals(name)
                                    && profile.actionClaimDescriptor().equals(descriptor);
                            final boolean inputIngressHere = isInputIngress
                                    && profile.inputIngressMethod().equals(name)
                                    && profile.inputIngressDescriptor().equals(descriptor);
                            if (!pointMoveHere
                                    && !dragTickHere
                                    && !stripMountHere
                                    && !stripLayoutTail
                                    && !greenTickHere
                                    && !weightAddHere
                                    && !weightSetHere
                                    && !actionDispatchHere
                                    && !actionClaimHere
                                    && !inputIngressHere) {
                                return delegate;
                            }
                            transformed[0] = true;
                            return new MethodVisitor(Opcodes.ASM9, delegate) {
                                @Override
                                public void visitCode() {
                                    super.visitCode();
                                    if (stripLayoutTail) {
                                        return;
                                    }
                                    visitVarInsn(Opcodes.ALOAD, 0);
                                    if (stripMountHere) {
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mountViewContextMenu",
                                                "(Ljava/lang/Object;)V",
                                                false);
                                    } else if (greenTickHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mirrorGreenTick",
                                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                                false);
                                    } else if (pointMoveHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitVarInsn(Opcodes.FLOAD, 2);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mirrorPointMove",
                                                "(Ljava/lang/Object;Ljava/lang/Object;F)V",
                                                false);
                                    } else if (dragTickHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitVarInsn(Opcodes.ALOAD, 2);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mirrorWarpDragMove",
                                                "(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V",
                                                false);
                                    } else if (weightAddHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitVarInsn(Opcodes.FLOAD, 2);
                                        visitVarInsn(Opcodes.ILOAD, 3);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mirrorWeightAdd",
                                                "(Ljava/lang/Object;Ljava/lang/Object;FZ)V",
                                                false);
                                    } else if (weightSetHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitVarInsn(Opcodes.FLOAD, 2);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mirrorWeightSet",
                                                "(Ljava/lang/Object;Ljava/lang/Object;F)V",
                                                false);
                                    } else if (actionDispatchHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "diagMouseAction",
                                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                                false);
                                    } else if (actionClaimHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitVarInsn(Opcodes.ALOAD, 2);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "diagSetAction",
                                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                                false);
                                    } else if (inputIngressHere) {
                                        visitVarInsn(Opcodes.ALOAD, 1);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "diagInputEvent",
                                                "(Ljava/lang/Object;Ljava/lang/Object;)V",
                                                false);
                                    } else {
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "mountViewContextMenu",
                                                "(Ljava/lang/Object;)V",
                                                false);
                                    }
                                }

                                @Override
                                public void visitInsn(final int opcode) {
                                    if (stripLayoutTail && opcode == Opcodes.RETURN) {
                                        visitVarInsn(Opcodes.ALOAD, 0);
                                        visitMethodInsn(
                                                Opcodes.INVOKESTATIC,
                                                BRIDGE,
                                                "positionStripButton",
                                                "(Ljava/lang/Object;)V",
                                                false);
                                    }
                                    super.visitInsn(opcode);
                                }
                            };
                        }
                    },
                    0);
            if (!transformed[0]) {
                outcome(Outcome.TARGET_UNCHANGED, "WARP_ALT_MIRROR_TARGET_UNCHANGED owner=" + className);
                return null;
            }
            final byte[] transformedBytes = writer.toByteArray();
            outcome(Outcome.TARGET_TRANSFORMED, "WARP_ALT_MIRROR_TARGET_TRANSFORMED owner=" + className);
            if (!active.get()) return null;
            transformedOwners.add(className);
            return transformedBytes;
        } catch (RuntimeException failure) {
            reject(Outcome.TRANSFORMATION_FAILED, "WARP_ALT_MIRROR_TRANSFORMATION_FAILED owner=" + className);
            return null;
        }
    }

    /** @return the exact host class loader admitted by a successful transformation, or null */
    public ClassLoader admittedClassLoader() {
        return admittedClassLoader.get();
    }

    /** @return the latest fail-closed transformation outcome */
    public Outcome outcome() {
        return outcome.get();
    }

    /** @return whether the exact target method was transformed successfully */
    public boolean targetTransformed() {
        return active.get() && outcome.get() == Outcome.TARGET_TRANSFORMED;
    }

    /** @return internal names of targets that received a successful transformation */
    public Set<String> transformedOwners() {
        return Set.copyOf(transformedOwners);
    }

    /** Permanently prevents new patches before removal or restoration begins. */
    public void deactivate() {
        active.set(false);
    }

    /**
     * Each expectation is enforced on its own: gating one behind another would let a target
     * pass a check its owner declared, which this fail-closed boundary must never allow.
     */
    private boolean admit(
            final ClassLoader loader, final ProtectionDomain protectionDomain, final String owner, final byte[] bytes) {
        if (loader == null && (expectedClassLoader != null || expectedArtifact != null)) {
            reject(Outcome.BOOTSTRAP_LOADER_REJECTED, "WARP_ALT_MIRROR_BOOTSTRAP_LOADER_REJECTED");
            return false;
        }
        if (expectedClassLoader != null && loader != expectedClassLoader) {
            reject(Outcome.LOADER_MISMATCH, "WARP_ALT_MIRROR_LOADER_MISMATCH");
            return false;
        }
        if (expectedArtifact != null && !expectedArtifact.equals(codeSourcePath(protectionDomain))) {
            reject(Outcome.ARTIFACT_MISMATCH, "WARP_ALT_MIRROR_ARTIFACT_MISMATCH");
            return false;
        }
        final ClassLoader existing = admittedClassLoader.get();
        if (existing != null && existing != loader) {
            reject(Outcome.SECOND_LOADER_REJECTED, "WARP_ALT_MIRROR_SECOND_LOADER_REJECTED");
            return false;
        }
        if ((expectedArtifact != null || !pinnedClassSha256.isEmpty())
                && !ReviewedHostContract.matchesClassBytes(pinnedClassSha256, owner, bytes)) {
            reject(Outcome.CLASS_BYTES_MISMATCH, "WARP_ALT_MIRROR_CLASS_BYTES_MISMATCH owner=" + owner);
            return false;
        }
        if (admittedClassLoader.compareAndSet(null, loader) || admittedClassLoader.get() == loader) return true;
        reject(Outcome.SECOND_LOADER_REJECTED, "WARP_ALT_MIRROR_SECOND_LOADER_REJECTED");
        return false;
    }

    /** Resolves the artifact path that defined the transformed class, or null. */
    private static Path codeSourcePath(final ProtectionDomain protectionDomain) {
        if (protectionDomain == null) return null;
        try {
            final CodeSource source = protectionDomain.getCodeSource();
            if (source == null || source.getLocation() == null) return null;
            return Path.of(source.getLocation().toURI());
        } catch (final URISyntaxException ignored) {
            return null;
        }
    }

    private void reject(final Outcome value, final String message) {
        outcome.set(value);
        diagnostic.accept(message);
    }

    private void outcome(final Outcome value, final String message) {
        outcome.set(value);
        diagnostic.accept(message);
    }

    /** Fail-closed transformation outcomes, surfaced for installation diagnostics. */
    public enum Outcome {
        NONE,
        TARGET_TRANSFORMED,
        TARGET_UNCHANGED,
        RETRANSFORM_REJECTED,
        BOOTSTRAP_LOADER_REJECTED,
        LOADER_MISMATCH,
        ARTIFACT_MISMATCH,
        CLASS_BYTES_MISMATCH,
        SECOND_LOADER_REJECTED,
        TRANSFORMATION_FAILED
    }
}
