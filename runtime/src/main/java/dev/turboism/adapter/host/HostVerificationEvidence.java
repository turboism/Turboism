package dev.turboism.adapter.host;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Strongly typed, local-only verification evidence for one host-session candidate.
 *
 * <p>Each slice is connection material: record-path changes, artifact-path changes, classloader
 * identity changes, and optional-slice presence changes require {@link HostSession} to replace the
 * complete adapter connection. When both slices are present they must attest the same artifact and
 * defining classloader, while retaining independent reviewed records.</p>
 */
public record HostVerificationEvidence(
    Slice projectWorkspace,
    Optional<Slice> clipMask,
    Optional<Slice> editorModel,
    Optional<Slice> mainToolbar
) {
    public HostVerificationEvidence {
        projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        clipMask = Objects.requireNonNull(clipMask, "clipMask");
        editorModel = Objects.requireNonNull(editorModel, "editorModel");
        mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        if (clipMask.isPresent()) {
            requireSameHostArtifact(projectWorkspace, clipMask.orElseThrow());
        }
        if (editorModel.isPresent()) {
            requireSameHostArtifact(projectWorkspace, editorModel.orElseThrow());
        }
        if (mainToolbar.isPresent()) {
            requireSameHostArtifact(projectWorkspace, mainToolbar.orElseThrow());
        }
    }

    private static void requireSameHostArtifact(final Slice project, final Slice clip) {
        if (project.hostClassLoader() != clip.hostClassLoader()) {
            throw new IllegalArgumentException(
                "project/workspace and clip-mask evidence must use the same host classloader"
            );
        }
        final Path projectArtifact = normalize(project.verifiedArtifact());
        final Path clipArtifact = normalize(clip.verifiedArtifact());
        if (!projectArtifact.toString().equals(clipArtifact.toString())) {
            throw new IllegalArgumentException(
                "project/workspace and clip-mask evidence must use the same host artifact"
            );
        }
    }

    private static Path normalize(final Path path) {
        return Objects.requireNonNull(
            Objects.requireNonNull(path, "path").toAbsolutePath().normalize(),
            "normalized path"
        );
    }

    public static HostVerificationEvidence projectOnly(final Slice projectWorkspace) {
        return new HostVerificationEvidence(
            projectWorkspace,
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public static HostVerificationEvidence withClipMask(
        final Slice projectWorkspace,
        final Slice clipMask
    ) {
        return new HostVerificationEvidence(
            projectWorkspace,
            Optional.of(Objects.requireNonNull(clipMask, "clipMask")),
            Optional.empty(),
            Optional.empty()
        );
    }

    public static HostVerificationEvidence withEditorModel(
        final Slice projectWorkspace,
        final Slice editorModel
    ) {
        return new HostVerificationEvidence(
            projectWorkspace,
            Optional.empty(),
            Optional.of(Objects.requireNonNull(editorModel, "editorModel")),
            Optional.empty()
        );
    }

    public HostVerificationEvidence addingEditorModel(final Slice editorModel) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            Optional.of(Objects.requireNonNull(editorModel, "editorModel")),
            mainToolbar
        );
    }

    public HostVerificationEvidence addingMainToolbar(final Slice mainToolbar) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            Optional.of(Objects.requireNonNull(mainToolbar, "mainToolbar"))
        );
    }

    /** Exact record, artifact, and defining classloader for one verified adapter slice. */
    public record Slice(
        Path reviewedRecord,
        Path verifiedArtifact,
        ClassLoader hostClassLoader
    ) {
        public Slice {
            reviewedRecord = Objects.requireNonNull(reviewedRecord, "reviewedRecord");
            verifiedArtifact = Objects.requireNonNull(verifiedArtifact, "verifiedArtifact");
            hostClassLoader = Objects.requireNonNull(hostClassLoader, "hostClassLoader");
        }

        @Override
        public String toString() {
            return "Slice[verificationMaterial=redacted]";
        }
    }
}
