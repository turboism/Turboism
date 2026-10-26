package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import javax.swing.SwingUtilities;

/** Read-only decoder. Caller must validate the object and read on the same Editor thread. */
final class EditorArtMeshAuthoringReadAccess {
    private static final String MODEL = "cubism.editor-model.";
    private static final String SEMANTIC = "cubism.editor-history.semantic.";
    private final VerifiedMemberResolver resolver;

    EditorArtMeshAuthoringReadAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    ArtMeshAuthoringSnapshot read(final ArtMeshId id, final Object source) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("stored authoring reads require the Editor thread");
        }
        requireAuthorized();
        final String sourceGuid = guid(model("art-mesh-source.guid", source));
        final var sourcePositions = points(model("art-mesh-source.positions", source));
        final var sourceUvs = points(model("art-mesh-source.uvs", source));
        final var sourceIndices = indices(model("art-mesh-source.indices", source));
        final ArtMeshGeometry geometry;
        try {
            geometry = new ArtMeshGeometry(sourcePositions, sourceUvs, sourceIndices);
        } catch (IllegalArgumentException invalid) {
            throw new IllegalArgumentException(
                    "Invalid source geometry for " + id.value()
                            + ": vertices=" + sourcePositions.size() + ", uvs=" + sourceUvs.size()
                            + ", indexCount=" + sourceIndices.size()
                            + ", indexMin="
                            + sourceIndices.stream()
                                    .mapToInt(Integer::intValue)
                                    .min()
                                    .orElse(-1)
                            + ", indexMax="
                            + sourceIndices.stream()
                                    .mapToInt(Integer::intValue)
                                    .max()
                                    .orElse(-1)
                            + "; " + invalid.getMessage(),
                    invalid);
        }
        final Object grid = model("parameter-controllable.keyform-grid", source);
        final var axes = new ArrayList<Axis>();
        final var axisByBinding = new IdentityHashMap<Object, Axis>();
        for (final Object binding : list(model("keyform-grid.bindings", grid))) {
            final Object parameter = semantic("keyform-binding.parameter", binding);
            final var keys = new ArrayList<Float>();
            for (final Object key : list(model("keyform-binding.keys", binding))) keys.add(number(key));
            final Axis axis = new Axis(
                    guid(model("keyform-binding.parameter-guid", binding)),
                    new ParameterId(text(model("id.value", model("keyform-binding.parameter-id", binding)))),
                    number(model("parameter-source.minimum", parameter)),
                    number(model("parameter-source.default", parameter)),
                    number(model("parameter-source.maximum", parameter)),
                    keys);
            axes.add(axis);
            if (axisByBinding.put(binding, axis) != null) {
                throw new IllegalStateException("duplicate native grid binding");
            }
        }
        // Opacity is an additive observation: older verified resolvers may still expose
        // all geometry selectors without this property. Never substitute evaluated opacity.
        final boolean opacityReadable = resolver.authorizesFeature(
                EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                "cubism.editor-model.read",
                java.util.Set.of(MODEL + "drawable-form.opacity"));
        final var forms = new ArrayList<Form>();
        final var cells = new ArrayList<Cell>();
        for (final Object form : list(model("art-mesh-source.keyforms", source))) {
            final Object nativeGuid = semantic("form.guid", form);
            final String formGuid = formGuid(nativeGuid);
            final Object order = model("drawable-form.draw-order", form);
            if (!(order instanceof Integer drawOrder)) {
                throw new IllegalStateException("stored draw order is not an int");
            }
            forms.add(new Form(
                    formGuid,
                    points(model("art-mesh-form.positions", form)),
                    drawOrder,
                    opacityReadable ? Optional.of(number(model("drawable-form.opacity", form))) : Optional.empty()));
            // Every reviewed generation exposes form -> rows; the grid -> all rows
            // selector is 5.3.03-only and deliberately not used here.
            for (final Object row : list(semantic("keyform-grid.forms-for-guid", grid, nativeGuid))) {
                final Object accessKey = semantic("keyform-on-grid.access-key", row);
                final var coordinates = new ArrayList<Coordinate>();
                for (final Object coordinate : list(semantic("keyform-access-key.coordinates", accessKey))) {
                    final Object binding = semantic("key-on-parameter.binding", coordinate);
                    final Axis axis = axisByBinding.get(binding);
                    if (axis == null) throw new IllegalStateException("cell refers to a foreign grid binding");
                    coordinates.add(new Coordinate(
                            axis.parameterGuid(), number(semantic("key-on-parameter.value", coordinate))));
                }
                cells.add(new Cell(formGuid, coordinates));
            }
        }
        final var morphKeys = new ArrayList<MorphKey>();
        final Object set = model("parameter-controllable.morph-target-set", source);
        for (final Object target : list(model("morph-target-set.morph-targets", set))) {
            final Object key = model("morph-target.key-value", target);
            morphKeys.add(new MorphKey(
                    guid(model("morph-target.parameter-guid", target)),
                    formGuid(model("morph-target.keyform-guid", target)),
                    key == null ? Optional.empty() : Optional.of(number(key))));
        }
        return new ArtMeshAuthoringSnapshot(id, sourceGuid, geometry, axes, cells, forms, morphKeys);
    }

    private void requireAuthorized() {
        if (!EditorReviewedGeneration.admits(resolver)) throw unavailable();
        for (final String capability : EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS) {
            if (!resolver.authorizesFeature(
                    EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                    capability,
                    EditorArtMeshAuthoringReadSelectorContract.REQUIRED_ALIASES)) throw unavailable();
        }
    }

    private static UnsupportedOperationException unavailable() {
        return new UnsupportedOperationException("verified stored ArtMesh authoring reads are unavailable");
    }

    private Object model(final String alias, final Object target, final Object... arguments) {
        return resolver.invoke(MODEL + alias, Objects.requireNonNull(target, alias), arguments);
    }

    private Object semantic(final String alias, final Object target, final Object... arguments) {
        return resolver.invoke(SEMANTIC + alias, Objects.requireNonNull(target, alias), arguments);
    }

    private String guid(final Object value) {
        return text(model("guid.value", value));
    }

    private String formGuid(final Object value) {
        return text(model("form-guid.value", value));
    }

    private static String text(final Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalStateException("missing stored authoring identity");
    }

    private static List<?> list(final Object value) {
        if (value instanceof List<?> list) return List.copyOf(list);
        throw new IllegalStateException("stored authoring collection is not a list");
    }

    private static float number(final Object value) {
        if (value instanceof Float number && Float.isFinite(number)) return number;
        throw new IllegalStateException("stored authoring value is not a finite float");
    }

    private static List<Point2> points(final Object value) {
        if (!(value instanceof float[] array) || array.length % 2 != 0) {
            throw new IllegalStateException("stored positions are not coordinate pairs");
        }
        final var points = new ArrayList<Point2>(array.length / 2);
        for (int i = 0; i < array.length; i += 2) points.add(new Point2(array[i], array[i + 1]));
        return List.copyOf(points);
    }

    private static List<Integer> indices(final Object value) {
        if (!(value instanceof int[] array)) throw new IllegalStateException("stored indices are not ints");
        return Arrays.stream(array).boxed().toList();
    }
}
