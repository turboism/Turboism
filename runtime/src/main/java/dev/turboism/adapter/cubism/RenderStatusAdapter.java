package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.HostUiVersionCheck;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * M14 simulated-host seam for read-only render status.
 * Driven by M13 behavior {@code render-status.overlay.fake}.
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

    final class Impl implements RenderStatusAdapter {
        private final Optional<HostOperations> host;

        private Impl(final Optional<HostOperations> host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        public static RenderStatusAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        public static RenderStatusAdapter safeMode() {
            return new Impl(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<RenderStatusSnapshot>> renderStatus() {
            return host.map(this::callIfSupported).orElseGet(unavailable());
        }

        private AdapterResult<Optional<RenderStatusSnapshot>> callIfSupported(final HostOperations operations) {
            final Optional<SafeModeDiagnostic> versionDiagnostic = HostUiVersionCheck.diagnosticFor(operations.hostVersion());
            if (versionDiagnostic.isPresent()) {
                return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
            }
            if (!operations.supportsRenderStatusRead()) {
                return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(CAPABILITY_ID));
            }
            return AdapterResult.available(operations.renderStatus());
        }

        private static Supplier<AdapterResult<Optional<RenderStatusSnapshot>>> unavailable() {
            return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(CAPABILITY_ID));
        }
    }
}
