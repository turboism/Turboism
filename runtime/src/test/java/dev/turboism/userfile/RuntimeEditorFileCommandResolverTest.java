package dev.turboism.userfile;

import dev.turboism.adapter.cubism.command.ResolvedEditorFileCommand;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.task.RuntimePluginTaskScheduler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class RuntimeEditorFileCommandResolverTest {
    @TempDir Path temporary;

    @Test
    void resolvesOnlyOwnedActiveMatchingGrantsAndConsumesOneOperationHandles() throws Exception {
        Path selected = temporary.resolve("model.cmo3");
        Files.writeString(selected, "fixture");
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 32, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        try {
            RuntimeUserFileAccessService service = new RuntimeUserFileAccessService(
                "test.plugin",
                Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_READ),
                UserFileGrantSource.fixedSelection(selected),
                new RuntimePluginTaskScheduler("test.plugin", scheduler, scope),
                scope
            );
            var handle = service.request(new UserFileRequest(
                "open-model", "Open model", List.of("cmo3"), UserFileMode.READ, UserFileLifetime.ONE_OPERATION
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            EditorFileCommandRequest request = new EditorFileCommandRequest(
                EditorFileCommand.OPEN, handle, EditorOverwritePolicy.REJECT_EXISTING
            );

            ResolvedEditorFileCommand resolved = service.resolve(request);

            assertEquals(selected.toRealPath(), resolved.file());
            assertEquals(EditorFileCommand.OPEN, resolved.command());
            assertNull(service.resolve(request));
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }

    @Test
    void rejectsExistingWriteTargetsUnlessReplacementWasExplicitlyApproved() throws Exception {
        Path selected = temporary.resolve("model.cmo3");
        Files.writeString(selected, "existing");
        DisposableScope scope = new DisposableScope();
        RuntimeScheduler scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 32, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        try {
            RuntimeUserFileAccessService service = new RuntimeUserFileAccessService(
                "test.plugin",
                Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_WRITE),
                UserFileGrantSource.fixedSelection(selected),
                new RuntimePluginTaskScheduler("test.plugin", scheduler, scope),
                scope
            );
            var rejectedHandle = service.request(new UserFileRequest(
                "save-model", "Save model", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            assertNull(service.resolve(new EditorFileCommandRequest(
                EditorFileCommand.SAVE_AS, rejectedHandle, EditorOverwritePolicy.REJECT_EXISTING
            )));

            var replacementHandle = service.request(new UserFileRequest(
                "replace-model", "Replace model", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            assertEquals(selected.toRealPath(), service.resolve(new EditorFileCommandRequest(
                EditorFileCommand.SAVE_AS, replacementHandle, EditorOverwritePolicy.REPLACE_EXISTING
            )).file());
        } finally {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }
    @Test
    void rejectsReadTargetsReplacedBySymlinksAfterGrant() throws Exception {
        Path selected = temporary.resolve("model.cmo3");
        Files.writeString(selected, "fixture");
        try (Harness harness = new Harness(Set.of(
            PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_READ
        ), selected)) {
            var handle = harness.service.request(new UserFileRequest(
                "open-model", "Open model", List.of("cmo3"), UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            EditorFileCommandRequest request = new EditorFileCommandRequest(
                EditorFileCommand.OPEN, handle, EditorOverwritePolicy.REJECT_EXISTING
            );
            assertEquals(selected.toRealPath(), harness.service.resolve(request).file());

            Files.delete(selected);
            Path other = temporary.resolve("other.txt");
            Files.writeString(other, "other");
            requireSymlinkSupport(selected, other);
            assertNull(harness.service.resolve(request));
        }
    }

    @Test
    void rejectsReadTargetsReplacedByNonRegularFiles() throws Exception {
        Path selected = temporary.resolve("model.cmo3");
        Files.writeString(selected, "fixture");
        try (Harness harness = new Harness(Set.of(
            PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_READ
        ), selected)) {
            var handle = harness.service.request(new UserFileRequest(
                "open-model", "Open model", List.of("cmo3"), UserFileMode.READ, UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            EditorFileCommandRequest request = new EditorFileCommandRequest(
                EditorFileCommand.OPEN, handle, EditorOverwritePolicy.REJECT_EXISTING
            );

            Files.delete(selected);
            Files.createDirectory(selected);
            assertNull(harness.service.resolve(request));
        }
    }

    @Test
    void rejectsReplaceWriteTargetsReplacedBySymlinksAfterGrant() throws Exception {
        Path selected = temporary.resolve("model.cmo3");
        Files.writeString(selected, "fixture");
        try (Harness harness = new Harness(Set.of(
            PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST, PermissionIds.TURBOISM_FILE_WRITE
        ), selected)) {
            var handle = harness.service.request(new UserFileRequest(
                "save-model", "Save model", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.UNTIL_DISABLE
            )).toCompletableFuture().get(2, TimeUnit.SECONDS).handle().orElseThrow();
            EditorFileCommandRequest replace = new EditorFileCommandRequest(
                EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REPLACE_EXISTING
            );
            assertEquals(selected.toRealPath(), harness.service.resolve(replace).file());

            Files.delete(selected);
            Path other = temporary.resolve("other.txt");
            Files.writeString(other, "other");
            requireSymlinkSupport(selected, other);
            assertNull(harness.service.resolve(replace));
            assertNull(harness.service.resolve(new EditorFileCommandRequest(
                EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING
            )));
        }
    }

    private static void requireSymlinkSupport(final Path link, final Path target) {
        try {
            Files.createSymbolicLink(link, target);
        } catch (UnsupportedOperationException | java.io.IOException exception) {
            Assumptions.abort("symlinks are not supported on this platform");
        }
    }

    private static final class Harness implements AutoCloseable {
        final RuntimeUserFileAccessService service;
        private final DisposableScope scope;
        private final RuntimeScheduler scheduler;

        Harness(final Set<String> permissions, final Path selected) {
            DisposableScope scope = new DisposableScope();
            RuntimeScheduler scheduler = new RuntimeScheduler(
                new DefaultWorkBudgetPolicy(),
                new PluginWorkExecutorRegistry(1, 32, ignored -> { }, Clock.systemUTC()),
                SidecarDispatcher.noop(),
                ignored -> { }
            );
            this.service = new RuntimeUserFileAccessService(
                "test.plugin",
                permissions,
                UserFileGrantSource.fixedSelection(selected),
                new RuntimePluginTaskScheduler("test.plugin", scheduler, scope),
                scope
            );
            this.scope = scope;
            this.scheduler = scheduler;
        }

        @Override
        public void close() throws Exception {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }
}
