package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.Map;
import java.util.Objects;

/** Request to execute one installed script. */
@PreviewApi
public record ScriptRunRequest(
    ScriptId scriptId,
    Map<String, String> arguments
) {

    public ScriptRunRequest {
        scriptId = Objects.requireNonNull(scriptId, "scriptId");
        arguments = Map.copyOf(Objects.requireNonNull(arguments, "arguments"));
        if (arguments.size() > 64) {
            throw new IllegalArgumentException("Script arguments are limited to 64 entries");
        }
        arguments.forEach((key, value) -> {
            if (key == null || key.isBlank() || key.length() > 128) {
                throw new IllegalArgumentException("Script argument keys must contain 1-128 characters");
            }
            if (value == null || value.length() > 4096) {
                throw new IllegalArgumentException("Script argument values are limited to 4096 characters");
            }
        });
    }

    public ScriptRunRequest(final ScriptId scriptId) {
        this(scriptId, Map.of());
    }
}
