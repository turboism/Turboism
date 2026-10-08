package dev.turboism.sdk.script;

import dev.turboism.sdk.Incubating;

/** Terminal state of a submitted script execution. */
@Incubating
public enum ScriptRunStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    TIMED_OUT
}
