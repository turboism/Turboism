package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;

import java.util.Objects;
import java.util.Optional;

/** Adapter seam for command-style host UI operations. */
public interface UiSurfaceAdapter {

    AdapterResult<Registration> openDialog(DialogRequest request);

    AdapterResult<Boolean> confirmDialog(DialogRequest request);

    AdapterResult<Optional<String>> requestFile(FileChooserRequest request);

    enum Capability {
        DIALOG_CONTRIBUTE("ui.dialog.contribute"),
        FILE_CHOOSER_REQUEST("ui.file-chooser.request");

        private final String id;

        Capability(final String id) {
            this.id = id;
        }

        /** @return the capability ID this constant is gated by, as declared in plugin manifests. */
        public String id() {
            return id;
        }
    }

    interface HostOperations {
        String hostVersion();

        boolean supports(Capability capability);

        Registration openDialog(DialogRequest request);

        boolean confirmDialog(DialogRequest request);

        Optional<String> requestFile(FileChooserRequest request);
    }

    record AdapterResult<T>(Optional<T> value, Optional<SafeModeDiagnostic> diagnostic) {
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
