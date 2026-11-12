package dev.turboism.recentpreview;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class RecentPreviewCacheRegistryTest {

    private static final RecentFileId ID = new RecentFileId("recent-1");

    @Test
    void routesDeletionToTheRegisteredProvider() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        final AtomicInteger calls = new AtomicInteger();
        registry.provide("provider", id -> {
            assertEquals(ID, id);
            calls.incrementAndGet();
            return CompletableFuture.completedStage(true);
        });

        assertTrue(registry.delete(ID).toCompletableFuture().join());
        assertEquals(1, calls.get());
    }

    @Test
    void reportsFalseWhenNoProviderIsRegistered() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        assertFalse(registry.delete(ID).toCompletableFuture().join());
    }

    @Test
    void closingTheRegistrationEndsTheProvider() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        final dev.turboism.sdk.plugin.Registration registration =
                registry.provide("provider", id -> CompletableFuture.completedStage(true));
        registration.close();

        assertFalse(registry.delete(ID).toCompletableFuture().join());
    }

    @Test
    void aSecondPluginCannotTakeOverTheProvider() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        registry.provide("first", id -> CompletableFuture.completedStage(true));

        assertThrows(
                IllegalStateException.class,
                () -> registry.provide("second", id -> CompletableFuture.completedStage(true)));
    }

    @Test
    void theSamePluginMayReplaceItsOwnProvider() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        registry.provide("provider", id -> CompletableFuture.completedStage(false));
        registry.provide("provider", id -> CompletableFuture.completedStage(true));

        assertTrue(registry.delete(ID).toCompletableFuture().join());
    }

    @Test
    void aClosedRegistryRefusesRegistrationAndReportsNoDeletion() {
        final RecentPreviewCacheRegistry registry = new RecentPreviewCacheRegistry();
        registry.close();

        assertThrows(
                IllegalStateException.class,
                () -> registry.provide("provider", id -> CompletableFuture.completedStage(true)));
        assertFalse(registry.delete(ID).toCompletableFuture().join());
    }
}
