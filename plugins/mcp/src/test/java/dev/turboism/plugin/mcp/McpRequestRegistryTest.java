package dev.turboism.plugin.mcp;

import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import dev.turboism.sdk.cubism.ModelSnapshot;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.query.ModelHierarchyQueryService;
import dev.turboism.sdk.cubism.service.query.ParameterQueryService;
import dev.turboism.sdk.cubism.service.query.SelectionQueryService;
import dev.turboism.sdk.cubism.service.read.CubismReadCapabilityService;
import dev.turboism.sdk.plugin.PluginLogger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class McpRequestRegistryTest {

    @Test
    void cancelsQueuedUiWorkBeforeItCommitsToTheHost() throws Exception {
        final McpRequestRegistry requests = new McpRequestRegistry();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final CountDownLatch scheduled = new CountDownLatch(1);
        final AtomicBoolean invoked = new AtomicBoolean();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final McpExecutionBridge bridge = new McpExecutionBridge(new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                queued.set(work);
                scheduled.countDown();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                throw new UnsupportedOperationException();
            }
        });

        final Thread worker = new Thread(() -> {
            try (McpRequestRegistry.Scope ignored = requests.enter("session", 7)) {
                bridge.ui(() -> {
                    invoked.set(true);
                    return null;
                });
            } catch (CancellationException expected) {
                cancelled.set(true);
            }
        });
        worker.start();

        assertTrue(scheduled.await(5, TimeUnit.SECONDS));
        assertTrue(requests.cancel("session", 7));
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertTrue(cancelled.get());

        queued.get().run();
        assertFalse(invoked.get());
        assertFalse(requests.cancel("session", 7));
    }

    @Test
    void cancelsQueuedModelObjectBatchBeforeAnyHostWrite() throws Exception {
        final McpRequestRegistry requests = new McpRequestRegistry();
        final AtomicReference<Runnable> queued = new AtomicReference<>();
        final CountDownLatch scheduled = new CountDownLatch(1);
        final AtomicBoolean invoked = new AtomicBoolean();
        final UiScheduler scheduler = new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                queued.set(work);
                scheduled.countDown();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                throw new UnsupportedOperationException();
            }
        };
        final ModelObjectService objects = new ModelObjectService() {
            @Override public java.util.List<ModelObjectDescriptor> list() { return java.util.List.of(); }
            @Override public ModelObjectDescriptor rename(final ModelObjectReference target, final String name) {
                invoked.set(true); throw new AssertionError("rename must not run");
            }
            @Override public ModelObjectDescriptor reparent(final ModelObjectReference target, final ModelObjectReference parent, final int index) {
                invoked.set(true); throw new AssertionError("reparent must not run");
            }
            @Override public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
                invoked.set(true); throw new AssertionError("create must not run");
            }
            @Override public void delete(final ModelObjectReference target, final ModelObjectDeletePolicy policy) {
                invoked.set(true); throw new AssertionError("delete must not run");
            }
        };
        final McpTools tools = new McpTools(
            objects,
            unavailableParameters(),
            unavailableHierarchy(),
            unavailableSelection(),
            unavailableRead(),
            java.util.List::of,
            silentLogger(),
            new McpExecutionBridge(scheduler)
        );
        final McpProductionDomainCatalog production = new McpProductionDomainCatalog(tools);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final Thread worker = new Thread(() -> {
            try (McpRequestRegistry.Scope ignored = requests.enter("session", 9)) {
                production.call(McpProductionDomainCatalog.APPLY, java.util.Map.of(
                    "operations", java.util.List.of(java.util.Map.of(
                        "operation", "create", "kind", "part", "name", "Queued"
                    ))
                ));
            } catch (CancellationException expected) {
                cancelled.set(true);
            }
        });
        worker.start();

        assertTrue(scheduled.await(5, TimeUnit.SECONDS));
        assertTrue(requests.cancel("session", 9));
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertTrue(cancelled.get());
        queued.get().run();
        assertFalse(invoked.get());
    }

    @Test
    void cancellationAfterUiSubmissionWaitsForTheHostResult() throws Exception {
        final McpRequestRegistry requests = new McpRequestRegistry();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicBoolean completed = new AtomicBoolean();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final McpExecutionBridge bridge = new McpExecutionBridge(new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                final Thread ui = new Thread(work, "test-ui");
                ui.start();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                throw new UnsupportedOperationException();
            }
        });
        final Thread worker = new Thread(() -> {
            try (McpRequestRegistry.Scope ignored = requests.enter("session", 10)) {
                bridge.ui(() -> {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interrupted);
                    }
                    completed.set(true);
                    return null;
                });
            } catch (Throwable caught) {
                failure.set(caught);
            }
        });
        worker.start();

        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertTrue(requests.cancel("session", 10));
        Thread.sleep(100L);
        assertTrue(worker.isAlive());
        assertFalse(completed.get());

        release.countDown();
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertTrue(completed.get());
        assertTrue(failure.get() == null);
    }

    @Test
    void cancelsPendingCompletionStage() throws Exception {
        final McpRequestRegistry requests = new McpRequestRegistry();
        final java.util.concurrent.CompletableFuture<String> pending =
            new java.util.concurrent.CompletableFuture<>();
        final CountDownLatch waiting = new CountDownLatch(1);
        final AtomicBoolean cancelled = new AtomicBoolean();
        final McpExecutionBridge bridge = new McpExecutionBridge(new UiScheduler() {
            @Override public Registration runOnUiThread(final Runnable work) {
                work.run();
                return () -> { };
            }

            @Override public Registration runOnUiThreadLater(
                final Runnable work,
                final Duration delay
            ) {
                throw new UnsupportedOperationException();
            }
        });
        final Thread worker = new Thread(() -> {
            try (McpRequestRegistry.Scope ignored = requests.enter("session", 8)) {
                waiting.countDown();
                bridge.stage(() -> pending);
            } catch (CancellationException expected) {
                cancelled.set(true);
            }
        });
        worker.start();

        assertTrue(waiting.await(5, TimeUnit.SECONDS));
        assertTrue(requests.cancel("session", 8));
        worker.join(5_000);
        assertFalse(worker.isAlive());
        assertTrue(cancelled.get());
        assertFalse(requests.cancel("session", 8));
    }

    private static ParameterQueryService unavailableParameters() {
        return new ParameterQueryService() {
            @Override public java.util.Optional<dev.turboism.sdk.cubism.service.query.ParameterSummary> findById(
                final dev.turboism.sdk.cubism.id.ParameterId id
            ) { return java.util.Optional.empty(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.service.query.ParameterSummary> listAll() {
                return java.util.List.of();
            }
            @Override public boolean exists(final dev.turboism.sdk.cubism.id.ParameterId id) { return false; }
        };
    }

    private static ModelHierarchyQueryService unavailableHierarchy() {
        return new ModelHierarchyQueryService() {
            @Override public java.util.Optional<dev.turboism.sdk.cubism.service.query.ModelHierarchy> currentHierarchy() {
                return java.util.Optional.empty();
            }
            @Override public java.util.List<dev.turboism.sdk.cubism.service.query.HierarchyNode> childrenOf(
                final dev.turboism.sdk.cubism.id.ModelObjectId id
            ) { return java.util.List.of(); }
            @Override public java.util.Optional<dev.turboism.sdk.cubism.service.query.HierarchyNode> findNode(
                final dev.turboism.sdk.cubism.id.ModelObjectId id
            ) { return java.util.Optional.empty(); }
        };
    }

    private static SelectionQueryService unavailableSelection() {
        return new SelectionQueryService() {
            @Override public dev.turboism.sdk.cubism.service.query.SelectionSummary currentSelection() {
                return dev.turboism.sdk.cubism.service.query.SelectionSummary.empty();
            }
            @Override public java.util.List<dev.turboism.sdk.cubism.id.ModelObjectId> selectedIds(
                final dev.turboism.sdk.cubism.service.query.HierarchyNode.Kind kind
            ) { return java.util.List.of(); }
        };
    }

    private static CubismReadCapabilityService unavailableRead() {
        return new CubismReadCapabilityService() {
            @Override public java.util.Optional<dev.turboism.sdk.cubism.ProjectSnapshot> activeProject() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<dev.turboism.sdk.cubism.DocumentSnapshot> activeDocument() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<ModelSnapshot> activeModel() { return java.util.Optional.empty(); }
            @Override public dev.turboism.sdk.cubism.SelectionSnapshot selection() {
                return new dev.turboism.sdk.cubism.SelectionSnapshot(
                    java.util.List.of(), java.util.Optional.empty(), java.util.Optional.empty(), java.util.Optional.empty()
                );
            }
            @Override public java.util.List<dev.turboism.sdk.cubism.ParameterSnapshot> parameters() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.ModelObjectSnapshot> modelObjects() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.ArtMeshSnapshot> meshes() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.DeformerSnapshot> deformers() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.PsdDocumentSnapshot> psdDocuments() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.ClipMaskSnapshot> clipMasks() { return java.util.List.of(); }
            @Override public java.util.List<dev.turboism.sdk.cubism.TextureAtlasSnapshot> textureAtlases() { return java.util.List.of(); }
            @Override public java.util.Optional<dev.turboism.sdk.cubism.RenderStatusSnapshot> renderStatus() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<dev.turboism.sdk.cubism.WorkspaceSnapshot> workspace() { return java.util.Optional.empty(); }
            @Override public java.util.Optional<dev.turboism.sdk.theme.ThemeStatusSnapshot> themeStatus() { return java.util.Optional.empty(); }
        };
    }

    private static PluginLogger silentLogger() {
        return new PluginLogger() {
            @Override public void debug(final String message) { }
            @Override public void info(final String message) { }
            @Override public void warn(final String message) { }
            @Override public void error(final String message) { }
            @Override public void error(final String message, final Throwable throwable) { }
        };
    }
}
