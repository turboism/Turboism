package dev.turboism.adapter.ui;

import dev.turboism.sdk.theme.ThemeStatusSnapshot;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapter seam for theme status reads.
 *
 * <p>Kept separate from {@link StatusToolbarAdapter} so Cubism-read theme status
 * does not depend on UI status/palette host operations.</p>
 */
public interface ThemeStatusAdapter {

    String CAPABILITY_ID = "cubism.theme.status.read";

    /**
     * @return an available result carrying the observed theme status (empty when the host
     *         has none to report), or an unavailable result whose diagnostic explains the
     *         failed read; never null
     */
    AdapterResult<Optional<ThemeStatusSnapshot>> themeStatus();

    /** The raw host call surface this adapter guards. */
    interface HostOperations {
        /**
         * @return the host application version string used for the reviewed-version check
         */
        String hostVersion();

        /**
         * @return {@code true} when this host exposes the theme-status read surface
         */
        boolean supportsThemeStatusRead();

        /**
         * @return the theme status observed on the host; empty when none is reported
         */
        Optional<ThemeStatusSnapshot> themeStatus();
    }

    /**
     * The outcome of one guarded adapter read: either the observed {@code value} or the
     * {@link SafeModeDiagnostic} explaining why it is absent.
     *
     * @param value the observed value, empty when the read was unavailable; never null
     * @param diagnostic why no value could be supplied, empty when the read succeeded; never null
     * @param <T> the observed value type
     */
    record AdapterResult<T>(
        Optional<T> value,
        Optional<SafeModeDiagnostic> diagnostic
    ) {
        public AdapterResult {
            value = Objects.requireNonNull(value, "value");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        }

        /**
         * @param <T> carried value type
         * @param value the host-produced result, never null
         * @return a result carrying the value with no diagnostic
         */
        public static <T> AdapterResult<T> available(final T value) {
            return new AdapterResult<>(Optional.of(value), Optional.empty());
        }

        /**
         * @param <T> carried value type
         * @param diagnostic why the capability degraded to safe mode
         * @return a result carrying only the diagnostic and no value
         */
        public static <T> AdapterResult<T> unavailable(final SafeModeDiagnostic diagnostic) {
            return new AdapterResult<>(Optional.empty(), Optional.of(diagnostic));
        }

        /** @return true only when a value is present and no diagnostic was recorded. */
        public boolean isAvailable() {
            return value.isPresent() && diagnostic.isEmpty();
        }
    }
}
