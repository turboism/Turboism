package dev.turboism.distribution.record;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtocolRecordValidationTest {
    private static final String VALID = """
        {"format":"turboism.distribution.protocol","schemaVersion":1,"rootId":"root-one",
        "rootPath":"/srv/turboism","protocolVersion":1,"javaMajor":17,
        "initializedAt":"2026-07-12T10:15:30Z",
        "environment":{"scope":"EXPLICIT_ROOT","storage":"LOCAL_SINGLE_FILESYSTEM","userMode":"SINGLE_USER"},
        "capabilities":{"atomicReplaceMove":true,"fileForce":true,"directorySync":"SUPPORTED","noFollowObjectIdentity":true},
        "fileStoreId":"store-one"}
        """;

    @Test void acceptsExactProtocolShape() {
        ProtocolValidationResult result = parse(VALID);
        assertEquals(VALID, result.record().orElseThrow().sourceJson());
        assertTrue(result.issues().isEmpty());
    }

    @Test void rejectsUnknownAndMissingFieldsAtEveryObjectLevel() {
        assertIssue(replace("\"fileStoreId\":\"store-one\"", "\"extra\":true,\"fileStoreId\":\"store-one\""),
            "PROTOCOL_UNKNOWN_FIELD", "extra");
        assertIssue(replace("\"rootId\":\"root-one\",", ""), "PROTOCOL_FIELD_MISSING", "rootId");
        assertIssue(replace("\"userMode\":\"SINGLE_USER\"", "\"extra\":true,\"userMode\":\"SINGLE_USER\""),
            "PROTOCOL_UNKNOWN_FIELD", "environment.extra");
        assertIssue(replace(",\"userMode\":\"SINGLE_USER\"", ""), "PROTOCOL_FIELD_MISSING", "environment.userMode");
        assertIssue(replace("\"noFollowObjectIdentity\":true", "\"extra\":true,\"noFollowObjectIdentity\":true"),
            "PROTOCOL_UNKNOWN_FIELD", "capabilities.extra");
        assertIssue(replace(",\"noFollowObjectIdentity\":true", ""),
            "PROTOCOL_FIELD_MISSING", "capabilities.noFollowObjectIdentity");
    }

    @Test void rejectsNullWrongTypesAndNonExactIntegers() {
        assertIssue(replace("\"rootId\":\"root-one\"", "\"rootId\":null"), "PROTOCOL_TYPE_INVALID", "rootId");
        assertIssue(replace(
            "\"environment\":{\"scope\":\"EXPLICIT_ROOT\",\"storage\":\"LOCAL_SINGLE_FILESYSTEM\",\"userMode\":\"SINGLE_USER\"}",
            "\"environment\":null"), "PROTOCOL_TYPE_INVALID", "environment");
        assertIssue(replace(
            "\"capabilities\":{\"atomicReplaceMove\":true,\"fileForce\":true,\"directorySync\":\"SUPPORTED\",\"noFollowObjectIdentity\":true}",
            "\"capabilities\":[]"), "PROTOCOL_TYPE_INVALID", "capabilities");
        assertIssue(replace("\"schemaVersion\":1", "\"schemaVersion\":1.0"),
            "PROTOCOL_TYPE_INVALID", "schemaVersion");
        assertIssue(replace("\"protocolVersion\":1", "\"protocolVersion\":2"),
            "PROTOCOL_VALUE_INVALID", "protocolVersion");
        assertIssue(replace("\"javaMajor\":17", "\"javaMajor\":17.0"),
            "PROTOCOL_TYPE_INVALID", "javaMajor");
        assertIssue(replace("\"atomicReplaceMove\":true", "\"atomicReplaceMove\":false"),
            "PROTOCOL_VALUE_INVALID", "atomicReplaceMove");
        assertIssue(replace("\"fileForce\":true", "\"fileForce\":\"true\""),
            "PROTOCOL_TYPE_INVALID", "fileForce");
    }

    @Test void rejectsInvalidFormatIdsTimestampAndEnvironmentValues() {
        assertIssue(replace("turboism.distribution.protocol", "turboism.distribution.other"),
            "PROTOCOL_VALUE_INVALID", "format");
        assertIssue(replace("root-one", "Root_One"), "PROTOCOL_ID_INVALID", "rootId");
        assertIssue(replace("store-one", "ab"), "PROTOCOL_ID_INVALID", "fileStoreId");
        assertIssue(replace("2026-07-12T10:15:30Z", "2026-02-30T10:15:30Z"),
            "PROTOCOL_TIMESTAMP_INVALID", "initializedAt");
        assertIssue(replace("EXPLICIT_ROOT", "AUTO_ROOT"), "PROTOCOL_VALUE_INVALID", "scope");
        assertIssue(replace("LOCAL_SINGLE_FILESYSTEM", "NETWORK"), "PROTOCOL_VALUE_INVALID", "storage");
        assertIssue(replace("SINGLE_USER", "MULTI_USER"), "PROTOCOL_VALUE_INVALID", "userMode");
    }

    @Test void enforcesStrictByteAndJsonEnvelope() {
        assertIssue(new byte[0], "PROTOCOL_EMPTY", "$");
        assertIssue(" \n\t ".getBytes(StandardCharsets.UTF_8), "PROTOCOL_EMPTY", "$");
        assertIssue(new byte[65_537], "PROTOCOL_TOO_LARGE", "$");
        assertIssue(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf, '{', '}'}, "PROTOCOL_BOM", "$");
        assertIssue(new byte[]{(byte) 0xc3, (byte) 0x28}, "PROTOCOL_UTF8_INVALID", "$");
        assertIssue("{".getBytes(StandardCharsets.UTF_8), "PROTOCOL_JSON_INVALID", "$");
        assertIssue("{} {}".getBytes(StandardCharsets.UTF_8), "PROTOCOL_JSON_INVALID", "$");
        assertIssue("[]".getBytes(StandardCharsets.UTF_8), "PROTOCOL_ROOT_TYPE_INVALID", "$");
        assertIssue(replace("\"rootId\":\"root-one\"", "\"rootId\":\"root-one\",\"rootId\":\"root-two\""),
            "PROTOCOL_JSON_INVALID", "$");
    }

    private static String replace(String target, String replacement) {
        return VALID.replace(target, replacement);
    }

    private static ProtocolValidationResult parse(String value) {
        return ProtocolRecordParser.parse(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void assertIssue(String input, String code, String path) {
        assertIssue(input.getBytes(StandardCharsets.UTF_8), code, path);
    }

    private static void assertIssue(byte[] input, String code, String path) {
        ProtocolValidationResult result = ProtocolRecordParser.parse(input);
        assertFalse(result.isValid(), input.length < 2_000 ? new String(input, StandardCharsets.UTF_8) : "large input");
        assertEquals(code, result.issues().get(0).code());
        assertEquals(path, result.issues().get(0).path());
    }
}
