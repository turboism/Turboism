package dev.turboism.sdk.cubism.command;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.ui.UserFileMode;
import java.util.Set;

/** Typed Editor operations whose source or destination is an opaque user-file grant. */
@CubismEditor
public enum EditorFileCommand {
    OPEN(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    SAVE_AS(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    CSV_IMPORT_MODEL_IDS(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    CSV_IMPORT_MODEL_IDS_PARAMETER(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    CSV_EXPORT_MODEL_IDS(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    CSV_EXPORT_MODEL_IDS_PARAMETER(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    IMPORT_SCENE_FROM_ANIMATION(
            UserFileMode.READ,
            Set.of(
                    "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01",
                    "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03", "5.3.04")),
    EXPORT_AS_TEMPLATE(
            UserFileMode.WRITE,
            Set.of(
                    "5.1.00", "5.1.01", "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01",
                    "5.3.02", "5.3.03", "5.3.04")),
    EXPORT_EMBEDDED_MODEL(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    EXPORT_MOTION(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    EXPORT_PHYSICS_SETTINGS(
            UserFileMode.WRITE,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    MODELING_TEMPLATE(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    REPLACE_MODEL(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04")),
    REPLACE_MODEL_RESOURCE(
            UserFileMode.READ,
            Set.of(
                    "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                    "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02", "5.3.03",
                    "5.3.04"));

    private final UserFileMode mode;
    private final Set<String> supportedVersions;

    EditorFileCommand(final UserFileMode mode, final Set<String> supportedVersions) {
        this.mode = mode;
        this.supportedVersions = Set.copyOf(supportedVersions);
    }

    /**
     * @return the host command identifier: the constant name lowercased and dot-separated,
     *     computed with {@code Locale.ROOT} so it is locale-independent
     */
    public String id() {
        return name().toLowerCase(java.util.Locale.ROOT).replace('_', '.');
    }

    /**
     * @return whether this command consumes a file or produces one; a request is rejected unless
     *     the user's file grant was issued in this same mode, so a read grant can never be spent
     *     on an export
     */
    public UserFileMode mode() {
        return mode;
    }

    /**
     * @param cubismVersion an exact Editor version string; compared for equality, not range-matched
     * @return whether this command was observed on that version
     */
    public boolean supports(final String cubismVersion) {
        return supportedVersions.contains(cubismVersion);
    }
}
