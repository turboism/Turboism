package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelImageId;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.ArtMeshTextureInput;
import dev.turboism.sdk.cubism.model.AtlasPageAppendPlan;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelImageBinding;
import dev.turboism.sdk.cubism.model.ModelImagePlacement;
import java.util.*;

/**
 * Authoring coordinator for append-only texture atlas creation, layout application,
 * and mesh UV binding verification within an ambient authoring transaction.
 */
final class ModelTextureAtlasAuthoring {

    private ModelTextureAtlasAuthoring() {}

    record Result(List<TextureAtlasId> createdAtlasIds, AtlasPageAppendPlan appliedPlan) {
        Result {
            createdAtlasIds = List.copyOf(createdAtlasIds);
        }
    }

    static Result apply(
            CubismModel model,
            List<ArtMeshId> meshIds,
            Map<String, ModelImageBinding> bindingsBySourceId,
            Map<ArtMeshId, ModelImageBinding> meshImageBindings,
            ModelTextureAtlasPlanner.PlannedAtlas plan) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(meshIds, "meshIds");
        Objects.requireNonNull(bindingsBySourceId, "bindingsBySourceId");
        Objects.requireNonNull(meshImageBindings, "meshImageBindings");
        Objects.requireNonNull(plan, "plan");

        var textures = model.textures();
        if (!textures.supportsAppendTextureAtlasPages()) {
            throw new ModelImportPlugin.LocalizedIllegalStateException(
                    "error.texture-atlas-unavailable", "当前环境不支持纹理图集功能。");
        }

        // Remap placements using textureId -> ModelImageBinding -> ModelImagePlacement
        var placements = new ArrayList<ModelImagePlacement>();
        for (var p : plan.placements()) {
            var binding = bindingsBySourceId.get(p.textureId());
            if (binding == null) {
                throw new IllegalStateException("Placement references unmapped source texture: " + p.textureId());
            }
            placements.add(new ModelImagePlacement(
                    binding.modelImageId(), p.pageIndex(), p.x(), p.y(), p.width(), p.height()));
        }

        var appendPlan = new AtlasPageAppendPlan(
                plan.pageWidth(), plan.pageHeight(), plan.pageCount(), plan.pageNames(), placements);

        List<TextureAtlasId> createdIds;
        try {
            createdIds = textures.appendTextureAtlasPages(appendPlan);
        } catch (Exception failure) {
            throw new ModelImportPlugin.LocalizedIllegalStateException(
                    "error.atlas-layout-failed", failure, "纹理图集布局应用失败，请检查文件或重试。");
        }

        if (createdIds == null || createdIds.size() != plan.pageCount()) {
            throw new IllegalStateException("Created atlas count (" + (createdIds == null ? 0 : createdIds.size())
                    + ") does not match planned page count (" + plan.pageCount() + ")");
        }

        var imageToExpectedPageId = new HashMap<ModelImageId, TextureAtlasId>();
        for (var p : appendPlan.placements()) {
            imageToExpectedPageId.put(p.modelImageId(), createdIds.get(p.pageIndex()));
        }

        // Fail-closed verification: every mesh must resolve to its expected atlas page
        for (var meshId : meshIds) {
            var input = textures.textureInput(meshId)
                    .orElseThrow(
                            () -> new IllegalStateException("Mesh lacks texture input after atlas append: " + meshId));
            if (input.kind() != ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION) {
                throw new IllegalStateException(
                        "Mesh " + meshId + " was not bound to TEXTURE_ATLAS_REGION, was: " + input.kind());
            }
            var binding = meshImageBindings.get(meshId);
            if (binding == null) {
                throw new IllegalStateException("Mesh lacks model image binding for page verification: " + meshId);
            }
            var expectedAtlasId = imageToExpectedPageId.get(binding.modelImageId());
            if (expectedAtlasId == null) {
                throw new IllegalStateException("Model image lacks atlas page placement: " + binding.modelImageId());
            }
            if (!input.textureAtlasId().equals(Optional.of(expectedAtlasId))) {
                throw new IllegalStateException("Mesh " + meshId + " bound to wrong atlas page: expected "
                        + expectedAtlasId + " but was " + input.textureAtlasId());
            }
        }

        return new Result(createdIds, appendPlan);
    }
}
