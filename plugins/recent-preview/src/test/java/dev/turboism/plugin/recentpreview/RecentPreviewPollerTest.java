package dev.turboism.plugin.recentpreview;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentfile.RecentFileSummary;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class RecentPreviewPollerTest {

    private static final RecentFileId ONE = new RecentFileId("one");
    private static final RecentFileId TWO = new RecentFileId("two");
    private static final Instant T0 = Instant.parse("2026-08-05T12:00:00Z");
    private static final Instant T1 = Instant.parse("2026-08-05T13:00:00Z");

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final RecentPreviewPoller poller = new RecentPreviewPoller(now::get);

    private static RecentFileSummary file(final RecentFileId id, final Instant modified) {
        return new RecentFileSummary(id, id.value() + ".cmo3", Optional.of(modified), Optional.empty());
    }

    private static RecentFileSummary fileWithoutModified(final RecentFileId id) {
        return new RecentFileSummary(id, id.value() + ".cmo3");
    }

    @Test
    void emitsTheCurrentDocumentOnTheFirstObservation() {
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T0))));
    }

    @Test
    void emitsNothingWhenNothingChanged() {
        poller.sample(List.of(file(ONE, T0)));
        assertEquals(Optional.empty(), poller.sample(List.of(file(ONE, T0))));
    }

    @Test
    void emitsOnCurrentDocumentChangeAfterOpenSemantics() {
        poller.sample(List.of(file(ONE, T0)));
        assertEquals(Optional.of(TWO), poller.sample(List.of(file(TWO, T0))));
    }

    @Test
    void emitsOnLastModifiedChangeSaveSemantics() {
        poller.sample(List.of(file(ONE, T0)));
        now.addAndGet(RecentPreviewPoller.MIN_CAPTURE_INTERVAL.toMillis() + 1);
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T1))));
    }

    @Test
    void ignoresEntriesWithoutLastModified() {
        assertEquals(Optional.empty(), poller.sample(List.of(fileWithoutModified(ONE))));
        assertEquals(Optional.empty(), poller.sample(List.of(fileWithoutModified(ONE))));
        // A later observation with a real timestamp still counts as a change.
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T0))));
    }

    @Test
    void clearsStateOnEmptyList() {
        poller.sample(List.of(file(ONE, T0)));
        assertEquals(Optional.empty(), poller.sample(List.of()));
        // The next non-empty observation is a fresh current document again.
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T1))));
    }

    @Test
    void spacesRepeatedEmissionsByTheMinInterval() {
        poller.sample(List.of(file(ONE, T0)));
        now.addAndGet(RecentPreviewPoller.MIN_CAPTURE_INTERVAL.toMillis() + 1);
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T1))));

        // Same document rewritten again too soon: suppressed and retried later.
        final Instant T2 = Instant.parse("2026-08-05T14:00:00Z");
        assertEquals(Optional.empty(), poller.sample(List.of(file(ONE, T2))),
            "a rewrite inside the min interval must be suppressed");
        now.addAndGet(RecentPreviewPoller.MIN_CAPTURE_INTERVAL.toMillis() + 1);
        assertEquals(Optional.of(ONE), poller.sample(List.of(file(ONE, T2))),
            "the suppressed emission must be retried once the interval has passed");
    }

    @Test
    void doesNotSuppressTheFirstEmissionEver() {
        assertTrue(poller.sample(List.of(file(ONE, T0))).isPresent());
    }
}
