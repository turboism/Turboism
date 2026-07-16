package dev.turboism.hostread;

import dev.turboism.adapter.cubism.ProjectWorkspaceAdapter;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import dev.turboism.sdk.hostread.ProjectWorkspaceSnapshot;

import java.util.Objects;

/** Runtime-only closed source for the first async host-read intent. */
public interface ProjectWorkspaceHostReadSource {

    ProjectWorkspaceHostReadResult read();

    static ProjectWorkspaceHostReadSource from(final ProjectWorkspaceAdapter adapter) {
        final ProjectWorkspaceAdapter source = Objects.requireNonNull(adapter, "adapter");
        return () -> {
            final ProjectWorkspaceAdapter.AdapterResult<ProjectWorkspaceSnapshot> result =
                source.projectWorkspaceSnapshot();
            if (!result.isAvailable()) {
                return ProjectWorkspaceHostReadResult.failed(map(
                    result.diagnostic().orElseThrow().code()
                ));
            }
            return ProjectWorkspaceHostReadResult.available(result.value().orElseThrow());
        };
    }

    private static AsyncHostReadErrorCode map(final SafeModeDiagnostic.Code code) {
        return switch (code) {
            case ADAPTER_UNAVAILABLE, CAPABILITY_UNAVAILABLE ->
                AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE;
            case HOST_VERSION_UNSUPPORTED -> AsyncHostReadErrorCode.HOST_VERSION_UNSUPPORTED;
            case MAPPING_NOT_VERIFIED -> AsyncHostReadErrorCode.MAPPING_NOT_VERIFIED;
            case PERMISSION_DENIED -> AsyncHostReadErrorCode.PERMISSION_DENIED;
            case TIMEOUT -> AsyncHostReadErrorCode.TIMEOUT;
            case VALIDATION_FAILURE -> AsyncHostReadErrorCode.VALIDATION_FAILURE;
            case HOOK_NOT_VERIFIED -> AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE;
        };
    }
}
