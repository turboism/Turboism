package dev.turboism.tests.distribution;

import dev.turboism.distribution.FrameworkPackageInspector;
import dev.turboism.distribution.LocalFrameworkPackageInspector;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

abstract class DistributionRegressionSupport {
    @TempDir Path tempDir;

    byte[] validRuntime() throws Exception {
        return FrameworkPackageFixtures.jar("dev/turboism/bootstrap/Agent.class", "runtime");
    }

    byte[] validSdk() throws Exception {
        return FrameworkPackageFixtures.jar("dev/turboism/sdk/Plugin.class", "sdk");
    }

    void assertArtifactRejected(byte[] runtime, byte[] sdk, String code, String path) throws Exception {
        Path input = tempDir.resolve("package-" + System.nanoTime() + ".zip");
        Files.write(input, FrameworkPackageFixtures.frameworkZip(runtime, sdk));
        assertRejected(input, code, path);
    }

    void assertRejected(Path input, String code, String path) {
        FrameworkPackageInspector.Rejected rejected = assertInstanceOf(
            FrameworkPackageInspector.Rejected.class, new LocalFrameworkPackageInspector().inspect(input));
        assertEquals(code, rejected.problems().get(0).code());
        assertEquals(path, rejected.problems().get(0).path());
    }

    static byte[] corruptEntryData(byte[] zip) {
        byte[] copy = zip.clone();
        int local = indexOf(copy, new byte[]{0x50, 0x4b, 0x03, 0x04});
        int nameLength = unsignedShort(copy, local + 26);
        int extraLength = unsignedShort(copy, local + 28);
        copy[local + 30 + nameLength + extraLength] ^= 0x40;
        return copy;
    }

    private static int indexOf(byte[] bytes, byte[] pattern) {
        outer: for (int i = 0; i <= bytes.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) if (bytes[i + j] != pattern[j]) continue outer;
            return i;
        }
        throw new IllegalArgumentException("ZIP signature missing");
    }

    private static int unsignedShort(byte[] bytes, int offset) {
        return (bytes[offset] & 255) | (bytes[offset + 1] & 255) << 8;
    }
}
