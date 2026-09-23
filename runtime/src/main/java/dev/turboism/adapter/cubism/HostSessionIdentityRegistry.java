package dev.turboism.adapter.cubism;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Session-scoped opaque identities without strongly retaining host objects. */
final class HostSessionIdentityRegistry {

    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final Map<Entry, String> entries = new HashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    synchronized String idFor(final Object hostObject, final String prefix) {
        Objects.requireNonNull(hostObject, "hostObject");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        removeCollectedEntries();
        final Entry probe = new Entry(hostObject, null, null);
        final String existing = entries.get(probe);
        if (existing != null) {
            return existing;
        }
        final String id = prefix + "-session-" + Long.toUnsignedString(sequence.incrementAndGet(), 36);
        entries.put(new Entry(hostObject, collected, id), id);
        return id;
    }

    private void removeCollectedEntries() {
        Entry collectedEntry;
        while ((collectedEntry = (Entry) collected.poll()) != null) {
            entries.remove(collectedEntry);
        }
    }

    /**
     * Identity-keyed weak map key. The hash is the referent's identity hash captured at
     * construction, so it stays stable after the referent is collected and an enqueued entry can
     * still be removed. Equality is referent identity and is never true once either referent is
     * gone, so a dead entry can never collide with a live object.
     */
    private static final class Entry extends WeakReference<Object> {
        private final int referentHash;

        private Entry(
            final Object referent,
            final ReferenceQueue<Object> queue,
            final String ignored
        ) {
            super(referent, queue);
            this.referentHash = System.identityHashCode(referent);
        }

        @Override
        public int hashCode() {
            return referentHash;
        }

        @Override
        public boolean equals(final Object other) {
            if (!(other instanceof Entry entry)) {
                return false;
            }
            final Object referent = get();
            return referent != null && referent == entry.get();
        }
    }
}
