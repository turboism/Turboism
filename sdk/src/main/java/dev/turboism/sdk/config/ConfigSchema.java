package dev.turboism.sdk.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public record ConfigSchema(
    String configId,
    String relativePath,
    int version,
    List<ConfigKey<?>> keys
) {
    public ConfigSchema {
        if (keys != null) {
            keys = Collections.unmodifiableList(new ArrayList<>(keys));
        }
    }
}
