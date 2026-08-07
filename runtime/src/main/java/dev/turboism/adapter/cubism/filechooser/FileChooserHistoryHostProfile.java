package dev.turboism.adapter.cubism.filechooser;

import dev.turboism.mapping.verification.HostArtifactDigest;

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
    String hostVersion,
    List<SaveDialogMethod> saveDialogMethods,
    List<String> exportContextClassNames
) {

    private static final HostArtifactDigest CUBISM_52 = new HostArtifactDigest(
        40_805_584L,
        "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd"
    );
    private static final HostArtifactDigest CUBISM_53 = new HostArtifactDigest(
        41_922_739L,
        "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21"
    );

    private static final String FILE_CHOOSER_CLASS = "com/live2d/ui/window/n";
    private static final String EXPORTER_CONTEXT_CLASS = "com.live2d.cubism.doc.model.exporter.b";
    private static final String APP_CTRL_EXPORT_CONTEXT_CLASS_52 = "com.live2d.cubism.appCtrlImpl.a";
    private static final String APP_CTRL_EXPORT_CONTEXT_CLASS_53 = "com.live2d.cubism.appCtrlImpl.al";

    private static final List<SaveDialogMethod> SAVE_DIALOG_METHODS = List.of(
        new SaveDialogMethod("c", "(Lcom/live2d/ui/window/V;)Ljava/io/File;"),
        new SaveDialogMethod("a", "(Ljava/awt/Component;Z)Ljava/io/File;")
    );

    public FileChooserHistoryHostProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
        saveDialogMethods = List.copyOf(Objects.requireNonNull(saveDialogMethods, "saveDialogMethods"));
        exportContextClassNames = List.copyOf(
            Objects.requireNonNull(exportContextClassNames, "exportContextClassNames")
        );
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

    public static Optional<FileChooserHistoryHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        final String version;
        final List<String> contextClasses;
        if (artifact.equals(CUBISM_52)) {
            version = "5.2.0";
            contextClasses = List.of(EXPORTER_CONTEXT_CLASS, APP_CTRL_EXPORT_CONTEXT_CLASS_52);
        } else if (artifact.equals(CUBISM_53)) {
            version = "5.3.02";
            contextClasses = List.of(EXPORTER_CONTEXT_CLASS, APP_CTRL_EXPORT_CONTEXT_CLASS_53);
        } else {
            return Optional.empty();
        }
        return Optional.of(new FileChooserHistoryHostProfile(version, SAVE_DIALOG_METHODS, contextClasses));
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
