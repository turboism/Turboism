package dev.turboism.sdk.failure;


import java.util.Objects;

/** Privacy-safe scalar context supplied to exception-advice handlers. */
public record FailureContext(
    String pluginId,
    String operationId,
    String eventType,
    String exceptionType
) {
    public FailureContext {
        pluginId = requireText(pluginId, "pluginId");
        operationId = requireText(operationId, "operationId");
        eventType = requireText(eventType, "eventType");
        exceptionType = requireText(exceptionType, "exceptionType");
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
