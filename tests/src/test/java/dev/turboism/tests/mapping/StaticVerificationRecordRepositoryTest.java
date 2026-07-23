package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.CorePublicApiSelectorContract;
import dev.turboism.mapping.verification.CorePublicApiTrustRoots;
import dev.turboism.mapping.verification.EditorModelVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.MainToolbarVerificationManifest;
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
    private static final Path RECORDS = PROJECT_ROOT.resolve("docs/migration/verification/static");

    private static final Map<String, SliceExpectation> EXPECTATIONS = Map.of(
        "docs/migration/verification/static/cubism-5.2-project-workspace.json",
        new SliceExpectation(
            "m15.cubism-5.2.project-workspace.static",
            ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "a160ecd12044652293991be68a2f3c08f8020688c9d39b8a8f2019defe5c2dcb",
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
        "docs/migration/verification/static/cubism-5.3.02-project-workspace.json",
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
        "docs/migration/verification/static/cubism-5.2-core-model-read.json",
        coreExpectation("5.2", "5.2.0", 36_237L,
            "85959a0572be02ee45d128cfdaf9046631241310b741d6b149d295a0dec7451e",
            "a32d871209c7449e4adfa84a3a25e928c759f118e36e2bb5349ad5deeff28382",
            69, "[5.2.0,5.3.0)"),
        "docs/migration/verification/static/cubism-5.3.02-clipmask.json",
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
        "docs/migration/verification/static/cubism-5.2-ui-main-toolbar.json",
        new SliceExpectation(
            "cubism-5.2.ui-main-toolbar.static",
            MainToolbarVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            MainToolbarVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "2576099f240a6b0c38f275ce59d806d63afa4a9f523ab4a90a72710896fc3682",
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
        "docs/migration/verification/static/cubism-5.3.02-ui-main-toolbar.json",
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
        "docs/migration/verification/static/cubism-5.2-editor-model.json",
        new SliceExpectation(
            "cubism-5.2.editor-model.static",
            EditorModelVerificationManifest.ADAPTER_SLICE_ID,
            "5.2.0",
            "cubism-5.2",
            EditorModelVerificationManifest.CAPABILITY_IDS,
            "Live2D_Cubism.jar",
            40_805_584L,
            "bcc6e34f448be33d8964f2e17f4eb7fd3780e4a9b7f60525da377c9f35d2b3dd",
            "f56774cb211f83654cf76e5cd46b811cee2cc4ccedb43f42b437de13dfede590",
            108,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            EditorModelVerificationManifest.REQUIRED_ALIASES,
            editorModelMethodAliases(),
            difference(EditorModelVerificationManifest.REQUIRED_ALIASES, editorModelMethodAliases()),
            "cubism-5.2-editor-model-read",
            Path.of("cubism-ref/mapping-packs/draft/cubism-5.2-editor-model-read.json"),
            Path.of("cubism-ref/profiles/draft/cubism-5.2.json"),
            "[5.2.0,5.3.0)",
            SliceKind.EDITOR_MODEL
        ),
        "docs/migration/verification/static/cubism-5.3.02-editor-model.json",
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
            108,
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
        "docs/migration/verification/static/cubism-5.3.02-core-model-read.json",
        coreExpectation("5.3.02", "5.3.2", 42_471L,
            "98f4dac9a9508a6e255f6f3862608409a83e29c9009a7f0fcf517e06658164e4",
            "986bcddca88c35dc09b848ccb6737d0a70a12b2fd030a52be838ce533c687073",
            70, "[5.3.02,5.3.03)")
    );

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
            "cubism.editor-model.label-color.color",
            "cubism.editor-model.label-color.set-color",
            "cubism.editor-model.label-color-type.custom",
            "cubism.editor-model.color.create",
            "cubism.editor-model.color.red",
            "cubism.editor-model.color.green",
            "cubism.editor-model.color.blue",
            "cubism.editor-model.color.alpha",
            "cubism.editor-model.parameter-group.children",
            "cubism.editor-model.parameter-group.remove",
            "cubism.editor-model.parameter-group.add",
            "cubism.editor-model.undo.add-listener"
        );
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
        final Set<String> versionMethods = Set.of(
            CorePublicApiSelectorContract.GET_VERSION,
            CorePublicApiSelectorContract.GET_MAJOR,
            CorePublicApiSelectorContract.GET_MINOR,
            CorePublicApiSelectorContract.GET_PATCH
        );
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
