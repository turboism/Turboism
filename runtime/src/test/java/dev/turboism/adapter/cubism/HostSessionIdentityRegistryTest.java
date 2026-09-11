package dev.turboism.adapter.cubism;

import org.junit.jupiter.api.Test;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostSessionIdentityRegistryTest {

    @Test
    void sameObjectGetsOneStableIdAndDistinctObjectsGetDistinctIds() {
        final HostSessionIdentityRegistry registry = new HostSessionIdentityRegistry();
        final Object first = new Object();
        final Object second = new Object();

        final String firstId = registry.idFor(first, "document");

        assertEquals(firstId, registry.idFor(first, "document"),
            "the same host object must keep its assigned id");
        assertNotEquals(firstId, registry.idFor(second, "document"),
            "a different host object must get a different id");
        assertEquals(firstId, registry.idFor(first, "document"),
            "later lookups must still return the first id");
        assertTrue(firstId.startsWith("document-session-"));
    }

    @Test
    void thePrefixOnlyLabelsNewIdentitiesAndLookupsAreByIdentity() {
        final HostSessionIdentityRegistry registry = new HostSessionIdentityRegistry();
        final Object shared = new Object();

        final String first = registry.idFor(shared, "document");
        final String second = registry.idFor(shared, "content");

        assertEquals(first, second,
            "identity lookup returns the first-registered id regardless of prefix");
    }

    @Test
    void collectedObjectsAreRemovedAndTheirIdsAreNeverReused() throws Exception {
        final HostSessionIdentityRegistry registry = new HostSessionIdentityRegistry();
        final List<WeakReference<Object>> references = new ArrayList<>();
        final List<String> issuedIds = new ArrayList<>();
        for (int i = 0; i < 32; i++) {
            final Object temporary = new Object();
            references.add(new WeakReference<>(temporary));
            issuedIds.add(registry.idFor(temporary, "document"));
        }
        final Object survivor = new Object();
        final String survivorId = registry.idFor(survivor, "document");

        assertCollected(references);

        for (int i = 0; i < 32; i++) {
            final Object fresh = new Object();
            references.add(new WeakReference<>(fresh));
            assertTrue(!issuedIds.contains(registry.idFor(fresh, "document")),
                "a collected referent's id must never be reassigned");
        }
        assertEquals(survivorId, registry.idFor(survivor, "document"),
            "a live object's id survives any collection pass");
    }

    private static void assertCollected(final List<WeakReference<Object>> references) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (references.stream().anyMatch(reference -> !reference.refersTo(null))
            && System.nanoTime() < deadline) {
            System.gc();
            Thread.sleep(10);
        }
        assertTrue(references.stream().allMatch(reference -> reference.refersTo(null)),
            "the registry must not retain collected host objects");
    }
}
