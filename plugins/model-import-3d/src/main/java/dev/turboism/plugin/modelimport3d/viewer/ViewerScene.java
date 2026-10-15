package dev.turboism.plugin.modelimport3d.viewer;

import java.util.List;

/** Plugin-owned default-pose scene. Arrays belong to this scene and are read-only to consumers. */
public record ViewerScene(List<Mesh> meshes, List<Material> materials, List<Texture> textures,
                          Bounds bounds, Stats stats) {
    public ViewerScene {
        meshes = List.copyOf(meshes);
        materials = List.copyOf(materials);
        textures = List.copyOf(textures);
    }
    public record Mesh(String name, float[] positions, float[] normals, float[] uvs,
                       float[] emissiveUvs, float[] colors, int[] indices, int materialIndex) {}
    public record Material(String name, float[] baseColorFactor, int baseColorTexture,
                           int emissiveTexture, float[] emissiveFactor, String alphaMode,
                           float alphaCutoff, boolean doubleSided, boolean unlit) {}
    public record Texture(int width, int height, int[] argb) {}
    public record Bounds(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {}
    public record Stats(long sourceBytes, int meshCount, int triangleCount, int materialCount,
                        int textureCount, int skinCount, int morphTargetCount) {}
}
