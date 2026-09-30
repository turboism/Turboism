package dev.turboism.mapping.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
}
