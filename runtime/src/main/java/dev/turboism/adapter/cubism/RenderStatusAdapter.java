package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.HostUiVersionCheck;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter seam for read-only render status.
 * Used by render-status and overlay consumers.
 */
public interface RenderStatusAdapter {

    String CAPABILITY_ID = "cubism.render.status.read";
    String ADAPTER_SLICE_ID = "adapter.render-status.readonly";

    AdapterResult<Optional<RenderStatusSnapshot>> renderStatus();

    interface HostOperations {
        String hostVersion();

        boolean supportsRenderStatusRead();

        Optional<RenderStatusSnapshot> renderStatus();
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
         * A result carrying a value the host actually supplied.
         *
         * @param value the observed value; must be non-null, since it is wrapped with
         *              {@link Optional#of}
         * @param <T>   the observed value type
         * @return an available result with no diagnostic
         * @throws NullPointerException if {@code value} is null
         */
        public static <T> AdapterResult<T> available(final T value) {
            return new AdapterResult<>(Optional.of(value), Optional.empty());
        }

        /**
         * A result carrying no value, only the reason the host could not be read.
         *
         * <p>This is how an unsupported host version, a missing capability, or a failed host call is
         * reported; the adapter does not throw for those.
         *
         * @param diagnostic why the value is unavailable, non-null
         * @param <T>        the value type that would have been observed
         * @return an unavailable result
         */
        public static <T> AdapterResult<T> unavailable(final SafeModeDiagnostic diagnostic) {
            return new AdapterResult<>(Optional.empty(), Optional.of(diagnostic));
        }

        /**
         * @return {@code true} only when a value is present and no diagnostic was recorded; the two
         *         are mutually exclusive for results built through the factory methods
         */
        public boolean isAvailable() {
            return value.isPresent() && diagnostic.isEmpty();
        }
    }

    final class Impl implements RenderStatusAdapter {
        private final Optional<HostOperations> host;

        private Impl(final Optional<HostOperations> host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        /**
         * An adapter that reads through the given host operations.
         *
         * <p>Calls are still guarded: the host version is checked and the capability probed before
         * any read, so a connected adapter can still answer unavailable.
         *
         * @param host the live host operations, non-null
         * @return an adapter bound to that host
         * @throws NullPointerException if {@code host} is null
         */
        public static RenderStatusAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        /**
         * An adapter for when no host is attached.
         *
         * <p>Every read returns an unavailable result carrying a safe-mode diagnostic; nothing is
         * ever called on the render-status host.
         *
         * @return a host-free adapter that never fails
         */
        public static RenderStatusAdapter safeMode() {
            return new Impl(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<RenderStatusSnapshot>> renderStatus() {
            return host.map(this::callIfSupported).orElseGet(unavailable());
        }

        private AdapterResult<Optional<RenderStatusSnapshot>> callIfSupported(final HostOperations operations) {
            try {
                final Optional<SafeModeDiagnostic> versionDiagnostic =
                    HostUiVersionCheck.diagnosticFor(CAPABILITY_ID, operations.hostVersion());
                if (versionDiagnostic.isPresent()) {
                    return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
                }
                if (!operations.supportsRenderStatusRead()) {
                    return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(CAPABILITY_ID));
                }
                return AdapterResult.available(operations.renderStatus());
            } catch (AdapterHostException exception) {
                return AdapterResult.unavailable(exception.diagnostic());
            } catch (RuntimeException exception) {
                return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                    CAPABILITY_ID,
                    "Host render-status adapter call failed safely."
                ));
            }
        }

        private static Supplier<AdapterResult<Optional<RenderStatusSnapshot>>> unavailable() {
            return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(CAPABILITY_ID));
        }
    }
}
