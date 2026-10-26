package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.io.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class McpMocPackageTest {
    static final CubismMocExportSettings SETTINGS =
            new CubismMocExportSettings(0, 0, 100, false, false, false, false, true);

    static byte[] moc() {
        var bytes = new byte[64];
        bytes[0] = 'M';
        bytes[1] = 'O';
        bytes[2] = 'C';
        bytes[3] = '3';
        return bytes;
    }

    static CubismMocExport export() {
        return new CubismMocExport(
                moc(),
                List.of(
                        new CubismExportTexture(2, 1, new int[] {0xff123456, 0x80765432}),
                        new CubismExportTexture(1, 2, new int[] {0xffabcdef, 0xff000000})),
                SETTINGS);
    }

    @Test
    void archiveHasOrderedNativeTexturesExactArgbAndSafeResolvedReferencesAndHashes() throws Exception {
        var archive = McpMocPackage.build(export(), new McpMocPackage.Budgets(1024, 4096, 4), Map.of(), "source");
        var entries = unzip(archive.bytes());
        assertEquals(
                Set.of(
                        "model.moc3",
                        "textures/texture_00.png",
                        "textures/texture_01.png",
                        "model.model3.json",
                        "export-manifest.json"),
                entries.keySet());
        assertArrayEquals(moc(), entries.get("model.moc3"));
        var image = ImageIO.read(new ByteArrayInputStream(entries.get("textures/texture_00.png")));
        assertEquals(0xff123456, image.getRGB(0, 0));
        assertEquals(0x80765432, image.getRGB(1, 0));
        var model = Json.parseObject(entries.get("model.model3.json"));
        assertEquals(3, ((Number) model.get("Version")).intValue());
        var refs = (Map<?, ?>) model.get("FileReferences");
        assertTrue(entries.containsKey(refs.get("Moc")));
        assertEquals(List.of("textures/texture_00.png", "textures/texture_01.png"), refs.get("Textures"));
        for (Object raw : (List<?>) archive.manifest().get("artifacts")) {
            var row = (Map<?, ?>) raw;
            var bytes = entries.get(row.get("path"));
            assertEquals(bytes.length, ((Number) row.get("byteLength")).intValue());
            assertEquals(McpMocPackage.hash(bytes), row.get("sha256"));
        }
        assertEquals(McpMocPackage.hash(archive.bytes()), archive.sha256());
        assertEquals(false, archive.manifest().get("fullCharacterDelivery"));
        assertArrayEquals(
                archive.bytes(),
                McpMocPackage.build(export(), new McpMocPackage.Budgets(1024, 4096, 4), Map.of(), "source")
                        .bytes());
    }

    @Test
    void archiveMocAndPixelBudgetsFailBeforeReturningAnyPackage() {
        assertThrows(
                McpMocPackage.Budget.class,
                () -> McpMocPackage.build(export(), new McpMocPackage.Budgets(63, 4096, 4), Map.of(), "source"));
        assertThrows(
                McpMocPackage.Budget.class,
                () -> McpMocPackage.build(export(), new McpMocPackage.Budgets(1024, 4096, 3), Map.of(), "source"));
        assertThrows(
                McpMocPackage.Budget.class,
                () -> McpMocPackage.build(export(), new McpMocPackage.Budgets(1024, 100, 4), Map.of(), "source"));
        var empty = new CubismMocExport(moc(), List.of(), SETTINGS);
        assertThrows(
                McpMocPackage.Budget.class,
                () -> McpMocPackage.build(empty, new McpMocPackage.Budgets(1024, 4096, 4), Map.of(), "source"));
    }

    @Test
    void settingsAreClosedFiniteAndNormalizedAsNativeFloats() {
        var valid = McpMocPackage.project(SETTINGS);
        assertEquals(SETTINGS, McpMocPackage.settings(valid));
        for (Object value : List.of(Double.NaN, Double.POSITIVE_INFINITY, Double.MAX_VALUE, "100", -1, 0, 1e-100)) {
            var map = new LinkedHashMap<>(valid);
            map.put("pixelsPerUnit", value);
            assertThrows(IllegalArgumentException.class, () -> McpMocPackage.settings(map));
        }
        var map = new LinkedHashMap<>(valid);
        map.put("path", "forbidden");
        assertThrows(IllegalArgumentException.class, () -> McpMocPackage.settings(map));
    }

    static Map<String, byte[]> unzip(byte[] bytes) throws IOException {
        var result = new LinkedHashMap<String, byte[]>();
        try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                assertNull(result.put(entry.getName(), zip.readAllBytes()));
            }
        }
        return result;
    }
}
