package dev.turboism.adapter.cubism.optimization.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ArchiveAndMocRuntimeParityTest {

    @Test
    void archiveWriterGetFreeFilePathMatchesStockNamingAndScalesLinearly() {
        final Object writer = new Object();
        final HashSet<String> fastSet = new HashSet<>();
        final HashSet<String> stockSet = new HashSet<>();

        final String[] inputs = {
            "image.png",
            "dir\\sub\\image.png",
            "dir.v1/noext",
            "tex.png",
            "image.png",
            "dir/sub/image.png",
            "no_extension"
        };
        for (int round = 0; round < 50; round++) {
            for (final String raw : inputs) {
                final String actual = ArchiveWriterIndexCache.getFreeFilePath(writer, fastSet, raw);
                final String expected = stockGetFreeFilePath(stockSet, raw);
                assertEquals(expected, actual);
            }
        }

        final long startNs = System.nanoTime();
        for (int i = 0; i < 20_000; i++) {
            assertNotNull(ArchiveWriterIndexCache.getFreeFilePath(writer, fastSet, "image.png"));
        }
        final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue(elapsedMs < 1_000L, "Expected O(1) archive naming, took " + elapsedMs + "ms");
    }

    @Test
    void mocIndexAcceleratorPreservesFirstSeenIndexOfAndInternSemantics() {
        final List<String> list = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            list.add("item-" + (i % 20));
        }
        assertEquals(list.indexOf("item-0"), MocExportIndexAccelerator.indexOf(list, "item-0"));
        assertEquals(list.indexOf("item-19"), MocExportIndexAccelerator.indexOf(list, "item-19"));
        assertEquals(-1, MocExportIndexAccelerator.indexOf(list, "missing"));

        // Incremental appends invalidate cached bound cleanly.
        list.add("item-new");
        assertEquals(50, MocExportIndexAccelerator.indexOf(list, "item-new"));

        final List<String> internList = new ArrayList<>();
        final String canonical = new String("shared-value");
        final String duplicate = new String("shared-value");
        for (int i = 0; i < 25; i++) {
            final String v = "v-" + i;
            assertSame(v, MocExportIndexAccelerator.intern(internList, v));
        }
        assertSame(canonical, MocExportIndexAccelerator.intern(internList, canonical));
        assertSame(canonical, MocExportIndexAccelerator.intern(internList, duplicate));
        assertEquals(26, internList.size());

        // Verify mutated list identity caching remains O(1) without rehashing the entire list.
        final List<String> growing = new ArrayList<>();
        final long startNs = System.nanoTime();
        for (int i = 0; i < 4_000; i++) {
            final String unique = "entry-" + i;
            assertSame(unique, MocExportIndexAccelerator.intern(growing, unique));
            assertEquals(i, MocExportIndexAccelerator.indexOf(growing, unique));
        }
        final long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;
        assertTrue(elapsedMs < 1_000L, "Expected O(1) identity-cached intern/indexOf, took " + elapsedMs + "ms");
        MocExportIndexAccelerator.exitMocBufferScope();
    }

    @Test
    void fastForceCopyPreservesIntArgbPixelsAndHandlesFallback() {
        final BufferedImage src = new BufferedImage(17, 13, BufferedImage.TYPE_INT_ARGB);
        final int[] pixels = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = (i * 0x01030711) | 0x80000000;
        }
        final BufferedImage copy = RuntimeExportScope.fastForceCopy(src, BufferedImage.TYPE_INT_ARGB);
        assertNotNull(copy);
        assertNotSame(src, copy);
        assertArrayEquals(pixels, ((DataBufferInt) copy.getRaster().getDataBuffer()).getData());

        final BufferedImage wrongTarget = RuntimeExportScope.fastForceCopy(src, BufferedImage.TYPE_4BYTE_ABGR);
        assertEquals(null, wrongTarget);
    }

    @Test
    void scopeAndBufferLifecycleCleanUpProperly() {
        RuntimeExportScope.enter(null);
        assertTrue(RuntimeExportScope.isActive());
        RuntimeExportScope.enter(null);
        RuntimeExportScope.exit();
        assertTrue(RuntimeExportScope.isActive());
        RuntimeExportScope.exit();
        assertFalse(RuntimeExportScope.isActive());

        assertEquals(0, MocExportIndexAccelerator.currentBufferInitialCapacity());
        MocExportIndexAccelerator.enterMocBufferScope();
        assertTrue(MocExportIndexAccelerator.currentBufferInitialCapacity() >= 1024 * 1024);
        MocExportIndexAccelerator.exitMocBufferScope();
        assertEquals(0, MocExportIndexAccelerator.currentBufferInitialCapacity());
    }

    private static String stockGetFreeFilePath(final HashSet<String> set, final String rawPath) {
        final String candidate = rawPath.replace('\\', '/');
        if (!set.contains(candidate)) {
            set.add(candidate);
            return candidate;
        }
        final int slash = candidate.lastIndexOf('/');
        int dot = candidate.lastIndexOf('.');
        if (dot < slash) {
            dot = -1;
        }
        final String stem = dot < 0 ? candidate : candidate.substring(0, dot);
        final String ext = dot < 0 ? "" : candidate.substring(dot);
        for (int i = 0; i < 100_000_000; i++) {
            final String probe = stem + i + ext;
            if (!set.contains(probe)) {
                set.add(probe);
                return probe;
            }
        }
        throw new IllegalStateException("too much file");
    }
}
