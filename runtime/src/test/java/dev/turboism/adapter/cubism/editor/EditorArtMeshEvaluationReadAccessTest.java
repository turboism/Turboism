package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshEvaluationReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot;
import dev.turboism.sdk.cubism.model.Point2;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;

import javax.swing.SwingUtilities;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.method;
import static org.junit.jupiter.api.Assertions.*;

@ResourceLock(Resources.SYSTEM_PROPERTIES)
class EditorArtMeshEvaluationReadAccessTest {
    private static final String M = "cubism.editor-model.";
    private static final ArtMeshId ID = new ArtMeshId("mesh");

    @Test void readsCalculatedRatherThanStoredWithIndependentConsumptionMetadata() {
        final Model model = new Model();
        final var result = read(model, resolver("5.3.02", false, null));
        assertEquals(ID, result.artMeshId());
        assertEquals("source", result.sourceGuid());
        assertEquals(List.of(new Point2(11, 12), new Point2(21, 22)), result.calculatedForm().orElseThrow().positions());
        assertEquals("DeformerLocal", result.calculatedForm().orElseThrow().coordinateType());
        assertEquals(321, result.calculatedForm().orElseThrow().drawOrder());
        assertTrue(result.calculatedForm().orElseThrow().interpolated());
        assertEquals(7, result.liveParameters().updateVersion());
        assertEquals(53, result.consumedParameters().updateVersion());
        assertEquals(.5F, result.liveParameters().parameters().get(0).value());
        assertEquals(0F, result.consumedParameters().parameters().get(0).value());
        assertEquals(1234L, result.passEntryTimeMillis());
        assertEquals(result, read(model, resolver("5.3.02", false, null)));
        // Exposes getters only: no current-keyform, evaluator, repaint, setter, history or dirty alias.
        model.form.positions[0] = 999;
        model.live.parameters.get(0).value = 9;
        model.consumed.parameters.clear();
        assertEquals(11, result.calculatedForm().orElseThrow().positions().get(0).x());
        assertEquals(.5F, result.liveParameters().parameters().get(0).value());
        assertEquals(1, result.consumedParameters().parameters().size());
        assertThrows(UnsupportedOperationException.class, () -> result.calculatedForm().orElseThrow().positions().clear());
        assertThrows(UnsupportedOperationException.class, () -> result.liveParameters().parameters().clear());
    }

    @Test void preservesAbsentAndOlderObservationsWithoutInventingReadiness() {
        final Model model = new Model();
        model.calculated = null;
        model.time = -1;
        model.consumed.parameters.clear();
        model.live.version = Integer.MIN_VALUE; // signed native overflow, not a global generation
        final var result = read(model, resolver("5.3.02", false, null));
        assertTrue(result.calculatedForm().isEmpty());
        assertEquals(-1L, result.passEntryTimeMillis());
        assertTrue(result.consumedParameters().parameters().isEmpty());
        assertEquals(Integer.MIN_VALUE, result.liveParameters().updateVersion());
        model.calculated = new Form(); // same content does not mean identical native form
        assertFalse(read(model, resolver("5.3.02", false, null)).calculatedForm().orElseThrow().interpolated());
    }

    @Test void keepsSameIdDifferentGuidsAndOlderMembership() {
        final Model model = new Model();
        model.live.parameters.add(new Parameter("y", .75F));
        model.consumed.parameters.add(new Parameter("removed", -1F));
        final var result = read(model, resolver("5.3.02", false, null));
        assertEquals(List.of("x", "y"), result.liveParameters().parameters().stream().map(p -> p.parameterGuid()).toList());
        assertEquals(result.liveParameters().parameters().get(0).parameterId(), result.liveParameters().parameters().get(1).parameterId());
        assertEquals("removed", result.consumedParameters().parameters().get(1).parameterGuid());
    }

    @Test void rejectsMissingAdmissionBeforeTouchingNativeObjects() {
        for (final String version : List.of("5.4.00")) {
            assertThrows(UnsupportedOperationException.class, () -> read(null, resolver(version, false, null)));
        }
        assertThrows(UnsupportedOperationException.class, () -> read(null, resolver("5.3.02", true, null)));
        for (final String alias : EditorArtMeshEvaluationReadSelectorContract.REQUIRED_ALIASES) {
            assertThrows(UnsupportedOperationException.class, () -> read(null, resolver("5.3.02", false, alias)), alias);
        }
    }

    @Test void rejectsAlternateOrUnknownRenderingConfigurationWithoutMutation() {
        for (final String property : List.of("sun.java2d.opengl", "jogl.gljpanel.noogl")) {
            final String before = System.getProperty(property);
            try {
                for (final String value : List.of("true", "True", "", "unknown")) {
                    System.setProperty(property, value);
                    assertThrows(UnsupportedOperationException.class, () -> read(null, resolver("5.3.02", false, null)));
                    assertEquals(value, System.getProperty(property));
                }
                System.setProperty(property, "false");
                assertNotNull(read(new Model(), resolver("5.3.02", false, null)));
            } finally {
                if (before == null) System.clearProperty(property); else System.setProperty(property, before);
            }
        }
    }

    @Test void rejectsOffThreadAndMalformedNativeValues() {
        final var resolver = resolver("5.3.02", false, null);
        assertThrows(IllegalStateException.class, () -> new EditorArtMeshEvaluationReadAccess(resolver).read(ID, null, null, null));
        final var mutations = List.<java.util.function.Consumer<Model>>of(
            m -> m.source.positions = new float[]{1},
            m -> m.source.positions[0] = Float.NaN,
            m -> m.source.guid = "",
            m -> m.form.positions = new float[]{1, 2},
            m -> m.form.positions = new float[]{1},
            m -> m.form.positions[0] = Float.POSITIVE_INFINITY,
            m -> m.form.label = "",
            m -> m.live.parameters.add(m.live.parameters.get(0)),
            m -> m.consumed.parameters.get(0).value = Float.NaN,
            m -> m.time = -2
        );
        for (int i = 0; i < mutations.size(); i++) {
            final Model model = new Model(); mutations.get(i).accept(model);
            assertThrows(RuntimeException.class, () -> read(model, resolver), "case " + i);
        }
    }

    private static ArtMeshEvaluationSnapshot read(final Model model, final VerifiedMemberResolver resolver) {
        return EditorHostThread.dispatch("test calculated observation", () ->
            new EditorArtMeshEvaluationReadAccess(resolver).read(ID,
                model == null ? null : model.source, model, model));
    }

    static VerifiedMemberResolver resolver(final String version, final boolean missingCapability, final String missingAlias) {
        return TestVerifiedResolvers.create(version, EditorArtMeshEvaluationReadSelectorContract.ADAPTER_SLICE_ID,
            missingCapability ? Set.of("cubism.editor-model.objects.read") : Set.of(EditorArtMeshEvaluationReadSelectorContract.CAPABILITY_ID),
            selectors().stream().filter(s -> !s.alias().equals(missingAlias)).toList(), Model.class.getClassLoader());
    }

    static List<StaticSelector> selectors() {
        return List.of(
            method(M + "art-mesh.calculated-form", Model.class, "calculated"),
            method(M + "art-mesh.interpolated-form", Model.class, "interpolated"),
            method(M + "drawable-form.coord-type", Form.class, "coordType"),
            method(M + "coord-type.label", CoordType.class, "label"),
            method(M + "model.last-updated-parameter-set", Model.class, "consumed"),
            method(M + "model.last-updated-time", Model.class, "time"),
            method(M + "parameter-set.update-version", Parameters.class, "version"),
            method(M + "art-mesh-source.guid", Source.class, "guid"),
            method(M + "art-mesh-source.positions", Source.class, "positions"),
            method(M + "art-mesh-form.positions", Form.class, "positions"),
            method(M + "drawable-form.draw-order", Form.class, "drawOrder"),
            method(M + "model.parameter-set", Model.class, "live"),
            method(M + "parameter-set.parameters", Parameters.class, "parameters"),
            method(M + "parameter.source", Parameter.class, "source"),
            method(M + "parameter-source.guid", Parameter.class, "guid"),
            method(M + "parameter.id", Parameter.class, "id"),
            method(M + "parameter.value", Parameter.class, "value"),
            method(M + "guid.value", Guid.class, "value"),
            method(M + "id.value", Guid.class, "value")
        );
    }

    private static void edt() { assertTrue(SwingUtilities.isEventDispatchThread()); }
    public record Guid(String text) { public String value() { edt(); return text; } }
    public static final class Source {
        String guid = "source"; float[] positions = {1, 2, 3, 4};
        public Guid guid() { edt(); return new Guid(guid); }
        public float[] positions() { edt(); return positions; }
    }
    public record CoordType(String text) { public String label() { edt(); return text; } }
    public static final class Form {
        float[] positions = {11, 12, 21, 22}; String label = "DeformerLocal";
        public float[] positions() { edt(); return positions; }
        public CoordType coordType() { edt(); return new CoordType(label); }
        public int drawOrder() { edt(); return 321; }
    }
    public static final class Parameter {
        final String guid; float value;
        Parameter(final String guid, final float value) { this.guid = guid; this.value = value; }
        public Parameter source() { edt(); return this; }
        public Guid guid() { edt(); return new Guid(guid); }
        public Guid id() { edt(); return new Guid("same-id"); }
        public float value() { edt(); return value; }
    }
    public static final class Parameters {
        int version; final List<Parameter> parameters;
        Parameters(final int version, final float value) { this.version = version; parameters = new ArrayList<>(List.of(new Parameter("x", value))); }
        public int version() { edt(); return version; }
        public List<Parameter> parameters() { edt(); return parameters; }
    }
    public static final class Model {
        final Source source = new Source(); final Form form = new Form(); Form calculated = form;
        final Parameters live = new Parameters(7, .5F); final Parameters consumed = new Parameters(53, 0F);
        long time = 1234;
        public Form calculated() { edt(); return calculated; }
        public Form interpolated() { edt(); return form; }
        public Parameters live() { edt(); return live; }
        public Parameters consumed() { edt(); return consumed; }
        public long time() { edt(); return time; }
    }
}
