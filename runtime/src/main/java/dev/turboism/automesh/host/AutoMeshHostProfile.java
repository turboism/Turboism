package dev.turboism.automesh.host;

import dev.turboism.internal.ReviewedVersions;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Exact reviewed Auto Mesh Generate funnel selectors for every supported Cubism artifact.
 *
 * <p>The four funnel members pin the two native entry points the dispatch guard transforms:
 * {@code AutoMeshUtil.updateMeshFromInspector_inModelingMode} and
 * {@code AutoMeshUtil_InMeshEditor.updateMeshFromInspector_inMeshEditorMode}. The guard refuses a
 * host whose funnel does not match its exact tuple, so an unrecognised build is never transformed.
 * The transformer additionally fails closed on any unexpected method cardinality, which keeps a
 * re-reviewed build byte-identical until its tuple is updated.</p>
 *
 * <p>The funnel package and both method names are stable across all 20 reviewed builds. Only the
 * modeling-mode signature grew a tail: 5.0.00 to 5.0.05 take the extension list alone, and 5.0.06
 * onward add the {@code GroupUndo} group and the {@code Function0} completion callback. The
 * mesh-editor funnel is descriptor-identical everywhere. Two tuples therefore cover the whole
 * reviewed set, and each artifact still carries its own mapping identities so a reviewed re-pin
 * stays per release.</p>
 *
 * <p>The three container constructor selectors anchor the selector row. Every reviewed build
 * declares exactly one public {@code (CECompletePack)V} constructor per container, and that
 * constructor is the only point where the container is reachable before the host shows it. The
 * panel's private property list changes its generic owner between 5.2.03 and 5.3.00, which is why
 * the anchor is the constructor and never a field.</p>
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

    private static final String CONTAINER_PACKAGE =
            "com/live2d/cubism/doc/model/extension/meshGenerator/ui/";
    private static final String MODELING_DIALOG_PANEL =
            CONTAINER_PACKAGE + "AutoMeshGenerate_DialogType$AutoGenerateMeshPanel";
    private static final String MODELING_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType$AutoGenerateMeshDialog";
    private static final String MESH_EDITOR_POPUP_DIALOG =
            CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType_InMeshEditor$AutoGenerateMeshDialog";

    /** The only container constructor every reviewed build declares: the pack, and nothing else. */
    private static final String CONTAINER_CONSTRUCTOR_DESCRIPTOR =
            "(Lcom/live2d/cubism/pack/CECompletePack;)V";

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

    /** The exact reviewed 5.0.00 tuple: the funnel predates the undo group and callback tail. */
    public static final AutoMeshHostProfile CUBISM_5_0_00 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_00_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_00.automesh.");

    /** The exact reviewed 5.0.01 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_0_01 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_01_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_01.automesh.");

    /** The exact reviewed 5.0.02 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_0_02 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_02_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_02.automesh.");

    /** The exact reviewed 5.0.03 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_0_03 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_03_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_03.automesh.");

    /** The exact reviewed 5.0.04 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_0_04 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_04_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_04.automesh.");

    /** The exact reviewed 5.0.05 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_0_05 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_05_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_EXTENSIONS_ONLY,
            "cubism.mapping.v5_0_05.automesh.");

    /** The exact reviewed 5.0.06 tuple: the first build whose funnel takes the undo group. */
    public static final AutoMeshHostProfile CUBISM_5_0_06 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_0_06_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_0_06.automesh.");

    /** The exact reviewed 5.1.00 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_1_00 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_1_00_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_1_00.automesh.");

    /** The exact reviewed 5.1.01 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_1_01 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_1_01_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_1_01.automesh.");

    /** The exact reviewed 5.1.02 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_1_02 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_1_02_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_1_02.automesh.");

    /** The exact reviewed 5.1.03 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_1_03 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_1_03_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_1_03.automesh.");

    /** The exact reviewed 5.2.00 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_2_00 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_2_00_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_2_00.automesh.");

    /** The exact reviewed 5.2.01 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_2_01 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_2_01_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_2_01.automesh.");

    /** The exact reviewed 5.2.02 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_2_02 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_2_02_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_2_02.automesh.");

    /** The exact reviewed 5.2.03 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_2_03 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_2_03_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_2_03.automesh.");

    /** The exact reviewed 5.3.00 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_3_00 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_3_00_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_3_00.automesh.");

    /** The exact reviewed 5.3.01 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_3_01 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_3_01_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_3_01.automesh.");

    /** The exact reviewed 5.3.02 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_3_02 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_3_02.automesh.");

    /** The exact reviewed 5.3.03 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_3_03 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_3_03_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_3_03.automesh.");

    /** The exact reviewed 5.3.04 tuple. */
    public static final AutoMeshHostProfile CUBISM_5_3_04 = reviewedProfile(
            ReviewedHostArtifacts.CUBISM_5_3_04_VERSION,
            MODELING_FUNNEL_DESCRIPTOR_WITH_UNDO_GROUP,
            "cubism.mapping.v5_3_04.automesh.");

    /**
     * Exact artifact-to-profile table. Keying on the reviewed digest keeps a repacked or rebuilt
     * host out instead of transforming whatever happens to carry the funnel method name.
     */
    private static final Map<HostArtifactDigest, AutoMeshHostProfile> BY_ARTIFACT = Map.ofEntries(
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_00, CUBISM_5_0_00),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_01, CUBISM_5_0_01),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_02, CUBISM_5_0_02),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_03, CUBISM_5_0_03),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_04, CUBISM_5_0_04),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_05, CUBISM_5_0_05),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_0_06, CUBISM_5_0_06),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_1_00, CUBISM_5_1_00),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_1_01, CUBISM_5_1_01),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_1_02, CUBISM_5_1_02),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_1_03, CUBISM_5_1_03),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_2_00, CUBISM_5_2_00),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_2_01, CUBISM_5_2_01),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_2_02, CUBISM_5_2_02),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_2_03, CUBISM_5_2_03),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_3_00, CUBISM_5_3_00),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_3_01, CUBISM_5_3_01),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_3_02, CUBISM_5_3_02),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_3_03, CUBISM_5_3_03),
            Map.entry(ReviewedHostArtifacts.CUBISM_5_3_04, CUBISM_5_3_04));

    public AutoMeshHostProfile {
        hostVersion = requireText(hostVersion, "hostVersion");
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
        return dev.turboism.compatibility.policy.ReviewedReleaseTable.versions().stream()
                .filter(ReviewedVersions.since("5.0.00")::contains)
                .toList();
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

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
