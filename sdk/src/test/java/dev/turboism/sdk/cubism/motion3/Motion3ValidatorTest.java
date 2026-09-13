package dev.turboism.sdk.cubism.motion3;

import org.junit.jupiter.api.Test;

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
}
