package dev.turboism.core.runtime.sidecar;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Immutable envelope for a single sidecar work unit.
 *
 * <p>All values are plain, JSON-serializable types. The payload is a JSON string
 * that must be validated by {@link SidecarEnvelopeValidator} before dispatch.
 */
public record SidecarEnvelope(
    @JsonProperty("pluginId") String pluginId,
    @JsonProperty("taskId") String taskId,
    @JsonProperty("taskType") String taskType,
    @JsonProperty("payload") String payload,
    @JsonProperty("declaredCapability") String declaredCapability,
    @JsonProperty("timestampUtc") String timestampUtc
) {

    public SidecarEnvelope {
        if (pluginId == null || pluginId.isBlank()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        if (taskType == null || taskType.isBlank()) {
            throw new IllegalArgumentException("taskType must not be blank");
        }
        if (payload == null) {
            throw new IllegalArgumentException("payload must not be null");
        }
        if (declaredCapability == null || declaredCapability.isBlank()) {
            throw new IllegalArgumentException("declaredCapability must not be blank");
        }
        if (timestampUtc == null || timestampUtc.isBlank()) {
            throw new IllegalArgumentException("timestampUtc must not be blank");
        }
    }
}
