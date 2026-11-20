package dev.turboism.plugin.modelimport3d;

import java.io.IOException;

/** Small, self-contained GLB primitives bundled with the plugin. */
enum ModelPreset {
    CUBE("cube", "Cube"),
    TETRAHEDRON("tetrahedron", "Regular tetrahedron (triangular pyramid)"),
    TRIANGLE("triangle", "Triangle"),
    CYLINDER("cylinder", "Cylinder"),
    SPHERE("sphere", "Sphere"),
    SQUARE("square", "Square"),
    CIRCLE("circle", "Circle"),
    INWARD_RING("inward-ring", "Ring (flat, double-sided)"),
    ANNULUS("annulus", "Ring (flat)"),
    TORUS("torus", "Donut (solid)");

    private static final int MAX_PRESET_BYTES = 256 * 1024;

    private final String id;
    private final String label;

    ModelPreset(String id, String label) {
        this.id = id;
        this.label = label;
    }

    String id() {
        return id;
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
            byte[] bytes = stream.readNBytes(MAX_PRESET_BYTES + 1);
            if (bytes.length > MAX_PRESET_BYTES) throw new IOException("Bundled preset exceeds size limit: " + id);
            return bytes;
        }
    }
}
