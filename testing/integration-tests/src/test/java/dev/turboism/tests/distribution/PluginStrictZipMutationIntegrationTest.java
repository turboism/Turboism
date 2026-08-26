package dev.turboism.tests.distribution;

import dev.turboism.distribution.LocalPluginPackageInspector;
import dev.turboism.distribution.PluginPackageInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PluginStrictZipMutationIntegrationTest {
    private static final int[] LOCAL_FIELDS = {14, 18, 22};
    @TempDir Path tempDir;

    @Test void enforcesDescriptorLocalFieldClosureInEveryZipScope() throws Exception {
        assertAccepted(outer(LocalValues.ZERO), "outer zero");
        assertAccepted(outer(LocalValues.CENTRAL), "outer central");
        assertAccepted(main(LocalValues.ZERO), "main zero");
        assertAccepted(main(LocalValues.CENTRAL), "main central");
        assertAccepted(library(LocalValues.ZERO), "library zero");
        assertAccepted(library(LocalValues.CENTRAL), "library central");

        for (int field : LOCAL_FIELDS) {
            assertRejected(outer(LocalValues.mixed(field)),
                "ARCHIVE_LOCAL_CENTRAL_MISMATCH", "plugin/plugin.jar", "outer field " + field);
            assertRejected(main(LocalValues.mixed(field)),
                "ARTIFACT_JAR_INVALID", "plugin/plugin.jar", "main field " + field);
            assertRejected(library(LocalValues.mixed(field)),
                "ARTIFACT_JAR_INVALID", "plugin/lib/sample.jar", "library field " + field);
        }
    }

    private byte[] outer(LocalValues values) throws Exception {
        return rewriteLocal(PluginPackageFixtures.valid(), "plugin/plugin.jar", values);
    }

    private byte[] main(LocalValues values) throws Exception {
        byte[] jar = rewriteLocal(mainJar(), "META-INF/turboism/plugin.json", values);
        return PluginPackageFixtures.packageWith(jar,
            PluginPackageFixtures.ID, PluginPackageFixtures.VERSION);
    }

    private byte[] library(LocalValues values) throws Exception {
        byte[] jar = PluginPackageFixtures.jarEntries("sample/Library.class", "library");
        jar = rewriteLocal(jar, "sample/Library.class", values);
        return PluginPackageFixtures.withLibraries(mainJar(), Map.of("sample.jar", jar));
    }

    private byte[] mainJar() throws Exception {
        return PluginPackageFixtures.jar(
            PluginPackageFixtures.descriptor(PluginPackageFixtures.ID,
                PluginPackageFixtures.VERSION, "0.1.0"),
            PluginPackageFixtures.ENTRYPOINT.replace('.', '/') + ".class", "class");
    }

    private void assertAccepted(byte[] bytes, String label) throws Exception {
        assertInstanceOf(PluginPackageInspector.Accepted.class, inspect(bytes, label), label);
    }

    private void assertRejected(byte[] bytes, String code, String path, String label) throws Exception {
        PluginPackageInspector.Rejected rejected = assertInstanceOf(PluginPackageInspector.Rejected.class,
            inspect(bytes, label), label);
        assertEquals(code, rejected.problems().get(0).code(), label);
        assertEquals(path, rejected.problems().get(0).path(), label);
    }

    private PluginPackageInspector.Result inspect(byte[] bytes, String label) throws Exception {
        Path input = tempDir.resolve(Integer.toHexString(label.hashCode()) + ".tplugin");
        Files.write(input, bytes);
        return new LocalPluginPackageInspector().inspect(input);
    }

    private static byte[] rewriteLocal(byte[] source, String entryName, LocalValues values) {
        byte[] bytes = source.clone();
        int central = (int) uint(bytes, bytes.length - 6);
        int count = ushort(bytes, bytes.length - 12);
        for (int index = 0; index < count; index++) {
            int nameLength = ushort(bytes, central + 28);
            int extraLength = ushort(bytes, central + 30);
            int commentLength = ushort(bytes, central + 32);
            String name = new String(bytes, central + 46, nameLength, StandardCharsets.UTF_8);
            if (name.equals(entryName)) {
                assertTrue((ushort(bytes, central + 8) & 8) != 0, "fixture must use bit 3");
                int local = (int) uint(bytes, central + 42);
                values.write(bytes, local, central);
                return bytes;
            }
            central += 46 + nameLength + extraLength + commentLength;
        }
        throw new IllegalArgumentException("missing ZIP entry " + entryName);
    }

    private static int ushort(byte[] bytes, int at) {
        return (bytes[at] & 255) | (bytes[at + 1] & 255) << 8;
    }

    private static long uint(byte[] bytes, int at) {
        return (bytes[at] & 255L) | (bytes[at + 1] & 255L) << 8
            | (bytes[at + 2] & 255L) << 16 | (bytes[at + 3] & 255L) << 24;
    }

    private static void putInt(byte[] bytes, int at, long value) {
        for (int index = 0; index < 4; index++) bytes[at + index] = (byte) (value >>> (8 * index));
    }

    private record LocalValues(long crc, long compressed, long expanded) {
        private static final LocalValues ZERO = new LocalValues(0, 0, 0);
        private static final LocalValues CENTRAL = new LocalValues(-1, -1, -1);

        private static LocalValues mixed(int field) {
            return switch (field) {
                case 14 -> new LocalValues(1, 0, 0);
                case 18 -> new LocalValues(0, 1, 0);
                case 22 -> new LocalValues(0, 0, 1);
                default -> throw new IllegalArgumentException("unknown local field " + field);
            };
        }

        private void write(byte[] bytes, int local, int central) {
            putInt(bytes, local + 14, crc < 0 ? uint(bytes, central + 16) : crc);
            putInt(bytes, local + 18, compressed < 0 ? uint(bytes, central + 20) : compressed);
            putInt(bytes, local + 22, expanded < 0 ? uint(bytes, central + 24) : expanded);
        }
    }
}
