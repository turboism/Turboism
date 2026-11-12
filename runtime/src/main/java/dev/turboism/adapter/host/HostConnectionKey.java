package dev.turboism.adapter.host;

import dev.turboism.adapter.HostVerificationEvidence;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Identity of one reviewed host connection.
 *
 * <p>A connection is reusable only while its session ID and every verification-evidence slice keep
 * the same normalized reviewed-record path, normalized verified-artifact path, defining host
 * classloader identity, and declared slice contract. Adding or removing an optional slice is also a
 * material change. A material change forces the complete old adapter bundle to close before the
 * replacement connects, so a partially refreshed bundle is never published.</p>
 */
final class HostConnectionKey {

    private static final String NORMALIZED_PATH = "normalized path";
    private static final String NORMALIZED_PATH_TEXT = "normalized path text";

    private final String sessionId;
    private final SliceKey projectWorkspace;
    private final Optional<SliceKey> clipMask;
    private final Optional<SliceKey> editorModel;
    private final Optional<SliceKey> coreRuntime;
    private final Optional<SliceKey> mainToolbar;
    private final Optional<SliceKey> embeddedPanel;
    private final Optional<SliceKey> topMenu;
    private final Optional<SliceKey> boundingBoxOverlayButton;
    private final Optional<SliceKey> workspaceControl;
    private final Optional<SliceKey> statusBar;
    private final Optional<SliceKey> autoBackup;

    private HostConnectionKey(
            final String sessionId,
            final SliceKey projectWorkspace,
            final Optional<SliceKey> clipMask,
            final Optional<SliceKey> editorModel,
            final Optional<SliceKey> coreRuntime,
            final Optional<SliceKey> mainToolbar,
            final Optional<SliceKey> embeddedPanel,
            final Optional<SliceKey> topMenu,
            final Optional<SliceKey> boundingBoxOverlayButton,
            final Optional<SliceKey> workspaceControl,
            final Optional<SliceKey> statusBar,
            final Optional<SliceKey> autoBackup) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.projectWorkspace = Objects.requireNonNull(projectWorkspace, "projectWorkspace");
        this.clipMask = Objects.requireNonNull(clipMask, "clipMask");
        this.editorModel = Objects.requireNonNull(editorModel, "editorModel");
        this.coreRuntime = Objects.requireNonNull(coreRuntime, "coreRuntime");
        this.mainToolbar = Objects.requireNonNull(mainToolbar, "mainToolbar");
        this.embeddedPanel = Objects.requireNonNull(embeddedPanel, "embeddedPanel");
        this.topMenu = Objects.requireNonNull(topMenu, "topMenu");
        this.boundingBoxOverlayButton = Objects.requireNonNull(boundingBoxOverlayButton, "boundingBoxOverlayButton");
        this.workspaceControl = Objects.requireNonNull(workspaceControl, "workspaceControl");
        this.statusBar = Objects.requireNonNull(statusBar, "statusBar");
        this.autoBackup = Objects.requireNonNull(autoBackup, "autoBackup");
    }

    /**
     * Derives the identity of the connection a descriptor describes.
     *
     * @param descriptor the host instance the connector would bind
     * @return the identity every later refresh compares against
     */
    static HostConnectionKey from(final HostInstanceDescriptor descriptor) {
        final HostVerificationEvidence evidence = descriptor.verificationEvidence();
        return new HostConnectionKey(
                descriptor.sessionId(),
                SliceKey.from(evidence.projectWorkspace()),
                evidence.clipMask().map(SliceKey::from),
                evidence.editorModel().map(SliceKey::from),
                evidence.coreRuntime().map(SliceKey::from),
                evidence.mainToolbar().map(SliceKey::from),
                evidence.embeddedPanel().map(SliceKey::from),
                evidence.topMenu().map(SliceKey::from),
                evidence.boundingBoxOverlayButton().map(SliceKey::from),
                evidence.workspaceControl().map(SliceKey::from),
                evidence.statusBar().map(SliceKey::from),
                evidence.autoBackup().map(SliceKey::from));
    }

    /**
     * @param other the identity of the connection that is already bound; null is never a match
     * @return true when every slice still describes the same reviewed host connection
     */
    boolean matches(final HostConnectionKey other) {
        return other != null
                && sessionId.equals(other.sessionId)
                && projectWorkspace.matches(other.projectWorkspace)
                && optionalSliceMatches(clipMask, other.clipMask)
                && optionalSliceMatches(editorModel, other.editorModel)
                && optionalSliceMatches(coreRuntime, other.coreRuntime)
                && optionalSliceMatches(mainToolbar, other.mainToolbar)
                && optionalSliceMatches(embeddedPanel, other.embeddedPanel)
                && optionalSliceMatches(topMenu, other.topMenu)
                && optionalSliceMatches(boundingBoxOverlayButton, other.boundingBoxOverlayButton)
                && optionalSliceMatches(workspaceControl, other.workspaceControl)
                && optionalSliceMatches(statusBar, other.statusBar)
                && optionalSliceMatches(autoBackup, other.autoBackup);
    }

    private static boolean optionalSliceMatches(final Optional<SliceKey> left, final Optional<SliceKey> right) {
        return left.isEmpty()
                ? right.isEmpty()
                : right.isPresent() && left.orElseThrow().matches(right.orElseThrow());
    }

    private static String normalizePath(final Path path) {
        final Path normalized = Objects.requireNonNull(path.toAbsolutePath().normalize(), NORMALIZED_PATH);
        return Objects.requireNonNull(normalized.toString(), NORMALIZED_PATH_TEXT);
    }

    /** One verification-evidence slice reduced to the values a reuse decision depends on. */
    private record SliceKey(
            String reviewedRecord,
            String verifiedArtifact,
            ClassLoader hostClassLoader,
            Optional<dev.turboism.mapping.verification.SliceContract> contract) {

        private static SliceKey from(final HostVerificationEvidence.Slice slice) {
            return new SliceKey(
                    normalizePath(slice.reviewedRecord()),
                    normalizePath(slice.verifiedArtifact()),
                    slice.hostClassLoader(),
                    slice.contract());
        }

        private boolean matches(final SliceKey other) {
            return other != null
                    && reviewedRecord.equals(other.reviewedRecord)
                    && verifiedArtifact.equals(other.verifiedArtifact)
                    && hostClassLoader == other.hostClassLoader
                    && contract.equals(other.contract);
        }
    }
}
