package dev.turboism.core.runtime.sidecar;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Map;

/**
 * Immutable, serializable key/value map for sidecar work parameters.
 *
 * <p>Values are stored as strings so that only primitive, representable types cross
 * the sidecar boundary. No {@link Object} or raw maps are exposed.
 */
public record SidecarWorkParameters(
    @JsonProperty("values") Map<String, String> values
) {

    public SidecarWorkParameters {
        values = values == null ? Map.of() : Map.copyOf(values);
    }

    public SidecarWorkParameters() {
        this(Map.of());
    }

    @Override
    public Map<String, String> values() {
        return values;
    }
}
