package dev.turboism.sdk.cubism.mesh;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.plugin.Registration;

/** Enables plugin policy that mirrors host-initiated selected-point movement. */
@PreviewApi
public interface MeshMirrorMoveParticipation {

    Registration participate();
}
