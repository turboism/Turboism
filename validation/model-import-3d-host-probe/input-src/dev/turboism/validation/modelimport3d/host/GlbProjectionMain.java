package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.json.Json;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** Offline acceptance fixture compiler: fixed 1024 canvas, scale 200, +/-30 degree axes. */
public final class GlbProjectionMain {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("usage: GlbProjectionMain <authorized.glb> <new-plan.json>");
        byte[] bytes;
        try (var stream = Files.newInputStream(Path.of(args[0]))) {
            bytes = stream.readNBytes(GlbSurfaceReader.MAX_BYTES + 1);
        }
        var surfaces = GlbSurfaceReader.read(bytes);
        var camera = new SurfaceProjection.Camera(512, 512, 200, new SurfaceProjection.Vertex(0, 0, 0));
        var plan = SurfaceImportPlan.compile(surfaces, camera, 30, 30, 0.05, 2_000_000);
        var patches = new ArrayList<Map<String, Object>>();
        for (var patch : plan.patches()) {
            var source = patch.source();
            var surface = surfaces.get(patch.surfaceIndex());
            var record = new LinkedHashMap<String, Object>();
            record.put("surfaceIndex", patch.surfaceIndex());
            record.put("node", source.node()); record.put("mesh", source.mesh()); record.put("primitive", source.primitive());
            record.put("material", surface.material()); record.put("baseColorLinear", surface.baseColor());
            record.put("doubleSided", surface.doubleSided()); record.put("reflected", surface.reflected());
            record.put("sourceVertices", source.sourceVertices()); record.put("sourceTriangles", source.sourceTriangles());
            record.put("source3D", source.positions().stream().map(v -> List.of(v.x(), v.y(), v.z())).toList());
            record.put("textureUV", source.textureUvs().stream().map(v -> List.of(v.x(), v.y())).toList());
            record.put("indices", source.indices());
            record.put("neutralCollapsedTriangles", patch.neutralCollapsedTriangles());
            record.put("positionsByKey", patch.positionsByKey().stream()
                .map(points -> points.stream().map(p -> List.of(p.x(), p.y())).toList()).toList());
            patches.add(record);
        }
        var result = new LinkedHashMap<String, Object>();
        result.put("schemaVersion", "mi3d-glb-projection-v1");
        result.put("status", "PROJECTED_NOT_IMPORTED");
        result.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        result.put("camera", Map.of("center", List.of(512,512), "scale", 200, "pivot", List.of(0,0,0),
            "rotation", "Ry(yaw)*Rx(pitch)", "screenY", "NEGATIVE_WORLD_Y"));
        result.put("keys", plan.keys().stream().map(k -> List.of(k.yaw(), k.pitch())).toList());
        result.put("neutralKey", plan.neutralKey()); result.put("projectedVertices", plan.projectedVertices());
        result.put("idealInterpolationBoundPixels", plan.grid().errorBoundPixels());
        result.put("appearancePolicy", "FLAT_BASE_COLOR_NO_PBR_LIGHTING");
        result.put("hostExecuted", false); result.put("visibilityOrderVerified", false);
        result.put("nativeInterpolationVerified", false); result.put("patches", patches);
        Files.write(Path.of(args[1]), Json.bytes(result), StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        System.out.println("PROJECTED_NOT_IMPORTED: " + patches.size() + " patches x " + plan.keys().size() + " keys");
    }
}
