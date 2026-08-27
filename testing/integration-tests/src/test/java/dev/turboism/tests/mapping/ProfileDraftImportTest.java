package dev.turboism.tests.mapping;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.turboism.mapping.schema.ProfileValidator;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for M3 draft profile import.
 */
class ProfileDraftImportTest {

    private static final Path DRAFT_DIR = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
        .resolve("compatibility/cubism/profiles/draft");

    private final ObjectMapper mapper = new ObjectMapper();
    private final ProfileValidator validator = new ProfileValidator();

    @Test
    void allDraftProfilesAreValid() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
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
    void profileIdsAreUnique() throws Exception {
        Set<String> seen = new HashSet<>();
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    String id = root.get("profileId").asText();
                    assertTrue(seen.add(id), "Duplicate profileId: " + id);
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void noProfilesAreVerified() throws Exception {
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    assertEquals("DRAFT", root.get("status").asText(),
                        path.getFileName() + " must be DRAFT");
                    assertEquals("none", root.get("verifiedBy").asText(),
                        path.getFileName() + " verifiedBy must be none");
                    assertTrue(root.get("verifiedAt").isNull(),
                        path.getFileName() + " verifiedAt must be null");
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void profilesReferenceExistingDraftPacks() throws Exception {
        Path packsDir = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
            .resolve("compatibility/cubism/mapping-packs/draft");
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    JsonNode refs = root.get("mappingPacks");
                    assertNotNull(refs, path.getFileName() + " must have mappingPacks");
                    for (JsonNode ref : refs) {
                        String refName = ref.asText() + ".json";
                        Path expected = packsDir.resolve(refName);
                        assertTrue(Files.exists(expected),
                            path.getFileName() + " references missing pack: " + refName);
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    @Test
    void semanticNamesAreCompatibleWithinEachProfile() throws Exception {
        Path packsDir = Paths.get(System.getProperty("projectRoot", System.getProperty("user.dir")))
            .resolve("compatibility/cubism/mapping-packs/draft");
        try (Stream<Path> files = Files.list(DRAFT_DIR)) {
            files.filter(p -> p.toString().endsWith(".json")).forEach(path -> {
                try {
                    JsonNode root = mapper.readTree(path.toFile());
                    String profileId = root.get("profileId").asText();
                    Map<String, JsonNode> seen = new HashMap<>();
                    JsonNode refs = root.get("mappingPacks");
                    assertNotNull(refs, path.getFileName() + " must have mappingPacks");
                    for (JsonNode ref : refs) {
                        Path packPath = packsDir.resolve(ref.asText() + ".json");
                        JsonNode pack = mapper.readTree(packPath.toFile());
                        JsonNode entries = pack.get("entries");
                        assertNotNull(entries, packPath.getFileName() + " must have entries");
                        for (JsonNode entry : entries) {
                            String semanticName = entry.get("semanticName").asText();
                            JsonNode previous = seen.putIfAbsent(semanticName, entry);
                            assertTrue(previous == null || sameSelector(previous, entry),
                                path.getFileName() + " (profile " + profileId
                                    + ") has conflicting duplicate semanticName: " + semanticName);
                        }
                    }
                } catch (Exception e) {
                    fail(path.getFileName() + " could not be parsed: " + e.getMessage());
                }
            });
        }
    }

    private static boolean sameSelector(
        final JsonNode left,
        final JsonNode right
    ) {
        return text(left, "kind").equals(text(right, "kind"))
            && text(left, "runtime").equals(text(right, "runtime"))
            && text(left, "intermediary").equals(text(right, "intermediary"))
            && text(left, "descriptor").equals(text(right, "descriptor"));
    }

    private static String text(
        final JsonNode entry,
        final String field
    ) {
        final String value = entry.path(field).asText();
        return value.length() >= 2
            && value.startsWith("\"")
            && value.endsWith("\"")
                ? value.substring(1, value.length() - 1)
                : value;
    }
}
