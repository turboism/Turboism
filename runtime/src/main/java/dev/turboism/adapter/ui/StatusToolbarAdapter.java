package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.toolbar.PaletteToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapter seam for UI status notifications and palette toolbar contributions.
 *
 * <p>Theme status reads live on {@link ThemeStatusAdapter} so Cubism-read does not
 * depend on UI toolbar host operations.</p>
 */
public interface StatusToolbarAdapter {

    AdapterResult<Registration> notifyStatus(StatusNotification notification);

    AdapterResult<Registration> contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);

    enum Capability {
        STATUS_NOTIFY("ui.status.notify"),
        PALETTE_TOOLBAR_CONTRIBUTE("ui.palette-toolbar.contribute");

        private final String id;

        Capability(final String id) {
            this.id = id;
        }

        public String id() {
            return id;
        }
    }

    interface HostOperations {
        String hostVersion();

        boolean supports(Capability capability);

        Registration notifyStatus(StatusNotification notification);

        Registration contributePaletteToolbar(PaletteToolbarRegistry.PaletteToolbarContribution contribution);
    }

    record AdapterResult<T>(
        Optional<T> value,
        Optional<SafeModeDiagnostic> diagnostic
    ) {
        public AdapterResult {
            value = Objects.requireNonNull(value, "value");
            diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        }

        public static <T> AdapterResult<T> available(final T value) {
            return new AdapterResult<>(Optional.of(value), Optional.empty());
        }

        public static <T> AdapterResult<T> unavailable(final SafeModeDiagnostic diagnostic) {
            return new AdapterResult<>(Optional.empty(), Optional.of(diagnostic));
        }

        public boolean isAvailable() {
            return value.isPresent() && diagnostic.isEmpty();
        }
    }
}
