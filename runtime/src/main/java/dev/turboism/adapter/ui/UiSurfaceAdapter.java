package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.EmbeddedPanelContribution;
import dev.turboism.sdk.ui.FileChooserRequest;
import dev.turboism.sdk.ui.OverlayContribution;

import java.util.Objects;
import java.util.Optional;

/** Adapter seam for non-toolbar host UI surfaces. */
public interface UiSurfaceAdapter {

    AdapterResult<Registration> contributeOverlay(OverlayContribution contribution);

    AdapterResult<Registration> openDialog(DialogRequest request);

    AdapterResult<Boolean> confirmDialog(DialogRequest request);

    AdapterResult<Registration> contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

    AdapterResult<Optional<String>> requestFile(FileChooserRequest request);

    enum Capability {
        OVERLAY_CONTRIBUTE("ui.overlay.contribute"),
        DIALOG_CONTRIBUTE("ui.dialog.contribute"),
        PANEL_CONTRIBUTE("ui.panel.contribute"),
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

        Registration contributeOverlay(OverlayContribution contribution);

        Registration openDialog(DialogRequest request);

        boolean confirmDialog(DialogRequest request);

        Registration contributeEmbeddedPanel(EmbeddedPanelContribution contribution);

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
