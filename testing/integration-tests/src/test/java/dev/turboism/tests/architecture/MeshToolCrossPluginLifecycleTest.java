package dev.turboism.tests.architecture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.mesh.MeshToolCoordinator;
import dev.turboism.adapter.cubism.mesh.RuntimeMeshToolRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.kernel.context.CorePluginContext;
import dev.turboism.sdk.cubism.mesh.MeshTool;
import dev.turboism.sdk.cubism.mesh.MeshToolContext;
import dev.turboism.sdk.cubism.mesh.MeshToolRegistry;
import dev.turboism.sdk.cubism.mesh.SelectionMode;
import dev.turboism.sdk.cubism.mesh.VertexSelection;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.test.Stubs;
import dev.turboism.test.plugin.FakePluginPaths;
import dev.turboism.test.ui.FakeDirectUiScheduler;
import dev.turboism.testkit.RecordingHostSnapshotSource;
import dev.turboism.testkit.RuntimeTestKit;
import dev.turboism.ui.contribution.EditorUiContributionAuthority;
import dev.turboism.ui.host.EditorUiFamily;
import dev.turboism.ui.host.RuntimeEditorUiHostLifecycle;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies uncoupled lifecycle isolation, deterministic ordering, lease revocation, and failure
 * rollback across concurrent mesh-tool plugins sharing {@link MeshToolCoordinator}.
 */
class MeshToolCrossPluginLifecycleTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-11-26T00:00:00Z"), ZoneOffset.UTC);
    private static final String PEER_ALPHA_PLUGIN = "dev.turboism.test.mesh-alpha";
    private static final String PEER_BETA_PLUGIN = "dev.turboism.test.mesh-beta";
    private static final String FAILING_PLUGIN = "dev.turboism.test.mesh-failing";
    private static final String ALPHA_TOOL_ID = "alpha-tool";
    private static final String BETA_TOOL_ID = "beta-tool";
    private static final String FAILING_TOOL_ID = "failing-tool";
    private static final String ICON_PATH = "icons/tool.svg";

    @TempDir
    Path tempDir;

    @Test
    void packagedMeshPluginsProduceIdenticalToolbarOrderAndIsolatedTeardownRegardlessOfEnableOrder() throws Exception {
        final List<String> brushFirstOrder = runPackagedOrderScenario(true);
        final List<String> layoutFirstOrder = runPackagedOrderScenario(false);
        assertEquals(brushFirstOrder, layoutFirstOrder);
        assertEquals(2, brushFirstOrder.size());
    }

    @Test
    void activatingPeerToolRevokesPriorLeaseAndDisablingActivePluginPreservesPeerRegistrations() throws Exception {
        final RuntimeEditorUiHostLifecycle uiLifecycle = new RuntimeEditorUiHostLifecycle();
        final long hostGeneration = uiLifecycle.connecting().generation();
        uiLifecycle.ready(hostGeneration, Set.of(EditorUiFamily.MESH_TOOLBAR));
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(uiLifecycle);
        final MeshToolCoordinator coordinator = new MeshToolCoordinator();
        final DisposableScope alphaScope = new DisposableScope();
        final DisposableScope betaScope = new DisposableScope();
        final AtomicBoolean alphaActive = new AtomicBoolean();
        final AtomicBoolean betaActive = new AtomicBoolean();
        try {
            final long alphaGen = coordinator.allocateGeneration();
            final long betaGen = coordinator.allocateGeneration();
            final RuntimeMeshToolRegistry alphaRegistry =
                    createRegistry(PEER_ALPHA_PLUGIN, alphaGen, true, coordinator, authority, alphaScope);
            final RuntimeMeshToolRegistry betaRegistry =
                    createRegistry(PEER_BETA_PLUGIN, betaGen, true, coordinator, authority, betaScope);

            alphaRegistry.register(recordingTool(ALPHA_TOOL_ID, 10, alphaActive));
            betaRegistry.register(recordingTool(BETA_TOOL_ID, 20, betaActive));
            coordinator.beginSession(new FakeMeshSession(hostGeneration));

            coordinator.activate(PEER_ALPHA_PLUGIN, alphaGen, ALPHA_TOOL_ID);
            final MeshToolCoordinator.ActivationLease alphaLease =
                    coordinator.activeLease().orElseThrow();
            assertTrue(alphaActive.get());
            assertTrue(alphaLease.revalidate());

            coordinator.activate(PEER_BETA_PLUGIN, betaGen, BETA_TOOL_ID);
            assertFalse(alphaActive.get(), "switching to peer tool must deactivate previous tool");
            assertFalse(alphaLease.revalidate(), "previous ActivationLease must revoke immediately");
            assertThrows(IllegalStateException.class, alphaLease::requireCurrent);
            assertTrue(betaActive.get());

            betaScope.close();
            assertFalse(betaActive.get(), "closing active plugin scope must deactivate its tool");
            assertTrue(coordinator.activeTool().isEmpty());
            assertEquals(1, coordinator.snapshot().size());
            assertEquals(PEER_ALPHA_PLUGIN, coordinator.snapshot().get(0).pluginId());

            coordinator.activate(PEER_ALPHA_PLUGIN, alphaGen, ALPHA_TOOL_ID);
            assertTrue(alphaActive.get(), "remaining peer plugin tool must stay activatable after neighbor teardown");
        } finally {
            alphaScope.close();
            betaScope.close();
            coordinator.close();
            uiLifecycle.close();
        }
    }

    @Test
    void failingToolActivationRollsBackCoordinatorStateWithoutBreakingPeerTools() throws Exception {
        final RuntimeEditorUiHostLifecycle uiLifecycle = new RuntimeEditorUiHostLifecycle();
        final long hostGeneration = uiLifecycle.connecting().generation();
        uiLifecycle.ready(hostGeneration, Set.of(EditorUiFamily.MESH_TOOLBAR));
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(uiLifecycle);
        final MeshToolCoordinator coordinator = new MeshToolCoordinator();
        final DisposableScope peerScope = new DisposableScope();
        final DisposableScope failingScope = new DisposableScope();
        final AtomicBoolean peerActive = new AtomicBoolean();
        try {
            final long peerGen = coordinator.allocateGeneration();
            final long failingGen = coordinator.allocateGeneration();
            createRegistry(PEER_ALPHA_PLUGIN, peerGen, false, coordinator, authority, peerScope)
                    .register(recordingTool(ALPHA_TOOL_ID, 10, peerActive));
            createRegistry(FAILING_PLUGIN, failingGen, false, coordinator, authority, failingScope)
                    .register(new MeshTool() {
                        @Override
                        public String id() {
                            return FAILING_TOOL_ID;
                        }

                        @Override
                        public String label() {
                            return "Failing Tool";
                        }

                        @Override
                        public String iconResourcePath() {
                            return ICON_PATH;
                        }

                        @Override
                        public void activate(final MeshToolContext context) {
                            throw new IllegalStateException("simulated tool activation failure");
                        }

                        @Override
                        public void deactivate() {}
                    });

            coordinator.beginSession(new FakeMeshSession(hostGeneration));
            coordinator.activate(PEER_ALPHA_PLUGIN, peerGen, ALPHA_TOOL_ID);
            assertTrue(peerActive.get());

            assertThrows(
                    IllegalStateException.class,
                    () -> coordinator.activate(FAILING_PLUGIN, failingGen, FAILING_TOOL_ID));
            assertFalse(peerActive.get());
            assertTrue(coordinator.activeTool().isEmpty(), "failed activation must leave no dangling active tool");

            coordinator.activate(PEER_ALPHA_PLUGIN, peerGen, ALPHA_TOOL_ID);
            assertTrue(peerActive.get(), "peer tool must recover cleanly after neighbor activation failure");
        } finally {
            peerScope.close();
            failingScope.close();
            coordinator.close();
            uiLifecycle.close();
        }
    }

    private List<String> runPackagedOrderScenario(final boolean selectionBrushFirst) throws Exception {
        final RuntimeScheduler scheduler = RuntimeTestKit.scheduler(FIXED_CLOCK, 1, 8);
        final RuntimeEditorUiHostLifecycle uiLifecycle = new RuntimeEditorUiHostLifecycle();
        final long hostGeneration = uiLifecycle.connecting().generation();
        uiLifecycle.ready(hostGeneration, Set.of(EditorUiFamily.MESH_TOOLBAR));
        final EditorUiContributionAuthority authority = new EditorUiContributionAuthority(uiLifecycle);
        final MeshToolCoordinator coordinator = new MeshToolCoordinator();

        try (var brushJar = PackagedPluginJarSupport.loadFromSystemProperty(
                        PackagedPluginJarSupport.SELECTION_BRUSH_BUILD_DIR_PROP);
                var layoutJar = PackagedPluginJarSupport.loadFromSystemProperty(
                        PackagedPluginJarSupport.GEOMETRIC_MESH_LAYOUT_BUILD_DIR_PROP)) {
            final DisposableScope brushScope = new DisposableScope();
            final DisposableScope layoutScope = new DisposableScope();
            try {
                if (selectionBrushFirst) {
                    enablePackagedMeshPlugin(brushJar, false, scheduler, coordinator, authority, brushScope);
                    enablePackagedMeshPlugin(layoutJar, true, scheduler, coordinator, authority, layoutScope);
                } else {
                    enablePackagedMeshPlugin(layoutJar, true, scheduler, coordinator, authority, layoutScope);
                    enablePackagedMeshPlugin(brushJar, false, scheduler, coordinator, authority, brushScope);
                }

                final List<String> orderedToolIds = coordinator.snapshot().stream()
                        .map(entry -> entry.pluginId() + ":" + entry.tool().id())
                        .toList();
                final int totalContributions =
                        authority.contributions(EditorUiFamily.MESH_TOOLBAR).size();
                assertTrue(totalContributions >= 3, "expected tool buttons and brush-size slider");

                disableAndShutdown(brushJar, brushScope);
                assertEquals(1, coordinator.snapshot().size());
                assertEquals(
                        layoutJar.descriptor().id(),
                        coordinator.snapshot().get(0).pluginId());

                disableAndShutdown(layoutJar, layoutScope);
                assertEquals(0, coordinator.snapshot().size());
                assertEquals(
                        0, authority.contributions(EditorUiFamily.MESH_TOOLBAR).size());
                return orderedToolIds;
            } finally {
                brushScope.close();
                layoutScope.close();
            }
        } finally {
            coordinator.close();
            uiLifecycle.close();
            scheduler.shutdown();
        }
    }

    private void enablePackagedMeshPlugin(
            final PackagedPluginJarSupport.LoadedJarPlugin jarPlugin,
            final boolean pointAndEdgeLayout,
            final RuntimeScheduler scheduler,
            final MeshToolCoordinator coordinator,
            final EditorUiContributionAuthority authority,
            final DisposableScope scope)
            throws Exception {
        final PluginDescriptor descriptor = jarPlugin.descriptor();
        final CorePluginContext context = new CorePluginContext(new CorePluginContext.Dependencies(
                descriptor,
                Stubs.noopLogger(),
                FakePluginPaths.of(tempDir.resolve(descriptor.id())),
                FakeDirectUiScheduler.immediateOnly(),
                scheduler,
                Stubs.diagnostics(FIXED_CLOCK.instant()),
                scope,
                RecordingHostSnapshotSource.builder().hostPresent(true).build(),
                ignored -> {},
                FIXED_CLOCK));
        final RuntimeMeshToolRegistry registry = createRegistry(
                descriptor.id(), coordinator.allocateGeneration(), pointAndEdgeLayout, coordinator, authority, scope);
        context.installMeshTools(registry);
        assertEquals(registry, context.services().require(MeshToolRegistry.class));
        for (TurboismPlugin entrypoint : jarPlugin.entrypoints()) {
            entrypoint.init(context);
            entrypoint.enable();
        }
    }

    private static void disableAndShutdown(
            final PackagedPluginJarSupport.LoadedJarPlugin jarPlugin, final DisposableScope scope) throws Exception {
        for (TurboismPlugin entrypoint : jarPlugin.entrypoints()) {
            entrypoint.disable();
        }
        scope.close();
        for (TurboismPlugin entrypoint : jarPlugin.entrypoints()) {
            entrypoint.shutdown();
        }
    }

    private static RuntimeMeshToolRegistry createRegistry(
            final String pluginId,
            final long generation,
            final boolean pointAndEdgeLayout,
            final MeshToolCoordinator coordinator,
            final EditorUiContributionAuthority authority,
            final DisposableScope scope) {
        final RuntimeMeshToolRegistry registry = new RuntimeMeshToolRegistry(
                pluginId, generation, (permission, operation) -> {}, true, pointAndEdgeLayout, coordinator, authority);
        registry.bind(scope);
        return registry;
    }

    private static MeshTool recordingTool(final String id, final int order, final AtomicBoolean activeState) {
        return new MeshTool() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String label() {
                return id;
            }

            @Override
            public String iconResourcePath() {
                return ICON_PATH;
            }

            @Override
            public int order() {
                return order;
            }

            @Override
            public void activate(final MeshToolContext context) {
                activeState.set(true);
            }

            @Override
            public void deactivate() {
                activeState.set(false);
            }
        };
    }

    private record FakeMeshSession(long hostGeneration) implements MeshToolCoordinator.Session {
        @Override
        public boolean revalidate() {
            return true;
        }

        @Override
        public dev.turboism.sdk.cubism.model.Drawable drawable() {
            return null;
        }

        @Override
        public VertexSelection selection() {
            return VertexSelection.empty();
        }

        @Override
        public void select(final VertexSelection selection, final SelectionMode mode) {}
    }
}
