package dev.turboism.adapter.cubism.interpolation;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

final class NativeFormLookupBridgeTest {
    @Test
    void scopesRestoreParentsAndRemoveRootOnExceptionalExit() {
        NativeMeshInterpolationBridge.enabled(true);
        long initialScopes = NativeFormLookupBridge.cacheStatistics().activeScopes();
        assertFalse(NativeFormLookupBridge.hasScope());
        Object outer = NativeFormLookupBridge.enter();
        try {
            Object inner = NativeFormLookupBridge.enter();
            try {
                assertEquals(
                        initialScopes + 2,
                        NativeFormLookupBridge.cacheStatistics().activeScopes());
                assertTrue(NativeFormLookupBridge.hasScope());
                assertSame(NativeFormLookupBridge.UNSUPPORTED, NativeFormLookupBridge.find(null, new Object(), null));
                throw new IllegalStateException("native failure");
            } catch (IllegalStateException expected) {
                assertEquals("native failure", expected.getMessage());
            } finally {
                NativeFormLookupBridge.exit(inner);
            }
            assertTrue(NativeFormLookupBridge.hasScope());
            assertEquals(
                    initialScopes + 1, NativeFormLookupBridge.cacheStatistics().activeScopes());
            NativeMeshInterpolationBridge.enabled(false);
            assertNull(NativeFormLookupBridge.enter());
            NativeFormLookupBridge.exit(null);
            assertTrue(NativeFormLookupBridge.hasScope());
        } finally {
            NativeFormLookupBridge.exit(outer);
            NativeMeshInterpolationBridge.enabled(false);
        }
        assertFalse(NativeFormLookupBridge.hasScope());
        assertEquals(initialScopes, NativeFormLookupBridge.cacheStatistics().activeScopes());
    }

    @Test
    void lookupsOutsideRebuildUseOfficialFallback() {
        NativeMeshInterpolationBridge.enabled(true);
        try {
            assertSame(NativeFormLookupBridge.UNSUPPORTED, NativeFormLookupBridge.find(null, null, null));
            assertFalse(NativeFormLookupBridge.hasScope());
        } finally {
            NativeMeshInterpolationBridge.enabled(false);
        }
    }
}
