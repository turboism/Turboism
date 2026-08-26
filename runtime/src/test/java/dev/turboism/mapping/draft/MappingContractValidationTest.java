package dev.turboism.mapping.draft;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingContractValidationTest {
    private static final String HASH = "0".repeat(64);

    @Test
    void strictJsonRejectsDuplicateKeysBomAndTrailingTokens() {
        assertStrictFailure("{\"format\":\"a\",\"format\":\"b\"}", "TEST_JSON_INVALID");
        byte[] bom = ("\ufeff{\"format\":\"a\"}").getBytes(StandardCharsets.UTF_8);
        assertEquals("TEST_JSON_INVALID", assertThrows(DraftMappingException.class,
            () -> StrictJson.read(bom, "TEST_JSON_INVALID")).code());
        assertStrictFailure("{} {}", "TEST_JSON_INVALID");
    }

    @Test
    void candidateClosesOperationPathHashNestedAndStateContract() {
        JsonNode valid = StrictJson.read(candidate().getBytes(StandardCharsets.UTF_8), "TEST");
        assertTrue(new MappingUpdateCandidateValidator().validate(valid).isEmpty());

        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"operation\":\"UPDATE_CLASS_RUNTIME\"", "\"operation\":\"UPDATE_METHOD_RUNTIME\"")),
            "MAPPING_UPDATE_CANDIDATE_BAD_OPERATION");
        for (String unsafePack : List.of(
            "compatibility/cubism/mapping-packs/draft/nested/fixture.json",
            "compatibility/cubism/mapping-packs/draft/fixture\\\\name.json",
            "compatibility/cubism/mapping-packs/draft/fixture:name.json",
            "compatibility/cubism/mapping-packs/draft/fixture..json",
            "compatibility/cubism/mapping-packs/draft/fixture\\u0001.json"
        )) {
            assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
                "\"pack\":\"compatibility/cubism/mapping-packs/draft/fixture.json\"", "\"pack\":\"" + unsafePack + "\"")),
                "MAPPING_UPDATE_CANDIDATE_BAD_TARGET");
        }
        for (String unsafeSemantic : List.of("fixture]target", "fixture[target", "fixture/target", "fixture\\\\target", "fixture:target", "fixture..target", "fixture\\u0001target")) {
            assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
                "\"semanticName\":\"fixture.target.class\"", "\"semanticName\":\"" + unsafeSemantic + "\"")),
                "MAPPING_UPDATE_CANDIDATE_BAD_TARGET");
        }
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"basePackSha256\":\"" + HASH + "\"", "\"basePackSha256\":\"ABC\"")),
            "MAPPING_UPDATE_CANDIDATE_BAD_HASH");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"format\":\"turboism.mapping.update.candidate\"", "\"format\":\"wrong\"")),
            "MAPPING_UPDATE_CANDIDATE_BAD_FORMAT");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"schemaVersion\":1", "\"schemaVersion\":2")),
            "MAPPING_UPDATE_CANDIDATE_BAD_SCHEMA_VERSION");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"caller\":{", "\"caller\":{\"unexpected\":true,")),
            "MAPPING_UPDATE_CANDIDATE_UNKNOWN_FIELD");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"invocation\":\"INSTANCE\"", "\"invocation\":\"ANY\"")),
            "MAPPING_UPDATE_CANDIDATE_BAD_EVIDENCE");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/New\"}",
            "\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/Old\"}")),
            "MAPPING_UPDATE_CANDIDATE_NO_CHANGE");
        assertHasCode(new MappingUpdateCandidateValidator().validate(with(candidate(),
            "\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/New\"}",
            "\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/Other\"}")),
            "MAPPING_UPDATE_CANDIDATE_TARGET_MISMATCH");
    }

    @Test
    void formalCandidateValidatorRejectsForbiddenTermsAcrossEverySelectorSurface() {
        MappingUpdateCandidateValidator validator = new MappingUpdateCandidateValidator();
        for (String[] selector : List.of(
            new String[] {"\"semanticName\":\"fixture.target.class\"", "\"semanticName\":\"fixture.target.classLicenseBypass\""},
            new String[] {"\"before\":{\"kind\":\"class\",\"runtime\":\"fixture/Old\"}", "\"before\":{\"kind\":\"class\",\"runtime\":\"fixture/OldLicenseBypass\"}"},
            new String[] {"\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/New\"}", "\"after\":{\"kind\":\"class\",\"runtime\":\"fixture/NewLicenseBypass\"}"},
            new String[] {"\"caller\":{\"owner\":\"fixture/Anchor\"", "\"caller\":{\"owner\":\"fixture/AnchorLicenseBypass\""},
            new String[] {"\"owner\":\"fixture/Anchor\",\"name\":\"anchor\"", "\"owner\":\"fixture/Anchor\",\"name\":\"anchorLicenseBypass\""},
            new String[] {"\"name\":\"anchor\",\"descriptor\":\"()V\"", "\"name\":\"anchor\",\"descriptor\":\"()VLicenseBypass\""},
            new String[] {"\"selectedTarget\":{\"owner\":\"fixture/New\"", "\"selectedTarget\":{\"owner\":\"fixture/NewLicenseBypass\""},
            new String[] {"\"owner\":\"fixture/New\",\"name\":\"selected\"", "\"owner\":\"fixture/New\",\"name\":\"selectedLicenseBypass\""},
            new String[] {"\"name\":\"selected\",\"descriptor\":\"()V\"", "\"name\":\"selected\",\"descriptor\":\"()VLicenseBypass\""}
        )) {
            assertHasCode(validator.validate(with(candidate(), selector[0], selector[1])),
                "MAPPING_UPDATE_CANDIDATE_FORBIDDEN_SELECTOR");
        }
    }

    @Test
    void reviewRequiresCanonicalUtcDecisionCombinationAndExactHashShape() {
        MappingReviewValidator validator = new MappingReviewValidator();
        assertTrue(validator.validate(json(review("PENDING", "null", "null"))).isEmpty());
        assertTrue(validator.validate(json(review("APPROVED", "\"human\"", "\"2026-07-10T00:00:00Z\""))).isEmpty());
        assertHasCode(validator.validate(json(review("PENDING", "\"human\"", "\"2026-07-10T00:00:00Z\""))),
            "MAPPING_UPDATE_REVIEW_PENDING_FIELDS");
        assertHasCode(validator.validate(json(review("APPROVED", "\"human\"", "\"2026-07-10T01:00:00+01:00\""))),
            "MAPPING_UPDATE_REVIEW_BAD_TIME");
        assertHasCode(validator.validate(json(review("APPROVED", "\"human\"", "\"2026-07-10T00:00:00.000Z\""))),
            "MAPPING_UPDATE_REVIEW_BAD_TIME");
        assertHasCode(validator.validate(json(review("UNKNOWN", "\"human\"", "\"2026-07-10T00:00:00Z\""))),
            "MAPPING_UPDATE_REVIEW_BAD_DECISION");
        assertHasCode(validator.validate(with(review("PENDING", "null", "null"),
            "\"candidateSha256\":\"" + HASH + "\"", "\"candidateSha256\":\"ABC\"")),
            "MAPPING_UPDATE_REVIEW_BAD_HASH");
    }

    @Test
    void diffIsOneExactRuntimePresentationAndCannotExpressAuthorization() {
        MappingUpdateDiffValidator validator = new MappingUpdateDiffValidator();
        assertTrue(validator.validate(json(diff())).isEmpty());
        assertHasCode(validator.validate(with(diff(), "\"semanticName\":\"fixture.target.class\"",
            "\"semanticName\":\"fixture.target.class\",\"approved\":true")),
            "MAPPING_UPDATE_DIFF_UNKNOWN_FIELD");
        assertHasCode(validator.validate(with(diff(),
            "entries[semanticName=fixture.target.class].runtime", "entries[semanticName=other].runtime")),
            "MAPPING_UPDATE_DIFF_BAD_CHANGES");
        assertHasCode(validator.validate(with(diff(),
            "\"before\":\"fixture/Old\",\"after\":\"fixture/New\"",
            "\"before\":\"fixture/Old\",\"after\":\"fixture/Old\"")),
            "MAPPING_UPDATE_DIFF_BAD_CHANGES");
        for (String unsafePack : List.of("/tmp/fixture.json", "compatibility/cubism/mapping-packs/draft/nested/fixture.json",
            "compatibility/cubism/mapping-packs/draft/fixture\\\\name.json", "compatibility/cubism/mapping-packs/draft/fixture:name.json",
            "compatibility/cubism/mapping-packs/draft/fixture..json", "compatibility/cubism/mapping-packs/draft/fixture\\u0001.json")) {
            assertHasCode(validator.validate(with(diff(),
                "\"pack\":\"compatibility/cubism/mapping-packs/draft/fixture.json\"", "\"pack\":\"" + unsafePack + "\"")),
                "MAPPING_UPDATE_DIFF_BAD_TARGET");
        }
        for (String unsafeSemantic : List.of("fixture]target", "fixture[target", "fixture/target", "fixture\\\\target", "fixture:target", "fixture..target", "fixture\\u0001target")) {
            assertHasCode(validator.validate(with(diff(), "fixture.target.class", unsafeSemantic)),
                "MAPPING_UPDATE_DIFF_BAD_TARGET");
        }
        assertHasCode(validator.validate(with(diff(),
            "\"path\":\"entries[semanticName=fixture.target.class].runtime\"",
            "\"path\":\"entries[semanticName=fixture.target.class].runtime\",\"unexpected\":true")),
            "MAPPING_UPDATE_DIFF_UNKNOWN_FIELD");
    }

    private static void assertStrictFailure(String text, String code) {
        assertEquals(code, assertThrows(DraftMappingException.class,
            () -> StrictJson.read(text.getBytes(StandardCharsets.UTF_8), code)).code());
    }

    private static JsonNode json(String text) {
        return StrictJson.read(text.getBytes(StandardCharsets.UTF_8), "TEST");
    }

    private static JsonNode with(String source, String oldText, String newText) {
        assertTrue(source.contains(oldText));
        return json(source.replace(oldText, newText));
    }

    private static void assertHasCode(List<dev.turboism.core.schema.SchemaValidationError> errors, String code) {
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(error -> code.equals(error.code())), errors.toString());
    }

    private static String candidate() {
        return """
            {"format":"turboism.mapping.update.candidate","schemaVersion":1,
             "operation":"UPDATE_CLASS_RUNTIME",
             "target":{"pack":"compatibility/cubism/mapping-packs/draft/fixture.json","semanticName":"fixture.target.class"},
             "basePackSha256":"%s",
             "artifact":{"name":"fixture.jar","size":1,"sha256":"%s"},
             "scannerPolicy":{"maxArtifactBytes":1,"maxEntries":1,"maxClassEntries":1,"maxEntryBytes":1,"maxExpandedBytes":1},
             "evidence":{"caller":{"owner":"fixture/Anchor","name":"anchor","descriptor":"()V"},
                         "selectedTarget":{"owner":"fixture/New","name":"selected","descriptor":"()V"},"invocation":"INSTANCE"},
             "before":{"kind":"class","runtime":"fixture/Old"},
             "after":{"kind":"class","runtime":"fixture/New"},
             "resultPackSha256":"%s"}
            """.formatted(HASH, HASH, HASH);
    }

    private static String review(String decision, String reviewer, String reviewedAt) {
        return "{\"format\":\"turboism.mapping.update.review\",\"schemaVersion\":1,"
            + "\"decision\":\"" + decision + "\",\"candidateSha256\":\"" + HASH + "\","
            + "\"reviewer\":" + reviewer + ",\"reviewedAt\":" + reviewedAt + "}";
    }

    private static String diff() {
        return "{\"format\":\"turboism.mapping.update.diff\",\"schemaVersion\":1,"
            + "\"candidateSha256\":\"" + HASH + "\","
            + "\"target\":{\"pack\":\"compatibility/cubism/mapping-packs/draft/fixture.json\",\"semanticName\":\"fixture.target.class\"},"
            + "\"changes\":[{\"path\":\"entries[semanticName=fixture.target.class].runtime\","
            + "\"before\":\"fixture/Old\",\"after\":\"fixture/New\"}]}";
    }
}
