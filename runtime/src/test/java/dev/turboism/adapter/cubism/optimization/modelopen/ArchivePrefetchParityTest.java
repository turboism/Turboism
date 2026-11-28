package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies archive background prefetch parity (RAW, Data-Descriptor ZIP, XOR obfuscation),
 * memory backpressure and cleanup, single-resource image pre-decode submission, and patching
 * across all 21 official Cubism Editor JARs ({@code 5.0.00} through {@code 5.3.05}).
 */
final class ArchivePrefetchParityTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String CLASS_SUFFIX = ".class";
    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final int XOR_OBFUSCATION_KEY = 0x5A;
    private static final long DEFAULT_TEST_BUDGET_BYTES = 16L * 1024L * 1024L;
    private static final int BACKPRESSURE_ENTRY_SIZE = 16 * 1024;
    private static final int BACKPRESSURE_ENTRY_COUNT = 12;
    private static final long BACKPRESSURE_BUDGET_BYTES = BACKPRESSURE_ENTRY_SIZE;
    private static final long POLL_TIMEOUT_MILLIS = 5000L;
    private static final long POLL_SLEEP_MILLIS = 10L;
    private static final int STUB_IMAGE_DIMENSION = 64;
    private static final long PREDECODE_WAIT_SECONDS = 5L;

    @Test
    void prefetchesRawDataDescriptorZipAndXorEntriesWithZeroCopyParity(@TempDir final Path tempDir) throws Exception {
        verifyPrefetchParity(tempDir.resolve("plain.cmo3"), 0);
        verifyPrefetchParity(tempDir.resolve("obfuscated.cmo3"), XOR_OBFUSCATION_KEY);
    }

    @Test
    void enforcesMemoryBackpressureAndReleasesResourcesOnClose(@TempDir final Path tempDir) throws Exception {
        final byte[] block = new byte[BACKPRESSURE_ENTRY_SIZE];
        for (int i = 0; i < block.length; i++) {
            block[i] = (byte) (i * 31 + 7);
        }
        final ByteArrayOutputStream archiveBytes = new ByteArrayOutputStream();
        final List<ArchivePrefetchSession.PrefetchEntrySpec> specs = new ArrayList<>();
        for (int i = 0; i < BACKPRESSURE_ENTRY_COUNT; i++) {
            final long offset = archiveBytes.size();
            archiveBytes.write(block);
            specs.add(new ArchivePrefetchSession.PrefetchEntrySpec("tex_" + i + ".png", offset, block.length, false));
        }
        final Path archiveFile = tempDir.resolve("budget.cmo3");
        Files.write(archiveFile, archiveBytes.toByteArray());

        final Object fakeReader = new Object();
        final ArchivePrefetchSession session =
                ArchivePrefetchSession.startForEntries(archiveFile, 0, BACKPRESSURE_BUDGET_BYTES, specs);
        ArchiveReadBridge.registerPrefetchSessionForTest(fakeReader, session);
        try {
            waitUntil(() -> session.inFlightPrefetchedBytes() >= BACKPRESSURE_BUDGET_BYTES);
            final long totalArchiveBytes = (long) BACKPRESSURE_ENTRY_COUNT * BACKPRESSURE_ENTRY_SIZE;
            assertTrue(
                    session.inFlightPrefetchedBytes() < totalArchiveBytes,
                    "Backpressure must pause workers before buffering the full archive");

            final InputStream first = ArchiveReadBridge.tryTakePrefetchedStream(fakeReader, "tex_0.png");
            assertNotNull(first);
            assertArrayEquals(block, ArchiveReadBridge.readAllBytes(first));
            first.close();

            // Closing must wake blocked workers, clear unconsumed buffers, and close the channel.
            ArchiveReadBridge.closeArchivePrefetch(fakeReader);
            assertFalse(session.isActive());
            assertFalse(session.isChannelOpen());
            assertEquals(0L, session.inFlightPrefetchedBytes());
            assertNull(ArchiveReadBridge.tryTakePrefetchedStream(fakeReader, "tex_1.png"));
        } finally {
            ArchiveReadBridge.closeArchivePrefetch(fakeReader);
        }

        assertEquals(16L << 20, ArchivePrefetchSession.computeMaxPrefetchBytes(0L));
        assertEquals(16L << 20, ArchivePrefetchSession.computeMaxPrefetchBytes(128L << 20));
        assertEquals(64L << 20, ArchivePrefetchSession.computeMaxPrefetchBytes(1L << 30));
        assertEquals(192L << 20, ArchivePrefetchSession.computeMaxPrefetchBytes(8L << 30));
    }

    @Test
    void schedulesSingleImageResourcePredecodeWithDeduplicationAndGuards() throws Exception {
        final StubImageResource active = new StubImageResource(STUB_IMAGE_DIMENSION, STUB_IMAGE_DIMENSION, false, true);
        final StubImageResource disposed =
                new StubImageResource(STUB_IMAGE_DIMENSION, STUB_IMAGE_DIMENSION, true, true);
        final StubImageResource alreadyDecoded =
                new StubImageResource(STUB_IMAGE_DIMENSION, STUB_IMAGE_DIMENSION, false, false);

        ImageDecodeBridge.scheduleImageResourcePredecode(null);
        ImageDecodeBridge.scheduleImageResourcePredecode(disposed);
        ImageDecodeBridge.scheduleImageResourcePredecode(alreadyDecoded);
        ImageDecodeBridge.scheduleImageResourcePredecode(active);
        ImageDecodeBridge.scheduleImageResourcePredecode(active);

        assertTrue(active.decodedLatch.await(PREDECODE_WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals(1, active.decodeCount.get());
        assertEquals(0, disposed.decodeCount.get());
        assertEquals(0, alreadyDecoded.decodeCount.get());
    }

    @Test
    void patchesArchiveReaderPrefetchAndCloseHooksAcrossAllTwentyOneJars() throws Exception {
        final List<Path> jars = discoverAllTwentyOneJars();
        Assumptions.assumeTrue(
                jars.size() == EXPECTED_TOTAL_JARS,
                "All 21 Cubism JARs (5.0.00-5.3.05) must exist locally; found " + jars.size());

        for (final Path jarPath : jars) {
            try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
                final JarEntry entry = jar.getJarEntry(ArchiveReadPatcher.ARCHIVE_READER + CLASS_SUFFIX);
                assertNotNull(entry, "ArchiveReader missing in " + jarPath);
                final byte[] original;
                try (InputStream in = jar.getInputStream(entry)) {
                    original = in.readAllBytes();
                }

                final byte[] patched = ArchiveReadPatcher.tryPatch(ArchiveReadPatcher.ARCHIVE_READER, null, original);
                assertNotNull(patched, "Expected patched ArchiveReader in " + jarPath);
                assertNull(
                        ArchiveReadPatcher.tryPatch(ArchiveReadPatcher.ARCHIVE_READER, null, patched),
                        "ArchiveReadPatcher must be idempotent in " + jarPath);

                final boolean[] sawTryTake = new boolean[1];
                final boolean[] sawClosePrefetch = new boolean[1];
                new ClassReader(patched)
                        .accept(
                                new ClassVisitor(Opcodes.ASM9) {
                                    @Override
                                    public MethodVisitor visitMethod(
                                            final int access,
                                            final String name,
                                            final String desc,
                                            final String signature,
                                            final String[] exceptions) {
                                        if (!"readFileByFilePath".equals(name) && !"close".equals(name)) {
                                            return null;
                                        }
                                        return new MethodVisitor(Opcodes.ASM9) {
                                            @Override
                                            public void visitMethodInsn(
                                                    final int opcode,
                                                    final String owner,
                                                    final String mName,
                                                    final String mDesc,
                                                    final boolean isInterface) {
                                                if (!owner.endsWith("/ArchiveReadBridge")) {
                                                    return;
                                                }
                                                if ("readFileByFilePath".equals(name)
                                                        && "tryTakePrefetchedStream".equals(mName)) {
                                                    sawTryTake[0] = true;
                                                }
                                                if ("close".equals(name) && "closeArchivePrefetch".equals(mName)) {
                                                    sawClosePrefetch[0] = true;
                                                }
                                            }
                                        };
                                    }
                                },
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                assertTrue(sawTryTake[0], "Expected tryTakePrefetchedStream call in " + jarPath);
                assertTrue(sawClosePrefetch[0], "Expected closeArchivePrefetch call in " + jarPath);
            }
        }
    }

    private static void verifyPrefetchParity(final Path archiveFile, final int xorKey) throws Exception {
        final byte[] rawPayload = "raw-texture-payload-0123456789".repeat(256).getBytes(StandardCharsets.UTF_8);
        final byte[] deflatedPayload =
                "deflated-data-descriptor-entry-ABCDEF".repeat(512).getBytes(StandardCharsets.UTF_8);
        final byte[] storedPayload = "stored-zip-entry-payload".repeat(128).getBytes(StandardCharsets.UTF_8);
        final byte[] emptyPayload = new byte[0];

        final byte[] rawStored = applyXor(rawPayload, xorKey);
        final byte[] deflatedZip = applyXor(buildDeflatedZip("tex_deflated.png", deflatedPayload), xorKey);
        final byte[] storedZip = applyXor(buildStoredZip("tex_stored.png", storedPayload), xorKey);
        final byte[] emptyZip = applyXor(buildDeflatedZip("empty.bin", emptyPayload), xorKey);

        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        final List<ArchivePrefetchSession.PrefetchEntrySpec> specs = new ArrayList<>();
        specs.add(appendEntry(out, "raw.bin", rawStored, false));
        specs.add(appendEntry(out, "deflated.png", deflatedZip, true));
        specs.add(appendEntry(out, "stored.png", storedZip, true));
        specs.add(appendEntry(out, "empty.bin", emptyZip, true));
        Files.write(archiveFile, out.toByteArray());

        final Object fakeReader = new Object();
        final ArchivePrefetchSession session =
                ArchivePrefetchSession.startForEntries(archiveFile, xorKey, DEFAULT_TEST_BUDGET_BYTES, specs);
        ArchiveReadBridge.registerPrefetchSessionForTest(fakeReader, session);
        try {
            // Wait until all entries finish prefetching and workers close the read-only channel.
            waitUntil(() -> !session.isChannelOpen());
            assertPrefetchedBytes(fakeReader, "raw.bin", rawPayload);
            assertPrefetchedBytes(fakeReader, "deflated.png", deflatedPayload);
            assertPrefetchedBytes(fakeReader, "stored.png", storedPayload);
            assertPrefetchedBytes(fakeReader, "empty.bin", emptyPayload);
            assertEquals(0L, session.inFlightPrefetchedBytes());
            assertNull(ArchiveReadBridge.tryTakePrefetchedStream(fakeReader, "raw.bin"));
        } finally {
            ArchiveReadBridge.closeArchivePrefetch(fakeReader);
        }
    }

    private static void assertPrefetchedBytes(final Object reader, final String path, final byte[] expected)
            throws IOException {
        try (InputStream stream = ArchiveReadBridge.tryTakePrefetchedStream(reader, path)) {
            assertNotNull(stream, "Expected prefetched stream for " + path);
            assertEquals("OwnedRawBytesInputStream", stream.getClass().getSimpleName());
            assertArrayEquals(expected, ArchiveReadBridge.readAllBytes(stream));
        }
    }

    private static ArchivePrefetchSession.PrefetchEntrySpec appendEntry(
            final ByteArrayOutputStream out, final String path, final byte[] storedBytes, final boolean compressed)
            throws IOException {
        final long offset = out.size();
        out.write(storedBytes);
        return new ArchivePrefetchSession.PrefetchEntrySpec(path, offset, storedBytes.length, compressed);
    }

    private static byte[] applyXor(final byte[] source, final int xorKey) {
        final byte[] copy = source.clone();
        if (xorKey != 0) {
            for (int i = 0; i < copy.length; i++) {
                copy[i] = (byte) (copy[i] ^ xorKey);
            }
        }
        return copy;
    }

    private static byte[] buildDeflatedZip(final String name, final byte[] payload) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            zos.putNextEntry(new ZipEntry(name));
            zos.write(payload);
            zos.closeEntry();
        }
        return buf.toByteArray();
    }

    private static byte[] buildStoredZip(final String name, final byte[] payload) throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            final ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            final CRC32 crc = new CRC32();
            crc.update(payload);
            entry.setCrc(crc.getValue());
            zos.putNextEntry(entry);
            zos.write(payload);
            zos.closeEntry();
        }
        return buf.toByteArray();
    }

    private static void waitUntil(final java.util.function.BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + POLL_TIMEOUT_MILLIS;
        while (!condition.getAsBoolean()) {
            assertTrue(System.currentTimeMillis() < deadline, "Timed out waiting for prefetch condition");
            Thread.sleep(POLL_SLEEP_MILLIS);
        }
    }

    private static List<Path> discoverAllTwentyOneJars() throws IOException {
        final List<Path> jars = new ArrayList<>();
        final Path home = Path.of(System.getProperty("user.home"));
        final Path artifacts = home.resolve(ARTIFACTS_DIR_NAME);
        if (Files.isDirectory(artifacts)) {
            try (var stream = Files.list(artifacts)) {
                stream.filter(Files::isDirectory).sorted().forEach(dir -> {
                    final Path jar = dir.resolve(JAR_NAME);
                    if (Files.isRegularFile(jar)) {
                        jars.add(jar);
                    }
                });
            }
        }
        final Path j5305 = home.resolve(JAR_5305_REL);
        if (Files.isRegularFile(j5305)) {
            jars.add(j5305);
        }
        return jars;
    }

    /** Public stub mimicking Cubism {@code CImageResource} pre-decode contract. */
    public static final class StubImageResource {

        private final int width;
        private final int height;
        private final boolean disposed;
        private volatile boolean needsPredecode;
        private final AtomicInteger decodeCount = new AtomicInteger();
        private final CountDownLatch decodedLatch = new CountDownLatch(1);

        StubImageResource(final int width, final int height, final boolean disposed, final boolean needsPredecode) {
            this.width = width;
            this.height = height;
            this.disposed = disposed;
            this.needsPredecode = needsPredecode;
        }

        public boolean isDisposed() {
            return this.disposed;
        }

        public int getWidth() {
            return this.width;
        }

        public int getHeight() {
            return this.height;
        }

        public boolean turboism$needsPredecode() {
            return this.needsPredecode;
        }

        public Object getImageOrNullIfDisposed() {
            this.needsPredecode = false;
            this.decodeCount.incrementAndGet();
            this.decodedLatch.countDown();
            return new Object();
        }
    }
}
