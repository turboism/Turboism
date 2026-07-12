package dev.turboism.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class OracleStrictFinalBlockersTest {
    @TempDir Path tempDir;

    @Test void acceptsStoredAndDeflatedPayloadsContainingEocdSignature() throws Exception {
        byte[] payload = new byte[]{'P', 'K', 0x05, 0x06};
        assertEquals(List.of("dev/turboism/bootstrap/Agent.class"),
            NestedZipDirectory.parse(storedJarBytes("dev/turboism/bootstrap/Agent.class", payload), "stored"));
        assertEquals(List.of("dev/turboism/bootstrap/Agent.class"),
            NestedZipDirectory.parse(PackageTestFixtures.jarBytes(
                "dev/turboism/bootstrap/Agent.class", payload), "deflated"));
    }

    @Test void acceptsFrameworkRequiredClassesWithEocdSignaturePayloads() throws Exception {
        byte[] payload = new byte[]{'P', 'K', 0x05, 0x06};
        byte[] runtime = storedJarBytes("dev/turboism/bootstrap/Agent.class", payload);
        byte[] sdk = PackageTestFixtures.jarBytes("dev/turboism/sdk/Plugin.class", payload);
        Path input = tempDir.resolve("signature-payload.zip");
        Files.write(input, PackageTestFixtures.framework(runtime, sdk));

        assertInstanceOf(FrameworkPackageInspector.Accepted.class,
            new LocalFrameworkPackageInspector().inspect(input));
    }

    @Test void rejectsSecondCompleteCentralDirectoryAndEocd() throws Exception {
        byte[] jar = PackageTestFixtures.jarBytes("dev/turboism/bootstrap/Agent.class", "one");
        int central = centralOffset(jar);
        byte[] suffix = Arrays.copyOfRange(jar, central, jar.length);
        byte[] forged = new byte[jar.length + suffix.length];
        System.arraycopy(jar, 0, forged, 0, jar.length);
        System.arraycopy(suffix, 0, forged, jar.length, suffix.length);
        int secondEocd = forged.length - 22;
        putInt(forged, secondEocd + 16, jar.length);
        assertJarRejected(forged);
    }

    @Test void rejectsGapBeforeCentralDirectory() throws Exception {
        byte[] jar = PackageTestFixtures.jarBytes("dev/turboism/bootstrap/Agent.class", "one");
        int central = centralOffset(jar);
        byte[] forged = new byte[jar.length + 1];
        System.arraycopy(jar, 0, forged, 0, central);
        forged[central] = 7;
        System.arraycopy(jar, central, forged, central + 1, jar.length - central);
        putInt(forged, forged.length - 22 + 16, central + 1);
        assertJarRejected(forged);
    }

    @Test void rejectsAbaReplacementDuringSnapshotCopy() throws Exception {
        Path input = tempDir.resolve("package.zip");
        byte[] a = PackageTestFixtures.framework("a");
        byte[] b = PackageTestFixtures.framework("b");
        Files.write(input, a);
        PackageAccess access = new PackageAccess() {
            @Override public java.io.InputStream open(Path path) throws java.io.IOException {
                byte[] mixed = Arrays.copyOf(a, a.length);
                System.arraycopy(b, 0, mixed, 0, Math.min(mixed.length, b.length) / 2);
                Files.write(input, b);
                return new java.io.ByteArrayInputStream(mixed) {
                    @Override public void close() throws java.io.IOException {
                        super.close();
                        Files.write(input, a);
                    }
                };
            }
        };
        var result = new LocalFrameworkPackageInspector(access).inspect(input);
        var rejected = assertInstanceOf(FrameworkPackageInspector.Rejected.class, result);
        assertEquals(DistributionErrors.PACKAGE_CHANGED, rejected.problems().get(0).code());
    }

    @Test void fifoIsRejectedWithoutOpeningStream() throws Exception {
        Path fifo = tempDir.resolve("package.fifo");
        Process process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertEquals(0, process.waitFor());
        assertTimeoutPreemptively(Duration.ofSeconds(2), () -> {
            var result = new LocalFrameworkPackageInspector().inspect(fifo);
            var rejected = assertInstanceOf(FrameworkPackageInspector.Rejected.class, result);
            assertEquals(DistributionErrors.PACKAGE_PATH_INVALID, rejected.problems().get(0).code());
        });
    }

    private static byte[] storedJarBytes(String name, byte[] payload) throws Exception {
        CRC32 crc = new CRC32();
        crc.update(payload);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (java.util.jar.JarOutputStream jar = new java.util.jar.JarOutputStream(output)) {
            ZipEntry entry = new ZipEntry(name);
            entry.setMethod(ZipEntry.STORED);
            entry.setSize(payload.length);
            entry.setCompressedSize(payload.length);
            entry.setCrc(crc.getValue());
            jar.putNextEntry(entry);
            jar.write(payload);
            jar.closeEntry();
        }
        return output.toByteArray();
    }

    private static void assertJarRejected(byte[] bytes) throws Exception {
        try {
            NestedZipDirectory.parse(bytes, "artifact");
        } catch (DistributionValidationException expected) {
            assertEquals(DistributionErrors.JAR_INVALID, expected.code());
            return;
        }
        throw new AssertionError("expected nested JAR rejection");
    }

    private static int centralOffset(byte[] bytes) {
        return (int) uint(bytes, bytes.length - 22 + 16);
    }

    private static long uint(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static void putInt(byte[] bytes, int at, int value) {
        for (int i = 0; i < 4; i++) bytes[at + i] = (byte) (value >>> (8 * i));
    }
}
