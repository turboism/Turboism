package dev.turboism.adapter.cubism.optimization.atlas;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.optimization.atlas.DisjointTileWavePlanner.TileBounds;
import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.RecursiveAction;
import org.junit.jupiter.api.Test;

final class DisjointTileWavePlannerTest {

    @Test
    void computesClippedAndEmptyBoundsCleanly() {
        final AffineTransform identityShift = AffineTransform.getTranslateInstance(10, 20);
        assertEquals(
                new TileBounds(10, 20, 42, 68),
                DisjointTileWavePlanner.computeClippedBounds(32, 48, identityShift, 0, 128, 128));

        final AffineTransform offPage = AffineTransform.getTranslateInstance(-50, 0);
        assertTrue(DisjointTileWavePlanner.computeClippedBounds(32, 32, offPage, 0, 128, 128)
                .isEmpty());

        final AffineTransform rotated = AffineTransform.getQuadrantRotateInstance(1, 32, 32);
        final TileBounds rotBounds = DisjointTileWavePlanner.computeClippedBounds(16, 8, rotated, 2, 128, 128);
        assertFalse(rotBounds.isEmpty());
        assertEquals(new TileBounds(54, 0, 66, 18), rotBounds);
    }

    @Test
    void plansSingleWaveForDisjointTilesAndMultipleWavesPreservingPaintersOrder() {
        final List<TileBounds> disjoint = List.of(
                new TileBounds(0, 0, 64, 64),
                new TileBounds(64, 0, 128, 64),
                new TileBounds(0, 64, 64, 128),
                new TileBounds(64, 64, 128, 128));
        final List<int[]> oneWave = DisjointTileWavePlanner.planWaves(disjoint);
        assertEquals(1, oneWave.size());
        assertArrayEquals(new int[] {0, 1, 2, 3}, oneWave.get(0));

        // 0 overlaps 1; 2 overlaps 1; 3 is disjoint with all -> 3 waves, preserving order.
        final List<TileBounds> overlapping = List.of(
                new TileBounds(0, 0, 50, 50),
                new TileBounds(25, 25, 75, 75),
                new TileBounds(40, 40, 90, 90),
                TileBounds.EMPTY,
                new TileBounds(100, 100, 140, 140));
        final List<int[]> waves = DisjointTileWavePlanner.planWaves(overlapping);
        assertEquals(3, waves.size());
        assertArrayEquals(new int[] {0, 4}, waves.get(0));
        assertArrayEquals(new int[] {1}, waves.get(1));
        assertArrayEquals(new int[] {2}, waves.get(2));
    }

    @Test
    void waveParallelExecutionMatchesSequentialAlphaBlendingBitForBit() {
        final int pageW = 256;
        final int pageH = 256;
        final Random rng = new Random(777L);
        final List<BufferedImage> tiles = new ArrayList<>();
        final List<AffineTransform> transforms = new ArrayList<>();
        final List<TileBounds> boxes = new ArrayList<>();

        for (int i = 0; i < 16; i++) {
            final int w = 24 + rng.nextInt(40);
            final int h = 24 + rng.nextInt(40);
            final BufferedImage tile = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    final int a = 64 + rng.nextInt(192);
                    tile.setRGB(x, y, (a << 24) | (rng.nextInt(1 << 24)));
                }
            }
            final int tx = rng.nextInt(pageW - w);
            final int ty = rng.nextInt(pageH - h);
            final AffineTransform affine = AffineTransform.getTranslateInstance(tx, ty);
            tiles.add(tile);
            transforms.add(affine);
            boxes.add(DisjointTileWavePlanner.computeClippedBounds(w, h, affine, 0, pageW, pageH));
        }

        final BufferedImage seqCanvas = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D seqG = seqCanvas.createGraphics();
        try {
            for (int i = 0; i < tiles.size(); i++) {
                seqG.setTransform(transforms.get(i));
                assertTrue(DirectArgbTileBlitter.tryBlit(seqCanvas, seqG, tiles.get(i), 0, 0));
            }
        } finally {
            seqG.dispose();
        }

        final BufferedImage waveCanvas = new BufferedImage(pageW, pageH, BufferedImage.TYPE_INT_ARGB);
        final List<int[]> waves = DisjointTileWavePlanner.planWaves(boxes);
        for (final int[] wave : waves) {
            final List<ForkJoinTask<Void>> tasks = new ArrayList<>(wave.length);
            for (final int idx : wave) {
                final TileBounds box = boxes.get(idx);
                final BufferedImage tile = tiles.get(idx);
                final AffineTransform tx = transforms.get(idx);
                tasks.add(new RecursiveAction() {
                    @Override
                    protected void compute() {
                        final Graphics2D g = waveCanvas.createGraphics();
                        try {
                            g.setClip(box.x0(), box.y0(), box.x1() - box.x0(), box.y1() - box.y0());
                            g.setTransform(tx);
                            assertTrue(DirectArgbTileBlitter.tryBlit(waveCanvas, g, tile, 0, 0));
                        } finally {
                            g.dispose();
                        }
                    }
                });
            }
            ForkJoinTask.invokeAll(tasks);
        }

        assertArrayEquals(
                ((DataBufferInt) seqCanvas.getRaster().getDataBuffer()).getData(),
                ((DataBufferInt) waveCanvas.getRaster().getDataBuffer()).getData());
    }
}
