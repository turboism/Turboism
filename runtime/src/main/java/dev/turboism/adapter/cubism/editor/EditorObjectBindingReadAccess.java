package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;
import static dev.turboism.adapter.cubism.editor.EditorObjectReadCore.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorParameterBindingReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Exact-version parameter-binding reads behind {@link EditorObjectReadAccess}. */
final class EditorObjectBindingReadAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorMorphTargetAccess morphTargetAccess;
    private final EditorObjectReadCore core;

    EditorObjectBindingReadAccess(
        final VerifiedMemberResolver resolver,
        final EditorMorphTargetAccess morphTargetAccess,
        final EditorObjectReadCore core
    ) {
        this.resolver = resolver;
        this.morphTargetAccess = morphTargetAccess;
        this.core = core;
    }

    List<ParameterBinding> parameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final ParameterId parameterId
    ) {
        Objects.requireNonNull(parameterId, "parameterId");
        requireBindingReadAuthorized();
        final ArrayList<ParameterBinding> result = new ArrayList<>();
        for (ObjectRef value : core.artMeshes(identity, source, model)) {
            parameterBindings(
                identity,
                source,
                model,
                value.source(),
                ParameterBindingTarget.artMesh(new ArtMeshId(value.id()))
            ).stream().filter(binding -> binding.parameterId().equals(parameterId)).forEach(result::add);
        }
        for (DeformerRef value : core.deformerRefs(identity, source, model)) {
            final ParameterBindingTarget target = value.kind() == Kind.WARP
                ? ParameterBindingTarget.warpDeformer(new DeformerId(value.id()))
                : ParameterBindingTarget.rotationDeformer(new DeformerId(value.id()));
            parameterBindings(identity, source, model, value.source(), target).stream()
                .filter(binding -> binding.parameterId().equals(parameterId))
                .forEach(result::add);
        }
        return List.copyOf(result);
    }

    Object bindingTargetSource(
        final String identity,
        final Object source,
        final Object model,
        final ParameterBindingTarget target
    ) {
        Objects.requireNonNull(target, "target");
        return switch (target.type()) {
            case ART_MESH -> core.artMeshes(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .map(ObjectRef::source)
                .findFirst()
                .orElseThrow(() -> stale("ArtMesh", target.id()));
            case WARP_DEFORMER, ROTATION_DEFORMER -> core.deformerRefs(identity, source, model).stream()
                .filter(value -> value.id().equals(target.id()))
                .filter(value -> value.kind() == (target.type() == dev.turboism.sdk.cubism.model.ParameterBindingTargetType.WARP_DEFORMER
                    ? Kind.WARP : Kind.ROTATION))
                .map(DeformerRef::source)
                .findFirst()
                .orElseThrow(() -> stale("Deformer", target.id()));
        };
    }

    private void requireBindingReadAuthorized() {
        if (!resolver.authorizesFeature(
            EditorParameterBindingReadSelectorContract.ADAPTER_SLICE_ID,
            EditorParameterBindingReadSelectorContract.CAPABILITY_ID,
            EditorParameterBindingReadSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor parameter-binding reads require exact verified host evidence."
            );
        }
    }

    List<ParameterBinding> parameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final Object objectSource,
        final ParameterBindingTarget target
    ) {
        // One row per parameter, whatever container it appears in: the Editor can
        // hold the same parameter in the keyform grid (even several entries) and
        // in the morph-target-set. Keyform rows are inserted first (putIfAbsent
        // keeps the first keyform entry); morph rows are then put, replacing the
        // value for an existing key without moving it (the row keeps its keyform
        // position but becomes the BLEND_SHAPE binding) and appending new
        // parameters after. Morph (BLEND_SHAPE) always wins.
        final java.util.LinkedHashMap<ParameterId, ParameterBinding> byParameter
            = new java.util.LinkedHashMap<>();
        for (Object hostBinding : hostBindings(objectSource)) {
            final ParameterId parameterId = bindingParameterId(hostBinding);
            final List<?> hostKeys = list(
                resolver.invoke("cubism.editor-model.keyform-binding.keys", hostBinding),
                "Editor binding keys"
            );
            final ArrayList<ParameterBindingPoint> points = new ArrayList<>(hostKeys.size());
            for (int index = 0; index < hostKeys.size(); index++) {
                final float value = number(hostKeys.get(index), "Editor binding key");
                points.add(new ParameterBindingPoint(
                    new ParameterBindingPointId(parameterId.value() + ":" + index),
                    value
                ));
            }
            byParameter.putIfAbsent(parameterId, new ParameterBinding(
                target,
                parameterId,
                ParameterBindingFamily.KEYFORM_GRID,
                points
            ));
        }
        for (ParameterBinding morph : morphParameterBindings(
            identity, source, model, objectSource, target
        )) {
            byParameter.put(morph.parameterId(), morph);
        }
        return List.copyOf(byParameter.values());
    }

    /**
     * Morph-target bindings of one object source, keyform grid first, morph after.
     *
     * <p>Morph containers are an Editor concept the Core backend does not expose:
     * when the verified plan lacks the morph-target capability the morph portion
     * is dropped (fail soft) while the keyform portion keeps its fail behavior.</p>
     */
    private List<ParameterBinding> morphParameterBindings(
        final String identity,
        final Object source,
        final Object model,
        final Object objectSource,
        final ParameterBindingTarget target
    ) {
        final java.util.LinkedHashMap<ParameterId, List<ParameterBindingPoint>> pointsByParameter
            = new java.util.LinkedHashMap<>();
        try {
            final List<dev.turboism.sdk.cubism.model.MorphTarget> morphTargets =
                morphTargetAccess.morphTargets(identity, source, model, objectSource).all();
            for (int index = 0; index < morphTargets.size(); index++) {
                final dev.turboism.sdk.cubism.model.MorphTarget morphTarget = morphTargets.get(index);
                final ParameterId parameterId = morphTarget.parameterId();
                pointsByParameter.computeIfAbsent(parameterId, ignored -> new ArrayList<>()).add(
                    new ParameterBindingPoint(
                        new ParameterBindingPointId(parameterId.value() + ":morph:" + index),
                        morphTarget.keyValue()
                    )
                );
            }
        } catch (UnsupportedOperationException unsupported) {
            return List.of();
        }
        return pointsByParameter.entrySet().stream()
            .map(entry -> new ParameterBinding(
                target,
                entry.getKey(),
                ParameterBindingFamily.BLEND_SHAPE,
                List.copyOf(entry.getValue())
            ))
            .toList();
    }

    /**
     * Whether the parameter is marked Combined in the Editor. Resolution failures
     * (absent mapping, invalid host value) fail soft and report non-combined.
     */
    boolean parameterCombined(final Object model, final ParameterId parameterId) {
        try {
            final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
            final List<?> parameters = list(
                resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
                "Editor parameter collection"
            );
            for (Object parameter : parameters) {
                if (!resolver.isInstance("cubism.editor-model.parameter.class", parameter)) {
                    continue;
                }
                final String id = text(
                    resolver.invoke("cubism.editor-model.id.value",
                        resolver.invoke("cubism.editor-model.parameter.id", parameter)),
                    "Editor parameter ID"
                );
                if (!parameterId.value().equals(id)) {
                    continue;
                }
                final Object source = resolver.invoke("cubism.editor-model.parameter.source", parameter);
                final Object raw = resolver.invoke("cubism.editor-model.parameter-source.combined", source);
                return raw instanceof Boolean combined && combined;
            }
        } catch (RuntimeException unavailable) {
            return false;
        }
        return false;
    }

    List<ParameterId> parameterIds(final Object objectSource) {
        final java.util.HashSet<ParameterId> unique = new java.util.HashSet<>();
        final ArrayList<ParameterId> result = new ArrayList<>();
        for (Object hostBinding : hostBindings(objectSource)) {
            final ParameterId id = bindingParameterId(hostBinding);
            if (!unique.add(id)) throw unavailable("Editor keyform binding parameters are not unique.");
            result.add(id);
        }
        return List.copyOf(result);
    }

    private List<?> hostBindings(final Object objectSource) {
        requireBindingReadAuthorized();
        final Object grid = resolver.invoke(
            "cubism.editor-model.parameter-controllable.keyform-grid",
            objectSource
        );
        if (!resolver.isInstance("cubism.editor-model.keyform-grid.class", grid)) {
            throw unavailable("Editor keyform grid is unavailable.");
        }
        final List<?> bindings = list(
            resolver.invoke("cubism.editor-model.keyform-grid.bindings", grid),
            "Editor keyform bindings"
        );
        for (Object binding : bindings) {
            if (!resolver.isInstance("cubism.editor-model.keyform-binding.class", binding)) {
                throw unavailable("Editor keyform binding is invalid.");
            }
        }
        return bindings;
    }

    private ParameterId bindingParameterId(final Object hostBinding) {
        final Object hostId = resolver.invoke(
            "cubism.editor-model.keyform-binding.parameter-id",
            hostBinding
        );
        return new ParameterId(text(
            resolver.invoke("cubism.editor-model.id.value", hostId),
            "Editor binding parameter ID"
        ));
    }
}
