package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.toolbar.MainToolbarRegistry;

import java.util.Objects;
import java.util.Optional;

public interface MainToolbarAdapter {

    AdapterResult<Registration> contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);

    enum Capability {
        MAIN_TOOLBAR_CONTRIBUTE("ui.main-toolbar.contribute");

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

        Registration contributeMainToolbar(MainToolbarRegistry.MainToolbarContribution contribution);
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
