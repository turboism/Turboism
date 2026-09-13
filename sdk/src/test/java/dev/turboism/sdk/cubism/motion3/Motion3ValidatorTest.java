package dev.turboism.sdk.cubism.motion3;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class Motion3ValidatorTest {

    private static final String VALID = """
        {
          "Version": 3,
          "Meta": {
            "Duration": 2.0, "Fps": 30.0, "Loop": true,
            "AreBeziersRestricted": false,
            "CurveCount": 2, "TotalSegmentCount": 3, "TotalPointCount": 5,
            "UserDataCount": 1, "TotalUserDataSize": 4
          },
          "Curves": [
            {
              "Target": "Parameter", "Id": "ParamAngleX",
              "Segments": [0.0, 0.0, 0, 1.0, 0.5, 2, 2.0, 1.0]
            },
            {
              "Target": "PartOpacity", "Id": "PartArm",
              "FadeInTime": 0.5, "FadeOutTime": 0.5,
              "Segments": [0.0, 1.0, 1, 0.8, 0.8, 0.2, 0.2, 1.0, 1.0]
            }
          ],
          "UserData": [
            {"Time": 1.0, "Value": "peak"}
          ]
        }
        """;

    @Test
    void validDocumentReportsNoIssues() {
        final Motion3Report report = Motion3Validator.validate(VALID);
        assertTrue(report.valid());
        assertTrue(report.issues().isEmpty());
    }

    @Test
    void malformedJsonReportsSingleError() {
        final Motion3Report report = Motion3Validator.validate("{ not json");
        assertFalse(report.valid());
        assertEquals(1, report.issues().size());
        assertEquals("$", report.issues().get(0).path());
    }

    @Test
    void duplicateKeysAreRejectedByStrictParser() {
        final Motion3Report report = Motion3Validator.validate(
            "{\"Version\":3,\"Version\":3}");
        assertFalse(report.valid());
        assertEquals("$", report.issues().get(0).path());
    }

    @Test
    void nonObjectRootIsAnError() {
        final Motion3Report report = Motion3Validator.validate("[1,2]");
        assertFalse(report.valid());
        assertTrue(report.errors().get(0).path().equals("$"));
    }

    @Test
    void wrongVersionIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":4,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[],"UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.Version")));
    }

    @Test
    void missingMetaFieldsAreErrors() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Loop":true},"Curves":[],"UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.Meta.Duration")));
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.Meta.Fps")));
    }

    @Test
    void unknownCurveTargetIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Bogus","Id":"X","Segments":[0.0,0.0]}],
             "UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.Curves[0].Target")));
    }

    @Test
    void invalidSegmentKindIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":2.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P",
               "Segments":[0.0,0.0,9,1.0,1.0]}],
             "UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().startsWith("$.Curves[0].Segments")));
    }

    @Test
    void truncatedBezierSegmentIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":2.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P",
               "Segments":[0.0,0.0,1,0.5,0.5,1.0]}],
             "UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.message().contains("bezier")));
    }

    @Test
    void decreasingSegmentTimeIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":2.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P",
               "Segments":[0.0,0.0,0,1.0,0.5,0,0.5,0.7]}],
             "UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.message().contains("increase")));
    }

    @Test
    void metaCountMismatchIsWarningNotError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":2.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false,"CurveCount":9},
             "Curves":[{"Target":"Parameter","Id":"P","Segments":[0.0,0.0]}],
             "UserData":[]}
            """);
        assertTrue(report.valid());
        assertEquals(1, report.warnings().size());
        assertEquals("$.Meta.CurveCount", report.warnings().get(0).path());
    }

    @Test
    void segmentBeyondDurationIsWarning() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P",
               "Segments":[0.0,0.0,0,1.5,0.5]}],
             "UserData":[]}
            """);
        assertTrue(report.valid());
        assertEquals(1, report.warnings().size());
        assertTrue(report.warnings().get(0).message().contains("Duration"));
    }

    @Test
    void userDataTimeBeyondDurationIsWarning() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[],"UserData":[{"Time":5.0,"Value":"late"}]}
            """);
        assertTrue(report.valid());
        assertTrue(report.warnings().stream()
            .anyMatch(issue -> issue.path().equals("$.UserData[0].Time")));
    }

    @Test
    void nonStringUserDataValueIsError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[],"UserData":[{"Time":0.5,"Value":42}]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.UserData[0].Value")));
    }

    @Test
    void negativeFadeTimeIsAnError() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P","FadeInTime":-0.5,
               "Segments":[0.0,0.0]}],
             "UserData":[]}
            """);
        assertFalse(report.valid());
        assertTrue(report.errors().stream()
            .anyMatch(issue -> issue.path().equals("$.Curves[0].FadeInTime")));
    }

    @Test
    void emptyCurveListWarnsButStaysValid() {
        final Motion3Report report = Motion3Validator.validate("""
            {"Version":3,"Meta":{"Duration":1.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[],"UserData":[]}
            """);
        assertTrue(report.valid());
        assertFalse(report.warnings().isEmpty());
    }

    @Test
    void fractionalVersionIsAnError() {
        assertVersionError("3.5");
        assertVersionError("3.0000000000000000001");
        assertVersionError("2.9999999999999999999");
    }

    @Test
    void wrappedAroundVersionIsAnError() {
        // longValue() truncation makes each of these equal 3.
        assertVersionError("18446744073709551619");   // 2^64 + 3
        assertVersionError("-18446744073709551613");  // -2^64 + 3
        assertVersionError("36893488147419103235");   // 2^65 + 3
    }

    @Test
    void nonNumericVersionIsAnError() {
        assertVersionError("\"3\"");
        assertVersionError("true");
    }

    @Test
    void equivalentVersionRepresentationsStayValid() {
        for (final String token : List.of("3", "3.0", "3e0", "30e-1", "0.3e1")) {
            final Motion3Report report = Motion3Validator.validate(
                document(token, "[0.0,0.0]"));
            assertTrue(report.valid(),
                "Version=" + token + " issues: " + report.issues());
            assertTrue(report.issues().isEmpty());
        }
    }

    @Test
    void oversizedSegmentKindIsAnError() {
        // intValue() wraps each of these into the accepted 0..3 range.
        assertKindError("4294967296");   // 2^32 -> 0
        assertKindError("4294967299");   // 2^32 + 3 -> 3
        assertKindError("8589934595");   // 2^33 + 3 -> 3
        assertKindError("12884901890");  // 3*2^32 + 2 -> 2
    }

    @Test
    void wrappedAroundSegmentKindsAreErrors() {
        assertKindError("-4294967296");           // -2^32 -> 0
        assertKindError("-4294967293");           // -2^32 + 3 -> 3
        assertKindError("9223372036854775808");   // 2^63 -> 0 (beyond long)
        assertKindError("18446744073709551619");  // 2^64 + 3 -> 3
    }

    @Test
    void fractionalSegmentKindIsAnError() {
        assertKindError("0.5");
        assertKindError("2.5");
        assertKindError("3.0000000000000000001");
    }

    @Test
    void negativeSegmentKindIsAnError() {
        assertKindError("-1");
        assertKindError("-0.5");
    }

    @Test
    void allSegmentKindsFromZeroToThreeAreAccepted() {
        for (final String segments : List.of(
            "[0.0,0.0,0,1.0,0.5]",
            "[0.0,0.0,1,0.8,0.8,0.2,0.2,1.0,1.0]",
            "[0.0,0.0,2,1.0,1.0]",
            "[0.0,0.0,3,1.0,1.0]"
        )) {
            final Motion3Report report = Motion3Validator.validate(
                document("3", segments));
            assertTrue(report.valid(),
                "Segments=" + segments + " issues: " + report.issues());
            assertTrue(report.issues().isEmpty());
        }
    }

    @Test
    void integerValuedSegmentKindRepresentationsStayValid() {
        for (final String segments : List.of(
            "[0.0,0.0,0.0,1.0,0.5]",
            "[0.0,0.0,1e0,0.8,0.8,0.2,0.2,1.0,1.0]",
            "[0.0,0.0,2.0,1.0,1.0]",
            "[0.0,0.0,0.3e1,1.0,1.0]"
        )) {
            final Motion3Report report = Motion3Validator.validate(
                document("3", segments));
            assertTrue(report.valid(),
                "Segments=" + segments + " issues: " + report.issues());
            assertTrue(report.issues().isEmpty());
        }
    }

    private static String document(
        final String versionToken,
        final String segmentsToken
    ) {
        return """
            {"Version":%s,"Meta":{"Duration":2.0,"Fps":30.0,"Loop":false,
              "AreBeziersRestricted":false},
             "Curves":[{"Target":"Parameter","Id":"P","Segments":%s}],
             "UserData":[]}
            """.formatted(versionToken, segmentsToken);
    }

    private static void assertVersionError(final String versionToken) {
        final Motion3Report report = Motion3Validator.validate(
            document(versionToken, "[0.0,0.0]"));
        assertFalse(report.valid(), "Version=" + versionToken);
        assertEquals(1, report.errors().size());
        assertEquals("$.Version", report.errors().get(0).path());
    }

    private static void assertKindError(final String kindToken) {
        final Motion3Report report = Motion3Validator.validate(
            document("3", "[0.0,0.0," + kindToken + ",1.0,0.5]"));
        assertFalse(report.valid(), "kind=" + kindToken);
        assertEquals(1, report.errors().size());
        assertEquals("$.Curves[0].Segments[2]", report.errors().get(0).path());
    }
}
