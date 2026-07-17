package dev.turboism.config;

import dev.turboism.sdk.config.ConfigErrorCode;
import dev.turboism.sdk.config.ConfigValueSource;

import java.util.Map;

/** Typed config document state produced by loading and migration. */
final class LoadedDocument {
    final long revision;
    final Map<String, String> values;
    final ConfigValueSource source;
    final ConfigErrorCode error;

    private LoadedDocument(
        final long revision,
        final Map<String, String> values,
        final ConfigValueSource source,
        final ConfigErrorCode error
    ) {
        this.revision = revision;
        this.values = values;
        this.source = source;
        this.error = error;
    }

    static LoadedDocument success(final long revision, final Map<String, String> values) {
        return new LoadedDocument(revision, Map.copyOf(values), null, null);
    }

    static LoadedDocument failure(
        final long revision,
        final ConfigValueSource source,
        final ConfigErrorCode error
    ) {
        return new LoadedDocument(revision, Map.of(), source, error);
    }
}
