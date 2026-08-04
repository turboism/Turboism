package dev.turboism.adapter.host;

import dev.turboism.adapter.RuntimeHostAdapters;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import dev.turboism.ui.workspace.WorkspaceControlTestFixtures;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VerifiedHostAdapterConnectorWorkspaceTest {
    @Test
    void composesWorkspaceWithoutRequiringEditorModelSlice() throws Exception {
        var resolver = TestVerifiedResolvers.create(
            "5.3.02", "adapter.workspace.control.v5_3", Set.of("cubism.workspace.control"),
            WorkspaceControlTestFixtures.selectors(), WorkspaceControlTestFixtures.classLoader()
        );
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            evidence -> RuntimeHostAdapters.safeMode(),
            slice -> { throw new AssertionError("editor resolver must not run"); },
            (value, session) -> { throw new AssertionError("editor access must not run"); },
            null, null, null, null, null, null, null,
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            slice -> resolver
        );
        Path artifact = Path.of("/tmp/shared-cubism.jar");
        var project = new HostVerificationEvidence.Slice(Path.of("project.json"), artifact, getClass().getClassLoader());
        var workspace = new HostVerificationEvidence.Slice(Path.of("workspace.json"), artifact, getClass().getClassLoader());
        var connection = connector.connect(new HostInstanceDescriptor(
            "session", HostVerificationEvidence.projectOnly(project).addingWorkspaceControl(workspace)
        ));

        assertNotNull(connection.workspaceProvider());
        assertEquals(WorkspaceStatus.Availability.AVAILABLE, connection.workspaceProvider().readStatus().availability());
        assertThrows(IllegalStateException.class, connection::editorModelResolver,
            "a workspace-only connection must fail closed when no editor slice exists");
    }

    @Test
    void withoutEditorModelOnlyWorkspaceComposesAndUnrelatedUiResolversNeverRun() throws Exception {
        var resolver = TestVerifiedResolvers.create(
            "5.3.02", "adapter.workspace.control.v5_3", Set.of("cubism.workspace.control"),
            WorkspaceControlTestFixtures.selectors(), WorkspaceControlTestFixtures.classLoader()
        );
        VerifiedHostAdapterConnector connector = new VerifiedHostAdapterConnector(
            evidence -> RuntimeHostAdapters.safeMode(),
            slice -> { throw new AssertionError("editor resolver must not run"); },
            (value, session) -> { throw new AssertionError("editor access must not run"); },
            slice -> { throw new AssertionError("toolbar resolver must not run"); },
            slice -> { throw new AssertionError("panel resolver must not run"); },
            slice -> { throw new AssertionError("overlay resolver must not run"); },
            new dev.turboism.ui.toolbar.EditorUiPluginResourceRegistry(),
            new dev.turboism.ui.action.RuntimeEditorUiActionRouter(),
            new dev.turboism.ui.panel.RuntimeEmbeddedPanelActivationCoordinator(),
            slice -> { throw new AssertionError("top-menu resolver must not run"); },
            new dev.turboism.ui.panel.RuntimeDockMaintenanceCoordinator(),
            slice -> resolver
        );
        Path artifact = Path.of("/tmp/shared-cubism.jar");
        var project = new HostVerificationEvidence.Slice(Path.of("project.json"), artifact, getClass().getClassLoader());
        var workspace = new HostVerificationEvidence.Slice(Path.of("workspace.json"), artifact, getClass().getClassLoader());
        var mainToolbar = new HostVerificationEvidence.Slice(Path.of("toolbar.json"), artifact, getClass().getClassLoader());
        var embeddedPanel = new HostVerificationEvidence.Slice(Path.of("panel.json"), artifact, getClass().getClassLoader());
        var topMenu = new HostVerificationEvidence.Slice(Path.of("topmenu.json"), artifact, getClass().getClassLoader());
        var overlay = new HostVerificationEvidence.Slice(Path.of("overlay.json"), artifact, getClass().getClassLoader());
        var connection = connector.connect(new HostInstanceDescriptor(
            "session", HostVerificationEvidence.projectOnly(project)
                .addingWorkspaceControl(workspace)
                .addingMainToolbar(mainToolbar)
                .addingEmbeddedPanel(embeddedPanel)
                .addingTopMenu(topMenu)
                .addingBoundingBoxOverlayButton(overlay)
        ));

        assertNotNull(connection.workspaceProvider(), "workspace composes without an editor model");
        assertEquals(WorkspaceStatus.Availability.AVAILABLE, connection.workspaceProvider().readStatus().availability());
        assertEquals(List.of(), connection.editorUiProviders(1),
            "without an editor model, unrelated UI slices must not be installed");
        assertThrows(IllegalStateException.class, connection::editorModelResolver);
    }
}
