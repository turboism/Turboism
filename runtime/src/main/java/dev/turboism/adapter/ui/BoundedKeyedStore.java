package dev.turboism.adapter.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe bounded store that replaces values by key and evicts the oldest entry. */
public final class BoundedKeyedStore<K, V> {

    private final int capacity;
    private final LinkedHashMap<K, V> values = new LinkedHashMap<>();

    public BoundedKeyedStore(final int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
    }

    public synchronized void put(final K key, final V value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        values.remove(key);
        values.put(key, value);
        while (values.size() > capacity) {
            final K oldest = values.keySet().iterator().next();
            values.remove(oldest);
        }
    }

    public synchronized void removeIfSame(final K key, final V expected) {
        values.computeIfPresent(key, (ignored, current) -> current == expected ? null : current);
    }

    public synchronized List<V> snapshot() {
        return List.copyOf(new ArrayList<>(values.values()));
    }

    public synchronized int size() {
        return values.size();
    }

    public synchronized void clear() {
        values.clear();
    }

    public synchronized Map<K, V> snapshotByKey() {
        return Map.copyOf(values);
    }
}
