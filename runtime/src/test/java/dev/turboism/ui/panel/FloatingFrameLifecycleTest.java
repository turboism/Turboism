package dev.turboism.ui.panel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FloatingFrameLifecycleTest {

    @Test
    void remembersEntriesAndConsumesCloseExactlyOnce() {
        FloatingFrameLifecycle lifecycle = new FloatingFrameLifecycle();
        Object frame = new Object();
        Object palette = new Object();
        Object sibling = new Object();
        Object originalBox = new Object();

        lifecycle.remember(frame, palette, sibling, originalBox);

        assertEquals(
            List.of(new FloatingFrameLifecycle.Entry(palette, sibling, originalBox)),
            lifecycle.beginClose(frame)
        );
        assertEquals(List.of(), lifecycle.beginClose(frame));
    }

    @Test
    void forgettingOnePaletteLeavesOtherFrameEntriesIntact() {
        FloatingFrameLifecycle lifecycle = new FloatingFrameLifecycle();
        Object frame = new Object();
        Object first = new Object();
        Object second = new Object();
        Object firstOriginal = new Object();
        Object secondOriginal = new Object();

        lifecycle.remember(frame, first, null, firstOriginal);
        lifecycle.remember(frame, second, null, secondOriginal);
        lifecycle.forget(first);

        assertEquals(
            List.of(new FloatingFrameLifecycle.Entry(second, null, secondOriginal)),
            lifecycle.beginClose(frame)
        );
    }

    @Test
    void movingAnEntryToAnotherFrameRemovesStaleOwnership() {
        FloatingFrameLifecycle lifecycle = new FloatingFrameLifecycle();
        Object firstFrame = new Object();
        Object secondFrame = new Object();
        Object palette = new Object();
        Object original = new Object();

        lifecycle.remember(firstFrame, palette, null, original);
        lifecycle.remember(secondFrame, palette, null, original);

        assertEquals(List.of(), lifecycle.beginClose(firstFrame));
        assertEquals(
            List.of(new FloatingFrameLifecycle.Entry(palette, null, original)),
            lifecycle.beginClose(secondFrame)
        );
    }
}
