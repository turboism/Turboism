package dev.turboism.sdk.script;


import java.util.Objects;

/** Runtime identity of one script execution. */
public record ScriptExecutionId(String value) {

    public ScriptExecutionId {
        value = Objects.requireNonNull(value, "value").trim();
        if (value.isEmpty() || value.length() > 128) {
            throw new IllegalArgumentException("Script execution id must contain 1-128 characters");
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
