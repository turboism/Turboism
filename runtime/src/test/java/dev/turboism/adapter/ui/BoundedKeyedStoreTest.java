package dev.turboism.adapter.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BoundedKeyedStoreTest {

    @Test
    void replacesByKeyAndEvictsOldestEntry() {
        BoundedKeyedStore<String, String> store = new BoundedKeyedStore<>(2);

        store.put("a", "first");
        store.put("a", "latest");
        store.put("b", "second");
        store.put("c", "third");

        assertEquals(2, store.size());
        assertEquals(java.util.List.of("second", "third"), store.snapshot());
    }
}
