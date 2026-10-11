package dev.turboism.adapter.cubism.editor;

import dev.turboism.adapter.cubism.editor.transaction.EditorRefreshRequirement;
import dev.turboism.adapter.cubism.editor.transaction.EditorUndoContribution;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Member-aware morph authoring. All mutation and recovery run inside the existing coordinator. */
final class EditorArtMeshMorphAccess implements ArtMeshMorphKeyform {
    private final Context context;
    private final String parameterGuid;
    private final float keyValue;
    private final String formGuid;
    private final String baseGuid;
    private final EditorArtMeshMorphState layout;

    static ArtMeshMorphKeyform open(
            final VerifiedMemberResolver resolver,
            final ArtMeshId id,
            final Supplier<Object> currentSource,
            final Object modelSource,
            final Object model,
            final String parameterGuid,
            final float keyValue,
            final boolean create,
            final Runnable requireTransaction,
            final Consumer<EditorUndoContribution> submit,
            final Runnable refresh) {
        if (Objects.requireNonNull(parameterGuid, "parameterGuid").isBlank()) {
            throw new IllegalArgumentException("parameter GUID must not be blank");
        }
        EditorArtMeshMorphState.validateKey(keyValue, 0f);
        return EditorHostThread.dispatch("ArtMesh morph keyform", () -> {
            EditorArtMeshMorphState.requireAuthorized(resolver);
            final var context = new Context(
                    resolver,
                    id,
                    currentSource,
                    currentSource.get(),
                    modelSource,
                    model,
                    requireTransaction,
                    submit,
                    refresh);
            if (create) create(context, parameterGuid, keyValue);
            return new EditorArtMeshMorphAccess(context, parameterGuid, keyValue);
        });
    }

    private EditorArtMeshMorphAccess(final Context context, final String parameterGuid, final float keyValue) {
        this.context = context;
        this.parameterGuid = parameterGuid;
        this.keyValue = keyValue;
        this.layout = context.read();
        this.baseGuid = layout.validateDomain(parameterGuid);
        EditorArtMeshMorphState.validateKey(
                keyValue, layout.parameter(parameterGuid).epsilon());
        this.formGuid = layout.target(parameterGuid, keyValue).formGuid();
    }

    private static void create(final Context c, final String parameterGuid, final float keyValue) {
        c.requireTransaction.run();
        final var before = c.read();
        final String base = before.validateDomain(parameterGuid);
        EditorArtMeshMorphState.validateKey(
                keyValue, before.parameter(parameterGuid).epsilon());
        final Object parameter = before.parameterObjects.get(parameterGuid);
        if (!Boolean.TRUE.equals(c.resolver.invoke(
                "cubism.editor-model.morph-target-set.can-add-key", before.targetSet, parameter, keyValue))) {
            throw new IllegalArgumentException("morph key already exists or conflicts within snap epsilon");
        }
        final EditorArtMeshMorphState.Seed seed = before.seed(parameterGuid, keyValue, base);
        final Object[] created = new Object[1];
        c.write(
                before,
                "cubism.art-mesh.create-morph-keyform",
                "Create ArtMesh morph key",
                () -> {
                    final Object nativeGuid = c.resolver.invoke("cubism.editor-model.parameter-source.guid", parameter);
                    // This singleton is MorphTargetUtils, NOT the older parameter-rebinding utility.
                    final Object utils =
                            c.resolver.readStaticField("cubism.editor-model.morph-authoring-utils.instance");
                    created[0] = c.resolver.invoke(
                            "cubism.editor-model.morph-authoring-utils.create-key",
                            utils,
                            c.model,
                            c.source,
                            keyValue,
                            nativeGuid,
                            null);
                    if (created[0] == null) throw new IllegalStateException("native morph key creation was cancelled");
                },
                () -> createdCorrectly(before, c.read(), parameterGuid, keyValue, seed, created[0]));
    }

    private static boolean createdCorrectly(
            final EditorArtMeshMorphState before,
            final EditorArtMeshMorphState after,
            final String parameterGuid,
            final float key,
            final EditorArtMeshMorphState.Seed seed,
            final Object returned) {
        after.validateDomain(parameterGuid);
        if (!before.protectedUnchanged(after)
                || after.snapshot.forms().size() != before.snapshot.forms().size() + 1
                || after.snapshot.morphKeys().size()
                        != before.snapshot.morphKeys().size() + 1) return false;
        final var added = after.target(parameterGuid, key);
        final int index = after.snapshot.morphKeys().indexOf(added);
        if (returned == null
                || after.targetObjects.get(index) != returned
                || before.formObjects.containsKey(added.formGuid())
                || !seed.matches(after.form(added.formGuid()), after.attributes.get(added.formGuid()))) return false;
        if (!after.snapshot
                .morphKeys()
                .subList(0, before.snapshot.morphKeys().size())
                .equals(before.snapshot.morphKeys())) return false;
        if (!after.snapshot.forms().subList(0, before.snapshot.forms().size()).equals(before.snapshot.forms()))
            return false;
        for (final var entry : before.formObjects.entrySet()) {
            final String guid = entry.getKey();
            if (after.formObjects.get(guid) != entry.getValue()
                    || !before.attributes.get(guid).equals(after.attributes.get(guid))) return false;
        }
        for (int i = 0; i < before.targetObjects.size(); i++) {
            if (before.targetObjects.get(i) != after.targetObjects.get(i)) return false;
        }
        return true;
    }

    @Override
    public String parameterGuid() {
        return EditorHostThread.dispatch("morph parameter GUID", () -> {
            checked();
            return parameterGuid;
        });
    }

    @Override
    public float keyValue() {
        return EditorHostThread.dispatch("morph key value", () -> {
            checked();
            return keyValue;
        });
    }

    @Override
    public Form snapshot() {
        return EditorHostThread.dispatch("morph stored form", () -> checked().form(formGuid));
    }

    @Override
    public Form baseSnapshot() {
        return EditorHostThread.dispatch("morph ordinary base", () -> checked().form(baseGuid));
    }

    @Override
    public void setPositions(final List<Point2> positions) {
        final List<Point2> input = List.copyOf(positions);
        EditorHostThread.dispatch("write absolute morph positions", () -> {
            context.requireTransaction.run();
            final var before = checked();
            final Form form = before.form(formGuid);
            if (input.size() != form.positions().size())
                throw new IllegalArgumentException("morph vertex count differs");
            if (input.equals(form.positions())) return null;
            context.write(
                    before,
                    "cubism.art-mesh.set-morph-keyform-positions",
                    "Set ArtMesh morph positions",
                    () -> {
                        checked();
                        final float[] raw = new float[input.size() * 2];
                        for (int i = 0; i < input.size(); i++) {
                            raw[2 * i] = input.get(i).x();
                            raw[2 * i + 1] = input.get(i).y();
                        }
                        context.resolver.invoke(
                                "cubism.editor-model.art-mesh-form.set-positions",
                                before.formObjects.get(formGuid),
                                raw);
                    },
                    () -> before.onlyPositionsChanged(context.read(), formGuid, input));
            return null;
        });
    }

    @Override
    public void setDrawOrder(final int drawOrder) {
        if (drawOrder < 0 || drawOrder > 1000) {
            throw new IllegalArgumentException("morph keyform draw order must be between 0 and 1000");
        }
        EditorHostThread.dispatch("write absolute morph draw order", () -> {
            EditorArtMeshMorphState.requireOrderAuthorized(context.resolver);
            context.requireTransaction.run();
            final var before = checked();
            if (before.form(formGuid).drawOrder() == drawOrder) return null;
            context.write(
                    before,
                    "cubism.art-mesh.set-morph-keyform-draw-order",
                    "Set ArtMesh morph draw order",
                    () -> {
                        checked();
                        context.resolver.invoke(
                                "cubism.editor-model.drawable-form.set-draw-order",
                                before.formObjects.get(formGuid),
                                drawOrder);
                    },
                    () -> before.onlyDrawOrderChanged(context.read(), formGuid, drawOrder));
            return null;
        });
    }

    private EditorArtMeshMorphState checked() {
        final var current = context.read();
        if (!layout.sameLayout(current)
                || !baseGuid.equals(current.validateDomain(parameterGuid))
                || !formGuid.equals(current.target(parameterGuid, keyValue).formGuid())) throw stale();
        return current;
    }

    private record Context(
            VerifiedMemberResolver resolver,
            ArtMeshId id,
            Supplier<Object> currentSource,
            Object source,
            Object modelSource,
            Object model,
            Runnable requireTransaction,
            Consumer<EditorUndoContribution> submit,
            Runnable refresh) {
        void requireCurrent() {
            EditorArtMeshMorphState.requireAuthorized(resolver);
            if (currentSource.get() != source) throw stale();
        }

        EditorArtMeshMorphState read() {
            requireCurrent();
            return EditorArtMeshMorphState.read(resolver, id, source, modelSource, model);
        }

        void write(
                final EditorArtMeshMorphState before,
                final String operation,
                final String label,
                final Runnable mutation,
                final BooleanSupplier applied) {
            final var undo = new EditorArtMeshMorphUndo(resolver, before, this::requireCurrent, refresh);
            submit.accept(new EditorUndoContribution(
                    operation,
                    id.value(),
                    label,
                    (edit, transactionLabel) -> {
                        final var current = read();
                        if (!before.sameLayout(current) || !before.sameValues(current)) throw stale();
                        undo.admit(edit, transactionLabel);
                    },
                    () -> {
                        // Admission is pure; detect unexpected reentrancy before the eager primitive.
                        final var current = read();
                        if (!before.sameLayout(current) || !before.sameValues(current)) throw stale();
                        mutation.run();
                        refresh.run();
                    },
                    applied,
                    () -> {
                        if (!before.protectedUnchanged(read()))
                            throw new IllegalStateException("morph protection domain changed");
                        undo.restore();
                    },
                    () -> before.sameValues(read()),
                    Set.of(EditorRefreshRequirement.MARK_DIRTY)));
        }
    }

    private static IllegalStateException stale() {
        return new IllegalStateException("ArtMesh morph keyform is stale");
    }
}
