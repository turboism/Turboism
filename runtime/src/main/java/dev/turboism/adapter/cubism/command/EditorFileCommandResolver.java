package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;

/** Runtime-owned authorization seam that resolves an opaque SDK file grant. */
public interface EditorFileCommandResolver {
    /**
     * Resolves an opaque SDK file-grant request into the concrete command the adapter
     * executes.
     *
     * @param request the plugin-visible request
     * @return the resolved command, or {@code null} when the grant cannot be resolved and
     *         the caller must fail closed
     */
    ResolvedEditorFileCommand resolve(EditorFileCommandRequest request);

    /**
     * A resolver for when no authorization seam is attached.
     *
     * @return a resolver that answers {@code null} for every request
     */
    static EditorFileCommandResolver unavailable() {
        return request -> null;
    }
}
