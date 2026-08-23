package dev.turboism.sdk.script;


/** Script languages currently supported by Turboism. */
public enum ScriptLanguage {
    JAVASCRIPT("js");

    private final String id;

    ScriptLanguage(final String id) {
        this.id = id;
    }

    /** @return the stable wire identifier for this language */
    public String id() {
        return id;
    }

    /**
     * Resolves a language from its case-insensitive wire identifier.
     *
     * @param id wire identifier
     * @return the matching language
     * @throws IllegalArgumentException when the identifier is unsupported
     */
    public static ScriptLanguage fromId(final String id) {
        for (ScriptLanguage language : values()) {
            if (language.id.equalsIgnoreCase(id)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unsupported script language: " + id);
    }
}
