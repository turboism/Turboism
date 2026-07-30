package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.plugin.Registration;

/** Preview registration seam for bounded Physics Settings workflows. */
@FunctionalInterface
public interface PhysicsEditorService {

    Registration contribute(PhysicsEditorContribution contribution);

    static PhysicsEditorService unavailable() {
        return contribution -> {
            throw new UnsupportedOperationException("physics editor service is not available");
        };
    }
}
