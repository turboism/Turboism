package dev.turboism.adapter.cubism.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CoreVersionExpectationTest {

    /**
     * The reviewed Core tuple map must cover every reviewed Cubism version: production
     * admission calls {@code reviewedProfile} on the admitted Core record's version, so
     * any missing entry rejects a verified host before plugin loading.
     */
    @Test
    void everyReviewedVersionResolvesItsHostObservedTuple() {
        final Map<String, CoreRuntimeVersion> expected = new LinkedHashMap<>();
        for (final String version : java.util.List.of(
                "5.0.00", "5.0.01", "5.0.02", "5.0.03", "5.0.04", "5.0.05", "5.0.06", "5.1.00", "5.1.01", "5.1.02",
                "5.1.03")) {
            expected.put(version, new CoreRuntimeVersion(5, 0, 0));
        }
        for (final String version : java.util.List.of("5.2.00", "5.2.01", "5.2.02", "5.2.03")) {
            expected.put(version, new CoreRuntimeVersion(5, 0, 256));
        }
        for (final String version : java.util.List.of("5.3.00", "5.3.01", "5.3.02", "5.3.03")) {
            expected.put(version, new CoreRuntimeVersion(6, 0, 257));
        }
        expected.put("5.3.04", new CoreRuntimeVersion(6, 0, 513));

        assertEquals(
                ReviewedHostArtifacts.reviewedCubismVersions(),
                expected.keySet(),
                "reviewedProfile must cover exactly the reviewed Cubism versions");
        expected.forEach((version, tuple) -> {
            final CoreVersionExpectation expectation = CoreVersionExpectation.reviewedProfile(version);
            assertEquals(
                    tuple, expectation.exactVersion(), version + ": tuple drifted from the host-observed Core version");
            assertTrue(expectation.matches(tuple), version + ": must match its own tuple");
        });
    }

    @Test
    void sharedCoreJarGenerationsShareTheReviewedTuple() {
        // 5.3.00-5.3.03 ship byte-identical Core jars; all report 6.0.257.
        final CoreRuntimeVersion shared = new CoreRuntimeVersion(6, 0, 257);
        for (final String version : new String[] {"5.3.00", "5.3.01", "5.3.02", "5.3.03"}) {
            assertEquals(shared, CoreVersionExpectation.reviewedProfile(version).exactVersion());
        }
        assertFalse(CoreVersionExpectation.reviewedProfile("5.3.02").matches(new CoreRuntimeVersion(6, 0, 513)));
        assertFalse(CoreVersionExpectation.reviewedProfile("5.3.04").matches(new CoreRuntimeVersion(6, 0, 257)));
        assertFalse(CoreVersionExpectation.reviewedProfile("5.2.03").matches(new CoreRuntimeVersion(5, 0, 0)));
    }

    @Test
    void unknownOrPartialVersionIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> CoreVersionExpectation.reviewedProfile("5.4"));
        assertThrows(IllegalArgumentException.class, () -> CoreVersionExpectation.reviewedProfile("5.3"));
        assertThrows(IllegalArgumentException.class, () -> CoreVersionExpectation.reviewedProfile("5.3.05"));
        assertThrows(IllegalArgumentException.class, () -> CoreVersionExpectation.reviewedProfile(""));
        assertThrows(NullPointerException.class, () -> CoreVersionExpectation.reviewedProfile(null));
    }
}
