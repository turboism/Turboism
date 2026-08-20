package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

@PreviewApi
public enum ScriptRunStatus {
    SUCCEEDED,
    FAILED,
    CANCELLED,
    REJECTED,
    TIMED_OUT
}
