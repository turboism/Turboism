package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterValue;
import dev.turboism.sdk.cubism.model.Point2;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Caller validates source/instance/model identity inside the same EDT dispatch as this read. */
final class EditorArtMeshEvaluationReadAccess {
    private final VerifiedMemberResolver resolver;

    EditorArtMeshEvaluationReadAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    ArtMeshEvaluationSnapshot read(final ArtMeshId id, final Object source,
                                   final Object instance, final Object model) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("calculated ArtMesh reads require the Editor thread");
        }
        requireSamplingConfiguration();
        if (!resolver.isExactCubismVersion("5.2.03", "5.3.02", "5.3.03") || !resolver.authorizesFeature(
            EditorArtMeshEvaluationReadSelectorContract.ADAPTER_SLICE_ID,
            EditorArtMeshEvaluationReadSelectorContract.CAPABILITY_ID,
            EditorArtMeshEvaluationReadSelectorContract.REQUIRED_ALIASES
        )) throw new UnsupportedOperationException("verified 5.3.02 calculated ArtMesh reads are unavailable");

        final String sourceGuid = text(invoke("cubism.editor-model.guid.value",
            invoke("cubism.editor-model.art-mesh-source.guid", source)));
        final int vertexCount = points(invoke("cubism.editor-model.art-mesh-source.positions", source)).size();
        final ParameterState live = parameters(invoke("cubism.editor-model.model.parameter-set", model));
        final ParameterState consumed = parameters(invoke("cubism.editor-model.model.last-updated-parameter-set", model));
        final Object stamp = invoke("cubism.editor-model.model.last-updated-time", model);
        if (!(stamp instanceof Long time)) throw new IllegalStateException("native pass time is not a long");
        final Object calculated = invoke("cubism.editor-model.art-mesh.calculated-form", instance);
        final Object interpolated = invoke("cubism.editor-model.art-mesh.interpolated-form", instance);
        Optional<Form> form = Optional.empty();
        if (calculated != null) {
            final List<Point2> positions = points(invoke("cubism.editor-model.art-mesh-form.positions", calculated));
            if (positions.size() != vertexCount) throw new IllegalStateException("calculated/source vertex count differs");
            form = Optional.of(new Form(positions,
                integer(invoke("cubism.editor-model.drawable-form.draw-order", calculated)),
                text(invoke("cubism.editor-model.coord-type.label",
                    invoke("cubism.editor-model.drawable-form.coord-type", calculated))),
                calculated == interpolated));
        }
        // No evaluation, freshness classification, current-form fallback or native mutation here.
        return new ArtMeshEvaluationSnapshot(id, sourceGuid, form, live, consumed, time);
    }

    private ParameterState parameters(final Object set) {
        final int version = integer(invoke("cubism.editor-model.parameter-set.update-version", set));
        final Object items = invoke("cubism.editor-model.parameter-set.parameters", set);
        if (!(items instanceof List<?> list)) throw new IllegalStateException("native parameters are not a list");
        final var values = new ArrayList<ParameterValue>();
        // The native copied set's GUID map can contain removed parameters. Enumerate its actual list.
        for (final Object parameter : List.copyOf(list)) {
            final Object source = invoke("cubism.editor-model.parameter.source", parameter);
            final String guid = text(invoke("cubism.editor-model.guid.value",
                invoke("cubism.editor-model.parameter-source.guid", source)));
            final ParameterId id = new ParameterId(text(invoke("cubism.editor-model.id.value",
                invoke("cubism.editor-model.parameter.id", parameter))));
            final Object raw = invoke("cubism.editor-model.parameter.value", parameter);
            if (!(raw instanceof Float value) || !Float.isFinite(value)) {
                throw new IllegalStateException("native parameter value is not a finite float");
            }
            values.add(new ParameterValue(guid, id, value));
        }
        return new ParameterState(version, values);
    }

    static void requireSamplingConfiguration() {
        // Official 5.3.02 JOGL OffscreenBackend paints/evaluates synchronously on EDT.
        // Do not enable an alternate backend, or guess its model-access thread semantics.
        for (final String property : List.of("sun.java2d.opengl", "jogl.gljpanel.noogl")) {
            final String value = System.getProperty(property);
            if (value != null && !value.equals("false")) {
                throw new UnsupportedOperationException("unsupported calculated-read rendering configuration: " + property);
            }
        }
    }

    private Object invoke(final String alias, final Object target) {
        return resolver.invoke(alias, Objects.requireNonNull(target, alias));
    }

    private static String text(final Object value) {
        if (value instanceof String text && !text.isBlank()) return text;
        throw new IllegalStateException("missing native calculated-form identity or coordinate label");
    }

    private static int integer(final Object value) {
        if (value instanceof Integer number) return number;
        throw new IllegalStateException("native calculated-form value is not an int");
    }

    private static List<Point2> points(final Object value) {
        if (!(value instanceof float[] array) || array.length % 2 != 0) {
            throw new IllegalStateException("native calculated positions are not coordinate pairs");
        }
        final var points = new ArrayList<Point2>(array.length / 2);
        for (int i = 0; i < array.length; i += 2) points.add(new Point2(array[i], array[i + 1]));
        return List.copyOf(points);
    }
}
