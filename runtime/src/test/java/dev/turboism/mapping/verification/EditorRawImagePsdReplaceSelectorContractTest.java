package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import dev.turboism.mapping.verification.selector.EditorRawImagePsdReplaceSelectorContract;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Static exact-JAR evidence for the internal T015 native replace seam. */
class EditorRawImagePsdReplaceSelectorContractTest {
    private static final Path LEGACY_EVIDENCE = EditorSelectorContractTestPaths.legacyEvidence();
    private static final Path ARTIFACT =
            LEGACY_EVIDENCE == null ? null : LEGACY_EVIDENCE.resolve("Cubism-5.3.02/jars/Live2D_Cubism.jar");
    private static final HostArtifactFingerprint FINGERPRINT = new HostArtifactFingerprint(
            ReviewedHostArtifacts.CUBISM_5_3_02_VERSION,
            ReviewedHostArtifacts.CUBISM_5_3_02.size(),
            ReviewedHostArtifacts.CUBISM_5_3_02.sha256());

    @Test
    void exact5302JarVerifiesReplaceReceiverStateAndFiveArgumentEntry() throws Exception {
        assumeTrue(
                ARTIFACT != null && Files.isRegularFile(ARTIFACT),
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final List<StaticSelector> selectors = exactSelectors();
        final StaticVerificationReport report = new StaticSelectorVerifier().verify(ARTIFACT, FINGERPRINT, selectors);

        assertTrue(
                report.allSelectorsVerified(),
                report.results().stream()
                        .filter(result -> result.status() != StaticVerificationStatus.VERIFIED_STATIC)
                        .map(result -> result.alias() + ": " + result.message())
                        .toList()
                        .toString());
        assertEquals(
                EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES,
                selectors.stream().map(StaticSelector::alias).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void verifiesNativeTransactionEvidenceSeparatelyWithoutAdmittingUnusedAliases() throws Exception {
        assumeTrue(
                ARTIFACT != null && Files.isRegularFile(ARTIFACT),
                "legacy Cubism evidence is not staged on this machine; exact-artifact verification skips");
        final List<StaticSelector> selectors = transactionEvidenceSelectors();
        final StaticVerificationReport report = new StaticSelectorVerifier().verify(ARTIFACT, FINGERPRINT, selectors);

        assertTrue(
                report.allSelectorsVerified(),
                report.results().stream()
                        .filter(result -> result.status() != StaticVerificationStatus.VERIFIED_STATIC)
                        .map(result -> result.alias() + ": " + result.message())
                        .toList()
                        .toString());
        assertEquals(
                TRANSACTION_EVIDENCE_ALIASES,
                selectors.stream().map(StaticSelector::alias).collect(java.util.stream.Collectors.toSet()));
        assertTrue(java.util.Collections.disjoint(
                EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES, TRANSACTION_EVIDENCE_ALIASES));
    }

    @Test
    void contractRequiresExact5302AndDoesNotAuthorizeOtherVersionsByItself() {
        assertEquals("5.3.02", EditorRawImagePsdReplaceSelectorContract.SUPPORTED_CUBISM_VERSION);
        assertEquals(
                "cubism.editor-model.psd.raw-image.replace", EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID);
        assertEquals(
                EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES.size(),
                new HashSet<>(EditorRawImagePsdReplaceSelectorContract.REQUIRED_ALIASES).size());
    }

    @Test
    void replaceAdmissionIsScopedToThe5302ModelRecord() {
        assertTrue(EditorModelVerificationManifest.cubism5302Capabilities()
                .contains(EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID));
        assertFalse(EditorModelVerificationManifest.CAPABILITY_IDS.contains(
                EditorRawImagePsdReplaceSelectorContract.CAPABILITY_ID));
        assertFalse(EditorModelVerificationManifest.cubism5303StaticAliases()
                .contains(EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_REPLACE_ALIAS));
    }

    private static List<StaticSelector> exactSelectors() {
        return List.of(
                classSelector(
                        EditorRawImagePsdReplaceSelectorContract.APP_CONTROLLER_CLASS_ALIAS,
                        "com/live2d/cubism/CEAppCtrl"),
                classSelector(
                        EditorRawImagePsdReplaceSelectorContract.MODELING_DOCUMENT_CLASS_ALIAS,
                        "com/live2d/cubism/doc/modeling/CModelingDocument"),
                method(
                        EditorRawImagePsdReplaceSelectorContract.CURRENT_EDIT_MODE_ALIAS,
                        "com/live2d/cubism/doc/modeling/CModelingDocument",
                        "getCurrentEditMode",
                        "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Base;"),
                method(
                        EditorRawImagePsdReplaceSelectorContract.EDITING_STATE_ALIAS,
                        "com/live2d/cubism/doc/ACEditMode",
                        "isEditing",
                        "()Z"),
                classSelector(
                        EditorRawImagePsdReplaceSelectorContract.LAYERED_IMAGE_CLASS_ALIAS,
                        "com/live2d/cubism/doc/resources/CLayeredImage"),
                classSelector(
                        EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_CLASS_ALIAS,
                        "com/live2d/cubism/process/psd/a"),
                field(
                        EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_PROCESS_INSTANCE_ALIAS,
                        "com/live2d/cubism/process/psd/a",
                        "a",
                        "Lcom/live2d/cubism/process/psd/a;",
                        StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method(
                        EditorRawImagePsdReplaceSelectorContract.PSD_IMPORT_REPLACE_ALIAS,
                        "com/live2d/cubism/process/psd/a",
                        "a",
                        "(Lcom/live2d/cubism/CEAppCtrl;"
                                + "Lcom/live2d/cubism/doc/resources/CLayeredImage;"
                                + "Ljava/io/File;"
                                + "Lcom/live2d/cubism/doc/modeling/CModelingDocument;"
                                + "Ljava/util/List;)V"));
    }

    /**
     * Report-only exact evidence for the transaction boundary observed in the native method
     * body. These aliases are verified by static evidence tests and are deliberately absent
     * from the generated contract: they are not production admission.
     */
    private static final java.util.Set<String> TRANSACTION_EVIDENCE_ALIASES = java.util.Set.of(
            "cubism.editor-model.psd-import.native-edit-mode.class",
            "cubism.editor-model.psd-import.native-edit-mode.begin",
            "cubism.editor-model.psd-import.native-edit-mode.end",
            "cubism.editor-model.psd-import.group-undo.class");

    private static List<StaticSelector> transactionEvidenceSelectors() {
        return List.of(
                classSelector(
                        "cubism.editor-model.psd-import.native-edit-mode.class", "com/live2d/cubism/doc/ACEditMode"),
                method(
                        "cubism.editor-model.psd-import.native-edit-mode.begin",
                        "com/live2d/cubism/doc/ACEditMode",
                        "beginEdit",
                        "(Ljava/lang/String;)Lcom/live2d/undo/GroupUndo;"),
                method(
                        "cubism.editor-model.psd-import.native-edit-mode.end",
                        "com/live2d/doc/IEditMode",
                        "endEdit",
                        "(ZLkotlin/jvm/functions/Function1;)Z"),
                classSelector("cubism.editor-model.psd-import.group-undo.class", "com/live2d/undo/GroupUndo"));
    }

    private static StaticSelector classSelector(final String alias, final String owner) {
        return new StaticSelector(
                alias, alias, StaticSelector.Kind.CLASS, owner, "", "", StaticSelector.ACCESS_PUBLIC, 0);
    }

    private static StaticSelector method(
            final String alias, final String owner, final String member, final String descriptor) {
        return StaticSelector.method(alias, owner, member, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static StaticSelector field(
            final String alias,
            final String owner,
            final String member,
            final String descriptor,
            final int requiredAccessFlags) {
        return StaticSelector.field(alias, owner, member, descriptor, requiredAccessFlags);
    }
}
