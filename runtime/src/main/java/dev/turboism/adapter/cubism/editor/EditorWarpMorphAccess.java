package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Atomic native creation plus absolute geometry or opacity write, with captured member-aware Undo. */
final class EditorWarpMorphAccess {
    static void create(
            VerifiedMemberResolver resolver,
            DeformerId id,
            Supplier<Object> currentSource,
            Object modelSource,
            Object model,
            ParameterId parameterId,
            float key,
            Float opacity,
            List<Point2> controls,
            Runnable requireTransaction,
            Consumer<EditorUndoContribution> submit,
            Runnable refresh) {
        Objects.requireNonNull(parameterId, "parameterId");
        final var input = controls == null ? null : List.copyOf(controls);
        if (opacity != null && (!Float.isFinite(opacity) || opacity < 0f || opacity > 1f))
            throw new IllegalArgumentException("invalid Warp opacity morph");
        if (!Float.isFinite(key) || key == 0f) throw new IllegalArgumentException("nonzero morph key required");
        EditorHostThread.dispatch("create Warp morph", () -> {
            requireTransaction.run();
            Object source = currentSource.get();
            Runnable current = () -> {
                EditorWarpMorphState.authorize(resolver);
                if (currentSource.get() != source) throw new IllegalStateException("stale Warp morph");
            };
            Supplier<EditorWarpMorphState> read = () -> {
                current.run();
                return new EditorWarpMorphState(resolver, source, modelSource, model);
            };
            var before = read.get();
            before.validate();
            String parameterGuid = before.parameters.entrySet().stream()
                    .filter(e -> e.getValue().id().equals(parameterId.value()))
                    .map(java.util.Map.Entry::getKey)
                    .findFirst()
                    .orElseThrow(() -> new java.util.NoSuchElementException("morph parameter does not exist"));
            var parameter = before.parameter(parameterGuid);
            EditorWarpMorphState.validateKey(key, parameter);
            final var base = before.stored.get(before.baseGuid).form();
            final var targetPoints = input == null ? base.controlPoints() : input;
            final float targetOpacity = opacity == null ? base.opacity() : opacity;
            if (targetPoints.size() != base.controlPoints().size())
                throw new IllegalArgumentException("Warp control count differs");
            if (before.keys.stream()
                            .anyMatch(k -> k.parameterGuid().equals(parameterGuid)
                                    && Math.abs(k.value() - key) <= parameter.epsilon())
                    || !Boolean.TRUE.equals(resolver.invoke(
                            "cubism.editor-model.morph-target-set.can-add-key",
                            before.targetSet,
                            parameter.source(),
                            key))) throw new IllegalArgumentException("Warp morph parameter already has a key");
            var undo = new EditorArtMeshMorphUndo(resolver, before.forms, before.targetSet, current, refresh);
            Object[] created = new Object[1];
            Runnable unchanged = () -> {
                if (!before.same(read.get())) throw new IllegalStateException("Warp morph state changed");
            };
            submit.accept(new EditorUndoContribution(
                    "cubism.warp.create-morph-keyform",
                    id.value(),
                    "Create Warp morph key",
                    (edit, label) -> {
                        unchanged.run();
                        undo.admit(edit, label);
                    },
                    () -> {
                        unchanged.run();
                        Object guid = resolver.invoke("cubism.editor-model.parameter-source.guid", parameter.source());
                        Object utils = resolver.readStaticField("cubism.editor-model.morph-authoring-utils.instance");
                        created[0] = resolver.invoke(
                                "cubism.editor-model.morph-authoring-utils.create-key",
                                utils,
                                model,
                                source,
                                key,
                                guid,
                                null);
                        if (created[0] == null)
                            throw new IllegalStateException("native Warp morph creation was cancelled");
                        var seeded = read.get();
                        // Native creation may interpolate another key. Write the requested absolute payload.
                        if (!before.protectedSame(seeded) || seeded.keys.size() != before.keys.size() + 1)
                            throw new IllegalStateException("native Warp morph membership differs");
                        Object form = seeded.formRefs.get(seeded.formRefs.size() - 1);
                        if (input != null) {
                            float[] values = new float[input.size() * 2];
                            for (int i = 0; i < input.size(); i++) {
                                values[i * 2] = input.get(i).x();
                                values[i * 2 + 1] = input.get(i).y();
                            }
                            resolver.invoke("cubism.editor-model.warp-form.set-positions", form, values);
                        }
                        resolver.invoke("cubism.editor-model.deformer-form.set-opacity", form, targetOpacity);
                        refresh.run();
                    },
                    () -> before.created(read.get(), parameterGuid, key, targetPoints, targetOpacity, created[0]),
                    () -> {
                        if (!before.protectedSame(read.get()))
                            throw new IllegalStateException("Warp protection domain changed");
                        undo.restore();
                    },
                    () -> before.sameValues(read.get()),
                    Set.of(EditorRefreshRequirement.MARK_DIRTY)));
            return null;
        });
    }

    private EditorWarpMorphAccess() {}
}
