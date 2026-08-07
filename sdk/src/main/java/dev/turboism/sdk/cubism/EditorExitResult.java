package dev.turboism.sdk.cubism;

import java.util.Objects;
import java.util.Optional;

/** Result of Cubism's exit request before process shutdown. */
public record EditorExitResult(
    EditorLifecycleSnapshot editor,
    boolean accepted,
    Optional<String> failureType
) {
    public EditorExitResult {
        editor = Objects.requireNonNull(editor, "editor");
        failureType = Objects.requireNonNull(failureType, "failureType");
        if (accepted && failureType.isPresent()) {
            throw new IllegalArgumentException("accepted exit cannot have failureType");
        }
    }
}
