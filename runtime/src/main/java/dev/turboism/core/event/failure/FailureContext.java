package dev.turboism.core.event.failure;

import dev.turboism.internal.Requirements;

/** Privacy-safe scalar context supplied to exception-advice handlers. */
public record FailureContext(String pluginId, String operationId, String eventType, String exceptionType) {
    public FailureContext {
        pluginId = Requirements.text(pluginId, "pluginId");
        operationId = Requirements.text(operationId, "operationId");
        eventType = Requirements.text(eventType, "eventType");
        exceptionType = Requirements.text(exceptionType, "exceptionType");
    }
}
