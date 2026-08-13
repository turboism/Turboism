package dev.turboism.adapter.cubism.command;

import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;

/** Runtime-owned authorization seam that resolves an opaque SDK file grant. */
public interface EditorFileCommandResolver {
    ResolvedEditorFileCommand resolve(EditorFileCommandRequest request);

    static EditorFileCommandResolver unavailable() {
        return request -> null;
    }
}
