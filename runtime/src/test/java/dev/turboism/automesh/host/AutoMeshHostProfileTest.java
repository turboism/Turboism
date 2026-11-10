package dev.turboism.automesh.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pins the reviewed Auto Mesh funnel tuples and the artifact resolution of every reviewed build.
 *
 * <p>These literals are the only thing between the guard and an unreviewed host, so they are
 * asserted directly instead of derived. A deliberate host re-review updates this test in the same
 * change as the profile.</p>
 */
class AutoMeshHostProfileTest {

    private static final String AUTO_MESH_UTIL = "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil";
    private static final String AUTO_MESH_UTIL_IN_MESH_EDITOR =
            "com/live2d/cubism/view/palette/tool/toolMode/meshEditor/AutoMeshUtil_InMeshEditor";
    private static final String MODELING = "updateMeshFromInspector_inModelingMode";
    private static final String MESH_EDITOR = "updateMeshFromInspector_inMeshEditorMode";
    private static final String EXTENSIONS_ONLY_DESCRIPTOR =
            "(Lcom/live2d/cubism/pack/CECompletePack;Lcom/live2d/ui/event/z;Ljava/util/List;)V";
    private static final String GROUPED_DESCRIPTOR = "(Lcom/live2d/cubism/pack/CECompletePack;Lcom/live2d/ui/event/z;"
            + "Ljava/util/List;Lcom/live2d/undo/GroupUndo;Lkotlin/jvm/functions/Function0;)V";
    private static final String CONTAINER_PACKAGE = "com/live2d/cubism/doc/model/extension/meshGenerator/ui/";
    private static final String CONTAINER_CONSTRUCTOR_DESCRIPTOR = "(Lcom/live2d/cubism/pack/CECompletePack;)V";
    private static final int SELECTORS_PER_PROFILE = 7;

    @Test
    void theEarlyBuildsPinTheFunnelWithoutUndoGroupOrCallback() {
        for (final AutoMeshHostProfile profile : List.of(
                AutoMeshHostProfile.CUBISM_5_0_00,
                AutoMeshHostProfile.CUBISM_5_0_01,
                AutoMeshHostProfile.CUBISM_5_0_02,
                AutoMeshHostProfile.CUBISM_5_0_03,
                AutoMeshHostProfile.CUBISM_5_0_04,
                AutoMeshHostProfile.CUBISM_5_0_05)) {
            assertEquals(AUTO_MESH_UTIL, profile.modelingFunnelOwner().ownerInternalName());
            assertEquals(MODELING, profile.modelingFunnel().memberName());
            assertEquals(EXTENSIONS_ONLY_DESCRIPTOR, profile.modelingFunnel().descriptor(), profile.hostVersion());
        }
    }

    @Test
    void everyBuildFrom5006PinsTheFunnelWithUndoGroupAndCallback() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            if (version.compareTo("5.0.06") < 0) {
                continue;
            }
            final AutoMeshHostProfile profile = profileOf(version);
            assertEquals(GROUPED_DESCRIPTOR, profile.modelingFunnel().descriptor(), version);
        }
    }

    @Test
    void everyBuildPinsTheSameMeshEditorFunnel() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final AutoMeshHostProfile profile = profileOf(version);
            assertEquals(
                    AUTO_MESH_UTIL_IN_MESH_EDITOR,
                    profile.meshEditorFunnelOwner().ownerInternalName());
            assertEquals(MESH_EDITOR, profile.meshEditorFunnel().memberName());
            assertEquals(EXTENSIONS_ONLY_DESCRIPTOR, profile.meshEditorFunnel().descriptor(), version);
            assertEquals(
                    StaticSelector.ACCESS_PUBLIC, profile.meshEditorFunnel().requiredAccessFlags(), version);
        }
    }

    @Test
    void everyMemberSelectorIsAPublicInstanceBinding() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            for (final StaticSelector selector : profileOf(version).selectors()) {
                if (selector.kind() == StaticSelector.Kind.CLASS) {
                    assertEquals(0, selector.forbiddenAccessFlags(), selector.alias());
                    continue;
                }
                assertNotEquals(
                        0,
                        selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC,
                        "an instance selector must reject a static binding: " + selector.alias());
            }
        }
    }

    @Test
    void everyProfileCarriesItsOwnMappingIdentities() {
        final List<String> mappingIds = new ArrayList<>();
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            profileOf(version).selectors().forEach(selector -> mappingIds.add(selector.mappingId()));
        }
        assertEquals(
                AutoMeshHostProfile.supportedHostVersions().size() * SELECTORS_PER_PROFILE,
                mappingIds.size(),
                "20 reviewed builds x " + SELECTORS_PER_PROFILE + " selectors");
        assertEquals(mappingIds.size(), mappingIds.stream().distinct().count());
    }

    @Test
    void everyBuildPinsTheSameThreeContainerConstructors() {
        for (final String version : AutoMeshHostProfile.supportedHostVersions()) {
            final AutoMeshHostProfile profile = profileOf(version);
            for (final AutoMeshContainer container : AutoMeshContainer.values()) {
                final StaticSelector selector = profile.containerConstructor(container);
                assertEquals(StaticSelector.Kind.CONSTRUCTOR, selector.kind(), version);
                assertEquals("<init>", selector.memberName(), version);
                assertEquals(CONTAINER_CONSTRUCTOR_DESCRIPTOR, selector.descriptor(), version);
                assertEquals(StaticSelector.ACCESS_PUBLIC, selector.requiredAccessFlags(), version);
                assertNotEquals(
                        0,
                        selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC,
                        "a container anchor must reject a static binding: " + version);
            }
        }
    }

    @Test
    void theContainerSelectorsNameTheThreeNativeContainers() {
        final AutoMeshHostProfile profile = profileOf("5.3.04");
        assertEquals(
                CONTAINER_PACKAGE + "AutoMeshGenerate_DialogType$AutoGenerateMeshPanel",
                profile.containerConstructor(AutoMeshContainer.MODELING_DIALOG_PANEL)
                        .ownerInternalName());
        assertEquals(
                CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType$AutoGenerateMeshDialog",
                profile.containerConstructor(AutoMeshContainer.MODELING_POPUP_DIALOG)
                        .ownerInternalName());
        assertEquals(
                CONTAINER_PACKAGE + "AutoMeshGenerate_PopupType_InMeshEditor$AutoGenerateMeshDialog",
                profile.containerConstructor(AutoMeshContainer.MESH_EDITOR_POPUP_DIALOG)
                        .ownerInternalName());
    }

    @Test
    void everyContainerAliasIsDistinctAndPinned() {
        assertEquals(
                List.of(
                        "cubism.automesh.container.modeling-dialog-panel.constructor",
                        "cubism.automesh.container.modeling-popup-dialog.constructor",
                        "cubism.automesh.container.mesh-editor-popup-dialog.constructor"),
                List.of(
                        AutoMeshHostProfile.MODELING_DIALOG_PANEL_CONSTRUCTOR_ALIAS,
                        AutoMeshHostProfile.MODELING_POPUP_DIALOG_CONSTRUCTOR_ALIAS,
                        AutoMeshHostProfile.MESH_EDITOR_POPUP_DIALOG_CONSTRUCTOR_ALIAS),
                "the three container aliases are the reviewed vocabulary");
    }

    @Test
    void everyReviewedArtifactResolvesToItsOwnProfile() {
        assertEquals(20, ReviewedHostArtifacts.all().size());
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            final String version =
                    ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow();
            assertEquals(
                    version,
                    AutoMeshHostProfile.forArtifact(artifact)
                            .orElseThrow(() -> new AssertionError("no profile for " + version))
                            .hostVersion());
        }
        assertTrue(AutoMeshHostProfile.forArtifact(new HostArtifactDigest(1L, "0".repeat(64)))
                .isEmpty());
        assertTrue(
                AutoMeshHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03_JOGL)
                        .isEmpty(),
                "a bundled dependency is not an Editor artifact");
    }

    @Test
    void supportedHostVersionsAreTheReviewedReleaseTable() {
        assertEquals(
                List.of(
                        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01",
                        "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
                        "5.3.03", "5.3.04"),
                AutoMeshHostProfile.supportedHostVersions());
    }

    private static AutoMeshHostProfile profileOf(final String version) {
        for (final HostArtifactDigest artifact : ReviewedHostArtifacts.all()) {
            if (version.equals(ReviewedHostArtifacts.cubismVersionOf(artifact).orElse(null))) {
                return AutoMeshHostProfile.forArtifact(artifact).orElseThrow();
            }
        }
        throw new AssertionError("no reviewed artifact for " + version);
    }
}
