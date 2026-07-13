package dev.turboism.distribution.record;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolRootAndCapabilitiesTest {
    private static final String VALID = """
        {"format":"turboism.distribution.protocol","schemaVersion":1,"rootId":"root-one",
        "rootPath":"/srv/turboism","protocolVersion":1,"javaMajor":17,
        "initializedAt":"2026-07-12T10:15:30.123456789Z",
        "environment":{"scope":"EXPLICIT_ROOT","storage":"LOCAL_SINGLE_FILESYSTEM","userMode":"SINGLE_USER"},
        "capabilities":{"atomicReplaceMove":true,"fileForce":true,"directorySync":"SUPPORTED","noFollowObjectIdentity":true},
        "fileStoreId":"store-one"}
        """;

    @Test void acceptsPortableAbsoluteRootForms() {
        for (String path : Set.of("/", "/srv/turboism", "/é/模型", "C:/", "C:/Turboism", "Z:/data/root")) {
            assertTrue(parse(withRoot(path)).isValid(), path);
        }
    }

    @Test void rejectsNonPortableRootForms() {
        Set<String> invalid = Set.of(
            "", "relative/root", "./root", "../root", "c:/root", "C:root", "C:\\root",
            "//server/share", "\\\\server\\share", "//?/C:/root", "/a//b", "/a/./b", "/a/../b",
            "/a:stream", "/CON", "/con.txt", "/Lpt9.log", "/name.", "/name ", "/line\nbreak"
        );
        for (String path : invalid) {
            assertEquals("PROTOCOL_ROOT_PATH_INVALID", firstIssue(withRoot(path)).code(), path);
        }
    }

    @Test void acceptsAllThreeDirectorySyncValuesWithoutWarnings() {
        for (String value : Set.of("SUPPORTED", "BEST_EFFORT", "UNSUPPORTED")) {
            ProtocolValidationResult result = parse(VALID.replace("SUPPORTED", value));
            assertTrue(result.isValid(), value);
            assertTrue(result.issues().isEmpty(), value);
        }
    }

    @Test void rejectsOtherDirectorySyncValuesAndFalseMandatoryCapabilities() {
        assertEquals("PROTOCOL_VALUE_INVALID", firstIssue(VALID.replace("SUPPORTED", "UNKNOWN")).code());
        assertEquals("PROTOCOL_VALUE_INVALID", firstIssue(VALID.replace("\"fileForce\":true", "\"fileForce\":false")).code());
        assertEquals("PROTOCOL_VALUE_INVALID", firstIssue(VALID.replace(
            "\"noFollowObjectIdentity\":true", "\"noFollowObjectIdentity\":false")).code());
    }

    @Test void mapsEveryValidationIssueToStableDiagnosticClassWithoutPersistenceFields() {
        ProtocolValidationIssue issue = firstIssue(VALID.replace("\"schemaVersion\":1", "\"schemaVersion\":2"));
        ProtocolDiagnosticMapping mapping = ProtocolDiagnosticMapping.forIssue(issue);
        assertEquals(issue.code(), mapping.code());
        assertEquals("RECORD_CORRUPTION", mapping.category());
        assertEquals("ERROR", mapping.severity());
        assertEquals(3, ProtocolDiagnosticMapping.class.getRecordComponents().length);
    }

    private static String withRoot(String root) {
        return VALID.replace("/srv/turboism", root.replace("\\", "\\\\").replace("\n", "\\n"));
    }

    private static ProtocolValidationIssue firstIssue(String input) {
        return parse(input).issues().get(0);
    }

    private static ProtocolValidationResult parse(String input) {
        return ProtocolRecordParser.parse(input.getBytes(StandardCharsets.UTF_8));
    }
}
