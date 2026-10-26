package dev.turboism.plugin.mcp;

import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.json.Json;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageOutputStream;

/** Owned official bytes to a bounded deterministic archive. No host/file access or dynamic paths. */
final class McpMocPackage {
    static final int MAX_ARCHIVE = 128 * 1024 * 1024, MAX_MOC = 64 * 1024 * 1024;
    static final long MAX_PIXELS = CubismMocExport.MAX_TOTAL_TEXTURE_PIXELS;

    record Budgets(int mocBytes, int archiveBytes, long pixels) {}

    record Archive(byte[] bytes, Map<String, Object> manifest, String sha256) {}

    static CubismMocExportSettings settings(Object raw) {
        var row = McpAtlasLayout.fields(
                raw,
                "originX",
                "originY",
                "pixelsPerUnit",
                "includeInvisibleParts",
                "includeSketchParts",
                "includeInvisibleMeshes",
                "includeInvisibleAliases",
                "extendEdgeColors");
        return new CubismMocExportSettings(
                number(row.get("originX")),
                number(row.get("originY")),
                number(row.get("pixelsPerUnit")),
                bool(row.get("includeInvisibleParts")),
                bool(row.get("includeSketchParts")),
                bool(row.get("includeInvisibleMeshes")),
                bool(row.get("includeInvisibleAliases")),
                bool(row.get("extendEdgeColors")));
    }

    static Map<String, Object> project(CubismMocExportSettings s) {
        return Map.of(
                "originX",
                s.originX(),
                "originY",
                s.originY(),
                "pixelsPerUnit",
                s.pixelsPerUnit(),
                "includeInvisibleParts",
                s.includeInvisibleParts(),
                "includeSketchParts",
                s.includeSketchParts(),
                "includeInvisibleMeshes",
                s.includeInvisibleMeshes(),
                "includeInvisibleAliases",
                s.includeInvisibleAliases(),
                "extendEdgeColors",
                s.extendEdgeColors());
    }

    static Archive build(CubismMocExport export, Budgets budgets, Map<String, Object> state, String sourceHash)
            throws IOException {
        byte[] moc = export.moc3();
        if (moc.length > budgets.mocBytes()
                || moc.length > MAX_MOC
                || export.textures().isEmpty()) throw new Budget();
        long pixels = 0;
        for (var texture : export.textures()) pixels += (long) texture.width() * texture.height();
        if (pixels > budgets.pixels() || pixels > MAX_PIXELS) throw new Budget();
        var out = new Bounded(budgets.archiveBytes());
        var entries = new ArrayList<Map<String, Object>>();
        var references = new ArrayList<String>();
        try (var zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            entry(zip, "model.moc3", moc);
            entries.add(metadata("model.moc3", "application/octet-stream", moc, null, null, null));
            for (int i = 0; i < export.textures().size(); i++) {
                var texture = export.textures().get(i);
                String path = String.format(Locale.ROOT, "textures/texture_%02d.png", i);
                byte[] png = png(texture, budgets.archiveBytes());
                entry(zip, path, png);
                references.add(path);
                entries.add(metadata(path, "image/png", png, i, texture.width(), texture.height()));
            }
            var model = Map.<String, Object>of(
                    "Version", 3, "FileReferences", Map.of("Moc", "model.moc3", "Textures", List.copyOf(references)));
            byte[] modelBytes = json(model);
            entry(zip, "model.model3.json", modelBytes);
            entries.add(metadata("model.model3.json", "application/json", modelBytes, null, null, null));
            var manifest = Map.<String, Object>of(
                    "schemaVersion",
                    1,
                    "format",
                    "MOC_5_3_STATIC_PACKAGE",
                    "sourceState",
                    state,
                    "sourceHash",
                    sourceHash,
                    "settings",
                    project(export.settings()),
                    "artifacts",
                    List.copyOf(entries),
                    "mocValidation",
                    Map.of("version", "V5_3", "consistency", "CONSISTENT"),
                    "fullCharacterDelivery",
                    false,
                    "omittedAssets",
                    List.of("physics", "motions", "expressions", "pose", "displayInfo", "eyeBlink/lipSyncGroups"));
            entry(zip, "export-manifest.json", json(manifest));
            zip.finish();
            byte[] bytes = out.toByteArray();
            return new Archive(bytes, manifest, hash(bytes));
        }
    }

    private static byte[] png(CubismExportTexture texture, int limit) throws IOException {
        var image = new BufferedImage(texture.width(), texture.height(), BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, texture.width(), texture.height(), texture.argb(), 0, texture.width());
        var out = new Bounded(limit);
        var writers = ImageIO.getImageWritersByFormatName("png");
        if (!writers.hasNext()) throw new IOException("PNG encoder unavailable");
        var writer = writers.next();
        try (var stream = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(stream);
            writer.write(image);
            stream.flush();
        } finally {
            writer.dispose();
            image.flush();
        }
        return out.toByteArray();
    }

    private static void entry(ZipOutputStream zip, String path, byte[] bytes) throws IOException {
        var entry = new ZipEntry(path);
        entry.setTime(0);
        zip.putNextEntry(entry);
        zip.write(bytes);
        zip.closeEntry();
    }

    private static Map<String, Object> metadata(
            String path, String mime, byte[] bytes, Integer index, Integer width, Integer height) {
        var result = new LinkedHashMap<String, Object>();
        result.put("path", path);
        result.put("mimeType", mime);
        result.put("byteLength", bytes.length);
        result.put("sha256", hash(bytes));
        result.put("textureIndex", index);
        result.put("width", width);
        result.put("height", height);
        return Collections.unmodifiableMap(result);
    }

    private static byte[] json(Map<String, Object> value) {
        @SuppressWarnings("unchecked")
        var sorted = (Map<String, Object>) canonical(value);
        return Json.stringify(sorted).getBytes(StandardCharsets.UTF_8);
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var result = new TreeMap<String, Object>();
            map.forEach((key, item) -> result.put((String) key, canonical(item)));
            return result;
        }
        if (value instanceof List<?> list)
            return list.stream().map(McpMocPackage::canonical).toList();
        return value;
    }

    static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static float number(Object value) {
        if (!(value instanceof Number n) || !Double.isFinite(n.doubleValue()) || !Float.isFinite(n.floatValue()))
            throw new IllegalArgumentException();
        return n.floatValue();
    }

    private static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw new IllegalArgumentException();
        return b;
    }

    private static final class Bounded extends OutputStream {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final int limit;

        Bounded(int limit) {
            if (limit < 1 || limit > MAX_ARCHIVE) throw new IllegalArgumentException();
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            if (bytes.size() >= limit) throw new Budget();
            bytes.write(value);
        }

        @Override
        public void write(byte[] value, int off, int length) throws IOException {
            Objects.checkFromIndexSize(off, length, value.length);
            if ((long) bytes.size() + length > limit) throw new Budget();
            bytes.write(value, off, length);
        }

        byte[] toByteArray() {
            return bytes.toByteArray();
        }
    }

    static final class Budget extends IOException {}
}
