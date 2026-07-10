package dev.turboism.adapter.cubism;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/** Session-scoped opaque identities without strongly retaining host objects. */
final class HostSessionIdentityRegistry {

    private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
    private final List<Entry> entries = new ArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    synchronized String idFor(final Object hostObject, final String prefix) {
        Objects.requireNonNull(hostObject, "hostObject");
        Objects.requireNonNull(prefix, "prefix");
        if (prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must not be blank");
        }
        removeCollectedEntries();
        for (Entry entry : entries) {
            if (entry.get() == hostObject) {
                return entry.id;
            }
        }
        final String id = prefix + "-session-" + Long.toUnsignedString(sequence.incrementAndGet(), 36);
        entries.add(new Entry(hostObject, collected, id));
        return id;
    }

    private void removeCollectedEntries() {
        Entry collectedEntry;
        while ((collectedEntry = (Entry) collected.poll()) != null) {
            entries.remove(collectedEntry);
        }
        final Iterator<Entry> iterator = entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().get() == null) {
                iterator.remove();
            }
        }
    }

    private static final class Entry extends WeakReference<Object> {
        private final String id;

        private Entry(
            final Object referent,
            final ReferenceQueue<Object> queue,
            final String id
        ) {
            super(referent, queue);
            this.id = id;
        }
    }
}
