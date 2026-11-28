package dev.turboism.adapter.cubism.optimization.modelopen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;

/**
 * Verifies single-entry fast ZIP extraction parity, fallback behavior, and bytecode patching
 * across all 21 official Cubism Editor JARs ({@code 5.0.00} through {@code 5.3.05}).
 */
final class ArchiveReadFastZipParityTest {

    private static final String ARTIFACTS_DIR_NAME = "cubism-host-artifacts";
    private static final String JAR_NAME = "Live2D_Cubism.jar";
    private static final String JAR_5305_REL =
            ".local/share/cubism-reference/5.3.05/proton/pfx/drive_c/Program Files/Live2D Cubism 5.3/app/lib/"
                    + JAR_NAME;
    private static final String CLASS_SUFFIX = ".class";
    private static final int EXPECTED_TOTAL_JARS = 21;
    private static final int PAYLOAD_REPEAT_COUNT = 2048;
    private static final int DEFLATED_CORRUPT_OFFSET = 45;
    private static final int STORED_CORRUPT_OFFSET = 37;
    private static final int EOCD_SIGNATURE_OFFSET_FROM_END = 22;
    private static final byte CORRUPT_FLIP_MASK = (byte) 0xFF;

    @Test
    void deflatedDataDescriptorAndEmptyEntriesExtractWithZeroCopyClaim() throws Exception {
        final byte[] payload = "CubismFastInflate_DataDescriptor_0123456789ABCDEF\n"
                .repeat(PAYLOAD_REPEAT_COUNT)
                .getBytes(StandardCharsets.UTF_8);

        // ZipOutputStream omits entry size in the local header and writes a Data Descriptor (bit 3).
        final byte[] zipBytes = buildDeflatedZip("model.xml", payload, "cubism-comment");
        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(zipBytes)) {
            assertEquals("OwnedRawBytesInputStream", stream.getClass().getSimpleName());
            assertArrayEquals(payload, ArchiveReadBridge.readAllBytes(stream));
            assertEquals(-1, stream.read());
        }

        final byte[] emptyZip = buildDeflatedZip("empty.bin", new byte[0], null);
        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(emptyZip)) {
            assertEquals("OwnedRawBytesInputStream", stream.getClass().getSimpleName());
            assertArrayEquals(new byte[0], ArchiveReadBridge.readAllBytes(stream));
        }
    }

    @Test
    void storedEntryExtractsExactPayloadWithZeroCopyClaim() throws Exception {
        final byte[] payload = "StoredEntryPayload_ABCDEF0123456789".repeat(64).getBytes(StandardCharsets.UTF_8);
        final byte[] zipBytes = buildStoredZip("texture.raw", payload);
        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(zipBytes)) {
            assertEquals("OwnedRawBytesInputStream", stream.getClass().getSimpleName());
            assertArrayEquals(payload, ArchiveReadBridge.readAllBytes(stream));
        }
    }

    @Test
    void multiEntryAndCorruptZipsFallBackCleanlyToZipInputStream() throws Exception {
        final byte[] first = "first-entry-payload".getBytes(StandardCharsets.UTF_8);
        final byte[] second = "second-entry-payload".getBytes(StandardCharsets.UTF_8);
        final ByteArrayOutputStream multiBuf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(multiBuf)) {
            zos.putNextEntry(new ZipEntry("a.xml"));
            zos.write(first);
            zos.closeEntry();
            zos.putNextEntry(new ZipEntry("b.xml"));
            zos.write(second);
            zos.closeEntry();
        }
        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(multiBuf.toByteArray())) {
            assertTrue(stream.getClass().getSimpleName().contains("Zip"));
            assertArrayEquals(first, ArchiveReadBridge.readAllBytes(stream));
        }

        final byte[] deflatePayload = "payload-for-crc-check".repeat(32).getBytes(StandardCharsets.UTF_8);
        final byte[] valid = buildDeflatedZip("corrupt.xml", deflatePayload, null);

        final byte[] corruptedEocd = valid.clone();
        corruptedEocd[corruptedEocd.length - EOCD_SIGNATURE_OFFSET_FROM_END] ^= CORRUPT_FLIP_MASK;
        try (InputStream stream = ArchiveReadBridge.openZipEntryStream(corruptedEocd)) {
            assertTrue(stream.getClass().getSimpleName().contains("Zip"));
            assertArrayEquals(deflatePayload, ArchiveReadBridge.readAllBytes(stream));
        }

        final byte[] corruptedDeflate = valid.clone();
        corruptedDeflate[DEFLATED_CORRUPT_OFFSET] ^= CORRUPT_FLIP_MASK;
        assertThrows(IOException.class, () -> {
            try (InputStream stream = ArchiveReadBridge.openZipEntryStream(corruptedDeflate)) {
                ArchiveReadBridge.readAllBytes(stream);
            }
        });

        final byte[] badCrcStored = buildStoredZip("crc.bin", "stored-crc-check".getBytes(StandardCharsets.UTF_8));
        badCrcStored[STORED_CORRUPT_OFFSET] ^= CORRUPT_FLIP_MASK;
        assertThrows(ZipException.class, () -> {
            try (InputStream stream = ArchiveReadBridge.openZipEntryStream(badCrcStored)) {
                ArchiveReadBridge.readAllBytes(stream);
            }
        });
    }

    @Test
    void patchesArchiveReaderWithOpenZipEntryStreamAndIsIdempotentAcrossAllTwentyOneJars() throws Exception {
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

                final boolean[] sawOpenZip = new boolean[1];
                final boolean[] sawWrapRaw = new boolean[1];
                final boolean[] sawZipInputStream = new boolean[1];
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
                                        if (!"readFileByFilePath".equals(name)) {
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
                                                if (owner.endsWith("/ArchiveReadBridge")
                                                        && "openZipEntryStream".equals(mName)) {
                                                    sawOpenZip[0] = true;
                                                }
                                                if (owner.endsWith("/ArchiveReadBridge")
                                                        && "wrapRawBytes".equals(mName)) {
                                                    sawWrapRaw[0] = true;
                                                }
                                                if ("java/util/zip/ZipInputStream".equals(owner)) {
                                                    sawZipInputStream[0] = true;
                                                }
                                            }
                                        };
                                    }
                                },
                                ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);

                assertTrue(sawOpenZip[0], "Expected openZipEntryStream call in " + jarPath);
                assertTrue(sawWrapRaw[0], "Expected wrapRawBytes call in " + jarPath);
                assertTrue(!sawZipInputStream[0], "ZipInputStream must be replaced in " + jarPath);
            }
        }
    }

    private static byte[] buildDeflatedZip(final String name, final byte[] payload, final String comment)
            throws IOException {
        final ByteArrayOutputStream buf = new ByteArrayOutputStream();
        try (ZipOutputStream zos = new ZipOutputStream(buf)) {
            if (comment != null) {
                zos.setComment(comment);
            }
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
}
