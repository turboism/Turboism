package dev.turboism.automesh.host;

import dev.turboism.compatibility.policy.ReviewedReleaseTable;
import dev.turboism.internal.Requirements;
import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Exact reviewed Auto Mesh Generate funnel selectors for every supported Cubism artifact.
 *
 * <p>The four funnel members pin {@code AutoMeshUtil.updateMeshFromInspector_inModelingMode} and
 * {@code AutoMeshUtil_InMeshEditor.updateMeshFromInspector_inMeshEditorMode}. The guard refuses any
 * host whose funnel mismatches its tuple, and the transformer fails closed on unexpected method
 * cardinality.</p>
 *
 * <p>The funnel package and both method names stay identical across all reviewed builds. Builds
 * from 5.0.00 through 5.0.05 pass the extension list only; builds from 5.0.06 onward append
 * {@code GroupUndo} and {@code Function0}. Each artifact keeps its own mapping identities.</p>
 *
 * <p>Three container constructor selectors anchor each profile because every reviewed build
 * declares one public {@code (CECompletePack)V} constructor per container, whereas private panel
 * fields changed generic ownership between 5.2.03 and 5.3.00.</p>
 */
public record AutoMeshHostProfile(
        String hostVersion,
        StaticSelector modelingFunnelOwner,
        StaticSelector modelingFunnel,
        StaticSelector meshEditorFunnelOwner,
        StaticSelector meshEditorFunnel,
        StaticSelector modelingDialogPanelConstructor,
        StaticSelector modelingPopupDialogConstructor,
        StaticSelector meshEditorPopupDialogConstructor) {

    /** Alias of the modeling-mode funnel owner class selector. */
    public static final String MODELING_FUNNEL_OWNER_ALIAS = "cubism.automesh.funnel.modeling-mode.owner";

    /** Alias of the modeling-mode funnel method selector. */
    public static final String MODELING_FUNNEL_ALIAS = "cubism.automesh.funnel.modeling-mode.method";

    /** Alias of the mesh-editor funnel owner class selector. */
    public static final String MESH_EDITOR_FUNNEL_OWNER_ALIAS = "cubism.automesh.funnel.mesh-editor-mode.owner";

    /** Alias of the mesh-editor funnel method selector. */
    public static final String MESH_EDITOR_FUNNEL_ALIAS = "cubism.automesh.funnel.mesh-editor-mode.method";

    /** Alias of the top-toolbar dialog panel constructor selector. */
    public static final String MODELING_DIALOG_PANEL_CONSTRUCTOR_ALIAS =
            "cubism.automesh.container.modeling-dialog-panel.constructor";

    /** Alias of the modeling-mode popup dialog constructor selector. */
    public static final String MODELING_POPUP_DIALOG_CONSTRUCTOR_ALIAS =
            "cubism.automesh.container.modeling-popup-dialog.constructor";

    /** Alias of the mesh-editor popup dialog constructor selector. */
    public static final String MESH_EDITOR_POPUP_DIALOG_CONSTRUCTOR_ALIAS =
            "cubism.automesh.container.mesh-editor-popup-dialog.constructor";

    private static final int ACCESS_PUBLIC = StaticSelector.ACCESS_PUBLIC;

    private static final String FUNNEL_PACKAGE = "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/";
    private static final String AUTO_MESH_UTIL = FUNNEL_PACKAGE + "AutoMeshUtil";
    private static final String AUTO_MESH_UTIL_IN_MESH_EDITOR = FUNNEL_PACKAGE + "AutoMeshUtil_InMeshEditor";
    private static final String MODELING_FUNNEL_NAME = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR_FUNNEL_NAME = "updateMeshFromInspector_inMeshEditorMode";

    private static final String CONTAINER_PACKAGE = "com/live2d/cubism/doc/model/extension/meshGenerator/ui/";
    private static final String MODELING_DIALOG_PANEL =
            CONTAINER_PACKAGE + "AutoMeshGenerate_DialogType$AutoGenerateMeshPanel";
    private static final String MODELING_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType$AutoGenerateMeshDialog";
    private static final String MESH_EDITOR_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType_InMeshEditor$AutoGenerateMeshDialog";

    /** The only container constructor every reviewed build declares: the pack, and nothing else. */
    private static final String CONTAINER_CONSTRUCTOR_DESCRIPTOR = "(Lcom/live2d/cubism/pack/CECompletePack;)V";

    private static final String COMPLETE_PACK = "Lcom/live2d/cubism/pack/CECompletePack;";
    private static final String EVENT = "Lcom/live2d/ui/event/z;";
    private static final String LIST = "Ljava/util/List;";

    /**
     * Modeling-mode descriptor of 5.0.00 to 5.0.05: the funnel takes the extension list only.
     */
    private static final String MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY = "(" + COMPLETE_PACK + EVENT + LIST + ")V";

    /**
     * Modeling-mode descriptor of 5.0.06 onward: the funnel also takes the undo group the caller
     * began and the completion callback it runs after the mesh update.
     */
    private static final String MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP =
            "(" + COMPLETE_PACK + EVENT + LIST + "Lcom/live2d/undo/GroupUndo;Lkotlin/jvm/functions/Function0;)V";

    /** Mesh-editor descriptor of every reviewed build: pack, event and the mesh targets. */
    private static final String MESH_EDITOR_FUNNEL_DESCRIPTOR = "(" + COMPLETE_PACK + EVENT + LIST + ")V";

    private static final Set<String> EXTENSIONS_ONLY_VERSIONS = ReviewedVersions.between("5.0.00", "5.0.05");
    private static final Set<String> WITH_UNDO_GROUP_VERSIONS = ReviewedVersions.since("5.0.06");

    /** Exact artifact-to-profile table derived from {@link ReviewedHostArtifacts#all()}. */
    private static final Map<HostArtifactDigest, AutoMeshHostProfile> BY_ARTIFACT = buildProfilesByArtifact();

    public AutoMeshHostProfile {
        hostVersion = Requirements.text(hostVersion, "hostVersion");
        modelingFunnelOwner = Objects.requireNonNull(modelingFunnelOwner, "modelingFunnelOwner");
        modelingFunnel = Objects.requireNonNull(modelingFunnel, "modelingFunnel");
        meshEditorFunnelOwner = Objects.requireNonNull(meshEditorFunnelOwner, "meshEditorFunnelOwner");
        meshEditorFunnel = Objects.requireNonNull(meshEditorFunnel, "meshEditorFunnel");
        modelingDialogPanelConstructor =
                Objects.requireNonNull(modelingDialogPanelConstructor, "modelingDialogPanelConstructor");
        modelingPopupDialogConstructor =
                Objects.requireNonNull(modelingPopupDialogConstructor, "modelingPopupDialogConstructor");
        meshEditorPopupDialogConstructor =
                Objects.requireNonNull(meshEditorPopupDialogConstructor, "meshEditorPopupDialogConstructor");
    }

    /** @return every pinned selector of this profile in installer order */
    public List<StaticSelector> selectors() {
        return List.of(
                modelingFunnelOwner,
                modelingFunnel,
                meshEditorFunnelOwner,
                meshEditorFunnel,
                modelingDialogPanelConstructor,
                modelingPopupDialogConstructor,
                meshEditorPopupDialogConstructor);
    }

    /**
     * Returns the container constructor selector of one container.
     *
     * @param container the container to resolve
     * @return the exact constructor selector this profile pins for that container
     */
    public StaticSelector containerConstructor(final AutoMeshContainer container) {
        return switch (Objects.requireNonNull(container, "container")) {
            case MODELING_DIALOG_PANEL -> modelingDialogPanelConstructor;
            case MODELING_POPUP_DIALOG -> modelingPopupDialogConstructor;
            case MESH_EDITOR_POPUP_DIALOG -> meshEditorPopupDialogConstructor;
        };
    }

    /** @return the host versions whose reviewed artifacts carry an Auto Mesh funnel profile */
    public static List<String> supportedHostVersions() {
        return ReviewedReleaseTable.versions();
    }

    /**
     * Resolves the reviewed funnel selectors for a host artifact.
     *
     * @param artifact digest of the host jar actually loaded
     * @return the matching profile, or empty when the artifact is not a reviewed build
     * @throws NullPointerException if {@code artifact} is {@code null}
     */
    public static Optional<AutoMeshHostProfile> forArtifact(final HostArtifactDigest artifact) {
        Objects.requireNonNull(artifact, "artifact");
        return Optional.ofNullable(BY_ARTIFACT.get(artifact));
    }

    /**
     * Resolves the reviewed funnel selectors for a reviewed Cubism version.
     *
     * @param hostVersion exact reviewed Cubism version string
     * @return the matching profile, or empty when the version is not reviewed
     * @throws NullPointerException if {@code hostVersion} is {@code null}
     */
    public static Optional<AutoMeshHostProfile> forReviewedVersion(final String hostVersion) {
        Objects.requireNonNull(hostVersion, "hostVersion");
        final String modelingDescriptor;
        if (EXTENSIONS_ONLY_VERSIONS.contains(hostVersion)) {
            modelingDescriptor = MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY;
        } else if (WITH_UNDO_GROUP_VERSIONS.contains(hostVersion)) {
            modelingDescriptor = MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP;
        } else {
            return Optional.empty();
        }
        return Optional.of(reviewedProfile(
                hostVersion, modelingDescriptor, "cubism.mapping.v" + hostVersion.replace('.', '_') + ".automesh."));
    }

    private static Map<HostArtifactDigest, AutoMeshHostProfile> buildProfilesByArtifact() {
        final Map<HostArtifactDigest, AutoMeshHostProfile> profiles = new LinkedHashMap<>();
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            final String version = ReviewedHostArtifacts.cubismVersionOf(artifact)
                    .orElseThrow(() -> new IllegalStateException("Missing Cubism version for reviewed artifact"));
            final AutoMeshHostProfile profile = forReviewedVersion(version)
                    .orElseThrow(() -> new IllegalStateException("Unsupported reviewed AutoMesh version: " + version));
            profiles.put(artifact, profile);
        }
        return Map.copyOf(profiles);
    }

    private static AutoMeshHostProfile reviewedProfile(
            final String hostVersion, final String modelingDescriptor, final String mappingIdPrefix) {
        return new AutoMeshHostProfile(
                hostVersion,
                StaticSelector.classSelector(
                        mappingIdPrefix + "modeling_mode_owner", MODELING_FUNNEL_OWNER_ALIAS, AUTO_MESH_UTIL),
                StaticSelector.method(
                        mappingIdPrefix + "modeling_mode_funnel",
                        MODELING_FUNNEL_ALIAS,
                        AUTO_MESH_UTIL,
                        MODELING_FUNNEL_NAME,
                        modelingDescriptor,
                        ACCESS_PUBLIC),
                StaticSelector.classSelector(
                        mappingIdPrefix + "mesh_editor_mode_owner",
                        MESH_EDITOR_FUNNEL_OWNER_ALIAS,
                        AUTO_MESH_UTIL_IN_MESH_EDITOR),
                StaticSelector.method(
                        mappingIdPrefix + "mesh_editor_mode_funnel",
                        MESH_EDITOR_FUNNEL_ALIAS,
                        AUTO_MESH_UTIL_IN_MESH_EDITOR,
                        MESH_EDITOR_FUNNEL_NAME,
                        MESH_EDITOR_FUNNEL_DESCRIPTOR,
                        ACCESS_PUBLIC),
                containerConstructor(
                        mappingIdPrefix + "modeling_dialog_panel_constructor",
                        MODELING_DIALOG_PANEL_CONSTRUCTOR_ALIAS,
                        MODELING_DIALOG_PANEL),
                containerConstructor(
                        mappingIdPrefix + "modeling_popup_dialog_constructor",
                        MODELING_POPUP_DIALOG_CONSTRUCTOR_ALIAS,
                        MODELING_POPUP_DIALOG),
                containerConstructor(
                        mappingIdPrefix + "mesh_editor_popup_dialog_constructor",
                        MESH_EDITOR_POPUP_DIALOG_CONSTRUCTOR_ALIAS,
                        MESH_EDITOR_POPUP_DIALOG));
    }

    private static StaticSelector containerConstructor(
            final String mappingId, final String alias, final String ownerInternalName) {
        return new StaticSelector(
                mappingId,
                alias,
                StaticSelector.Kind.CONSTRUCTOR,
                ownerInternalName,
                "<init>",
                CONTAINER_CONSTRUCTOR_DESCRIPTOR,
                ACCESS_PUBLIC,
                StaticSelector.ACCESS_STATIC);
    }
}
