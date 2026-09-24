package dev.turboism.adapter.cubism.textureatlas;

/**
 * Shared parser for the bounded admit-digest properties used by the atlas transformers.
 * Comma-separated lowercase 64-hex digests; malformed entries are dropped and the list is
 * capped so a runaway property cannot widen admission.
 */
public final class AtlasAdmitDigests {

    private AtlasAdmitDigests() {
    }

    /**
     * Parses a comma-separated list of lowercase 64-hex class digests. Entries are trimmed,
     * lowercased, and dropped when malformed; parsing stops once {@code maxEntries} valid
     * digests have been collected so a runaway property cannot widen admission.
     */
    public static java.util.Set<String> parse(final String raw, final int maxEntries) {
        final java.util.Set<String> parsed = new java.util.LinkedHashSet<>();
        if (raw == null) return parsed;
        for (final String part : raw.split(",")) {
            if (parsed.size() >= maxEntries) break;
            final String value = part.trim().toLowerCase(java.util.Locale.ROOT);
            if (value.length() != 64) continue;
            boolean hex = true;
            for (int i = 0; i < value.length(); i++) {
                final char c = value.charAt(i);
                if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                    hex = false;
                    break;
                }
            }
            if (hex) parsed.add(value);
        }
        return parsed;
    }
}
