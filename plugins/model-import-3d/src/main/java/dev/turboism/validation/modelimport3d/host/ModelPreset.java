package dev.turboism.validation.modelimport3d.host;

import java.io.IOException;

/** Small, self-contained GLB primitives bundled with the plugin. */
enum ModelPreset {
    CUBE("cube", "立方体"),
    TETRAHEDRON("tetrahedron", "正四面体（三角锥）"),
    TRIANGLE("triangle", "三角形"),
    CYLINDER("cylinder", "圆柱"),
    SPHERE("sphere", "球体"),
    SQUARE("square", "正方形"),
    CIRCLE("circle", "圆形"),
    INWARD_RING("inward-ring", "圆环（扁平、双面）"),
    ANNULUS("annulus", "圆环（无厚度）"),
    TORUS("torus", "圆环（有厚度）");

    private final String id;
    private final String label;

    ModelPreset(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String labelKey() {
        return "preset." + id;
    }

    String fallbackLabel() {
        return label;
    }

    byte[] read() throws IOException {
        String resource = "/META-INF/turboism/model-import-3d/presets/" + id + ".glb";
        try (var stream = ModelPreset.class.getResourceAsStream(resource)) {
            if (stream == null) throw new IOException("Missing bundled preset: " + id);
            byte[] bytes = stream.readNBytes(256 * 1024 + 1);
            if (bytes.length > 256 * 1024) throw new IOException("Bundled preset exceeds size limit: " + id);
            return bytes;
        }
    }
}
