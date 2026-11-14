package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;

class ClassPinTableTest {

    /** Tables that ship in the runtime jar and must resolve every reviewed version. */
    private static final List<String> SHIPPED_TABLES = List.of(
            "float-array-parse",
            "image-archive-reuse",
            "incremental-update",
            "matrix-scratch",
            "mesh-interpolation",
            "mesh-mirror",
            "mesh-triangulation-hash",
            "model-update-skip",
            "reinit-pair-elision",
            "shader-uniform",
            "startup-suppression",
            "texture-upload-preparation",
            "uniform-location-lifecycle",
            "warp-alt-mirror",
            "warp-position-projection");

    @Test
    void loadsWellFormedResource() {
        // Uses an embedded test resource — see class-pins/test-fixture.json
        final Map<String, List<String>> table = ClassPinTable.load("test-fixture");
        assertEquals(2, table.size());
        assertTrue(table.containsKey("5.2.03"));
        assertTrue(table.containsKey("5.3.02"));
        assertEquals(List.of("com/example/ClassA", "com/example/ClassB"), table.get("5.2.03"));
        assertEquals(1, table.get("5.3.02").size());
    }

    @Test
    void missingResourceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("nonexistent"));
    }

    @Test
    void duplicateClassEntryFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-bad-entry"));
    }

    @Test
    void duplicateVersionKeyFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-duplicate-version"));
    }

    @Test
    void emptyTableIsPermitted() {
        final Map<String, List<String>> table = ClassPinTable.load("test-empty");
        assertTrue(table.isEmpty());
    }

    @Test
    void classesReturnsDeclaredTargets() {
        assertEquals(
                List.of("com/example/ClassA", "com/example/ClassB"), ClassPinTable.classes("test-fixture", "5.2.03"));
    }

    @Test
    void classesMissingVersionFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.classes("test-fixture", "9.9.99"));
    }

    @Test
    void returnedMapsAreImmutable() {
        final Map<String, List<String>> table = ClassPinTable.load("test-fixture");
        assertThrows(UnsupportedOperationException.class, () -> table.put("x", List.of()));
        assertThrows(
                UnsupportedOperationException.class, () -> table.get("5.2.03").add("x"));
    }

    @Test
    void compactTableCoversEveryReviewedVersion() {
        final Map<String, List<String>> table = ClassPinTable.load("test-compact");
        assertEquals(ReviewedHostArtifacts.reviewedCubismVersions(), table.keySet());
        for (final var entry : table.entrySet()) {
            assertEquals(List.of("com/example/Shared"), entry.getValue(), entry.getKey());
        }
    }

    @Test
    void compactOverridesReplaceTheDefaultBlock() {
        final Map<String, List<String>> table = ClassPinTable.load("test-compact-override");
        assertEquals(List.of("com/example/Shared"), table.get("5.0.00"));
        assertEquals(List.of("com/example/Shared"), table.get("5.3.03"));
        assertEquals(List.of("com/example/Special"), table.get("5.3.04"));
    }

    @Test
    void compactTableWithoutDefaultFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-compact-no-default"));
    }

    @Test
    void compactTableMixingVersionKeysFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-compact-mixed"));
    }

    @Test
    void compactOverrideForUnreviewedVersionFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-compact-unreviewed"));
    }

    @Test
    void compactOverrideRepeatingDefaultFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-compact-redundant"));
    }

    @Test
    void compactBlankTargetFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-compact-blank"));
    }

    @Test
    void repeatedLoadReturnsTheSameCachedMap() {
        final Map<String, List<String>> first = ClassPinTable.load("test-fixture");
        assertSame(first, ClassPinTable.load("test-fixture"));
        assertSame(first.get("5.2.03"), ClassPinTable.load("test-fixture").get("5.2.03"));
    }

    @Test
    void concurrentLoadsAgreeOnOneCachedMap() throws Exception {
        final ExecutorService pool = Executors.newFixedThreadPool(8);
        try {
            final List<Callable<Map<String, List<String>>>> loads = new ArrayList<>();
            for (int index = 0; index < 64; index++) {
                loads.add(() -> ClassPinTable.load("warp-position-projection"));
            }
            final List<Future<Map<String, List<String>>>> futures = pool.invokeAll(loads);
            Map<String, List<String>> expected = null;
            for (final Future<Map<String, List<String>>> future : futures) {
                final Map<String, List<String>> loaded = future.get();
                if (expected == null) {
                    expected = loaded;
                }
                assertSame(expected, loaded);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    /** Every shipped table resolves all reviewed versions in canonical order with non-empty targets. */
    @Test
    void shippedTablesCoverEveryReviewedVersionInOrder() {
        final List<String> reviewedOrder = ReviewedHostArtifacts.all().stream()
                .map(artifact -> ReviewedHostArtifacts.cubismVersionOf(artifact).orElseThrow())
                .toList();
        for (final String name : SHIPPED_TABLES) {
            final Map<String, List<String>> compact = ClassPinTable.load(name);
            assertEquals(reviewedOrder, List.copyOf(compact.keySet()), name);
            for (final var entry : compact.entrySet()) {
                assertTrue(!entry.getValue().isEmpty(), name + "@" + entry.getKey());
            }
        }
    }
}
