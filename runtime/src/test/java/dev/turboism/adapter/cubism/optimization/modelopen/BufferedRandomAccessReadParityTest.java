package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Random;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

/**
 * Verifies byte-exact read parity for {@link BufferedRandomAccessReader} and fail-closed bytecode
 * patching for {@link BufferedRandomAccessReadPatcher} across Cubism {@code 5.0.00} through
 * {@code 5.3.05}.
 */
final class BufferedRandomAccessReadParityTest {

    private static final String MODE_READ = "r";
    private static final int SMALL_BUFFER_BYTES = 64;
    private static final int LARGE_FILE_BYTES = 200_000;
    private static final int DIRECT_BYPASS_READ_BYTES = 90_000;
    private static final int POSITIVE_KEY = 0x13579BDF;
    private static final int NEGATIVE_KEY = 0x80123456;
    private static final int ZERO_KEY = 0;
    private static final int LONG_HIGH_SHIFT = 32;
    private static final long PRNG_SEED = 0x5EED1234L;
    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String CLASS_SUFFIX = ".class";

    @Test
    void primitiveReadsSeeksSkipsAndLargeBypassMatchUnbufferedRandomAccessFile(@TempDir final Path tempDir)
            throws IOException {
        final Path dataPath = tempDir.resolve("archive.bin");
        final byte[] payload = new byte[LARGE_FILE_BYTES];
        new Random(PRNG_SEED).nextBytes(payload);
        Files.write(dataPath, payload);

        final int[] keys = {ZERO_KEY, POSITIVE_KEY, NEGATIVE_KEY};
        try (RandomAccessFile bufferedRaf = new RandomAccessFile(dataPath.toFile(), MODE_READ);
                RandomAccessFile stockRaf = new RandomAccessFile(dataPath.toFile(), MODE_READ)) {
            final BufferedRandomAccessReader reader = new BufferedRandomAccessReader(bufferedRaf);
            for (final int key : keys) {
                assertEquals(stockReadByte(stockRaf, key), reader.readByte(key));
                assertEquals(stockReadShort(stockRaf, key), reader.readShort(key));
                assertEquals(stockReadInt(stockRaf, key), reader.readInt(key));
                assertEquals(stockReadLong(stockRaf, key), reader.readLong(key));
                assertEquals(stockRaf.getFilePointer(), reader.getFilePointer());
            }

            // Exercise in-buffer skip, non-positive skip, and large skip past buffer capacity.
            reader.skip(0);
            reader.skip(-5);
            reader.skip(37);
            stockSkip(stockRaf, 37);
            assertEquals(stockRaf.getFilePointer(), reader.getFilePointer());
            assertEquals(stockReadLong(stockRaf, NEGATIVE_KEY), reader.readLong(NEGATIVE_KEY));

            reader.skip(70_000);
            stockSkip(stockRaf, 70_000);
            assertEquals(stockRaf.getFilePointer(), reader.getFilePointer());
            assertEquals(stockReadInt(stockRaf, POSITIVE_KEY), reader.readInt(POSITIVE_KEY));

            // Exercise large direct-bypass readBytes with reusable buffer and non-zero key.
            final byte[] reusableLarge = new byte[DIRECT_BYPASS_READ_BYTES];
            final byte[] actualLarge = reader.readBytes(DIRECT_BYPASS_READ_BYTES, reusableLarge, NEGATIVE_KEY);
            assertSame(reusableLarge, actualLarge);
            assertArrayEquals(stockReadBytes(stockRaf, DIRECT_BYPASS_READ_BYTES, NEGATIVE_KEY), actualLarge);
            assertEquals(stockRaf.getFilePointer(), reader.getFilePointer());

            // Exercise backward seek, in-window seek, and zero-length readBytes.
            reader.seek(1234L);
            stockRaf.seek(1234L);
            assertEquals(stockReadInt(stockRaf, ZERO_KEY), reader.readInt(ZERO_KEY));
            reader.seek(1230L);
            stockRaf.seek(1230L);
            assertEquals(stockReadLong(stockRaf, NEGATIVE_KEY), reader.readLong(NEGATIVE_KEY));

            final byte[] reusableZero = new byte[4];
            assertSame(reusableZero, reader.readBytes(0, reusableZero, POSITIVE_KEY));
            assertEquals(0, reader.readBytes(0, null, ZERO_KEY).length);

            reader.close();
            reader.close();
        }
    }

    @Test
    void bufferBoundaryCrossingsEofAndRawHandleCoherenceMatchStockBehavior(@TempDir final Path tempDir)
            throws IOException {
        final Path dataPath = tempDir.resolve("boundary.bin");
        final byte[] payload = new byte[SMALL_BUFFER_BYTES * 3 + 5];
        new Random(PRNG_SEED ^ 0xFFL).nextBytes(payload);
        Files.write(dataPath, payload);

        try (RandomAccessFile bufferedRaf = new RandomAccessFile(dataPath.toFile(), MODE_READ);
                RandomAccessFile stockRaf = new RandomAccessFile(dataPath.toFile(), MODE_READ)) {
            final BufferedRandomAccessReader reader = new BufferedRandomAccessReader(bufferedRaf, SMALL_BUFFER_BYTES);

            // Straddle the 64-byte buffer boundary with readShort, readInt, and readLong.
            reader.seek(SMALL_BUFFER_BYTES - 1L);
            stockRaf.seek(SMALL_BUFFER_BYTES - 1L);
            assertEquals(stockReadShort(stockRaf, NEGATIVE_KEY), reader.readShort(NEGATIVE_KEY));

            reader.seek(SMALL_BUFFER_BYTES - 2L);
            stockRaf.seek(SMALL_BUFFER_BYTES - 2L);
            assertEquals(stockReadInt(stockRaf, POSITIVE_KEY), reader.readInt(POSITIVE_KEY));

            reader.seek(SMALL_BUFFER_BYTES - 3L);
            stockRaf.seek(SMALL_BUFFER_BYTES - 3L);
            assertEquals(stockReadLong(stockRaf, NEGATIVE_KEY), reader.readLong(NEGATIVE_KEY));

            // Synchronize raw handle, mutate its cursor directly, and verify subsequent buffered reads.
            final RandomAccessFile raw = reader.syncAndGetFile();
            assertSame(bufferedRaf, raw);
            assertEquals(stockRaf.getFilePointer(), raw.getFilePointer());
            final int directByte = raw.readByte();
            assertEquals(stockRaf.readByte(), directByte);
            assertEquals(stockRaf.getFilePointer(), reader.getFilePointer());
            assertEquals(stockReadInt(stockRaf, NEGATIVE_KEY), reader.readInt(NEGATIVE_KEY));

            // Verify partial readBytes near EOF preserves untouched tail and still applies XOR key.
            final long nearEof = payload.length - 3L;
            reader.seek(nearEof);
            stockRaf.seek(nearEof);
            final byte[] dstBuffered = new byte[] {10, 20, 30, 40, 50};
            final byte[] dstStock = dstBuffered.clone();
            final byte[] actualTail = reader.readBytes(5, dstBuffered, POSITIVE_KEY);
            final byte[] expectedTail = stockReadBytesInto(stockRaf, 5, dstStock, POSITIVE_KEY);
            assertSame(dstBuffered, actualTail);
            assertArrayEquals(expectedTail, actualTail);

            // Verify EOFException and post-exception cursor on primitive reads and skip.
            reader.seek(payload.length - 2L);
            assertThrows(EOFException.class, () -> reader.readInt(ZERO_KEY));
            assertEquals(payload.length, reader.getFilePointer());

            reader.seek(payload.length - 3L);
            assertThrows(EOFException.class, () -> reader.skip(10));
            assertEquals(payload.length, reader.getFilePointer());

            reader.seek(0L);
            assertThrows(EOFException.class, () -> reader.skip(payload.length + 100));
            assertEquals(payload.length, reader.getFilePointer());

            assertThrows(IOException.class, () -> reader.seek(-1L));
            reader.close();
        }
    }

    @Test
    void patcherFailsClosedOnUnexpectedShapeAndPatchesAllRealHostJars(@TempDir final Path tempDir) throws Exception {
        final ClassWriter synthetic = new ClassWriter(0);
        synthetic.visit(
                Opcodes.V17,
                Opcodes.ACC_PUBLIC | Opcodes.ACC_FINAL,
                BufferedRandomAccessReadPatcher.TARGET,
                null,
                "java/lang/Object",
                null);
        synthetic.visitEnd();
        assertNull(BufferedRandomAccessReadPatcher.tryPatch(
                BufferedRandomAccessReadPatcher.TARGET, null, synthetic.toByteArray()));
        assertNull(BufferedRandomAccessReadPatcher.tryPatch("com/live2d/Other", null, synthetic.toByteArray()));

        final List<Path> jars = discoverHostJars();
        Assumptions.assumeTrue(!jars.isEmpty(), "Real Cubism host JARs not present locally");

        final Path sampleFile = tempDir.resolve("host-exec.bin");
        final byte[] samplePayload = new byte[100_000];
        new Random(PRNG_SEED + 1L).nextBytes(samplePayload);
        Files.write(sampleFile, samplePayload);

        boolean executedPatchedHostClass = false;
        for (final Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile(), false);
                    URLClassLoader hostLoader = new URLClassLoader(
                            new URL[] {jarPath.toUri().toURL()}, getClass().getClassLoader())) {
                final JarEntry entry = jar.getJarEntry(BufferedRandomAccessReadPatcher.TARGET + CLASS_SUFFIX);
                assertNotNull(entry, "Missing RandomAccess$c in " + jarPath);
                final byte[] original;
                try (InputStream in = jar.getInputStream(entry)) {
                    original = in.readAllBytes();
                }

                final byte[] patched = BufferedRandomAccessReadPatcher.tryPatch(
                        BufferedRandomAccessReadPatcher.TARGET, hostLoader, original);
                assertNotNull(patched, "Expected patched bytes for " + jarPath);
                assertNull(
                        BufferedRandomAccessReadPatcher.tryPatch(
                                BufferedRandomAccessReadPatcher.TARGET, hostLoader, patched),
                        "Patcher must be idempotent for " + jarPath);

                if (!executedPatchedHostClass) {
                    verifyPatchedHostExecution(hostLoader, patched, sampleFile);
                    executedPatchedHostClass = true;
                }
            }
        }
        assertTrue(executedPatchedHostClass);
    }

    private static void verifyPatchedHostExecution(
            final ClassLoader hostLoader, final byte[] patchedBytes, final Path sampleFile) throws Exception {
        final String className = BufferedRandomAccessReadPatcher.TARGET.replace('/', '.');
        final ClassLoader patchedLoader = new ClassLoader(hostLoader) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve) throws ClassNotFoundException {
                if (className.equals(name)) {
                    final Class<?> defined = defineClass(name, patchedBytes, 0, patchedBytes.length);
                    if (resolve) {
                        resolveClass(defined);
                    }
                    return defined;
                }
                return super.loadClass(name, resolve);
            }
        };
        final Class<?> patchedClass = Class.forName(className, true, patchedLoader);
        final Constructor<?> ctor = patchedClass.getConstructor(File.class);
        final Object instance = ctor.newInstance(sampleFile.toFile());
        final Method readByte = patchedClass.getMethod("b", int.class);
        final Method readShort = patchedClass.getMethod("c", int.class);
        final Method readInt = patchedClass.getMethod("d", int.class);
        final Method readLong = patchedClass.getMethod("e", int.class);
        final Method readBytes = patchedClass.getMethod("a", int.class, byte[].class, int.class);
        final Method seek = patchedClass.getMethod("a", long.class);
        final Method skip = patchedClass.getMethod("a", int.class);
        final Method getRaf = patchedClass.getMethod("b");
        final Method close = patchedClass.getMethod("a");

        try (RandomAccessFile stockRaf = new RandomAccessFile(sampleFile.toFile(), MODE_READ)) {
            assertEquals(stockReadByte(stockRaf, NEGATIVE_KEY), readByte.invoke(instance, NEGATIVE_KEY));
            assertEquals(stockReadShort(stockRaf, NEGATIVE_KEY), readShort.invoke(instance, NEGATIVE_KEY));
            assertEquals(stockReadInt(stockRaf, NEGATIVE_KEY), readInt.invoke(instance, NEGATIVE_KEY));
            assertEquals(stockReadLong(stockRaf, NEGATIVE_KEY), readLong.invoke(instance, NEGATIVE_KEY));

            skip.invoke(instance, 19);
            stockSkip(stockRaf, 19);
            final byte[] actualChunk = (byte[]) readBytes.invoke(instance, 70_000, null, POSITIVE_KEY);
            assertArrayEquals(stockReadBytes(stockRaf, 70_000, POSITIVE_KEY), actualChunk);

            seek.invoke(instance, 512L);
            stockRaf.seek(512L);
            final RandomAccessFile raw = (RandomAccessFile) getRaf.invoke(instance);
            assertEquals(512L, raw.getFilePointer());
            assertEquals(stockReadInt(stockRaf, ZERO_KEY), readInt.invoke(instance, ZERO_KEY));
        } finally {
            close.invoke(instance);
        }
    }

    private static List<Path> discoverHostJars() throws IOException {
        final Path userHome = Path.of(System.getProperty("user.home"));
        final Path root = userHome.resolve(ARTIFACTS_DIR_NAME);
        final List<Path> jars = new ArrayList<>();
        if (Files.isDirectory(root)) {
            try (Stream<Path> dirs = Files.list(root)) {
                dirs.filter(Files::isDirectory)
                        .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                        .map(dir -> dir.resolve(JAR_NAME))
                        .filter(Files::isRegularFile)
                        .forEach(jars::add);
            }
        }
        final Path jar5305 = userHome.resolve(JAR_5305_REL);
        if (Files.isRegularFile(jar5305)) {
            jars.add(jar5305);
        }
        return jars;
    }

    private static byte stockReadByte(final RandomAccessFile raf, final int key) throws IOException {
        return (byte) (raf.readByte() ^ key);
    }

    private static short stockReadShort(final RandomAccessFile raf, final int key) throws IOException {
        return (short) (raf.readShort() ^ key);
    }

    private static int stockReadInt(final RandomAccessFile raf, final int key) throws IOException {
        return raf.readInt() ^ key;
    }

    private static long stockReadLong(final RandomAccessFile raf, final int key) throws IOException {
        final long mask = (((long) key) << LONG_HIGH_SHIFT) | ((long) key);
        return raf.readLong() ^ mask;
    }

    private static void stockSkip(final RandomAccessFile raf, final int count) throws IOException {
        for (int i = 0; i < count; i++) {
            raf.readByte();
        }
    }

    private static byte[] stockReadBytes(final RandomAccessFile raf, final int len, final int key) throws IOException {
        return stockReadBytesInto(raf, len, new byte[len], key);
    }

    private static byte[] stockReadBytesInto(final RandomAccessFile raf, final int len, final byte[] dst, final int key)
            throws IOException {
        raf.read(dst, 0, len);
        if (key != 0) {
            for (int i = 0; i < len; i++) {
                dst[i] = (byte) (dst[i] ^ key);
            }
        }
        return dst;
    }
}
