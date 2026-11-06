package dev.turboism.compatibility.policy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Pins exact version+build review admission, including deep-adaptation targets. */
final class ReviewedCubismReleasesTest {

    @ParameterizedTest
    @CsvSource({
        "5.2.03, 502030002, true",
        "5.3.02, 503020001, true",
        "5.3.03, 503030001, true",
        "5.2.03, 502030001, false",
        "5.3.02, 503020002, false",
        "5.3.03, 503030002, false",
        "5.3.99, 503990001, false",
        "5.3.02, 503030001, false",
        "5.3.2, 503020001, false",
        "5.3.02, 0, false"
    })
    void reviewRequiresTheObservedVersionAndBuild(final String version, final int build, final boolean reviewed) {
        assertEquals(reviewed, ReviewedCubismReleases.isReviewed(version, build));
    }

    @ParameterizedTest
    @CsvSource({"5.1.99, 501990000", "5.9.99, 509990001", "6.0.00, 600000001"})
    void unknownOrUnreviewedFutureVersionsFailClosed(final String version, final int build) {
        assertFalse(ReviewedCubismReleases.isReviewed(version, build));
    }

    @Test
    void nullVersionFailsClosedByContract() {
        assertThrows(NullPointerException.class, () -> ReviewedCubismReleases.isReviewed(null, 503020001));
    }
}
