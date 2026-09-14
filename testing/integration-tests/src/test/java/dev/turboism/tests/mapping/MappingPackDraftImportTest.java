package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for M3 draft mapping pack import.
 */
class MappingPackDraftImportTest {

    private static final Path DRAFT_DIR = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
        .resolve("compatibility/cubism/mapping-packs/draft");

    private final ObjectMapper mapper = new ObjectMapper();
    private final MappingPackValidator validator = new MappingPackValidator();

    @Test
    void allDraftPacksAreValid() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    if (!"turboism.mapping.pack".equals(root.path("format").asText())) {
                        return;
                    }
                    var errors = validator.validate(root, path.toString());
                    assertTrue(errors.isEmpty(),
                        path.getFileName() + " validation failed: " + errors);
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void noDraftPacksAreVerified() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    JsonNode status = root.get("status");
                    assertNotNull(status, path.getFileName() + " must have status");
                    assertEquals("DRAFT", status.asText(),
                        path.getFileName() + " must have status DRAFT");
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void semanticNamesAreUniqueWithinEachPack() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    JsonNode entries = root.get("entries");
                    assertNotNull(entries, path.getFileName() + " must have entries");
                    Set<String> seen = new java.util.HashSet<>();
                    for (JsonNode entry : entries) {
                        String semantic = entry.get("semanticName").asText();
                        assertTrue(seen.add(semantic),
                            path.getFileName() + " has duplicate semanticName: " + semantic);
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void verifiedFieldsAreDefaulted() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    if (!"turboism.mapping.pack".equals(root.path("format").asText())) {
                        return;
                    }
                    JsonNode entries = root.get("entries");
                    assertNotNull(entries, path.getFileName() + " must have entries");
                    for (JsonNode entry : entries) {
                        assertEquals("none", entry.get("verifiedBy").asText(),
                            path.getFileName() + " entry must have verifiedBy=none");
                        assertTrue(entry.get("verifiedAt").isNull(),
                            path.getFileName() + " entry must have verifiedAt=null");
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void noJavaMethodBodiesOrBypassKeywords() throws Exception {
        List<String> forbidden = List.of(
            "public void", "private void", "protected void", "System.out.print",
            "license bypass", "trial bypass", "crack", "patch authorization",
            "remove watermark", "disable security", "bypass"
        );
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    String text = Files.readString(path).toLowerCase();
                    for (String kw : forbidden) {
                        assertFalse(text.contains(kw.toLowerCase()),
                            path.getFileName() + " contains forbidden keyword: " + kw);
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be read: " + e.getMessage());
                }
            });
        }
    }


    @Test
    void defaultDraftPacksOnlyContainHighOrMediumConfidenceEntries() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    if (!"turboism.mapping.pack".equals(root.path("format").asText())) {
                        return;
                    }
                    for (JsonNode entry : root.get("entries")) {
                        String confidence = entry.get("confidence").asText();
                        assertTrue(Set.of("high", "medium").contains(confidence),
                            path.getFileName() + " contains non-default confidence entry: " + confidence);
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void semanticNamesDoNotCarryRawRuntimeOrPrivatePackageNames() throws Exception {
        List<String> forbiddenFragments = List.of("class_", "method_", "field_", "com.live2d", "jp.live2d", "$", "\\");
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    for (JsonNode entry : root.get("entries")) {
                        String semanticName = entry.get("semanticName").asText();
                        for (String fragment : forbiddenFragments) {
                            assertFalse(semanticName.contains(fragment),
                                path.getFileName() + " semanticName contains raw/private fragment: " + semanticName);
                        }
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void pathsDoNotContainDotDot() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    String text = Files.readString(path);
                    assertFalse(text.contains(".."), path.getFileName() + " contains .. in path");
                    assertFalse(text.contains("/\\") || text.contains("\\\\"),
                        path.getFileName() + " contains absolute path separator");
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be read: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void pathFieldsAreRelative() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    checkPathField(root.path("x").path("legacy").path("sourcePath"), path);
                    for (JsonNode entry : root.get("entries")) {
                        checkPathField(entry.path("x").path("legacy").path("sourcePath"), path);
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    private void checkPathField(JsonNode node, Path path) {
        if (node.isMissingNode() || node.isNull()) {
            return;
        }
        String value = node.asText();
        assertFalse(value.contains(".."),
            path.getFileName() + " path field contains ..: " + value);
        assertFalse(value.startsWith("/"),
            path.getFileName() + " path field is absolute: " + value);
        assertFalse(value.startsWith("\\"),
            path.getFileName() + " path field is absolute: " + value);
        assertFalse(value.matches("^[A-Za-z]:\\\\.*"),
            path.getFileName() + " path field is Windows absolute: " + value);
        assertFalse(value.startsWith("file://"),
            path.getFileName() + " path field is URI absolute: " + value);
    }

    @Test
    void editorModel52PackUsesExactEditorProvenance() throws Exception {
        JsonNode metadata = mapper.readTree(
            DRAFT_DIR.resolve("cubism-5.2.03-editor-model-read.json").toFile()
        ).path("metadata");
        assertEquals(
            "compatibility/cubism/verification/cubism-5.2.03-editor-model.json",
            metadata.path("inventoryRef").asText()
        );
        assertEquals(
            ReviewedHostArtifacts.CUBISM_5_2_03.sha256(),
            metadata.path("artifactSha256").asText()
        );
    }

    @Test
    void editorModel5303PackPinsMinimalCapabilityAssociation() throws Exception {
        final JsonNode root = mapper.readTree(
            DRAFT_DIR.resolve("cubism-5.3.03-editor-model-read.json").toFile()
        );
        final JsonNode metadata = root.path("metadata");
        assertEquals(
            "compatibility/cubism/verification/cubism-5.3.03-editor-model.json",
            metadata.path("inventoryRef").asText()
        );
        assertEquals(
            "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166",
            metadata.path("artifactSha256").asText()
        );

        final Path evidencePath = Paths.get(
                System.getProperty("projectRoot", System.getProperty("user.dir")))
            .resolve(metadata.path("inventoryRef").asText())
            .normalize();
        final byte[] evidenceBytes = Files.readAllBytes(evidencePath);
        final JsonNode evidence = mapper.readTree(evidenceBytes);
        assertEquals("VERIFIED_STATIC", evidence.path("status").asText());
        assertEquals(evidence.path("artifact").path("name").asText(),
            metadata.path("artifactName").asText());
        assertEquals(evidence.path("artifact").path("size").asLong(),
            metadata.path("artifactSize").asLong());
        assertEquals(evidence.path("artifact").path("sha256").asText(),
            metadata.path("artifactSha256").asText());
        assertEquals(evidence.path("profileId").asText(),
            metadata.path("profileId").asText());
        assertEquals(evidence.path("verificationId").asText(),
            metadata.path("verificationId").asText());

        final List<String> metadataViolations =
            draftMetadataViolations(root, evidence, sha256Hex(evidenceBytes));
        assertTrue(metadataViolations.isEmpty(),
            () -> "draft metadata out of sync with inventoryRef: " + metadataViolations);

        final JsonNode selectors = evidence.path("selectors");
        final Map<String, JsonNode> selectorsById = new TreeMap<>();
        selectors.forEach(s -> selectorsById.put(s.path("mappingId").asText(), s));
        assertEquals(selectors.size(), selectorsById.size(),
            "record selector mappingIds must be unique");
        assertEquals(selectors.size(), root.path("entries").size());

        for (JsonNode entry : root.path("entries")) {
            final String mappingId = entry.path("semanticName").asText();
            final JsonNode selector = selectorsById.get(mappingId);
            assertNotNull(selector, "entry without record selector: " + mappingId);
            assertEquals("VERIFIED_STATIC", selector.path("status").asText());
            assertEquals(selector.path("alias"), entry.path("name"));
            assertEquals(selector.path("kind"), entry.path("kind"));
            assertEquals("DRAFT", entry.path("status").asText());
            assertEquals("none", entry.path("verifiedBy").asText());
            assertTrue(entry.path("verifiedAt").isNull());
            if ("class".equals(selector.path("kind").asText())) {
                assertEquals(selector.path("ownerInternalName"), entry.path("runtime"));
            } else {
                assertEquals(selector.path("memberName"), entry.path("runtime"));
                assertEquals(selector.path("descriptor"), entry.path("descriptor"));
            }
            for (String field : List.of("ownerInternalName", "requiredAccessFlags", "forbiddenAccessFlags")) {
                assertEquals(selector.path(field), entry.path("x.verification").path(field));
            }
            if (entry.has("x.review")) {
                assertEquals(selector.path("mappingId"), entry.path("x.review").path("recordMappingId"));
                assertEquals(selector.path("alias"), entry.path("x.review").path("recordAlias"));
                assertEquals(evidence.path("artifact").path("sha256"),
                    entry.path("x.review").path("exactArtifactSha256"));
            }
        }
    }

    @Test
    void editorModel5303DraftMetadataRejectsTamperedProjection() throws Exception {
        final JsonNode pack = mapper.readTree(
            DRAFT_DIR.resolve("cubism-5.3.03-editor-model-read.json").toFile()
        );
        final Path evidencePath = Paths.get(
                System.getProperty("projectRoot", System.getProperty("user.dir")))
            .resolve(pack.path("metadata").path("inventoryRef").asText())
            .normalize();
        final byte[] evidenceBytes = Files.readAllBytes(evidencePath);
        final JsonNode evidence = mapper.readTree(evidenceBytes);
        final String recordSha256 = sha256Hex(evidenceBytes);

        assertTrue(draftMetadataViolations(pack, evidence, recordSha256).isEmpty(),
            "pack metadata must be consistent for mutation negatives to be meaningful");

        final int recordSelectorCount = evidence.path("selectors").size();
        final int recordCapabilityCount = evidence.path("capabilityIds").size();

        assertAll(
            () -> assertFieldViolation(pack, evidence, recordSha256, "selectorCount",
                metadata -> metadata.put("selectorCount", recordSelectorCount + 1)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "selectorCount",
                metadata -> metadata.put("selectorCount", recordSelectorCount + 0.5)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "selectorCount",
                metadata -> metadata.put("selectorCount", (1L << 32) + recordSelectorCount)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "selectorCount",
                metadata -> metadata.put("selectorCount", String.valueOf(recordSelectorCount))),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityCount",
                metadata -> metadata.put("capabilityCount", recordCapabilityCount + 1)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityCount",
                metadata -> metadata.put("capabilityCount", recordCapabilityCount + 0.5)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityCount",
                metadata -> metadata.put("capabilityCount", (1L << 32) + recordCapabilityCount)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityCount",
                metadata -> metadata.put("capabilityCount", String.valueOf(recordCapabilityCount))),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityIds",
                metadata -> ((ArrayNode) metadata.get("capabilityIds")).remove(0)),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityIds",
                metadata -> ((ArrayNode) metadata.get("capabilityIds"))
                    .add("cubism.editor-model.nonexistent-capability")),
            () -> assertFieldViolation(pack, evidence, recordSha256, "capabilityIds",
                metadata -> ((ArrayNode) metadata.get("capabilityIds"))
                    .set(1, metadata.get("capabilityIds").get(0))),
            () -> assertFieldViolation(pack, evidence, recordSha256, "verificationRecordSha256",
                metadata -> metadata.put("verificationRecordSha256", "0".repeat(64))));
    }

    private void assertFieldViolation(JsonNode pack, JsonNode evidence, String recordSha256,
                                      String field, Consumer<ObjectNode> mutation) {
        final ObjectNode mutated = pack.deepCopy();
        mutation.accept((ObjectNode) mutated.get("metadata"));
        final List<String> violations = draftMetadataViolations(mutated, evidence, recordSha256);
        assertFalse(violations.isEmpty(),
            () -> "mutation of " + field + " was not rejected");
        assertTrue(violations.stream().allMatch(v -> v.contains(field)),
            () -> "mutation of " + field + " reported unrelated violations: " + violations);
    }

    private static List<String> draftMetadataViolations(
            JsonNode pack, JsonNode record, String recordSha256) {
        final List<String> violations = new ArrayList<>();
        final JsonNode metadata = pack.path("metadata");

        final int selectorCount = record.path("selectors").size();
        final JsonNode selectorCountNode = metadata.path("selectorCount");
        if (!selectorCountNode.isIntegralNumber() || !selectorCountNode.canConvertToInt()
                || selectorCountNode.intValue() != selectorCount) {
            violations.add("selectorCount " + selectorCountNode.asText()
                + " is not an exact int equal to record selector count " + selectorCount);
        }

        final List<String> recordCapabilities = new ArrayList<>();
        record.path("capabilityIds").forEach(c -> recordCapabilities.add(c.asText()));
        final List<String> packCapabilities = new ArrayList<>();
        metadata.path("capabilityIds").forEach(c -> packCapabilities.add(c.asText()));
        if (new HashSet<>(packCapabilities).size() != packCapabilities.size()) {
            violations.add("capabilityIds contains duplicate entries");
        }
        if (!packCapabilities.equals(recordCapabilities)) {
            violations.add("capabilityIds does not exactly match record capabilityIds");
        }
        final JsonNode capabilityCountNode = metadata.path("capabilityCount");
        if (!capabilityCountNode.isIntegralNumber() || !capabilityCountNode.canConvertToInt()
                || capabilityCountNode.intValue() != recordCapabilities.size()) {
            violations.add("capabilityCount " + capabilityCountNode.asText()
                + " is not an exact int equal to record capability count " + recordCapabilities.size());
        }

        if (!recordSha256.equals(metadata.path("verificationRecordSha256").asText())) {
            violations.add("verificationRecordSha256 does not match sha256 of the referenced record bytes");
        }
        return violations;
    }

    private static String sha256Hex(byte[] bytes) throws Exception {
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @Test
    void boundingBoxDraftRemainsAnUnverifiedProjectionOfItsStaticEvidence() throws Exception {
        JsonNode pack = mapper.readTree(DRAFT_DIR.resolve("cubism-5.3.03-ui-bounding-box-overlay.json").toFile());
        Path project = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")));
        JsonNode evidence = mapper.readTree(project.resolve(pack.path("metadata").path("inventoryRef").asText()).toFile());
        assertEquals("DRAFT", pack.path("status").asText());
        assertEquals("JAR_METADATA", evidence.path("evidenceType").asText());
        assertEquals(pack.path("cubismVersion"), evidence.path("cubismVersion"));
        assertEquals(pack.path("metadata").path("artifactSha256"), evidence.path("artifact").path("sha256"));
        var selectors = java.util.stream.StreamSupport.stream(evidence.path("selectors").spliterator(), false)
            .collect(java.util.stream.Collectors.toMap(value -> value.path("mappingId").asText(), java.util.function.Function.identity()));
        assertEquals(12, pack.path("entries").size());
        assertEquals(selectors.size(), pack.path("entries").size());
        for (JsonNode entry : pack.path("entries")) {
            JsonNode selector = selectors.get(entry.path("semanticName").asText());
            assertNotNull(selector);
            assertEquals("VERIFIED_STATIC", selector.path("status").asText());
            assertEquals(selector.path("alias"), entry.path("name"));
            assertEquals(selector.path("kind"), entry.path("kind"));
            assertEquals(selector.path("memberName"), entry.path("runtime"));
            assertEquals(selector.path("descriptor"), entry.path("descriptor"));
            for (String field : List.of("ownerInternalName", "requiredAccessFlags", "forbiddenAccessFlags")) {
                assertEquals(selector.path(field), entry.path("x.verification").path(field));
            }
            assertEquals("DRAFT", entry.path("status").asText());
            assertEquals("medium", entry.path("confidence").asText());
            assertEquals("none", entry.path("verifiedBy").asText());
            assertTrue(entry.path("verifiedAt").isNull());
        }
    }

    @Test
    void draftPacksAreNotInRuntimeEnabledResources() throws Exception {

        Path runtimeMapping = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
            .resolve("runtime/src/main/resources/turboism/mapping");
        if (!Files.exists(runtimeMapping)) {
            return;
        }
        try (Stream<Path> files = Files.walk(runtimeMapping)) {
            AtomicInteger count = new AtomicInteger(0);
            files.filter(p -> p.toString().endsWith(".json")).forEach(p -> count.incrementAndGet());
            assertEquals(0, count.get(),
                "runtime/src/main/resources/turboism/mapping must not contain draft mapping JSON");
        }
    }
}
