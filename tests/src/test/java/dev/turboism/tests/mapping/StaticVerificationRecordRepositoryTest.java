package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.HostArtifactDigest;
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
        )
    );

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
        assertEquals(expectation.cubismVersion(), pack.get("cubismVersion").asText(), "mapping pack version drift");
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
        CLIP_MASK
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
