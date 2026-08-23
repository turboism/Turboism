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

    AdapterResult<Optional<ThemeStatusSnapshot>> themeStatus();

    interface HostOperations {
        String hostVersion();

        boolean supportsThemeStatusRead();

        Optional<ThemeStatusSnapshot> themeStatus();
    }

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
