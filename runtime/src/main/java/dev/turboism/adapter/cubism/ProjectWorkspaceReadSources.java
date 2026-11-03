package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.hostread.ProjectWorkspaceHostReadResult;
import dev.turboism.hostread.ProjectWorkspaceHostReadSource;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;
import java.util.Objects;

/**
 * Adapts a {@link ProjectWorkspaceAdapter} into the async host-read port.
 *
 * <p>The port package must not depend on the adapter layer, so the bridge lives
 * here: the adapter reaches down into {@code hostread}, never the reverse.</p>
 */
public final class ProjectWorkspaceReadSources {

    private ProjectWorkspaceReadSources() {}

    /**
     * Adapts a {@link ProjectWorkspaceAdapter} into this source, mapping its
     * {@link SafeModeDiagnostic} codes onto {@link AsyncHostReadErrorCode}.
     *
     * @param adapter the backing adapter, non-null
     * @return a source reading through it
     * @throws NullPointerException if {@code adapter} is null
     */
    public static ProjectWorkspaceHostReadSource from(final ProjectWorkspaceAdapter adapter) {
        final ProjectWorkspaceAdapter source = Objects.requireNonNull(adapter, "adapter");
        return () -> {
            final ProjectWorkspaceAdapter.AdapterResult<ProjectWorkspaceSnapshot> result =
                    source.projectWorkspaceSnapshot();
            if (!result.isAvailable()) {
                return ProjectWorkspaceHostReadResult.failed(
                        map(result.diagnostic().orElseThrow().code()));
            }
            return ProjectWorkspaceHostReadResult.available(result.value().orElseThrow());
        };
    }

    private static AsyncHostReadErrorCode map(final SafeModeDiagnostic.Code code) {
        return switch (code) {
            case ADAPTER_UNAVAILABLE, CAPABILITY_UNAVAILABLE -> AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE;
            case HOST_VERSION_UNSUPPORTED -> AsyncHostReadErrorCode.HOST_VERSION_UNSUPPORTED;
            case MAPPING_NOT_VERIFIED -> AsyncHostReadErrorCode.MAPPING_NOT_VERIFIED;
            case PERMISSION_DENIED -> AsyncHostReadErrorCode.PERMISSION_DENIED;
            case TIMEOUT -> AsyncHostReadErrorCode.TIMEOUT;
            case VALIDATION_FAILURE -> AsyncHostReadErrorCode.VALIDATION_FAILURE;
            case HOOK_NOT_VERIFIED -> AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE;
        };
    }
}
