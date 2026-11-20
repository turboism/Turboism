package dev.turboism.plugin.modelimport3d;

import dev.turboism.sdk.json.Json;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.util.*;

/** Static OBJ/MTL to GLB. Polygon/corner identities, materials and UV seams are explicit. */
final class ObjModelInput {
    private static final int MIN_POSITION_TOKENS = 4;
    private static final int HOMOGENEOUS_POSITION_TOKENS = 5;
    private static final int HOMOGENEOUS_W_INDEX = 4;
    private static final int MIN_UV_TOKENS = 3;
    private static final int MAX_UV_TOKENS = 4;
    private static final int NORMAL_TOKENS = 4;
    private static final int MIN_FACE_TOKENS = 4;
    private static final int MAX_FACE_TOKENS = 65;
    private static final int MAX_CORNER_PARTS = 3;
    private static final int TRIANGLE_CORNERS = 3;

    private static final int MAX_POSITIONS = 100_000;
    private static final int MAX_UVS = 100_000;
    private static final int MAX_NORMALS = 100_000;
    private static final int MAX_TRIANGLES = 200_000;
    private static final int MAX_EXPANDED_VERTICES = 100_000;
    private static final int MAX_MATERIALS = 32;
    private static final int MAX_MATERIALS_WITH_DEFAULT = MAX_MATERIALS + 1;
    private static final int KD_TOKENS = 4;
    private static final int TRANSPARENCY_TOKENS = 2;
    private static final int MAX_LINE_LENGTH = 16_384;

    private static final int VECTOR_3D_COMPONENTS = 3;
    private static final int VECTOR_2D_COMPONENTS = 2;
    private static final int BYTES_PER_FLOAT = 4;
    private static final int BYTES_PER_INT = 4;
    private static final int ALIGNMENT_BYTES = 4;
    private static final int GLTF_COMPONENT_TYPE_FLOAT = 5126;
    private static final int GLTF_COMPONENT_TYPE_UNSIGNED_INT = 5125;
    private static final int GLTF_WRAP_CLAMP_TO_EDGE = 33071;
    private static final int GLTF_FILTER_LINEAR = 9729;
    private static final double DEGENERATE_NORMAL_EPSILON = 1e-12;

    private ObjModelInput() {}

    private record Corner(int position, int uv, int normal, int synthetic) {}

    private record Face(List<Corner> corners, String material) {}

    private static final class Material {
        final String name;
        List<Double> color = List.of(1d, 1d, 1d, 1d);
        String image;

        Material(String name) {
            this.name = name;
        }
    }

    static byte[] normalize(byte[] input, Map<String, byte[]> files, String base) throws IOException {
        var positions = new ArrayList<double[]>();
        var uvs = new ArrayList<double[]>();
        int normals = 0, triangles = 0;
        var faces = new ArrayList<Face>();
        var materials = new LinkedHashMap<String, Material>();
        String current = "";
        materials.put("", new Material("default"));
        for (String line : lines(input)) {
            String[] words = line.split("\\s+");
            String kind = words[0];
            switch (kind) {
                case "v" -> {
                    require(
                            words.length == MIN_POSITION_TOKENS || words.length == HOMOGENEOUS_POSITION_TOKENS,
                            "OBJ vertex colors are not supported.");
                    double w = words.length == HOMOGENEOUS_POSITION_TOKENS ? number(words[HOMOGENEOUS_W_INDEX]) : 1;
                    require(w == 1, "OBJ homogeneous coordinate requires w=1.");
                    positions.add(new double[] {number(words[1]), number(words[2]), number(words[3])});
                    require(positions.size() <= MAX_POSITIONS, "OBJ vertex count exceeds limit.");
                }
                case "vt" -> {
                    require(words.length == MIN_UV_TOKENS || words.length == MAX_UV_TOKENS, "Invalid OBJ vt record.");
                    double u = number(words[1]), v = number(words[2]);
                    require(u >= 0 && u <= 1 && v >= 0 && v <= 1, "OBJ wrapped UVs are not supported.");
                    if (words.length == MAX_UV_TOKENS) require(number(words[3]) == 0, "OBJ 3D UVs are not supported.");
                    uvs.add(new double[] {u, 1 - v});
                    require(uvs.size() <= MAX_UVS, "OBJ UV count exceeds limit.");
                }
                case "vn" -> {
                    require(words.length == NORMAL_TOKENS, "Invalid OBJ vn record.");
                    double length = Math.hypot(Math.hypot(number(words[1]), number(words[2])), number(words[3]));
                    require(length > 0, "Degenerate OBJ normal.");
                    require(++normals <= MAX_NORMALS, "OBJ normal count exceeds limit.");
                }
                case "mtllib" -> {
                    String name = line.substring(kind.length()).trim();
                    require(!name.isEmpty(), "OBJ mtllib lacks a filename.");
                    byte[] mtl = ModelInputNormalizer.packageResource(files, base, name);
                    String resolved = ModelInputNormalizer.packagePath(base, name);
                    int slash = resolved.lastIndexOf('/');
                    parseMaterials(mtl, files, slash < 0 ? "" : resolved.substring(0, slash + 1), materials);
                }
                case "usemtl" -> {
                    current = line.substring(kind.length()).trim();
                    require(!current.isEmpty(), "OBJ material name is empty.");
                }
                case "f" -> {
                    require(
                            words.length >= MIN_FACE_TOKENS && words.length <= MAX_FACE_TOKENS,
                            "OBJ face requires 3 to 64 corners.");
                    var corners = new ArrayList<Corner>();
                    for (int i = 1; i < words.length; i++) {
                        String[] corner = words[i].split("/", -1);
                        require(corner.length <= MAX_CORNER_PARTS && !corner[0].isEmpty(), "Invalid OBJ face corner.");
                        int p = index(corner[0], positions.size()),
                                uv = corner.length > 1 && !corner[1].isEmpty() ? index(corner[1], uvs.size()) : -1,
                                n = corner.length > 2 && !corner[2].isEmpty() ? index(corner[2], normals) : -1;
                        corners.add(new Corner(p, uv, n, -1));
                    }
                    require((triangles += corners.size() - 2) <= MAX_TRIANGLES, "OBJ triangle count exceeds limit.");
                    faces.add(new Face(List.copyOf(corners), current));
                }
                case "o", "g", "s" -> {
                    /* Object/group names and smoothing do not affect static base-color rendering. */
                }
                default -> throw new IllegalArgumentException("Unsupported OBJ directive: " + kind);
            }
        }
        require(!faces.isEmpty(), "OBJ contains no faces.");
        var groups = new LinkedHashMap<String, List<Face>>();
        for (var face : faces) {
            require(materials.containsKey(face.material()), "OBJ references undefined material: " + face.material());
            groups.computeIfAbsent(face.material(), ignored -> new ArrayList<>())
                    .add(face);
        }
        require(groups.size() <= MAX_MATERIALS, "OBJ material count exceeds limit.");
        var binary = new ByteArrayOutputStream();
        var views = new ArrayList<Object>();
        var accessors = new ArrayList<Object>();
        var primitives = new ArrayList<Object>();
        var gltfMaterials = new ArrayList<Object>();
        var images = new ArrayList<Object>();
        var textures = new ArrayList<Object>();
        var textureIds = new LinkedHashMap<String, Integer>();
        int totalSlots = 0;
        for (var group : groups.entrySet()) {
            var material = materials.get(group.getKey());
            var slots = new LinkedHashMap<Corner, Integer>();
            var xyz = new ArrayList<double[]>();
            var uv = new ArrayList<double[]>();
            var indices = new ArrayList<Integer>();
            int synthetic = 0;
            for (var face : group.getValue())
                for (int[] triangle : triangulate(face, positions)) {
                    for (int c = 0; c < TRIANGLE_CORNERS; c++) {
                        var original = face.corners().get(triangle[c]);
                        require(material.image == null || original.uv() >= 0, "Textured OBJ face lacks UVs.");
                        var corner = original.uv() >= 0
                                ? original
                                : new Corner(original.position(), -1, original.normal(), synthetic++);
                        Integer id = slots.get(corner);
                        if (id == null) {
                            id = slots.size();
                            slots.put(corner, id);
                            xyz.add(positions.get(corner.position()));
                            uv.add(
                                    corner.uv() >= 0
                                            ? uvs.get(corner.uv())
                                            : new double[] {c == 1 ? 1 : 0, c == 2 ? 0 : 1});
                        }
                        indices.add(id);
                    }
                }
            totalSlots += slots.size();
            require(totalSlots <= MAX_EXPANDED_VERTICES, "Expanded OBJ corners exceed 100,000 vertices.");
            int positionAccessor = vectors(binary, views, accessors, xyz, VECTOR_3D_COMPONENTS),
                    uvAccessor = vectors(binary, views, accessors, uv, VECTOR_2D_COMPONENTS),
                    indexAccessor = indices(binary, views, accessors, indices);
            var pbr = new LinkedHashMap<String, Object>();
            pbr.put("baseColorFactor", material.color);
            if (material.image != null) {
                Integer texture = textureIds.get(material.image);
                if (texture == null) {
                    byte[] png =
                            ModelTextureNormalizer.png(ModelInputNormalizer.packageResource(files, "", material.image));
                    align(binary);
                    int offset = binary.size();
                    require(
                            (long) binary.size() + png.length <= GlbSurfaceReader.MAX_BYTES,
                            "Expanded OBJ texture data exceeds size limit.");
                    binary.writeBytes(png);
                    int view = views.size();
                    views.add(Map.of("buffer", 0, "byteOffset", offset, "byteLength", png.length));
                    int image = images.size();
                    images.add(Map.of("bufferView", view, "mimeType", "image/png"));
                    texture = textures.size();
                    textures.add(Map.of("source", image, "sampler", 0));
                    textureIds.put(material.image, texture);
                }
                pbr.put("baseColorTexture", Map.of("index", texture));
            }
            int materialIndex = gltfMaterials.size();
            gltfMaterials.add(Map.of("name", material.name, "pbrMetallicRoughness", pbr));
            primitives.add(Map.of(
                    "attributes",
                    Map.of("POSITION", positionAccessor, "TEXCOORD_0", uvAccessor),
                    "indices",
                    indexAccessor,
                    "material",
                    materialIndex));
        }
        require(binary.size() <= GlbSurfaceReader.MAX_BYTES, "Expanded OBJ binary exceeds size limit.");
        var doc = new LinkedHashMap<String, Object>();
        doc.put("asset", Map.of("version", "2.0"));
        doc.put("scene", 0);
        doc.put("scenes", List.of(Map.of("nodes", List.of(0))));
        doc.put("nodes", List.of(Map.of("mesh", 0)));
        doc.put("meshes", List.of(Map.of("primitives", primitives)));
        doc.put(
                "buffers",
                List.of(Map.of(
                        "uri",
                        "data:application/octet-stream;base64,"
                                + Base64.getEncoder().encodeToString(binary.toByteArray()),
                        "byteLength",
                        binary.size())));
        doc.put("bufferViews", views);
        doc.put("accessors", accessors);
        doc.put("materials", gltfMaterials);
        if (!images.isEmpty()) {
            doc.put("images", images);
            doc.put("textures", textures);
            doc.put(
                    "samplers",
                    List.of(Map.of(
                            "wrapS",
                            GLTF_WRAP_CLAMP_TO_EDGE,
                            "wrapT",
                            GLTF_WRAP_CLAMP_TO_EDGE,
                            "magFilter",
                            GLTF_FILTER_LINEAR,
                            "minFilter",
                            GLTF_FILTER_LINEAR)));
        }
        return ModelInputNormalizer.gltf(Json.bytes(doc), Map.of(), "");
    }

    private static void parseMaterials(
            byte[] input, Map<String, byte[]> files, String base, Map<String, Material> materials) {
        Material current = null;
        for (String line : lines(input)) {
            String[] words = line.split("\\s+");
            String kind = words[0];
            if (kind.equals("newmtl")) {
                String name = line.substring(kind.length()).trim();
                require(!name.isEmpty() && !materials.containsKey(name), "MTL material name is empty or duplicated.");
                require(materials.size() < MAX_MATERIALS_WITH_DEFAULT, "MTL material count exceeds limit.");
                current = new Material(name);
                materials.put(name, current);
                continue;
            }
            require(current != null, "MTL attribute precedes newmtl.");
            switch (kind) {
                case "Kd" -> {
                    require(words.length == KD_TOKENS, "Invalid MTL Kd record.");
                    var color = new ArrayList<Double>();
                    for (int i = 1; i < KD_TOKENS; i++) {
                        double v = number(words[i]);
                        require(v >= 0 && v <= 1, "MTL Kd value out of range.");
                        color.add(v);
                    }
                    color.add(1d);
                    current.color = List.copyOf(color);
                }
                case "d", "Tr" -> {
                    require(words.length == TRANSPARENCY_TOKENS, "Invalid MTL transparency record.");
                    require(
                            number(words[1]) == (kind.equals("d") ? 1 : 0),
                            "Transparent MTL materials are not supported.");
                }
                case "map_Kd" -> {
                    String name = line.substring(kind.length()).trim();
                    require(!name.startsWith("-"), "MTL map_Kd options are not supported.");
                    ModelInputNormalizer.packageResource(files, base, name);
                    current.image = ModelInputNormalizer.packagePath(base, name);
                }
                case "Ka", "Ks", "Ke", "Ns", "Ni", "Tf", "illum", "map_Ka", "map_bump", "bump" -> {
                    /* Cubism imports base color only; UI states lighting/bump are not reproduced. */
                }
                default -> throw new IllegalArgumentException("Unsupported MTL directive: " + kind);
            }
        }
    }

    private static int vectors(
            ByteArrayOutputStream binary,
            List<Object> views,
            List<Object> accessors,
            List<double[]> values,
            int components) {
        align(binary);
        int offset = binary.size();
        var b = ByteBuffer.allocate(values.size() * components * BYTES_PER_FLOAT)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (double[] v : values)
            for (double x : v) {
                float f = (float) x;
                require(Float.isFinite(f), "OBJ coordinate cannot convert to a finite float.");
                b.putFloat(f);
            }
        binary.writeBytes(b.array());
        int view = views.size();
        views.add(Map.of("buffer", 0, "byteOffset", offset, "byteLength", b.capacity()));
        int accessor = accessors.size();
        accessors.add(Map.of(
                "bufferView",
                view,
                "componentType",
                GLTF_COMPONENT_TYPE_FLOAT,
                "count",
                values.size(),
                "type",
                components == VECTOR_3D_COMPONENTS ? "VEC3" : "VEC2"));
        return accessor;
    }

    private static int indices(
            ByteArrayOutputStream binary, List<Object> views, List<Object> accessors, List<Integer> values) {
        align(binary);
        int offset = binary.size();
        var b = ByteBuffer.allocate(values.size() * BYTES_PER_INT).order(ByteOrder.LITTLE_ENDIAN);
        for (int i : values) b.putInt(i);
        binary.writeBytes(b.array());
        int view = views.size();
        views.add(Map.of("buffer", 0, "byteOffset", offset, "byteLength", b.capacity()));
        int accessor = accessors.size();
        accessors.add(Map.of(
                "bufferView",
                view,
                "componentType",
                GLTF_COMPONENT_TYPE_UNSIGNED_INT,
                "count",
                values.size(),
                "type",
                "SCALAR"));
        return accessor;
    }

    private static List<int[]> triangulate(Face face, List<double[]> positions) {
        int count = face.corners().size();
        var polygon = new ArrayList<double[]>();
        for (var corner : face.corners()) polygon.add(positions.get(corner.position()));
        double[] normal = new double[VECTOR_3D_COMPONENTS];
        for (int i = 0; i < count; i++) {
            var a = polygon.get(i);
            var b = polygon.get((i + 1) % count);
            normal[0] += (a[1] - b[1]) * (a[2] + b[2]);
            normal[1] += (a[2] - b[2]) * (a[0] + b[0]);
            normal[2] += (a[0] - b[0]) * (a[1] + b[1]);
        }
        int axis = 0;
        for (int i = 1; i < VECTOR_3D_COMPONENTS; i++) if (Math.abs(normal[i]) > Math.abs(normal[axis])) axis = i;
        require(Math.abs(normal[axis]) > DEGENERATE_NORMAL_EPSILON, "Degenerate OBJ polygon.");
        int x = (axis + 1) % VECTOR_3D_COMPONENTS, y = (axis + 2) % VECTOR_3D_COMPONENTS;
        double sign = Math.signum(normal[axis]);
        for (int i = 0; i < count; i++)
            for (int j = i + 1; j < count; j++) {
                if (j == i + 1 || i == 0 && j == count - 1) continue;
                var a = polygon.get(i);
                var b = polygon.get((i + 1) % count);
                var c = polygon.get(j);
                var d = polygon.get((j + 1) % count);
                double abC = cross(a, b, c, x, y),
                        abD = cross(a, b, d, x, y),
                        cdA = cross(c, d, a, x, y),
                        cdB = cross(c, d, b, x, y);
                require(
                        !(abC * abD <= 0
                                && cdA * cdB <= 0
                                && Math.max(Math.min(a[x], b[x]), Math.min(c[x], d[x]))
                                        <= Math.min(Math.max(a[x], b[x]), Math.max(c[x], d[x]))
                                && Math.max(Math.min(a[y], b[y]), Math.min(c[y], d[y]))
                                        <= Math.min(Math.max(a[y], b[y]), Math.max(c[y], d[y]))),
                        "OBJ polygon self-intersects or overlaps.");
            }
        boolean convex = true;
        for (int i = 0; i < count; i++)
            if (cross(polygon.get(i), polygon.get((i + 1) % count), polygon.get((i + 2) % count), x, y) * sign <= 0)
                convex = false;
        var result = new ArrayList<int[]>();
        if (convex) {
            for (int i = 1; i < count - 1; i++) result.add(new int[] {0, i, i + 1});
            return result;
        }
        var remaining = new ArrayList<Integer>();
        for (int i = 0; i < count; i++) remaining.add(i);
        while (remaining.size() > TRIANGLE_CORNERS) {
            boolean found = false;
            for (int at = 0; at < remaining.size(); at++) {
                int a = remaining.get((at + remaining.size() - 1) % remaining.size()),
                        b = remaining.get(at),
                        c = remaining.get((at + 1) % remaining.size());
                if (cross(polygon.get(a), polygon.get(b), polygon.get(c), x, y) * sign <= DEGENERATE_NORMAL_EPSILON)
                    continue;
                boolean inside = false;
                for (int p : remaining)
                    if (p != a
                            && p != b
                            && p != c
                            && cross(polygon.get(a), polygon.get(b), polygon.get(p), x, y) * sign >= 0
                            && cross(polygon.get(b), polygon.get(c), polygon.get(p), x, y) * sign >= 0
                            && cross(polygon.get(c), polygon.get(a), polygon.get(p), x, y) * sign >= 0) {
                        inside = true;
                        break;
                    }
                if (inside) continue;
                result.add(new int[] {a, b, c});
                remaining.remove(at);
                found = true;
                break;
            }
            require(
                    found,
                    "OBJ polygon could not be triangulated reliably; check for self-intersection or duplicate corners.");
        }
        result.add(new int[] {remaining.get(0), remaining.get(1), remaining.get(2)});
        return result;
    }

    private static double cross(double[] a, double[] b, double[] c, int x, int y) {
        return (b[x] - a[x]) * (c[y] - a[y]) - (b[y] - a[y]) * (c[x] - a[x]);
    }

    private static int index(String text, int count) {
        int i = Integer.parseInt(text);
        require(i != 0, "OBJ index cannot be zero.");
        int result = i > 0 ? i - 1 : count + i;
        require(result >= 0 && result < count, "OBJ index out of range.");
        return result;
    }

    private static double number(String text) {
        double n = Double.parseDouble(text);
        require(Double.isFinite(n), "OBJ/MTL number must be finite.");
        return n;
    }

    private static List<String> lines(byte[] input) {
        require(input.length <= GlbSurfaceReader.MAX_BYTES, "OBJ/MTL file exceeds size limit.");
        String text;
        try {
            text = StandardCharsets.UTF_8
                    .newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input))
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IllegalArgumentException("OBJ/MTL input must be valid UTF-8.", e);
        }
        var result = new ArrayList<String>();
        for (String raw : text.split("\\R")) {
            int comment = raw.indexOf('#');
            String line = (comment < 0 ? raw : raw.substring(0, comment)).trim();
            require(line.length() <= MAX_LINE_LENGTH, "OBJ/MTL line exceeds length limit.");
            if (!line.isEmpty()) result.add(line);
        }
        return result;
    }

    private static void align(ByteArrayOutputStream b) {
        while (b.size() % ALIGNMENT_BYTES != 0) b.write(0);
    }

    private static void require(boolean ok, String message) {
        if (!ok) throw new IllegalArgumentException(message);
    }
}
