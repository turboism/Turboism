package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.service.query.SelectionSummary;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/**
 * One committed selection observation in the runtime-shared baseline.
 *
 * <p>The baseline is shared between every plugin's selection-query path and the
 * session-scoped {@link SelectionObservationPublisher}, so commits must be safe
 * under concurrent readers. Each observation records the identity of the
 * {@link HostSnapshotSource} it was read from and that source's monotonic
 * invalidation token at read time: an equal or older same-source read can never
 * overwrite a newer committed baseline. Versions from different sources are not
 * comparable — they order nothing, and commits between them deduplicate purely
 * on the observed summary. Production composition shares one session source
 * instance across all query facades and the observer, so in practice every
 * commit is same-source.</p>
 *
 * @param source the snapshot source instance this observation was read through;
 *     compared by identity, never exposed to subscribers
 * @param sourceVersion the source's invalidation token at read time; monotonic
 *     within one source instance
 * @param summary the project-less observed selection identity
 *     ({@link SelectionSummaries#observedIdentity})
 */
public record SelectionObservation(
    Object source,
    long sourceVersion,
    SelectionSummary summary
) {
    public SelectionObservation {
        source = Objects.requireNonNull(source, "source");
        summary = Objects.requireNonNull(summary, "summary");
    }

    /**
     * Commits {@code next} to the shared baseline unless it is already reflected or stale.
     *
     * <p>The whole commit — evaluation, baseline update and {@code publisher}
     * invocation — runs under the baseline monitor, so committed transitions are
     * delivered in exactly the order they were committed; concurrent committers
     * cannot interleave an out-of-order publish between another commit's
     * compare-and-set and its publication. Rules, applied atomically:</p>
     * <ul>
     *   <li>an empty baseline accepts the first observation silently — the initial
     *       state establishes the baseline and is never published as a transition;</li>
     *   <li>an identical summary is a no-op (query and observer deduplicate), but a
     *       strictly newer same-source revision still advances the watermark so a
     *       later stale read cannot regress it;</li>
     *   <li>an equal or older same-source revision whose summary differs is
     *       discarded — a stale or inconsistent read must not regress a newer
     *       committed baseline into a spurious back-transition;</li>
     *   <li>otherwise the commit succeeds and {@code publisher} is invoked with
     *       (previous, current) summaries while the monitor is still held.</li>
     * </ul>
     *
     * <p>Cross-source commits never compare versions: they deduplicate on the
     * summary and, on a real transition, replace the baseline wholesale.</p>
     *
     * @return {@code true} when a transition was committed and published
     */
    public static boolean commit(
        final AtomicReference<SelectionObservation> baseline,
        final SelectionObservation next,
        final BiConsumer<SelectionSummary, SelectionSummary> publisher
    ) {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(next, "next");
        Objects.requireNonNull(publisher, "publisher");
        synchronized (baseline) {
            final SelectionObservation current = baseline.get();
            final boolean sameSource = current != null && current.source() == next.source();
            if (current != null && current.summary().equals(next.summary())) {
                if (sameSource && next.sourceVersion() > current.sourceVersion()) {
                    // Identical state at a strictly newer revision: still raise the
                    // watermark so a late older read cannot regress the baseline.
                    baseline.set(next);
                }
                return false;
            }
            if (sameSource && next.sourceVersion() <= current.sourceVersion()) {
                // Stale or equal-revision-conflicting read: discard, keep baseline.
                return false;
            }
            baseline.set(next);
            if (current == null) {
                return false;
            }
            publisher.accept(current.summary(), next.summary());
            return true;
        }
    }
}
