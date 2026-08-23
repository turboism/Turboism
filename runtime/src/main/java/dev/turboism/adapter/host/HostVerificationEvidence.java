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
    Optional<Slice> statusBar,
    Optional<Slice> autoBackup
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
        autoBackup = Objects.requireNonNull(autoBackup, "autoBackup");
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
        if (autoBackup.isPresent()) {
            requireSameHostArtifact(projectWorkspace, autoBackup.orElseThrow());
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

    /**
     * Evidence carrying only the mandatory project/workspace slice.
     *
     * @param projectWorkspace the verified project/workspace slice
     * @return evidence with every optional slice absent
     */
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
            Optional.empty(),
            Optional.empty()
        );
    }

    /**
     * Evidence carrying the project/workspace and clip-mask slices.
     *
     * @param projectWorkspace the verified project/workspace slice
     * @param clipMask the verified clip-mask slice
     * @return evidence with those two slices present
     */
    public static HostVerificationEvidence withClipMask(
        final Slice projectWorkspace,
        final Slice clipMask
    ) {
        return projectOnly(projectWorkspace).addingClipMask(clipMask);
    }

    /**
     * Evidence carrying the project/workspace and Editor-model slices.
     *
     * @param projectWorkspace the verified project/workspace slice
     * @param editorModel the verified Editor-model slice
     * @return evidence with those two slices present
     */
    public static HostVerificationEvidence withEditorModel(
        final Slice projectWorkspace,
        final Slice editorModel
    ) {
        return projectOnly(projectWorkspace).addingEditorModel(editorModel);
    }

    /**
     * Returns a copy that also carries the verified clip-mask slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified clip-mask slice
     * @return a copy with the clip-mask slice present
     * @throws NullPointerException when {@code slice} is null
     */
    public HostVerificationEvidence addingClipMask(final Slice slice) {
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified Editor-model slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified Editor-model slice
     * @return a copy with the Editor-model slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified Cubism Core runtime slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified Cubism Core runtime slice
     * @return a copy with the Cubism Core runtime slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified native main-toolbar slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified native main-toolbar slice
     * @return a copy with the native main-toolbar slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified embedded-panel slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified embedded-panel slice
     * @return a copy with the embedded-panel slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified top-menu slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified top-menu slice
     * @return a copy with the top-menu slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified bounding-box overlay button slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified bounding-box overlay button slice
     * @return a copy with the bounding-box overlay button slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified workspace-control slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified workspace-control slice
     * @return a copy with the workspace-control slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            statusBar,
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified native status-bar slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified native status-bar slice
     * @return a copy with the native status-bar slice present
     * @throws NullPointerException when {@code slice} is null
     */
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
            Optional.of(Objects.requireNonNull(statusBar, "statusBar")),
            autoBackup
        );
    }

    /**
     * Returns a copy that also carries the verified auto-backup slice.
     *
     * <p>Evidence is immutable, so adding a slice produces new connection material and
     * makes the host session replace the whole adapter connection.</p>
     *
     * @param slice the verified auto-backup slice
     * @return a copy with the auto-backup slice present
     * @throws NullPointerException when {@code slice} is null
     */
    public HostVerificationEvidence addingAutoBackup(final Slice autoBackupSlice) {
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
            statusBar,
            Optional.of(Objects.requireNonNull(autoBackupSlice, "autoBackup"))
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
