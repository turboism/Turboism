package dev.turboism.sdk.script;

import dev.turboism.sdk.PreviewApi;

/** Script languages currently supported by Turboism. */
@PreviewApi
public enum ScriptLanguage {
    JAVASCRIPT("js");

    private final String id;

    ScriptLanguage(final String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static ScriptLanguage fromId(final String id) {
        for (ScriptLanguage language : values()) {
            if (language.id.equalsIgnoreCase(id)) {
                return language;
            }
        }
        throw new IllegalArgumentException("Unsupported script language: " + id);
    }
}
