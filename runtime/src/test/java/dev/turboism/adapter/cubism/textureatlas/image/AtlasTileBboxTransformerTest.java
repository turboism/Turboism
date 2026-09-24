package dev.turboism.adapter.cubism.textureatlas.image;

import org.junit.jupiter.api.Test;

import java.awt.Graphics2D;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.lang.reflect.Method;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Behaviour tests for the tile-bbox transformer.
 *
 * <p>The fixtures are compiled into the test source set under the reviewed class names with
 * the reviewed method shape — {@code com/live2d/util/f/g} (5.3.x) and {@code
 * com/live2d/util/e/g} (5.2.03) — so the test exercises the real selector and the real rewrite
 * without touching any official artifact. Patched classes are defined in child-first loaders
 * so the unpatched classpath copies cannot silently stand in for the candidates.</p>
 */
final class AtlasTileBboxTransformerTest {

    private static final AtlasTileBboxTarget F_G =
        AtlasTileBboxTarget.reviewed("com/live2d/util/f/g");
    private static final AtlasTileBboxTarget E_G =
        AtlasTileBboxTarget.reviewed("com/live2d/util/e/g");

    @Test
    void patchesTheReviewedShape() throws Exception {
        assertPatchesReviewedShape(F_G);
        assertPatchesReviewedShape(E_G);
    }

    private static void assertPatchesReviewedShape(final AtlasTileBboxTarget target)
            throws Exception {
        final byte[] fixture = fixtureBytes(target.internalName() + ".class");
        final AtlasTileBboxTransformer transformer =
            new AtlasTileBboxTransformer(target, java.util.Set.of(
                AtlasTileBboxTransformer.sha256(fixture)));

        final byte[] patched = transformer.transform(
            null, AtlasTileBboxTransformerTest.class.getClassLoader(),
            target.internalName(), null, null, fixture);

        assertNotNull(patched, "the reviewed shape must be patched: " + target.internalName());
        assertEquals(AtlasTileBboxTransformer.Outcome.PATCHED, transformer.outcome());
    }

    @Test
    void refusesClassesWhoseBytesAreNotTheReviewedDigest() throws Exception {
        final AtlasTileBboxTransformer transformer = new AtlasTileBboxTransformer();
        for (final AtlasTileBboxTarget target : new AtlasTileBboxTarget[]{F_G, E_G}) {
            final byte[] fixture = fixtureBytes(target.internalName() + ".class");
            assertNull(transformer.transform(
                null, getLoader(), target.internalName(), null, null, fixture));
        }
        assertEquals(AtlasTileBboxTransformer.Outcome.HASH_MISMATCH, transformer.outcome());
    }

    @Test
    void refusesAClassWithTheDigestButNotTheShape() throws Exception {
        final byte[] alien = fixtureBytes("com/live2d/util/f/Alien.class");
        final String alienSha = AtlasTileBboxTransformer.sha256(alien);
        for (final AtlasTileBboxTarget target : new AtlasTileBboxTarget[]{F_G, E_G}) {
            final AtlasTileBboxTransformer transformer =
                new AtlasTileBboxTransformer(target, java.util.Set.of(alienSha));
            assertNull(transformer.transform(
                null, getLoader(), target.internalName(), null, null, alien));
            assertEquals(AtlasTileBboxTransformer.Outcome.SHAPE_REJECTED, transformer.outcome());
        }
    }

    @Test
    void admitsTheExtraDigestOnlyWhenThePropertyIsSet() throws Exception {
        final byte[] fixture = fixtureBytes("com/live2d/util/f/g.class");
        final String fixtureSha = AtlasTileBboxTransformer.sha256(fixture);
        final String property = AtlasTileBboxTransformer.ADMIT_CLASS_SHA256_PROPERTY;
        final String saved = System.getProperty(property);
        try {
            System.setProperty(property, fixtureSha);
            final AtlasTileBboxTransformer transformer = new AtlasTileBboxTransformer();
            assertNotNull(transformer.transform(
                null, getLoader(), F_G.internalName(), null, null, fixture),
                "the admitted extra digest must patch like the reviewed one");
            assertEquals(AtlasTileBboxTransformer.Outcome.PATCHED, transformer.outcome());
        } finally {
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    @Test
    void admitsCommaSeparatedExtraDigests() throws Exception {
        final byte[] fixture = fixtureBytes("com/live2d/util/e/g.class");
        final String fixtureSha = AtlasTileBboxTransformer.sha256(fixture);
        final String filler = "a".repeat(64);
        final String property = AtlasTileBboxTransformer.ADMIT_CLASS_SHA256_PROPERTY;
        final String saved = System.getProperty(property);
        try {
            System.setProperty(property, filler + " , " + fixtureSha);
            final AtlasTileBboxTransformer transformer = new AtlasTileBboxTransformer();
            assertNotNull(transformer.transform(
                null, getLoader(), E_G.internalName(), null, null, fixture),
                "a digest later in the comma list must admit its target");
            assertEquals(AtlasTileBboxTransformer.Outcome.PATCHED, transformer.outcome());
        } finally {
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    @Test
    void ignoresMalformedExtraDigests() throws Exception {
        final byte[] fixture = fixtureBytes("com/live2d/util/f/g.class");
        final String property = AtlasTileBboxTransformer.ADMIT_CLASS_SHA256_PROPERTY;
        final String saved = System.getProperty(property);
        try {
            System.setProperty(property, "not-hex");
            final AtlasTileBboxTransformer transformer = new AtlasTileBboxTransformer();
            assertNull(transformer.transform(
                null, getLoader(), F_G.internalName(), null, null, fixture));
            assertEquals(AtlasTileBboxTransformer.Outcome.HASH_MISMATCH, transformer.outcome());
        } finally {
            if (saved == null) System.clearProperty(property);
            else System.setProperty(property, saved);
        }
    }

    @Test
    void declinesWhenTheLoaderCannotResolveTheHostHelpers() throws Exception {
        // A loader with no parent cannot see the jp.noids.* fixtures — the same failure the
        // transformer would face if the delegate's helpers were absent on a foreign host.
        for (final AtlasTileBboxTarget target : new AtlasTileBboxTarget[]{F_G, E_G}) {
            final byte[] fixture = fixtureBytes(target.internalName() + ".class");
            final AtlasTileBboxTransformer transformer =
                new AtlasTileBboxTransformer(target, java.util.Set.of(
                    AtlasTileBboxTransformer.sha256(fixture)));
            final ClassLoader orphan = new ClassLoader(null) { };

            assertNull(transformer.transform(
                null, orphan, target.internalName(), null, null, fixture));
            assertEquals(AtlasTileBboxTransformer.Outcome.HELPERS_UNAVAILABLE,
                transformer.outcome());
        }
    }

    @Test
    void ignoresEveryOtherClassAndNullBytes() {
        final AtlasTileBboxTransformer transformer = new AtlasTileBboxTransformer();
        final ClassLoader loader = getLoader();
        assertNull(transformer.transform(
            null, loader, "com/live2d/util/f/other", null, null, new byte[8]));
        assertNull(transformer.transform(null, loader, F_G.internalName(), null, null, null));
        assertEquals(AtlasTileBboxTransformer.Outcome.NONE, transformer.outcome());
    }

    @Test
    void patchedClassIsPixelIdenticalToTheOriginalPipeline() throws Exception {
        assertPixelIdentical(F_G, "com.live2d.util.f.gorig");
        assertPixelIdentical(E_G, "com.live2d.util.e.gorig");
    }

    private static void assertPixelIdentical(final AtlasTileBboxTarget target,
                                             final String referenceClass) throws Exception {
        final String binaryName = target.className();
        final byte[] fixture = fixtureBytes(target.internalName() + ".class");
        final AtlasTileBboxTransformer transformer =
            new AtlasTileBboxTransformer(target, java.util.Set.of(
                AtlasTileBboxTransformer.sha256(fixture)));

        final ClassLoader child = new ClassLoader(getLoader()) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve)
                    throws ClassNotFoundException {
                synchronized (getClassLoadingLock(name)) {
                    if (name.equals(binaryName)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) {
                            final byte[] patched = transformer.transform(
                                null, this, target.internalName(), null, null, fixture);
                            assertNotNull(patched);
                            loaded = defineClass(name, patched, 0, patched.length);
                        }
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                    return super.loadClass(name, resolve);
                }
            }
        };

        final Class<?> patchedG = Class.forName(binaryName, true, child);
        final Class<?> originalG = Class.forName(referenceClass, true, getLoader());
        final Object patchedInstance = patchedG.getDeclaredField("a").get(null);
        final Object originalInstance = originalG.getDeclaredField("a").get(null);
        final Method patchedDraw = patchedG.getMethod("draw",
            BufferedImage.class, Graphics2D.class, BufferedImage.class, int.class, int.class);
        final Method originalDraw = originalG.getMethod("draw",
            BufferedImage.class, Graphics2D.class, BufferedImage.class, int.class, int.class);

        final Random rng = new Random(0x5EED);
        final int cases = 150;
        for (int c = 0; c < cases; c++) {
            final int pw = 64 + rng.nextInt(897);
            final int ph = 64 + rng.nextInt(897);
            final int sw = 4 + rng.nextInt(509);
            final int sh = 4 + rng.nextInt(509);
            final int x = rng.nextInt(pw + 512) - 256;
            final int y = rng.nextInt(ph + 512) - 256;
            final AffineTransform t = randomTransform(rng);
            final BufferedImage src = randomSrc(rng, sw, sh);
            final BufferedImage pageA = randomPage(rng, pw, ph);
            final BufferedImage pageB = copy(pageA);

            final Graphics2D gA = pageA.createGraphics();
            gA.setTransform(t);
            originalDraw.invoke(originalInstance, pageA, gA, src, x, y);
            gA.dispose();

            final Graphics2D gB = pageB.createGraphics();
            gB.setTransform(t);
            patchedDraw.invoke(patchedInstance, pageB, gB, src, x, y);
            gB.dispose();

            assertEquals(0, diffCount(pageA, pageB),
                target.internalName() + " case " + c + " page=" + pw + "x" + ph
                    + " src=" + sw + "x" + sh + " pos=(" + x + "," + y + ") transform=" + t);
        }
        assertEquals(AtlasTileBboxTransformer.Outcome.PATCHED, transformer.outcome());
    }

    private static ClassLoader getLoader() {
        return AtlasTileBboxTransformerTest.class.getClassLoader();
    }

    private static AffineTransform randomTransform(final Random rng) {
        final AffineTransform t = new AffineTransform();
        switch (rng.nextInt(6)) {
            case 0 -> { }
            case 1 -> t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100);
            case 2 -> {
                final double s = 0.2 + rng.nextDouble() * 2.8;
                t.translate(rng.nextInt(200) - 100, rng.nextInt(200) - 100);
                t.scale(s, s);
            }
            case 3 -> {
                t.translate(rng.nextDouble() * 200 - 100, rng.nextDouble() * 200 - 100);
                t.scale(0.2 + rng.nextDouble() * 2.0, 0.2 + rng.nextDouble() * 2.0);
            }
            case 4 -> {
                t.translate(rng.nextInt(300), rng.nextInt(300));
                t.rotate(rng.nextDouble() * Math.PI * 2);
            }
            default -> {
                t.translate(rng.nextInt(300), rng.nextInt(300));
                t.shear(rng.nextDouble() * 0.8 - 0.4, rng.nextDouble() * 0.8 - 0.4);
            }
        }
        return t;
    }

    private static BufferedImage randomSrc(final Random rng, final int w, final int h) {
        final BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        final int mode = rng.nextInt(5);
        for (int i = 0; i < px.length; i++) {
            final int r = rng.nextInt(256), g = rng.nextInt(256), b = rng.nextInt(256);
            final int a = switch (mode) {
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

    private static BufferedImage randomPage(final Random rng, final int w, final int h) {
        final BufferedImage bi = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
        final int[] px = ((DataBufferInt) bi.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            px[i] = rng.nextInt(256) << 24 | rng.nextInt(0x1000000);
        }
        return bi;
    }

    private static BufferedImage copy(final BufferedImage src) {
        final BufferedImage c =
            new BufferedImage(src.getWidth(), src.getHeight(), src.getType());
        final int[] s = ((DataBufferInt) src.getRaster().getDataBuffer()).getData();
        final int[] d = ((DataBufferInt) c.getRaster().getDataBuffer()).getData();
        System.arraycopy(s, 0, d, 0, s.length);
        return c;
    }

    private static int diffCount(final BufferedImage a, final BufferedImage b) {
        final int[] pa = ((DataBufferInt) a.getRaster().getDataBuffer()).getData();
        final int[] pb = ((DataBufferInt) b.getRaster().getDataBuffer()).getData();
        int d = 0;
        for (int i = 0; i < pa.length; i++) if (pa[i] != pb[i]) d++;
        return d;
    }

    private static byte[] fixtureBytes(final String resource) throws Exception {
        try (var in = AtlasTileBboxTransformerTest.class.getClassLoader()
                .getResourceAsStream(resource)) {
            assertNotNull(in, "missing fixture resource " + resource);
            return in.readAllBytes();
        }
    }
}
