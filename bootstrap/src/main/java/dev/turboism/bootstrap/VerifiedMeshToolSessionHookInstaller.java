package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshEditorLifecycleTransformer;
import dev.turboism.adapter.cubism.mesh.MeshToolSessionHostProfile;
import dev.turboism.adapter.cubism.mesh.NativeMeshToolSessionBridge;
import dev.turboism.adapter.cubism.startup.StartupSuppressionInstaller.AttachmentMode;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * Owns the exact-artifact, premain-only mesh-tool session lifecycle hook and its bridge binding.
 *
 * <p>Registration and binding are one atomic step, in this order: the bridge is installed first so
 * transformed bytecode can never execute unbound, then the transformer is registered, and finally
 * every already-loaded exact owner is retransformed so a host that loaded its mesh-editor class
 * before this hook still receives the lifecycle callbacks. Teardown is the mirror image: the
 * transformer is removed, the loaded owners are retransformed back to their original bytecode, and
 * the bridge is revoked last.</p>
 */
final class VerifiedMeshToolSessionHookInstaller implements AutoCloseable {
    static final String TARGET_ALREADY_LOADED_TWICE = "MESH_TOOL_SESSION_TARGET_DUPLICATE";
    static final String TARGET_LOADER_MISMATCH = "MESH_TOOL_SESSION_LOADER_MISMATCH";
    static final String TARGET_UNMODIFIABLE = "MESH_TOOL_SESSION_TARGET_UNMODIFIABLE";
    static final String TARGET_ENUMERATION_FAILED = "MESH_TOOL_SESSION_OWNER_ENUMERATION_FAILED";
    static final String TRANSFORM_DELTA_MISMATCH = "MESH_TOOL_SESSION_TRANSFORM_DELTA_MISMATCH";

    private final Instrumentation instrumentation;
    private final AttachmentMode attachmentMode;
    private final ClassLoader hostClassLoader;
    private final MeshToolSessionHostProfile profile;
    private final Runnable bindBridge;
    private final Runnable unbindBridge;
    private final MeshEditorLifecycleTransformer transformer;
    private boolean restorePending;
    private boolean installed;
    private boolean transformerAdded;
    private boolean bridgeInstalled;

    VerifiedMeshToolSessionHookInstaller(
            final Instrumentation instrumentation,
            final AttachmentMode attachmentMode,
            final ClassLoader hostClassLoader,
            final MeshToolSessionHostProfile profile,
            final Path expectedArtifact,
            final Runnable bindBridge,
            final Runnable unbindBridge) {
        this.instrumentation = Objects.requireNonNull(instrumentation, "instrumentation");
        this.attachmentMode = Objects.requireNonNull(attachmentMode, "attachmentMode");
        this.hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        this.profile = Objects.requireNonNull(profile, "profile");
        this.bindBridge = Objects.requireNonNull(bindBridge, "bindBridge");
        this.unbindBridge = Objects.requireNonNull(unbindBridge, "unbindBridge");
        this.transformer = new MeshEditorLifecycleTransformer(
                profile,
                hostClassLoader,
                Objects.requireNonNull(expectedArtifact, "expectedArtifact"),
                message -> dev.turboism.runtime.log.RuntimeDiagnostics.debug("mesh-tool-session", message));
    }

    /** Returns the exact reviewed owner internal name this hook targets. */
    String targetClassName() {
        return transformer.targetClassName();
    }

    /**
     * Installs the bridge and transformer, then retransforms every already-loaded exact owner.
     *
     * <p>An exact owner that is already loaded is expected, not a failure: Cubism loads its
     * mesh-editor class during its own startup, so retransformation is the only way to observe the
     * lifecycle of a host that got there first.</p>
     */
    synchronized void install() {
        if (installed) return;
        if (transformerAdded || restorePending || bridgeInstalled) {
            throw new IllegalStateException("Mesh-tool session cleanup must finish before reinstalling.");
        }
        if (attachmentMode != AttachmentMode.PREMAIN) {
            throw new IllegalStateException("Mesh-tool session lifecycle hook is premain-only.");
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            throw new IllegalStateException("Mesh-tool session retransformation is unavailable.");
        }
        requireBridgeLinkage();
        final List<Class<?>> targets = preflight();
        try {
            bindBridge.run();
            bridgeInstalled = true;
            transformer.resetTransformedCount();
            instrumentation.addTransformer(transformer, true);
            transformerAdded = true;
            for (final Class<?> target : targets) {
                try {
                    instrumentation.retransformClasses(target);
                } catch (Throwable failure) {
                    dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
                    throw new IllegalStateException("mesh-editor target retransform failed", failure);
                }
            }
            if (transformer.transformedCount() != targets.size()) {
                throw new IllegalStateException(TRANSFORM_DELTA_MISMATCH + ": expected " + targets.size()
                        + " transformed owner(s), got " + transformer.transformedCount());
            }
            installed = true;
        } catch (RuntimeException | Error failure) {
            try {
                closeLocked();
            } catch (Throwable cleanup) {
                dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(cleanup);
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    /** Returns the latest fail-closed transformation outcome for this hook. */
    MeshEditorLifecycleTransformer.Outcome transformOutcome() {
        return transformer.outcome();
    }

    @Override
    public synchronized void close() {
        closeLocked();
    }

    private void closeLocked() {
        installed = false;
        if (transformerAdded) {
            removeTransformer();
            transformerAdded = false;
            restorePending = true;
        }
        if (restorePending) {
            restoreLoadedOwners();
            restorePending = false;
        }
        if (bridgeInstalled) {
            unbindBridge.run();
            bridgeInstalled = false;
        }
    }

    private List<Class<?>> preflight() {
        final Class<?>[] loaded;
        try {
            loaded = instrumentation.getAllLoadedClasses();
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException(TARGET_ENUMERATION_FAILED, failure);
        }
        final var targetNames = transformer.targetClassNames().stream()
                .map(name -> name.replace('/', '.'))
                .toList();
        final var seen = new HashSet<String>();
        final List<Class<?>> exact = new ArrayList<>();
        for (final Class<?> type : loaded) {
            if (type == null || !targetNames.contains(type.getName())) continue;
            final String targetName = type.getName();
            if (type.getClassLoader() != hostClassLoader) {
                throw new IllegalStateException(TARGET_LOADER_MISMATCH + ": " + targetName);
            }
            if (!instrumentation.isModifiableClass(type)) {
                throw new IllegalStateException(TARGET_UNMODIFIABLE + ": " + targetName);
            }
            if (!seen.add(targetName)) {
                throw new IllegalStateException(TARGET_ALREADY_LOADED_TWICE + ": " + targetName);
            }
            exact.add(type);
        }
        return List.copyOf(exact);
    }

    private void requireBridgeLinkage() {
        try {
            if (Class.forName(NativeMeshToolSessionBridge.class.getName(), false, hostClassLoader)
                    != NativeMeshToolSessionBridge.class) {
                throw new IllegalStateException("Mesh-tool session bridge class identity mismatch.");
            }
        } catch (ClassNotFoundException | LinkageError failure) {
            throw new IllegalStateException("Mesh-tool session bridge is not visible to the host loader.", failure);
        }
    }

    private void removeTransformer() {
        final boolean removed;
        try {
            removed = instrumentation.removeTransformer(transformer);
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("mesh-editor transformer removal failed", failure);
        }
        if (!removed) throw new IllegalStateException("mesh-editor transformer removal failed");
    }

    /**
     * Retransforms the loaded owners after transformer removal so the host keeps its original
     * bytecode instead of retaining injected lifecycle calls for the rest of the process.
     */
    private void restoreLoadedOwners() {
        try {
            for (final Class<?> type : instrumentation.getAllLoadedClasses()) {
                if (type == null
                        || !transformer
                                .targetClassNames()
                                .contains(type.getName().replace('.', '/'))) continue;
                if (type.getClassLoader() != hostClassLoader) continue;
                if (!instrumentation.isModifiableClass(type)) throw new IllegalStateException(TARGET_UNMODIFIABLE);
                instrumentation.retransformClasses(type);
            }
        } catch (Throwable failure) {
            dev.turboism.core.runtime.work.FatalErrors.rethrowIfFatal(failure);
            throw new IllegalStateException("mesh-editor bytecode restore failed", failure);
        }
    }
}
