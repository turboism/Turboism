package dev.turboism.validation.tilepatch;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicLong;

/** Thread-safe counters + summary writer for the tile-patch probe. */
public final class TilePatchProbe {

    private static final AtomicLong DRAWS = new AtomicLong();
    private static final AtomicLong SKIPPED = new AtomicLong();
    private static final AtomicLong BBOX_PX = new AtomicLong();
    private static final AtomicLong PAGE_PX = new AtomicLong();
    private static final AtomicLong NANOS = new AtomicLong();
    private static volatile Path OUT_DIR;

    private TilePatchProbe() {
    }

    /** Sets the incremental digest output dir; page lines are appended
     *  immediately so a host crash cannot lose already-computed hashes. */
    public static void setOutputDir(Path dir) {
        OUT_DIR = dir;
    }

    public static void recordDraw(long bboxPx, long pagePx) {
        DRAWS.incrementAndGet();
        BBOX_PX.addAndGet(bboxPx);
        PAGE_PX.addAndGet(pagePx);
    }

    public static void recordSkipped() {
        SKIPPED.incrementAndGet();
    }

    /**
     * Called from the instrumented {@code CTextureAtlas.setupCacheImage$cubism}
     * RETURN — hashes the just-produced page image. Uses reflection so the probe
     * compiles without host classes on its classpath.
     * Access chain (all public, 5303-verified):
     *   atlas.getCachedAtlasImage() -> CImageResource
     *   .getImage()                 -> CWritableImage
     *   .getJBufferedImage()        -> BufferedImage -> int[] raster
     */
    public static void recordPageHash(Object atlas) {
        String line;
        try {
            Object res = atlas.getClass().getMethod("getCachedAtlasImage").invoke(atlas);
            if (res == null) { line = "null"; }
            else {
            Object wimg = res.getClass().getMethod("getImage").invoke(res);
            if (wimg == null) { line = "null-image"; }
            else {
            java.awt.image.BufferedImage bi =
                (java.awt.image.BufferedImage) wimg.getClass()
                    .getMethod("getJBufferedImage").invoke(wimg);
            int[] px = ((java.awt.image.DataBufferInt) bi.getRaster().getDataBuffer()).getData();
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(px.length * 4);
            bb.asIntBuffer().put(px);
            byte[] d = md.digest(bb.array());
            StringBuilder sb = new StringBuilder();
            for (byte x : d) sb.append(String.format("%02x", x));
            line = bi.getWidth() + "x" + bi.getHeight() + ":" + sb;
            }
            }
        } catch (Exception e) {
            line = "ERR:" + e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        synchronized (PAGE_HASHES) {
            PAGE_HASHES.add(line);
            Path dir = OUT_DIR;
            if (dir != null) {
                try {
                    Files.createDirectories(dir);
                    Files.writeString(dir.resolve("tilepatch-pages.txt"), line + "\n",
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.APPEND);
                } catch (IOException e) {
                    e.printStackTrace(System.err);
                }
            }
        }
    }

    private static final java.util.List<String> PAGE_HASHES =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static void writeSummary(Path dir) {
        try {
            Files.createDirectories(dir);
            try (PrintWriter w = new PrintWriter(
                    Files.newBufferedWriter(dir.resolve("tilepatch-summary.properties")))) {
                w.println("draws=" + DRAWS.get());
                w.println("skipped=" + SKIPPED.get());
                w.println("bboxPx=" + BBOX_PX.get());
                w.println("pagePx=" + PAGE_PX.get());
                w.println("nanos=" + NANOS.get());
                w.println("pageHashes=" + PAGE_HASHES.size());
            }
            try (PrintWriter w = new PrintWriter(
                    Files.newBufferedWriter(dir.resolve("tilepatch-pages.txt")))) {
                for (String h : PAGE_HASHES) w.println(h);
            }
        } catch (IOException e) {
            e.printStackTrace(System.err);
        }
    }
}
