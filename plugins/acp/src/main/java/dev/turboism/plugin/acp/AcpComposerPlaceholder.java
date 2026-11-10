package dev.turboism.plugin.acp;

import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.ArrayList;
import java.util.List;
import java.util.random.RandomGenerator;

/**
 * Picks the hint shown while the composer is empty.
 *
 * <p>The catalog owns the example wording, so translators revise prompts without code edits. The
 * injected random source keeps the pick reproducible under test, and the generic invitation covers
 * catalogs that translate fewer examples than {@link #SUGGESTION_KEYS}.
 */
final class AcpComposerPlaceholder {

    static final String GENERIC_KEY = "composer.placeholder";
    static final List<String> SUGGESTION_KEYS = List.of(
            "composer.suggestion.mask-import",
            "composer.suggestion.folder-colors",
            "composer.suggestion.missing-display");

    private AcpComposerPlaceholder() {}

    static String choose(final PluginLocalization localization, final RandomGenerator random) {
        final List<String> suggestions = suggestions(localization);
        if (suggestions.isEmpty()) {
            return localization.text(GENERIC_KEY, "");
        }
        return suggestions.get(random.nextInt(suggestions.size()));
    }

    private static List<String> suggestions(final PluginLocalization localization) {
        final List<String> translated = new ArrayList<>(SUGGESTION_KEYS.size());
        for (final String key : SUGGESTION_KEYS) {
            final String suggestion = localization.text(key, "");
            if (!suggestion.isBlank()) translated.add(suggestion);
        }
        return translated;
    }
}
