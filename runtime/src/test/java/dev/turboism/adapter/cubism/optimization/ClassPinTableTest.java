package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassPinTableTest {

    @Test
    void loadsWellFormedResource() {
        // Uses an embedded test resource — see class-pins/test-fixture.json
        final Map<String, Map<String, String>> table = ClassPinTable.load("test-fixture");
        assertEquals(2, table.size());
        assertTrue(table.containsKey("5.2.03"));
        assertTrue(table.containsKey("5.3.02"));
        assertEquals(
                "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789",
                table.get("5.2.03").get("com/example/ClassA"));
        assertEquals(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                table.get("5.2.03").get("com/example/ClassB"));
        assertEquals(1, table.get("5.3.02").size());
    }

    @Test
    void missingResourceFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("nonexistent"));
    }

    @Test
    void invalidSha256FailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-bad-sha256"));
    }

    @Test
    void duplicateVersionKeyFailsClosed() {
        assertThrows(IllegalStateException.class, () -> ClassPinTable.load("test-duplicate-version"));
    }

    @Test
    void emptyTableIsPermitted() {
        final Map<String, Map<String, String>> table = ClassPinTable.load("test-empty");
        assertTrue(table.isEmpty());
    }

    @Test
    void singleSha256ReturnsPinnedValue() {
        assertEquals(
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                ClassPinTable.singleSha256("test-fixture", "5.2.03", "com/example/ClassB"));
    }

    @Test
    void singleSha256MissingVersionFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> ClassPinTable.singleSha256("test-fixture", "9.9.99", "com/example/ClassA"));
    }

    @Test
    void singleSha256MissingClassFailsClosed() {
        assertThrows(
                IllegalStateException.class,
                () -> ClassPinTable.singleSha256("test-fixture", "5.2.03", "com/example/NotPinned"));
    }

    @Test
    void returnedMapsAreImmutable() {
        final Map<String, Map<String, String>> table = ClassPinTable.load("test-fixture");
        assertThrows(UnsupportedOperationException.class, () -> table.put("x", Map.of()));
        assertThrows(
                UnsupportedOperationException.class, () -> table.get("5.2.03").put("x", "y"));
    }
}
