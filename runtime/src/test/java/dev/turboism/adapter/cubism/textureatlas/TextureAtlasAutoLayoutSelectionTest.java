package dev.turboism.adapter.cubism.textureatlas;

import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutSelection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class TextureAtlasAutoLayoutSelectionTest {

    @Test
    void selectPersistsTheWinningUpdateEvenWhenAnEarlierSaveIsSlower() throws Exception {
        final CopyOnWriteArrayList<TextureAtlasLayoutSelection> saved = new CopyOnWriteArrayList<>();
        final CountDownLatch firstSaving = new CountDownLatch(1);
        final CountDownLatch releaseFirst = new CountDownLatch(1);
        final AtomicReference<TextureAtlasLayoutSelection> persisted = new AtomicReference<>();
        final TextureAtlasAutoLayoutSelection.Persistence store =
            new TextureAtlasAutoLayoutSelection.Persistence() {
                @Override
                public TextureAtlasLayoutSelection load() {
                    return TextureAtlasLayoutSelection.nativeDefault();
                }

                @Override
                public void save(final TextureAtlasLayoutSelection selection) {
                    saved.add(selection);
                    if (saved.size() == 1) {
                        firstSaving.countDown();
                        try {
                            assertTrue(releaseFirst.await(10, TimeUnit.SECONDS));
                        } catch (InterruptedException interrupted) {
                            Thread.currentThread().interrupt();
                            throw new IllegalStateException(interrupted);
                        }
                    }
                    // The store commits at the end of save, like a real write.
                    persisted.set(selection);
                }
            };
        final TextureAtlasAutoLayoutSelection selection =
            new TextureAtlasAutoLayoutSelection(store);
        final TextureAtlasLayoutSelection first = new TextureAtlasLayoutSelection("first", false);
        final TextureAtlasLayoutSelection second = new TextureAtlasLayoutSelection("second", true);

        final Thread firstWriter = new Thread(() -> selection.select(first));
        firstWriter.start();
        assertTrue(firstSaving.await(10, TimeUnit.SECONDS));

        // The second select must wait until the in-flight save completes, so the
        // store can never end up holding the stale first selection.
        final Thread secondWriter = new Thread(() -> selection.select(second));
        secondWriter.start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (secondWriter.getState() != Thread.State.BLOCKED
            && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(Thread.State.BLOCKED, secondWriter.getState());
        releaseFirst.countDown();
        firstWriter.join(10_000);
        secondWriter.join(10_000);

        assertEquals(List.of(first, second), saved);
        assertEquals(second, persisted.get());
        assertEquals(second, selection.selection());
    }

    @Test
    void selectSkipsRedundantWrites() {
        final List<TextureAtlasLayoutSelection> saved = new java.util.ArrayList<>();
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection(
            new TextureAtlasAutoLayoutSelection.Persistence() {
                @Override public TextureAtlasLayoutSelection load() {
                    return TextureAtlasLayoutSelection.nativeDefault();
                }
                @Override public void save(final TextureAtlasLayoutSelection next) {
                    saved.add(next);
                }
            }
        );
        final TextureAtlasLayoutSelection value = new TextureAtlasLayoutSelection("algo", true);
        selection.select(value);
        selection.select(value);
        assertEquals(1, saved.size());
        assertEquals(value, selection.selection());
    }

    @Test
    void persistedSelectionIsRestoredOnConstruction() {
        final TextureAtlasLayoutSelection stored = new TextureAtlasLayoutSelection("algo", true);
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection(
            new TextureAtlasAutoLayoutSelection.Persistence() {
                @Override public TextureAtlasLayoutSelection load() { return stored; }
                @Override public void save(final TextureAtlasLayoutSelection next) { }
            }
        );
        assertEquals(stored, selection.selection());
    }

    @Test
    void loadFailureDegradesToNativeDefault() {
        final TextureAtlasAutoLayoutSelection selection = new TextureAtlasAutoLayoutSelection(
            new TextureAtlasAutoLayoutSelection.Persistence() {
                @Override public TextureAtlasLayoutSelection load() {
                    throw new IllegalStateException("unreadable store");
                }
                @Override public void save(final TextureAtlasLayoutSelection next) { }
            }
        );
        assertTrue(selection.selection().isNative());
    }
}
