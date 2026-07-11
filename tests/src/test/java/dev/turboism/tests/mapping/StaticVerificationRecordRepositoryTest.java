package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.adapter.cubism.VerifiedClipMaskHostOperations;
import dev.turboism.adapter.cubism.VerifiedProjectWorkspaceHostOperations;
import dev.turboism.mapping.verification.ClipMaskVerificationManifest;
import dev.turboism.mapping.verification.ProjectWorkspaceVerificationManifest;
import dev.turboism.mapping.verification.StaticVerificationRecordValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class StaticVerificationRecordRepositoryTest {

    private static final Path PROJECT_ROOT = Paths.get(
        System.getProperty("projectRoot", System.getProperty("user.dir"))
    );
    private static final Path RECORDS = PROJECT_ROOT.resolve("docs/migration/verification/static");

    private final ObjectMapper mapper = new ObjectMapper();
    private final StaticVerificationRecordValidator validator = new StaticVerificationRecordValidator();

    @Test
    void projectWorkspacePackRecordAndOperationAliasesStayInExactSync() throws Exception {
        Path recordPath = RECORDS.resolve("cubism-5.3.02-project-workspace.json");
        Path packPath = PROJECT_ROOT.resolve(
            "cubism-ref/mapping-packs/draft/cubism-5.3.02-m14-project-workspace.json"
        );
        JsonNode record = mapper.readTree(recordPath.toFile());
        JsonNode pack = mapper.readTree(packPath.toFile());

        java.util.Map<String, JsonNode> entriesByMappingId = new java.util.LinkedHashMap<>();
        for (JsonNode entry : pack.get("entries")) {
            entriesByMappingId.put(entry.get("semanticName").asText(), entry);
        }
        assertEquals(22, entriesByMappingId.size(), "project/workspace DRAFT dependency count drifted");
        assertEquals(22, record.get("selectors").size(), "project/workspace verified selector count drifted");

        Set<String> aliases = new HashSet<>();
        Set<String> methodAliases = new HashSet<>();
        for (JsonNode selector : record.get("selectors")) {
            String mappingId = selector.get("mappingId").asText();
            JsonNode entry = entriesByMappingId.remove(mappingId);
            assertTrue(entry != null, "record selector has no DRAFT mapping entry: " + mappingId);
            assertEquals(entry.get("name").asText(), selector.get("alias").asText(), mappingId + " alias drift");
            assertEquals(entry.get("kind").asText(), selector.get("kind").asText(), mappingId + " kind drift");
            assertEquals(entry.get("x.verification").get("ownerInternalName").asText(),
                selector.get("ownerInternalName").asText(), mappingId + " owner drift");
            assertEquals(entry.get("x.verification").get("requiredAccessFlags").asInt(),
                selector.get("requiredAccessFlags").asInt(), mappingId + " required access drift");
            assertEquals(entry.get("x.verification").get("forbiddenAccessFlags").asInt(),
                selector.get("forbiddenAccessFlags").asInt(), mappingId + " forbidden access drift");
            if (!"class".equals(selector.get("kind").asText())) {
                methodAliases.add(selector.get("alias").asText());
                assertEquals(entry.get("runtime").asText(), selector.get("memberName").asText(), mappingId + " member drift");
                assertEquals(entry.get("descriptor").asText(), selector.get("descriptor").asText(), mappingId + " descriptor drift");
            } else {
                assertEquals(entry.get("runtime").asText(), selector.get("ownerInternalName").asText(), mappingId + " class owner drift");
            }
            aliases.add(selector.get("alias").asText());
        }
        assertTrue(entriesByMappingId.isEmpty(), "DRAFT mapping entries missing from verification record: " + entriesByMappingId.keySet());
        assertEquals(VerifiedProjectWorkspaceHostOperations.REQUIRED_ALIASES, aliases,
            "HostOperations required aliases drifted from verified record");
        assertEquals(VerifiedProjectWorkspaceHostOperations.methodAliasesUsed(), methodAliases,
            "actual HostOperations invocation aliases drifted from verified method selectors");
        assertEquals(ProjectWorkspaceVerificationManifest.REQUIRED_ALIASES, aliases,
            "runtime-owned selector trust manifest drifted from verified record");
        assertEquals(ProjectWorkspaceVerificationManifest.VERIFICATION_ID, record.get("verificationId").asText());
        assertEquals(ProjectWorkspaceVerificationManifest.ADAPTER_SLICE_ID, record.get("adapterSliceId").asText());
        assertEquals(ProjectWorkspaceVerificationManifest.CUBISM_VERSION, record.get("cubismVersion").asText());
        assertEquals(ProjectWorkspaceVerificationManifest.PROFILE_ID, record.get("profileId").asText());
        assertEquals(ProjectWorkspaceVerificationManifest.CAPABILITY_IDS,
            asStringSet(record.get("capabilityIds")));
        assertEquals(ProjectWorkspaceVerificationManifest.ARTIFACT_SIZE,
            record.get("artifact").get("size").asLong());
        assertEquals(ProjectWorkspaceVerificationManifest.ARTIFACT_SHA256,
            record.get("artifact").get("sha256").asText());
        assertEquals(ProjectWorkspaceVerificationManifest.RECORD_SHA256,
            dev.turboism.mapping.verification.HostArtifactDigest.from(recordPath).sha256());

        assertEquals(ProjectWorkspaceVerificationManifest.CUBISM_VERSION,
            pack.get("cubismVersion").asText(), "mapping pack version drift");
        for (JsonNode entry : pack.get("entries")) {
            assertEquals(ProjectWorkspaceVerificationManifest.PROFILE_ID, entry.get("profile").asText(),
                entry.get("semanticName").asText() + " profile drift");
        }
        Path profilePath = PROJECT_ROOT.resolve("cubism-ref/profiles/draft/cubism-5.3.02.json");
        JsonNode profile = mapper.readTree(profilePath.toFile());
        assertEquals(ProjectWorkspaceVerificationManifest.PROFILE_ID, profile.get("profileId").asText());
        assertTrue(asStringSet(profile.get("mappingPacks")).contains("cubism-5.3.02-m14-project-workspace"),
            "profile must reference project/workspace pack");
        assertEquals("[5.3.02,5.3.03)", profile.get("versionRange").asText(),
            "profile version range must stay pinned around exact verified version");
    }

    @Test
    void clipMaskPackRecordManifestAndProfileStayInExactSync() throws Exception {
        Path recordPath = RECORDS.resolve("cubism-5.3.02-clipmask.json");
        Path packPath = PROJECT_ROOT.resolve(
            "cubism-ref/mapping-packs/draft/cubism-5.3.02-m15-clipmask.json"
        );
        JsonNode record = mapper.readTree(recordPath.toFile());
        JsonNode pack = mapper.readTree(packPath.toFile());

        java.util.Map<String, JsonNode> entriesByMappingId = new java.util.LinkedHashMap<>();
        for (JsonNode entry : pack.get("entries")) {
            entriesByMappingId.put(entry.get("semanticName").asText(), entry);
        }
        assertEquals(16, entriesByMappingId.size(), "clip-mask DRAFT dependency count drifted");
        assertEquals(16, record.get("selectors").size(), "clip-mask verified selector count drifted");

        Set<String> aliases = new HashSet<>();
        Set<String> classAliases = new HashSet<>();
        Set<String> methodAliases = new HashSet<>();
        for (JsonNode selector : record.get("selectors")) {
            String mappingId = selector.get("mappingId").asText();
            JsonNode entry = entriesByMappingId.remove(mappingId);
            assertTrue(entry != null, "record selector has no DRAFT mapping entry: " + mappingId);
            assertEquals(entry.get("name").asText(), selector.get("alias").asText(), mappingId + " alias drift");
            assertEquals(entry.get("kind").asText(), selector.get("kind").asText(), mappingId + " kind drift");
            assertEquals(entry.get("x.verification").get("ownerInternalName").asText(),
                selector.get("ownerInternalName").asText(), mappingId + " owner drift");
            assertEquals(entry.get("x.verification").get("requiredAccessFlags").asInt(),
                selector.get("requiredAccessFlags").asInt(), mappingId + " required access drift");
            assertEquals(entry.get("x.verification").get("forbiddenAccessFlags").asInt(),
                selector.get("forbiddenAccessFlags").asInt(), mappingId + " forbidden access drift");
            if (!"class".equals(selector.get("kind").asText())) {
                methodAliases.add(selector.get("alias").asText());
                assertEquals(entry.get("runtime").asText(), selector.get("memberName").asText(), mappingId + " member drift");
                assertEquals(entry.get("descriptor").asText(), selector.get("descriptor").asText(), mappingId + " descriptor drift");
            } else {
                classAliases.add(selector.get("alias").asText());
                assertEquals(entry.get("runtime").asText(), selector.get("ownerInternalName").asText(), mappingId + " class owner drift");
            }
            aliases.add(selector.get("alias").asText());
        }
        assertTrue(entriesByMappingId.isEmpty(), "DRAFT mapping entries missing from clip-mask record: " + entriesByMappingId.keySet());
        assertEquals(VerifiedClipMaskHostOperations.classAliasesUsed(), classAliases,
            "actual clip-mask HostOperations type aliases drifted from verified class selectors");
        assertEquals(VerifiedClipMaskHostOperations.methodAliasesUsed(), methodAliases,
            "actual clip-mask HostOperations invocation aliases drifted from verified method selectors");
        Set<String> implementationAliases = new HashSet<>(classAliases);
        implementationAliases.addAll(methodAliases);
        assertEquals(VerifiedClipMaskHostOperations.REQUIRED_ALIASES, implementationAliases,
            "clip-mask HostOperations required aliases must be the class/method alias union");
        assertEquals(aliases, implementationAliases,
            "verified clip-mask aliases must be the class/method selector union");
        assertEquals(ClipMaskVerificationManifest.REQUIRED_ALIASES, aliases,
            "independent clip-mask selector trust manifest drifted from verified record");
        assertEquals(ClipMaskVerificationManifest.VERIFICATION_ID, record.get("verificationId").asText());
        assertEquals(ClipMaskVerificationManifest.ADAPTER_SLICE_ID, record.get("adapterSliceId").asText());
        assertEquals(ClipMaskVerificationManifest.CUBISM_VERSION, record.get("cubismVersion").asText());
        assertEquals(ClipMaskVerificationManifest.PROFILE_ID, record.get("profileId").asText());
        assertEquals(ClipMaskVerificationManifest.CAPABILITY_IDS, asStringSet(record.get("capabilityIds")));
        assertEquals(ClipMaskVerificationManifest.ARTIFACT_SIZE, record.get("artifact").get("size").asLong());
        assertEquals(ClipMaskVerificationManifest.ARTIFACT_SHA256, record.get("artifact").get("sha256").asText());
        assertEquals(ClipMaskVerificationManifest.RECORD_SHA256,
            dev.turboism.mapping.verification.HostArtifactDigest.from(recordPath).sha256());

        assertEquals("DRAFT", pack.get("status").asText(), "mapping pack readiness must remain DRAFT");
        assertEquals(ClipMaskVerificationManifest.CUBISM_VERSION, pack.get("cubismVersion").asText());
        for (JsonNode entry : pack.get("entries")) {
            assertEquals(ClipMaskVerificationManifest.PROFILE_ID, entry.get("profile").asText(),
                entry.get("semanticName").asText() + " profile drift");
        }
        JsonNode profile = mapper.readTree(PROJECT_ROOT.resolve("cubism-ref/profiles/draft/cubism-5.3.02.json").toFile());
        assertEquals("DRAFT", profile.get("status").asText(), "profile readiness must remain DRAFT");
        assertTrue(asStringSet(profile.get("mappingPacks")).contains("cubism-5.3.02-m15-clipmask"),
            "profile must reference independent clip-mask pack");
        assertFalse(asStringSet(profile.get("mappingPacks")).contains("cubism-5.3.02-m15-project-workspace-clipmask"),
            "clip-mask evidence must not be coupled to a project/workspace pack");
    }

    @Test
    void allTrackedStaticRecordsAreValidatedAndSelfReferencing() throws Exception {
        assertTrue(Files.isDirectory(RECORDS), "static verification record directory must exist");
        try (Stream<Path> files = Files.list(RECORDS)) {
            var records = files.filter(path -> path.toString().endsWith(".json")).sorted().toList();
            assertFalse(records.isEmpty(), "at least one static verification record must exist");
            for (Path path : records) {
                verify(path);
            }
        }
    }

    private static Set<String> asStringSet(final JsonNode array) {
        Set<String> values = new HashSet<>();
        array.forEach(value -> values.add(value.asText()));
        return Set.copyOf(values);
    }

    private void verify(final Path path) {
        try {
            JsonNode root = mapper.readTree(path.toFile());
            var errors = validator.validate(root, PROJECT_ROOT.relativize(path).toString());
            assertTrue(errors.isEmpty(), path.getFileName() + " validation failed: " + errors);
            assertEquals(
                PROJECT_ROOT.relativize(path).toString().replace('\\', '/'),
                root.get("evidencePath").asText(),
                path.getFileName() + " must reference its tracked relative path"
            );
            assertEquals("VERIFIED_STATIC", root.get("status").asText());
            assertFalse(root.get("cubismVersion").asText().contains("[")
                || root.get("cubismVersion").asText().contains(","),
                path.getFileName() + " must use an exact version, not a range");

            Set<String> aliases = new HashSet<>();
            Set<String> mappingIds = new HashSet<>();
            for (JsonNode selector : root.get("selectors")) {
                assertTrue(aliases.add(selector.get("alias").asText()),
                    path.getFileName() + " has duplicate alias");
                assertTrue(mappingIds.add(selector.get("mappingId").asText()),
                    path.getFileName() + " has duplicate mappingId");
            }
        } catch (Exception exception) {
            fail(path.getFileName() + " could not be validated: " + exception.getMessage());
        }
    }
}
