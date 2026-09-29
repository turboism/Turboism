package dev.turboism.ui.action;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.action.ActionDescriptor;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.permission.PluginPermission;
import dev.turboism.sdk.plugin.PluginLogger;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class RuntimeActionCatalogServiceTest {

    private static final String CALLER = "dev.turboism.plugin.caller";
    private static final String OWNER = "dev.turboism.plugin.owner";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-29T00:00:00Z"), ZoneOffset.UTC);

    private final List<String> logLines = new CopyOnWriteArrayList<>();
    private RuntimeScheduler scheduler;
    private RuntimeEditorUiActionRouter router;

    @AfterEach
    void shutdown() {
        if (router != null) {
            router.close();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void enumeratesEveryOwnerRegistryWithLabelsAndShortcuts() {
        // Given
        router = new RuntimeEditorUiActionRouter();
        final RuntimeActionRegistry ownerRegistry = registry(OWNER);
        ownerRegistry.register("owner.open", ActionRegistry.Action.of("owner.open", "Open Owner", ignored -> {}));
        ownerRegistry.register(
                "owner.bound", ActionRegistry.Action.of("owner.bound", "  ", "Ctrl+Shift+O", ignored -> {}));
        final RuntimeActionCatalogService catalog = catalog(CALLER, List.of(invokeGrant()));

        // When
        final List<ActionDescriptor> descriptors = catalog.actions();

        // Then
        assertEquals(2, descriptors.size());
        assertEquals(
                new ActionDescriptor(OWNER, "owner.open", "Open Owner", java.util.Optional.empty()),
                descriptors.get(0));
        // A blank label degrades to the action id.
        assertEquals(
                new ActionDescriptor(OWNER, "owner.bound", "owner.bound", java.util.Optional.of("Ctrl+Shift+O")),
                descriptors.get(1));
    }

    @Test
    void enumerationIncludesCallerOwnedActions() {
        // Given — the seam filters nothing; row policy belongs to consumers.
        router = new RuntimeEditorUiActionRouter();
        registry(CALLER).register("caller.open", ActionRegistry.Action.of("caller.open", "Caller", ignored -> {}));
        final RuntimeActionCatalogService catalog = catalog(CALLER, List.of(invokeGrant()));

        // Then
        assertEquals(1, catalog.actions().size());
        assertEquals(CALLER, catalog.actions().get(0).pluginId());
    }

    @Test
    void enumerationIsEmptyWithoutInvokePermission() {
        // Given
        router = new RuntimeEditorUiActionRouter();
        registry(OWNER).register("owner.open", ActionRegistry.Action.of("owner.open", "Open", ignored -> {}));

        // Then
        assertTrue(catalog(CALLER, List.of()).actions().isEmpty());
    }

    @Test
    void invokeRoutesToOwnerHandlerAndAuditsCaller() throws InterruptedException {
        // Given
        router = new RuntimeEditorUiActionRouter();
        final CountDownLatch handled = new CountDownLatch(1);
        registry(OWNER)
                .register("owner.open", ActionRegistry.Action.of("owner.open", "Open", ignored -> handled.countDown()));
        final RuntimeActionCatalogService catalog = catalog(CALLER, List.of(invokeGrant()));

        // When
        catalog.invoke(OWNER, "owner.open");

        // Then
        assertTrue(handled.await(2, TimeUnit.SECONDS));
        assertTrue(logLines.stream().anyMatch(line -> line.contains(CALLER) && line.contains(OWNER + "/owner.open")));
    }

    @Test
    void invokeWithoutPermissionIsRefused() {
        // Given
        router = new RuntimeEditorUiActionRouter();
        registry(OWNER).register("owner.open", ActionRegistry.Action.of("owner.open", "Open", ignored -> {}));

        // Then
        assertThrows(
                CubismPermissionException.class,
                () -> catalog(CALLER, List.of()).invoke(OWNER, "owner.open"));
    }

    @Test
    void invokeUnknownTargetIsSilent() {
        // Given
        router = new RuntimeEditorUiActionRouter();
        final RuntimeActionCatalogService catalog = catalog(CALLER, List.of(invokeGrant()));

        // When/Then — absent owner and absent action id are no-ops, matching the router.
        catalog.invoke("dev.turboism.plugin.absent", "nope");
        registry(OWNER);
        catalog.invoke(OWNER, "nope");
    }

    private RuntimeActionRegistry registry(final String owner) {
        if (scheduler == null) {
            scheduler = new RuntimeScheduler(
                    new DefaultWorkBudgetPolicy(),
                    new PluginWorkExecutorRegistry(1, 2, event -> {}, CLOCK),
                    SidecarDispatcher.noop(),
                    event -> {});
        }
        final RuntimeActionRegistry registry = new RuntimeActionRegistry(
                scheduler, problem -> {}, owner, dev.turboism.permissions.PermissionChecker.allowAll());
        router.register(owner, registry);
        return registry;
    }

    private RuntimeActionCatalogService catalog(final String caller, final List<PluginPermission> grants) {
        return new RuntimeActionCatalogService(
                caller, router, new CubismPermissionGate(caller, grants, event -> {}, CLOCK), new PluginLogger() {
                    @Override
                    public void debug(final String message) {}

                    @Override
                    public void info(final String message) {
                        logLines.add(message);
                    }

                    @Override
                    public void warn(final String message) {
                        logLines.add(message);
                    }

                    @Override
                    public void error(final String message) {}

                    @Override
                    public void error(final String message, final Throwable error) {}
                });
    }

    private static PluginPermission invokeGrant() {
        return new PluginPermission() {
            @Override
            public String id() {
                return PermissionIds.TURBOISM_ACTION_INVOKE;
            }

            @Override
            public String scope() {
                return "application";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }
}
