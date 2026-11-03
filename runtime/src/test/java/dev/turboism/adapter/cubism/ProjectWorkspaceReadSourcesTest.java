package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.hostread.ProjectWorkspaceHostReadResult;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import dev.turboism.sdk.hostread.AsyncHostReadErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ProjectWorkspaceReadSourcesTest {

    @Test
    void readsAdapterResultsWithoutSynchronousFacadeFallback() {
        final ProjectWorkspaceHostReadResult result = ProjectWorkspaceReadSources.from(
                        ProjectWorkspaceAdapter.Impl.connected(new Host()))
                .read();

        assertTrue(result.value().isPresent());
        assertEquals(
                "Project", result.value().orElseThrow().project().orElseThrow().name());
        assertEquals(
                "workspace",
                result.value().orElseThrow().workspace().orElseThrow().displayName());
    }

    @Test
    void readsProjectAndWorkspaceThroughOneCombinedAdapterAdmission() {
        final List<String> order = new ArrayList<>();
        final Host host = new Host() {
            @Override
            public boolean supportsProjectWorkspaceRead() {
                order.add("supports");
                return super.supportsProjectWorkspaceRead();
            }

            @Override
            public Optional<ProjectSnapshot> activeProject() {
                order.add("project");
                return super.activeProject();
            }

            @Override
            public Optional<WorkspaceSnapshot> workspace() {
                order.add("workspace");
                return super.workspace();
            }
        };

        final ProjectWorkspaceHostReadResult result = ProjectWorkspaceReadSources.from(
                        ProjectWorkspaceAdapter.Impl.connected(host))
                .read();

        assertTrue(result.value().isPresent());
        assertEquals(List.of("supports", "project", "workspace"), order);
    }

    @Test
    void mapsSafeModeAndAdapterDiagnosticsIntoClosedErrors() {
        assertEquals(
                AsyncHostReadErrorCode.CAPABILITY_UNAVAILABLE,
                ProjectWorkspaceReadSources.from(ProjectWorkspaceAdapter.Impl.safeMode())
                        .read()
                        .errorCode()
                        .orElseThrow());
        assertEquals(
                AsyncHostReadErrorCode.HOST_VERSION_UNSUPPORTED,
                ProjectWorkspaceReadSources.from(ProjectWorkspaceAdapter.Impl.connected(new Host() {
                            @Override
                            public String hostVersion() {
                                return "0.0";
                            }
                        }))
                        .read()
                        .errorCode()
                        .orElseThrow());
        assertEquals(
                AsyncHostReadErrorCode.MAPPING_NOT_VERIFIED,
                ProjectWorkspaceReadSources.from(ProjectWorkspaceAdapter.Impl.connected(new Host() {
                            @Override
                            public Optional<ProjectSnapshot> activeProject() {
                                throw new AdapterHostException(
                                        SafeModeDiagnostic.Code.MAPPING_NOT_VERIFIED,
                                        ProjectWorkspaceAdapter.PROJECT_CAPABILITY_ID,
                                        "private selector");
                            }
                        }))
                        .read()
                        .errorCode()
                        .orElseThrow());
    }

    private static class Host implements ProjectWorkspaceAdapter.HostOperations {
        @Override
        public String hostVersion() {
            return "5.3.02";
        }

        @Override
        public boolean supportsProjectWorkspaceRead() {
            return true;
        }

        @Override
        public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot("project", "Project", Optional.empty(), List.of()));
        }

        @Override
        public Optional<WorkspaceSnapshot> workspace() {
            return Optional.of(new WorkspaceSnapshot("workspace", "workspace", List.of("project")));
        }
    }
}
