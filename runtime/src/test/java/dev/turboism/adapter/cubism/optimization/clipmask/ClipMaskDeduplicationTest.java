package dev.turboism.adapter.cubism.optimization.clipmask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

final class ClipMaskDeduplicationTest {

    @Test
    void digestDistinguishesIdentityAndOrder() {
        final Object entity1 = new Object();
        final Object entity2 = new Object();
        final Object entity3 = new Object();

        final long d1 = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity2));
        final long d2 = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity2));
        final long dReversed = ClipMaskDeduplicationBridge.digestOf(List.of(entity2, entity1));
        final long dDifferent = ClipMaskDeduplicationBridge.digestOf(List.of(entity1, entity3));
        final long dSingle = ClipMaskDeduplicationBridge.digestOf(List.of(entity1));

        assertEquals(d1, d2, "Identical entity lists must have identical digests");
        assertTrue(d1 != dReversed, "Order reversal must produce different digest");
        assertTrue(d1 != dDifferent, "Different entities must produce different digest");
        assertTrue(d1 != dSingle, "Different length must produce different digest");
    }

    @Test
    void targetOwnerResolvesBothMajorVersionFamilies() {
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_53, ClipMaskDeduplicationTarget.renderSystemOwner("5.3.00"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_53, ClipMaskDeduplicationTarget.renderSystemOwner("5.3.04"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_50, ClipMaskDeduplicationTarget.renderSystemOwner("5.0.01"));
        assertEquals(
                ClipMaskDeduplicationTarget.RENDER_SYSTEM_50, ClipMaskDeduplicationTarget.renderSystemOwner("5.2.03"));
    }
}
