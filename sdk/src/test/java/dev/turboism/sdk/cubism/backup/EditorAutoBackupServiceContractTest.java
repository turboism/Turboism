package dev.turboism.sdk.cubism.backup;

import dev.turboism.sdk.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class EditorAutoBackupServiceContractTest {

    @Test
    void pluginContextExposesTypedBackupServiceFailingClosed() throws Exception {
        final Method accessor = Arrays.stream(PluginContext.class.getMethods())
            .filter(method -> method.getName().equals("backup"))
            .findFirst()
            .orElseThrow();

        assertTrue(accessor.isDefault());
        assertEquals(EditorAutoBackupService.class, accessor.getReturnType());
        assertEquals(0, accessor.getParameterCount());

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
            PluginContext.class.getClassLoader(),
            new Class<?>[]{PluginContext.class},
            (proxy, method, args) -> method.isDefault()
                ? invokeDefault(proxy, method, args)
                : null
        );
        final UnsupportedOperationException unavailable = assertThrows(
            UnsupportedOperationException.class,
            context::backup
        );
        assertEquals("auto-backup service is not available", unavailable.getMessage());
    }

    @Test
    void serviceSurfaceIsStableAndFailsClosed() {
        assertEquals(
            List.of("backupNow", "registerSyncTarget", "settings", "statuses", "unavailable", "updateSettings"),
            Arrays.stream(EditorAutoBackupService.class.getDeclaredMethods())
                .filter(method -> Modifier.isPublic(method.getModifiers()))
                .map(Method::getName)
                .sorted()
                .toList()
        );

        final EditorAutoBackupService service = EditorAutoBackupService.unavailable();
        assertThrows(UnsupportedOperationException.class, service::settings);
        assertThrows(UnsupportedOperationException.class, service::statuses);
        assertThrows(UnsupportedOperationException.class, () -> service.updateSettings(
            new EditorAutoBackupSettings(true, 5, 50, null)));
        final CompletionStage<BackupCompletedEvent> stage = service.backupNow();
        assertTrue(stage.toCompletableFuture().isDone());
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally());
        service.registerSyncTarget(BackupSyncTarget.noop()).close();
    }

    @Test
    void settingsRecordValidatesHostRanges() {
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 0, 50, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 1441, 50, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 5, 0, null));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 5, 1_048_577, null));
        new EditorAutoBackupSettings(true, 1, 1, null);
        new EditorAutoBackupSettings(true, 1440, 1_048_576, "backup");
    }

    @Test
    void statusAndEventRecordsAreImmutableProjections() {
        final EditorAutoBackupStatus status = new EditorAutoBackupStatus(
            "model.cmo3", "C:/backup/model.cmo3", 1000L, 900L, true
        );
        assertEquals("model.cmo3", status.documentName());
        assertEquals(1000L, status.lastAutoBackupTimeMillis());
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupStatus(" ", null, 0, 0, false));

        final File artifact = new File("backup/model_backup2026_08_08_1200.cmo3");
        final BackupCompletedEvent event = new BackupCompletedEvent(
            42L, List.of(artifact), List.of(status)
        );
        assertEquals(42L, event.completedAtMillis());
        assertEquals(List.of(artifact), event.newBackupFiles());
        assertThrows(NullPointerException.class, () -> new BackupCompletedEvent(0L, null, List.of()));
        assertThrows(NullPointerException.class, () -> new BackupCompletedEvent(0L, List.of(), null));
    }

    private static Object invokeDefault(
        final Object proxy,
        final Method method,
        final Object[] args
    ) throws Throwable {
        final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
            method.getDeclaringClass(),
            java.lang.invoke.MethodHandles.lookup()
        );
        return lookup.unreflectSpecial(method, method.getDeclaringClass())
            .bindTo(proxy)
            .invokeWithArguments(args == null ? new Object[0] : args);
    }
}
