package dev.turboism.mapping.draft;

import java.util.Locale;
import java.util.Set;

/** Compliance gate for selectors that could target licensing or security controls. */
final class ForbiddenSelectorTerms {
    private static final Set<String> TERMS = Set.of(
        "license", "licence", "trial", "authentication", "authorization", "security",
        "watermark", "bypass", "network-check", "network_check"
    );

    private ForbiddenSelectorTerms() { }

    static boolean containsForbidden(final String... values) {
        for (String value : values) {
            if (value == null) continue;
            final String normalized = value.toLowerCase(Locale.ROOT);
            for (String term : TERMS) {
                if (normalized.contains(term)) return true;
            }
        }
        return false;
    }

    static void requireAllowed(final String... values) {
        if (containsForbidden(values)) {
            throw new DraftMappingException(
                "FORBIDDEN_SELECTOR_TERM",
                "mapping update recipe targets a prohibited licensing or security term"
            );
        }
    }
}
