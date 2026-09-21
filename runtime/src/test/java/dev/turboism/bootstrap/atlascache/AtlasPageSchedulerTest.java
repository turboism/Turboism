package dev.turboism.bootstrap.atlascache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class AtlasPageSchedulerTest {

    @Test
    void serialRunsEveryPageInListOrder() {
        final List<String> order = new ArrayList<>();
        final List<Runnable> pages = List.of(
            () -> order.add("a"),
            () -> order.add("b"),
            () -> order.add("c"));
        AtlasPageScheduler.serial().runPages(pages);
        assertEquals(List.of("a", "b", "c"), order);
    }

    @Test
    void serialPropagatesFailureAndStops() {
        final List<String> order = new ArrayList<>();
        final IllegalStateException boom = new IllegalStateException("page failed");
        final List<Runnable> pages = List.of(
            () -> order.add("a"),
            () -> { throw boom; },
            () -> order.add("c"));
        final IllegalStateException thrown = assertThrows(IllegalStateException.class,
            () -> AtlasPageScheduler.serial().runPages(pages));
        assertEquals(boom, thrown);
        assertEquals(List.of("a"), order);
    }

    @Test
    void uniformHeuristicAdmitsEvenlySizedTiles() {
        assertTrue(AtlasPageScheduler.uniformTileAreas(
            new long[] {100, 110, 95, 105}, 4.0));
    }

    @Test
    void uniformHeuristicRejectsSingleDominatingTile() {
        // mean≈3417, tolerance 4 → bound 13667; the 20000px tile dominates and is rejected.
        assertFalse(AtlasPageScheduler.uniformTileAreas(
            new long[] {100, 100, 100, 100, 100, 20_000}, 4.0));
    }

    @Test
    void uniformHeuristicRejectsDegenerateInput() {
        assertFalse(AtlasPageScheduler.uniformTileAreas(new long[0], 4.0));
        assertFalse(AtlasPageScheduler.uniformTileAreas(null, 4.0));
        assertFalse(AtlasPageScheduler.uniformTileAreas(new long[] {0, 0}, 4.0));
    }
}
