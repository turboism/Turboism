package dev.turboism.mapping.draft;

/** Shared strict grammar for draft mapping target paths and semantic names. */
final class DraftMappingGrammar {
    private static final String PACK_PREFIX = "cubism-ref/mapping-packs/draft/";

    private DraftMappingGrammar() { }

    static boolean isDirectDraftPack(final String value) {
        if (value == null || !value.startsWith(PACK_PREFIX) || !value.endsWith(".json") || hasControl(value)) {
            return false;
        }
        final String file = value.substring(PACK_PREFIX.length());
        return !file.isBlank() && file.length() > ".json".length()
            && !file.contains("/") && !file.contains("\\") && !file.contains(":") && !file.contains("..");
    }

    static boolean isSafeSemanticName(final String value) {
        return value != null && !value.isBlank() && !hasControl(value)
            && !value.contains("[") && !value.contains("]")
            && !value.contains("/") && !value.contains("\\")
            && !value.contains(":") && !value.contains("..");
    }

    static boolean isSafeRelativePath(final String value) {
        return value != null && !value.isBlank() && !hasControl(value)
            && !value.startsWith("/") && !value.startsWith("\\")
            && !value.contains("\\") && !value.contains(":") && !value.contains("..");
    }

    private static boolean hasControl(final String value) {
        return value.codePoints().anyMatch(Character::isISOControl);
    }
}
