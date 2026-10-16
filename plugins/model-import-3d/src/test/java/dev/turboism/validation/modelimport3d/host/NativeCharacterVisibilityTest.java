package dev.turboism.validation.modelimport3d.host;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class NativeCharacterVisibilityTest {
    @Test
    void contiguousVotingMatchesPixelScanForSparseCoverageAndDepthTies() {
        var random = new Random(66);
        for (int partitions : new int[] {0, 1, 2, 17, 129}) {
            float[][] depth = new float[partitions][397];
            for (var row : depth)
                for (int k = 0; k < row.length; k++)
                    row[k] = switch (random.nextInt(6)) {
                        case 0, 1, 2 -> Float.NEGATIVE_INFINITY;
                        case 3 -> 1f; // frequent ties must favor the first partition
                        case 4 -> 1f + random.nextInt(3) * 0.0000005f;
                        default -> random.nextFloat() * 10 - 5;
                    };
            assertVotesEqual(pixelScan(depth, 397), NativeCharacterVisibility.coverageVotes(depth, 397));
        }
    }

    @Test
    void coverageVotingRetainsEmptyPixelsTieThresholdAndNonfiniteBehavior() {
        float[][] depth = {
            {Float.NEGATIVE_INFINITY, 1, 0, Float.NaN, 0, Float.POSITIVE_INFINITY},
            {Float.NEGATIVE_INFINITY, 1, 0.0000005f, 1, 0.000002f, 2},
            {Float.NEGATIVE_INFINITY, 0, -1, 0, 0.000002f, Float.NaN}
        };
        assertVotesEqual(pixelScan(depth, 6), NativeCharacterVisibility.coverageVotes(depth, 6));
        assertVotesEqual(new long[3][3], NativeCharacterVisibility.coverageVotes(new float[3][0], 0));
    }

    private static void assertVotesEqual(long[][] expected, long[][] actual) {
        assertEquals(expected.length, actual.length);
        for (int i = 0; i < expected.length; i++) assertArrayEquals(expected[i], actual[i], "partition " + i);
    }

    /** Baseline traversal before the cache-locality change. */
    static long[][] pixelScan(float[][] z, int pixels) {
        int n = z.length;
        long[][] votes = new long[n][n];
        for (int k = 0; k < pixels; k++) {
            int best = -1;
            float dep = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < n; i++)
                if (z[i][k] > dep) {
                    dep = z[i][k];
                    best = i;
                }
            if (best < 0) continue;
            for (int i = 0; i < n; i++)
                if (i != best && Float.isFinite(z[i][k]) && z[i][k] < dep - 1e-6) votes[i][best]++;
        }
        return votes;
    }
}
