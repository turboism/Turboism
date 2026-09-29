package dev.turboism.keybinding;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.internal.core.KeybindingService;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.ui.action.RuntimeEditorUiActionRouter;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RuntimeKeybindingServiceTest {

    private static final String PLUGIN_ID = "dev.example.plugin";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path dir;

    private RuntimeScheduler scheduler;
    private RuntimeEditorUiActionRouter router;
    private RuntimeActionRegistry registry;
    private final List<String> diagnostics = new CopyOnWriteArrayList<>();

    @AfterEach
    void shutdown() {
        if (scheduler != null) scheduler.shutdown();
    }

    private Path file() {
        return dir.resolve("keybindings.properties");
    }

    private RuntimeKeybindingService service() {
        if (scheduler == null) {
            scheduler = new RuntimeScheduler(
                    new DefaultWorkBudgetPolicy(),
                    new PluginWorkExecutorRegistry(1, 2, ignored -> {}, CLOCK),
                    SidecarDispatcher.noop(),
                    ignored -> {});
            registry = new RuntimeActionRegistry(scheduler, ignored -> {}, PLUGIN_ID, PermissionChecker.allowAll());
            router = new RuntimeEditorUiActionRouter();
            router.register(PLUGIN_ID, registry);
        }
        return new RuntimeKeybindingService(
                file(), router, id -> id.equals(PLUGIN_ID) ? "Example" : id, diagnostics::add);
    }

    private KeybindingService.Row row(final KeybindingService service, final String rowId) {
        return service.snapshot().stream()
                .filter(candidate -> candidate.id().equals(rowId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing row " + rowId));
    }

    @Test
    void snapshotListsPluginActionsAndSeededNatives() {
        service();
        registry.register("example.run", ActionRegistry.Action.of("example.run", "Run", "Ctrl+T", context -> {}));
        RuntimeKeybindingService service = service();
        service.load();

        List<KeybindingService.Row> rows = service.snapshot();
        KeybindingService.Row plugin = row(service, "plugin:" + PLUGIN_ID + "/example.run");
        assertEquals(KeybindingService.Scope.PLUGIN, plugin.scope());
        assertEquals("Example", plugin.owner());
        assertEquals("Ctrl+T", plugin.declaredStroke());
        assertEquals("Ctrl+T", plugin.effectiveStroke());

        KeybindingService.Row save = row(service, "native:save");
        assertEquals(KeybindingService.Scope.NATIVE, save.scope());
        assertEquals("Ctrl+S", save.nativeStroke());
        assertEquals("Ctrl+S", save.effectiveStroke());
        assertFalse(rows.isEmpty());
    }

    @Test
    void bindingPersistsAcrossServiceInstances() {
        service();
        registry.register("example.run", ActionRegistry.Action.of("example.run", "Run", "Ctrl+T", context -> {}));
        RuntimeKeybindingService first = service();
        first.load();
        first.bind("plugin:" + PLUGIN_ID + "/example.run", "F6");
        first.close();

        RuntimeKeybindingService second = service();
        second.load();
        assertEquals("F6", row(second, "plugin:" + PLUGIN_ID + "/example.run").effectiveStroke());
        second.close();
    }

    @Test
    void disableAndResetRoundTrip() {
        service();
        registry.register("example.run", ActionRegistry.Action.of("example.run", "Run", "Ctrl+T", context -> {}));
        RuntimeKeybindingService service = service();
        service.load();

        service.disable("plugin:" + PLUGIN_ID + "/example.run");
        KeybindingService.Row disabled = row(service, "plugin:" + PLUGIN_ID + "/example.run");
        assertTrue(disabled.disabled());
        assertEquals("", disabled.effectiveStroke());

        service.reset("plugin:" + PLUGIN_ID + "/example.run");
        assertEquals(
                "Ctrl+T", row(service, "plugin:" + PLUGIN_ID + "/example.run").effectiveStroke());
    }

    @Test
    void nativeRowsBindAndSuppress() {
        RuntimeKeybindingService service = service();
        service.load();
        service.bind("native:save", "F2");
        KeybindingService.Row save = row(service, "native:save");
        assertEquals("F2", save.effectiveStroke());
        assertEquals("Ctrl+S", save.nativeStroke());

        service.reset("native:save");
        assertEquals("Ctrl+S", row(service, "native:save").effectiveStroke());
    }

    @Test
    void customNativeCommandsAddAndRemove() {
        RuntimeKeybindingService service = service();
        service.load();
        String rowId = service.addNativeCommand("My Command", "Ctrl+F9");
        KeybindingService.Row row = row(service, rowId);
        assertEquals(KeybindingService.Scope.NATIVE, row.scope());
        assertEquals("Ctrl+F9", row.nativeStroke());
        assertTrue(row.removable());

        service.bind(rowId, "F10");
        RuntimeKeybindingService second = service();
        second.load();
        assertEquals("F10", row(second, rowId).effectiveStroke());
        assertEquals("Ctrl+F9", row(second, rowId).nativeStroke());

        second.removeRow(rowId);
        assertTrue(
                second.snapshot().stream().noneMatch(candidate -> candidate.id().equals(rowId)));
    }

    @Test
    void builtInNativeRowsAreNotRemovable() {
        RuntimeKeybindingService service = service();
        service.load();
        assertThrows(IllegalStateException.class, () -> service.removeRow("native:save"));
    }

    @Test
    void invalidStrokeTextIsRejected() {
        RuntimeKeybindingService service = service();
        service.load();
        assertThrows(IllegalArgumentException.class, () -> service.bind("native:save", "Ctrl+"));
        assertThrows(IllegalArgumentException.class, () -> service.setNativeStroke("native:save", "nonsense"));
        assertThrows(IllegalArgumentException.class, () -> service.bind("native:missing", "F1"));
        assertThrows(IllegalArgumentException.class, () -> service.setNativeStroke("plugin:x/y", "F1"));
    }

    @Test
    void conflictMarksLosingRow() {
        service();
        registry.register("example.run", ActionRegistry.Action.of("example.run", "Run", context -> {}));
        RuntimeKeybindingService service = service();
        service.load();
        service.bind("plugin:" + PLUGIN_ID + "/example.run", "Ctrl+S");
        assertTrue(row(service, "native:save").conflict());
        assertFalse(row(service, "plugin:" + PLUGIN_ID + "/example.run").conflict());
    }

    @Test
    void suspendInterceptionNests() {
        RuntimeKeybindingService service = service();
        var first = service.suspendInterception();
        var second = service.suspendInterception();
        first.close();
        second.close();
        first.close(); // idempotent
    }

    @Test
    void nativeStrokeOverridePersists() {
        RuntimeKeybindingService first = service();
        first.load();
        first.setNativeStroke("native:save", "Ctrl+Alt+S");
        first.close();

        RuntimeKeybindingService second = service();
        second.load();
        assertEquals("Ctrl+Alt+S", row(second, "native:save").nativeStroke());
        assertEquals("Ctrl+Alt+S", row(second, "native:save").effectiveStroke());
        second.close();
    }
}
