package dev.turboism.plugin.modelimport3d.viewer;

import java.util.List;

/** Plugin-owned default-pose scene. Arrays belong to this scene and are read-only to consumers. */
public record ViewerScene(
        List<Mesh> meshes,
        List<Material> materials,
        List<Texture> textures,
        Bounds bounds,
        Stats stats,
        List<Control> controls) {
    /** Takes immutable snapshots of the scene lists; arrays remain scene-owned. */
    public ViewerScene {
        controls = List.copyOf(controls);
        meshes = List.copyOf(meshes);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
    }

    public ViewerScene(
            List<Mesh> meshes, List<Material> materials, List<Texture> textures, Bounds bounds, Stats stats) {
        this(meshes, materials, textures, bounds, stats, List.of());
    }

    /** Explicit source control. Zero preserves the saved source state. */
    public record Control(String id, String name, float minimum, float maximum) {
        public Control {
            if (id == null
                    || id.isBlank()
                    || name == null
                    || name.isBlank()
                    || !Float.isFinite(minimum)
                    || !Float.isFinite(maximum)
                    || minimum > 0
                    || maximum < 0
                    || minimum == maximum) throw new IllegalArgumentException("invalid source control");
        }
    }

    /** Source-space delta at one non-basic control key. Arrays are scene-owned. */
    public record ControlSample(String controlId, float key, float[] deltas) {
        public ControlSample {
            if (controlId == null || controlId.isBlank() || !Float.isFinite(key) || key == 0 || deltas == null)
                throw new IllegalArgumentException("invalid control sample");
        }
    }

    /** One multiplicative opacity parent. Independent factors compose by hierarchy. */
    public record OpacityControl(String controlId, float base, float target) {
        public OpacityControl {
            if (controlId == null
                    || controlId.isBlank()
                    || !Float.isFinite(base)
                    || !Float.isFinite(target)
                    || base < 0
                    || base > 1
                    || target < 0
                    || target > 1) throw new IllegalArgumentException("invalid source opacity factor");
        }
    }

    /** Stores world-space geometry and the selected material UV channels. */
    public record Mesh(
            String name,
            float[] positions,
            float[] normals,
            float[] uvs,
            float[] emissiveUvs,
            float[] colors,
            int[] indices,
            int materialIndex,
            boolean visible,
            String appearanceGroup,
            String bodyPart,
            List<ControlSample> controlSamples,
            List<OpacityControl> opacityControls) {
        public Mesh {
            controlSamples = List.copyOf(controlSamples);
            opacityControls = List.copyOf(opacityControls);
        }

        /** Saved-state opacity, including independent multiplicative parent controls. */
        public float defaultOpacity() {
            float value = visible ? 1f : 0f;
            for (var factor : opacityControls) value *= factor.base();
            return value;
        }

        public Mesh(
                String name,
                float[] positions,
                float[] normals,
                float[] uvs,
                float[] emissiveUvs,
                float[] colors,
                int[] indices,
                int materialIndex,
                boolean visible,
                String appearanceGroup,
                String bodyPart) {
            this(
                    name,
                    positions,
                    normals,
                    uvs,
                    emissiveUvs,
                    colors,
                    indices,
                    materialIndex,
                    visible,
                    appearanceGroup,
                    bodyPart,
                    List.of(),
                    List.of());
        }

        public Mesh(
                String name,
                float[] positions,
                float[] normals,
                float[] uvs,
                float[] emissiveUvs,
                float[] colors,
                int[] indices,
                int materialIndex,
                boolean visible,
                String appearanceGroup) {
            this(
                    name,
                    positions,
                    normals,
                    uvs,
                    emissiveUvs,
                    colors,
                    indices,
                    materialIndex,
                    visible,
                    appearanceGroup,
                    "");
        }

        public Mesh(
                String name,
                float[] positions,
                float[] normals,
                float[] uvs,
                float[] emissiveUvs,
                float[] colors,
                int[] indices,
                int materialIndex,
                boolean visible) {
            this(name, positions, normals, uvs, emissiveUvs, colors, indices, materialIndex, visible, name);
        }

        public Mesh(
                String name,
                float[] positions,
                float[] normals,
                float[] uvs,
                float[] emissiveUvs,
                float[] colors,
                int[] indices,
                int materialIndex) {
            this(name, positions, normals, uvs, emissiveUvs, colors, indices, materialIndex, true);
        }
    }

    /** Stores source material color, transparency and texture assignments. */
    public record Material(
            String name,
            float[] baseColorFactor,
            int baseColorTexture,
            int emissiveTexture,
            float[] emissiveFactor,
            String alphaMode,
            float alphaCutoff,
            boolean doubleSided,
            boolean unlit) {}

    /** Stores decoded pixels in top-left-origin ARGB order. */
    public record Texture(int width, int height, int[] argb) {}

    /** Stores the world-space axis-aligned scene bounds. */
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}

    /** Reports input size and decoded scene counts. */
    public record Stats(
            long sourceBytes,
            int meshCount,
            int triangleCount,
            int materialCount,
            int textureCount,
            int skinCount,
            int morphTargetCount) {}
}
