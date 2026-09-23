package dev.turboism.preview;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginLifecycleLeaseTest {

    @Test
    void expiredLeaseFencesCommitAndCheckpoint() {
        final PluginLifecycleLease lease = new PluginLifecycleLease("p");
        assertTrue(lease.expire());
        assertTrue(lease.isExpired());
        assertFalse(lease.isCommitted());
        assertThrows(PluginLifecycleFenced.class, lease::checkpoint);
        assertThrows(PluginLifecycleFenced.class, () -> lease.commit(() -> "x"));
    }

    @Test
    void committedLeaseSurvivesExpire() {
        final PluginLifecycleLease lease = new PluginLifecycleLease("p");
        assertEquals("v", lease.commit(() -> "v"));
        assertTrue(lease.isCommitted());
        assertFalse(lease.expire(), "a won commit section must not be fenced");
        assertFalse(lease.isExpired());
    }

    @Test
    void doubleExpireIsNotCommitted() {
        // Regression for await() misclassification: a second expire() losing the race must not
        // look like a commit.
        final PluginLifecycleLease lease = new PluginLifecycleLease("p");
        assertTrue(lease.expire());
        assertFalse(lease.expire());
        assertFalse(lease.isCommitted());
        assertTrue(lease.isExpired());
    }

    @Test
    void checkpointPassesBeforeExpiry() {
        final PluginLifecycleLease lease = new PluginLifecycleLease("p");
        lease.checkpoint();
        assertFalse(lease.isExpired());
    }
}
