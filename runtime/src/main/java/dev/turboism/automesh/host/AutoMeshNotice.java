package dev.turboism.automesh.host;

import java.util.Objects;

/**
 * One user-facing message the guarded Auto Mesh Generate entry reports.
 *
 * <p>The guard runs inside host bytecode, so it may not touch a dialog directly. It publishes an
 * immutable notice instead, and the composition layer renders it through the Editor's own status
 * surface.</p>
 *
 * @param severity how the host must render the notice
 * @param message the already-localized text
 */
public record AutoMeshNotice(Severity severity, String message) {

    public AutoMeshNotice {
        severity = Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(message, "message");
        if (message.isBlank()) {
            throw new IllegalArgumentException("message must not be blank");
        }
    }

    /** Severity of one notice, mapped onto the host status surface's own severity vocabulary. */
    public enum Severity {

        /** The run completed as the user asked. */
        INFO,

        /** The run was refused or rejected, so the user must act. */
        WARNING,

        /** The runtime could not complete the run. */
        ERROR
    }
}
