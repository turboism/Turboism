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

    synchronized V get(final K key) {
        expunge();
        return entries.get(new LookupKey(key));
    }

    synchronized V getOrCompute(final K key, final java.util.function.Supplier<V> factory) {
        expunge();
        final LookupKey lookup = new LookupKey(key);
        V value = entries.get(lookup);
        if (value == null) {
            value = factory.get();
            entries.put(new IdentityKey<>(key, queue), value);
        }
        return value;
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

        private final Object referent;
        private final int hash;

        private LookupKey(final Object referent) {
            this.referent = referent;
            this.hash = System.identityHashCode(referent);
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
