package dev.turboism.adapter.cubism.model;

import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectDeletePolicy;
import dev.turboism.sdk.cubism.model.ModelObjectDescriptor;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectOperationException;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.ModelObjectService;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.permission.PermissionIds;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Permission-checked automation service backed by the active natural Editor model API. */
public final class RuntimeModelObjectService implements ModelObjectService {

    private final CubismModelAccess modelAccess;
    private final PermissionChecker permissions;
    private final BooleanSupplier activeScope;
    private final RuntimeModelObjectCreateProvider createProvider;

    public RuntimeModelObjectService(
        final CubismModelAccess modelAccess,
        final PermissionChecker permissions,
        final BooleanSupplier activeScope
    ) {
        this.modelAccess = Objects.requireNonNull(modelAccess, "modelAccess");
        this.permissions = Objects.requireNonNull(permissions, "permissions");
        this.activeScope = Objects.requireNonNull(activeScope, "activeScope");
        this.createProvider = modelAccess instanceof RuntimeModelObjectCreateProvider provider
            ? provider
            : null;
    }

    @Override
    public List<ModelObjectDescriptor> list() {
        requireRead("modelObjects.list");
        return translate("List model objects", () -> list(activeModel()));
    }

    @Override
    public ModelObjectDescriptor rename(
        final ModelObjectReference target,
        final String name
    ) {
        requireWrite("modelObjects.rename");
        final ModelObjectReference checkedTarget = Objects.requireNonNull(target, "target");
        final String checkedName = normalizeName(name);
        return translate("Rename model object", () -> {
            final CubismModel model = activeModel();
            switch (checkedTarget.kind()) {
                case PART -> model.parts().find(new PartId(checkedTarget.id()))
                    .setName(checkedName);
                case ART_MESH -> model.drawables().find(new ArtMeshId(checkedTarget.id()))
                    .setName(checkedName);
                case WARP_DEFORMER -> model.warpDeformers()
                    .find(new DeformerId(checkedTarget.id())).setName(checkedName);
                case ROTATION_DEFORMER -> model.rotationDeformers()
                    .find(new DeformerId(checkedTarget.id())).setName(checkedName);
            }
            return describe(model, checkedTarget);
        });
    }

    @Override
    public ModelObjectDescriptor reparent(
        final ModelObjectReference target,
        final ModelObjectReference parent,
        final int index
    ) {
        requireWrite("modelObjects.reparent");
        final ModelObjectReference checkedTarget = Objects.requireNonNull(target, "target");
        final ModelObjectReference checkedParent = Objects.requireNonNull(parent, "parent");
        return translate(
            "Reparent model object",
            () -> reparent(activeModel(), checkedTarget, checkedParent, index)
        );
    }

    @Override
    public ModelObjectDescriptor create(final ModelObjectCreateRequest request) {
        requireWrite("modelObjects.create");
        final ModelObjectCreateRequest checked = Objects.requireNonNull(request, "request");
        return translate("Create model object", () -> {
            if (createProvider != null) {
                createProvider.requireCreateSupported(checked);
            }
            return create(activeModel(), checked);
        });
    }

    @Override
    public void delete(
        final ModelObjectReference target,
        final ModelObjectDeletePolicy policy
    ) {
        requireWrite("modelObjects.delete");
        final ModelObjectReference checkedTarget = Objects.requireNonNull(target, "target");
        final ModelObjectDeletePolicy checkedPolicy = Objects.requireNonNull(policy, "policy");
        translate("Delete model object", () -> {
            final CubismModel model = activeModel();
            if (checkedPolicy == ModelObjectDeletePolicy.REJECT_REFERENCED) {
                ensureUnreferenced(model, checkedTarget);
            }
            switch (checkedTarget.kind()) {
                case PART -> {
                    final Part part = model.parts().find(new PartId(checkedTarget.id()));
                    model.parts().remove(part);
                }
                case ART_MESH -> {
                    final Drawable drawable = model.drawables()
                        .find(new ArtMeshId(checkedTarget.id()));
                    model.drawables().remove(drawable);
                }
                case WARP_DEFORMER -> {
                    final WarpDeformer deformer = model.warpDeformers()
                        .find(new DeformerId(checkedTarget.id()));
                    model.deformers().remove(deformer);
                }
                case ROTATION_DEFORMER -> {
                    final RotationDeformer deformer = model.rotationDeformers()
                        .find(new DeformerId(checkedTarget.id()));
                    model.deformers().remove(deformer);
                }
            }
            return null;
        });
    }

    private ModelObjectDescriptor reparent(
        final CubismModel model,
        final ModelObjectReference target,
        final ModelObjectReference parent,
        final int index
    ) {
        if (target.equals(parent)) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.CONFLICT,
                "A model object cannot be its own parent"
            );
        }
        if (parent.kind() == ModelObjectKind.ART_MESH) {
            throw new IllegalArgumentException("an ArtMesh cannot be used as a parent");
        }
        if (target.kind() == ModelObjectKind.PART && parent.kind() != ModelObjectKind.PART) {
            throw new IllegalArgumentException("a Part parent must also be a Part");
        }

        final ParentResolution resolvedParent = resolveParent(model, Optional.of(parent));
        switch (target.kind()) {
            case PART -> model.parts().find(new PartId(target.id()))
                .setParent(resolvedParent.part(), index);
            case ART_MESH -> {
                final Drawable drawable = model.drawables().find(new ArtMeshId(target.id()));
                if (resolvedParent.deformer() != null) {
                    drawable.setParent(resolvedParent.deformer(), index);
                } else {
                    drawable.setParent(resolvedParent.part(), index);
                }
            }
            case WARP_DEFORMER -> {
                final WarpDeformer deformer = model.warpDeformers()
                    .find(new DeformerId(target.id()));
                if (resolvedParent.deformer() != null) {
                    deformer.setParent(resolvedParent.deformer(), index);
                } else {
                    deformer.setParent(resolvedParent.part(), index);
                }
            }
            case ROTATION_DEFORMER -> {
                final RotationDeformer deformer = model.rotationDeformers()
                    .find(new DeformerId(target.id()));
                if (resolvedParent.deformer() != null) {
                    deformer.setParent(resolvedParent.deformer(), index);
                } else {
                    deformer.setParent(resolvedParent.part(), index);
                }
            }
        }
        return describe(model, target);
    }

    private ModelObjectDescriptor create(
        final CubismModel model,
        final ModelObjectCreateRequest request
    ) {
        if (createProvider != null) {
            final ModelObjectReference reference = Objects.requireNonNull(
                createProvider.createModelObject(model, request),
                "created model-object reference"
            );
            return describeCommitted(reference, () -> describe(model, reference));
        }
        final ParentResolution parent = resolveParent(model, request.parent());
        if (request instanceof ModelObjectCreateRequest.Part partRequest) {
            final Part created = model.parts().create(
                partRequest.name(),
                parent.part(),
                -1
            );
            final ModelObjectReference reference = new ModelObjectReference(
                ModelObjectKind.PART,
                created.id().value()
            );
            return describeCommitted(reference, () -> describe(created));
        }
        if (request instanceof ModelObjectCreateRequest.ArtMesh artMeshRequest) {
            final Drawable created = model.drawables().create(
                artMeshRequest.name(),
                parent.part(),
                -1,
                artMeshRequest.geometry()
            );
            if (parent.deformer() != null) {
                created.setParent(parent.deformer(), -1);
            }
            return describe(model, new ModelObjectReference(
                ModelObjectKind.ART_MESH,
                created.id().value()
            ));
        }
        if (request instanceof ModelObjectCreateRequest.WarpDeformer warpRequest) {
            final WarpDeformer created = model.deformers().createWarp(
                warpRequest.name(),
                parent.part(),
                -1,
                warpRequest.grid().rows(),
                warpRequest.grid().columns()
            );
            if (!created.grid().equals(warpRequest.grid())) {
                created.replaceGrid(warpRequest.grid());
            }
            if (parent.deformer() != null) {
                created.setParent(parent.deformer(), -1);
            }
            return describe(model, new ModelObjectReference(
                ModelObjectKind.WARP_DEFORMER,
                created.id().value()
            ));
        }
        if (request instanceof ModelObjectCreateRequest.RotationDeformer rotationRequest) {
            final RotationDeformer created = model.deformers().createRotation(
                rotationRequest.name(),
                parent.part(),
                -1
            );
            if (!created.form().equals(rotationRequest.form())) {
                created.replaceForm(rotationRequest.form());
            }
            if (parent.deformer() != null) {
                created.setParent(parent.deformer(), -1);
            }
            return describe(model, new ModelObjectReference(
                ModelObjectKind.ROTATION_DEFORMER,
                created.id().value()
            ));
        }
        throw new IllegalArgumentException(
            "Unsupported model-object create request: " + request.getClass().getName()
        );
    }

    private static List<ModelObjectDescriptor> list(final CubismModel model) {
        final Map<DeformerId, ModelObjectKind> deformerKinds = deformerKinds(model);
        final ArrayList<ModelObjectDescriptor> result = new ArrayList<>();
        model.parts().all().forEach(part -> result.add(describe(part)));
        model.drawables().all().forEach(drawable ->
            result.add(describe(deformerKinds, drawable))
        );
        model.warpDeformers().all().forEach(deformer ->
            result.add(describe(deformerKinds, ModelObjectKind.WARP_DEFORMER, deformer))
        );
        model.rotationDeformers().all().forEach(deformer ->
            result.add(describe(
                deformerKinds,
                ModelObjectKind.ROTATION_DEFORMER,
                deformer
            ))
        );
        result.sort(Comparator
            .comparing((ModelObjectDescriptor value) -> value.reference().kind().ordinal())
            .thenComparing(value -> value.reference().id()));
        return List.copyOf(result);
    }

    private ModelObjectDescriptor describe(
        final CubismModel model,
        final ModelObjectReference reference
    ) {
        if (reference.kind() == ModelObjectKind.PART) {
            return describe(model.parts().find(new PartId(reference.id())));
        }
        final Map<DeformerId, ModelObjectKind> deformerKinds = deformerKinds(model);
        return switch (reference.kind()) {
            case PART -> throw new AssertionError("handled above");
            case ART_MESH -> describe(
                deformerKinds,
                model.drawables().find(new ArtMeshId(reference.id()))
            );
            case WARP_DEFORMER -> describe(
                deformerKinds,
                ModelObjectKind.WARP_DEFORMER,
                model.warpDeformers().find(new DeformerId(reference.id()))
            );
            case ROTATION_DEFORMER -> describe(
                deformerKinds,
                ModelObjectKind.ROTATION_DEFORMER,
                model.rotationDeformers().find(new DeformerId(reference.id()))
            );
        };
    }

    private static ModelObjectDescriptor describe(final Part part) {
        return new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.PART, part.id().value()),
            part.name(),
            part.parentId().map(id ->
                new ModelObjectReference(ModelObjectKind.PART, id.value())
            )
        );
    }

    private static ModelObjectDescriptor describe(
        final Map<DeformerId, ModelObjectKind> deformerKinds,
        final Drawable drawable
    ) {
        final Optional<ModelObjectReference> parent = drawable.parentDeformerId()
            .map(id -> deformerReference(deformerKinds, id))
            .or(() -> drawable.parentPartId().map(id ->
                new ModelObjectReference(ModelObjectKind.PART, id.value())
            ));
        return new ModelObjectDescriptor(
            new ModelObjectReference(ModelObjectKind.ART_MESH, drawable.id().value()),
            drawable.name(),
            parent
        );
    }

    private static ModelObjectDescriptor describe(
        final Map<DeformerId, ModelObjectKind> deformerKinds,
        final ModelObjectKind kind,
        final Deformer deformer
    ) {
        final Optional<ModelObjectReference> parent = deformer.parentDeformerId()
            .map(id -> deformerReference(deformerKinds, id))
            .or(() -> deformer.parentPartId().map(id ->
                new ModelObjectReference(ModelObjectKind.PART, id.value())
            ));
        return new ModelObjectDescriptor(
            new ModelObjectReference(kind, deformer.id().value()),
            deformer.name(),
            parent
        );
    }

    private static Map<DeformerId, ModelObjectKind> deformerKinds(
        final CubismModel model
    ) {
        final LinkedHashMap<DeformerId, ModelObjectKind> result = new LinkedHashMap<>();
        model.warpDeformers().all().forEach(value ->
            putDeformerKind(result, value.id(), ModelObjectKind.WARP_DEFORMER)
        );
        model.rotationDeformers().all().forEach(value ->
            putDeformerKind(result, value.id(), ModelObjectKind.ROTATION_DEFORMER)
        );
        return Map.copyOf(result);
    }

    private static void putDeformerKind(
        final Map<DeformerId, ModelObjectKind> target,
        final DeformerId id,
        final ModelObjectKind kind
    ) {
        final ModelObjectKind previous = target.putIfAbsent(id, kind);
        if (previous != null) {
            throw new IllegalStateException("Cubism Deformer ID is ambiguous: " + id.value());
        }
    }

    private static ModelObjectReference deformerReference(
        final Map<DeformerId, ModelObjectKind> deformerKinds,
        final DeformerId id
    ) {
        final ModelObjectKind kind = deformerKinds.get(id);
        if (kind == null) {
            throw new IllegalStateException("Cubism parent Deformer is absent: " + id.value());
        }
        return new ModelObjectReference(kind, id.value());
    }

    private static ParentResolution resolveParent(
        final CubismModel model,
        final Optional<ModelObjectReference> parent
    ) {
        if (parent.isEmpty()) return new ParentResolution(null, null);
        final ModelObjectReference reference = parent.orElseThrow();
        if (reference.kind() == ModelObjectKind.PART) {
            return new ParentResolution(
                model.parts().find(new PartId(reference.id())),
                null
            );
        }
        final Deformer deformer = switch (reference.kind()) {
            case PART, ART_MESH -> throw new IllegalArgumentException(
                "The requested object kind cannot be a parent: " + reference.kind()
            );
            case WARP_DEFORMER -> model.warpDeformers()
                .find(new DeformerId(reference.id()));
            case ROTATION_DEFORMER -> model.rotationDeformers()
                .find(new DeformerId(reference.id()));
        };
        final Part part = deformer.parentPartId()
            .map(model.parts()::find)
            .orElse(null);
        return new ParentResolution(part, deformer);
    }

    private static ModelObjectDescriptor describeCommitted(
        final ModelObjectReference reference,
        final Supplier<ModelObjectDescriptor> readback
    ) {
        try {
            return readback.get();
        } catch (RuntimeException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.COMMITTED,
                "Model object was created, but its descriptor could not be read back",
                failure,
                Optional.of(reference)
            );
        }
    }

    private static void ensureUnreferenced(
        final CubismModel model,
        final ModelObjectReference target
    ) {
        for (ModelObjectDescriptor descriptor : list(model)) {
            if (descriptor.reference().equals(target)) continue;
            if (descriptor.parent().filter(target::equals).isPresent()) {
                throw conflict(target, "a child object");
            }
        }
        if (target.kind() != ModelObjectKind.ART_MESH) return;
        final ArtMeshId id = new ArtMeshId(target.id());
        for (Drawable drawable : model.drawables().all()) {
            if (!drawable.id().equals(id) && drawable.maskIds().contains(id)) {
                throw conflict(target, "an ArtMesh mask");
            }
        }
        for (var glue : model.glues().all()) {
            if (glue.drawableAId().equals(id) || glue.drawableBId().equals(id)) {
                throw conflict(target, "a Glue relation");
            }
        }
    }

    private CubismModel activeModel() {
        return Objects.requireNonNull(modelAccess.active(), "active model");
    }

    private void requireRead(final String operation) {
        requireActive();
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_READ, operation);
    }

    private void requireWrite(final String operation) {
        requireRead(operation + ".read");
        permissions.check(PermissionIds.TURBOISM_CUBISM_MODEL_WRITE, operation);
    }

    private void requireActive() {
        if (!activeScope.getAsBoolean()) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.STALE,
                "Model-object service is stale because the owning plugin is disabled"
            );
        }
    }

    private static String normalizeName(final String value) {
        final String result = Objects.requireNonNull(value, "name").strip();
        if (result.isEmpty()) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.INVALID_REQUEST,
                "name must not be blank"
            );
        }
        if (result.length() > 256) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.INVALID_REQUEST,
                "name must not exceed 256 characters"
            );
        }
        return result;
    }

    private static ModelObjectOperationException conflict(
        final ModelObjectReference target,
        final String reference
    ) {
        return new ModelObjectOperationException(
            ModelObjectOperationException.Code.CONFLICT,
            target.kind() + " " + target.id() + " is still referenced by " + reference
        );
    }

    private static <T> T translate(final String action, final Supplier<T> invocation) {
        try {
            return invocation.get();
        } catch (ModelObjectOperationException failure) {
            throw failure;
        } catch (NoSuchElementException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.NOT_FOUND,
                failure.getMessage() == null ? action + " target was not found" : failure.getMessage(),
                failure
            );
        } catch (UnsupportedOperationException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.UNAVAILABLE,
                failure.getMessage() == null ? action + " is unavailable" : failure.getMessage(),
                failure
            );
        } catch (IllegalArgumentException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.INVALID_REQUEST,
                failure.getMessage() == null ? action + " request is invalid" : failure.getMessage(),
                failure
            );
        } catch (IllegalStateException failure) {
            final String message = failure.getMessage() == null
                ? action + " is unavailable"
                : failure.getMessage();
            final String normalized = message.toLowerCase(Locale.ROOT);
            final ModelObjectOperationException.Code code;
            if (normalized.contains("stale")) {
                code = ModelObjectOperationException.Code.STALE;
            } else if (normalized.contains("unavailable")
                || normalized.contains("no active")
                || normalized.contains("absent")) {
                code = ModelObjectOperationException.Code.UNAVAILABLE;
            } else if (normalized.contains("conflict")
                || normalized.contains("referenced")
                || normalized.contains("cycle")) {
                code = ModelObjectOperationException.Code.CONFLICT;
            } else {
                code = ModelObjectOperationException.Code.FAILED;
            }
            throw new ModelObjectOperationException(code, message, failure);
        } catch (RuntimeException failure) {
            throw new ModelObjectOperationException(
                ModelObjectOperationException.Code.FAILED,
                failure.getMessage() == null ? action + " failed" : failure.getMessage(),
                failure
            );
        }
    }

    private record ParentResolution(Part part, Deformer deformer) {
    }
}
