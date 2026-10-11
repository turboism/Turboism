package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline, explicit-file input compiler. Output never claims Cubism objects were created. */
public final class GlbInputMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: GlbInputMain <authorized.glb> <new-plan.json>");
        Path input = Path.of(args[0]), output = Path.of(args[1]);
        if (Files.size(input) > GlbSurfaceReader.MAX_BYTES) throw new IllegalArgumentException("GLB byte budget");
        byte[] bytes;
        try (var stream = Files.newInputStream(input)) {
            bytes = stream.readNBytes(GlbSurfaceReader.MAX_BYTES + 1);
        }
        var surfaces = GlbSurfaceReader.read(bytes);
        var records = new ArrayList<Map<String, Object>>();
        for (var surface : surfaces) {
            var record = new LinkedHashMap<String, Object>();
            record.put("node", surface.node()); record.put("mesh", surface.mesh()); record.put("primitive", surface.primitive());
            record.put("material", surface.material()); record.put("baseColorLinear", surface.baseColor());
            record.put("doubleSided", surface.doubleSided()); record.put("reflected", surface.reflected());
            record.put("source3D", positions(surface.positions())); record.put("sourceNormals", positions(surface.sourceNormals()));
            record.put("textureUV", surface.textureUvs().stream().map(p -> List.of(p.x(), p.y())).toList());
            record.put("sourceIndices", surface.indices());
            if(surface.texture()!=null) {
                var texture=surface.texture();var pixelBytes=java.nio.ByteBuffer.allocate(texture.pixels().size()*4);
                texture.pixels().forEach(pixelBytes::putInt);
                record.put("baseColorTexture",Map.of("textureIndex",texture.textureIndex(),"imageIndex",texture.imageIndex(),
                    "width",texture.width(),"height",texture.height(),"pixelOrder","TOP_ROW_FIRST_ARGB_BIG_ENDIAN",
                    "pixelsSha256",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(pixelBytes.array()))));
            }
            record.put("candidateDrawPatches", SurfacePatchPlan.plan(surface).stream().map(p -> Map.of(
                "sourceVertices", p.sourceVertices(), "sourceTriangles", p.sourceTriangles(), "indices", p.indices())).toList());
            records.add(record);
        }
        var result = new LinkedHashMap<String, Object>();
        boolean textured=surfaces.stream().anyMatch(s->s.texture()!=null);
        result.put("schemaVersion", textured ? "mi3d-glb-surfaces-v2" : "mi3d-glb-surfaces-v1"); result.put("status", "DECODED_NOT_IMPORTED");
        result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("bytes", bytes.length); result.put("sourceSpace", "GLTF_RIGHT_HANDED_STATIC_NODE_WORLD");
        result.put("appearancePolicy", textured ? "EMBEDDED_BASE_COLOR_NO_PBR_LIGHTING" : "FLAT_BASE_COLOR_NO_PBR_LIGHTING");
        result.put("hostExecuted", false); result.put("visibilityOrderVerified", false); result.put("surfaces", records);
        Files.write(output, Json.bytes(result), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("DECODED_NOT_IMPORTED: " + surfaces.size() + " primitives; plan=" + output);
    }

    private static List<List<Double>> positions(List<SurfaceProjection.Vertex> points) {
        return points.stream().map(v -> List.of(v.x(), v.y(), v.z())).toList();
    }
}
