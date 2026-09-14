package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.DialogRequest;
import dev.turboism.sdk.ui.FileChooserRequest;

import java.util.Objects;
import java.util.Optional;

/** Adapter seam for command-style host UI operations. */
public interface UiSurfaceAdapter {

    /**
     * Opens a plugin-contributed dialog on the host.
     *
     * @param request the dialog to open
     * @return an available result carrying the registration that owns the open dialog, or
     *         an unavailable result when the capability cannot be served
     */
    AdapterResult<Registration> openDialog(DialogRequest request);

    /**
     * Shows a confirmation dialog and reports the user's answer.
     *
     * @param request the dialog to show
     * @return an available result carrying whether the user confirmed, or an unavailable
     *         result when the capability cannot be served
     */
    AdapterResult<Boolean> confirmDialog(DialogRequest request);

    /**
     * Asks the host file chooser for a user-granted file.
     *
     * @param request the chooser request
     * @return an available result carrying the chosen path (empty when the user canceled),
     *         or an unavailable result when the capability cannot be served
     */
    AdapterResult<Optional<String>> requestFile(FileChooserRequest request);

    /** The host capabilities this adapter can be gated by. */
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

    /** The raw host call surface this adapter guards. */
    interface HostOperations {
        /**
         * @return the host application version string used for the reviewed-version check
         */
        String hostVersion();

        /**
         * @param capability the capability being probed
         * @return {@code true} when this host exposes it
         */
        boolean supports(Capability capability);

        /**
         * @param request the dialog to open
         * @return the registration owning the open dialog
         */
        Registration openDialog(DialogRequest request);

        /**
         * @param request the dialog to show
         * @return whether the user confirmed
         */
        boolean confirmDialog(DialogRequest request);

        /**
         * @param request the chooser request
         * @return the chosen path; empty when the user canceled
         */
        Optional<String> requestFile(FileChooserRequest request);
    }

    /**
     * The outcome of one guarded adapter call: either the produced {@code value} or the
     * {@link SafeModeDiagnostic} explaining why it is absent.
     *
     * @param value the produced value, empty when the call was unavailable; never null
     * @param diagnostic why no value could be supplied, empty when the call succeeded; never null
     * @param <T> the produced value type
     */
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
