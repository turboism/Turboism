package dev.turboism.adapter.cubism.optimization.memory;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.Objects;

/**
 * Minimal identity-keyed weak map that never invokes {@code equals} or {@code hashCode} on keys.
 *
 * <p>Host graph and UI objects (for example {@code GEntity}) override equality semantics around
 * domain GUIDs; keying by {@link System#identityHashCode(Object)} and reference identity prevents
 * collisions and allows cleared entries to drain lazily on access.</p>
 */
final class WeakIdentityMap<K, V> {

    private static final int DEFAULT_CAPACITY = 64;
    private static final float LOAD_THRESHOLD = 0.75f;

    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private Entry<K, V>[] buckets;
    private int size;

    @SuppressWarnings("unchecked")
    WeakIdentityMap() {
        buckets = (Entry<K, V>[]) new Entry<?, ?>[DEFAULT_CAPACITY];
    }

    /** Returns the associated value for exact instance {@code key}, or {@code null}. */
    synchronized V get(final K key) {
        if (key == null) {
            return null;
        }
        expungeStale();
        final int hash = System.identityHashCode(key);
        final int index = bucketIndex(hash, buckets.length);
        for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
            if (entry.identityHash == hash && entry.get() == key) {
                return entry.value;
            }
        }
        return null;
    }

    /** Associates {@code value} with exact instance {@code key}, returning the prior value. */
    synchronized V put(final K key, final V value) {
        Objects.requireNonNull(key, "key");
        expungeStale();
        final int hash = System.identityHashCode(key);
        final int index = bucketIndex(hash, buckets.length);
        for (Entry<K, V> entry = buckets[index]; entry != null; entry = entry.next) {
            if (entry.identityHash == hash && entry.get() == key) {
                final V previous = entry.value;
                entry.value = value;
                return previous;
            }
        }
        buckets[index] = new Entry<>(key, value, hash, queue, buckets[index]);
        size++;
        if (size > buckets.length * LOAD_THRESHOLD) {
            rehash();
        }
        return null;
    }

    /** Removes exact instance {@code key} and returns its value, or {@code null}. */
    synchronized V remove(final K key) {
        if (key == null) {
            return null;
        }
        expungeStale();
        final int hash = System.identityHashCode(key);
        final int index = bucketIndex(hash, buckets.length);
        Entry<K, V> prev = null;
        Entry<K, V> entry = buckets[index];
        while (entry != null) {
            final Entry<K, V> next = entry.next;
            if (entry.identityHash == hash && entry.get() == key) {
                final V removed = entry.value;
                unlink(index, prev, entry, next);
                return removed;
            }
            prev = entry;
            entry = next;
        }
        return null;
    }

    /** Discards all entries without releasing weak reference objects eagerly. */
    synchronized void clear() {
        for (int i = 0; i < buckets.length; i++) {
            buckets[i] = null;
        }
        size = 0;
        while (queue.poll() != null) {
            // Drain queue entries so future writes do not scan cleared slots.
        }
    }

    /** Appends live keys and values into caller-owned lists without scanning cleared entries. */
    synchronized void collectEntries(final List<K> keysOut, final List<V> valuesOut) {
        expungeStale();
        for (Entry<K, V> entry : buckets) {
            while (entry != null) {
                final K key = entry.get();
                if (key != null) {
                    keysOut.add(key);
                    valuesOut.add(entry.value);
                }
                entry = entry.next;
            }
        }
    }

    /** Removes {@code key} only when its mapped value matches identity {@code expectedValue}. */
    synchronized boolean removeIfSame(final K key, final V expectedValue) {
        if (key == null) {
            return false;
        }
        expungeStale();
        final int hash = System.identityHashCode(key);
        final int index = bucketIndex(hash, buckets.length);
        Entry<K, V> prev = null;
        Entry<K, V> entry = buckets[index];
        while (entry != null) {
            final Entry<K, V> next = entry.next;
            if (entry.identityHash == hash && entry.get() == key) {
                if (entry.value != expectedValue) {
                    return false;
                }
                unlink(index, prev, entry, next);
                return true;
            }
            prev = entry;
            entry = next;
        }
        return false;
    }

    private void unlink(final int index, final Entry<K, V> prev, final Entry<K, V> entry, final Entry<K, V> next) {
        if (prev == null) {
            buckets[index] = next;
        } else {
            prev.next = next;
        }
        entry.next = null;
        entry.value = null;
        size--;
    }

    @SuppressWarnings("unchecked")
    private void expungeStale() {
        for (Entry<K, V> stale; (stale = (Entry<K, V>) queue.poll()) != null; ) {
            final int index = bucketIndex(stale.identityHash, buckets.length);
            Entry<K, V> prev = null;
            Entry<K, V> entry = buckets[index];
            while (entry != null) {
                final Entry<K, V> next = entry.next;
                if (entry == stale) {
                    unlink(index, prev, entry, next);
                    break;
                }
                prev = entry;
                entry = next;
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void rehash() {
        final int newCap = buckets.length << 1;
        final Entry<K, V>[] nextBuckets = (Entry<K, V>[]) new Entry<?, ?>[newCap];
        for (Entry<K, V> head : buckets) {
            Entry<K, V> entry = head;
            while (entry != null) {
                final Entry<K, V> next = entry.next;
                if (entry.get() != null) {
                    final int index = bucketIndex(entry.identityHash, newCap);
                    entry.next = nextBuckets[index];
                    nextBuckets[index] = entry;
                } else {
                    entry.value = null;
                    size--;
                }
                entry = next;
            }
        }
        buckets = nextBuckets;
    }

    private static int bucketIndex(final int identityHash, final int length) {
        return (identityHash ^ (identityHash >>> 16)) & (length - 1);
    }

    private static final class Entry<K, V> extends WeakReference<K> {
        private final int identityHash;
        private V value;
        private Entry<K, V> next;

        private Entry(
                final K referent,
                final V value,
                final int identityHash,
                final ReferenceQueue<K> queue,
                final Entry<K, V> next) {
            super(referent, queue);
            this.value = value;
            this.identityHash = identityHash;
            this.next = next;
        }
    }
}
