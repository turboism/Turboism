package dev.turboism.exportsettings;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StaticSelector;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the reviewed export-settings selector tuples for 5.2.03, 5.3.02 and 5.3.03.
 *
 * <p>These literals are the only thing standing between the transformer and an unreviewed host, so
 * they are asserted directly rather than derived. A deliberate host re-review must update this test
 * in the same change as the profile.</p>
 */
class ExportSettingsHostProfileTest {

    private static final String DIALOG_OWNER = "com/live2d/cubism/doc/model/exporter/e";
    private static final String WINDOW_BASE = "com/live2d/ui/window/y";

    @Test
    void exact5302SelectorsMatchTheReviewedDialogShape() {
        final ExportSettingsHostProfile profile = ExportSettingsHostProfile.CUBISM_5_3_02;
        assertEquals("5.3.02", profile.hostVersion());

        assertEquals(StaticSelector.Kind.CLASS, profile.dialogOwner().kind());
        assertEquals(DIALOG_OWNER, profile.dialogOwner().ownerInternalName());
        assertEquals(StaticSelector.ACCESS_PUBLIC, profile.dialogOwner().requiredAccessFlags());

        assertEquals(StaticSelector.Kind.CONSTRUCTOR, profile.dialogConstructor().kind());
        assertEquals(DIALOG_OWNER, profile.dialogConstructor().ownerInternalName());
        assertEquals("<init>", profile.dialogConstructor().memberName());
        assertEquals(
            "(Lcom/live2d/cubism/doc/model/CModelSource;)V",
            profile.dialogConstructor().descriptor()
        );
        assertEquals(
            StaticSelector.ACCESS_PUBLIC, profile.dialogConstructor().requiredAccessFlags()
        );

        assertEquals("a", profile.dialogShow().memberName());
        assertEquals(
            "(Lcom/live2d/ui/window/V;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;)Z",
            profile.dialogShow().descriptor()
        );
        assertEquals(StaticSelector.ACCESS_PUBLIC, profile.dialogShow().requiredAccessFlags());

        assertEquals("b", profile.dialogContentBuilder().memberName());
        assertEquals("()V", profile.dialogContentBuilder().descriptor());
        assertEquals(0, profile.dialogContentBuilder().requiredAccessFlags());

        assertEquals(StaticSelector.Kind.FIELD, profile.dialogWindowField().kind());
        assertEquals("c", profile.dialogWindowField().memberName());
        assertEquals("L" + WINDOW_BASE + ";", profile.dialogWindowField().descriptor());
        assertEquals(0, profile.dialogWindowField().requiredAccessFlags());

        assertEquals(StaticSelector.Kind.CLASS, profile.windowClass().kind());
        assertEquals(WINDOW_BASE, profile.windowClass().ownerInternalName());

        assertEquals("e", profile.windowJDialog().memberName());
        assertEquals("()Ljavax/swing/JDialog;", profile.windowJDialog().descriptor());
        assertEquals(StaticSelector.ACCESS_PUBLIC, profile.windowJDialog().requiredAccessFlags());
    }

    @Test
    void everyMemberSelectorForbidsStaticBinding() {
        final ExportSettingsHostProfile profile = ExportSettingsHostProfile.CUBISM_5_3_02;
        for (StaticSelector selector : profile.selectors()) {
            if (selector.kind() == StaticSelector.Kind.CLASS) {
                assertEquals(0, selector.forbiddenAccessFlags(), selector.alias());
                continue;
            }
            assertNotEquals(
                0,
                selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC,
                "instance member must reject a static binding: " + selector.alias()
            );
        }
    }

    @Test
    void eachSelectorCarriesADistinctReviewedMappingIdentity() {
        final List<String> aliases = ExportSettingsHostProfile.CUBISM_5_3_02.selectors().stream()
            .map(StaticSelector::alias)
            .toList();
        assertEquals(7, aliases.size());
        assertEquals(7, aliases.stream().distinct().count());
        assertTrue(aliases.containsAll(List.of(
            ExportSettingsHostProfile.DIALOG_OWNER_ALIAS,
            ExportSettingsHostProfile.DIALOG_CONSTRUCTOR_ALIAS,
            ExportSettingsHostProfile.DIALOG_SHOW_ALIAS,
            ExportSettingsHostProfile.DIALOG_CONTENT_BUILDER_ALIAS,
            ExportSettingsHostProfile.DIALOG_WINDOW_FIELD_ALIAS,
            ExportSettingsHostProfile.WINDOW_CLASS_ALIAS,
            ExportSettingsHostProfile.WINDOW_JDIALOG_ALIAS
        )));
    }

    @Test
    void exact5203SelectorsMatchTheReviewedDialogShape() {
        final ExportSettingsHostProfile profile = ExportSettingsHostProfile.CUBISM_5_2_03;
        assertEquals("5.2.03", profile.hostVersion());
        assertEquals(DIALOG_OWNER, profile.dialogOwner().ownerInternalName());
        // The 5.2.03 abstract window panel is X, not the 5.3.x V.
        assertEquals(
            "(Lcom/live2d/ui/window/X;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;)Z",
            profile.dialogShow().descriptor()
        );
        assertEquals("L" + WINDOW_BASE + ";", profile.dialogWindowField().descriptor());
        assertEquals(WINDOW_BASE, profile.windowClass().ownerInternalName());
        assertEquals("()Ljavax/swing/JDialog;", profile.windowJDialog().descriptor());
    }

    @Test
    void exact5303SelectorsMatchTheReviewedDialogShape() {
        final ExportSettingsHostProfile profile = ExportSettingsHostProfile.CUBISM_5_3_03;
        assertEquals("5.3.03", profile.hostVersion());
        assertEquals(
            "(Lcom/live2d/ui/window/V;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;Lcom/live2d/cubism/doc/model/exporter/"
                + "CModelExportSettingDialogData;)Z",
            profile.dialogShow().descriptor()
        );
        assertEquals("L" + WINDOW_BASE + ";", profile.dialogWindowField().descriptor());
    }

    @Test
    void everyProfileMemberSelectorForbidsStaticBinding() {
        for (ExportSettingsHostProfile profile : List.of(
            ExportSettingsHostProfile.CUBISM_5_2_03,
            ExportSettingsHostProfile.CUBISM_5_3_02,
            ExportSettingsHostProfile.CUBISM_5_3_03
        )) {
            for (StaticSelector selector : profile.selectors()) {
                if (selector.kind() == StaticSelector.Kind.CLASS) {
                    assertEquals(0, selector.forbiddenAccessFlags(), selector.alias());
                    continue;
                }
                assertNotEquals(
                    0,
                    selector.forbiddenAccessFlags() & StaticSelector.ACCESS_STATIC,
                    "instance member must reject a static binding: " + selector.alias()
                );
            }
        }
    }

    @Test
    void eachProfileCarriesItsOwnReviewedMappingIdentities() {
        final List<String> mappingIds = new java.util.ArrayList<>();
        for (ExportSettingsHostProfile profile : List.of(
            ExportSettingsHostProfile.CUBISM_5_2_03,
            ExportSettingsHostProfile.CUBISM_5_3_02,
            ExportSettingsHostProfile.CUBISM_5_3_03
        )) {
            profile.selectors().forEach(selector -> mappingIds.add(selector.mappingId()));
        }
        assertEquals(21, mappingIds.size());
        assertEquals(21, mappingIds.stream().distinct().count());
    }

    @Test
    void onlyTheExactReviewedArtifactsResolveAProfile() {
        assertEquals(
            "5.2.03",
            ExportSettingsHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_2_03)
                .orElseThrow().hostVersion()
        );
        assertEquals(
            "5.3.02",
            ExportSettingsHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_02)
                .orElseThrow().hostVersion()
        );
        assertEquals(
            "5.3.03",
            ExportSettingsHostProfile.forArtifact(ReviewedHostArtifacts.CUBISM_5_3_03)
                .orElseThrow().hostVersion()
        );
        assertTrue(
            ExportSettingsHostProfile.forArtifact(
                new dev.turboism.mapping.verification.HostArtifactDigest(
                    1L, "0".repeat(64)
                )
            ).isEmpty()
        );
        assertEquals(
            List.of("5.2.03", "5.3.02", "5.3.03"),
            ExportSettingsHostProfile.supportedHostVersions()
        );
    }
}
