package dev.turboism.adapter.cubism.optimization.export;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import kotlin.Unit;
import kotlin.jvm.functions.Function2;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Verifies exact pixel-for-pixel parity between unpatched host image kernels and fast export ops.
 */
final class EdgeAndDownscaleParityTest {

    private static final String REFERENCE_VERSION = "5.3.03";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String CLASS_SUFFIX = ".class";
    private static final String EDGE_EXTEND_HOST = "com.live2d.util.d.a";
    private static final String EDGE_CLEAN_HOST = "jp.noids.graphics.h";
    private static final String DOWNSCALE_HOST = "com.live2d.util.f.g";
    private static final String INSTANCE_FIELD = "a";
    private static final String HOST_METHOD_NAME = "a";
    private static final long TEST_SEED = 20261121L;
    private static final int ZERO = 0;

    @Test
    void edgeExtendMatchesHostPixelForPixel() throws Exception {
        final Path hostJar = resolveReferenceJar();
        Assumptions.assumeTrue(Files.isRegularFile(hostJar), "Reference host JAR absent: " + hostJar);

        try (HostJarClassLoader loader = new HostJarClassLoader(hostJar)) {
            final Class<?> hostClass = Class.forName(EDGE_EXTEND_HOST, true, loader);
            final Field instanceField = hostClass.getField(INSTANCE_FIELD);
            final Object instance = instanceField.get(null);
            final Method hostExtend =
                    hostClass.getMethod(HOST_METHOD_NAME, BufferedImage.class, int.class, Function2.class);
            final Function2<Integer, String, Unit> noopProgress = (step, label) -> Unit.INSTANCE;

            final int[][] sizes = {{37, 29}, {64, 64}, {128, 96}};
            final int[] thresholds = {0, 1, 128, 255};
            final Random random = new Random(TEST_SEED);
            for (final int[] size : sizes) {
                for (final int threshold : thresholds) {
                    final BufferedImage source = createRandomArgbImage(size[0], size[1], random);
                    final BufferedImage hostImage = copyArgb(source);
                    final BufferedImage fastImage = copyArgb(source);

                    hostExtend.invoke(instance, hostImage, threshold, noopProgress);
                    final boolean handled = EdgeExtendFastOp.tryExtendEdgeColor(fastImage, threshold, noopProgress);
                    assertTrue(handled, "Expected fast edge extend to handle TYPE_INT_ARGB");
                    assertArrayEquals(
                            pixelsOf(hostImage),
                            pixelsOf(fastImage),
                            "Pixel mismatch at " + size[0] + "x" + size[1] + " threshold=" + threshold);
                }
            }
        }
    }

    @Test
    void edgeCleanMatchesHostPixelForPixelIncludingNonSquareAndGreyFill() throws Exception {
        final Path hostJar = resolveReferenceJar();
        Assumptions.assumeTrue(Files.isRegularFile(hostJar), "Reference host JAR absent: " + hostJar);

        try (HostJarClassLoader loader = new HostJarClassLoader(hostJar)) {
            final Class<?> hostClass = Class.forName(EDGE_CLEAN_HOST, true, loader);
            final Method hostClean =
                    hostClass.getMethod(HOST_METHOD_NAME, BufferedImage.class, int.class, boolean.class);

            final int[][] sizes = {{53, 31}, {31, 53}, {64, 64}, {129, 97}};
            final int[] radii = {1, 3, 5};
            final boolean[] greyFills = {false, true};
            final Random random = new Random(TEST_SEED + 1);
            for (final int[] size : sizes) {
                final BufferedImage base = createStructuredEdgeImage(size[0], size[1], random);
                for (final int radius : radii) {
                    for (final boolean greyFill : greyFills) {
                        final BufferedImage hostInput = copyArgb(base);
                        final BufferedImage fastInput = copyArgb(base);

                        final BufferedImage hostOut =
                                (BufferedImage) hostClean.invoke(null, hostInput, radius, greyFill);
                        final BufferedImage fastOut = EdgeCleanFastOp.fastCleanEdges(fastInput, radius, greyFill);

                        assertEquals(hostOut.getWidth(), fastOut.getWidth());
                        assertEquals(hostOut.getHeight(), fastOut.getHeight());
                        assertArrayEquals(
                                pixelsOf(hostOut),
                                pixelsOf(fastOut),
                                "EdgeClean mismatch at "
                                        + size[0]
                                        + "x"
                                        + size[1]
                                        + " r="
                                        + radius
                                        + " greyFill="
                                        + greyFill);
                    }
                }
            }
        }
    }

    @Test
    void downscaleMatchesHostPixelForPixel() throws Exception {
        final Path hostJar = resolveReferenceJar();
        Assumptions.assumeTrue(Files.isRegularFile(hostJar), "Reference host JAR absent: " + hostJar);

        try (HostJarClassLoader loader = new HostJarClassLoader(hostJar)) {
            final Class<?> hostClass = Class.forName(DOWNSCALE_HOST, true, loader);
            final Field instanceField = hostClass.getField(INSTANCE_FIELD);
            final Object instance = instanceField.get(null);
            final Method hostDownscale = hostClass.getMethod(
                    HOST_METHOD_NAME,
                    int.class,
                    int[].class,
                    int.class,
                    int.class,
                    int[].class,
                    int.class,
                    int.class,
                    int.class);

            final int[] scales = {1, 2, 4, 8};
            final int[][] srcSizes = {{64, 64}, {33, 25}, {128, 96}};
            final int[] paddings = {0, 1};
            final Random random = new Random(TEST_SEED + 2);
            for (final int[] srcSize : srcSizes) {
                final int srcW = srcSize[0];
                final int srcH = srcSize[1];
                final int[] src = new int[srcW * srcH];
                for (int i = ZERO; i < src.length; i++) {
                    src[i] = random.nextInt();
                }
                for (final int scale : scales) {
                    final int dstW = (srcW / scale) + (srcW % scale != ZERO ? 1 : ZERO);
                    final int dstH = (srcH / scale) + (srcH % scale != ZERO ? 1 : ZERO);
                    for (final int padding : paddings) {
                        final int paddedW = dstW + padding * 2;
                        final int paddedH = dstH + padding * 2;
                        final int[] hostDst = new int[paddedW * paddedH];
                        final int[] fastDst = new int[paddedW * paddedH];
                        hostDownscale.invoke(instance, scale, src, srcW, srcH, hostDst, dstW, dstH, padding);
                        DownscaleFastOp.downscaleBox(scale, src, srcW, srcH, fastDst, dstW, dstH, padding);
                        assertArrayEquals(
                                hostDst,
                                fastDst,
                                "Downscale mismatch src="
                                        + srcW
                                        + "x"
                                        + srcH
                                        + " scale="
                                        + scale
                                        + " padding="
                                        + padding);
                    }
                }
            }
        }
    }

    private static Path resolveReferenceJar() {
        final String prop = System.getProperty("turboism.test.cubismRefDir");
        if (prop != null && !prop.isBlank()) {
            return Path.of(prop, REFERENCE_VERSION, JAR_NAME);
        }
        final String env = System.getenv("TURBOISM_CUBISM_REF");
        if (env != null && !env.isBlank()) {
            return Path.of(env, REFERENCE_VERSION, JAR_NAME);
        }
        return Path.of(System.getProperty("user.home"), "cubism-host-artifacts", REFERENCE_VERSION, JAR_NAME);
    }

    private static BufferedImage createRandomArgbImage(final int width, final int height, final Random random) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] pixels = pixelsOf(image);
        for (int i = ZERO; i < pixels.length; i++) {
            final int roll = random.nextInt(10);
            if (roll < 5) {
                pixels[i] = ZERO;
            } else if (roll < 7) {
                final int alpha = 1 + random.nextInt(254);
                pixels[i] = (alpha << 24) | (random.nextInt(0x1000000));
            } else {
                pixels[i] = 0xFF000000 | random.nextInt(0x1000000);
            }
        }
        return image;
    }

    private static BufferedImage createStructuredEdgeImage(final int width, final int height, final Random random) {
        final BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int[] pixels = pixelsOf(image);
        for (int y = ZERO; y < height; y++) {
            for (int x = ZERO; x < width; x++) {
                final int idx = (y * width) + x;
                final boolean insideIsland = x >= 5 && x < width - 6 && y >= 4 && y < height - 5;
                if (insideIsland) {
                    final int borderDist = Math.min(Math.min(x - 5, width - 7 - x), Math.min(y - 4, height - 6 - y));
                    final int alpha = borderDist == ZERO ? (1 + random.nextInt(254)) : 255;
                    final int rgb = random.nextInt(0x1000000);
                    pixels[idx] = (alpha << 24) | rgb;
                } else if (random.nextInt(12) == ZERO) {
                    final int alpha = random.nextInt(200);
                    pixels[idx] = (alpha << 24) | random.nextInt(0x1000000);
                } else {
                    pixels[idx] = ZERO;
                }
            }
        }
        return image;
    }

    private static BufferedImage copyArgb(final BufferedImage source) {
        final BufferedImage copy =
                new BufferedImage(source.getWidth(), source.getHeight(), BufferedImage.TYPE_INT_ARGB);
        final int[] src = pixelsOf(source);
        final int[] dst = pixelsOf(copy);
        System.arraycopy(src, ZERO, dst, ZERO, src.length);
        return copy;
    }

    private static int[] pixelsOf(final BufferedImage image) {
        return ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
    }

    private static final class HostJarClassLoader extends ClassLoader implements AutoCloseable {
        private final JarFile jar;

        HostJarClassLoader(final Path jarPath) throws IOException {
            super(EdgeAndDownscaleParityTest.class.getClassLoader());
            this.jar = new JarFile(jarPath.toFile(), false);
        }

        @Override
        protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                final Class<?> loaded = findLoadedClass(name);
                if (loaded != null) {
                    if (resolve) {
                        resolveClass(loaded);
                    }
                    return loaded;
                }
                if (name.startsWith("com.live2d.") || name.startsWith("jp.")) {
                    final String entryName = name.replace('.', '/') + CLASS_SUFFIX;
                    final JarEntry entry = jar.getJarEntry(entryName);
                    if (entry != null) {
                        final Class<?> found = defineFromEntry(name, entry);
                        if (resolve) {
                            resolveClass(found);
                        }
                        return found;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }

        @Override
        protected Class<?> findClass(final String name) throws ClassNotFoundException {
            final String entryName = name.replace('.', '/') + CLASS_SUFFIX;
            final JarEntry entry = jar.getJarEntry(entryName);
            if (entry == null) {
                throw new ClassNotFoundException(name);
            }
            return defineFromEntry(name, entry);
        }

        private Class<?> defineFromEntry(final String name, final JarEntry entry) throws ClassNotFoundException {
            try (InputStream in = jar.getInputStream(entry)) {
                final byte[] bytes = in.readAllBytes();
                return defineClass(name, bytes, ZERO, bytes.length);
            } catch (IOException failure) {
                throw new ClassNotFoundException(name, failure);
            }
        }

        @Override
        public void close() throws IOException {
            jar.close();
        }
    }
}
