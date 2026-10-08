package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;

/** SDK-only base-color binding. Caller owns whole-import recovery; this does not enable culling. */
final class SurfaceImportAppearance {
    private SurfaceImportAppearance() { }
    record BoundMaterial(ArtMeshId meshId, int sourceSurface, int sourceMaterial, int argb, ModelImageBinding binding) { }

    static List<BoundMaterial> bind(CubismModel model, SurfaceImportGeometry.Created geometry, SurfaceBaseColorPlan.Plan plan) {
        if(geometry.patches().size()!=plan.patches().size() || geometry.patches().isEmpty()) {
            throw new IllegalArgumentException("material/created patch count differs");
        }
        var identities=new HashSet<ArtMeshId>();
        // Validate every target before the first image registration. No replacement of existing inputs.
        for(int i=0;i<geometry.patches().size();i++) {
            var patch=geometry.patches().get(i);var material=plan.patches().get(i);
            if(!patch.source().equals(material.source()) || !identities.add(patch.meshId())
                    || !plan.materials().contains(material.material())) {
                throw new IllegalArgumentException("material/source correspondence differs");
            }
            patch.bake().verify(model.drawables().find(patch.meshId()));
            if(model.textures().textureInput(patch.meshId()).isPresent()) {
                throw new IllegalArgumentException("material target already has an image input");
            }
        }
        var registered=new HashMap<SurfaceBaseColorPlan.Material,ModelImageBinding>();
        var result=new ArrayList<BoundMaterial>();
        for(int i=0;i<geometry.patches().size();i++) {
            var patch=geometry.patches().get(i);var material=plan.patches().get(i);
            var existing=registered.get(material.material());
            var binding=existing==null
                ? model.textures().registerImageAndBind(material.material().newGrant(),patch.meshId())
                : model.textures().bindModelImage(existing.modelImageId(),patch.meshId());
            if(binding==null || !binding.artMeshId().equals(java.util.Optional.of(patch.meshId()))) {
                throw new IllegalStateException("material binding returned another target");
            }
            if(existing!=null && (!binding.modelImageId().equals(existing.modelImageId())
                    || !binding.rawImageId().equals(existing.rawImageId())
                    || !binding.modelId().equals(existing.modelId()) || binding.modelGeneration()!=existing.modelGeneration())) {
                throw new IllegalStateException("shared material identity changed");
            }
            registered.putIfAbsent(material.material(),binding);
            result.add(new BoundMaterial(patch.meshId(),material.surfaceIndex(),material.sourceMaterial(),material.material().argb(),binding));
        }
        // Later bindings must not disturb any previously bound face or baked stored state.
        for(int i=0;i<result.size();i++) {
            var row=result.get(i);var observed=model.textures().textureInput(row.meshId()).orElseThrow();
            if(observed.kind()!=ArtMeshTextureInput.Kind.MODEL_IMAGE || !observed.artMeshId().equals(row.meshId())
                    || !observed.modelImageId().equals(java.util.Optional.of(row.binding().modelImageId()))
                    || !observed.modelId().equals(row.binding().modelId())
                    || observed.modelGeneration()!=row.binding().modelGeneration()) {
                throw new IllegalStateException("material binding observation differs");
            }
            geometry.patches().get(i).bake().verify(model.drawables().find(row.meshId()));
        }
        return List.copyOf(result);
    }
}
