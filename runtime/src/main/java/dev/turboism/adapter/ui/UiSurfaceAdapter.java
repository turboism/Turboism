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
