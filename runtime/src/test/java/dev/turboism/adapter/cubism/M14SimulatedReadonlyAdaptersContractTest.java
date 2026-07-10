package dev.turboism.adapter.cubism;

import dev.turboism.adapter.ui.AdapterHostException;
import dev.turboism.adapter.ui.SafeModeDiagnostic;
import dev.turboism.sdk.cubism.ClipMaskSnapshot;
import dev.turboism.sdk.cubism.ProjectSnapshot;
import dev.turboism.sdk.cubism.RenderStatusSnapshot;
import dev.turboism.sdk.cubism.WorkspaceSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class M14SimulatedReadonlyAdaptersContractTest {

    @Test
    void renderStatusSafeModeIsUnavailable() {
        RenderStatusAdapter adapter = RenderStatusAdapter.Impl.safeMode();
        RenderStatusAdapter.AdapterResult<Optional<RenderStatusSnapshot>> result = adapter.renderStatus();
        assertFalse(result.isAvailable());
        assertEquals(SafeModeDiagnostic.Code.ADAPTER_UNAVAILABLE, result.diagnostic().orElseThrow().code());
        assertEquals(RenderStatusAdapter.CAPABILITY_ID, result.diagnostic().orElseThrow().capability());
    }

    @Test
    void renderStatusDelegatesWhenConnected() {
        RenderStatusAdapter adapter = RenderStatusAdapter.Impl.connected(new RenderHost("5.3.0", true));
        RenderStatusAdapter.AdapterResult<Optional<RenderStatusSnapshot>> result = adapter.renderStatus();
        assertTrue(result.isAvailable());
        assertEquals(
            Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer")),
            result.value().orElseThrow()
        );
    }

    @Test
    void renderStatusRejectsUnsupportedVersion() {
        RenderStatusAdapter adapter = RenderStatusAdapter.Impl.connected(new RenderHost("5.4.0", true));
        assertEquals(
            SafeModeDiagnostic.Code.HOST_VERSION_UNSUPPORTED,
            adapter.renderStatus().diagnostic().orElseThrow().code()
        );
    }

    @Test
    void renderStatusHostFailureBecomesSafeDiagnostic() {
        RenderStatusAdapter adapter = RenderStatusAdapter.Impl.connected(new RenderStatusAdapter.HostOperations() {
            @Override public String hostVersion() {
                throw new AdapterHostException(
                    SafeModeDiagnostic.Code.TIMEOUT,
                    RenderStatusAdapter.CAPABILITY_ID,
                    "timeout"
                );
            }
            @Override public boolean supportsRenderStatusRead() { return true; }
            @Override public Optional<RenderStatusSnapshot> renderStatus() { return Optional.empty(); }
        });

        assertEquals(
            SafeModeDiagnostic.Code.TIMEOUT,
            adapter.renderStatus().diagnostic().orElseThrow().code()
        );
    }

    @Test
    void projectWorkspaceSafeModeIsUnavailable() {
        ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.safeMode();
        assertFalse(adapter.activeProject().isAvailable());
        assertFalse(adapter.workspace().isAvailable());
    }

    @Test
    void projectWorkspaceDelegatesWhenConnected() {
        ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.connected(new ProjectHost("5.3.1", true));
        assertTrue(adapter.activeProject().isAvailable());
        assertTrue(adapter.workspace().isAvailable());
        assertEquals("project-1", adapter.activeProject().value().orElseThrow().orElseThrow().projectId());
        assertEquals("workspace-1", adapter.workspace().value().orElseThrow().orElseThrow().workspaceId());
    }

    @Test
    void projectWorkspaceUnexpectedFailureDoesNotLeakHostDetails() {
        ProjectWorkspaceAdapter adapter = ProjectWorkspaceAdapter.Impl.connected(new ProjectWorkspaceAdapter.HostOperations() {
            @Override public String hostVersion() { return "5.3.1"; }
            @Override public boolean supportsProjectWorkspaceRead() { return true; }
            @Override public Optional<ProjectSnapshot> activeProject() {
                throw new IllegalStateException("private-project-object");
            }
            @Override public Optional<WorkspaceSnapshot> workspace() { return Optional.empty(); }
        });

        SafeModeDiagnostic diagnostic = adapter.activeProject().diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("private-project-object"));
    }

    @Test
    void clipMaskSafeModeIsUnavailable() {
        ClipMaskReadAdapter adapter = ClipMaskReadAdapter.Impl.safeMode();
        assertFalse(adapter.clipMasks().isAvailable());
        assertEquals(ClipMaskReadAdapter.CAPABILITY_ID, adapter.clipMasks().diagnostic().orElseThrow().capability());
    }

    @Test
    void clipMaskDelegatesWhenConnected() {
        ClipMaskReadAdapter adapter = ClipMaskReadAdapter.Impl.connected(new ClipMaskHost("5.3.2", true));
        assertTrue(adapter.clipMasks().isAvailable());
        assertEquals(1, adapter.clipMasks().value().orElseThrow().size());
        assertEquals("mask-1", adapter.clipMasks().value().orElseThrow().get(0).clipMaskId());
    }

    @Test
    void clipMaskUnexpectedFailureBecomesValidationDiagnostic() {
        ClipMaskReadAdapter adapter = ClipMaskReadAdapter.Impl.connected(new ClipMaskReadAdapter.HostOperations() {
            @Override public String hostVersion() { return "5.3.2"; }
            @Override public boolean supportsClipMaskRead() { return true; }
            @Override public List<ClipMaskSnapshot> clipMasks() {
                throw new IllegalStateException("private-mask-object");
            }
        });

        SafeModeDiagnostic diagnostic = adapter.clipMasks().diagnostic().orElseThrow();
        assertEquals(SafeModeDiagnostic.Code.VALIDATION_FAILURE, diagnostic.code());
        assertFalse(diagnostic.message().contains("private-mask-object"));
    }

    @Test
    void clipMaskCapabilityUnavailableWhenHostOmitsSupport() {
        ClipMaskReadAdapter adapter = ClipMaskReadAdapter.Impl.connected(new ClipMaskHost("5.3.0", false));
        assertEquals(
            SafeModeDiagnostic.Code.CAPABILITY_UNAVAILABLE,
            adapter.clipMasks().diagnostic().orElseThrow().code()
        );
    }

    private record RenderHost(String hostVersion, boolean supports) implements RenderStatusAdapter.HostOperations {
        @Override public String hostVersion() { return hostVersion; }
        @Override public boolean supportsRenderStatusRead() { return supports; }
        @Override public Optional<RenderStatusSnapshot> renderStatus() {
            return Optional.of(new RenderStatusSnapshot(true, 60.0, "fake-renderer"));
        }
    }

    private record ProjectHost(String hostVersion, boolean supports) implements ProjectWorkspaceAdapter.HostOperations {
        @Override public String hostVersion() { return hostVersion; }
        @Override public boolean supportsProjectWorkspaceRead() { return supports; }
        @Override public Optional<ProjectSnapshot> activeProject() {
            return Optional.of(new ProjectSnapshot("project-1", "Demo", Optional.empty(), List.of()));
        }
        @Override public Optional<WorkspaceSnapshot> workspace() {
            return Optional.of(new WorkspaceSnapshot("workspace-1", "workspaces/demo", List.of("project-1")));
        }
    }

    private record ClipMaskHost(String hostVersion, boolean supports) implements ClipMaskReadAdapter.HostOperations {
        @Override public String hostVersion() { return hostVersion; }
        @Override public boolean supportsClipMaskRead() { return supports; }
        @Override public List<ClipMaskSnapshot> clipMasks() {
            return List.of(new ClipMaskSnapshot("mask-1", List.of("src"), List.of("mesh-1"), true));
        }
    }
}
