package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.HostUiVersionCheck;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Adapter seam for read-only project/workspace snapshots.
 * Used by toolbar, project-panel, and workspace consumers.
 */
public interface ProjectWorkspaceAdapter {

    String PROJECT_CAPABILITY_ID = "cubism.project.read";
    String DOCUMENT_CAPABILITY_ID = PROJECT_CAPABILITY_ID;
    String WORKSPACE_CAPABILITY_ID = "cubism.workspace.read";
    String ADAPTER_SLICE_ID = "adapter.project-workspace.readonly";

    AdapterResult<Optional<ProjectSnapshot>> activeProject();

    AdapterResult<Optional<DocumentSnapshot>> activeDocument();

    AdapterResult<Optional<WorkspaceSnapshot>> workspace();

    /** One ordered adapter admission for a coherent serialized project/workspace observation. */
    AdapterResult<ProjectWorkspaceSnapshot> projectWorkspaceSnapshot();

    interface HostOperations {
        String hostVersion();

        boolean supportsProjectWorkspaceRead();

        Optional<ProjectSnapshot> activeProject();

        default Optional<DocumentSnapshot> activeDocument() {
            return Optional.empty();
        }

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

    final class Impl implements ProjectWorkspaceAdapter {
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
        public static ProjectWorkspaceAdapter connected(final HostOperations host) {
            return new Impl(Optional.of(Objects.requireNonNull(host, "host")));
        }

        /**
         * An adapter for when no host is attached.
         *
         * <p>Every read returns an unavailable result carrying a safe-mode diagnostic; nothing is
         * ever called on the project/workspace host.
         *
         * @return a host-free adapter that never fails
         */
        public static ProjectWorkspaceAdapter safeMode() {
            return new Impl(Optional.empty());
        }

        @Override
        public AdapterResult<Optional<ProjectSnapshot>> activeProject() {
            return host.map(ops -> callIfSupported(ops, PROJECT_CAPABILITY_ID, ops::activeProject))
                .orElseGet(unavailable(PROJECT_CAPABILITY_ID));
        }

        @Override
        public AdapterResult<Optional<DocumentSnapshot>> activeDocument() {
            return host.map(ops -> callIfSupported(
                    ops,
                    DOCUMENT_CAPABILITY_ID,
                    ops::activeDocument
                ))
                .orElseGet(unavailable(DOCUMENT_CAPABILITY_ID));
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
                if (!isReviewedProjectWorkspaceVersion(operations.hostVersion())) {
                    return AdapterResult.unavailable(SafeModeDiagnostic.hostVersionUnsupported(
                        PROJECT_CAPABILITY_ID,
                        operations.hostVersion()
                    ));
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
                if (!isReviewedProjectWorkspaceVersion(operations.hostVersion())) {
                    return AdapterResult.unavailable(SafeModeDiagnostic.hostVersionUnsupported(
                        capabilityId,
                        operations.hostVersion()
                    ));
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

        private static boolean isReviewedProjectWorkspaceVersion(final String hostVersion) {
            return HostUiVersionCheck.diagnosticFor(PROJECT_CAPABILITY_ID, hostVersion).isEmpty();
        }

        private static <T> Supplier<AdapterResult<Optional<T>>> unavailable(final String capabilityId) {
            return () -> AdapterResult.unavailable(SafeModeDiagnostic.adapterUnavailable(capabilityId));
        }
    }
}
