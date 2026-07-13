package dev.turboism.distribution.record;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolFixtureMatrixTest {
    private static final String ROOT = "fixtures/schema/distribution-protocol-v1";

    @Test void acceptsEveryPersistentValidFixture() throws Exception {
        for (Path fixture : fixtures("valid")) {
            assertTrue(ProtocolRecordParser.parse(Files.readAllBytes(fixture)).isValid(), fixture.toString());
        }
    }

    @Test void rejectsEveryPersistentInvalidFixture() throws Exception {
        for (Path fixture : fixtures("invalid")) {
            assertFalse(ProtocolRecordParser.parse(Files.readAllBytes(fixture)).isValid(), fixture.toString());
        }
    }

    @Test void diagnosticMappingTsvIsCompleteAndStable() throws Exception {
        Set<String> expectedCodes = Set.of(
            "PROTOCOL_EMPTY", "PROTOCOL_TOO_LARGE", "PROTOCOL_BOM", "PROTOCOL_UTF8_INVALID",
            "PROTOCOL_JSON_INVALID", "PROTOCOL_ROOT_TYPE_INVALID", "PROTOCOL_UNKNOWN_FIELD",
            "PROTOCOL_FIELD_MISSING", "PROTOCOL_TYPE_INVALID", "PROTOCOL_VALUE_INVALID",
            "PROTOCOL_ID_INVALID", "PROTOCOL_ROOT_PATH_INVALID", "PROTOCOL_TIMESTAMP_INVALID"
        );
        List<String> lines = readResource(ROOT + "/diagnostic-mapping.tsv").lines().toList();
        assertEquals("code\tcategory\tseverity", lines.get(0));
        Map<String, String> rows = new HashMap<>();
        for (String line : lines.subList(1, lines.size())) {
            String[] columns = line.split("\\t", -1);
            assertEquals(3, columns.length, line);
            assertEquals("RECORD_CORRUPTION", columns[1], line);
            assertEquals("ERROR", columns[2], line);
            rows.put(columns[0], line);
        }
        assertEquals(expectedCodes, rows.keySet());
    }

    private static List<Path> fixtures(String kind) throws Exception {
        URI uri = ProtocolFixtureMatrixTest.class.getClassLoader().getResource(ROOT + "/" + kind).toURI();
        if (uri.getScheme().equals("file")) return regularFiles(Path.of(uri));
        try (FileSystem fileSystem = FileSystems.newFileSystem(uri, Map.of())) {
            return regularFiles(fileSystem.getPath(ROOT, kind));
        }
    }

    private static List<Path> regularFiles(Path directory) throws IOException {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.list(directory)) {
            stream.filter(Files::isRegularFile).sorted().forEach(files::add);
        }
        return files;
    }

    private static String readResource(String name) throws IOException {
        try (InputStream input = ProtocolFixtureMatrixTest.class.getClassLoader().getResourceAsStream(name)) {
            if (input == null) throw new IOException("Missing resource " + name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
