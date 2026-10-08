package dev.turboism.adapter.cubism.mesh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class NativeMeshEdgeTableTest {
    private record Pair(int first, int second) {}

    @Test
    void preservesLiteralSignedEndpointsAndFirstPhysicalSlot() {
        try (NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(16)) {
            assertNotNull(table);
            List<Pair> pairs = List.of(
                    new Pair(0, 0),
                    new Pair(-1, -1),
                    new Pair(0, -1),
                    new Pair(-1, 0),
                    new Pair(Integer.MIN_VALUE, Integer.MAX_VALUE),
                    new Pair(Integer.MAX_VALUE, Integer.MIN_VALUE),
                    new Pair(2, 7),
                    new Pair(7, 2));
            for (int i = 0; i < pairs.size(); i++) {
                Pair pair = pairs.get(i);
                assertTrue(table.putFirst(pair.first(), pair.second(), i + 20));
                assertTrue(table.putFirst(pair.first(), pair.second(), i + 40));
                assertEquals(i + 20, table.find(pair.first(), pair.second()));
                assertTrue(table.putFirst(pair.first(), pair.second(), i));
                assertEquals(i, table.find(pair.first(), pair.second()));
            }
            assertEquals(NativeMeshEdgeTable.ABSENT, table.find(2, 8));
        }
    }

    @Test
    void randomizedFirstSlotOracleCoversDenseAndSparsePairs() {
        try (NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) {
            assertNotNull(table);
            HashMap<Pair, Integer> expected = new HashMap<>();
            Random random = new Random(82005303L);
            for (int i = 0; i < 14_000; i++) {
                Pair pair = i < 7000 ? new Pair(i, i + 1) : new Pair(random.nextInt(), random.nextInt());
                int slot = random.nextInt(Integer.MAX_VALUE);
                expected.merge(pair, slot, Math::min);
                assertTrue(table.putFirst(pair.first(), pair.second(), slot));
                assertTrue(table.putFirst(pair.first(), pair.second(), Integer.MAX_VALUE));
            }
            for (var row : expected.entrySet())
                assertEquals(
                        row.getValue().intValue(),
                        table.find(row.getKey().first(), row.getKey().second()));
            for (int i = 0; i < 4000; i++) {
                Pair pair = new Pair(random.nextInt(), random.nextInt());
                assertEquals(
                        expected.getOrDefault(pair, NativeMeshEdgeTable.ABSENT).intValue(),
                        table.find(pair.first(), pair.second()));
            }
        }
    }

    @Test
    void growingFromEmptyPreservesSignedPairsAndMinimumPhysicalSlots() {
        int initial = NativeMeshEdgeTable.reservedBytes();
        try (NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES, 0)) {
            assertNotNull(table);
            HashMap<Pair, Integer> expected = new HashMap<>();
            Random random = new Random(92005303L);
            for (int i = 0; i < 14_000; i++) {
                Pair pair = new Pair(i - 7000, random.nextInt());
                int slot = random.nextInt(Integer.MAX_VALUE);
                expected.merge(pair, slot, Math::min);
                assertTrue(table.putFirst(pair.first(), pair.second(), slot));
                if (i % 17 == 0) {
                    expected.merge(pair, i, Math::min);
                    assertTrue(table.putFirst(pair.first(), pair.second(), i));
                }
                if (i % 1000 == 0)
                    for (var row : expected.entrySet())
                        assertEquals(
                                row.getValue().intValue(),
                                table.find(row.getKey().first(), row.getKey().second()));
            }
            for (var row : expected.entrySet())
                assertEquals(
                        row.getValue().intValue(),
                        table.find(row.getKey().first(), row.getKey().second()));
        }
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
    }

    @Test
    void currentEntriesDoNotReserveWorstCaseBuffersBeforeTheyAreNeeded() {
        int initial = NativeMeshEdgeTable.reservedBytes();
        int worstCase;
        try (NativeMeshEdgeTable full = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) {
            assertNotNull(full);
            worstCase = NativeMeshEdgeTable.reservedBytes() - initial;
        }
        try (NativeMeshEdgeTable small = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES, 16)) {
            assertNotNull(small);
            assertTrue((NativeMeshEdgeTable.reservedBytes() - initial) * 10 < worstCase);
            for (int i = 0; i < 16; i++) assertTrue(small.putFirst(i, -i, i));
            for (int i = 0; i < 16; i++) assertEquals(i, small.find(i, -i));
        }
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
        assertNull(NativeMeshEdgeTable.reserve(5, -1));
        assertNull(NativeMeshEdgeTable.reserve(5, 6));
    }

    @Test
    void growthBudgetRefusalDiscardsPartialAnswersAndReturnsOnlyItsReservation() {
        int initial = NativeMeshEdgeTable.reservedBytes();
        List<NativeMeshEdgeTable> holders = new ArrayList<>();
        try {
            NativeMeshEdgeTable next;
            while ((next = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) != null) holders.add(next);
            int occupied = NativeMeshEdgeTable.reservedBytes();
            try (NativeMeshEdgeTable growing = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES, 0)) {
                assertNotNull(growing);
                boolean refused = false;
                for (int i = 0; i < NativeMeshEdgeTable.MAX_ENTRIES; i++) {
                    if (!growing.putFirst(i, -i, i)) {
                        refused = true;
                        break;
                    }
                    assertTrue(NativeMeshEdgeTable.reservedBytes() <= NativeMeshEdgeTable.PROCESS_BUDGET_BYTES);
                }
                assertTrue(refused, "replacement plus old buffers must fit the process budget");
                assertEquals(NativeMeshEdgeTable.UNKNOWN, growing.find(0, 0));
                assertEquals(occupied, NativeMeshEdgeTable.reservedBytes());
            }
        } finally {
            for (NativeMeshEdgeTable holder : holders) holder.close();
        }
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
    }

    @Test
    void capacityRefusalDiscardsPartialAnswersAndReturnsReservation() {
        int initial = NativeMeshEdgeTable.reservedBytes();
        NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(2);
        assertNotNull(table);
        assertTrue(table.putFirst(1, 2, 0));
        assertTrue(table.putFirst(2, 3, 1));
        assertTrue(table.putFirst(1, 2, 4), "duplicates remain valid at capacity");
        assertFalse(table.putFirst(3, 4, 2));
        assertEquals(NativeMeshEdgeTable.UNKNOWN, table.find(1, 2));
        assertEquals(NativeMeshEdgeTable.UNKNOWN, table.find(3, 4));
        assertFalse(table.putFirst(4, 5, 3));
        table.close();
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
        try (NativeMeshEdgeTable invalid = NativeMeshEdgeTable.reserve(2)) {
            assertNotNull(invalid);
            assertFalse(invalid.putFirst(1, 2, -1));
            assertEquals(NativeMeshEdgeTable.UNKNOWN, invalid.find(1, 2));
        }
    }

    @Test
    void budgetRefusesBeforeAllocationAndCloseAllowsAnotherOperation() {
        int initial = NativeMeshEdgeTable.reservedBytes();
        assertNull(NativeMeshEdgeTable.reserve(0));
        assertNull(NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES + 1));
        List<NativeMeshEdgeTable> tables = new ArrayList<>();
        try {
            NativeMeshEdgeTable next;
            while ((next = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) != null) tables.add(next);
            assertFalse(tables.isEmpty());
            assertTrue(NativeMeshEdgeTable.reservedBytes() <= NativeMeshEdgeTable.PROCESS_BUDGET_BYTES);
            assertNull(NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES));
            NativeMeshEdgeTable first = tables.remove(0);
            first.close();
            first.close();
            assertEquals(NativeMeshEdgeTable.UNKNOWN, first.find(0, 1));
            try (NativeMeshEdgeTable replacement = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) {
                assertNotNull(replacement);
            }
        } finally {
            for (NativeMeshEdgeTable table : tables) table.close();
        }
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
    }

    @Test
    void crossThreadAccessCannotAnswerOrReleaseOwnerReservation() throws Exception {
        var executor = Executors.newSingleThreadExecutor();
        try (NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(2)) {
            assertNotNull(table);
            assertTrue(table.putFirst(1, 2, 7));
            int reserved = NativeMeshEdgeTable.reservedBytes();
            executor.submit(() -> {
                        assertEquals(NativeMeshEdgeTable.UNKNOWN, table.find(1, 2));
                        assertFalse(table.putFirst(2, 3, 8));
                        assertThrows(IllegalStateException.class, table::close);
                    })
                    .get(10, TimeUnit.SECONDS);
            assertEquals(reserved, NativeMeshEdgeTable.reservedBytes());
            assertEquals(7, table.find(1, 2));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousReservationsStayWithinProcessBudgetAndReleaseOnFailure() throws Exception {
        int initial = NativeMeshEdgeTable.reservedBytes();
        var executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1),
                allocated = new CountDownLatch(16),
                release = new CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 16; i++)
                results.add(executor.submit(() -> {
                    start.await();
                    try (NativeMeshEdgeTable table = NativeMeshEdgeTable.reserve(NativeMeshEdgeTable.MAX_ENTRIES)) {
                        allocated.countDown();
                        release.await();
                        if (table == null) return false;
                        assertTrue(table.putFirst(1, 2, 0));
                        try {
                            throw new IllegalStateException("owned operation failure");
                        } catch (IllegalStateException expected) {
                            assertEquals(0, table.find(1, 2));
                        }
                        return true;
                    }
                }));
            start.countDown();
            assertTrue(allocated.await(10, TimeUnit.SECONDS));
            assertTrue(NativeMeshEdgeTable.reservedBytes() <= NativeMeshEdgeTable.PROCESS_BUDGET_BYTES);
            release.countDown();
            int admitted = 0;
            for (var result : results) if (result.get(10, TimeUnit.SECONDS)) admitted++;
            assertTrue(admitted > 0 && admitted < 16, "some concurrent requests must reach native fallback");
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
        assertEquals(initial, NativeMeshEdgeTable.reservedBytes());
    }
}
