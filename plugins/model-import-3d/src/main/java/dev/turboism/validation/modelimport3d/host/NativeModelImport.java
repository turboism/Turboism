package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.GlbViewerLoader;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.textureatlas.TextureAtlasLayoutItem;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.function.IntFunction;

/** Complete default-pose GLB import. The caller owns the document transaction. */
final class NativeModelImport {
    static final int MAX_BYTES = 128 * 1024 * 1024;

    private NativeModelImport() {}

    record Prepared(
            NativeCharacterPlan.Plan geometry, NativeMaterialPlan materials, NativeControlWarpPlan.Plan controls) {}

    static boolean isGlb(byte[] bytes) {
        return bytes != null
                && bytes.length >= 4
                && bytes[0] == 'g'
                && bytes[1] == 'l'
                && bytes[2] == 'T'
                && bytes[3] == 'F';
    }

    static Prepared prepare(byte[] bytes, SurfaceImport.Request request, BooleanSupplier cancelled) throws IOException {
        if (!isGlb(bytes) || bytes.length > MAX_BYTES)
            throw new IllegalArgumentException("Native character import requires a GLB within 128 MiB");
        var scene = GlbViewerLoader.loadWithSourceControls(bytes, cancelled);
        var materials = NativeMaterialPlan.prepare(scene, cancelled);
        if (cancelled.getAsBoolean()) throw new CancellationException();
        var limits = new java.util.HashMap<Integer, java.util.Set<java.util.List<Integer>>>();
        var fits = new NativeControlWarpPlan.FitCache();
        for (int retry = 0; retry < 512; retry++) {
            var geometry = NativeCharacterPlan.prepare(materials.scene(), request, limits);
            if (cancelled.getAsBoolean()) throw new CancellationException();
            try {
                return new Prepared(
                        geometry, materials, NativeControlWarpPlan.prepare(materials.scene(), geometry, fits));
            } catch (NativeControlWarpPlan.FitFailure failure) {
                var failures = new ArrayList<NativeControlWarpPlan.FitFailure>();
                failures.add(failure);
                for (var suppressed : failure.getSuppressed())
                    if (suppressed instanceof NativeControlWarpPlan.FitFailure fit) failures.add(fit);
                for (var fit : failures) {
                    if (fit.triangles.size() <= 1) throw fit;
                    limits.computeIfAbsent(fit.sourceMesh, ignored -> new java.util.HashSet<>())
                            .add(fit.triangles);
                }
            }
        }
        throw new IllegalArgumentException("Native control refinement did not converge");
    }

    static ModelTextureAtlasPlanner.PlannedAtlas planAtlas(Prepared prepared, IntFunction<String> pageNames) {
        var items = new ArrayList<TextureAtlasLayoutItem>();
        var images = prepared.materials().images();
        for (int i = 0; i < images.size(); i++) {
            var image = images.get(i);
            items.add(new TextureAtlasLayoutItem("native-img-" + i, image.width(), image.height()));
        }
        return ModelTextureAtlasPlanner.plan(items, ModelTextureAtlasPlanner.Options.DEFAULT, pageNames);
    }

    static NativeCharacterAuthoring.Created assemble(
            CubismModel model, Prepared prepared, ModelTextureAtlasPlanner.PlannedAtlas atlas) {
        Map<String, ModelImageBinding> sources = new LinkedHashMap<>();
        Map<ArtMeshId, ModelImageBinding> meshes = new LinkedHashMap<>();
        var materials = prepared.materials();
        var created = NativeCharacterAuthoring.assemble(
                model,
                prepared.geometry(),
                (meshId, sourceIndex) -> {
                    int imageIndex = materials.imageIndexForMesh(sourceIndex);
                    if (imageIndex < 0 || imageIndex >= materials.images().size())
                        throw new IllegalStateException("Native mesh has no material image");
                    String key = "native-img-" + imageIndex;
                    var existing = sources.get(key);
                    var binding = existing == null
                            ? model.textures()
                                    .registerImageAndBind(
                                            materials.images().get(imageIndex).newGrant(), meshId)
                            : model.textures().bindModelImage(existing.modelImageId(), meshId);
                    sources.putIfAbsent(key, binding);
                    meshes.put(meshId, binding);
                },
                sourceIndex -> {
                    var image = materials.imageForMesh(sourceIndex);
                    return new NativeCharacterAuthoring.ImageDimensions(image.width(), image.height());
                },
                sourceIndex -> materials.scene().meshes().get(sourceIndex).appearanceGroup(),
                sourceIndex -> materials.scene().meshes().get(sourceIndex).bodyPart());
        ModelTextureAtlasAuthoring.apply(model, created.meshIds(), sources, meshes, atlas);
        NativeControlAuthoring.apply(model, created, materials.scene(), prepared.controls());
        return created;
    }
}
