package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.HostUiVersionCheck;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter seam for read-only clip-mask snapshots.
 * Used by clip-mask inspection consumers.
 */
public interface ClipMaskReadAdapter {

    String CAPABILITY_ID = "cubism.clipmask.read";
    String ADAPTER_SLICE_ID = "adapter.clipmask.readonly";

    AdapterResult<List<ClipMaskSnapshot>> clipMasks();

    interface HostOperations {
        String hostVersion();

        boolean supportsClipMaskRead();

        List<ClipMaskSnapshot> clipMasks();
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

    final class Impl implements ClipMaskReadAdapter {
        private final Optional<HostOperations> host;

        private Impl(final Optional<HostOperations> host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        public static ClipMaskReadAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        public static ClipMaskReadAdapter safeMode() {
            return new Impl(Optional.empty());
        }

        @Override
        public AdapterResult<List<ClipMaskSnapshot>> clipMasks() {
            return host.map(this::callIfSupported).orElseGet(unavailable());
        }

        private AdapterResult<List<ClipMaskSnapshot>> callIfSupported(final HostOperations operations) {
            try {
                final Optional<SafeModeDiagnostic> versionDiagnostic =
                    HostUiVersionCheck.diagnosticFor(CAPABILITY_ID, operations.hostVersion());
                if (versionDiagnostic.isPresent()) {
                    return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
                }
                if (!operations.supportsClipMaskRead()) {
                    return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(CAPABILITY_ID));
                }
                return AdapterResult.available(List.copyOf(operations.clipMasks()));
            } catch (AdapterHostException exception) {
                return AdapterResult.unavailable(exception.diagnostic());
            } catch (RuntimeException exception) {
                return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                    CAPABILITY_ID,
                    "Host clip-mask adapter call failed safely."
                ));
            }
        }

        private static Supplier<AdapterResult<List<ClipMaskSnapshot>>> unavailable() {
            return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(CAPABILITY_ID));
        }
    }
}
