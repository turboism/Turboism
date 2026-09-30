package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact reviewed file-chooser selectors for supported Cubism Editor artifacts.
 *
 * <p>The selectors are frozen from the reviewed legacy behavior
 * ({@code FileChooserHistoryHostAdapter}): the save-dialog methods and the
 * export-context classes used for stack-trace detection. The mapping-pack
 * entry {@code stable.cubism.hook.FileChooser} is DRAFT, so the selectors are
 * pinned here as profile constants; host validation is deferred to a later
 * Lane C slice.
 */
public record FileChooserHistoryHostProfile(
        String hostVersion, List<SaveDialogMethod> saveDialogMethods, List<String> exportContextClassNames) {

    private static final String FILE_CHOOSER_CLASS = "com/live2d/ui/window/n";
    private static final String EXPORTER_CONTEXT_CLASS = "com.live2d.cubism.doc.model.exporter.b";
    private static final String APP_CTRL_EXPORT_CONTEXT_CLASS_52 = "com.live2d.cubism.appCtrlImpl.a";
    private static final String APP_CTRL_EXPORT_CONTEXT_CLASS_53 = "com.live2d.cubism.appCtrlImpl.al";

    private static final SaveDialogMethod GENERIC_DIALOG_METHOD =
            new SaveDialogMethod("a", "(Ljava/awt/Component;Z)Ljava/io/File;");

    private static List<SaveDialogMethod> saveDialogMethods(final String dialogClass) {
        return List.of(
                new SaveDialogMethod("c", "(Lcom/live2d/ui/window/" + dialogClass + ";)Ljava/io/File;"),
                GENERIC_DIALOG_METHOD);
    }

    public FileChooserHistoryHostProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
        saveDialogMethods = List.copyOf(Objects.requireNonNull(saveDialogMethods, "saveDialogMethods"));
        exportContextClassNames =
                List.copyOf(Objects.requireNonNull(exportContextClassNames, "exportContextClassNames"));
        if (saveDialogMethods.isEmpty()) {
            throw new IllegalArgumentException("saveDialogMethods must not be empty");
        }
        if (exportContextClassNames.isEmpty()) {
            throw new IllegalArgumentException("exportContextClassNames must not be empty");
        }
    }

    /** Internal name of the FileChooser host class. */
    public String fileChooserClassInternalName() {
        return FILE_CHOOSER_CLASS;
    }

    /**
     * Resolves the reviewed selector set for a host artifact.
     *
     * <p>Only the exact reviewed Cubism builds are recognised. They share the save-dialog
     * method bindings and differ in the chooser dialog class and the app-controller
     * export-context class name.
     * Any other artifact yields empty, which is the fail-closed signal that the file-chooser hook
     * must not be installed against an unreviewed host.
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<FileChooserHistoryHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return ReviewedHostArtifacts.cubismVersionOf(artifact)
                .flatMap(FileChooserHistoryHostProfile::forReviewedVersion);
    }

    /**
     * Resolves the reviewed selector tuple by declared reviewed generation for
     * a compatibility host that bound its declared version.
     *
     * @param cubismVersion host-declared reviewed version
     * @return the matching profile, or empty for any unreviewed version
     */
    public static Optional<FileChooserHistoryHostProfile> forReviewedVersion(final String cubismVersion) {
        Objects.requireNonNull(cubismVersion, "cubismVersion");
        if (!ReviewedHostArtifacts.reviewedCubismVersions().contains(cubismVersion)) {
            return Optional.empty();
        }
        final String dialogClass;
        final String contextClass;
        if (cubismVersion.startsWith("5.0.")) {
            dialogClass = "Y";
            contextClass = APP_CTRL_EXPORT_CONTEXT_CLASS_53;
        } else if (cubismVersion.startsWith("5.3.")) {
            dialogClass = "V";
            contextClass = APP_CTRL_EXPORT_CONTEXT_CLASS_53;
        } else {
            dialogClass = "X";
            contextClass = ReviewedHostArtifacts.CUBISM_5_2_03_VERSION.equals(cubismVersion)
                    ? APP_CTRL_EXPORT_CONTEXT_CLASS_52
                    : APP_CTRL_EXPORT_CONTEXT_CLASS_53;
        }
        return Optional.of(new FileChooserHistoryHostProfile(
                cubismVersion, saveDialogMethods(dialogClass), List.of(EXPORTER_CONTEXT_CLASS, contextClass)));
    }

    /** One save-dialog method binding: name + descriptor (identical across versions). */
    public record SaveDialogMethod(String name, String descriptor) {
        public SaveDialogMethod {
            name = requireText(name, "name");
            descriptor = requireText(descriptor, "descriptor");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
