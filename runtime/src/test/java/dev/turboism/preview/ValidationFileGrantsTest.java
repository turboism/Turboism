package dev.turboism.preview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorConfiguration;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.home.PluginHomePaths;
import dev.turboism.sdk.cubism.command.EditorFileCommand;
import dev.turboism.sdk.cubism.command.EditorFileCommandRequest;
import dev.turboism.sdk.cubism.command.EditorOverwritePolicy;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.sdk.ui.UserFileRequestStatus;
import dev.turboism.task.RuntimePluginTaskScheduler;
import dev.turboism.userfile.RuntimeUserFileAccessService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * T014 persistence-save grant policy: the pinned grant only ever exists for
 * the exact validation plugin/request/mode, and it resolves through the real
 * RuntimeUserFileAccessService + EditorFileCommandResolver chain, never just
 * the helper's return value.
 */
class ValidationFileGrantsTest {

    @Test
    void nativeCharacterSaveIsPinnedAndCannotUseOtherProfiles() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                ValidationFileGrants.CHARACTER_REQUEST_ID,
                "Save character",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "native-character-save");
        try (Harness h = harness(ValidationFileGrants.CHARACTER_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        for (String plugin : List.of(ValidationFileGrants.PLUGIN_ID, ValidationFileGrants.APPLE_PLUGIN_ID)) {
            try (Harness h = harness(plugin, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "persistence-save");
        try (Harness h = harness(ValidationFileGrants.CHARACTER_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service
                            .request(new UserFileRequest(
                                    ValidationFileGrants.REQUEST_ID,
                                    "Other profile",
                                    List.of("cmo3"),
                                    UserFileMode.WRITE,
                                    UserFileLifetime.ONE_OPERATION))
                            .toCompletableFuture()
                            .get()
                            .status());
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "native-character-save");
        try (Harness h = harness(ValidationFileGrants.CHARACTER_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var granted = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, granted.status());
            assertEquals(
                    paths.stateDir()
                            .resolve("persistence")
                            .toRealPath()
                            .resolve(ValidationFileGrants.CHARACTER_FILE_NAME),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS,
                                    granted.handle().orElseThrow(),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.CHARACTER_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.CHARACTER_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void catSaveRequiresExactOptInModePluginRequestAndUnusedTarget() throws Exception {
        catSavePolicy("cat-vertex-save");
    }

    @Test
    void optimizedCatSaveRequiresExactOptInModePluginRequestAndUnusedTarget() throws Exception {
        catSavePolicy("cat-optimized-save");
    }

    @Test
    void defaultSerializationCatSaveRequiresExactOptInModePluginRequestAndUnusedTarget() throws Exception {
        catSavePolicy("cat-default-serialization");
    }

    @Test
    void adaptiveCatSaveRequiresExactOptInModePluginRequestAndUnusedTarget() throws Exception {
        catSavePolicy("cat-adaptive-save");
    }

    @Test
    void costCatSaveRequiresExactOptInModePluginRequestAndUnusedTarget() throws Exception {
        catSavePolicy("cat-cost-save");
    }

    private void catSavePolicy(String saveMode) throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                "mi3d-cat-menu-save", "Save Cat", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, saveMode);
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        for (var mode : List.of("cat-vertex-menu", "cat-vertex-frames", "cat-vertex-reopen")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, saveMode);
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var grant = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, grant.status());
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve("mi3d-cat-model.cmo3"),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS,
                                    grant.handle().orElseThrow(),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        Files.writeString(paths.stateDir().resolve("persistence/mi3d-cat-model.cmo3"), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void derivedSaveRequiresOwnProfilePluginAndUnusedTarget() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save derived Apple",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-derived-save");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        for (var mode : List.of("apple-derived-edit", "apple-derived-reopen")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-derived-save");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var granted = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, granted.status());
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.APPLE_FILE_NAME),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS,
                                    granted.handle().orElseThrow(),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.APPLE_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void partitionBoundSaveRequiresExactFlagsRangePluginAndUnusedTarget() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save bound partition",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        var properties = java.util.Map.of(
                ValidationFileGrants.SAVE_PROPERTY,
                "true",
                "turboism.validation.mi3d.equivalentSlotProbe",
                "true",
                "turboism.validation.mi3d.partitionBoundProbe",
                "true",
                "turboism.validation.mi3d.meshStartIndex",
                "26",
                "turboism.validation.mi3d.meshEndIndex",
                "27");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-all-mesh-edit");
        try {
            properties.forEach(System::setProperty);
            for (var property : properties.entrySet()) {
                System.setProperty(property.getKey(), "false");
                try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                    assertEquals(
                            UserFileRequestStatus.UNAVAILABLE,
                            h.service
                                    .request(request)
                                    .toCompletableFuture()
                                    .get()
                                    .status());
                }
                System.setProperty(property.getKey(), property.getValue());
            }
            try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                var granted = h.service.request(request).toCompletableFuture().get();
                assertEquals(UserFileRequestStatus.GRANTED, granted.status());
                assertEquals(
                        paths.stateDir()
                                .resolve("persistence")
                                .toRealPath()
                                .resolve(ValidationFileGrants.APPLE_FILE_NAME),
                        h.service
                                .resolve(new EditorFileCommandRequest(
                                        EditorFileCommand.SAVE_AS,
                                        granted.handle().orElseThrow(),
                                        EditorOverwritePolicy.REJECT_EXISTING))
                                .file());
            }
            Files.writeString(
                    paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.APPLE_FILE_NAME), "protected");
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        } finally {
            properties.keySet().forEach(System::clearProperty);
        }
    }

    @Test
    void interpolationSaveIsPinnedAndCannotCrossModeOrPlugin() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                "mi3d-interpolation-save",
                "Save interpolation",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "interpolation-witness-save");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var grant = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, grant.status());
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve("mi3d-interpolation-model.cmo3"),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS,
                                    grant.handle().orElseThrow(),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "interpolation-witness-reopen");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "interpolation-witness-save");
        Files.writeString(paths.stateDir().resolve("persistence/mi3d-interpolation-model.cmo3"), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void originalMeshCommitSaveRequiresEveryDedicatedFlagAndExactRequest() throws Exception {
        final PluginHomePaths paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        final var request = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save edited apple",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        final List<String> flags = List.of(
                ValidationFileGrants.SAVE_PROPERTY,
                "turboism.validation.mi3d.meshEditingProbe",
                "turboism.validation.mi3d.originalMeshEditing",
                "turboism.validation.mi3d.meshEditingCommitSave");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-editability-compact");
        try {
            flags.forEach(flag -> System.setProperty(flag, "true"));
            for (String missing : flags) {
                System.clearProperty(missing);
                try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                    assertEquals(
                            UserFileRequestStatus.UNAVAILABLE,
                            h.service
                                    .request(request)
                                    .toCompletableFuture()
                                    .get()
                                    .status());
                }
                System.setProperty(missing, "true");
            }
            try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(new UserFileRequest(
                                        "other-target",
                                        "Other",
                                        List.of("cmo3"),
                                        UserFileMode.WRITE,
                                        UserFileLifetime.ONE_OPERATION))
                                .toCompletableFuture()
                                .get()
                                .status());
                final var granted =
                        h.service.request(request).toCompletableFuture().get();
                assertEquals(UserFileRequestStatus.GRANTED, granted.status());
                granted.handle().orElseThrow().close();
            }
        } finally {
            flags.forEach(System::clearProperty);
        }
    }

    @Test
    void xyzMenuSavePinsTargetAndRequiresExactLaunchPluginAndRequest() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                "mi3d-xyz-menu-save", "Save XYZ", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "cube-xyz-menu-save");
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty("turboism.validation.mi3d.xyzProbe", "true");
        try {
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(cubeRequest())
                                .toCompletableFuture()
                                .get()
                                .status());
                var grant = h.service.request(request).toCompletableFuture().get();
                assertEquals(UserFileRequestStatus.GRANTED, grant.status());
                var handle = grant.handle().orElseThrow();
                assertEquals(
                        paths.stateDir().resolve("persistence").toRealPath().resolve("mi3d-xyz-model.cmo3"),
                        h.service
                                .resolve(new EditorFileCommandRequest(
                                        EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING))
                                .file());
            }
            for (String plugin : List.of(ValidationFileGrants.PLUGIN_ID, "other-plugin"))
                try (Harness h = harness(plugin, paths, WRITE_PERMISSIONS)) {
                    assertEquals(
                            UserFileRequestStatus.UNAVAILABLE,
                            h.service
                                    .request(request)
                                    .toCompletableFuture()
                                    .get()
                                    .status());
                }
            Files.writeString(paths.stateDir().resolve("persistence/mi3d-xyz-model.cmo3"), "protected");
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        } finally {
            System.clearProperty("turboism.validation.mi3d.xyzProbe");
        }
    }

    private static final Set<String> WRITE_PERMISSIONS = Set.of(
            PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST,
            PermissionIds.TURBOISM_FILE_WRITE,
            PermissionIds.TURBOISM_CUBISM_MODEL_WRITE);

    @TempDir
    Path home;

    @AfterEach
    void clearProperties() {
        System.clearProperty(ValidationFileGrants.SAVE_PROPERTY);
        System.clearProperty(ValidationFileGrants.MODE_PROPERTY);
    }

    private PluginHomePaths paths() throws Exception {
        Path state = home.resolve("plugin/state");
        Files.createDirectories(state);
        return new PluginHomePaths(
                home.resolve("plugin/config"), home.resolve("plugin/data"), home.resolve("plugin/cache"), state);
    }

    private Harness harness(String pluginId, PluginHomePaths paths, Set<String> permissions) {
        return new Harness(pluginId, permissions, ValidationFileGrants.source(pluginId, paths));
    }

    private static UserFileRequest request() {
        return new UserFileRequest(
                ValidationFileGrants.REQUEST_ID,
                "MI3D persistence save",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
    }

    private static UserFileRequest cubeRequest() {
        return new UserFileRequest(
                ValidationFileGrants.CUBE_REQUEST_ID,
                "Cube persistence save",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
    }

    @Test
    void appleMenuSaveIsExplicitAndCannotCrossPluginOrMode() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var apple = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save apple",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-save");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(apple).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var result = h.service.request(apple).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, result.status());
            var handle = result.handle().orElseThrow();
            var resolved = h.service.resolve(new EditorFileCommandRequest(
                    EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.APPLE_FILE_NAME),
                    resolved.file());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new EditorFileCommandRequest(
                            EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
        }
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(apple).toCompletableFuture().get().status());
        }
        for (String mode : List.of("cube-persistence-save", "persistence-save", "apple-menu-reopen", "")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(apple).toCompletableFuture().get().status());
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(cubeRequest())
                                .toCompletableFuture()
                                .get()
                                .status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-save");
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.APPLE_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(apple).toCompletableFuture().get().status());
        }
    }

    @Test
    void compactAppleSaveUsesSamePinnedTargetAndRejectsLegacyPlugin() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-save-compact");
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        var request = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save apple",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            var grant = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, grant.status());
            var handle = grant.handle().orElseThrow();
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.APPLE_FILE_NAME),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void appleAtlasSaveIsPinnedAndExportOrReopenCannotWrite() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                ValidationFileGrants.APPLE_REQUEST_ID,
                "Save packed apple",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-atlas-save-compact");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
            var grant = h.service.request(request).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, grant.status());
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.APPLE_FILE_NAME),
                    h.service
                            .resolve(new EditorFileCommandRequest(
                                    EditorFileCommand.SAVE_AS,
                                    grant.handle().orElseThrow(),
                                    EditorOverwritePolicy.REJECT_EXISTING))
                            .file());
        }
        for (String plugin : List.of(ValidationFileGrants.PLUGIN_ID, "dev.turboism.plugin.model-import-3d"))
            try (Harness h = harness(plugin, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        for (String mode :
                List.of("apple-menu-export-compact", "apple-menu-atlas-reopen-compact", "apple-menu-reopen-compact")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "apple-menu-atlas-save-compact");
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.APPLE_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void surfaceSaveIsPinnedAndCannotCrossModesOrPlugins() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        var request = new UserFileRequest(
                ValidationFileGrants.SURFACE_REQUEST_ID,
                "Save surface",
                List.of("cmo3"),
                UserFileMode.WRITE,
                UserFileLifetime.ONE_OPERATION);
        for (String mode :
                List.of("plane-menu-save", "seam-menu-save", "plane-menu-atlas-save", "seam-menu-atlas-save")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            System.clearProperty(ValidationFileGrants.SAVE_PROPERTY);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
            System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(cubeRequest())
                                .toCompletableFuture()
                                .get()
                                .status());
                var apple = new UserFileRequest(
                        ValidationFileGrants.APPLE_REQUEST_ID,
                        "Save apple",
                        List.of("cmo3"),
                        UserFileMode.WRITE,
                        UserFileLifetime.ONE_OPERATION);
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(apple).toCompletableFuture().get().status());
                var grant = h.service.request(request).toCompletableFuture().get();
                assertEquals(UserFileRequestStatus.GRANTED, grant.status());
                var handle = grant.handle().orElseThrow();
                assertEquals(
                        paths.stateDir()
                                .resolve("persistence")
                                .toRealPath()
                                .resolve(ValidationFileGrants.SURFACE_FILE_NAME),
                        h.service
                                .resolve(new EditorFileCommandRequest(
                                        EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING))
                                .file());
                org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalArgumentException.class,
                        () -> new EditorFileCommandRequest(
                                EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
            }
            for (String plugin : List.of(ValidationFileGrants.PLUGIN_ID, "dev.turboism.plugin.model-import-3d"))
                try (Harness h = harness(plugin, paths, WRITE_PERMISSIONS)) {
                    assertEquals(
                            UserFileRequestStatus.UNAVAILABLE,
                            h.service
                                    .request(request)
                                    .toCompletableFuture()
                                    .get()
                                    .status());
                }
        }
        for (String mode : List.of(
                "plane-menu",
                "seam-menu",
                "plane-menu-reopen",
                "seam-menu-reopen",
                "plane-menu-export",
                "seam-menu-export",
                "plane-menu-atlas-reopen",
                "seam-menu-atlas-reopen",
                "apple-menu-save-compact")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service.request(request).toCompletableFuture().get().status());
            }
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "plane-menu-save");
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.SURFACE_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.APPLE_PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request).toCompletableFuture().get().status());
        }
    }

    @Test
    void atlasSaveGrantRequiresOptInAndReopenNeverGrants() throws Exception {
        var paths = paths();
        // CubePersistenceFile creates this task-local parent before requesting a grant.
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "cube-atlas-save");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request()).toCompletableFuture().get().status());
            var result = h.service.request(cubeRequest()).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, result.status());
            result.handle().orElseThrow().close();
        }
        try (Harness h = harness("dev.turboism.plugin.model-import-3d", paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "cube-atlas-reopen");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
    }

    @Test
    void cubeGrantUsesItsOwnPinnedTargetAndCannotCrossModes() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "cube-persistence-save");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(request()).toCompletableFuture().get().status());
            var result = h.service.request(cubeRequest()).toCompletableFuture().get();
            assertEquals(UserFileRequestStatus.GRANTED, result.status());
            var handle = result.handle().orElseThrow();
            var resolved = h.service.resolve(new EditorFileCommandRequest(
                    EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.CUBE_FILE_NAME),
                    resolved.file());
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new EditorFileCommandRequest(
                            EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
        }
        for (String mode : List.of("persistence-save", "cube-persistence-reopen", "whole-import", "cube-render", "")) {
            System.setProperty(ValidationFileGrants.MODE_PROPERTY, mode);
            try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(cubeRequest())
                                .toCompletableFuture()
                                .get()
                                .status());
            }
        }
    }

    @Test
    void cubeGrantRejectsMissingOptInExistingFileAndCrossPlugin() throws Exception {
        var paths = paths();
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "cube-persistence-save");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        try (Harness h = harness("another.plugin", paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        Files.writeString(
                paths.stateDir().resolve("persistence").resolve(ValidationFileGrants.CUBE_FILE_NAME), "protected");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service.request(cubeRequest()).toCompletableFuture().get().status());
        }
    }

    @Test
    void grantsExactlyThePinnedSaveTargetThroughTheRealChain() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "persistence-save");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            var result = h.service.request(request()).toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(UserFileRequestStatus.GRANTED, result.status());
            var handle = result.handle().orElseThrow();
            var resolved = h.service.resolve(new EditorFileCommandRequest(
                    EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
            assertNotNull(resolved);
            // The resolver returns the canonical parent realPath + file name;
            // the target itself does not exist yet (SAVE_AS creates it).
            assertEquals(
                    paths.stateDir().resolve("persistence").toRealPath().resolve(ValidationFileGrants.FILE_NAME),
                    resolved.file());
            // One-operation consumption: the spent handle is no longer ACTIVE.
            org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalArgumentException.class,
                    () -> new EditorFileCommandRequest(
                            EditorFileCommand.SAVE_AS, handle, EditorOverwritePolicy.REJECT_EXISTING));
        }
    }

    @Test
    void unavailableWithoutBothOptInProperties() throws Exception {
        var paths = paths();
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            // Neither property set.
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service
                            .request(request())
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
            System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
            // Mode property still missing.
            try (Harness partial = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        partial.service
                                .request(request())
                                .toCompletableFuture()
                                .get(2, TimeUnit.SECONDS)
                                .status());
            }
        }
    }

    @Test
    void unavailableForOtherPluginOrRequestShape() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "persistence-save");
        try (Harness other = harness("dev.turboism.other-plugin", paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    other.service
                            .request(request())
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
        }
        // FILE_READ included so READ-mode requests reach the grant layer instead
        // of being denied earlier by the permission gate.
        var bothModes = new java.util.HashSet<>(WRITE_PERMISSIONS);
        bothModes.add(PermissionIds.TURBOISM_FILE_READ);
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, bothModes)) {
            for (UserFileRequest wrong : List.of(
                    new UserFileRequest(
                            "other-request", "x", List.of("cmo3"), UserFileMode.WRITE, UserFileLifetime.ONE_OPERATION),
                    new UserFileRequest(
                            ValidationFileGrants.REQUEST_ID,
                            "x",
                            List.of("cmo3"),
                            UserFileMode.READ,
                            UserFileLifetime.ONE_OPERATION),
                    new UserFileRequest(
                            ValidationFileGrants.REQUEST_ID,
                            "x",
                            List.of("cmo3"),
                            UserFileMode.WRITE,
                            UserFileLifetime.UNTIL_DISABLE),
                    new UserFileRequest(
                            ValidationFileGrants.REQUEST_ID,
                            "x",
                            List.of("can3"),
                            UserFileMode.WRITE,
                            UserFileLifetime.ONE_OPERATION))) {
                assertEquals(
                        UserFileRequestStatus.UNAVAILABLE,
                        h.service
                                .request(wrong)
                                .toCompletableFuture()
                                .get(2, TimeUnit.SECONDS)
                                .status());
            }
        }
    }

    @Test
    void unavailableWhenTargetAlreadyExistsOrParentIsLinked() throws Exception {
        var paths = paths();
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "persistence-save");
        Path persistence = paths.stateDir().resolve("persistence");
        Files.createDirectories(persistence);
        Path target = persistence.resolve(ValidationFileGrants.FILE_NAME);
        Files.writeString(target, "pre-existing");
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service
                            .request(request())
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
        }
        Files.delete(target);
        Files.delete(persistence);
        // A symlinked persistence directory must never be granted through.
        Path real = home.resolve("elsewhere");
        Files.createDirectories(real);
        try {
            Files.createSymbolicLink(persistence, real);
        } catch (UnsupportedOperationException | java.io.IOException failure) {
            return; // platform without symlink support cannot express this case
        }
        try (Harness h = harness(ValidationFileGrants.PLUGIN_ID, paths, WRITE_PERMISSIONS)) {
            assertEquals(
                    UserFileRequestStatus.UNAVAILABLE,
                    h.service
                            .request(request())
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
        }
    }

    @Test
    void requestStillRequiresDeclaredPermissions() throws Exception {
        var paths = paths();
        Files.createDirectories(paths.stateDir().resolve("persistence"));
        System.setProperty(ValidationFileGrants.SAVE_PROPERTY, "true");
        System.setProperty(ValidationFileGrants.MODE_PROPERTY, "persistence-save");
        try (Harness h = harness(
                ValidationFileGrants.PLUGIN_ID, paths, Set.of(PermissionIds.TURBOISM_UI_FILE_CHOOSER_REQUEST))) {
            assertEquals(
                    UserFileRequestStatus.DENIED,
                    h.service
                            .request(request())
                            .toCompletableFuture()
                            .get(2, TimeUnit.SECONDS)
                            .status());
        }
    }

    private static final class Harness implements AutoCloseable {
        final RuntimeUserFileAccessService service;
        private final DisposableScope scope;
        private final RuntimeScheduler scheduler;

        Harness(String pluginId, Set<String> permissions, dev.turboism.userfile.UserFileGrantSource source) {
            this.scope = new DisposableScope();
            this.scheduler = new RuntimeScheduler(
                    new DefaultWorkBudgetPolicy(),
                    new PluginWorkExecutorRegistry(
                            PluginWorkExecutorConfiguration.of(500L, 1, 32, 50.0f)
                                    .withShutdownTimeout(java.time.Duration.ofMillis(20)),
                            ignored -> {},
                            Clock.systemUTC()),
                    SidecarDispatcher.noop(),
                    ignored -> {});
            this.service = new RuntimeUserFileAccessService(
                    pluginId, permissions, source, new RuntimePluginTaskScheduler(pluginId, scheduler, scope), scope);
        }

        @Override
        public void close() throws Exception {
            scope.close();
            if (!scheduler.isClosed()) scheduler.shutdown();
        }
    }
}
