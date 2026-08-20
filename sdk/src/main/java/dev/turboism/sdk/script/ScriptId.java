package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

import java.util.Objects;
import java.util.regex.Pattern;

/** Stable identifier of one installed Turboism script. */
@PreviewApi
public record ScriptId(String value) {

    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");

    public ScriptId {
        value = Objects.requireNonNull(value, "value").trim();
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid script id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
