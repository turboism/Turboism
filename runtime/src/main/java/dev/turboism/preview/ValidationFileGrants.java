package dev.turboism.preview;

import dev.turboism.home.PluginHomePaths;
import dev.turboism.sdk.ui.UserFileLifetime;
import dev.turboism.sdk.ui.UserFileMode;
import dev.turboism.sdk.ui.UserFileRequest;
import dev.turboism.userfile.UserFileGrantSource;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Validation-only grant pinning for the model-import-3d persistence job (G2
 * 2026-09-28). Within this validation-only source, unless BOTH
 * opt-in properties are set for THIS runtime launch
 * ({@code turboism.validation.mi3d.persistenceSave=true} and
 * {@code turboism.validation.mi3d.mode=persistence-save} or
 * {@code cube-persistence-save} or {@code cube-atlas-save}), every request
 * resolves unavailable. When enabled, only the exact probe plugin, the exact
 * request id, WRITE mode, ONE_OPERATION lifetime and the single {@code cmo3}
 * extension are granted — and only to the fixed path derived from this
 * runtime's own plugin state directory, never from a caller-supplied name.
 * Grant-time path checks are a pre-flight only: the existing
 * use-point/one-operation/REJECT_EXISTING guards still revalidate at execute.
 */
final class ValidationFileGrants {

    static final String PLUGIN_ID = "dev.turboism.validation.model-import-3d";
    static final String REQUEST_ID = "mi3d-t014-persistence-save";
    static final String FILE_NAME = "mi3d-t014-model.cmo3";
    static final String CUBE_REQUEST_ID = "mi3d-cube-persistence-save";
    static final String CUBE_FILE_NAME = "mi3d-cube-model.cmo3";
    static final String APPLE_PLUGIN_ID = "dev.turboism.validation.model-import-3d-user-entry";
    static final String APPLE_REQUEST_ID = "mi3d-apple-menu-save";
    static final String APPLE_FILE_NAME = "mi3d-apple-model.cmo3";
    static final String SURFACE_REQUEST_ID = "mi3d-surface-menu-save";
    static final String SURFACE_FILE_NAME = "mi3d-surface-model.cmo3";
    static final String XYZ_REQUEST_ID = "mi3d-xyz-menu-save";
    static final String XYZ_FILE_NAME = "mi3d-xyz-model.cmo3";
    static final String SAVE_PROPERTY = "turboism.validation.mi3d.persistenceSave";
    static final String MODE_PROPERTY = "turboism.validation.mi3d.mode";

    private ValidationFileGrants() {}

    static UserFileGrantSource source(final String pluginId, final PluginHomePaths paths) {
        Objects.requireNonNull(pluginId, "pluginId");
        if ((!PLUGIN_ID.equals(pluginId) && !APPLE_PLUGIN_ID.equals(pluginId))
                || !"true".equals(System.getProperty(SAVE_PROPERTY))) {
            return UserFileGrantSource.unavailable();
        }
        final String mode = System.getProperty(MODE_PROPERTY, "");
        final String requestId;
        final String fileName;
        switch (mode) {
            case "cat-vertex-save",
                    "cat-optimized-save",
                    "cat-adaptive-save",
                    "cat-cost-save",
                    "cat-default-serialization" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
                requestId = "mi3d-cat-menu-save";
                fileName = "mi3d-cat-model.cmo3";
            }
            case "apple-derived-save" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
                requestId = APPLE_REQUEST_ID;
                fileName = APPLE_FILE_NAME;
            }
            case "apple-all-mesh-edit" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.equivalentSlotProbe"))
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.partitionBoundProbe"))
                        || !"26".equals(System.getProperty("turboism.validation.mi3d.meshStartIndex"))
                        || !"27".equals(System.getProperty("turboism.validation.mi3d.meshEndIndex")))
                    return UserFileGrantSource.unavailable();
                requestId = APPLE_REQUEST_ID;
                fileName = APPLE_FILE_NAME;
            }
            case "apple-menu-editability-compact" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.meshEditingProbe"))
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.originalMeshEditing"))
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.meshEditingCommitSave")))
                    return UserFileGrantSource.unavailable();
                requestId = APPLE_REQUEST_ID;
                fileName = APPLE_FILE_NAME;
            }
            case "interpolation-witness-save" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
                requestId = "mi3d-interpolation-save";
                fileName = "mi3d-interpolation-model.cmo3";
            }
            case "cube-xyz-menu-save" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)
                        || !"true".equals(System.getProperty("turboism.validation.mi3d.xyzProbe")))
                    return UserFileGrantSource.unavailable();
                requestId = XYZ_REQUEST_ID;
                fileName = XYZ_FILE_NAME;
            }
            case "apple-menu-save", "apple-menu-save-compact", "apple-menu-atlas-save-compact" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
                requestId = APPLE_REQUEST_ID;
                fileName = APPLE_FILE_NAME;
            }
            case "plane-menu-save", "seam-menu-save", "plane-menu-atlas-save", "seam-menu-atlas-save" -> {
                if (!APPLE_PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
                requestId = SURFACE_REQUEST_ID;
                fileName = SURFACE_FILE_NAME;
            }
            case "persistence-save" -> {
                requestId = REQUEST_ID;
                fileName = FILE_NAME;
            }
            case "cube-persistence-save", "cube-atlas-save" -> {
                requestId = CUBE_REQUEST_ID;
                fileName = CUBE_FILE_NAME;
            }
            default -> {
                return UserFileGrantSource.unavailable();
            }
        }
        if (!List.of(
                                "apple-menu-save",
                                "apple-menu-save-compact",
                                "apple-menu-atlas-save-compact",
                                "apple-menu-editability-compact",
                                "plane-menu-save",
                                "seam-menu-save",
                                "plane-menu-atlas-save",
                                "seam-menu-atlas-save",
                                "cube-xyz-menu-save",
                                "interpolation-witness-save",
                                "apple-all-mesh-edit",
                                "apple-derived-save",
                                "cat-vertex-save",
                                "cat-optimized-save",
                                "cat-adaptive-save",
                                "cat-cost-save",
                                "cat-default-serialization")
                        .contains(mode)
                && !PLUGIN_ID.equals(pluginId)) return UserFileGrantSource.unavailable();
        final Path stateDir = Objects.requireNonNull(paths, "paths").stateDir();
        return request -> CompletableFuture.completedFuture(grant(request, stateDir, requestId, fileName));
    }

    private static UserFileGrantSource.Decision grant(
            final UserFileRequest request, final Path stateDir, final String requestId, final String fileName) {
        if (!requestId.equals(request.id())
                || request.mode() != UserFileMode.WRITE
                || request.lifetime() != UserFileLifetime.ONE_OPERATION
                || !request.allowedExtensions().equals(List.of("cmo3"))) {
            return UserFileGrantSource.Decision.unavailable();
        }
        try {
            final Path state = stateDir.toAbsolutePath().normalize();
            // The granted target must stay inside the real task home: the whole
            // state path chain must be symlink-free and the persistence
            // directory, if it already exists, must be a real directory.
            for (Path p = state; p != null; p = p.getParent()) {
                if (Files.isSymbolicLink(p)) {
                    return UserFileGrantSource.Decision.unavailable();
                }
            }
            if (!Files.isDirectory(state, LinkOption.NOFOLLOW_LINKS)) {
                return UserFileGrantSource.Decision.unavailable();
            }
            final Path parent = state.resolve("persistence");
            if (Files.exists(parent, LinkOption.NOFOLLOW_LINKS)
                    && !Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) {
                return UserFileGrantSource.Decision.unavailable();
            }
            final Path target = parent.resolve(fileName);
            // No overwrite at grant time either — including a dangling symlink.
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                return UserFileGrantSource.Decision.unavailable();
            }
            return UserFileGrantSource.Decision.selected(target);
        } catch (RuntimeException failure) {
            return UserFileGrantSource.Decision.unavailable();
        }
    }
}
