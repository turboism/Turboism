package dev.turboism.sdk.cubism.physics;

import dev.turboism.sdk.plugin.Registration;

/** Preview registration seam for bounded Physics Settings workflows. */
@FunctionalInterface
public interface PhysicsEditorService {

    /**
     * Registers a Physics Settings contribution. Closing the returned {@link Registration}
     * withdraws the contribution.
     */
    Registration contribute(PhysicsEditorContribution contribution);

    /** Safe-mode instance: every contribution is refused (fail closed). */
    static PhysicsEditorService unavailable() {
        return contribution -> {
            throw new UnsupportedOperationException("physics editor service is not available");
        };
    }
}
