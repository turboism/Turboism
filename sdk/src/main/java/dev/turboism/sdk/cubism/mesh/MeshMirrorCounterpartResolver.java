package dev.turboism.sdk.cubism.mesh;


import java.util.Optional;

/**
 * Replaces the framework's counterpart rule for one plugin's contributions.
 *
 * <p>This is the expensive path, and deliberately so: registering it makes the runtime
 * materialise a {@link MeshSnapshot} and call this method once per source point, synchronously,
 * on the host thread, inside a host mutation. The framework default in
 * {@link MeshMirrorCounterparts#mirrorOf} does none of that. Prefer the default unless the rule
 * genuinely has to differ.</p>
 */
@FunctionalInterface
public interface MeshMirrorCounterpartResolver {

    Optional<MeshPointRef> counterpart(MeshPointRef source, MeshSnapshot mesh, MirrorAxisState axis);
}
