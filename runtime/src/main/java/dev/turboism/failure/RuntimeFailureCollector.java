package dev.turboism.failure;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Thread-safe bounded runtime-session collector with stable aggregation keys. */
public final class RuntimeFailureCollector implements RuntimeFailureSink {

    public static final int ENTRY_LIMIT = 255;
    private static final RuntimeFailure OVERFLOW = new RuntimeFailure(
        "FAILURE_COLLECTOR_ENTRY_LIMIT",
        "ERROR",
        "collection",
        null,
        null,
        null,
        "Runtime failure collector entry limit was reached.",
        null,
        1
    );

    private final Object lock = new Object();
    private final EnumMap<RuntimeFailureDomain, LinkedHashMap<RuntimeFailure, Long>> entries =
        new EnumMap<>(RuntimeFailureDomain.class);

    public RuntimeFailureCollector() {
        for (RuntimeFailureDomain domain : RuntimeFailureDomain.values()) {
            entries.put(domain, new LinkedHashMap<>());
        }
    }

    @Override
    public void record(
        final RuntimeFailureDomain domain,
        final RuntimeFailure failure
    ) {
        final RuntimeFailureDomain target = Objects.requireNonNull(domain, "domain");
        final RuntimeFailure key = Objects.requireNonNull(failure, "failure").key();
        synchronized (lock) {
            final Map<RuntimeFailure, Long> domainEntries = entries.get(target);
            final Long existing = domainEntries.get(key);
            if (existing != null) {
                domainEntries.put(key, saturatingAdd(existing, failure.count()));
                return;
            }
            if (domainEntries.size() < ENTRY_LIMIT) {
                domainEntries.put(key, failure.count());
                return;
            }
            final Long overflowCount = domainEntries.get(OVERFLOW);
            if (overflowCount != null) {
                domainEntries.put(OVERFLOW, saturatingAdd(overflowCount, failure.count()));
                return;
            }
            final RuntimeFailure evicted = domainEntries.keySet().stream()
                .max(RuntimeFailure.KEY_ORDER)
                .orElseThrow();
            final long evictedCount = Objects.requireNonNull(domainEntries.remove(evicted));
            domainEntries.put(OVERFLOW, saturatingAdd(evictedCount, failure.count()));
        }
    }

    /**
     * Takes a consistent, detached view of everything collected so far. Held under the collector's
     * lock, so no domain can be observed mid-update, and the collector remains usable afterwards —
     * this reads rather than drains.
     *
     * @return the accumulated failures per domain, each list sorted by the stable aggregation key
     *     and carrying its folded occurrence count
     */
    public RuntimeFailureSnapshot snapshot() {
        synchronized (lock) {
            return new RuntimeFailureSnapshot(
                snapshot(entries.get(RuntimeFailureDomain.TASK)),
                snapshot(entries.get(RuntimeFailureDomain.STORAGE)),
                snapshot(entries.get(RuntimeFailureDomain.CONFIG)),
                snapshot(entries.get(RuntimeFailureDomain.EVENT))
            );
        }
    }

    private static List<RuntimeFailure> snapshot(
        final Map<RuntimeFailure, Long> domainEntries
    ) {
        final List<RuntimeFailure> values = new ArrayList<>(domainEntries.size());
        domainEntries.forEach((key, count) -> values.add(key.withCount(count)));
        values.sort(RuntimeFailure.KEY_ORDER);
        return List.copyOf(values);
    }

    private static long saturatingAdd(final long left, final long right) {
        if (right > Long.MAX_VALUE - left) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }
}
