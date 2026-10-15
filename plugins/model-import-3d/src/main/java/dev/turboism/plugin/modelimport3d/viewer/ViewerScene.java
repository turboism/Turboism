package dev.turboism.plugin.modelimport3d.viewer;

import java.util.List;

/** Plugin-owned default-pose scene. Arrays belong to this scene and are read-only to consumers. */
public record ViewerScene(
        List<Mesh> meshes, List<Material> materials, List<Texture> textures, Bounds bounds, Stats stats) {
    /** Takes immutable snapshots of the scene lists; arrays remain scene-owned. */
    public ViewerScene {
        meshes = List.copyOf(meshes);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
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
            String bodyPart) {
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
