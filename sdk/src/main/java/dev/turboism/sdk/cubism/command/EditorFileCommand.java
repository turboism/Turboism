package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.PreviewApi;
import dev.turboism.sdk.ui.UserFileMode;

import java.util.Set;

/** Typed Editor operations whose source or destination is an opaque user-file grant. */
@PreviewApi
public enum EditorFileCommand {
    OPEN(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    SAVE_AS(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    CSV_IMPORT_MODEL_IDS(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    CSV_IMPORT_MODEL_IDS_PARAMETER(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    CSV_EXPORT_MODEL_IDS(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    CSV_EXPORT_MODEL_IDS_PARAMETER(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    IMPORT_SCENE_FROM_ANIMATION(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    EXPORT_AS_TEMPLATE(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    EXPORT_EMBEDDED_MODEL(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    EXPORT_MOTION(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    EXPORT_PHYSICS_SETTINGS(UserFileMode.WRITE, Set.of("5.2.03", "5.3.02")),
    MODELING_TEMPLATE(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    REPLACE_MODEL(UserFileMode.READ, Set.of("5.2.03", "5.3.02")),
    REPLACE_MODEL_RESOURCE(UserFileMode.READ, Set.of("5.2.03", "5.3.02"));

    private final UserFileMode mode;
    private final Set<String> supportedVersions;

    EditorFileCommand(final UserFileMode mode, final Set<String> supportedVersions) {
        this.mode = mode;
        this.supportedVersions = Set.copyOf(supportedVersions);
    }

    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
    }

    public UserFileMode mode() {
        return mode;
    }

    public boolean supports(final String cubismVersion) {
        return supportedVersions.contains(cubismVersion);
    }
}
