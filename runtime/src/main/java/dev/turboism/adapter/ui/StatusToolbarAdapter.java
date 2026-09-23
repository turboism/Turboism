package dev.turboism.adapter.ui;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.StatusNotification;
import dev.turboism.sdk.ui.CanvasHintNotification;

import java.util.Objects;
import java.util.Optional;

/**
 * Adapter seam for command-style host UI status notifications.
 */
public interface StatusToolbarAdapter {

    /**
     * Posts a status-bar notification through the host.
     *
     * @param notification the notification to display
     * @return an available result carrying the registration that owns the posted
     *         notification, or an unavailable result when the capability cannot be served
     */
    AdapterResult<Registration> notifyStatus(StatusNotification notification);

    /**
     * Posts a canvas hint notification through the host.
     *
     * <p>The default reports {@link Capability#CANVAS_HINT} as unavailable; only hosts that
     * actually expose the hint surface override it.</p>
     *
     * @param notification the hint to display, non-null
     * @return an available result carrying the owning registration, or an unavailable result
     * @throws NullPointerException if {@code notification} is null
     */
    default AdapterResult<Registration> notifyCanvasHint(final CanvasHintNotification notification) {
        Objects.requireNonNull(notification, "notification");
        return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(
            Capability.CANVAS_HINT.id()
        ));
    }

    /** The host capabilities this adapter can be gated by. */
    enum Capability {
        STATUS_NOTIFY("ui.status.notify"),
        CANVAS_HINT("ui.canvas.hint");

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
         * @param notification the notification to display
         * @return the registration owning the posted notification
         */
        Registration notifyStatus(StatusNotification notification);

        /**
         * @param notification the hint to display
         * @return the registration owning the posted hint
         * @throws UnsupportedOperationException by default, for hosts without the hint surface
         */
        default Registration notifyCanvasHint(final CanvasHintNotification notification) {
            throw new UnsupportedOperationException("canvas hints are not available");
        }
    }

    /**
     * The outcome of one guarded adapter call: either the produced {@code value} or the
     * {@link SafeModeDiagnostic} explaining why it is absent.
     *
     * @param value the produced value, empty when the call was unavailable; never null
     * @param diagnostic why no value could be supplied, empty when the call succeeded; never null
     * @param <T> the produced value type
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
