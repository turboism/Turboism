package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.event.CubismOperation;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.Parameters;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** Permission-checked {@link CubismModel} view for one facade scope. */
final class PermissionCheckedModel implements CubismModel {
    private final CubismFacadeImpl facade;
    private final Object wrapperOwner = new Object();
    private final CubismModel delegate;

    PermissionCheckedModel(final CubismFacadeImpl facade, final CubismModel delegate) {
        this.facade = facade;
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override public dev.turboism.sdk.cubism.id.ModelId id() {
        facade.requireModelRead("model.id");
        return delegate.id();
    }
    @Override public String name() {
        facade.requireModelRead("model.name");
        return delegate.name();
    }
    @Override public void setName(final String name) {
        facade.requireModelWrite("model.setName");
        final String value = Objects.requireNonNull(name, "name");
        if (value.strip().isEmpty()) throw new IllegalArgumentException("name must not be blank");
        delegate.setName(value);
    }
    @Override public List<dev.turboism.sdk.cubism.model.ModelInstance> modelInstances() {
        facade.requireModelRead("model.modelInstances");
        return delegate.modelInstances();
    }
    @Override public java.util.Optional<dev.turboism.sdk.cubism.model.ModelInstance> currentModelInstance() {
        facade.requireModelRead("model.currentModelInstance");
        return delegate.currentModelInstance();
    }
    @Override public boolean modelEditing() {
        facade.requireModelRead("model.modelEditing");
        return delegate.modelEditing();
    }
    @Override public dev.turboism.sdk.cubism.core.MocInfo mocInfo() {
        facade.requireModelRead("model.mocInfo");
        return delegate.mocInfo();
    }

    @Override public dev.turboism.sdk.cubism.model.ModelProfile profile() {
        facade.requireModelRead("model.profile");
        return delegate.profile();
    }

    @Override public dev.turboism.sdk.cubism.model.PhysicsSettings physicsSettings() {
        facade.requireModelRead("model.physicsSettings");
        return delegate.physicsSettings();
    }

    @Override public dev.turboism.sdk.cubism.model.AutoYure autoYure() {
        facade.requireModelRead("model.autoYure");
        return delegate.autoYure();
    }

    @Override public List<dev.turboism.sdk.cubism.model.AnimationDocument> animationDocuments() {
        facade.requireModelRead("model.animationDocuments");
        return delegate.animationDocuments().stream()
            .map(document -> (dev.turboism.sdk.cubism.model.AnimationDocument)
                new PermissionCheckedAnimationDocument(facade, document))
            .toList();
    }
    @Override public dev.turboism.sdk.cubism.model.ModelTextures textures() {
        facade.requireModelRead("model.textures");
        final dev.turboism.sdk.cubism.model.ModelTextures textures = delegate.textures();
        return new dev.turboism.sdk.cubism.model.ModelTextures() {
            @Override public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                facade.requireModelRead("model.textures.rawImages");
                return textures.rawImages();
            }
            @Override public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                facade.requireModelRead("model.textures.modelImageGroups");
                return textures.modelImageGroups();
            }
            @Override public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                facade.requireModelRead("model.textures.textureAtlases");
                return textures.textureAtlases();
            }
            @Override public void addModelImageGroup(final String name) {
                facade.requireModelWrite("model.textures.addModelImageGroup");
                textures.addModelImageGroup(name);
            }
            @Override public void removeModelImage(
                final dev.turboism.sdk.cubism.id.ModelImageId id
            ) {
                facade.requireModelWrite("model.textures.removeModelImage");
                textures.removeModelImage(id);
            }
            @Override public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                final String name,
                final int widthPixels,
                final int heightPixels
            ) {
                facade.requireModelWrite("model.textures.addTextureAtlas");
                return textures.addTextureAtlas(name, widthPixels, heightPixels);
            }
            @Override public void removeTextureAtlas(
                final dev.turboism.sdk.cubism.id.TextureAtlasId id
            ) {
                facade.requireModelWrite("model.textures.removeTextureAtlas");
                textures.removeTextureAtlas(id);
            }
            @Override public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {
                facade.requireModelWrite("model.textures.removeRawImage");
                textures.removeRawImage(id);
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.ParameterDefinitions parameterDefinitions() {
        facade.requireModelRead("model.parameterDefinitions");
        final dev.turboism.sdk.cubism.model.ParameterDefinitions definitions =
            delegate.parameterDefinitions();
        return new dev.turboism.sdk.cubism.model.ParameterDefinitions() {
            @Override public List<dev.turboism.sdk.cubism.model.ParameterDefinition> all() {
                facade.requireModelRead("model.parameterDefinitions.all");
                return definitions.all();
            }
            @Override public dev.turboism.sdk.cubism.model.ParameterDefinition find(
                final dev.turboism.sdk.cubism.id.ParameterId id
            ) {
                facade.requireModelRead("model.parameterDefinitions.find");
                return definitions.find(Objects.requireNonNull(id, "id"));
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.ModelStatistics statistics() {
        facade.requireModelRead("model.statistics");
        return delegate.statistics();
    }

    @Override public java.util.List<dev.turboism.sdk.cubism.clipmask.PsdClipMaskDocumentSnapshot> psdDocuments() {
        facade.requireModelRead("model.psdDocuments");
        return delegate.psdDocuments();
    }
    @Override public boolean defaultKeyformLocked() {
        facade.requireModelRead("model.defaultKeyformLocked");
        return delegate.defaultKeyformLocked();
    }
    @Override public void setDefaultKeyformLocked(final boolean locked) {
        facade.requireModelWrite("model.setDefaultKeyformLocked");
        facade.runSemantic(
            CubismOperation.SET_MODEL_DEFAULT_KEYFORM_LOCKED,
            id().value(),
            delegate::defaultKeyformLocked,
            () -> delegate.setDefaultKeyformLocked(locked)
        );
    }
    @Override public dev.turboism.sdk.cubism.model.ModelEditLevel editLevel() {
        facade.requireModelRead("model.editLevel");
        return delegate.editLevel();
    }
    @Override public void setEditLevel(
        final dev.turboism.sdk.cubism.model.ModelEditLevel level
    ) {
        facade.requireModelWrite("model.setEditLevel");
        delegate.setEditLevel(level);
    }
    @Override public dev.turboism.sdk.cubism.model.Canvas canvas() {
        facade.requireModelRead("model.canvas");
        final dev.turboism.sdk.cubism.model.Canvas canvas = delegate.canvas();
        return new dev.turboism.sdk.cubism.model.Canvas() {
            @Override public float widthPixels() {
                facade.requireModelRead("model.canvas.widthPixels");
                return canvas.widthPixels();
            }
            @Override public float heightPixels() {
                facade.requireModelRead("model.canvas.heightPixels");
                return canvas.heightPixels();
            }
            @Override public float originXPixels() {
                facade.requireModelRead("model.canvas.originXPixels");
                return canvas.originXPixels();
            }
            @Override public float originYPixels() {
                facade.requireModelRead("model.canvas.originYPixels");
                return canvas.originYPixels();
            }
            @Override public float pixelsPerUnit() {
                facade.requireModelRead("model.canvas.pixelsPerUnit");
                return canvas.pixelsPerUnit();
            }
        };
    }
    @Override public Parameters parameters() {
        facade.requireModelRead("model.parameters");
        final Parameters parameters = delegate.parameters();
        return new Parameters() {
            @Override public List<Parameter> all() {
                facade.requireModelRead("model.parameters.all");
                return parameters.all().stream()
                    .map(value -> (Parameter) new PermissionCheckedParameter(facade, value))
                    .toList();
            }
            @Override public Parameter find(final dev.turboism.sdk.cubism.id.ParameterId id) {
                facade.requireModelRead("model.parameters.find");
                return new PermissionCheckedParameter(facade, 
                    parameters.find(Objects.requireNonNull(id, "id"))
                );
            }

            @Override public Parameter create(
                final dev.turboism.sdk.cubism.model.ParameterDefinition definition
            ) {
                facade.requireModelWrite("model.parameters.create");
                return new PermissionCheckedParameter(facade, parameters.create(definition));
            }

            @Override public Parameter create(
                final dev.turboism.sdk.cubism.model.ParameterDefinition definition,
                final Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
            ) {
                facade.requireModelWrite("model.parameters.create");
                return new PermissionCheckedParameter(facade, parameters.create(definition, folderId));
            }

            @Override public Parameter copy(final dev.turboism.sdk.cubism.id.ParameterId id) {
                facade.requireModelWrite("model.parameters.copy");
                return new PermissionCheckedParameter(facade, parameters.copy(id));
            }

            @Override public void remove(final dev.turboism.sdk.cubism.id.ParameterId id) {
                facade.requireModelWrite("model.parameters.remove");
                parameters.remove(id);
            }

            @Override public java.util.Optional<Parameter> findById(
                final dev.turboism.sdk.cubism.id.ParameterId id
            ) {
                facade.requireModelRead("model.parameters.findById");
                return parameters.findById(Objects.requireNonNull(id, "id"))
                    .map(value -> (Parameter) new PermissionCheckedParameter(facade, value));
            }

            @Override public java.util.Optional<Parameter> findById(final String id) {
                return findById(new dev.turboism.sdk.cubism.id.ParameterId(
                    Objects.requireNonNull(id, "id")
                ));
            }

            @Override public List<Parameter> findByName(final String name) {
                Objects.requireNonNull(name, "name");
                return filter(parameter -> parameter.name().filter(name::equals).isPresent());
            }

            @Override public List<Parameter> search(final String text) {
                Objects.requireNonNull(text, "text");
                final String query = text.toLowerCase(java.util.Locale.ROOT);
                return filter(parameter ->
                    parameter.id().value().toLowerCase(java.util.Locale.ROOT).contains(query)
                        || parameter.name()
                            .map(value -> value.toLowerCase(java.util.Locale.ROOT).contains(query))
                            .orElse(false)
                );
            }

            @Override public List<Parameter> filter(
                final java.util.function.Predicate<Parameter> predicate
            ) {
                Objects.requireNonNull(predicate, "predicate");
                facade.requireModelRead("model.parameters.filter");
                return all().stream().filter(predicate).toList();
            }

            @Override public List<Parameter> createMany(
                final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions
            ) {
                return createMany(definitions, java.util.Optional.empty());
            }

            @Override public List<Parameter> createMany(
                final List<dev.turboism.sdk.cubism.model.ParameterDefinition> definitions,
                final java.util.Optional<dev.turboism.sdk.cubism.id.ParameterGroupId> folderId
            ) {
                facade.requireModelWrite("model.parameters.createMany");
                return parameters.createMany(definitions, folderId).stream()
                    .map(value -> (Parameter) new PermissionCheckedParameter(facade, value))
                    .toList();
            }

            @Override public void removeMany(
                final List<dev.turboism.sdk.cubism.id.ParameterId> ids
            ) {
                facade.requireModelWrite("model.parameters.removeMany");
                parameters.removeMany(ids);
            }
        };
    }
    @Override public ParameterGroups parameterGroups() {
        facade.requireModelRead("model.parameterGroups");
        final ParameterGroups groups = delegate.parameterGroups();
        return new ParameterGroups() {
            @Override public List<ParameterGroup> all() {
                facade.requireModelRead("model.parameterGroups.all");
                return groups.all().stream()
                    .map(value -> (ParameterGroup) new PermissionCheckedParameterGroup(facade, value))
                    .toList();
            }
            @Override public ParameterGroup root() {
                facade.requireModelRead("model.parameterGroups.root");
                return new PermissionCheckedParameterGroup(facade, groups.root());
            }
            @Override public ParameterGroup find(
                final dev.turboism.sdk.cubism.id.ParameterGroupId id
            ) {
                facade.requireModelRead("model.parameterGroups.find");
                return new PermissionCheckedParameterGroup(facade, 
                    groups.find(Objects.requireNonNull(id, "id"))
                );
            }

            @Override public ParameterGroup addGroup(final String name) {
                facade.requireModelWrite("model.parameterGroups.addGroup");
                return new PermissionCheckedParameterGroup(facade, groups.addGroup(name));
            }

            @Override public void removeGroup(
                final dev.turboism.sdk.cubism.id.ParameterGroupId id
            ) {
                facade.requireModelWrite("model.parameterGroups.removeGroup");
                groups.removeGroup(id);
            }

            @Override public void moveParameter(
                final dev.turboism.sdk.cubism.id.ParameterId parameterId,
                final dev.turboism.sdk.cubism.id.ParameterGroupId targetGroupId
            ) {
                facade.requireModelWrite("model.parameterGroups.moveParameter");
                groups.moveParameter(parameterId, targetGroupId);
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.ParameterBindingOperations parameterBindings(
        final dev.turboism.sdk.cubism.id.ParameterId parameterId
    ) {
        facade.requireModelRead("model.parameterBindings");
        final dev.turboism.sdk.cubism.model.ParameterBindingOperations operations =
            delegate.parameterBindings(Objects.requireNonNull(parameterId, "parameterId"));
        return new dev.turboism.sdk.cubism.model.ParameterBindingOperations() {
            private void write(
                final CubismOperation semanticOperation,
                final String operation,
                final Runnable mutation
            ) {
                facade.requireModelWrite(operation);
                facade.runSemantic(
                    semanticOperation,
                    parameterId.value(),
                    () -> bindingSnapshot(parameterId),
                    mutation
                );
            }
            @Override public void bind(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final List<dev.turboism.sdk.cubism.model.ParameterBindingPoint> points
            ) {
                write(
                    CubismOperation.BIND_PARAMETER,
                    "model.parameterBindings.bind",
                    () -> operations.bind(target, points)
                );
            }
            @Override public void createPoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.model.ParameterBindingPoint point
            ) {
                write(
                    CubismOperation.CREATE_PARAMETER_BINDING_POINT,
                    "model.parameterBindings.createPoint",
                    () -> operations.createPoint(target, point)
                );
            }
            @Override public void movePoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId,
                final float value
            ) {
                write(
                    CubismOperation.MOVE_PARAMETER_BINDING_POINT,
                    "model.parameterBindings.movePoint",
                    () -> operations.movePoint(target, pointId, value)
                );
            }
            @Override public void deletePoint(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target,
                final dev.turboism.sdk.cubism.id.ParameterBindingPointId pointId
            ) {
                write(
                    CubismOperation.DELETE_PARAMETER_BINDING_POINT,
                    "model.parameterBindings.deletePoint",
                    () -> operations.deletePoint(target, pointId)
                );
            }
            @Override public void unbind(
                final dev.turboism.sdk.cubism.model.ParameterBindingTarget target
            ) {
                write(
                    CubismOperation.UNBIND_PARAMETER,
                    "model.parameterBindings.unbind",
                    () -> operations.unbind(target)
                );
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations parameterBindingBatch() {
        facade.requireModelRead("model.parameterBindingBatch");
        final dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations operations =
            delegate.parameterBindingBatch();
        return new dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations() {
            @Override public void invert(
                final List<dev.turboism.sdk.cubism.model.ParameterBindingTarget> targets
            ) {
                facade.requireModelWrite("model.parameterBindingBatch.invert");
                facade.runSemantic(
                    CubismOperation.INVERT_PARAMETER_BINDINGS,
                    id().value(),
                    PermissionCheckedModel.this::allBindingSnapshot,
                    () -> operations.invert(targets)
                );
            }
            @Override public void transfer(
                final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
            ) {
                facade.requireModelWrite("model.parameterBindingBatch.transfer");
                facade.runSemantic(
                    CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                    id().value(),
                    PermissionCheckedModel.this::allBindingSnapshot,
                    () -> operations.transfer(plan)
                );
            }
            @Override public void transferClamped(
                final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
            ) {
                facade.requireModelWrite("model.parameterBindingBatch.transferClamped");
                facade.runSemantic(
                    CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                    id().value(),
                    PermissionCheckedModel.this::allBindingSnapshot,
                    () -> operations.transferClamped(plan)
                );
            }
            @Override public void transferMorphClamped(
                final dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan plan
            ) {
                facade.requireModelWrite("model.parameterBindingBatch.transferMorphClamped");
                facade.runSemantic(
                    CubismOperation.TRANSFER_PARAMETER_BINDINGS,
                    id().value(),
                    PermissionCheckedModel.this::allBindingSnapshot,
                    () -> operations.transferMorphClamped(plan)
                );
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.Parts parts() {
        facade.requireModelRead("model.parts");
        final dev.turboism.sdk.cubism.model.Parts parts = delegate.parts();
        return new dev.turboism.sdk.cubism.model.Parts() {
            @Override public List<dev.turboism.sdk.cubism.model.Part> all() {
                facade.requireModelRead("model.parts.all");
                return parts.all().stream()
                    .map(value -> (dev.turboism.sdk.cubism.model.Part)
                        new PermissionCheckedPart(facade, wrapperOwner, value))
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.Part find(
                final dev.turboism.sdk.cubism.model.PartId id
            ) {
                facade.requireModelRead("model.parts.find");
                return new PermissionCheckedPart(facade, 
                    wrapperOwner,
                    parts.find(Objects.requireNonNull(id, "id"))
                );
            }

            @Override public dev.turboism.sdk.cubism.model.Part add(
                final dev.turboism.sdk.cubism.model.PartId id
            ) {
                facade.requireModelWrite("model.parts.add");
                return new PermissionCheckedPart(facade, wrapperOwner, parts.add(id));
            }

            @Override public dev.turboism.sdk.cubism.model.Part add(
                final dev.turboism.sdk.cubism.model.PartId id,
                final dev.turboism.sdk.cubism.model.PartId parentId
            ) {
                facade.requireModelWrite("model.parts.add");
                return new PermissionCheckedPart(facade, 
                    wrapperOwner,
                    parts.add(id, parentId)
                );
            }

            @Override public dev.turboism.sdk.cubism.model.Part copy(
                final dev.turboism.sdk.cubism.model.PartId id
            ) {
                facade.requireModelWrite("model.parts.copy");
                return new PermissionCheckedPart(facade, wrapperOwner, parts.copy(id));
            }

            @Override public void remove(final dev.turboism.sdk.cubism.model.PartId id) {
                facade.requireModelWrite("model.parts.remove");
                parts.remove(id);
            }

            @Override public dev.turboism.sdk.cubism.model.Part create(
                final String name,
                final dev.turboism.sdk.cubism.model.Part parent,
                final int index
            ) {
                facade.requireModelWrite("model.parts.create");
                return new PermissionCheckedPart(facade, wrapperOwner, parts.create(
                    name,
                    facade.unwrapPart(wrapperOwner, parent),
                    index
                ));
            }
            @Override public void remove(
                final dev.turboism.sdk.cubism.model.Part part
            ) {
                facade.requireModelWrite("model.parts.remove");
                parts.remove(facade.unwrapPart(wrapperOwner, part));
            }

            @Override public dev.turboism.sdk.cubism.model.Part create(final String name) {
                return create(name, null, -1);
            }

            @Override public dev.turboism.sdk.cubism.model.Part add(final String id) {
                return add(new dev.turboism.sdk.cubism.model.PartId(id));
            }

            @Override public dev.turboism.sdk.cubism.model.Part add(
                final String id,
                final dev.turboism.sdk.cubism.model.PartId parentId
            ) {
                return add(new dev.turboism.sdk.cubism.model.PartId(id), parentId);
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.Drawables drawables() {
        facade.requireModelRead("model.drawables");
        final dev.turboism.sdk.cubism.model.Drawables values = delegate.drawables();
        return new dev.turboism.sdk.cubism.model.Drawables() {
            @Override public List<dev.turboism.sdk.cubism.model.Drawable> all() {
                facade.requireModelRead("model.drawables.all");
                return values.all().stream()
                    .map(value -> (dev.turboism.sdk.cubism.model.Drawable)
                        new PermissionCheckedDrawable(facade, wrapperOwner, value))
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.Drawable find(
                final dev.turboism.sdk.cubism.id.ArtMeshId id
            ) {
                facade.requireModelRead("model.drawables.find");
                return new PermissionCheckedDrawable(facade, 
                    wrapperOwner,
                    values.find(Objects.requireNonNull(id, "id"))
                );
            }
            @Override public dev.turboism.sdk.cubism.model.Drawable create(
                final String name,
                final dev.turboism.sdk.cubism.model.Part parent,
                final int index,
                final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry
            ) {
                facade.requireModelWrite("model.drawables.create");
                return new PermissionCheckedDrawable(facade, wrapperOwner, values.create(
                    name,
                    facade.unwrapPart(wrapperOwner, parent),
                    index,
                    geometry
                ));
            }
            @Override public void remove(
                final dev.turboism.sdk.cubism.model.Drawable drawable
            ) {
                facade.requireModelWrite("model.drawables.remove");
                values.remove(facade.unwrapDrawable(drawable));
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.Deformers deformers() {
        facade.requireModelRead("model.deformers");
        final dev.turboism.sdk.cubism.model.Deformers values = delegate.deformers();
        return new dev.turboism.sdk.cubism.model.Deformers() {
            @Override public List<dev.turboism.sdk.cubism.model.Deformer> all() {
                facade.requireModelRead("model.deformers.all");
                return values.all().stream()
                    .map(PermissionCheckedModel.this::wrapDeformer)
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.Deformer find(
                final dev.turboism.sdk.cubism.id.DeformerId id
            ) {
                facade.requireModelRead("model.deformers.find");
                return wrapDeformer(values.find(Objects.requireNonNull(id, "id")));
            }
            @Override public dev.turboism.sdk.cubism.model.WarpDeformer createWarp(
                final String name,
                final dev.turboism.sdk.cubism.model.Part parent,
                final int index,
                final int rows,
                final int columns
            ) {
                facade.requireModelWrite("model.deformers.createWarp");
                return new PermissionCheckedWarpDeformer(facade, wrapperOwner, values.createWarp(
                    name,
                    facade.unwrapPart(wrapperOwner, parent),
                    index,
                    rows,
                    columns
                ));
            }
            @Override public dev.turboism.sdk.cubism.model.RotationDeformer createRotation(
                final String name,
                final dev.turboism.sdk.cubism.model.Part parent,
                final int index
            ) {
                facade.requireModelWrite("model.deformers.createRotation");
                return new PermissionCheckedRotationDeformer(facade, 
                    wrapperOwner,
                    values.createRotation(
                        name,
                        facade.unwrapPart(wrapperOwner, parent),
                        index
                    )
                );
            }
            @Override public void remove(
                final dev.turboism.sdk.cubism.model.Deformer deformer
            ) {
                facade.requireModelWrite("model.deformers.remove");
                values.remove(facade.unwrapDeformer(deformer));
            }
        };
    }
    private dev.turboism.sdk.cubism.model.Deformer wrapDeformer(
        final dev.turboism.sdk.cubism.model.Deformer value
    ) {
        if (value instanceof dev.turboism.sdk.cubism.model.WarpDeformer warp) {
            return new PermissionCheckedWarpDeformer(facade, wrapperOwner, warp);
        }
        if (value instanceof dev.turboism.sdk.cubism.model.RotationDeformer rotation) {
            return new PermissionCheckedRotationDeformer(facade, wrapperOwner, rotation);
        }
        return new PermissionCheckedDeformer(facade, wrapperOwner, value);
    }
    @Override public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() {
        facade.requireModelRead("model.warpDeformers");
        final dev.turboism.sdk.cubism.model.WarpDeformers values = delegate.warpDeformers();
        return new dev.turboism.sdk.cubism.model.WarpDeformers() {
            @Override public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                facade.requireModelRead("model.warpDeformers.all");
                return values.all().stream()
                    .map(value -> (dev.turboism.sdk.cubism.model.WarpDeformer)
                        new PermissionCheckedWarpDeformer(facade, wrapperOwner, value))
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.WarpDeformer find(
                final dev.turboism.sdk.cubism.id.DeformerId id
            ) {
                facade.requireModelRead("model.warpDeformers.find");
                return new PermissionCheckedWarpDeformer(facade, 
                    wrapperOwner,
                    values.find(Objects.requireNonNull(id, "id"))
                );
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() {
        facade.requireModelRead("model.rotationDeformers");
        final dev.turboism.sdk.cubism.model.RotationDeformers values =
            delegate.rotationDeformers();
        return new dev.turboism.sdk.cubism.model.RotationDeformers() {
            @Override public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() {
                facade.requireModelRead("model.rotationDeformers.all");
                return values.all().stream()
                    .map(value -> (dev.turboism.sdk.cubism.model.RotationDeformer)
                        new PermissionCheckedRotationDeformer(facade, wrapperOwner, value))
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.RotationDeformer find(
                final dev.turboism.sdk.cubism.id.DeformerId id
            ) {
                facade.requireModelRead("model.rotationDeformers.find");
                return new PermissionCheckedRotationDeformer(facade, 
                    wrapperOwner,
                    values.find(Objects.requireNonNull(id, "id"))
                );
            }
        };
    }
    @Override public dev.turboism.sdk.cubism.model.Glues glues() {
        facade.requireModelRead("model.glues");
        final dev.turboism.sdk.cubism.model.Glues values = delegate.glues();
        return new dev.turboism.sdk.cubism.model.Glues() {
            @Override public List<dev.turboism.sdk.cubism.model.Glue> all() {
                facade.requireModelRead("model.glues.all");
                return values.all().stream()
                    .map(value -> (dev.turboism.sdk.cubism.model.Glue)
                        new PermissionCheckedGlue(facade, value))
                    .toList();
            }
            @Override public dev.turboism.sdk.cubism.model.Glue find(
                final dev.turboism.sdk.cubism.model.GlueId id
            ) {
                facade.requireModelRead("model.glues.find");
                return new PermissionCheckedGlue(facade, 
                    values.find(Objects.requireNonNull(id, "id"))
                );
            }
            @Override public java.util.Optional<String> providerVersion() {
                facade.requireModelRead("model.glues.providerVersion");
                return values.providerVersion();
            }
        };
    }
    @Override public void update() {
        facade.requireModelWrite("model.update");
        facade.runSemanticConfirmed(CubismOperation.UPDATE_MODEL, id().value(), delegate::update);
    }

    private List<dev.turboism.sdk.cubism.model.ParameterBinding> bindingSnapshot(
        final dev.turboism.sdk.cubism.id.ParameterId parameterId
    ) {
        return List.copyOf(delegate.parameters().find(parameterId).getParameterBindings());
    }

    private List<dev.turboism.sdk.cubism.model.ParameterBinding> allBindingSnapshot() {
        return delegate.parameters().all().stream()
            .flatMap(parameter -> parameter.getParameterBindings().stream())
            .toList();
    }

    @Override
    public void replaceArtMeshClipMasks(
        final List<dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement> replacements
    ) {
        facade.requireModelWrite("model.replaceArtMeshClipMasks");
        delegate.replaceArtMeshClipMasks(List.copyOf(Objects.requireNonNull(replacements, "replacements")));
    }
}
