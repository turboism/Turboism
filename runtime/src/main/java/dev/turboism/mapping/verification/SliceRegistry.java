package dev.turboism.mapping.verification;

import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry of reviewed Cubism slice descriptors.
 */
public final class SliceRegistry {

    /** Auto-backup slice descriptor. */
    public static final SliceDescriptor AUTO_BACKUP = new SliceDescriptor(
            AutoBackupVerificationManifest.ADAPTER_SLICE_ID, AutoBackupVerificationManifest::forArtifact);

    /** Bounding-box overlay button slice descriptor. */
    public static final SliceDescriptor BOUNDING_BOX_OVERLAY_BUTTON = new SliceDescriptor(
            BoundingBoxOverlayButtonVerificationManifest.ADAPTER_SLICE_ID,
            BoundingBoxOverlayButtonVerificationManifest::forArtifact);

    /** Clip-mask slice descriptor. */
    public static final SliceDescriptor CLIP_MASK = new SliceDescriptor(
            ClipMaskVerificationManifest.ADAPTER_SLICE_ID, ClipMaskVerificationManifest::forArtifact);

    /** Control appearance slice descriptor. */
    public static final SliceDescriptor CONTROL_APPEARANCE = new SliceDescriptor(
            ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID, ControlAppearanceVerificationManifest::forArtifact);

    /** Cubism Core public-API read slice descriptor. */
    public static final SliceDescriptor CORE_PUBLIC_API = new SliceDescriptor(
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            artifact ->
                    CorePublicApiVerificationManifest.require(CorePublicApiVerificationManifest.profileFor(artifact)));

    /** Editor model slice descriptor. */
    public static final SliceDescriptor EDITOR_MODEL = new SliceDescriptor(
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest::forArtifact,
            VerifiedEditorModelResolverFactory::runtimeScopeFor);

    /** Embedded panel slice descriptor. */
    public static final SliceDescriptor EMBEDDED_PANEL = new SliceDescriptor(
            EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID, EmbeddedPanelVerificationManifest::forArtifact);

    /** Main toolbar slice descriptor. */
    public static final SliceDescriptor MAIN_TOOLBAR = new SliceDescriptor(
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID, MainToolbarVerificationManifest::forArtifact);

    /** Project workspace slice descriptor. */
    public static final SliceDescriptor PROJECT_WORKSPACE = new SliceDescriptor(
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID, ProjectWorkspaceVerificationManifest::forArtifact);

    /** Protected export orchestration slice descriptor. */
    public static final SliceDescriptor PROTECTED_EXPORT = new SliceDescriptor(
            ProtectedExportVerificationManifest.ADAPTER_SLICE_ID, ProtectedExportVerificationManifest::forArtifact);

    /** Status bar slice descriptor. */
    public static final SliceDescriptor STATUS_BAR = new SliceDescriptor(
            StatusBarVerificationManifest.ADAPTER_SLICE_ID, StatusBarVerificationManifest::forArtifact);

    /** Top menu slice descriptor. */
    public static final SliceDescriptor TOP_MENU =
            new SliceDescriptor(TopMenuVerificationManifest.ADAPTER_SLICE_ID, TopMenuVerificationManifest::forArtifact);

    /** Workspace control slice descriptor. */
    public static final SliceDescriptor WORKSPACE_CONTROL =
            new SliceDescriptor("adapter.workspace.control.v5_3", WorkspaceControlVerificationManifest::forArtifact);

    private static final List<SliceDescriptor> ALL = List.of(
            AUTO_BACKUP,
            BOUNDING_BOX_OVERLAY_BUTTON,
            CLIP_MASK,
            CONTROL_APPEARANCE,
            CORE_PUBLIC_API,
            EDITOR_MODEL,
            EMBEDDED_PANEL,
            MAIN_TOOLBAR,
            PROJECT_WORKSPACE,
            PROTECTED_EXPORT,
            STATUS_BAR,
            TOP_MENU,
            WORKSPACE_CONTROL);

    private SliceRegistry() {}

    /**
     * Returns an unmodifiable list of all registered slice descriptors.
     *
     * @return all registered slice descriptors
     */
    public static List<SliceDescriptor> all() {
        return ALL;
    }

    /**
     * Finds a registered slice descriptor by its slice identifier.
     *
     * @param sliceId slice identifier
     * @return optional containing the matching descriptor, or empty
     */
    public static Optional<SliceDescriptor> find(final String sliceId) {
        Objects.requireNonNull(sliceId, "sliceId");
        for (final SliceDescriptor descriptor : ALL) {
            if (descriptor.sliceId().equals(sliceId)) {
                return Optional.of(descriptor);
            }
        }
        return Optional.empty();
    }
}
