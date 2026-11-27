package dev.turboism.bootstrap;

import dev.turboism.adapter.cubism.mesh.MeshEditorLifecycleTransformer;
import dev.turboism.adapter.cubism.mesh.MeshToolSessionHostProfile;
import dev.turboism.adapter.cubism.mesh.NativeMeshToolSessionBridge;
import dev.turboism.adapter.cubism.optimization.startup.StartupSuppressionInstaller.AttachmentMode;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Owns the exact-artifact, premain-only mesh-tool session lifecycle hook and its bridge binding.
 *
 * <p>Registration and binding share {@link PremainLifecycleHookSession}: the bridge installs first
 * so transformed bytecode never runs unbound, the transformer registers and retransforms already-loaded
 * owners, and teardown removes the transformer, restores original bytecode, and unbinds the bridge.</p>
 */
final class VerifiedMeshToolSessionHookInstaller implements AutoCloseable {
    static final String TARGET_ALREADY_LOADED_TWICE = "MESH_TOOL_SESSION_TARGET_DUPLICATE";
    static final String TARGET_LOADER_MISMATCH = "MESH_TOOL_SESSION_LOADER_MISMATCH";
    static final String TARGET_UNMODIFIABLE = "MESH_TOOL_SESSION_TARGET_UNMODIFIABLE";
    static final String TARGET_ENUMERATION_FAILED = "MESH_TOOL_SESSION_OWNER_ENUMERATION_FAILED";
    static final String TRANSFORM_DELTA_MISMATCH = "MESH_TOOL_SESSION_TRANSFORM_DELTA_MISMATCH";

    private static final PremainLifecycleHookSession.Labels LABELS = new PremainLifecycleHookSession.Labels(
            "Mesh-tool session",
            "mesh-editor",
            NativeMeshToolSessionBridge.class,
            TARGET_ALREADY_LOADED_TWICE,
            TARGET_LOADER_MISMATCH,
            TARGET_UNMODIFIABLE,
            TARGET_ENUMERATION_FAILED,
            TRANSFORM_DELTA_MISMATCH);

    private final MeshEditorLifecycleTransformer transformer;
    private final PremainLifecycleHookSession session;

    VerifiedMeshToolSessionHookInstaller(
            final Instrumentation instrumentation,
            final AttachmentMode attachmentMode,
            final ClassLoader hostClassLoader,
            final MeshToolSessionHostProfile profile,
            final Path expectedArtifact,
            final Runnable bindBridge,
            final Runnable unbindBridge) {
        this(
                instrumentation,
                attachmentMode,
                hostClassLoader,
                profile,
                expectedArtifact,
                bindBridge,
                unbindBridge,
                null);
    }

    /**
     * @param loadedClasses the loaded-class snapshot of this install phase, or {@code null} to
     *     enumerate the loaded classes on install
     */
    VerifiedMeshToolSessionHookInstaller(
            final Instrumentation instrumentation,
            final AttachmentMode attachmentMode,
            final ClassLoader hostClassLoader,
            final MeshToolSessionHostProfile profile,
            final Path expectedArtifact,
            final Runnable bindBridge,
            final Runnable unbindBridge,
            final LoadedClassIndex loadedClasses) {
        this.transformer = new MeshEditorLifecycleTransformer(
                Objects.requireNonNull(profile, "profile"),
                Objects.requireNonNull(hostClassLoader, "hostClassLoader"),
                Objects.requireNonNull(expectedArtifact, "expectedArtifact"),
                message -> dev.turboism.runtime.log.RuntimeDiagnostics.debug("mesh-tool-session", message));
        this.session = new PremainLifecycleHookSession(
                instrumentation,
                attachmentMode,
                hostClassLoader,
                transformer,
                transformer.targetClassNames(),
                transformer::resetTransformedCount,
                transformer::transformedCount,
                bindBridge,
                unbindBridge,
                loadedClasses,
                LABELS);
    }

    /** Returns the exact reviewed owner internal name this hook targets. */
    String targetClassName() {
        return transformer.targetClassName();
    }

    /** Installs the bridge and transformer, then retransforms every already-loaded exact owner. */
    void install() {
        session.install();
    }

    /** Returns the latest fail-closed transformation outcome for this hook. */
    MeshEditorLifecycleTransformer.Outcome transformOutcome() {
        return transformer.outcome();
    }

    @Override
    public void close() {
        session.close();
    }
}
