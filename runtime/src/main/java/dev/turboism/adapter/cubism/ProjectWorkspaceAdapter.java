package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.HostUiVersionCheck;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * M14 simulated-host seam for read-only project/workspace snapshots.
 * Driven by M13 main-toolbar / project-panel behaviors.
 */
public interface ProjectWorkspaceAdapter {

    String PROJECT_CAPABILITY_ID = "cubism.project.read";
    String WORKSPACE_CAPABILITY_ID = "cubism.workspace.read";
    String ADAPTER_SLICE_ID = "adapter.project-workspace.readonly";

    AdapterResult<Optional<ProjectSnapshot>> activeProject();

    AdapterResult<Optional<WorkspaceSnapshot>> workspace();

    /** One ordered adapter admission for a coherent serialized project/workspace observation. */
    AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot();

    interface HostOperations {
        String hostVersion();

        boolean supportsProjectWorkspaceRead();

        Optional<ProjectSnapshot> activeProject();

        Optional<WorkspaceSnapshot> workspace();
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

    final class Impl implements ProjectWorkspaceAdapter {
        private final Optional<HostOperations> host;

        private Impl(final Optional<HostOperations> host) {
            this.host = Objects.requireNonNull(host, "host");
        }

        public static ProjectWorkspaceAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        public static ProjectWorkspaceAdapter safeMode() {
            return new Impl(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<ProjectSnapshot>> activeProject() {
            return host.map(ops -> callIfSupported(ops, PROJECT_CAPABILITY_ID, ops::activeProject))
                .orElseGet(unavailable(PROJECT_CAPABILITY_ID));
        }

        @Override
        public AdapterResult<Optional<WorkspaceSnapshot>> workspace() {
            return host.map(ops -> callIfSupported(ops, WORKSPACE_CAPABILITY_ID, ops::workspace))
                .orElseGet(unavailable(WORKSPACE_CAPABILITY_ID));
        }

        @Override
        public AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot() {
            return host.map(this::readCombined)
                .orElseGet(() -> AdapterResult.unavailable(
                    SafeModeDiagnostic.adapterUnavailable(PROJECT_CAPABILITY_ID)
                ));
        }

        private AdapterResult<ProjectWorkspaceSnapshot> readCombined(final HostOperations operations) {
            try {
                final Optional<SafeModeDiagnostic> versionDiagnostic =
                    HostUiVersionCheck.diagnosticFor(PROJECT_CAPABILITY_ID, operations.hostVersion());
                if (versionDiagnostic.isPresent()) {
                    return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
                }
                if (!operations.supportsProjectWorkspaceRead()) {
                    return AdapterResult.unavailable(
                        SafeModeDiagnostic.capabilityUnavailable(PROJECT_CAPABILITY_ID)
                    );
                }
                return AdapterResult.available(new ProjectWorkspaceSnapshot(
                    operations.activeProject(),
                    operations.workspace()
                ));
            } catch (AdapterHostException exception) {
                return AdapterResult.unavailable(exception.diagnostic());
            } catch (RuntimeException exception) {
                return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                    PROJECT_CAPABILITY_ID,
                    "Host project/workspace adapter call failed safely."
                ));
            }
        }

        private <T> AdapterResult<Optional<T>> callIfSupported(
            final HostOperations operations,
            final String capabilityId,
            final Supplier<Optional<T>> supplier
        ) {
            try {
                final Optional<SafeModeDiagnostic> versionDiagnostic =
                    HostUiVersionCheck.diagnosticFor(capabilityId, operations.hostVersion());
                if (versionDiagnostic.isPresent()) {
                    return AdapterResult.unavailable(versionDiagnostic.orElseThrow());
                }
                if (!operations.supportsProjectWorkspaceRead()) {
                    return AdapterResult.unavailable(SafeModeDiagnostic.capabilityUnavailable(capabilityId));
                }
                return AdapterResult.available(supplier.get());
            } catch (AdapterHostException exception) {
                return AdapterResult.unavailable(exception.diagnostic());
            } catch (RuntimeException exception) {
                return AdapterResult.unavailable(SafeModeDiagnostic.validationFailure(
                    capabilityId,
                    "Host project/workspace adapter call failed safely."
                ));
            }
        }

        private static <T> Supplier<AdapterResult<Optional<T>>> unavailable(final String capabilityId) {
            return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capabilityId));
        }
    }
}
