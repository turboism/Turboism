package dev.turboism.internal.core;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Runtime-owned update-notes tracking consumed by the framework shell.
 *
 * <p>Detects when the installation starts on a newer framework version than the last acknowledged
 * release and supplies localized release notes until the user dismisses the window.</p>
 */
public interface UpdateNotesService {

    /**
     * Localized release-notes payload for a newly installed framework version.
     *
     * @param version current installed framework version
     * @param previousVersion previously acknowledged version when known
     * @param localizedNotes Markdown release notes keyed by language or locale tag
     */
    record ReleaseNotes(String version, Optional<String> previousVersion, Map<String, String> localizedNotes) {
        /** Validates and freezes release-notes components. */
        public ReleaseNotes {
            version = Objects.requireNonNull(version, "version");
            previousVersion = Objects.requireNonNull(previousVersion, "previousVersion");
            localizedNotes = Map.copyOf(Objects.requireNonNull(localizedNotes, "localizedNotes"));
            if (version.isBlank()) {
                throw new IllegalArgumentException("version must not be blank");
            }
        }

        /**
         * Returns the release notes best matching {@code locale}, falling back to English or blank.
         *
         * @param locale target UI locale
         * @return Markdown release notes, or an empty string when unavailable
         */
        public String notesFor(final Locale locale) {
            Objects.requireNonNull(locale, "locale");
            final String tag = locale.toLanguageTag();
            if (!tag.isEmpty() && localizedNotes.containsKey(tag)) {
                return localizedNotes.get(tag);
            }
            final String language = locale.getLanguage();
            if (!language.isEmpty() && localizedNotes.containsKey(language)) {
                return localizedNotes.get(language);
            }
            return localizedNotes.getOrDefault("en", "");
        }
    }

    /**
     * Checks whether the shell should present release notes for a newly updated version.
     *
     * @return release notes when an unacknowledged upgrade is active, otherwise empty
     */
    Optional<ReleaseNotes> pendingReleaseNotes();

    /**
     * Records user acknowledgement for {@code version} so subsequent starts stay silent.
     *
     * @param version acknowledged framework version
     */
    void markReleaseNotesSeen(String version);

    /**
     * Returns an inert sentinel for runtimes without persistent update-notes tracking.
     *
     * @return unavailable update-notes service
     */
    static UpdateNotesService unavailable() {
        return Unavailable.INSTANCE;
    }

    /** Inert sentinel returned by {@link #unavailable()}. */
    enum Unavailable implements UpdateNotesService {
        INSTANCE;

        @Override
        public Optional<ReleaseNotes> pendingReleaseNotes() {
            return Optional.empty();
        }

        @Override
        public void markReleaseNotesSeen(final String version) {}
    }
}
