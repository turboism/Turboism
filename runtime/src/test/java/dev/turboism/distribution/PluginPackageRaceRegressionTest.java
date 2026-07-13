package dev.turboism.distribution;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

class PluginPackageRaceRegressionTest {
    @TempDir Path tempDir;

    @Test void rejectsAbaReplacementAfterHashAndAfterInspection() throws Exception {
        byte[] one = fixture("one");
        byte[] two = fixture("two");
        Path input = tempDir.resolve("sample.tplugin");
        Files.write(input, one);
        assertChanged(new LocalPluginPackageInspector(TestPackageAccess.replaceAfterHash(input, two)).inspect(input));
        Files.write(input, one);
        assertChanged(new LocalPluginPackageInspector(TestPackageAccess.replaceAfterInspection(input, two)).inspect(input));
    }

    @Test void rejectsAbaReplacementDuringSnapshotCopy() throws Exception {
        byte[] first = fixture("first");
        byte[] second = fixture("second");
        Path input = tempDir.resolve("snapshot-race.tplugin");
        Files.write(input, first);
        PackageAccess access = new PackageAccess() {
            @Override public java.io.InputStream open(Path path) throws java.io.IOException {
                byte[] mixed = java.util.Arrays.copyOf(first, first.length);
                System.arraycopy(second, 0, mixed, 0, Math.min(mixed.length, second.length) / 2);
                Files.write(input, second);
                return new java.io.ByteArrayInputStream(mixed) {
                    @Override public void close() throws java.io.IOException {
                        super.close();
                        Files.write(input, first);
                    }
                };
            }
        };
        assertChanged(new LocalPluginPackageInspector(access).inspect(input));
    }

    @Test void rejectsSymlinkWithoutFollowing() throws Exception {
        Path target = tempDir.resolve("target.tplugin");
        Files.write(target, fixture("target"));
        Path link = tempDir.resolve("link.tplugin");
        Files.createSymbolicLink(link, target.getFileName());
        PluginPackageInspector.Rejected rejected = assertInstanceOf(PluginPackageInspector.Rejected.class,
            new LocalPluginPackageInspector().inspect(link));
        assertEquals(DistributionErrors.PACKAGE_PATH_INVALID, rejected.problems().get(0).code());
    }

    @Test void fifoIsRejectedWithoutOpening() throws Exception {
        Path fifo = tempDir.resolve("sample.tplugin");
        Process process = new ProcessBuilder("mkfifo", fifo.toString()).start();
        assertEquals(0, process.waitFor());
        assertTimeoutPreemptively(java.time.Duration.ofSeconds(2), () -> {
            PluginPackageInspector.Rejected rejected = assertInstanceOf(PluginPackageInspector.Rejected.class,
                new LocalPluginPackageInspector().inspect(fifo));
            assertEquals(DistributionErrors.PACKAGE_PATH_INVALID, rejected.problems().get(0).code());
        });
    }

    private static void assertChanged(PluginPackageInspector.Result result) {
        PluginPackageInspector.Rejected rejected = assertInstanceOf(PluginPackageInspector.Rejected.class, result);
        assertEquals(DistributionErrors.PACKAGE_CHANGED, rejected.problems().get(0).code());
    }

    private static byte[] fixture(String marker) throws Exception {
        String id = "dev.turboism.plugin.sample";
        String entrypoint = "dev.turboism.plugin.sample.SamplePlugin";
        String descriptor = "{\"format\":\"turboism.plugin.meta\",\"schemaVersion\":1,\"id\":\"" + id
            + "\",\"name\":\"Sample\",\"version\":\"0.1.0\",\"entrypoints\":{\"plugin\":\""
            + entrypoint + "\"},\"turboismApi\":\"0.1.0\"}";
        ByteArrayOutputStream jarBytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(jarBytes)) {
            add(jar, "META-INF/turboism/plugin.json", descriptor.getBytes(StandardCharsets.UTF_8));
            add(jar, entrypoint.replace('.', '/') + ".class", marker.getBytes(StandardCharsets.UTF_8));
        }
        byte[] payload = jarBytes.toByteArray();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        Map<String, Object> manifest = new LinkedHashMap<>();
        manifest.put("createdAt", "2026-07-12T18:00:00Z");
        manifest.put("files", List.of(Map.of("path", "plugin/plugin.jar", "role", "PLUGIN_JAR",
            "sha256", hash, "size", payload.length)));
        manifest.put("format", "turboism.distribution.plugin-package");
        manifest.put("packageId", id);
        manifest.put("packageKind", "PLUGIN");
        manifest.put("pluginDescriptorPath", "plugin/plugin.jar!/META-INF/turboism/plugin.json");
        manifest.put("pluginDescriptorSha256", sha256(descriptor.getBytes(StandardCharsets.UTF_8)));
        manifest.put("schemaVersion", 1);
        manifest.put("version", "0.1.0");
        ObjectMapper canonical = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
        manifest.put("packageHash", sha256(canonical.writeValueAsBytes(manifest)));
        byte[] manifestBytes = canonical.writeValueAsBytes(manifest);
        ByteArrayOutputStream archive = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(archive)) {
            add(zip, PluginManifestReader.NAME, manifestBytes);
            add(zip, "plugin/plugin.jar", payload);
        }
        return archive.toByteArray();
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static void add(ZipOutputStream zip, String name, byte[] bytes) throws Exception {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(bytes);
        zip.closeEntry();
    }
}
