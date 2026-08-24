package dev.turboism.sdk.script;


/** Terminal state of a submitted script execution. */
public enum ScriptRunStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    TIMED_OUT
}
