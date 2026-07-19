package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

public record PsdActionDescriptor(String id, String labelKey, Map<String, Boolean> booleanParameters) {
    public PsdActionDescriptor {
        id = Objects.requireNonNull(id, "id");
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
        booleanParameters = Collections.unmodifiableMap(new LinkedHashMap<>(booleanParameters));
    }
}
