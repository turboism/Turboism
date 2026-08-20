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

    /**
     * Stores {@code value} under {@code key}, moving the key to the newest position so re-putting an
     * existing key refreshes its eviction order. Once the store exceeds its capacity the oldest
     * entries are evicted until it fits again.
     *
     * @param key non-null identity the value is filed under
     * @param value non-null value replacing any prior value for the key
     * @throws NullPointerException if either argument is null
     */
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

    /**
     * Removes the entry for {@code key} only when the currently stored value is the very same
     * instance as {@code expected} (reference identity, not equality). A newer value put under the
     * key is left in place, which makes this safe for a late unregister racing a re-registration.
     *
     * @param key key to conditionally remove; absent keys are ignored
     * @param expected the instance the caller believes is stored
     */
    public synchronized void removeIfSame(final K key, final V expected) {
        values.computeIfPresent(key, (ignored, current) -> current == expected ? null : current);
    }

    /**
     * @return an immutable copy of the stored values in insertion order, oldest first; later
     *     mutation of the store does not affect the returned list
     */
    public synchronized List<V> snapshot() {
        return List.copyOf(new ArrayList<>(values.values()));
    }

    /** @return the number of live entries, never above the capacity given at construction. */
    public synchronized int size() {
        return values.size();
    }

    /** Drops every entry, leaving the store empty but still usable. */
    public synchronized void clear() {
        values.clear();
    }

    /**
     * @return an immutable key-to-value copy of the current contents; iteration order of the
     *     returned map is unspecified, unlike {@link #snapshot()}
     */
    public synchronized Map<K, V> snapshotByKey() {
        return Map.copyOf(values);
    }
}
