package dev.turboism.adapter.cubism.optimization;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ClassPinTableTest {

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
}
