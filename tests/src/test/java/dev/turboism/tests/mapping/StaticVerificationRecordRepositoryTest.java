package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.ControlAppearanceVerificationManifest;
import dev.turboism.mapping.verification.CorePublicApiTrustRoots;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.EditorObjectWriteSelectorContract;
import dev.turboism.mapping.verification.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacitySelectorContract;
import dev.turboism.mapping.verification.EditorPartOpacity52SelectorContract;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.verification.EmbeddedPanelVerificationManifest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
import dev.turboism.mapping.verification.TopMenuVerificationManifest;
import dev.turboism.mapping.verification.WorkspaceControlVerificationManifest;
import dev.turboism.mapping.verification.BoundingBoxOverlayButtonVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
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

    private static final Map<String, SliceExpectation> EXPECTATIONS = withWorkspaceControl(withControlAppearance53(withBoundingBoxOverlays(withTopMenus(withEmbeddedPanels(Map.of(
        "cubism-ref/verification/cubism-5.2-project-workspace.json",
        new SliceExpectation(
            "m15.cubism-5.2.project-workspace.static",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "661be268ad68726eecfdad7ca385f0c52c0105f1180c72813547504b23f81003",
            23,
            ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
            VerifiedProjectWorkspaceHostOperations.methodAliasesUsed(),
            difference(
                VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES,
                VerifiedProjectWorkspaceHostOperations.methodAliasesUsed()
            ),
            "cubism-5.2-project-workspace",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-project-workspace.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
            "[5.2.0,5.3.0)",
            SliceKind.PROJECT_WORKSPACE
        ),
        "cubism-ref/verification/cubism-5.3.02-project-workspace.json",
        new SliceExpectation(
            ProjectWorkspaceVerificationManifest.VERIFICATION_ID,
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            ProjectWorkspaceVerificationManifest.CUBISM_VERSION,
            ProjectWorkspaceVerificationManifest.PROFILE_ID,
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ProjectWorkspaceVerificationManifest.ARTIFACT_SIZE,
            ProjectWorkspaceVerificationManifest.ARTIFACT_SHA256,
            ProjectWorkspaceVerificationManifest.RECORD_SHA256,
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
            "[5.3.02,5.3.03)",
            SliceKind.PROJECT_WORKSPACE
        ),
        "cubism-ref/verification/cubism-5.2-core-model-read.json",
        coreExpectation("5.2", "5.2.0", 36_237L,
            "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e",
            "067b6dc666ba8419d973ae88b7f3a5829083b54644f2a28abff4ac258521815d",
            72, "[5.2.0,5.3.0)"),
        "cubism-ref/verification/cubism-5.3.02-clipmask.json",
        new SliceExpectation(
            ClipMaskVerificationManifest.VERIFICATION_ID,
            ClipMaskVerificationManifest.ADAPTER_SLICE_ID,
            ClipMaskVerificationManifest.CUBISM_VERSION,
            ClipMaskVerificationManifest.PROFILE_ID,
            ClipMaskVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            ClipMaskVerificationManifest.ARTIFACT_SIZE,
            ClipMaskVerificationManifest.ARTIFACT_SHA256,
            ClipMaskVerificationManifest.RECORD_SHA256,
            16,
            ClipMaskVerificationManifest.REQUIRED_ALIASES,
            VerifiedClipMaskHostOperations.REQUIRED_ALIASES,
            VerifiedClipMaskHostOperations.methodAliasesUsed(),
            VerifiedClipMaskHostOperations.classAliasesUsed(),
            "cubism-5.3.02-m15-clipmask",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "[5.3.02,5.3.03)",
            SliceKind.CLIP_MASK
        ),
        "cubism-ref/verification/cubism-5.2-ui-main-toolbar.json",
        new SliceExpectation(
            "cubism-5.2.ui-main-toolbar.static",
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "2c6b0989633c8cd2f41eb742f29ce5bcfa74b62ed48647900cb46e4fca607bb8",
            25,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            mainToolbarMethodAliases(),
            difference(MainToolbarVerificationManifest.REQUIRED_ALIASES, mainToolbarMethodAliases()),
            "cubism-5.2-ui-main-toolbar",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-ui-main-toolbar.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
            "[5.2.0,5.3.0)",
            SliceKind.EDITOR_UI
        ),
        "cubism-ref/verification/cubism-5.3.02-ui-main-toolbar.json",
        new SliceExpectation(
            MainToolbarVerificationManifest.VERIFICATION_ID,
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            MainToolbarVerificationManifest.CUBISM_VERSION,
            MainToolbarVerificationManifest.PROFILE_ID,
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            MainToolbarVerificationManifest.ARTIFACT_SIZE,
            MainToolbarVerificationManifest.ARTIFACT_SHA256,
            MainToolbarVerificationManifest.RECORD_SHA256,
            25,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            MainToolbarVerificationManifest.REQUIRED_ALIASES,
            mainToolbarMethodAliases(),
            difference(MainToolbarVerificationManifest.REQUIRED_ALIASES, mainToolbarMethodAliases()),
            "cubism-5.3.02-ui-main-toolbar",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-main-toolbar.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "[5.3.02,5.3.03)",
            SliceKind.EDITOR_UI
        ),
        "cubism-ref/verification/cubism-5.2-ui-control-appearance.json",
        controlAppearanceExpectation(
            "5.2.03",
            "cubism-5.2",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "a1a9900374bc9a8637f2de0973206f1ac92bd59a93df6933ae5fd919b0134f6f",
            "cubism-5.2-ui-control-appearance",
            "[5.2.0,5.3.0)"
        ),
        "cubism-ref/verification/cubism-5.2-editor-model.json",
        new SliceExpectation(
            "cubism-5.2.editor-model.static",
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            editorModel52Capabilities(),
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "51c1a52d24f7a485936257e1b6fe8b452b33294c13953cb4fbdb26a0594d8549",
            260,
            editorModel52Aliases(),
            editorModel52Aliases(),
            editorModel52MethodAliases(),
            difference(editorModel52Aliases(), editorModel52MethodAliases()),
            "cubism-5.2-editor-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-editor-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
            "[5.2.0,5.3.0)",
            SliceKind.EDITOR_MODEL
        ),
        "cubism-ref/verification/cubism-5.3.02-editor-model.json",
        new SliceExpectation(
            EditorModelVerificationManifest.VERIFICATION_ID,
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            EditorModelVerificationManifest.CUBISM_VERSION,
            EditorModelVerificationManifest.PROFILE_ID,
            EditorModelVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            EditorModelVerificationManifest.ARTIFACT_SIZE,
            EditorModelVerificationManifest.ARTIFACT_SHA256,
            EditorModelVerificationManifest.RECORD_SHA256,
            265,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            editorModelMethodAliases(),
            difference(EditorModelVerificationManifest.REQUIRED_ALIASES, editorModelMethodAliases()),
            "cubism-5.3.02-editor-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-editor-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
            "[5.3.02,5.3.03)",
            SliceKind.EDITOR_MODEL
        ),
        "cubism-ref/verification/cubism-5.3.02-core-model-read.json",
        coreExpectation("5.3.02", "5.3.2", 42_471L,
            "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4",
            "b68770af94b43bafa92bbe06a3cb2017f89ed5d561c3bb08447d3eeca89d06d0",
            74, "[5.3.02,5.3.03)")
    ))))));

    private static Map<String, SliceExpectation> withWorkspaceControl(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2-workspace-control.json",
            workspaceControlExpectation(
                "5.2", "5.2.03", 40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
                "8b001802fa672ce2f053ab516af9c38b2a2a08296fc663e9adf352e88c7dbf36",
                "m.workspace-5.2.03.control.static", "adapter.workspace.control.v5_2",
                "[5.2.0,5.3.0)"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-workspace-control.json",
            workspaceControlExpectation(
                "5.3.02", "5.3.02", 41_922_739L,
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                "7c675de8b23e63e6de14ae6c67403717d3b64fc8eefab54ac4124fffb3633f16",
                "m.workspace-5.3.02.control.static", "adapter.workspace.control.v5_3",
                "[5.3.02,5.3.03)"
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
            "cubism-ref/verification/cubism-5.2-ui-embedded-panel.json",
            new SliceExpectation(
                "cubism-5.2.03.ui-embedded-panel.static",
                EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                "cubism-5.2",
                EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
                "b54253d0d37b3e6ed97ef3e316256b85a7b026c13390105a726cc20c03d85f6f",
                73,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                embeddedPanelMethodAliases(),
                difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                "cubism-5.2-ui-embedded-panel",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-ui-embedded-panel.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
                "[5.2.0,5.3.0)",
                SliceKind.EDITOR_UI
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-embedded-panel.json",
            new SliceExpectation(
                EmbeddedPanelVerificationManifest.VERIFICATION_ID,
                EmbeddedPanelVerificationManifest.ADAPTER_SLICE_ID,
                EmbeddedPanelVerificationManifest.CUBISM_VERSION,
                EmbeddedPanelVerificationManifest.PROFILE_ID,
                EmbeddedPanelVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                EmbeddedPanelVerificationManifest.ARTIFACT_SIZE,
                EmbeddedPanelVerificationManifest.ARTIFACT_SHA256,
                EmbeddedPanelVerificationManifest.RECORD_SHA256,
                73,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                EmbeddedPanelVerificationManifest.REQUIRED_ALIASES,
                embeddedPanelMethodAliases(),
                difference(EmbeddedPanelVerificationManifest.REQUIRED_ALIASES, embeddedPanelMethodAliases()),
                "cubism-5.3.02-ui-embedded-panel",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-embedded-panel.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
                "[5.3.02,5.3.03)",
                SliceKind.EDITOR_UI
            )
        );
        return Map.copyOf(expectations);
    }

    private static Map<String, SliceExpectation> withTopMenus(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2-ui-top-menu.json",
            new SliceExpectation(
                "cubism-5.2.03.ui-top-menu.static",
                TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                "5.2.03",
                "cubism-5.2",
                TopMenuVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
                "65fea2ac6763aa612ae9b0effa7fddd78538a40c0e9a434db2965ac4ba84bf13",
                15,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                topMenuMethodAliases(),
                difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                "cubism-5.2-ui-top-menu",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-ui-top-menu.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
                "[5.2.0,5.3.0)",
                SliceKind.EDITOR_UI
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-top-menu.json",
            new SliceExpectation(
                TopMenuVerificationManifest.VERIFICATION_ID,
                TopMenuVerificationManifest.ADAPTER_SLICE_ID,
                TopMenuVerificationManifest.CUBISM_VERSION,
                TopMenuVerificationManifest.PROFILE_ID,
                TopMenuVerificationManifest.CAPABILITY_IDS,
                "Live2D_Cubism.jar",
                TopMenuVerificationManifest.ARTIFACT_SIZE,
                TopMenuVerificationManifest.ARTIFACT_SHA256,
                TopMenuVerificationManifest.RECORD_SHA256,
                15,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                TopMenuVerificationManifest.REQUIRED_ALIASES,
                topMenuMethodAliases(),
                difference(TopMenuVerificationManifest.REQUIRED_ALIASES, topMenuMethodAliases()),
                "cubism-5.3.02-ui-top-menu",
                Path.of("cubism-ref/mapping-packs/draft/cubism-5.3.02-ui-top-menu.json"),
                Path.of("cubism-ref/profiles/draft/cubism-5.3.02.json"),
                "[5.3.02,5.3.03)",
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
                41_922_739L,
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                "d5ae349fd18dbebbd52b2bf1e84291ecd325d7a2ec6d0be6bae0609285aebbac",
                "cubism-5.3.02-ui-control-appearance",
                "[5.3.02,5.3.03)"
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
        final String versionRange
    ) {
        final Set<String> classAliases = Set.of(
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
            38,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
            ControlAppearanceVerificationManifest.REQUIRED_ALIASES,
            difference(ControlAppearanceVerificationManifest.REQUIRED_ALIASES, classAliases),
            classAliases,
            packId,
            Path.of("cubism-ref/mapping-packs/draft/" + packId + ".json"),
            Path.of("cubism-ref/profiles/draft/" + profile + ".json"),
            versionRange,
            SliceKind.EDITOR_UI
        );
    }

    private static Map<String, SliceExpectation> withBoundingBoxOverlays(
        final Map<String, SliceExpectation> existing
    ) {
        final LinkedHashMap<String, SliceExpectation> expectations = new LinkedHashMap<>(existing);
        expectations.put(
            "cubism-ref/verification/cubism-5.2-ui-bounding-box-overlay.json",
            boundingBoxOverlayExpectation(
                "cubism-5.2.ui-bounding-box-overlay.static",
                "5.2.0",
                "cubism-5.2",
                40_805_584L,
                "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
                "4eb89fba8a44cf15e4f0be6818a57ba2c0a7847cc73a7c97855d595c913aa81f",
                "cubism-5.2-ui-bounding-box-overlay",
                "[5.2.0,5.3.0)"
            )
        );
        expectations.put(
            "cubism-ref/verification/cubism-5.3.02-ui-bounding-box-overlay.json",
            boundingBoxOverlayExpectation(
                "cubism-5.3.02.ui-bounding-box-overlay.static",
                "5.3.02",
                "cubism-5.3.02",
                41_922_739L,
                "988ef6a8b5fede84bd43c6dc3a9a045d9a6a974986c3f49fb6f567ccf8c84f21",
                "606a1837c03b00c62c8711dcb5eb53fe04eb7025f78736281a2e2afacd21ce54",
                "cubism-5.3.02-ui-bounding-box-overlay",
                "[5.3.02,5.3.03)"
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
        final String versionRange
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
            versionRange,
            SliceKind.EDITOR_UI
        );
    }

    private static Set<String> topMenuMethodAliases() {
        return Set.of(
            "cubism.ui-top-menu.app-controller.instance",
            "cubism.ui-top-menu.app-controller.main-frame",
            "cubism.ui-top-menu.main-frame.window",
            "cubism.ui-top-menu.window.menu-bar",
            "cubism.ui-top-menu.menu-bar.menus",
            "cubism.ui-top-menu.menu-bar.add",
            "cubism.ui-top-menu.menu-bar.swing",
            "cubism.ui-top-menu.widget.name",
            "cubism.ui-top-menu.widget.set-name",
            "cubism.ui-top-menu.widget.revalidate",
            "cubism.ui-top-menu.widget.repaint",
            "cubism.ui-top-menu.menu.add",
            "cubism.ui-top-menu.menu.swing",
            "cubism.ui-top-menu.menu.create",
            "cubism.ui-top-menu.menu-item.create"
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
            "cubism.ui-panel.menu-item.swing",
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
            "cubism.ui-main-toolbar.icon.create"
        );
    }

    private static Set<String> editorModelMethodAliases() {
        return Set.of(
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.app-controller.complete-pack",
            "cubism.editor-model.app-controller.main-frame",
            "cubism.editor-model.modeling-document.model-source",
            "cubism.editor-model.modeling-document.last-active-view",
            "cubism.editor-model.modeling-view.model",
            "cubism.editor-model.model-source.guid",
            "cubism.editor-model.model-source.name",
            "cubism.editor-model.model-source.current-instance",
            "cubism.editor-model.model-source.default-keyform-locked",
            "cubism.editor-model.model-source.set-default-keyform-locked",
            "cubism.editor-model.model-source.all-parameters",
            "cubism.editor-model.model-source.root-parameter-group",
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.parameter.id",
            "cubism.editor-model.parameter.value",
            "cubism.editor-model.parameter.source",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum",
            "cubism.editor-model.parameter-source.default",
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.repeat",
            "cubism.editor-model.parameter-source.morph-target",
            "cubism.editor-model.parameter-source.combined",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.id.value",
            "cubism.editor-model.guid.value",
            "cubism.editor-model.main-frame.parameter-palette",
            "cubism.editor-model.parameter-palette.view",
            "cubism.editor-model.parameter-palette-view.operation",
            "cubism.editor-model.parameter-operation.set-value",
            "cubism.editor-model.complete-pack.update-parameter",
            "cubism.editor-model.complete-pack.repaint-canvas",
            "cubism.editor-model.modeling-document.edit-mode",
            "cubism.editor-model.modeling-document.mark-dirty",
            "cubism.editor-model.edit-mode.begin",
            "cubism.editor-model.edit-mode.end",
            "cubism.editor-model.undo.add",
            "cubism.editor-model.simple-undo.create",
            "cubism.editor-model.parameter-operation.property-editor",
            "cubism.editor-model.parameter-operation.validator",
            "cubism.editor-model.parameter-operation.rows",
            "cubism.editor-model.parameter-operation.refresh",
            "cubism.editor-model.parameter-property-editor.update-definition",
            "cubism.editor-model.parameter-property-editor.rebuild-keep-value",
            "cubism.editor-model.parameter-validator.valid-id",
            "cubism.editor-model.parameter-validator.supports-type",
            "cubism.editor-model.parameter-validator.reject-type-change",
            "cubism.editor-model.parameter-validator.allow-repeat",
            "cubism.editor-model.parameter-validator.keys-outside-range",
            "cubism.editor-model.parameter-validator.default-change-affects-morph-target",
            "cubism.editor-model.parameter-helper.instance",
            "cubism.editor-model.parameter-helper.morph-target-eligible",
            "cubism.editor-model.parameter-source.model-source",
            "cubism.editor-model.model-source.all-objects",
            "cubism.editor-model.parameter-controllable.keyform-grid",
            "cubism.editor-model.keyform-grid.contains-parameter",
            "cubism.editor-model.parameter-controllable.morph-target-set",
            "cubism.editor-model.morph-target-set.contains-parameter",
            "cubism.editor-model.parameter-source.guid",
            "cubism.editor-model.parameter-refresh-callback.create",
            "cubism.editor-model.parameter-source.set-combined",
            "cubism.editor-model.parameter-source.parent-group",
            "cubism.editor-model.parameter-group.id",
            "cubism.editor-model.parameter-group.name",
            "cubism.editor-model.parameter-group.parent",
            "cubism.editor-model.parameter-group.label-color",
            "cubism.editor-model.parameter-controllable-source.label-color",
            "cubism.editor-model.label-color.label-type",
            "cubism.editor-model.label-color.customized-color",
            "cubism.editor-model.label-color.color",
            "cubism.editor-model.label-color.set-color",
            "cubism.editor-model.label-color.set-label-type",
            "cubism.editor-model.label-color-type.custom",
            "cubism.editor-model.label-color-type.undefined",
            "cubism.editor-model.label-color-type.red",
            "cubism.editor-model.label-color-type.orange",
            "cubism.editor-model.label-color-type.yellow",
            "cubism.editor-model.label-color-type.green",
            "cubism.editor-model.label-color-type.blue",
            "cubism.editor-model.label-color-type.purple",
            "cubism.editor-model.label-color-type.gray",
            "cubism.editor-model.color.create",
            "cubism.editor-model.color.red",
            "cubism.editor-model.color.green",
            "cubism.editor-model.color.blue",
            "cubism.editor-model.color.alpha",
            "cubism.editor-model.parameter-group.children",
            "cubism.editor-model.parameter-group.remove",
            "cubism.editor-model.parameter-group.add",
            "cubism.editor-model.undo.add-listener",
            "cubism.editor-model.model-source.parts",
            "cubism.editor-model.model-source.update-instances",
            "cubism.editor-model.model-source.update-visible-lock-hierarchy",
            "cubism.editor-model.model.parts",
            "cubism.editor-model.part.source",
            "cubism.editor-model.part.id",
            "cubism.editor-model.part.current-keyform",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.set-opacity",
            "cubism.editor-model.part-source.parent",
            "cubism.editor-model.part-source.id",
            "cubism.editor-model.part-source.use-offscreen",
            "cubism.editor-model.part-source.local-name",
            "cubism.editor-model.part-source.set-local-name",
            "cubism.editor-model.part-source.default-order",
            "cubism.editor-model.part-source.set-default-order",
            "cubism.editor-model.part-source.sketch",
            "cubism.editor-model.part-source.set-sketch",
            "cubism.editor-model.part-source.edit-color",
            "cubism.editor-model.part-source.set-edit-color",
            "cubism.editor-model.part-source.create-undo-for-basic-settings",
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.create-undo-for-all-edit",
            "cubism.editor-model.part-id.value",
            "cubism.editor-model.complete-pack.update-part-palette",
            "cubism.editor-model.model-source.all-art-meshes",
            "cubism.editor-model.model.all-art-meshes",
            "cubism.editor-model.parameter-controllable-source.id",
            "cubism.editor-model.parameter-controllable-source.local-name",
            "cubism.editor-model.parameter-controllable-source.visible",
            "cubism.editor-model.parameter-controllable-source.locked",
            "cubism.editor-model.parameter-controllable-source.visible-in-hierarchy",
            "cubism.editor-model.parameter-controllable-source.locked-in-hierarchy",
            "cubism.editor-model.parameter-controllable-source.target-deformer-source",
            "cubism.editor-model.art-mesh.source",
            "cubism.editor-model.art-mesh.current-keyform",
            "cubism.editor-model.drawable-form.opacity",
            "cubism.editor-model.drawable-form.draw-order",
            "cubism.editor-model.art-mesh-form.positions",
            "cubism.editor-model.art-mesh-source.positions",
            "cubism.editor-model.art-mesh-source.uvs",
            "cubism.editor-model.art-mesh-source.indices",
            "cubism.editor-model.art-mesh-source.culling",
            "cubism.editor-model.art-mesh-source.user-data",
            "cubism.editor-model.art-mesh-source.inverted-mask",
            "cubism.editor-model.art-mesh-source.guid",
            "cubism.editor-model.art-mesh-source.clip-guid-list",
            "cubism.editor-model.art-mesh-source.texture",
            "cubism.editor-model.texture.guid",
            "cubism.editor-model.model-source.all-glues",
            "cubism.editor-model.glue-source.target-art-mesh-a",
            "cubism.editor-model.glue-source.target-art-mesh-b",
            "cubism.editor-model.model-source.all-deformers",
            "cubism.editor-model.model.all-deformers",
            "cubism.editor-model.deformer.source",
            "cubism.editor-model.deformer.current-keyform",
            "cubism.editor-model.deformer-form.opacity",
            "cubism.editor-model.warp-source.row",
            "cubism.editor-model.warp-source.col",
            "cubism.editor-model.warp-source.quad-transform",
            "cubism.editor-model.warp-form.positions",
            "cubism.editor-model.rotation-source.base-angle",
            "cubism.editor-model.rotation-form.angle",
            "cubism.editor-model.rotation-form.origin-x",
            "cubism.editor-model.rotation-form.origin-y",
            "cubism.editor-model.rotation-form.scale",
            "cubism.editor-model.rotation-form.reflect-x",
            "cubism.editor-model.rotation-form.reflect-y",
            "cubism.editor-model.parameter-controllable-source.handler",
            "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
            "cubism.editor-model.parameter-controllable-source.set-visible",
            "cubism.editor-model.parameter-controllable-source.set-locked",
            "cubism.editor-model.drawable-form.set-opacity",
            "cubism.editor-model.art-mesh-form.set-positions",
            "cubism.editor-model.art-mesh-source.set-positions",
            "cubism.editor-model.art-mesh-source.set-uvs",
            "cubism.editor-model.art-mesh-source.set-indices",
            "cubism.editor-model.deformer-form.set-opacity",
            "cubism.editor-model.warp-source.set-row",
            "cubism.editor-model.warp-source.set-col",
            "cubism.editor-model.warp-source.set-quad-transform",
            "cubism.editor-model.warp-form.set-positions",
            "cubism.editor-model.rotation-source.set-base-angle",
            "cubism.editor-model.rotation-form.set-angle",
            "cubism.editor-model.rotation-form.set-origin-x",
            "cubism.editor-model.rotation-form.set-origin-y",
            "cubism.editor-model.rotation-form.set-scale",
            "cubism.editor-model.rotation-form.set-reflect-x",
            "cubism.editor-model.rotation-form.set-reflect-y",
            "cubism.editor-model.complete-pack.update-deformer-palette",
            "object-context-menu.parameter.group-row.source",
            "object-context-menu.parameter.row-parameters",
            "object-context-menu.workspace.selector",
            "object-context-menu.workspace.selected",
            "object-context-menu.workspace.selection-source",
            "object-context-menu.object-id",
            "object-context-menu.parameter-id",
            "object-context-menu.parameter-group-id",
            "object-context-menu.id-value",
            "object-context-menu.menu-item.create",
            "object-context-menu.menu.append",
            "object-context-menu.submenu.append",
            "object-context-menu.menu.items",
            "object-context-menu.menu-separator.create",
            "object-context-menu.submenu.create",
            "object-context-menu.menu-item.label",
            "object-context-menu.parameter-point.guid-value",
            "object-context-menu.menu.component",
            "cubism.editor-model.keyform-grid.bindings",
            "cubism.editor-model.keyform-binding.parameter-id",
            "cubism.editor-model.keyform-binding.parameter-guid",
            "cubism.editor-model.keyform-binding.keys",
            "cubism.editor-model.keyform-grid.find-binding",
            "cubism.editor-model.keyform-grid.reverse-parameter",
            "cubism.editor-model.keyform-grid.change-parameter",
            "cubism.editor-model.keyform-grid.add-key",
            "cubism.editor-model.keyform-grid.remove-key",
            "cubism.editor-model.keyform-grid.remove-all-key",
            "cubism.editor-model.keyform-grid.rearrange-keys"
        );
    }

    private static Set<String> editorModel52Capabilities() {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>(
            EditorModelVerificationManifest.CAPABILITY_IDS
        );
        capabilities.remove(EditorPartOpacitySelectorContract.CAPABILITY_ID);
        capabilities.add(EditorPartOpacity52SelectorContract.CAPABILITY_ID);
        return Set.copyOf(capabilities);
    }

    private static Set<String> editorModel52Aliases() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(
            EditorModelVerificationManifest.REQUIRED_ALIASES
        );
        aliases.removeAll(EditorPartOpacitySelectorContract.REQUIRED_ALIASES);
        aliases.addAll(EditorPartOpacity52SelectorContract.REQUIRED_ALIASES);
        aliases.addAll(EditorPartNameSelectorContract.REQUIRED_ALIASES);
        aliases.addAll(EditorPartNameSelectorContract.WRITE_REQUIRED_ALIASES);
        aliases.addAll(EditorObjectReadSelectorContract.REQUIRED_ALIASES);
        aliases.removeAll(EditorObjectReadSelectorContract.OFFSCREEN_STATISTICS_ALIASES);
        aliases.addAll(EditorObjectWriteSelectorContract.ART_MESH_REQUIRED_ALIASES);
        aliases.addAll(EditorObjectWriteSelectorContract.WARP_REQUIRED_ALIASES);
        aliases.addAll(EditorObjectWriteSelectorContract.ROTATION_REQUIRED_ALIASES);
        aliases.addAll(Set.of(
            "cubism.editor-model.part-source.handler",
            "cubism.editor-model.part-handler.class",
            "cubism.editor-model.part-handler.create-undo-for-all-edit"
        ));
        return Set.copyOf(aliases);
    }

    private static Set<String> editorModel52MethodAliases() {
        final java.util.HashSet<String> aliases = new java.util.HashSet<>(editorModelMethodAliases());
        aliases.removeAll(Set.of(
            "cubism.editor-model.part.id",
            "cubism.editor-model.part.current-keyform",
            "cubism.editor-model.part-form.opacity",
            "cubism.editor-model.part-form.set-opacity",
            "cubism.editor-model.part-source.use-offscreen"
        ));
        aliases.add("cubism.editor-model.part.parts-opacity");
        return Set.copyOf(aliases);
    }

    private static SliceExpectation coreExpectation(
        final String profile,
        final String exactVersion,
        final long artifactSize,
        final String artifactSha256,
        final String recordSha256,
        final int selectorCount,
        final String versionRange
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
            versionRange,
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
        assertEquals(expectation.expectedVersionRange(), profile.get("versionRange").asText(),
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
        EDITOR_UI
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
        String expectedVersionRange,
        SliceKind kind
    ) {
    }
}
