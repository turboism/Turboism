package dev.turboism.tests.mapping;

import dev.turboism.mapping.verification.selector.EditorClipMaskReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.selector.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.selector.EditorPsdSnapshotSelectorContract;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.mapping.verification.AutoBackupVerificationManifest;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.selector.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.ControlAppearanceVerificationManifest;
import dev.turboism.mapping.verification.CorePublicApiTrustRoots;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
import dev.turboism.mapping.verification.TopMenuVerificationManifest;
import dev.turboism.mapping.verification.WorkspaceControlVerificationManifest;
import dev.turboism.mapping.verification.BoundingBoxOverlayButtonVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import dev.turboism.mapping.verification.StatusBarVerificationManifest;
import dev.turboism.mapping.verification.StaticVerificationRecordValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StaticVerificationRecordRepositoryTest {

    private static final Path PROJECT_ROOT = Paths.get(
        System.getProperty("projectRoot", System.getProperty("user.dir"))
    );
    private static final Path RECORDS = PROJECT_ROOT.resolve("cubism-ref/verification");

    private static final Map<String, SliceExpectation> EXPECTATIONS = withPerformance(withClipMask52(withAutoBackup(withStatusBar(withWorkspaceControl(withControlAppearance53(withBoundingBoxOverlays(withTopMenus(withEmbeddedPanels(Map.of(
        "cubism-ref/verification/cubism-5.2.03-project-workspace.json",
        new SliceExpectation(
            "m15.cubism-5.2.03.project-workspace.static",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.03",
            "cubism-5.2.03",
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            "59ac1ee40d386aed22b6f3f8c6eb0fe876c5af69190affd7f0c00209d1f12de4",
            23,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.methodAliasesUsed(),
            difference(
                VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
                VerifiedProjectWorkspaceHostOperations.methodAliasesUsed()
            ),
            "cubism-5.2.03-project-workspace",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-project-workspace.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
            "5.2.03",
            SliceKind.PROJECT_WORKSPACE
        ),
        "cubism-ref/verification/cubism-5.3.02-project-workspace.json",
        new SliceExpectation(
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.verificationId(),
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.cubismVersion(),
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.profileId(),
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.artifact().size(),
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.artifact().sha256(),
            ProjectWorkspaceVerificationManifest.RECORD_5_3_02.recordSha256(),
            23,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.methodAliasesUsed(),
            difference(
                VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
                VerifiedProjectWorkspaceHostOperations.methodAliasesUsed()
            ),
            "cubism-5.3.02-m14-project-workspace",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-m14-project-workspace.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "5.3.02",
            SliceKind.PROJECT_WORKSPACE
        ),
        "cubism-ref/verification/cubism-5.2.03-core-model-read.json",
        coreExpectation("5.2.03", "5.2.03", 36_237L,
            "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e",
            "7e5dd498d46b654671f80639bcc74c01ed5d7ab10a1d4b36914956b1567ffceb",
            72, "5.2.03"),
        "cubism-ref/verification/cubism-5.3.02-clipmask.json",
        new SliceExpectation(
            ClipMaskVerificationManifest.RECORD_5_3_02.verificationId(),
            ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
            ClipMaskVerificationManifest.RECORD_5_3_02.cubismVersion(),
            ClipMaskVerificationManifest.RECORD_5_3_02.profileId(),
            ClipMaskVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ClipMaskVerificationManifest.RECORD_5_3_02.artifact().size(),
            ClipMaskVerificationManifest.RECORD_5_3_02.artifact().sha256(),
            ClipMaskVerificationManifest.RECORD_5_3_02.recordSha256(),
            16,
            ClipMaskVerificationManifest.REQUIRED_ALIASES,
            VerifiedClipMaskHostOperations.REQUIRED_ALIASES,
            VerifiedClipMaskHostOperations.methodAliasesUsed(),
            VerifiedClipMaskHostOperations.classAliasesUsed(),
            "cubism-5.3.02-m15-clipmask",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "5.3.02",
            SliceKind.CLIP_MASK
        ),
        "cubism-ref/verification/cubism-5.2.03-ui-main-toolbar.json",
        new SliceExpectation(
            MainToolbarVerificationManifest.RECORD_5_2_03.verificationId(),
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.RECORD_5_2_03.cubismVersion(),
            MainToolbarVerificationManifest.RECORD_5_2_03.profileId(),
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            MainToolbarVerificationManifest.RECORD_5_2_03.recordSha256(),
            28,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            mainToolbarMethodAliases(),
            difference(MainToolbarVerificationManifest.REQUIRED_ALIASES, mainToolbarMethodAliases()),
            "cubism-5.2.03-ui-main-toolbar",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-ui-main-toolbar.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
            "5.2.03",
            SliceKind.EDITOR_UI
        ),
        "cubism-ref/verification/cubism-5.3.02-ui-main-toolbar.json",
        new SliceExpectation(
            MainToolbarVerificationManifest.RECORD_5_3_02.verificationId(),
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.RECORD_5_3_02.cubismVersion(),
            MainToolbarVerificationManifest.RECORD_5_3_02.profileId(),
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            MainToolbarVerificationManifest.RECORD_5_3_02.artifact().size(),
            MainToolbarVerificationManifest.RECORD_5_3_02.artifact().sha256(),
            MainToolbarVerificationManifest.RECORD_5_3_02.recordSha256(),
            28,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            mainToolbarMethodAliases(),
            difference(MainToolbarVerificationManifest.REQUIRED_ALIASES, mainToolbarMethodAliases()),
            "cubism-5.3.02-ui-main-toolbar",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-main-toolbar.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "5.3.02",
            SliceKind.EDITOR_UI
        ),
        "cubism-ref/verification/cubism-5.2.03-ui-control-appearance.json",
        controlAppearanceExpectation(
            "5.2.03",
            "cubism-5.2.03",
            ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            "429a9bc1f4ae9fe6d38cbdfd5d418d14a236860a0a9db0771a443b4cdb28ae5c",
            "cubism-5.2.03-ui-control-appearance",
            "5.2.03"
        ),
        "cubism-ref/verification/cubism-5.2.03-editor-model.json",
        new SliceExpectation(
            "cubism-5.2.03.editor-model.static",
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.03",
            "cubism-5.2.03",
            EditorModelVerificationManifest.cubism52Capabilities(),
            "Live2D_Cubism.jar",
            ReviewedHostArtifacts.CUBISM_5_2_03.size(),
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            "4224231c56683f855a8e8c0ffe3c0be1d1035192f908de3f4d80fbd66d4f4b96",
            589,
            EditorModelVerificationManifest.cubism52Aliases(),
            EditorModelVerificationManifest.cubism52Aliases(),
            recordMethodAliases("cubism-ref/verification/cubism-5.2.03-editor-model.json"),
            recordClassAliases("cubism-ref/verification/cubism-5.2.03-editor-model.json"),
            "cubism-5.2.03-editor-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-editor-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
            "5.2.03",
            SliceKind.EDITOR_MODEL
        ),
        "cubism-ref/verification/cubism-5.3.02-editor-model.json",
        new SliceExpectation(
            EditorModelVerificationManifest.RECORD_5_3_02.verificationId(),
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.RECORD_5_3_02.cubismVersion(),
            EditorModelVerificationManifest.RECORD_5_3_02.profileId(),
            EditorModelVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            EditorModelVerificationManifest.RECORD_5_3_02.artifact().size(),
            EditorModelVerificationManifest.RECORD_5_3_02.artifact().sha256(),
            EditorModelVerificationManifest.RECORD_5_3_02.recordSha256(),
            611,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            recordMethodAliases("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            recordClassAliases("cubism-ref/verification/cubism-5.3.02-editor-model.json"),
            "cubism-5.3.02-editor-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-editor-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "5.3.02",
            SliceKind.EDITOR_MODEL
        ),
        "cubism-ref/verification/cubism-5.3.02-core-model-read.json",
        coreExpectation("5.3.02", "5.3.02", 42_471L,
            "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4",
            "96ad896a86ed2fd1543c8ac6099adcf8cd7750483000b0ca4b13014ffe04a86d",
            74, "5.3.02")
    ))))))))));

    private static Map<String, SliceExpectation> withWorkspaceControl(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-workspace-control.json",
            workspaceControlExpectation(
                "5.2.03", "5.2.03", ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "f42efb4d878ac4dfb9398dfc978705217d1c55a21d9521690d77fede9af32fed",
                "m.workspace-5.2.03.control.static", "adapter.workspace.control.v5_2",
                "5.2.03"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-workspace-control.json",
            workspaceControlExpectation(
                "5.3.02", "5.3.02", ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                "7c675de8b23e63e6de14ae6c67403717d3b64fc8eefab54ac4124fffb3633f16",
                "m.workspace-5.3.02.control.static", "adapter.workspace.control.v5_3",
                "5.3.02"
            )
        );
        return Map.copyOf(expectations);
    }

    private static SliceExpectation workspaceControlExpectation(
        String profile, String version, long size, String artifactSha, String recordSha,
        String verificationId, String sliceId, String range
    ) {
        final Set<String> aliases = WorkspaceControlVerificationManifest.REQUIRED_ALIASES;
        return new SliceExpectation(
            verificationId, sliceId, version, "cubism-" + profile,
            Set.of(WorkspaceControlVerificationManifest.CAPABILITY_ID), "Live2D_Cubism.jar",
            size, artifactSha, recordSha, aliases.size(), aliases, aliases,
            difference(aliases, Set.of("workspace.app.class")), Set.of("workspace.app.class"),
            "cubism-" + profile + "-workspace-control",
            Path.of("cubism-ref/mapping-packs/draft/cubism-" + profile + "-workspace-control.json"),
            Path.of("cubism-ref/profiles/draft/cubism-" + profile + ".json"),
            range, SliceKind.EDITOR_UI
        );
    }

    private static Map<String, SliceExpectation> withEmbeddedPanels(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-ui-embedded-panel.json",
            new SliceExpectation(
                "cubism-5.2.03.ui-embedded-panel.static",
                EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                "cubism-5.2.03",
                EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "5ec9331ab80b79f6eff6777f282738bfbe26400620b2e27e23715963a23b7d89",
                78,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                embeddedPanelMethodAliases(),
                difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                "cubism-5.2.03-ui-embedded-panel",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-ui-embedded-panel.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
                "5.2.03",
                SliceKind.EDITOR_UI
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-embedded-panel.json",
            new SliceExpectation(
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.verificationId(),
                EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.cubismVersion(),
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.profileId(),
                EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.artifact().size(),
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.artifact().sha256(),
                EmbeddedPanelVerificationManifest.RECORD_5_3_02.recordSha256(),
                78,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                embeddedPanelMethodAliases(),
                difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                "cubism-5.3.02-ui-embedded-panel",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-embedded-panel.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
                "5.3.02",
                SliceKind.EDITOR_UI
            )
        );
        return Map.copyOf(expectations);
    }

    /**
     * Method-kind aliases declared by a reviewed record.
     *
     * <p>Editor-model has no {@code Verified*HostOperations.methodAliasesUsed()} to compare
     * against: its aliases are inline literals across 23 access classes. Whether the
     * implementation actually invokes them is checked by
     * {@code scripts/test/check_editor_model_aliases.py}, which derives the implementation side
     * by scanning. This expectation therefore only restates the record's own method/class split,
     * which is what the surrounding pack-binding assertions need.</p>
     */
    private static Set<String> recordMethodAliases(final String recordPath) {
        return recordAliasesOfKind(recordPath, false);
    }

    /** Class-kind aliases declared by a reviewed record; see {@link #recordMethodAliases}. */
    private static Set<String> recordClassAliases(final String recordPath) {
        return recordAliasesOfKind(recordPath, true);
    }

    private static Set<String> recordAliasesOfKind(final String recordPath, final boolean classes) {
        try {
            final JsonNode root = new ObjectMapper().readTree(
                PROJECT_ROOT.resolve(recordPath).toFile()
            );
            final Set<String> aliases = new HashSet<>();
            for (JsonNode selector : root.get("selectors")) {
                final boolean isClass = "class".equals(selector.get("kind").asText());
                if (isClass == classes) {
                    aliases.add(selector.get("alias").asText());
                }
            }
            return Set.copyOf(aliases);
        } catch (Exception failure) {
            throw new IllegalStateException("cannot read reviewed record " + recordPath, failure);
        }
    }

    private static Map<String, SliceExpectation> withPerformance(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-performance-render-scene.json",
            performanceExpectation(
                "cubism-5.2.03.performance.render-scene.static",
                "5.2.03",
                "cubism-5.2.03",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "7eb2ec2755b20f1dccbe97057b448d438655dc29ad4d4a1aaadb661c3bdef6c7",
                Set.of("cubism.performance.render-scene"),
                "cubism-5.2.03-performance-render-scene",
                "5.2.03"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-performance-render-scene.json",
            performanceExpectation(
                "cubism-5.3.02.performance.render-scene.static",
                "5.3.02",
                "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                "d1f858150f5ec2cf2be012a40d8f60dfc8639be7cfd87acd036d9ed735f86107",
                Set.of(
                    "cubism.performance.render-scene",
                    "cubism.performance.modeling-pre-render-update",
                    "cubism.performance.render-system",
                    "cubism.performance.scene-traversal",
                    "cubism.performance.renderer-dispatch",
                    "cubism.performance.update-model-instances",
                    "cubism.performance.reinit-model-instance"
                ),
                "cubism-5.3.02-performance-render-scene",
                "5.3.02"
            )
        );
        return Map.copyOf(expectations);
    }

    private static SliceExpectation performanceExpectation(
        final String verificationId,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final Set<String> aliases,
        final String packId,
        final String expectedCubismVersion
    ) {
        return new SliceExpectation(
            verificationId,
            "adapter.cubism.performance.fps-hook",
            cubismVersion,
            profileId,
            Set.of("turboism.performance.stats.read"),
            "Live2D_Cubism.jar",
            artifactSize,
            artifactSha256,
            recordSha256,
            aliases.size(),
            aliases,
            aliases,
            aliases,
            Set.of(),
            packId,
            Path.of("cubism-ref/mapping-packs/draft/" + packId + ".json"),
            Path.of("cubism-ref/profiles/draft/" + profileId + ".json"),
            expectedCubismVersion,
            SliceKind.PERFORMANCE
        );
    }

    private static Map<String, SliceExpectation> withClipMask52(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-clipmask.json",
            new SliceExpectation(
                ClipMaskVerificationManifest.RECORD_5_2_03.verificationId(),
                ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
                ClipMaskVerificationManifest.RECORD_5_2_03.cubismVersion(),
                ClipMaskVerificationManifest.RECORD_5_2_03.profileId(),
                ClipMaskVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                ClipMaskVerificationManifest.RECORD_5_2_03.artifact().size(),
                ClipMaskVerificationManifest.RECORD_5_2_03.artifact().sha256(),
                ClipMaskVerificationManifest.RECORD_5_2_03.recordSha256(),
                16,
                ClipMaskVerificationManifest.REQUIRED_ALIASES,
                VerifiedClipMaskHostOperations.REQUIRED_ALIASES,
                VerifiedClipMaskHostOperations.methodAliasesUsed(),
                VerifiedClipMaskHostOperations.classAliasesUsed(),
                "cubism-5.2.03-clipmask",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-clipmask.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
                "5.2.03",
                SliceKind.CLIP_MASK
            )
        );
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> withAutoBackup(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-autobackup.json",
            autoBackupExpectation(
                AutoBackupVerificationManifest.VERIFICATION_ID_53,
                AutoBackupVerificationManifest.CUBISM_VERSION_53,
                AutoBackupVerificationManifest.PROFILE_ID_53,
                AutoBackupVerificationManifest.ARTIFACT_SIZE_53,
                AutoBackupVerificationManifest.ARTIFACT_SHA256_53,
                AutoBackupVerificationManifest.RECORD_SHA256_53,
                "cubism-5.3.02-autobackup",
                "cubism-ref/profiles/draft/cubism-5.3.02.json",
                "5.3.02"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-autobackup.json",
            autoBackupExpectation(
                AutoBackupVerificationManifest.VERIFICATION_ID_52,
                AutoBackupVerificationManifest.CUBISM_VERSION_52,
                AutoBackupVerificationManifest.PROFILE_ID_52,
                AutoBackupVerificationManifest.ARTIFACT_SIZE_52,
                AutoBackupVerificationManifest.ARTIFACT_SHA256_52,
                AutoBackupVerificationManifest.RECORD_SHA256_52,
                "cubism-5.2.03-autobackup",
                "cubism-ref/profiles/draft/cubism-5.2.03.json",
                "5.2.03"
            )
        );
        return Map.copyOf(expectations);
    }

    private static SliceExpectation autoBackupExpectation(
        final String verificationId,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final String packId,
        final String profilePath,
        final String expectedCubismVersion
    ) {
        final Set<String> aliases = AutoBackupVerificationManifest.REQUIRED_ALIASES;
        return new SliceExpectation(
            verificationId,
            AutoBackupVerificationManifest.ADAPTER_SLICE_ID,
            cubismVersion,
            profileId,
            AutoBackupVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            artifactSize,
            artifactSha256,
            recordSha256,
            aliases.size(),
            aliases,
            aliases,
            difference(aliases, Set.of("cubism.auto-backup.manager.class",
                "cubism.auto-backup.app-controller.class",
                "cubism.auto-backup.complete-pack.class",
                "cubism.auto-backup.file-content.class")),
            Set.of("cubism.auto-backup.manager.class", "cubism.auto-backup.app-controller.class",
                "cubism.auto-backup.complete-pack.class", "cubism.auto-backup.file-content.class"),
            packId,
            Path.of("cubism-ref/mapping-packs/draft/" + packId + ".json"),
            Path.of(profilePath),
            expectedCubismVersion,
            SliceKind.EDITOR_UI
        );
    }

    private static Map<String, SliceExpectation> withStatusBar(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-ui-status-bar.json",
            new SliceExpectation(
                "cubism-5.2.03.ui-status-bar.static",
                StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                "cubism-5.2.03",
                StatusBarVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "45e529ae8771adfd00500100214afa9fa14fa463e967a227806e4e3a9f3e66a5",
                21,
                StatusBarVerificationManifest.REQUIRED_ALIASES,
                StatusBarVerificationManifest.REQUIRED_ALIASES,
                statusBarMethodAliases(),
                difference(StatusBarVerificationManifest.REQUIRED_ALIASES, statusBarMethodAliases()),
                "cubism-5.2.03-ui-status-bar",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-ui-status-bar.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
                "5.2.03",
                SliceKind.EDITOR_UI
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-status-bar.json",
            new SliceExpectation(
                StatusBarVerificationManifest.RECORD_5_3_02.verificationId(),
                StatusBarVerificationManifest.ADAPTER_SLICE_ID,
                StatusBarVerificationManifest.RECORD_5_3_02.cubismVersion(),
                StatusBarVerificationManifest.RECORD_5_3_02.profileId(),
                StatusBarVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                StatusBarVerificationManifest.RECORD_5_3_02.artifact().size(),
                StatusBarVerificationManifest.RECORD_5_3_02.artifact().sha256(),
                StatusBarVerificationManifest.RECORD_5_3_02.recordSha256(),
                21,
                StatusBarVerificationManifest.REQUIRED_ALIASES,
                StatusBarVerificationManifest.REQUIRED_ALIASES,
                statusBarMethodAliases(),
                difference(StatusBarVerificationManifest.REQUIRED_ALIASES, statusBarMethodAliases()),
                "cubism-5.3.02-ui-status-bar",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-status-bar.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
                "5.3.02",
                SliceKind.EDITOR_UI
            )
        );
        return Map.copyOf(expectations);
    }

    private static Set<String> statusBarMethodAliases() {
        return Set.of(
            "cubism.ui-status-bar.app-controller.instance",
            "cubism.ui-status-bar.app-controller.main-frame",
            "cubism.ui-status-bar.main-frame-controller.frame",
            "cubism.ui-status-bar.frame.content-pane",
            "cubism.ui-status-bar.widget.set-name",
            "cubism.ui-status-bar.widget.set-tooltip",
            "cubism.ui-status-bar.widget.revalidate",
            "cubism.ui-status-bar.widget.repaint",
            "cubism.ui-status-bar.container.children",
            "cubism.ui-status-bar.container.add",
            "cubism.ui-status-bar.container.remove",
            "cubism.ui-status-bar.label.text",
            "cubism.ui-status-bar.label.set-text",
            "cubism.ui-status-bar.label.create"
        );
    }

    private static Map<String, SliceExpectation> withTopMenus(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-ui-top-menu.json",
            new SliceExpectation(
                "cubism-5.2.03.ui-top-menu.static",
                TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                "cubism-5.2.03",
                TopMenuVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "a704c04bad828734044d5167cde0dabc3e1f91314dd46e74c1fc008278f2c12e",
                82,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                topMenuMethodAliases(),
                difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                "cubism-5.2.03-ui-top-menu",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2.03-ui-top-menu.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.03.json"),
                "5.2.03",
                SliceKind.EDITOR_UI
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-top-menu.json",
            new SliceExpectation(
                TopMenuVerificationManifest.RECORD_5_3_02.verificationId(),
                TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                TopMenuVerificationManifest.RECORD_5_3_02.cubismVersion(),
                TopMenuVerificationManifest.RECORD_5_3_02.profileId(),
                TopMenuVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                TopMenuVerificationManifest.RECORD_5_3_02.artifact().size(),
                TopMenuVerificationManifest.RECORD_5_3_02.artifact().sha256(),
                TopMenuVerificationManifest.RECORD_5_3_02.recordSha256(),
                82,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                topMenuMethodAliases(),
                difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                "cubism-5.3.02-ui-top-menu",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-top-menu.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
                "5.3.02",
                SliceKind.EDITOR_UI
            )
        );
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> withControlAppearance53(
        final Map<String, SliceExpectation> base
    ) {
        final Map<String, SliceExpectation> expectations = new LinkedHashMap<>(base);
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-control-appearance.json",
            controlAppearanceExpectation(
                "5.3.02",
                "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                "44c3e370bd4488fa86f231b37235547969f67b3eb35a41e3c4306d18e2879927",
                "cubism-5.3.02-ui-control-appearance",
                "5.3.02"
            )
        );
        return Map.copyOf(expectations);
    }

    private static SliceExpectation controlAppearanceExpectation(
        final String version,
        final String profile,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final String packId,
        final String expectedCubismVersion
    ) {
        // Every alias the reviewed record declares with kind=class, listed rather than derived
        // so the expectation can disagree with the record.  was missing
        // while the record has always carried it.
        final Set<String> classAliases = Set.of(
            "cubism.ui-control-appearance.art-mesh.source-class",
            "cubism.ui-control-appearance.deformer-source.class",
            "cubism.ui-control-appearance.deformer-control.outer-class",
            "cubism.ui-control-appearance.deformer-control.tree-class",
            "cubism.ui-control-appearance.parameter.single-class",
            "cubism.ui-control-appearance.parameter.double-class",
            "cubism.ui-control-appearance.parameter.folder-class",
            "cubism.ui-control-appearance.parameter.source-class",
            "cubism.ui-control-appearance.parameter.folder-source-class",
            "cubism.ui-control-appearance.parameter.label-class",
            "cubism.ui-control-appearance.part.node-class",
            "cubism.ui-control-appearance.part.source-class"
        );
        return new SliceExpectation(
            "cubism-" + version + ".ui-control-appearance.static",
            ControlAppearanceVerificationManifest.ADAPTER_SLICE_ID,
            version,
            profile,
            ControlAppearanceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            artifactSize,
            artifactSha256,
            recordSha256,
            40,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
            difference(ControlAppearanceVerificationManifest.REQUIRED_ALIASES, classAliases),
            classAliases,
            packId,
            Path.of("cubism-ref/mapping-packs/draft/" + packId + ".json"),
            Path.of("cubism-ref/profiles/draft/" + profile + ".json"),
            expectedCubismVersion,
            SliceKind.EDITOR_UI
        );
    }

    private static Map<String, SliceExpectation> withBoundingBoxOverlays(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2.03-ui-bounding-box-overlay.json",
            boundingBoxOverlayExpectation(
                "cubism-5.2.03.ui-bounding-box-overlay.static",
                "5.2.03",
                "cubism-5.2.03",
                ReviewedHostArtifacts.CUBISM_5_2_03.size(),
                ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
                "f2e39e94f199f54833413965a7d510b7b34b20b7c3cd20adde53b44353d7c291",
                "cubism-5.2.03-ui-bounding-box-overlay",
                "5.2.03"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-bounding-box-overlay.json",
            boundingBoxOverlayExpectation(
                "cubism-5.3.02.ui-bounding-box-overlay.static",
                "5.3.02",
                "cubism-5.3.02",
                ReviewedHostArtifacts.CUBISM_5_3_02.size(),
                ReviewedHostArtifacts.CUBISM_5_3_02.sha256(),
                "606a1837c03b00c62c8711dcb5eb53fe04eb7025f78736281a2e2afacd21ce54",
                "cubism-5.3.02-ui-bounding-box-overlay",
                "5.3.02"
            )
        );
        return Map.copyOf(expectations);
    }

    private static SliceExpectation boundingBoxOverlayExpectation(
        final String verificationId,
        final String cubismVersion,
        final String profileId,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final String packId,
        final String expectedCubismVersion
    ) {
        return new SliceExpectation(
            verificationId,
            BoundingBoxOverlayButtonVerificationManifest.ADAPTER_SLICE_ID,
            cubismVersion,
            profileId,
            BoundingBoxOverlayButtonVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            artifactSize,
            artifactSha256,
            recordSha256,
            27,
            BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
            BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
            BoundingBoxOverlayButtonVerificationManifest.REQUIRED_ALIASES,
            Set.of(),
            packId,
            Path.of("cubism-ref/mapping-packs/draft/" + packId + ".json"),
            Path.of("cubism-ref/profiles/draft/" + profileId + ".json"),
            expectedCubismVersion,
            SliceKind.EDITOR_UI
        );
    }

    private static Set<String> topMenuMethodAliases() {
        return Set.of(
            "cubism.ui-top-menu.app-controller.instance",
            "cubism.ui-top-menu.app-controller.main-frame",
            "cubism.ui-top-menu.main-frame.window",
            "cubism.ui-top-menu.menu.add",
            "cubism.ui-top-menu.menu-bar.add",
            "cubism.ui-top-menu.menu-bar.menus",
            "cubism.ui-top-menu.menu-bar.swing",
            "cubism.ui-top-menu.menu.create",
            "cubism.ui-top-menu.menu-item.create",
            "cubism.ui-top-menu.menu.swing",
            "cubism.editor-command.canvas.begin-edit",
            "cubism.editor-command.canvas.canvas",
            "cubism.editor-command.canvas.companion",
            "cubism.editor-command.canvas.complete-pack",
            "cubism.editor-command.canvas.current-view-context",
            "cubism.editor-command.canvas.doc-size",
            "cubism.editor-command.canvas.edit-mode",
            "cubism.editor-command.canvas.end-edit-default",
            "cubism.editor-command.canvas.group-add",
            "cubism.editor-command.canvas.handler",
            "cubism.editor-command.canvas.is-editing",
            "cubism.editor-command.canvas.mark-dirty",
            "cubism.editor-command.canvas.model",
            "cubism.editor-command.canvas.model-source",
            "cubism.editor-command.canvas.modeling-doc",
            "cubism.editor-command.canvas.notify-size",
            "cubism.editor-command.canvas.pixel-height",
            "cubism.editor-command.canvas.pixel-width",
            "cubism.editor-command.canvas.scale-with-anchor",
            "cubism.editor-command.canvas.set-pixel-height",
            "cubism.editor-command.canvas.set-pixel-width",
            "cubism.editor-command.canvas.simple-undo",
            "cubism.editor-command.canvas.size-height",
            "cubism.editor-command.canvas.size-width",
            "cubism.editor-command.canvas.undo",
            "cubism.editor-command.canvas.undo-manager",
            "cubism.editor-command.canvas.undo-pos",
            "cubism.editor-command.canvas.vector2",
            "cubism.editor-command.canvas.vector2-zero",
            "cubism.editor-command.config.instance",
            "cubism.editor-command.config.read",
            "cubism.editor-command.config.write",
            "cubism.editor-command.external-app.companion",
            "cubism.editor-command.external-app.connected",
            "cubism.editor-command.external-app.get-port",
            "cubism.editor-command.external-app.get-remote",
            "cubism.editor-command.external-app.instance",
            "cubism.editor-command.external-app.manager",
            "cubism.editor-command.external-app.set-port",
            "cubism.editor-command.external-app.set-remote",
            "cubism.editor-command.external-app.start",
            "cubism.editor-command.external-app.stop",
            "cubism.editor-command.file.save-model",
            "cubism.editor-command.file.save-scene",
            "cubism.editor-command.file.scene-content",
            "cubism.editor-command.grid.all-view-contexts",
            "cubism.editor-command.grid.color-create",
            "cubism.editor-command.grid.developer-setting",
            "cubism.editor-command.grid.entity",
            "cubism.editor-command.grid.entity-from-draw",
            "cubism.editor-command.grid.get-bold",
            "cubism.editor-command.grid.get-color",
            "cubism.editor-command.grid.get-jcolor",
            "cubism.editor-command.grid.get-spacing",
            "cubism.editor-command.grid.modeling-draw",
            "cubism.editor-command.grid.repaint-default",
            "cubism.editor-command.grid.set-color",
            "cubism.editor-command.grid.set-reset",
            "cubism.editor-command.grid.set-spacing",
            "cubism.editor-command.grid.update-manager",
            "cubism.editor-command.resize.guard",
            "cubism.editor-command.resize.guard-active",
            "cubism.editor-command.resize.guard-current",
            "cubism.editor-command.resize.scale-model",
            "cubism.ui-top-menu.widget.name",
            "cubism.ui-top-menu.widget.repaint",
            "cubism.ui-top-menu.widget.revalidate",
            "cubism.ui-top-menu.widget.set-name",
            "cubism.ui-top-menu.window.menu-bar"
        );
    }

    private static Set<String> embeddedPanelMethodAliases() {
        return Set.of(
            "cubism.ui-panel.app-controller.instance",
            "cubism.ui-panel.app-controller.main-frame",
            "cubism.ui-panel.app-controller.repaint",
            "cubism.ui-panel.main-frame.dock-manager",
            "cubism.ui-panel.dock.palette-manager",
            "cubism.ui-panel.dock.set-palette-visible",
            "cubism.ui-panel.dock.update-window-menu",
            "cubism.ui-panel.palette-manager.get",
            "cubism.ui-panel.palette-manager.add",
            "cubism.ui-panel.palette-manager.close",
            "cubism.ui-panel.palette-manager.current-workspace",
            "cubism.ui-panel.workspace.activate",
            "cubism.ui-panel.workspace.palette-box-for",
            "cubism.ui-panel.palette-box.remove-tab",
            "cubism.ui-panel.palette-manager.remove-update",
            "cubism.ui-panel.palette-manager.main-frame-window",
            "cubism.ui-panel.palette-manager.verify-cleanup",
            "cubism.ui-panel.palette-manager.fire-state",
            "cubism.ui-panel.workspace.add-palette-frame",
            "cubism.ui-panel.workspace.remove-palette-frame",
            "cubism.ui-panel.workspace.first-palette-box",
            "cubism.ui-panel.palette-box.create",
            "cubism.ui-panel.palette-box.add-tab",
            "cubism.ui-panel.palette-box.set-selected",
            "cubism.ui-panel.palette-box.palettes",
            "cubism.ui-panel.palette-box.tab-panel",
            "cubism.ui-panel.tab-panel.entries",
            "cubism.ui-panel.tab-entry.palette",
            "cubism.ui-panel.tab-entry.button",
            "cubism.ui-panel.widget.jcomponent",
            "cubism.ui-panel.palette-frame.create",
            "cubism.ui-panel.palette-frame.root",
            "cubism.ui-panel.palette-frame.window",
            "cubism.ui-panel.palette-frame.dispose",
            "cubism.ui-panel.palette-frame.raw-disposed",
            "cubism.ui-panel.floating-tab-close.operation",
            "cubism.ui-panel.floating-tab-close.palette-field",
            "cubism.ui-panel.workspace.root-container",
            "cubism.ui-panel.root.component",
            "cubism.ui-panel.split.contents",
            "cubism.ui-panel.split.remove",
            "cubism.ui-panel.component.palette-count",
            "cubism.ui-panel.root.set-component",
            "cubism.ui-panel.window.set-visible",
            "cubism.ui-panel.palette-id.create",
            "cubism.ui-panel.palette.create",
            "cubism.ui-panel.palette.set-panel",
            "cubism.ui-panel.swing-container.create",
            "cubism.ui-panel.main-frame.window",
            "cubism.ui-panel.window.menu-bar",
            "cubism.ui-panel.menu-bar.menus",
            "cubism.ui-panel.widget.name",
            "cubism.ui-panel.widget.set-name",
            "cubism.ui-panel.widget.revalidate",
            "cubism.ui-panel.widget.repaint",
            "cubism.ui-panel.menu.items",
            "cubism.ui-panel.menu.add",
            "cubism.ui-panel.menu.swing",
            "cubism.ui-panel.menu-item.create",
            "cubism.ui-panel.menu-item.check.create",
            "cubism.ui-panel.menu-item.swing",
            "cubism.ui-panel.menu-item.is-selected",
            "cubism.ui-panel.dock.main-frame-ctrl",
            "cubism.ui-panel.main-frame.palette-menu-map",
            "cubism.ui-panel.palette.id",
            "cubism.ui-panel.dock-tab-popup.operation",
            "cubism.ui-panel.dock-tab-popup.palette-field",
            "cubism.ui-panel.dock-tab-popup.menu-append"
        );
    }

    private static Set<String> mainToolbarMethodAliases() {
        return Set.of(
            "cubism.ui-main-toolbar.app-controller.instance",
            "cubism.ui-main-toolbar.app-controller.main-frame",
            "cubism.ui-main-toolbar.main-frame.view",
            "cubism.ui-main-toolbar.main-frame-view.home-button",
            "cubism.ui-main-toolbar.widget.parent",
            "cubism.ui-main-toolbar.widget.name",
            "cubism.ui-main-toolbar.widget.set-name",
            "cubism.ui-main-toolbar.widget.set-tooltip",
            "cubism.ui-main-toolbar.widget.set-pref-width",
            "cubism.ui-main-toolbar.widget.set-pref-height",
            "cubism.ui-main-toolbar.widget.revalidate",
            "cubism.ui-main-toolbar.widget.repaint",
            "cubism.ui-main-toolbar.container.children",
            "cubism.ui-main-toolbar.container.add",
            "cubism.ui-main-toolbar.container.remove",
            "cubism.ui-main-toolbar.icon-button.create",
            "cubism.ui-main-toolbar.icon-button.set-rollover-icon",
            "cubism.ui-main-toolbar.icon.create",
            "cubism.ui-main-toolbar.main-frame-view.main-container",
            "cubism.ui-main-toolbar.vbox.create",
            "cubism.ui-main-toolbar.widget.jcomponent"
        );
    }




    private static SliceExpectation coreExpectation(
        final String profile,
        final String exactVersion,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final int selectorCount,
        final String expectedCubismVersion
    ) {
        final Set<String> aliases = CorePublicApiSelectorContract
            .requiredAliasesFor(profile)
            .orElseThrow();
        final Set<String> methods = CorePublicApiSelectorContract
            .structuralMethodAliasesFor(profile)
            .orElseThrow();
        final Set<String> versionMethods = CorePublicApiSelectorContract.VERSION_PROBE_ALIASES.stream()
            .filter(alias -> !alias.endsWith(".class"))
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        final Set<String> allMethods = new HashSet<>(methods);
        allMethods.addAll(versionMethods);
        return new SliceExpectation(
            CorePublicApiTrustRoots.verificationId(profile),
            CorePublicApiSelectorContract.ADAPTER_SLICE_ID,
            exactVersion,
            "cubism-" + profile,
            CorePublicApiSelectorContract.CAPABILITY_IDS,
            "Live2DCubismCore.jar",
            artifactSize,
            artifactSha256,
            recordSha256,
            selectorCount,
            aliases,
            aliases,
            Set.copyOf(allMethods),
            difference(aliases, allMethods),
            "cubism-" + profile + "-core-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-" + profile + "-core-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-" + profile + ".json"),
            expectedCubismVersion,
            SliceKind.CORE
        );
    }

    private final ObjectMapper mapper = new ObjectMapper();
    private final StaticVerificationRecordValidator validator = new StaticVerificationRecordValidator();
    private final MappingPackValidator mappingPackValidator = new MappingPackValidator();

    @Test
    void everyStaticVerificationRecordMatchesItsRegisteredRepositoryExpectation() throws Exception {
        assertTrue(Files.isDirectory(RECORDS), "static verification record directory must exist");
        final Map<String, Path> discovered = discoverRecords();
        assertFalse(discovered.isEmpty(), "at least one static verification record must exist");
        assertEquals(EXPECTATIONS.keySet(), discovered.keySet(),
            "registered expectations and tracked records must match bidirectionally");

        final Set<String> verificationIds = new HashSet<>();
        final Set<String> sliceVersions = new HashSet<>();
        for (Map.Entry<String, Path> discoveredRecord : discovered.entrySet()) {
            final JsonNode record = mapper.readTree(discoveredRecord.getValue().toFile());
            assertTrue(verificationIds.add(record.get("verificationId").asText()),
                "duplicate verificationId: " + record.get("verificationId").asText());
            final String sliceVersion = record.get("adapterSliceId").asText()
                + "@" + record.get("cubismVersion").asText();
            assertTrue(sliceVersions.add(sliceVersion),
                "duplicate (adapterSliceId,cubismVersion): " + sliceVersion);
            verifySlice(discoveredRecord.getValue(), record, EXPECTATIONS.get(discoveredRecord.getKey()));
        }
    }

    private Map<String, Path> discoverRecords() throws Exception {
        final Map<String, Path> discovered = new LinkedHashMap<>();
        try (Stream<Path> paths = Files.walk(RECORDS)) {
            for (Path path : paths.filter(Files::isRegularFile)
                .filter(candidate -> candidate.toString().endsWith(".json"))
                .sorted()
                .toList()) {
                final String relativePath = repositoryPath(path);
                assertTrue(discovered.put(relativePath, path) == null,
                    "duplicate static verification record path: " + relativePath);
            }
        }
        return Map.copyOf(discovered);
    }

    private void verifySlice(
        final Path recordPath,
        final JsonNode record,
        final SliceExpectation expectation
    ) throws Exception {
        final String repositoryPath = repositoryPath(recordPath);
        assertTrue(validator.validate(record, repositoryPath).isEmpty(),
            recordPath.getFileName() + " validation failed: " + validator.validate(record, repositoryPath));
        assertEquals(repositoryPath, record.get("evidencePath").asText(), "record must self-reference its path");
        assertEquals("VERIFIED_STATIC", record.get("status").asText());
        assertFalse(record.get("cubismVersion").asText().contains("[")
            || record.get("cubismVersion").asText().contains(","),
            "record must use an exact Cubism version");

        assertEquals(expectation.verificationId(), record.get("verificationId").asText());
        assertEquals(expectation.adapterSliceId(), record.get("adapterSliceId").asText());
        assertEquals(expectation.cubismVersion(), record.get("cubismVersion").asText());
        assertEquals(expectation.profileId(), record.get("profileId").asText());
        assertEquals(expectation.capabilityIds(), asStringSet(record.get("capabilityIds")));
        assertEquals(expectation.artifactName(), record.get("artifact").get("name").asText());
        assertEquals(expectation.artifactSize(), record.get("artifact").get("size").asLong());
        assertEquals(expectation.artifactSha256(), record.get("artifact").get("sha256").asText());
        assertEquals(expectation.recordSha256(), HostArtifactDigest.from(recordPath).sha256());
        assertEquals(expectation.selectorCount(), record.get("selectors").size(),
            "verified selector count drifted");

        final JsonNode pack = mapper.readTree(PROJECT_ROOT.resolve(expectation.packPath()).toFile());
        assertEquals("DRAFT", pack.get("status").asText(), "mapping pack readiness must remain DRAFT");
        if (EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID.equals(expectation.adapterSliceId())) {
            assertTrue(mappingPackValidator.validate(pack, expectation.packPath().toString()).isEmpty(),
                expectation.packPath() + " validation failed: "
                    + mappingPackValidator.validate(pack, expectation.packPath().toString()));
        }
        final String packVersion = pack.get("cubismVersion").asText();
        if (expectation.kind() == SliceKind.CORE
            || expectation.kind() == SliceKind.EDITOR_MODEL) {
            assertTrue(expectation.profileId().equals("cubism-" + packVersion),
                "Core mapping pack profile label drift");
        } else {
            assertEquals(expectation.cubismVersion(), packVersion, "mapping pack version drift");
        }
        final Map<String, JsonNode> packEntries = uniqueNodesBy(
            pack.get("entries"), "semanticName", expectation.packId() + " pack"
        );
        final Map<String, JsonNode> selectors = uniqueNodesBy(
            record.get("selectors"), "mappingId", expectation.adapterSliceId() + " record"
        );
        assertEquals(expectation.selectorCount(), packEntries.size(), "DRAFT dependency count drifted");
        assertEquals(packEntries.keySet(), selectors.keySet(),
            "record selectors and mapping entries must match bidirectionally");

        final Set<String> aliases = new HashSet<>();
        final Set<String> methodAliases = new HashSet<>();
        final Set<String> attestationClassAliases = new HashSet<>();
        for (Map.Entry<String, JsonNode> selectorEntry : selectors.entrySet()) {
            verifySelector(
                selectorEntry.getKey(),
                selectorEntry.getValue(),
                packEntries.get(selectorEntry.getKey()),
                expectation,
                aliases,
                methodAliases,
                attestationClassAliases
            );
        }
        if (!expectation.manifestAliases().equals(aliases)) {
            System.err.println("DIAG slice=" + expectation.verificationId()
                + " manifestAliases=" + expectation.manifestAliases().size()
                + " aliases=" + aliases.size()
                + " missing=" + (expectation.manifestAliases().size() - aliases.size()));
            try {
                java.nio.file.Files.writeString(
                    java.nio.file.Path.of("/tmp/diag-aliases.txt"),
                    "slice=" + expectation.verificationId() + " missing="
                        + difference(expectation.manifestAliases(), aliases) + "\n",
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND
                );
            } catch (Throwable ignored) { }
            for (String missing : difference(expectation.manifestAliases(), aliases)) {
                System.err.println("DIAG missing=" + missing);
            }
        }
        assertEquals(expectation.manifestAliases(), aliases, "manifest aliases drifted from verified record");
        assertEquals(expectation.implementationAliases(), aliases,
            "HostOperations required aliases drifted from verified record");
        assertEquals(expectation.methodAliases(), methodAliases,
            "implementation method aliases drifted from verified record");
        assertEquals(expectation.attestationClassAliases(), attestationClassAliases,
            "implementation attestation class aliases drifted from verified record");

        verifyProfile(expectation);
    }

    private static void verifySelector(
        final String mappingId,
        final JsonNode selector,
        final JsonNode packEntry,
        final SliceExpectation expectation,
        final Set<String> aliases,
        final Set<String> methodAliases,
        final Set<String> attestationClassAliases
    ) {
        assertTrue(aliases.add(selector.get("alias").asText()), mappingId + " duplicate alias");
        assertEquals("VERIFIED_STATIC", selector.get("status").asText(), mappingId + " selector status drift");
        assertEquals(packEntry.get("name").asText(), selector.get("alias").asText(), mappingId + " alias drift");
        assertEquals(packEntry.get("kind").asText(), selector.get("kind").asText(), mappingId + " kind drift");
        assertEquals(packEntry.get("x.verification").get("ownerInternalName").asText(),
            selector.get("ownerInternalName").asText(), mappingId + " owner drift");
        assertEquals(packEntry.get("x.verification").get("requiredAccessFlags").asInt(),
            selector.get("requiredAccessFlags").asInt(), mappingId + " required access drift");
        assertEquals(packEntry.get("x.verification").get("forbiddenAccessFlags").asInt(),
            selector.get("forbiddenAccessFlags").asInt(), mappingId + " forbidden access drift");
        assertEquals(expectation.profileId(), packEntry.get("profile").asText(), mappingId + " profile drift");
        assertEquals("DRAFT", packEntry.get("status").asText(), mappingId + " mapping readiness drift");

        if ("class".equals(selector.get("kind").asText())) {
            attestationClassAliases.add(selector.get("alias").asText());
            assertEquals(packEntry.get("runtime").asText(), selector.get("ownerInternalName").asText(),
                mappingId + " class owner drift");
            assertTrue(selector.get("memberName").isNull(), mappingId + " class member must be null");
            assertTrue(selector.get("descriptor").isNull(), mappingId + " class descriptor must be null");
        } else {
            methodAliases.add(selector.get("alias").asText());
            assertEquals(packEntry.get("runtime").asText(), selector.get("memberName").asText(),
                mappingId + " member drift");
            assertEquals(packEntry.get("descriptor").asText(), selector.get("descriptor").asText(),
                mappingId + " descriptor drift");
        }
    }

    private void verifyProfile(final SliceExpectation expectation) throws Exception {
        final JsonNode profile = mapper.readTree(PROJECT_ROOT.resolve(expectation.profilePath()).toFile());
        assertEquals("DRAFT", profile.get("status").asText(), "profile readiness must remain DRAFT");
        assertEquals(expectation.profileId(), profile.get("profileId").asText());
        assertTrue(asStringSet(profile.get("mappingPacks")).contains(expectation.packId()),
            "profile must reference " + expectation.packId());
        assertEquals(expectation.expectedCubismVersion(), profile.get("cubismVersion").asText(),
            "profile version range drifted");
        if (expectation.kind() == SliceKind.CLIP_MASK) {
            assertFalse(asStringSet(profile.get("mappingPacks"))
                    .contains("cubism-5.3.02-m15-project-workspace-clipmask"),
                "clip-mask evidence must not be coupled to a project/workspace pack");
        }
    }

    private static Map<String, JsonNode> uniqueNodesBy(
        final JsonNode array,
        final String identityField,
        final String source
    ) {
        final Map<String, JsonNode> nodes = new LinkedHashMap<>();
        for (JsonNode node : array) {
            final String identity = node.get(identityField).asText();
            assertTrue(nodes.put(identity, node) == null,
                source + " has duplicate " + identityField + ": " + identity);
        }
        return Map.copyOf(nodes);
    }

    private static String repositoryPath(final Path path) {
        return PROJECT_ROOT.relativize(path).toString().replace('\\', '/');
    }

    private static Set<String> intersection(final Set<String> left, final Set<String> right) {
        final Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        return Set.copyOf(intersection);
    }

    private static Set<String> difference(final Set<String> all, final Set<String> excluded) {
        final Set<String> difference = new HashSet<>(all);
        difference.removeAll(excluded);
        return Set.copyOf(difference);
    }

    private static Set<String> asStringSet(final JsonNode array) {
        final Set<String> values = new HashSet<>();
        array.forEach(value -> assertTrue(values.add(value.asText()), "duplicate array value: " + value.asText()));
        return Set.copyOf(values);
    }

    private enum SliceKind {
        PROJECT_WORKSPACE,
        CLIP_MASK,
        CORE,
        EDITOR_MODEL,
        EDITOR_UI,
        PERFORMANCE
    }

    private record SliceExpectation(
        String verificationId,
        String adapterSliceId,
        String cubismVersion,
        String profileId,
        Set<String> capabilityIds,
        String artifactName,
        long artifactSize,
        String artifactSha256,
        String recordSha256,
        int selectorCount,
        Set<String> manifestAliases,
        Set<String> implementationAliases,
        Set<String> methodAliases,
        Set<String> attestationClassAliases,
        String packId,
        Path packPath,
        Path profilePath,
        String expectedCubismVersion,
        SliceKind kind
    ) {
    }
}
