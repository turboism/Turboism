package dev.turboism.validation.tilepatch;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Method;
import java.util.Random;

/**
 * Live-JVM equivalence harness.
 *
 * <p>Reference = {@code com.live2d.util.f.gorig} (same pipeline, different class
 * name — never matched by the transformer, never patched by the agent).</p>
 *
 * <p>Candidate = {@code com.live2d.util.f.g}:</p>
 * <ul>
 *   <li>{@code manual} mode — harness patches the bytes via
 *       {@link TilePatchTransformer} and loads them in a child-first loader.</li>
 *   <li>{@code agent} mode — loads g from the app loader; requires the
 *       -javaagent to have transformed it (verifies the private body was
 *       replaced by checking that probe counters move).</li>
 * </ul>
 */
public final class TilePatchHarness {

    public static void main(String[] args) throws Exception {
        String mode = args.length > 0 ? args[0] : "manual";
        int cases = args.length > 1 ? Integer.parseInt(args[1]) : 200;
        long seed = args.length > 2 ? Long.parseLong(args[2]) : 0x5EED;
        Random rng = new Random(seed);

        Class<?> patchedG;
        if (mode.equals("manual")) {
            byte[] fixtureBytes;
            try (var in = TilePatchHarness.class.getClassLoader()
                    .getResourceAsStream("com/live2d/util/f/g.class")) {
                fixtureBytes = in.readAllBytes();
            }
            TilePatchTransformer.Outcome out =
                TilePatchTransformer.patch(fixtureBytes, TilePatchTransformer.OWNER);
            if (!out.patched()) {
                System.err.println("FAIL: fixture did not patch, anchors=" + out.anchors());
                System.exit(1);
            }
            final byte[] patched = out.bytes();
            // Child-first for g only: parent delegation would find the UNPATCHED
            // fixture on the classpath and silently skip the candidate.
            ClassLoader child = new ClassLoader(TilePatchHarness.class.getClassLoader()) {
                @Override
                protected Class<?> loadClass(String name, boolean resolve)
                        throws ClassNotFoundException {
                    synchronized (getClassLoadingLock(name)) {
                        if (name.equals("com.live2d.util.f.g")) {
                            Class<?> c = findLoadedClass(name);
                            if (c == null) c = defineClass(name, patched, 0, patched.length);
                            if (resolve) resolveClass(c);
                            return c;
                        }
                        return super.loadClass(name, resolve);
                    }
                }
            };
            patchedG = Class.forName("com.live2d.util.f.g", true, child);
        } else {
            // agent mode: the -javaagent transformer rewrote the app-loader copy.
            patchedG = Class.forName("com.live2d.util.f.g");
        }
        Object patchedInst = patchedG.getDeclaredField("a").get(null);
        Method draw = patchedG.getMethod("draw",
            BufferedImage.class, Graphics2D.class, BufferedImage.class, int.class, int.class);

        Class<?> origG = Class.forName("com.live2d.util.f.gorig");
        Object origInst = origG.getDeclaredField("a").get(null);
        Method origDraw = origG.getMethod("draw",
            BufferedImage.class, Graphics2D.class, BufferedImage.class, int.class, int.class);

        int diffs = 0;
        for (int c = 0; c < cases; c++) {
            int pw = 64 + rng.nextInt(897);
            int ph = 64 + rng.nextInt(897);
            int sw = 4 + rng.nextInt(509);
            int sh = 4 + rng.nextInt(509);
            int x = rng.nextInt(pw + 512) - 256;
            int y = rng.nextInt(ph + 512) - 256;
            AffineTransform t = randomTransform(rng);
            BufferedImage src = randomSrc(rng, sw, sh);
            BufferedImage pa = randomPage(rng, pw, ph);
            BufferedImage pb = copy(pa);

            Graphics2D gA = pa.createGraphics(); gA.setTransform(t);
            origDraw.invoke(origInst, pa, gA, src, x, y);
            gA.dispose();

            Graphics2D gB = pb.createGraphics(); gB.setTransform(t);
            draw.invoke(patchedInst, pb, gB, src, x, y);
            gB.dispose();

            int d = diffCount(pa, pb);
            if (d > 0) {
                diffs++;
                System.out.printf("DIFF case=%d page=%dx%d src=%dx%d pos=(%d,%d) T=%s diffPx=%d%n",
                    c, pw, ph, sw, sh, x, y, t, d);
            }
        }
        long draws = probeField("DRAWS"), skipped = probeField("SKIPPED");
        System.out.printf("cases=%d diffs=%d probeDraws=%d probeSkipped=%d%n",
            cases, diffs, draws, skipped);
        // The delegate must actually have run (counters moved) — otherwise the
        // comparison silently tested unpatched-vs-unpatched.
        if (draws + skipped == 0) {
            System.err.println("FAIL: delegate never ran — patch did not take (mode=" + mode + ")");
            System.exit(1);
        }
        System.out.println(diffs == 0 ? "LIVE PASS: PIXEL-IDENTICAL" : "LIVE FAIL");
        if (diffs > 0) System.exit(1);
    }

    private static long probeField(String name) throws Exception {
        var f = TilePatchProbe.class.getDeclaredField(name);
        f.setAccessible(true);
        return ((java.util.concurrent.atomic.AtomicLong) f.get(null)).get();
    }

    private static AffineTransform randomTransform(Random rng) {
        AffineTransform t = new AffineTransform();
        switch (rng.nextInt(6)) {
            case 0: break;
            case 1: t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100); break;
            case 2: { double s = 0.2 + rng.nextDouble() * 2.8;
                      t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100);
                      t.scale(s, s); break; }
            case 3: { t.translate(rng.nextDouble() * 200 - 100, rng.nextDouble() * 200 - 100);
                      t.scale(0.2 + rng.nextDouble() * 2.0, 0.2 + rng.nextDouble() * 2.0); break; }
            case 4: { t.translate(rng.nextInt(300), rng.nextInt(300));
                      t.rotate(rng.nextDouble() * Math.PI * 2); break; }
            default: { t.translate(rng.nextInt(300), rng.nextInt(300));
                       t.shear(rng.nextDouble() * 0.8 - 0.4, rng.nextDouble() * 0.8 - 0.4); break; }
        }
        return t;
    }

    private static BufferedImage randomSrc(Random rng, int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        int mode = rng.nextInt(5);
        for (int i = 0; i < px.length; i++) {
            int r = rng.nextInt(256), g = rng.nextInt(256), b = rng.nextInt(256);
            int a = switch (mode) {
                case 0 -> 255;
                case 1 -> 0;
                case 2 -> (i % w == 0 || i % w == w - 1 || i / w == 0 || i / w == h - 1) ? 0 : 255;
                case 3 -> rng.nextInt(256);
                default -> rng.nextBoolean() ? 0 : 255;
            };
            px[i] = (a << 24) | (r << 16) | (g << 8) | b;
        }
        return bi;
    }

    private static BufferedImage randomPage(Random rng, int w, int h) {
        BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++)
            px[i] = rng.nextInt(256) << 24 | rng.nextInt(0x1000000);
        return bi;
    }

    private static BufferedImage copy(BufferedImage src) {
        BufferedImage c = new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        int[] d = ((DataBufferInt) c.getRaster().getDataBuffer()).getData();
        System.arraycopy(s, 0, d, 0, s.length);
        return c;
    }

    private static int diffCount(BufferedImage a, BufferedImage b) {
        int[] pa = ((DataBufferInt) a.getRaster().getDataBuffer()).getData();
        int[] pb = ((DataBufferInt) b.getRaster().getDataBuffer()).getData();
        int d = 0;
        for (int i = 0; i < pa.length; i++) if (pa[i] != pb[i]) d++;
        return d;
    }
}
