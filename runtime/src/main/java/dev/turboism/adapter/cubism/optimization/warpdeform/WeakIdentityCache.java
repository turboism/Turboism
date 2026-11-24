package dev.turboism.adapter.cubism.optimization.warpdeform;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;

/**
 * Identity-keyed weak map that evicts overlay snapshots when host entities become unreachable.
 *
 * @param <K> host entity or buffer key type compared strictly by reference identity
 * @param <V> cached mutable snapshot value type
 */
final class WeakIdentityCache<K, V> {

    private final ReferenceQueue<K> queue = new ReferenceQueue<>();
    private final Map<Object, V> entries = new HashMap<>();
    private final LookupKey lookupScratch = new LookupKey();

    synchronized V get(final K key) {
        expunge();
        lookupScratch.set(key);
        try {
            return entries.get(lookupScratch);
        } finally {
            lookupScratch.clear();
        }
    }

    synchronized V getOrCompute(final K key, final java.util.function.Supplier<V> factory) {
        expunge();
        lookupScratch.set(key);
        final V existing;
        try {
            existing = entries.get(lookupScratch);
        } finally {
            lookupScratch.clear();
        }
        if (existing != null) {
            return existing;
        }
        final V created = factory.get();
        entries.put(new IdentityKey<>(key, queue), created);
        return created;
    }

    synchronized void clear() {
        entries.clear();
        while (queue.poll() != null) {
            // Drain stale references cleanly.
        }
    }

    private void expunge() {
        Object ref;
        while ((ref = queue.poll()) != null) {
            entries.remove(ref);
        }
    }

    private static final class IdentityKey<K> extends WeakReference<K> {

        private final int hash;

        private IdentityKey(final K referent, final ReferenceQueue<K> queue) {
            super(referent, queue);
            this.hash = System.identityHashCode(referent);
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
            final Object self = get();
            if (self == null) {
                return false;
            }
            if (obj instanceof IdentityKey<?> other) {
                return self == other.get();
            }
            if (obj instanceof LookupKey lookup) {
                return self == lookup.referent;
            }
            return false;
        }
    }

    private static final class LookupKey {

        private Object referent;
        private int hash;

        private void set(final Object referent) {
            this.referent = referent;
            this.hash = System.identityHashCode(referent);
        }

        private void clear() {
            this.referent = null;
            this.hash = 0;
        }

        @Override
        public int hashCode() {
            return hash;
        }

        @Override
        public boolean equals(final Object obj) {
            if (obj instanceof IdentityKey<?> entry) {
                return entry.get() == referent;
            }
            return obj instanceof LookupKey other && other.referent == referent;
        }
    }
}
