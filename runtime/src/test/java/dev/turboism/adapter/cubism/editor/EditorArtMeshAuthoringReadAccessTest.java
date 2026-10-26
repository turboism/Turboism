package dev.turboism.adapter.cubism.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorArtMeshAuthoringReadSelectorContract;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.Point2;
import java.lang.invoke.MethodType;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class EditorArtMeshAuthoringReadAccessTest {
    private static final String M = "cubism.editor-model.";
    private static final String H = "cubism.editor-history.semantic.";
    private static final ArtMeshId ID = new ArtMeshId("mesh");

    @Test
    void preservesSourceStoredFormsFullTuplesAliasesAndNullableMorphKeys() {
        final Source source = new Source();
        final ArtMeshAuthoringSnapshot snapshot = read(source, resolver("5.3.02", null, null));
        assertEquals("source", snapshot.sourceGuid());
        assertEquals(ID, snapshot.artMeshId());
        assertEquals(new Point2(100, 101), snapshot.sourceGeometry().positions().get(0));
        assertEquals(List.of(0, 1, 2, 0, 2, 3), snapshot.sourceGeometry().triangleIndices());
        assertEquals(9, snapshot.cells().size());
        assertEquals(10, snapshot.forms().size()); // eight grid forms, two morph forms
        assertEquals(List.of(-1F, 0F, 1F), snapshot.axes().get(0).keys());
        assertEquals(0F, snapshot.axes().get(0).defaultValue());
        assertEquals(
                snapshot.axes().get(0).parameterId(), snapshot.axes().get(1).parameterId());
        assertNotEquals(
                snapshot.axes().get(0).parameterGuid(), snapshot.axes().get(1).parameterGuid());
        final var defaults = snapshot.cells().stream()
                .filter(c -> c.coordinates().stream().allMatch(p -> p.value() == 0F))
                .toList();
        assertEquals(1, defaults.size());
        assertEquals("f4", defaults.get(0).formGuid());
        assertEquals(
                List.of("y", "x"),
                defaults.get(0).coordinates().stream()
                        .map(ArtMeshAuthoringSnapshot.Coordinate::parameterGuid)
                        .toList());
        assertEquals(
                2,
                snapshot.cells().stream().filter(c -> c.formGuid().equals("f0")).count());
        assertEquals(
                new Point2(4, 5),
                snapshot.forms().stream()
                        .filter(f -> f.formGuid().equals("f4"))
                        .findFirst()
                        .orElseThrow()
                        .positions()
                        .get(0));
        assertEquals(
                List.of(Optional.of(.5F), Optional.of(1F), Optional.empty(), Optional.of(0F)),
                snapshot.morphKeys().stream()
                        .map(ArtMeshAuthoringSnapshot.MorphKey::keyValue)
                        .toList());
        assertEquals(
                List.of("m05", "m10", "f4", "f4"),
                snapshot.morphKeys().stream()
                        .map(ArtMeshAuthoringSnapshot.MorphKey::formGuid)
                        .toList());
        assertEquals(
                204,
                snapshot.forms().stream()
                        .filter(f -> f.formGuid().equals("f4"))
                        .findFirst()
                        .orElseThrow()
                        .drawOrder());
        // Native fixture exposes no current-form, edit, undo, setter or dirty selector.
        source.positions[0] = 999;
        source.forms.get(0).positions[0] = 999;
        source.grid.bindings.get(0).keys.clear();
        source.forms.clear();
        assertEquals(new Point2(100, 101), snapshot.sourceGeometry().positions().get(0));
        assertEquals(new Point2(0, 1), snapshot.forms().get(0).positions().get(0));
        assertEquals(3, snapshot.axes().get(0).keys().size());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.forms().clear());
        assertThrows(
                UnsupportedOperationException.class,
                () -> snapshot.cells().get(0).coordinates().clear());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.0.00", "5.0.06", "5.1.03", "5.2.00", "5.2.03", "5.3.00", "5.3.04"})
    void readsTheSameStoredAuthoringOnEveryReviewedGeneration(final String version) {
        final ArtMeshAuthoringSnapshot reference = read(new Source(), resolver("5.3.02", null, null));
        assertEquals(reference, read(new Source(), resolver(version, null, null)));
    }

    @Test
    void readsStoredOpacityOnlyWhenItsSelectorIsAdmittedAndPreservesRawMorphValues() {
        var source = new Source();
        source.forms.get(0).storedOpacity = .25F;
        source.forms.get(9).storedOpacity = -.5F;
        var observed = read(source, opacityResolver());
        assertEquals(Optional.of(.25F), observed.forms().get(0).opacity());
        assertEquals(Optional.of(-.5F), observed.forms().get(9).opacity());
        source.forms.get(0).storedOpacity = .75F;
        assertEquals(Optional.of(.25F), observed.forms().get(0).opacity(), "Detached value is immutable");
        assertNotEquals(observed, read(source, opacityResolver()), "CAS must observe same-revision opacity changes");
        var unknown = read(source, resolver("5.3.02", null, null));
        assertTrue(unknown.forms().stream().allMatch(f -> f.opacity().isEmpty()));
        assertNotEquals(unknown, observed);
    }

    @Test
    void admittedMalformedStoredOpacityFailsInsteadOfFallingBackToUnknown() {
        for (float value : new float[] {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY}) {
            var source = new Source();
            source.forms.get(0).storedOpacity = value;
            assertThrows(IllegalStateException.class, () -> read(source, opacityResolver()));
            assertTrue(read(source, resolver("5.3.02", null, null))
                    .forms()
                    .get(0)
                    .opacity()
                    .isEmpty());
        }
    }

    static VerifiedMemberResolver opacityResolver() {
        var selectors = new ArrayList<>(selectors());
        selectors.add(method(M + "drawable-form.opacity", StoredForm.class, "opacity"));
        return TestVerifiedResolvers.create(
                "5.3.02",
                EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS,
                selectors,
                Source.class.getClassLoader());
    }

    @Test
    void reportsNativeIndexRangeWithoutAcceptingInvalidSourceGeometry() {
        final Source source = new Source();
        source.indices[0] = 99;
        var failure = assertThrows(IllegalArgumentException.class, () -> read(source, resolver("5.3.02", null, null)));
        assertTrue(failure.getMessage().contains("Invalid source geometry for mesh"));
        assertTrue(failure.getMessage().contains("vertices=4"));
        assertTrue(failure.getMessage().contains("indexCount=6"));
        assertTrue(failure.getMessage().contains("indexMax=99"));
    }

    @Test
    void doesNotGuessDefaultWhenItIsNotADeclaredKey() {
        final Source source = new Source();
        source.grid.bindings.get(0).parameter.defaultValue = .25F;
        assertEquals(
                .25F, read(source, resolver("5.3.02", null, null)).axes().get(0).defaultValue());
    }

    @Test
    void requiresEveryCapabilityAndSelectorBeforeTouchingSource() {
        for (final String version : List.of("5.4.00")) {
            assertThrows(UnsupportedOperationException.class, () -> read(null, resolver(version, null, null)));
        }
        for (final String capability : EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS) {
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> read(null, resolver("5.3.02", capability, null)),
                    capability);
        }
        for (final String alias : EditorArtMeshAuthoringReadSelectorContract.REQUIRED_ALIASES) {
            assertThrows(UnsupportedOperationException.class, () -> read(null, resolver("5.3.02", null, alias)), alias);
        }
    }

    @Test
    void rejectsMalformedNativeStateInsteadOfReturningPartialSnapshot() {
        final var mutations = List.<java.util.function.Consumer<Source>>of(
                s -> s.positions = new float[] {1},
                s -> s.positions[0] = Float.NaN,
                s -> s.indices[0] = 99,
                s -> s.forms.get(0).positions = new float[] {1, 2},
                s -> s.forms.get(0).guid = new Guid(""),
                s -> s.forms.add(s.forms.get(0)),
                s -> s.grid.bindings.add(s.grid.bindings.get(0)),
                s -> s.grid.rows.get("f0").get(0).key.coordinates.clear(),
                s -> s.grid.rows.get("f0").get(0).key.coordinates.get(0).binding = new Binding("foreign"),
                s -> s.grid.rows.get("f0").get(0).key.coordinates.get(0).value = .123F,
                s -> s.targets.targets.add(new Target(new Guid("missing"), .7F)),
                s -> s.targets.targets.get(0).value = Float.POSITIVE_INFINITY);
        for (int i = 0; i < mutations.size(); i++) {
            final Source source = new Source();
            mutations.get(i).accept(source);
            assertThrows(RuntimeException.class, () -> read(source, resolver("5.3.02", null, null)), "case " + i);
        }
    }

    @Test
    void rejectsOffThreadAndSupportsAlreadyDispatchedCalls() {
        final var access = new EditorArtMeshAuthoringReadAccess(resolver("5.3.02", null, null));
        assertFalse(SwingUtilities.isEventDispatchThread());
        assertThrows(IllegalStateException.class, () -> access.read(ID, new Source()));
        assertNotNull(EditorHostThread.dispatch(
                "outer", () -> EditorHostThread.dispatch("inner", () -> access.read(ID, new Source()))));
    }

    private static ArtMeshAuthoringSnapshot read(final Source source, final VerifiedMemberResolver resolver) {
        return EditorHostThread.dispatch(
                "test stored authoring", () -> new EditorArtMeshAuthoringReadAccess(resolver).read(ID, source));
    }

    private static VerifiedMemberResolver resolver(
            final String version, final String missingCapability, final String missingAlias) {
        final var capabilities = new HashSet<>(EditorArtMeshAuthoringReadSelectorContract.CAPABILITY_IDS);
        capabilities.remove(missingCapability);
        return TestVerifiedResolvers.create(
                version,
                EditorArtMeshAuthoringReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors().stream()
                        .filter(s -> !s.alias().equals(missingAlias))
                        .toList(),
                Source.class.getClassLoader());
    }

    static List<StaticSelector> selectors() {
        return List.of(
                method(M + "guid.value", Guid.class, "value"),
                method(M + "form-guid.value", Guid.class, "value"),
                method(M + "id.value", Guid.class, "value"),
                method(M + "art-mesh-source.guid", Source.class, "guid"),
                method(M + "art-mesh-source.positions", Source.class, "positions"),
                method(M + "art-mesh-source.uvs", Source.class, "uvs"),
                method(M + "art-mesh-source.indices", Source.class, "indices"),
                method(M + "art-mesh-source.keyforms", Source.class, "forms"),
                method(M + "art-mesh-form.positions", StoredForm.class, "positions"),
                method(M + "drawable-form.draw-order", StoredForm.class, "drawOrder"),
                method(M + "parameter-controllable.keyform-grid", Source.class, "grid"),
                method(M + "keyform-grid.bindings", Grid.class, "bindings"),
                method(M + "keyform-binding.keys", Binding.class, "keys"),
                method(M + "keyform-binding.parameter-guid", Binding.class, "guid"),
                method(M + "keyform-binding.parameter-id", Binding.class, "id"),
                method(M + "parameter-source.minimum", Parameter.class, "minimum"),
                method(M + "parameter-source.default", Parameter.class, "defaultValue"),
                method(M + "parameter-source.maximum", Parameter.class, "maximum"),
                method(H + "keyform-binding.parameter", Binding.class, "parameter"),
                method(H + "form.guid", StoredForm.class, "guid"),
                method(H + "keyform-grid.forms-for-guid", Grid.class, "rows", Guid.class),
                method(H + "keyform-on-grid.access-key", Row.class, "key"),
                method(H + "keyform-access-key.coordinates", AccessKey.class, "coordinates"),
                method(H + "key-on-parameter.binding", Coordinate.class, "binding"),
                method(H + "key-on-parameter.value", Coordinate.class, "value"),
                method(M + "parameter-controllable.morph-target-set", Source.class, "targets"),
                method(M + "morph-target-set.morph-targets", Targets.class, "targets"),
                method(M + "morph-target.key-value", Target.class, "value"),
                method(M + "morph-target.keyform-guid", Target.class, "formGuid"),
                method(M + "morph-target.parameter-guid", Target.class, "parameterGuid"));
    }

    static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final Class<?>... arguments) {
        try {
            final var method = owner.getMethod(name, arguments);
            return StaticSelector.method(
                    alias,
                    owner.getName().replace('.', '/'),
                    name,
                    MethodType.methodType(method.getReturnType(), arguments).toMethodDescriptorString(),
                    StaticSelector.ACCESS_PUBLIC);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static void edt() {
        assertTrue(SwingUtilities.isEventDispatchThread());
    }

    private static float[] positions(final float start) {
        return new float[] {start, start + 1, start + 2, start + 3, start + 4, start + 5, start + 6, start + 7};
    }

    public record Guid(String text) {
        public String value() {
            edt();
            return text;
        }
    }

    public static final class Parameter {
        float defaultValue;

        public float minimum() {
            edt();
            return -1;
        }

        public float defaultValue() {
            edt();
            return defaultValue;
        }

        public float maximum() {
            edt();
            return 1;
        }
    }

    public static class Binding {
        final Guid guid;
        final Parameter parameter = new Parameter();
        final List<Float> keys = new ArrayList<>(List.of(-1F, 0F, 1F));

        Binding(final String guid) {
            this.guid = new Guid(guid);
        }

        public Guid guid() {
            edt();
            return guid;
        }

        public Guid id() {
            edt();
            return new Guid("same-id");
        }

        public Parameter parameter() {
            edt();
            return parameter;
        }

        public List<Float> keys() {
            edt();
            return keys;
        }
    }

    public static final class Coordinate {
        Binding binding;
        float value;

        Coordinate(final Binding binding, final float value) {
            this.binding = binding;
            this.value = value;
        }

        public Binding binding() {
            edt();
            return binding;
        }

        public float value() {
            edt();
            return value;
        }
    }

    public record AccessKey(List<Coordinate> coordinates) {
        @Override
        public List<Coordinate> coordinates() {
            edt();
            return coordinates;
        }
    }

    public record Row(AccessKey key, Guid formGuid) {
        public Row(AccessKey key) {
            this(key, new Guid("unassigned"));
        }

        @Override
        public AccessKey key() {
            edt();
            return key;
        }
    }

    public static class Grid {
        final List<Binding> bindings = new ArrayList<>(List.of(new Binding("x"), new Binding("y")));
        final Map<String, List<Row>> rows = new HashMap<>();

        public List<Binding> bindings() {
            edt();
            return bindings;
        }

        public List<Row> rows(final Guid guid) {
            edt();
            return rows.getOrDefault(guid.text(), List.of());
        }
    }

    public static final class StoredForm {
        Guid guid;
        float[] positions;
        int order;
        float storedOpacity = 1;
        Runnable afterSetPositions;
        Runnable afterSetOpacity;

        StoredForm(final String guid, final int value) {
            this.guid = new Guid(guid);
            positions = EditorArtMeshAuthoringReadAccessTest.positions(value);
            order = 200 + value;
        }

        public Guid guid() {
            edt();
            return guid;
        }

        public float[] positions() {
            edt();
            return positions;
        }

        public int drawOrder() {
            edt();
            return order;
        }

        public float opacity() {
            edt();
            return storedOpacity;
        }

        public void setPositions(final float[] value) {
            edt();
            positions = value;
            var action = afterSetPositions;
            afterSetPositions = null;
            if (action != null) action.run();
        }

        public void setOpacity(final float value) {
            edt();
            storedOpacity = value;
            var action = afterSetOpacity;
            afterSetOpacity = null;
            if (action != null) action.run();
        }

        public void setDrawOrder(final int value) {
            edt();
            order = value;
        }
    }

    public static final class Target {
        final Guid formGuid;
        Float value;

        Target(final Guid formGuid, final Float value) {
            this.formGuid = formGuid;
            this.value = value;
        }

        public Guid formGuid() {
            edt();
            return formGuid;
        }

        public Guid parameterGuid() {
            edt();
            return new Guid("morph");
        }

        public Float value() {
            edt();
            return value;
        }
    }

    public static final class Targets {
        final List<Target> targets = new ArrayList<>();

        public List<Target> targets() {
            edt();
            return targets;
        }
    }

    public static class Source {
        float[] positions = EditorArtMeshAuthoringReadAccessTest.positions(100);
        final int[] indices = {0, 1, 2, 0, 2, 3};
        final Grid grid = new Grid();
        final List<StoredForm> forms = new ArrayList<>();
        final Targets targets = new Targets();

        Source() {
            for (int i = 0; i < 9; i++) {
                final String guid = "f" + (i == 8 ? 0 : i);
                if (i != 8) forms.add(new StoredForm(guid, i));
                final var coordinates = new ArrayList<>(List.of(
                        new Coordinate(grid.bindings.get(1), i % 3 - 1),
                        new Coordinate(grid.bindings.get(0), i / 3 - 1)));
                grid.rows.computeIfAbsent(guid, unused -> new ArrayList<>()).add(new Row(new AccessKey(coordinates)));
            }
            forms.add(new StoredForm("m05", 50));
            forms.add(new StoredForm("m10", 60));
            targets.targets.add(new Target(new Guid("m05"), .5F));
            targets.targets.add(new Target(new Guid("m10"), 1F));
            targets.targets.add(new Target(new Guid("f4"), null));
            targets.targets.add(new Target(new Guid("f4"), 0F));
        }

        public Guid guid() {
            edt();
            return new Guid("source");
        }

        public float[] positions() {
            edt();
            return positions;
        }

        public float[] uvs() {
            edt();
            return new float[] {0, 0, 1, 0, 1, 1, 0, 1};
        }

        public int[] indices() {
            edt();
            return indices;
        }

        public List<StoredForm> forms() {
            edt();
            return forms;
        }

        public Grid grid() {
            edt();
            return grid;
        }

        public Targets targets() {
            edt();
            return targets;
        }
    }
}
