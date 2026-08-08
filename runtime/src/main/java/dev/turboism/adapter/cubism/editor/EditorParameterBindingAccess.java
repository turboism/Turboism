package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorParameterBindingWriteSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTargetType;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Serial exact-version Editor authoring path for one parameter's object bindings. */
final class EditorParameterBindingAccess implements ParameterBindingOperations {

    @FunctionalInterface
    interface CurrentGuard {
        void requireCurrent(String identity, Object model);
    }

    @FunctionalInterface
    interface BindingReader {
        List<ParameterBinding> read(String identity, Object source, Object model, ParameterId parameterId);
    }

    @FunctionalInterface
    interface TargetLookup {
        Object find(String identity, Object source, Object model, ParameterBindingTarget target);
    }

    @FunctionalInterface
    interface ParameterSourceLookup {
        Object find(Object model, ParameterId parameterId);
    }

    private static final float EPSILON = 0.000001F;

    private final VerifiedMemberResolver resolver;
    private final String identity;
    private final Object modelSource;
    private final Object model;
    private final ParameterId parameterId;
    private final CurrentGuard currentGuard;
    private final BindingReader bindingReader;
    private final TargetLookup targetLookup;
    private final ParameterSourceLookup parameterSourceLookup;

    EditorParameterBindingAccess(
        final VerifiedMemberResolver resolver,
        final String identity,
        final Object modelSource,
        final Object model,
        final ParameterId parameterId,
        final CurrentGuard currentGuard,
        final BindingReader bindingReader,
        final TargetLookup targetLookup,
        final ParameterSourceLookup parameterSourceLookup
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.identity = Objects.requireNonNull(identity, "identity");
        this.modelSource = Objects.requireNonNull(modelSource, "modelSource");
        this.model = Objects.requireNonNull(model, "model");
        this.parameterId = Objects.requireNonNull(parameterId, "parameterId");
        this.currentGuard = Objects.requireNonNull(currentGuard, "currentGuard");
        this.bindingReader = Objects.requireNonNull(bindingReader, "bindingReader");
        this.targetLookup = Objects.requireNonNull(targetLookup, "targetLookup");
        this.parameterSourceLookup = Objects.requireNonNull(parameterSourceLookup, "parameterSourceLookup");
    }

    @Override
    public void bind(final ParameterBindingTarget target, final List<ParameterBindingPoint> points) {
        Objects.requireNonNull(target, "target");
        final List<Float> desired = values(points, true);
        if (binding(target) != null) {
            throw new IllegalStateException("The target is already bound to this parameter.");
        }
        mutate(target, "Bind Parameter", grid -> {
            final Object guid = parameterGuid();
            for (float value : desired) {
                resolver.invoke("cubism.editor-model.keyform-grid.add-key", grid, Float.valueOf(value), guid);
            }
        });
    }

    @Override
    public void createPoint(final ParameterBindingTarget target, final ParameterBindingPoint point) {
        Objects.requireNonNull(point, "point");
        final ParameterBinding current = requireBinding(target);
        final ArrayList<Float> desired = new ArrayList<>(current.points().stream()
            .map(ParameterBindingPoint::value).toList());
        requireAbsent(desired, point.value());
        desired.sort(Float::compare);
        mutate(target, "Create Parameter Binding Point", grid -> resolver.invoke(
            "cubism.editor-model.keyform-grid.add-key",
            grid,
            Float.valueOf(point.value()),
            parameterGuid()
        ));
    }

    @Override
    public void movePoint(
        final ParameterBindingTarget target,
        final ParameterBindingPointId pointId,
        final float value
    ) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        final ParameterBinding current = requireBinding(target);
        final int index = pointIndex(current, Objects.requireNonNull(pointId, "pointId"));
        final ArrayList<Float> before = values(current.points(), false);
        if (near(before.get(index), value)) return;
        for (int other = 0; other < before.size(); other++) {
            if (other != index && near(before.get(other), value)) {
                throw new IllegalStateException("A binding point already exists at the requested value.");
            }
        }
        final ArrayList<Float> after = new ArrayList<>(before);
        after.set(index, value);
        after.sort(Float::compare);
        mutate(target, "Move Parameter Binding Point", grid -> resolver.invoke(
            "cubism.editor-model.keyform-grid.rearrange-keys",
            grid,
            parameterGuid(),
            List.copyOf(before),
            List.copyOf(after)
        ));
    }

    @Override
    public void deletePoint(
        final ParameterBindingTarget target,
        final ParameterBindingPointId pointId
    ) {
        final ParameterBinding current = requireBinding(target);
        if (current.points().size() == 1) {
            throw new IllegalStateException("Deleting the last binding point is not allowed; use unbind.");
        }
        final int index = pointIndex(current, Objects.requireNonNull(pointId, "pointId"));
        final float value = current.points().get(index).value();
        mutate(target, "Delete Parameter Binding Point", grid -> resolver.invoke(
            "cubism.editor-model.keyform-grid.remove-key",
            grid,
            Float.valueOf(value),
            parameterGuid()
        ));
    }

    @Override
    public void unbind(final ParameterBindingTarget target) {
        if (binding(target) == null) return;
        mutate(target, "Unbind Parameter", grid -> resolver.invoke(
            "cubism.editor-model.keyform-grid.remove-all-key",
            grid,
            resolver.invoke("cubism.editor-model.model.parameter-set", model),
            parameterGuid()
        ));
    }

    private void mutate(
        final ParameterBindingTarget target,
        final String action,
        final java.util.function.Consumer<Object> mutation
    ) {
        requireAuthorized(target.type());
        currentGuard.requireCurrent(identity, model);
        final Object objectSource = targetLookup.find(identity, modelSource, model, target);
        final Object grid = resolver.invoke(
            "cubism.editor-model.parameter-controllable.keyform-grid",
            objectSource
        );
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = resolver.invoke(
            "cubism.editor-model.app-controller.current-document",
            app
        );
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode",
            document
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin",
            editMode,
            "Turboism: " + action
        );
        boolean completed = false;
        try {
            final Object handler = resolver.invoke(
                "cubism.editor-model.parameter-controllable-source.handler",
                objectSource
            );
            final Object undo = resolver.invoke(
                "cubism.editor-model.parameter-controllable-handler.create-undo-for-all-edit",
                handler,
                "Turboism: " + action
            );
            final Object accepted = resolver.invoke(
                "cubism.editor-model.undo.add",
                edit,
                undo,
                Boolean.TRUE
            );
            if (!(accepted instanceof Boolean value) || !value) {
                throw new IllegalStateException("Cubism rejected the parameter-binding Undo entry.");
            }
            final Object listener = resolver.createFunctionalProxy(
                "cubism.editor-model.undo-listener.class",
                ignored -> {
                    refresh(app, target.type());
                    return null;
                }
            );
            resolver.invoke("cubism.editor-model.undo.add-listener", undo, listener);
            mutation.accept(grid);
            refresh(app, target.type());
            resolver.invoke("cubism.editor-model.modeling-document.mark-dirty", document);
            completed = true;
        } finally {
            resolver.invoke(
                "cubism.editor-model.edit-mode.end",
                editMode,
                Boolean.valueOf(!completed),
                null
            );
        }
        currentGuard.requireCurrent(identity, model);
    }

    private void refresh(final Object app, final ParameterBindingTargetType type) {
        resolver.invoke("cubism.editor-model.model-source.update-instances", modelSource);
        final Object pack = resolver.invoke("cubism.editor-model.app-controller.complete-pack", app);
        resolver.invoke("cubism.editor-model.complete-pack.update-parameter", pack, Boolean.TRUE);
        if (type == ParameterBindingTargetType.ART_MESH) {
            resolver.invoke("cubism.editor-model.complete-pack.update-part-palette", pack, Boolean.TRUE);
        } else {
            resolver.invoke("cubism.editor-model.complete-pack.update-deformer-palette", pack, Boolean.TRUE);
        }
        resolver.invoke("cubism.editor-model.complete-pack.repaint-canvas", pack, Boolean.TRUE);
    }

    private void requireAuthorized(final ParameterBindingTargetType type) {
        final String capability = switch (type) {
            case ART_MESH -> EditorParameterBindingWriteSelectorContract.ART_MESH_CAPABILITY_ID;
            case WARP_DEFORMER -> EditorParameterBindingWriteSelectorContract.WARP_CAPABILITY_ID;
            case ROTATION_DEFORMER -> EditorParameterBindingWriteSelectorContract.ROTATION_CAPABILITY_ID;
        };
        if (!resolver.authorizesFeature(
            EditorParameterBindingWriteSelectorContract.ADAPTER_SLICE_ID,
            capability,
            EditorParameterBindingWriteSelectorContract.REQUIRED_ALIASES
        )) {
            throw new UnsupportedOperationException(
                "Editor parameter-binding writes require exact verified host evidence for " + type + "."
            );
        }
    }

    private ParameterBinding requireBinding(final ParameterBindingTarget target) {
        final ParameterBinding binding = binding(target);
        if (binding == null) throw new IllegalStateException("The target is not bound to this parameter.");
        return binding;
    }

    private ParameterBinding binding(final ParameterBindingTarget target) {
        Objects.requireNonNull(target, "target");
        currentGuard.requireCurrent(identity, model);
        return bindingReader.read(identity, modelSource, model, parameterId).stream()
            .filter(value -> value.target().equals(target))
            .findFirst()
            .orElse(null);
    }

    private Object parameterGuid() {
        return resolver.invoke(
            "cubism.editor-model.parameter-source.guid",
            parameterSourceLookup.find(model, parameterId)
        );
    }

    private static ArrayList<Float> values(
        final List<ParameterBindingPoint> points,
        final boolean requireNonEmpty
    ) {
        final List<ParameterBindingPoint> copy = List.copyOf(Objects.requireNonNull(points, "points"));
        if (requireNonEmpty && copy.isEmpty()) throw new IllegalArgumentException("points must not be empty");
        final ArrayList<Float> values = new ArrayList<>(copy.size());
        for (ParameterBindingPoint point : copy) requireAbsent(values, Objects.requireNonNull(point, "point").value());
        values.sort(Comparator.naturalOrder());
        return values;
    }

    private static int pointIndex(
        final ParameterBinding binding,
        final ParameterBindingPointId pointId
    ) {
        for (int index = 0; index < binding.points().size(); index++) {
            if (binding.points().get(index).id().equals(pointId)) return index;
        }
        throw new IllegalStateException("The binding point is stale or absent.");
    }

    private static void requireAbsent(final List<Float> values, final float value) {
        if (!Float.isFinite(value)) throw new IllegalArgumentException("value must be finite");
        if (values.stream().anyMatch(existing -> near(existing, value))) {
            throw new IllegalStateException("A binding point already exists at the requested value.");
        }
        values.add(value);
    }

    private static boolean near(final float left, final float right) {
        return Math.abs(left - right) <= EPSILON;
    }
}
