package dev.turboism.adapter.host;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Strongly typed, local-only verification evidence for one host-session candidate.
 *
 * <p>Each slice is connection material: record-path changes, artifact-path changes, classloader
 * identity changes, and optional-slice presence changes require {@link HostSession} to replace the
 * complete adapter connection. Editor slices attest the same Editor artifact and defining
 * classloader. The Core slice attests its own Core artifact under that same classloader.</p>
 */
public record HostVerificationEvidence(
    Slice projectWorkspace,
    Optional<Slice> clipMask,
    Optional<Slice> editorModel,
    Optional<Slice> coreRuntime,
    Optional<Slice> mainToolbar,
    Optional<Slice> embeddedPanel,
    Optional<Slice> topMenu,
    Optional<Slice> boundingBoxOverlayButton,
    Optional<Slice> workspaceControl,
    Optional<Slice> statusBar
) {
    /** Compatibility constructor for evidence created before the distinct Core artifact slice. */
    public HostVerificationEvidence(
        final Slice projectWorkspace,
        final Optional<Slice> clipMask,
        final Optional<Slice> editorModel,
        final Optional<Slice> mainToolbar,
        final Optional<Slice> embeddedPanel,
        final Optional<Slice> topMenu,
        final Optional<Slice> boundingBoxOverlayButton
    ) {
        this(
            projectWorkspace,
            clipMask,
            editorModel,
            Optional.empty(),
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            Optional.empty(),
            Optional.empty()
        );
    }

    public HostVerificationEvidence(
        final Slice projectWorkspace,
        final Optional<Slice> clipMask,
        final Optional<Slice> editorModel,
        final Optional<Slice> mainToolbar
    ) {
        this(
            projectWorkspace,
            clipMask,
            editorModel,
            Optional.empty(),
            mainToolbar,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public HostVerificationEvidence(
        final Slice projectWorkspace,
        final Optional<Slice> clipMask,
        final Optional<Slice> editorModel,
        final Optional<Slice> mainToolbar,
        final Optional<Slice> embeddedPanel
    ) {
        this(
            projectWorkspace,
            clipMask,
            editorModel,
            Optional.empty(),
            mainToolbar,
            embeddedPanel,
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public HostVerificationEvidence {
        projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        clipMask = Objects.requireNonNull(clipMask, "clipMask");
        editorModel = Objects.requireNonNull(editorModel, "editorModel");
        coreRuntime = Objects.requireNonNull(coreRuntime, "coreRuntime");
        mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        embeddedPanel = Objects.requireNonNull(embeddedPanel, "embeddedPanel");
        topMenu = Objects.requireNonNull(topMenu, "topMenu");
        boundingBoxOverlayButton = Objects.requireNonNull(
            boundingBoxOverlayButton,
            "boundingBoxOverlayButton"
        );
        workspaceControl = Objects.requireNonNull(workspaceControl, "workspaceControl");
        statusBar = Objects.requireNonNull(statusBar, "statusBar");
        if (clipMask.isPresent()) {
            requireSameHostArtifact(projectWorkspace, clipMask.orElseThrow());
        }
        if (editorModel.isPresent()) {
            requireSameHostArtifact(projectWorkspace, editorModel.orElseThrow());
        }
        if (coreRuntime.isPresent()) {
            requireSameHostClassLoader(projectWorkspace, coreRuntime.orElseThrow());
        }
        if (mainToolbar.isPresent()) {
            requireSameHostArtifact(projectWorkspace, mainToolbar.orElseThrow());
        }
        if (embeddedPanel.isPresent()) {
            requireSameHostArtifact(projectWorkspace, embeddedPanel.orElseThrow());
        }
        if (topMenu.isPresent()) {
            requireSameHostArtifact(projectWorkspace, topMenu.orElseThrow());
        }
        if (boundingBoxOverlayButton.isPresent()) {
            requireSameHostArtifact(projectWorkspace, boundingBoxOverlayButton.orElseThrow());
        }
        if (workspaceControl.isPresent()) {
            requireSameHostArtifact(projectWorkspace, workspaceControl.orElseThrow());
        }
        if (statusBar.isPresent()) {
            requireSameHostArtifact(projectWorkspace, statusBar.orElseThrow());
        }
    }

    private static void requireSameHostArtifact(final Slice project, final Slice candidate) {
        requireSameHostClassLoader(project, candidate);
        final Path projectArtifact = normalize(project.verifiedArtifact());
        final Path candidateArtifact = normalize(candidate.verifiedArtifact());
        if (!projectArtifact.toString().equals(candidateArtifact.toString())) {
            throw new IllegalArgumentException(
                "all Editor verification slices must use the same host artifact"
            );
        }
    }

    private static void requireSameHostClassLoader(final Slice project, final Slice candidate) {
        if (project.hostClassLoader() != candidate.hostClassLoader()) {
            throw new IllegalArgumentException(
                "all host verification slices must use the same host classloader"
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
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()
        );
    }

    public static HostVerificationEvidence withClipMask(
        final Slice projectWorkspace,
        final Slice clipMask
    ) {
        return projectOnly(projectWorkspace).addingClipMask(clipMask);
    }

    public static HostVerificationEvidence withEditorModel(
        final Slice projectWorkspace,
        final Slice editorModel
    ) {
        return projectOnly(projectWorkspace).addingEditorModel(editorModel);
    }

    private HostVerificationEvidence addingClipMask(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            Optional.of(Objects.requireNonNull(slice, "clipMask")),
            editorModel,
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingEditorModel(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            Optional.of(Objects.requireNonNull(slice, "editorModel")),
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingCoreRuntime(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            Optional.of(Objects.requireNonNull(slice, "coreRuntime")),
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingMainToolbar(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            Optional.of(Objects.requireNonNull(slice, "mainToolbar")),
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingEmbeddedPanel(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            mainToolbar,
            Optional.of(Objects.requireNonNull(slice, "embeddedPanel")),
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingTopMenu(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            Optional.of(Objects.requireNonNull(slice, "topMenu")),
            boundingBoxOverlayButton,
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingBoundingBoxOverlayButton(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            topMenu,
            Optional.of(Objects.requireNonNull(slice, "boundingBoxOverlayButton")),
            workspaceControl,
            statusBar
        );
    }

    public HostVerificationEvidence addingWorkspaceControl(final Slice slice) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            Optional.of(Objects.requireNonNull(slice, "workspaceControl")),
            statusBar
        );
    }

    public HostVerificationEvidence addingStatusBar(final Slice statusBar) {
        return new HostVerificationEvidence(
            projectWorkspace,
            clipMask,
            editorModel,
            coreRuntime,
            mainToolbar,
            embeddedPanel,
            topMenu,
            boundingBoxOverlayButton,
            workspaceControl,
            Optional.of(Objects.requireNonNull(statusBar, "statusBar"))
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
