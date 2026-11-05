package dev.turboism.compatibility.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Pins the generated exact reviewed release matrix used for every admission decision. */
class ReviewedReleaseTableTest {

    @Test
    void keepsEveryReviewedReleaseInTableOrder() {
        assertEquals(
                List.of(
                        "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01",
                        "5.1.02", "5.1.03", "5.2.00", "5.2.01", "5.2.02", "5.2.03", "5.3.00", "5.3.01", "5.3.02",
                        "5.3.03", "5.3.04"),
                ReviewedReleaseTable.versions());
        assertEquals(20, ReviewedReleaseTable.all().size());
    }

    @Test
    void preservesObservedBuildsCoreTuplesAndFullRuntimeDecisions() {
        final ReviewedReleaseTable.Release adaptation5203 =
                ReviewedReleaseTable.find("5.2.03").orElseThrow();
        assertEquals(502030002, adaptation5203.build());
        assertEquals(5, adaptation5203.coreMajor());
        assertEquals(0, adaptation5203.coreMinor());
        assertEquals(256, adaptation5203.corePatch());
        assertTrue(adaptation5203.fullRuntime());

        final ReviewedReleaseTable.Release adaptation5302 =
                ReviewedReleaseTable.find("5.3.02").orElseThrow();
        assertEquals(503020001, adaptation5302.build());
        assertEquals(6, adaptation5302.coreMajor());
        assertEquals(0, adaptation5302.coreMinor());
        assertEquals(257, adaptation5302.corePatch());
        assertTrue(adaptation5302.fullRuntime());

        final ReviewedReleaseTable.Release newest =
                ReviewedReleaseTable.find("5.3.04").orElseThrow();
        assertEquals(503040001, newest.build());
        assertEquals(6, newest.coreMajor());
        assertEquals(0, newest.coreMinor());
        assertEquals(513, newest.corePatch());
        assertTrue(newest.fullRuntime());
    }

    @Test
    void findsOnlyExactReviewedVersionLabels() {
        assertTrue(ReviewedReleaseTable.find("5.3.02").isPresent());
        assertFalse(ReviewedReleaseTable.find("5.3.2").isPresent());
        assertFalse(ReviewedReleaseTable.find("5.9.99").isPresent());
        assertFalse(ReviewedReleaseTable.find("").isPresent());
        assertThrows(NullPointerException.class, () -> ReviewedReleaseTable.find(null));
    }
}
