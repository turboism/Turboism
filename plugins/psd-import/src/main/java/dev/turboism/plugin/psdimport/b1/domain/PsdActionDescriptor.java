package dev.turboism.plugin.psdimport.b1.domain;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Static description of one PSD-import action offered to the host: its stable id, the
 * localization key for its label, and the boolean parameters it accepts with their defaults.
 *
 * <p>Purely declarative — it carries no behaviour and does not itself execute anything. The
 * parameter map is defensively copied and wrapped unmodifiable by the compact constructor, so the
 * descriptor is immutable and iterates in the order the caller supplied.
 *
 * @param id stable action identifier the host uses to invoke the action, never {@code null}
 * @param labelKey localization key for the action's user-visible label, never {@code null};
 *     resolution is the caller's job, this record does not translate it
 * @param booleanParameters accepted parameter names mapped to their default values, defensively
 *     copied and exposed unmodifiable in insertion order; must not be {@code null}
 */
public record PsdActionDescriptor(String id, String labelKey, Map<String, Boolean> booleanParameters) {
    public PsdActionDescriptor {
        id = Objects.requireNonNull(id, "id");
        labelKey = Objects.requireNonNull(labelKey, "labelKey");
        booleanParameters = Collections.unmodifiableMap(new LinkedHashMap<>(booleanParameters));
    }
}
