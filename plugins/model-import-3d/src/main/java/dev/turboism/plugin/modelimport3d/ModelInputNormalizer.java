package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.net.URI;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Deterministic, bounded input normalization. Never accesses paths or the network. */
final class ModelInputNormalizer {
    private ModelInputNormalizer() {}

    private static final int LIMIT = GlbSurfaceReader.MAX_BYTES;
    private static final int MAX_EXTERNAL_RESOURCES = 64;
    private static final int MAX_ARCHIVE_ENTRIES = 128;
    private static final int MAX_GLTF_JSON_BYTES = 2 * 1024 * 1024;
    private static final int MAX_GLTF_BUFFERS = 32;
    private static final int MAX_GLTF_BUFFER_VIEWS = 4096;
    private static final int MAX_GLTF_IMAGES = 32;
    private static final int MAX_PATH_LENGTH = 1024;
    private static final int ASCII_CONTROL_LIMIT = 32;

    private static final int MAGIC_PREFIX_BYTES = 4;
    private static final int ALIGNMENT_BYTES = 4;
    private static final int GLB_MAGIC = 0x46546c67;
    private static final int GLB_VERSION = 2;
    private static final int GLB_MIN_BYTES = 28;
    private static final int GLB_JSON_CHUNK_HEADER_OFFSET = 20;
    private static final int GLB_CHUNK_HEADER_BYTES = 8;
    private static final int GLB_JSON_CHUNK_TYPE = 0x4e4f534a;
    private static final int GLB_BIN_CHUNK_TYPE = 0x004e4942;

    private static final int ZIP_EOCD_SIGNATURE = 0x06054b50;
    private static final int ZIP_EOCD_MIN_BYTES = 22;
    private static final int ZIP_EOCD_SEARCH_WINDOW = 65_557;
    private static final int ZIP_EOCD_DISK_OFFSET = 4;
    private static final int ZIP_EOCD_CD_DISK_OFFSET = 6;
    private static final int ZIP_EOCD_DISK_ENTRIES_OFFSET = 8;
    private static final int ZIP_EOCD_TOTAL_ENTRIES_OFFSET = 10;
    private static final int ZIP_EOCD_CD_SIZE_OFFSET = 12;
    private static final int ZIP_EOCD_CD_OFFSET = 16;
    private static final int ZIP_EOCD_COMMENT_LEN_OFFSET = 20;

    private static final int DATA_URI_SCHEME_PREFIX_LEN = "data:".length();
    private static final int DATA_URI_BASE64_SUFFIX_LEN = ";base64".length();

    static final class MissingResource extends IllegalArgumentException {
        final String key, reference;

        MissingResource(String key, String reference) {
            super("Model is missing referenced resource: " + reference + ". Select the file or package it in a ZIP.");
            this.key = key;
            this.reference = reference;
        }
    }

    /** Resources are separately granted bytes, not paths to read from the filesystem. */
    static byte[] normalizeSelected(byte[] input, Map<String, byte[]> resources) throws IOException {
        require(input != null && input.length > 0 && input.length <= LIMIT, "Model input exceeds the 16 MiB limit.");
        long total = input.length;
        require(resources.size() <= MAX_EXTERNAL_RESOURCES, "External resources exceed the 64-file limit.");
        for (var entry : resources.entrySet()) {
            require(packagePath("", entry.getKey()).equals(entry.getKey()), "Invalid external resource identifier.");
            require(entry.getValue() != null, "External resource content is null.");
            total += entry.getValue().length;
        }
        require(total <= LIMIT, "Total size of model and external resources exceeds the 16 MiB limit.");
        return dispatchNormalization(input, resources, "model/");
    }

    static byte[] normalize(byte[] input) throws IOException {
        require(input != null && input.length > 0 && input.length <= LIMIT, "Model input exceeds the 16 MiB limit.");
        return dispatchNormalization(input, Map.of(), "");
    }

    private static byte[] dispatchNormalization(byte[] input, Map<String, byte[]> resources, String base)
            throws IOException {
        if (input.length >= MAGIC_PREFIX_BYTES
                && ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN).getInt() == GLB_MAGIC) {
            return glb(input, resources, base);
        }
        if (input.length >= MAGIC_PREFIX_BYTES && input[0] == 'P' && input[1] == 'K') {
            return archive(input);
        }
        int at = 0;
        while (at < input.length && Character.isWhitespace((char) (input[at] & 0xFF))) at++;
        return at < input.length && input[at] == '{'
                ? gltf(input, resources, base)
                : ObjModelInput.normalize(input, resources, base);
    }

    private static byte[] glb(byte[] input) throws IOException {
        return glb(input, Map.of(), "");
    }

    @SuppressWarnings("unchecked")
    private static byte[] glb(byte[] input, Map<String, byte[]> resources, String base) throws IOException {
        require(input.length >= GLB_MIN_BYTES, "Incomplete GLB header.");
        var bytes = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        require(
                bytes.getInt() == GLB_MAGIC && bytes.getInt() == GLB_VERSION && bytes.getInt() == input.length,
                "Invalid GLB header or length.");
        int length = bytes.getInt();
        require(
                bytes.getInt() == GLB_JSON_CHUNK_TYPE
                        && length > 0
                        && length <= MAX_GLTF_JSON_BYTES
                        && length % ALIGNMENT_BYTES == 0
                        && length <= bytes.remaining() - GLB_CHUNK_HEADER_BYTES,
                "Invalid GLB JSON chunk range.");
        byte[] text = new byte[length];
        bytes.get(text);
        Object value = Json.parseObject(text);
        require(value instanceof Map<?, ?>, "GLB JSON root must be an object.");
        var doc = (Map<String, Object>) value;
        int binLength = bytes.getInt();
        require(bytes.getInt() == GLB_BIN_CHUNK_TYPE && binLength == bytes.remaining(), "Invalid GLB BIN chunk range.");
        var images = objects(doc.getOrDefault("images", List.of()), "GLB images");
        boolean convert = images.stream()
                .anyMatch(image -> "image/jpeg".equals(image.get("mimeType")) || image.containsKey("uri"));
        if (!convert) return input.clone();
        byte[] binary = new byte[binLength];
        bytes.get(binary);
        var views = objects(doc.get("bufferViews"), "GLB bufferViews");
        for (var image : images)
            if ("image/jpeg".equals(image.get("mimeType"))) {
                if (image.containsKey("uri")) continue;
                int view = number(image.get("bufferView"));
                require(view < views.size(), "GLB image bufferView index out of range.");
                var entry = views.get(view);
                require(number(entry.get("buffer")) == 0, "GLB texture must reference embedded buffer 0.");
                int offset = number(entry.getOrDefault("byteOffset", 0)), size = number(entry.get("byteLength"));
                require((long) offset + size <= binary.length, "GLB texture bufferView range out of bounds.");
                image.remove("bufferView");
                image.put(
                        "uri",
                        "data:image/jpeg;base64,"
                                + Base64.getEncoder()
                                        .encodeToString(Arrays.copyOfRange(binary, offset, offset + size)));
            }
        doc.put(
                "buffers",
                List.of(Map.of(
                        "byteLength",
                        binary.length,
                        "uri",
                        "data:application/octet-stream;base64,"
                                + Base64.getEncoder().encodeToString(binary))));
        return gltf(Json.bytes(doc), resources, base);
    }

    private static byte[] archive(byte[] input) throws IOException {
        int expected = archiveEntryCount(input);
        var files = new TreeMap<String, byte[]>();
        int total = 0, entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(input), StandardCharsets.UTF_8)) {
            for (ZipEntry e; (e = zip.getNextEntry()) != null; ) {
                require(++entries <= MAX_ARCHIVE_ENTRIES, "Model archive exceeds the 128-entry limit.");
                String path = path(e.getName());
                if (e.isDirectory()) continue;
                require(!files.containsKey(path), "Model archive contains duplicate paths.");
                byte[] bytes = zip.readNBytes(LIMIT - total + 1);
                total += bytes.length;
                require(total <= LIMIT, "Uncompressed model archive exceeds the 16 MiB limit.");
                files.put(path, bytes);
                zip.closeEntry();
            }
        }
        require(entries == expected, "ZIP entry count does not match central directory.");
        var models = files.keySet().stream()
                .filter(p -> p.toLowerCase(Locale.ROOT).endsWith(".gltf")
                        || p.toLowerCase(Locale.ROOT).endsWith(".glb")
                        || p.toLowerCase(Locale.ROOT).endsWith(".obj"))
                .toList();
        require(models.size() == 1, "Model archive must contain exactly one GLB, glTF, or OBJ model.");
        String model = models.get(0);
        if (model.toLowerCase(Locale.ROOT).endsWith(".glb")) {
            byte[] glb = files.get(model);
            require(
                    glb.length >= MAGIC_PREFIX_BYTES
                            && ByteBuffer.wrap(glb)
                                            .order(ByteOrder.LITTLE_ENDIAN)
                                            .getInt()
                                    == GLB_MAGIC,
                    "Invalid GLB format inside archive.");
            return glb(glb);
        }
        int slash = model.lastIndexOf('/');
        String base = slash < 0 ? "" : model.substring(0, slash + 1);
        return model.toLowerCase(Locale.ROOT).endsWith(".obj")
                ? ObjModelInput.normalize(files.get(model), files, base)
                : gltf(files.get(model), files, base);
    }

    private static int archiveEntryCount(byte[] input) {
        var b = ByteBuffer.wrap(input).order(ByteOrder.LITTLE_ENDIAN);
        for (int end = input.length - ZIP_EOCD_MIN_BYTES;
                end >= Math.max(0, input.length - ZIP_EOCD_SEARCH_WINDOW);
                end--)
            if (b.getInt(end) == ZIP_EOCD_SIGNATURE) {
                int comment = Short.toUnsignedInt(b.getShort(end + ZIP_EOCD_COMMENT_LEN_OFFSET));
                if (end + ZIP_EOCD_MIN_BYTES + comment != input.length) continue;
                int entries = Short.toUnsignedInt(b.getShort(end + ZIP_EOCD_TOTAL_ENTRIES_OFFSET));
                require(
                        b.getShort(end + ZIP_EOCD_DISK_OFFSET) == 0
                                && b.getShort(end + ZIP_EOCD_CD_DISK_OFFSET) == 0
                                && Short.toUnsignedInt(b.getShort(end + ZIP_EOCD_DISK_ENTRIES_OFFSET)) == entries,
                        "Multi-volume ZIP archives are not supported.");
                long length = Integer.toUnsignedLong(b.getInt(end + ZIP_EOCD_CD_SIZE_OFFSET)),
                        offset = Integer.toUnsignedLong(b.getInt(end + ZIP_EOCD_CD_OFFSET));
                require(
                        entries > 0 && entries <= MAX_ARCHIVE_ENTRIES && offset + length == end,
                        "ZIP central directory is incomplete or exceeds entry limit.");
                return entries;
            }
        throw new IllegalArgumentException("Incomplete ZIP archive.");
    }

    @SuppressWarnings("unchecked")
    static byte[] gltf(byte[] json, Map<String, byte[]> files, String base) throws IOException {
        require(json.length <= LIMIT, "glTF JSON exceeds the 16 MiB limit.");
        Object parsed = Json.parseObject(json);
        require(parsed instanceof Map<?, ?>, "glTF root must be a JSON object.");
        var doc = (Map<String, Object>) parsed;
        var buffers = objects(doc.get("buffers"), "glTF buffers");
        require(!buffers.isEmpty() && buffers.size() <= MAX_GLTF_BUFFERS, "glTF buffer count exceeds limit.");
        var binary = new ByteArrayOutputStream();
        var offsets = new ArrayList<Integer>();
        var lengths = new ArrayList<Integer>();
        for (var buffer : buffers) {
            byte[] data = resource(buffer.get("uri"), files, base);
            int declared = number(buffer.get("byteLength"));
            require(declared == data.length, "glTF buffer length mismatch.");
            align(binary);
            offsets.add(binary.size());
            lengths.add(declared);
            append(binary, data);
        }
        var views = objects(doc.getOrDefault("bufferViews", new ArrayList<>()), "glTF bufferViews");
        require(views.size() <= MAX_GLTF_BUFFER_VIEWS, "glTF bufferView count exceeds limit.");
        for (var view : views) {
            int buffer = number(view.get("buffer"));
            require(buffer < offsets.size(), "glTF bufferView buffer index out of range.");
            int offset = number(view.getOrDefault("byteOffset", 0)), length = number(view.get("byteLength"));
            require((long) offset + length <= lengths.get(buffer), "glTF bufferView range out of bounds.");
            view.put("buffer", 0);
            view.put("byteOffset", offsets.get(buffer) + offset);
        }
        var images = objects(doc.getOrDefault("images", new ArrayList<>()), "glTF images");
        require(images.size() <= MAX_GLTF_IMAGES, "glTF image count exceeds limit.");
        for (var image : images)
            if (image.containsKey("uri")) {
                require(!image.containsKey("bufferView"), "glTF image contains both URI and bufferView.");
                byte[] encoded = resource(image.remove("uri"), files, base);
                if (image.containsKey("mimeType"))
                    require(
                            Set.of("image/png", "image/jpeg").contains(image.get("mimeType")),
                            "Unsupported texture MIME type.");
                byte[] png = ModelTextureNormalizer.png(encoded);
                align(binary);
                int offset = binary.size();
                append(binary, png);
                image.put("bufferView", views.size());
                image.put("mimeType", "image/png");
                views.add(new LinkedHashMap<>(Map.of("buffer", 0, "byteOffset", offset, "byteLength", png.length)));
            }
        doc.put("bufferViews", views);
        doc.put("buffers", List.of(Map.of("byteLength", binary.size())));
        byte[] text = Json.bytes((Map<String, Object>) canonical(doc));
        require(text.length <= MAX_GLTF_JSON_BYTES, "Normalized glTF JSON exceeds size limit.");
        int padded = (text.length + (ALIGNMENT_BYTES - 1)) & ~(ALIGNMENT_BYTES - 1);
        align(binary);
        byte[] bin = binary.toByteArray();
        require((long) GLB_MIN_BYTES + padded + bin.length <= LIMIT, "Normalized GLB exceeds the 16 MiB limit.");
        var out = ByteBuffer.allocate(GLB_MIN_BYTES + padded + bin.length).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(GLB_MAGIC)
                .putInt(GLB_VERSION)
                .putInt(out.capacity())
                .putInt(padded)
                .putInt(GLB_JSON_CHUNK_TYPE)
                .put(text);
        while (out.position() < GLB_JSON_CHUNK_HEADER_OFFSET + padded) out.put((byte) ' ');
        out.putInt(bin.length).putInt(GLB_BIN_CHUNK_TYPE).put(bin);
        return out.array();
    }

    private static byte[] resource(Object value, Map<String, byte[]> files, String base) {
        require(value instanceof String, "glTF resource lacks URI.");
        String uri = (String) value;
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            require(comma > 0 && uri.substring(0, comma).endsWith(";base64"), "Only base64 data URIs are supported.");
            String mime = uri.substring(DATA_URI_SCHEME_PREFIX_LEN, comma - DATA_URI_BASE64_SUFFIX_LEN);
            require(
                    Set.of("application/octet-stream", "application/gltf-buffer", "image/png", "image/jpeg")
                            .contains(mime),
                    "Unsupported data URI MIME type.");
            byte[] data = Base64.getDecoder().decode(uri.substring(comma + 1));
            require(data.length <= LIMIT, "Decoded data URI exceeds size limit.");
            return data;
        }
        URI parsed = URI.create(uri);
        require(
                !parsed.isAbsolute()
                        && parsed.getRawAuthority() == null
                        && parsed.getRawQuery() == null
                        && parsed.getRawFragment() == null,
                "Model resource must be a relative path inside the package.");
        return packageResource(files, base, parsed.getPath());
    }

    static byte[] packageResource(Map<String, byte[]> files, String base, String name) {
        String resolved = packagePath(base, name);
        byte[] bytes = files.get(resolved);
        if (bytes == null) throw new MissingResource(resolved, name);
        return bytes;
    }

    static String packagePath(String base, String name) {
        require(
                name != null
                        && !name.isEmpty()
                        && name.length() <= MAX_PATH_LENGTH
                        && !name.startsWith("/")
                        && !name.startsWith("\\")
                        && !name.contains(":"),
                "Invalid model resource path.");
        var parts = new ArrayDeque<String>();
        for (String part : (base + name.replace('\\', '/')).split("/", -1)) {
            require(part.chars().noneMatch(c -> c < ASCII_CONTROL_LIMIT), "Invalid model resource path.");
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) {
                require(!parts.isEmpty(), "Model resource path escapes the selected ZIP.");
                parts.removeLast();
            } else parts.addLast(part);
        }
        require(!parts.isEmpty(), "Model resource path is empty.");
        return String.join("/", parts);
    }

    private static String path(String value) {
        require(
                value != null
                        && !value.isEmpty()
                        && value.length() <= MAX_PATH_LENGTH
                        && !value.startsWith("/")
                        && !value.contains("\\")
                        && !value.contains(":"),
                "Invalid model archive path.");
        for (String part : value.split("/", -1))
            require(
                    !part.equals("..") && !part.equals(".") && part.chars().noneMatch(c -> c < ASCII_CONTROL_LIMIT),
                    "Invalid model archive path.");
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> objects(Object value, String name) {
        require(value instanceof List<?>, name + " must be an array.");
        var result = new ArrayList<Map<String, Object>>();
        for (Object item : (List<?>) value) {
            require(item instanceof Map<?, ?>, name + " entry must be an object.");
            result.add((Map<String, Object>) item);
        }
        return result;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            var sorted = new TreeMap<String, Object>();
            for (var entry : map.entrySet()) {
                require(entry.getKey() instanceof String, "JSON object key must be a string.");
                sorted.put((String) entry.getKey(), canonical(entry.getValue()));
            }
            return sorted;
        }
        if (value instanceof List<?> list)
            return list.stream().map(ModelInputNormalizer::canonical).toList();
        return value;
    }

    private static int number(Object value) {
        require(value instanceof Number, "Missing glTF integer.");
        double n = ((Number) value).doubleValue();
        require(Double.isFinite(n) && n >= 0 && n <= LIMIT && n == Math.rint(n), "glTF integer out of range.");
        return (int) n;
    }

    private static void align(ByteArrayOutputStream b) {
        while (b.size() % ALIGNMENT_BYTES != 0) b.write(0);
    }

    private static void append(ByteArrayOutputStream b, byte[] bytes) {
        require((long) b.size() + bytes.length <= LIMIT, "Total model resource bytes exceed limit.");
        b.writeBytes(bytes);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
