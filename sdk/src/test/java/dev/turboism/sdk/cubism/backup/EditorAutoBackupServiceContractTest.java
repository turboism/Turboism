package dev.turboism.sdk.cubism.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectContentSnapshot;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.plugin.PluginContext;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

final class EditorAutoBackupServiceContractTest {

    @Test
    void pluginContextDefaultsToTheTypedUnavailableBackupSingleton() throws Exception {
        assertTrue(Arrays.stream(PluginContext.class.getMethods())
                .noneMatch(method -> method.getName().equals("backup")));

        final PluginContext context = (PluginContext) java.lang.reflect.Proxy.newProxyInstance(
                PluginContext.class.getClassLoader(),
                new Class<?>[] {PluginContext.class},
                (proxy, method, args) -> method.isDefault() ? invokeDefault(proxy, method, args) : null);
        assertSame(
                EditorAutoBackupService.unavailable(),
                context.services().find(EditorAutoBackupService.class).orElse(EditorAutoBackupService.unavailable()));
    }

    @Test
    void serviceSurfaceIsStableAndFailsClosed() {
        assertEquals(
                List.of(
                        "artifacts",
                        "backupAfterSave",
                        "backupNow",
                        "isAvailable",
                        "registerSyncTarget",
                        "settings",
                        "statuses",
                        "unavailable",
                        "updateSettings"),
                Arrays.stream(EditorAutoBackupService.class.getDeclaredMethods())
                        .filter(method -> Modifier.isPublic(method.getModifiers()))
                        .map(Method::getName)
                        .sorted()
                        .toList());

        final EditorAutoBackupService service = EditorAutoBackupService.unavailable();
        assertThrows(UnsupportedOperationException.class, service::settings);
        assertThrows(UnsupportedOperationException.class, service::statuses);
        assertThrows(UnsupportedOperationException.class, service::artifacts);
        assertThrows(
                UnsupportedOperationException.class,
                () -> service.updateSettings(new EditorAutoBackupSettings(true, 5, 50, java.util.Optional.empty())));
        final CompletionStage<BackupRunResult> stage = service.backupNow();
        assertTrue(stage.toCompletableFuture().isDone());
        assertTrue(stage.toCompletableFuture().isCompletedExceptionally());
        service.registerSyncTarget(BackupSyncTarget.noop()).close();

        final CompletionStage<BackupRunResult> afterSave = service.backupAfterSave(new ProjectContentSnapshot(
                "model:1", "model.cmo3", ProjectContentKind.MODEL, java.util.Optional.empty(), List.of()));
        assertTrue(afterSave.toCompletableFuture().isDone());
        assertTrue(afterSave.toCompletableFuture().isCompletedExceptionally());
    }

    @Test
    void settingsRecordValidatesHostRanges() {
        final java.util.Optional<String> noDir = java.util.Optional.empty();
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 0, 50, noDir));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 1441, 50, noDir));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 5, 0, noDir));
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupSettings(true, 5, 1_048_577, noDir));
        new EditorAutoBackupSettings(true, 1, 1, noDir);
        new EditorAutoBackupSettings(true, 1440, 1_048_576, java.util.Optional.of("backup"));
    }

    @Test
    void statusAndEventRecordsAreImmutableProjections() {
        final EditorAutoBackupStatus status = new EditorAutoBackupStatus("model.cmo3", 1000L, 900L, true);
        assertEquals("model.cmo3", status.documentName());
        assertEquals(1000L, status.lastAutoBackupTimeMillis());
        assertThrows(IllegalArgumentException.class, () -> new EditorAutoBackupStatus(" ", 0, 0, false));

        final BackupArtifactHandle handle = new StubHandle();
        final BackupRunResult result = new BackupRunResult(42L, List.of(handle), List.of(status));
        assertEquals(42L, result.completedAtMillis());
        assertEquals(List.of(handle), result.artifacts());
        assertEquals(
                "model_backup2026_08_08_1200.cmo3", result.artifacts().get(0).fileName());
        assertEquals(128L, result.artifacts().get(0).sizeBytes());
        assertTrue(result.artifacts().get(0).temporary());

        final BackupCompletedEvent event = new BackupCompletedEvent(
                42L,
                List.of(new BackupArtifact("model_backup2026_08_08_1200.cmo3", 128L, true)),
                List.of(new BackupDocumentStatus("model.cmo3", 1000L, 900L, true)));
        assertEquals(
                "model_backup2026_08_08_1200.cmo3", event.artifacts().get(0).fileName());
        assertEquals(128L, event.artifacts().get(0).sizeBytes());
        assertThrows(NullPointerException.class, () -> new BackupCompletedEvent(0L, null, List.of()));
        assertThrows(NullPointerException.class, () -> new BackupCompletedEvent(0L, List.of(), null));
        assertThrows(IllegalArgumentException.class, () -> new BackupArtifact("../secret", 1L, false));
    }

    /** Minimal handle stub: metadata only; reads are never exercised here. */
    private static final class StubHandle implements BackupArtifactHandle {
        @Override
        public BackupArtifact artifact() {
            return new BackupArtifact("model_backup2026_08_08_1200.cmo3", 128L, true);
        }

        @Override
        public long lastModifiedMillis() {
            return 42L;
        }

        @Override
        public InputStream openStream() throws IOException {
            throw new CubismPermissionException("stub handle grants nothing");
        }

        @Override
        public void discard() throws IOException {
            throw new CubismPermissionException("stub handle grants nothing");
        }
    }

    private static Object invokeDefault(final Object proxy, final Method method, final Object[] args) throws Throwable {
        final java.lang.invoke.MethodHandles.Lookup lookup = java.lang.invoke.MethodHandles.privateLookupIn(
                method.getDeclaringClass(), java.lang.invoke.MethodHandles.lookup());
        return lookup.unreflectSpecial(method, method.getDeclaringClass())
                .bindTo(proxy)
                .invokeWithArguments(args == null ? new Object[0] : args);
    }
}
