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

    final class Impl implements ClipMaskReadAdapter {
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
        public static ClipMaskReadAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        /**
         * An adapter for when no host is attached.
         *
         * <p>Every read returns an unavailable result carrying a safe-mode diagnostic; nothing is
         * ever called on the clip-mask host.
         *
         * @return a host-free adapter that never fails
         */
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
