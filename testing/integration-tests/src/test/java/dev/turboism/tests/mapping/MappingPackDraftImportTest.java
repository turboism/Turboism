package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.schema.MappingPackValidator;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
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
        assertEquals(620, root.path("entries").size());
        assertEquals(58, metadata.path("capabilityCount").asInt());
        final Set<String> capabilities = asStringSet(metadata.path("capabilityIds"));
        assertEquals(metadata.path("capabilityCount").asInt(), capabilities.size());
        assertTrue(capabilities.containsAll(Set.of(
            "cubism.editor-model.read",
            "cubism.editor-model.texture.read",
            "cubism.editor-model.parameter-structure.write",
            "cubism.editor-model.part-structure.write"
        )));
    }

    private static Set<String> asStringSet(final JsonNode array) {
        final java.util.HashSet<String> values = new java.util.HashSet<>();
        array.forEach(value -> assertTrue(values.add(value.asText())));
        return Set.copyOf(values);
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
