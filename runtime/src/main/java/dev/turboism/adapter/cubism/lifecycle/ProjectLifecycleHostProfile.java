package dev.turboism.adapter.cubism.lifecycle;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.sdk.cubism.ProjectContentKind;
import dev.turboism.sdk.cubism.ProjectFileOperationType;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact reviewed lifecycle selectors for supported Cubism Editor artifacts. */
public record ProjectLifecycleHostProfile(
    String hostVersion,
    List<ProjectLifecycleNativeMethodTransformer.Binding> bindings
) {
    private static final HostArtifactDigest CUBISM_52 = ReviewedHostArtifacts.CUBISM_5_2_03;
    private static final HostArtifactDigest CUBISM_53 = ReviewedHostArtifacts.CUBISM_5_3_02;
    private static final HostArtifactDigest CUBISM_5303 = ReviewedHostArtifacts.CUBISM_5_3_03;

    public ProjectLifecycleHostProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
        bindings = List.copyOf(Objects.requireNonNull(bindings, "bindings"));
        if (bindings.isEmpty()) throw new IllegalArgumentException("bindings must not be empty");
    }

    /**
     * Resolves the reviewed lifecycle bindings for a host artifact. Cubism Editor 5.2.03, 5.3.02,
     * and 5.3.03 currently share the same reviewed method selectors for model open, animation open,
     * editor exit, and model/animation save and close. Runtime admission is decided separately.
     *
     * @param artifact digest of the host artifact actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed Cubism build, in which
     *     case no lifecycle transformation may be installed
     * @throws NullPointerException when {@code artifact} is null
     */
    public static Optional<ProjectLifecycleHostProfile> forArtifact(
        final HostArtifactDigest artifact
    ) {
        Objects.requireNonNull(artifact, "artifact");
        final String version;
        if (artifact.equals(CUBISM_52)) version = "5.2.03";
        else if (artifact.equals(CUBISM_53)) version = "5.3.02";
        else if (artifact.equals(CUBISM_5303)) version = "5.3.03";
        else return Optional.empty();

        final String app = "com/live2d/cubism/CEAppCtrl";
        final String model = "com/live2d/cubism/doc/modeling/CModelingDocument";
        final String animation = "com/live2d/cubism/doc/animation/CAnimationFileContent";
        return Optional.of(new ProjectLifecycleHostProfile(version, List.of(
            ProjectLifecycleNativeMethodTransformer.Binding.modelOpen(
                app,
                "openModelDocument",
                "(Ljava/lang/String;Lcom/live2d/cubism/doc/model/CModelSource;Ljava/io/File;ZLcom/live2d/util/a/a;)Lcom/live2d/cubism/doc/modeling/CModelingDocument;"
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.animationOpen(
                app,
                "openAnimationContent",
                "(Lcom/live2d/cubism/doc/animation/CAnimation;Ljava/io/File;ZLcom/live2d/util/a/a;)Lcom/live2d/cubism/doc/animation/CAnimationFileContent;"
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.editorExit(
                app,
                "command_exit",
                "()Z"
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.content(
                model,
                "saveDocument",
                "(Ljava/io/File;Z)Z",
                ProjectContentKind.MODEL,
                ProjectFileOperationType.SAVE
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.content(
                model,
                "closeFile",
                "(ZZ)Z",
                ProjectContentKind.MODEL,
                ProjectFileOperationType.CLOSE
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.content(
                animation,
                "saveDocument",
                "(Ljava/io/File;Z)Z",
                ProjectContentKind.ANIMATION,
                ProjectFileOperationType.SAVE
            ),
            ProjectLifecycleNativeMethodTransformer.Binding.content(
                animation,
                "closeFile",
                "(ZZ)Z",
                ProjectContentKind.ANIMATION,
                ProjectFileOperationType.CLOSE
            )
        )));
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }
}
