package dev.turboism.adapter.cubism.exportperf;

import java.io.ByteArrayOutputStream;
import java.lang.ref.Reference;
import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Replaces quadratic list scans during MOC3 assembly with amortized O(1) index/intern lookups.
 *
 * <p>Uses object-identity weak keys instead of {@code List.hashCode}/{@code equals} so mutating
 * append-only collections never invalidates the cache or incurs O(N) key hashing per lookup.</p>
 */
public final class MocExportIndexAccelerator {

    private static final int MIN_INDEXED_SIZE = 16;
    private static final int INITIAL_INDEX_CAPACITY = 64;
    private static final int DEFAULT_MOC_BUFFER_BYTES = 4 * 1024 * 1024;

    private static final ThreadLocal<IdentityWeakCache<List<?>, IndexedList>> LIST_INDEX_CACHE =
            ThreadLocal.withInitial(IdentityWeakCache::new);
    private static final ThreadLocal<IdentityWeakCache<Collection<?>, InternedCollection>> INTERN_CACHE =
            ThreadLocal.withInitial(IdentityWeakCache::new);
    private static final ThreadLocal<Integer> BUFFER_CAPACITY_DEPTH = ThreadLocal.withInitial(() -> 0);

    private MocExportIndexAccelerator() {}

    /**
     * Marks entry into a MOC3 serialization section so {@code util.c.b} pre-sizes its stream.
     */
    public static void enterMocBufferScope() {
        BUFFER_CAPACITY_DEPTH.set(BUFFER_CAPACITY_DEPTH.get() + 1);
    }

    /**
     * Releases the active MOC3 buffer sizing scope and clears per-thread export lookup caches.
     */
    public static void exitMocBufferScope() {
        final int next = Math.max(0, BUFFER_CAPACITY_DEPTH.get() - 1);
        if (next == 0) {
            BUFFER_CAPACITY_DEPTH.remove();
            LIST_INDEX_CACHE.remove();
            INTERN_CACHE.remove();
        } else {
            BUFFER_CAPACITY_DEPTH.set(next);
        }
    }

    /**
     * Returns the initial capacity for {@code com.live2d.util.c.b}'s backing stream.
     *
     * @return pre-sized capacity when building MOC3 payloads, zero for default sizing outside MOC3
     */
    public static int currentBufferInitialCapacity() {
        return BUFFER_CAPACITY_DEPTH.get() > 0 ? DEFAULT_MOC_BUFFER_BYTES : 0;
    }

    /**
     * Constructs a {@link ByteArrayOutputStream} pre-sized for MOC3 export when active on-thread.
     *
     * @return pre-sized output stream during MOC3 build or default-sized stream otherwise
     */
    public static ByteArrayOutputStream newByteArrayOutputStream() {
        final int cap = currentBufferInitialCapacity();
        return cap > 0 ? new ByteArrayOutputStream(cap) : new ByteArrayOutputStream();
    }

    /**
     * Returns the index of the first occurrence of {@code target} in {@code list}, or {@code -1}.
     *
     * @param list target list to search
     * @param target element to locate by {@code Object.equals}
     * @return first matching index or {@code -1} if absent
     */
    public static int indexOf(final List<?> list, final Object target) {
        if (list == null) {
            return -1;
        }
        final int size = list.size();
        if (size < MIN_INDEXED_SIZE) {
            return list.indexOf(target);
        }
        final IdentityWeakCache<List<?>, IndexedList> cache = LIST_INDEX_CACHE.get();
        IndexedList indexed = cache.get(list);
        if (indexed == null || indexed.indexedCount > size) {
            indexed = new IndexedList(Math.max(INITIAL_INDEX_CAPACITY, size));
            cache.put(list, indexed);
        }
        for (int i = indexed.indexedCount; i < size; i++) {
            indexed.map.putIfAbsent(list.get(i), i);
        }
        indexed.indexedCount = size;
        final Integer found = indexed.map.get(target);
        return found != null ? found : -1;
    }

    /**
     * Returns an equal element already stored in {@code collection}, or appends {@code candidate}.
     *
     * @param <T> element type stored in {@code collection}
     * @param collection backing collection preserving insertion order
     * @param candidate candidate instance to intern
     * @return existing canonical element or newly added {@code candidate}
     */
    @SuppressWarnings("unchecked")
    public static <T> T intern(final Collection<T> collection, final T candidate) {
        if (collection == null) {
            throw new NullPointerException("collection");
        }
        final int size = collection.size();
        if (size < MIN_INDEXED_SIZE && !(collection instanceof List<?>)) {
            for (final T existing : collection) {
                if (existing == null ? candidate == null : existing.equals(candidate)) {
                    return existing;
                }
            }
            collection.add(candidate);
            return candidate;
        }
        if (size < MIN_INDEXED_SIZE) {
            final List<T> asList = (List<T>) collection;
            final int idx = asList.indexOf(candidate);
            if (idx >= 0) {
                return asList.get(idx);
            }
            collection.add(candidate);
            return candidate;
        }
        final IdentityWeakCache<Collection<?>, InternedCollection> cache = INTERN_CACHE.get();
        InternedCollection interned = cache.get(collection);
        if (interned == null || interned.indexedCount != size) {
            if (interned == null || interned.indexedCount > size) {
                interned = new InternedCollection(Math.max(INITIAL_INDEX_CAPACITY, size));
                cache.put(collection, interned);
                for (final Object existing : collection) {
                    interned.map.putIfAbsent(existing, existing);
                }
            } else if (collection instanceof List<?> asList) {
                for (int i = interned.indexedCount; i < size; i++) {
                    final Object existing = asList.get(i);
                    interned.map.putIfAbsent(existing, existing);
                }
            } else {
                interned.map.clear();
                for (final Object existing : collection) {
                    interned.map.putIfAbsent(existing, existing);
                }
            }
            interned.indexedCount = size;
        }
        final Object existing = interned.map.get(candidate);
        if (existing != null || (candidate == null && interned.map.containsKey(null))) {
            return (T) existing;
        }
        collection.add(candidate);
        interned.map.putIfAbsent(candidate, candidate);
        interned.indexedCount = collection.size();
        return candidate;
    }

    private static final class IdentityWeakCache<K, V> {
        private final Map<IdentityKey, V> entries = new HashMap<>();
        private final ReferenceQueue<Object> queue = new ReferenceQueue<>();
        private final ProbeKey probe = new ProbeKey();
        private WeakReference<K> lastKeyRef;
        private V lastValue;

        V get(final K key) {
            if (lastKeyRef != null && lastKeyRef.get() == key) {
                return lastValue;
            }
            probe.target = key;
            probe.hash = System.identityHashCode(key);
            final V found = entries.get(probe);
            probe.target = null;
            if (found != null) {
                lastKeyRef = new WeakReference<>(key);
                lastValue = found;
            }
            return found;
        }

        void put(final K key, final V value) {
            expungeStale();
            final WeakIdentityKey weakKey = new WeakIdentityKey(key, queue);
            entries.put(weakKey, value);
            lastKeyRef = new WeakReference<>(key);
            lastValue = value;
        }

        private void expungeStale() {
            Reference<?> ref;
            while ((ref = queue.poll()) != null) {
                entries.remove(ref);
            }
        }
    }

    private interface IdentityKey {
        Object referent();
    }

    private static final class ProbeKey implements IdentityKey {
        private Object target;
        private int hash;

        @Override
        public Object referent() {
            return target;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object obj) {
            return obj instanceof IdentityKey other && other.referent() == target;
        }
    }

    private static final class WeakIdentityKey extends WeakReference<Object> implements IdentityKey {
        private final int hash;

        WeakIdentityKey(final Object referent, final ReferenceQueue<Object> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
        }

        @Override
        public Object referent() {
            return get();
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object obj) {
            if (this == obj) {
                return true;
            }
            if (!(obj instanceof IdentityKey other)) {
                return false;
            }
            final Object mine = get();
            return mine != null && mine == other.referent();
        }
    }

    private static final class IndexedList {
        private final Map<Object, Integer> map;
        private int indexedCount;

        IndexedList(final int expectedSize) {
            this.map = new HashMap<>(expectedSize * 4 / 3 + 1);
        }
    }

    private static final class InternedCollection {
        private final Map<Object, Object> map;
        private int indexedCount;

        InternedCollection(final int expectedSize) {
            this.map = new HashMap<>(expectedSize * 4 / 3 + 1);
        }
    }
}
