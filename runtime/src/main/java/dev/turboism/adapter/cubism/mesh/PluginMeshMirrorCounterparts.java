package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshDeletion;
import dev.turboism.sdk.cubism.mesh.MeshEditContribution;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterpartResolver;
import dev.turboism.sdk.cubism.mesh.MeshMirrorCounterparts;
import dev.turboism.sdk.plugin.Registration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-plugin view of mirror counterpart resolution: owns this plugin's resolver override and
 * resolves through the session-shared runtime.
 *
 * <p>Resolution itself belongs to the session ({@link RuntimeMeshMirrorCounterparts}); the
 * override slot belongs to the plugin, so one plugin's rule can never answer another plugin's
 * deletion. A second override from the same plugin is a programming error and refused, and
 * closing the returned registration restores the framework default.</p>
 */
final class PluginMeshMirrorCounterparts implements MeshMirrorCounterparts {

    private final RuntimeMeshMirrorCounterparts shared;
    private final AtomicReference<MeshMirrorCounterpartResolver> resolver = new AtomicReference<>();

    /**
     * @param shared the session-shared resolver the framework default runs in
     */
    PluginMeshMirrorCounterparts(final RuntimeMeshMirrorCounterparts shared) {
        this.shared = Objects.requireNonNull(shared, "shared");
    }

    @Override
    public MeshEditContribution mirrorOf(final MeshDeletion deletion) {
        return shared.mirrorOf(deletion, resolver.get());
    }

    @Override
    public Registration overrideResolver(final MeshMirrorCounterpartResolver replacement) {
        Objects.requireNonNull(replacement, "resolver");
        if (!resolver.compareAndSet(null, replacement)) {
            throw new IllegalStateException("this plugin already registered a mirror counterpart resolver");
        }
        return () -> resolver.compareAndSet(replacement, null);
    }
}
