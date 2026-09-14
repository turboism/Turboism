package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DeformerFormListUndoDecoderTest {

    @Test
    void aWarpOpacityEditDecodesWithTheDeformerTarget() {
        final Source source = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        final WarpForm before = warp(source, "form-default", 1.0F, QUAD);
        final WarpForm after = warp(source, "form-default", 0.5F, QUAD);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("WARP_DEFORMER", detail.targets().get(0).type());
        assertEquals("Warp1", detail.targets().get(0).id().orElseThrow());
        assertEquals("Body warp", detail.targets().get(0).displayName().orElseThrow());
        final var change = detail.changes().get(0);
        assertEquals(HistoryChange.Operation.SET, change.operation());
        assertEquals("opacity", change.property().orElseThrow());
        assertEquals("1.0", change.before().orElseThrow());
        assertEquals("0.5", change.after().orElseThrow());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, change.context().kind());
    }

    @Test
    void aUniformWarpPointShiftIsAProvenMove() {
        // A whole-object drag moves every control point by the same delta: the compared snapshots
        // prove the translation, so the entry carries MOVE and the signed delta, not a count.
        final Source source = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        final WarpForm before = warp(source, "form-default", 1.0F, QUAD);
        final float[] shifted = {2.0F, -1.0F, 3.0F, -1.0F, 3.0F, 0.0F, 2.0F, 0.0F};
        final WarpForm after = warp(source, "form-default", 1.0F, shifted);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Move"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(1, detail.changes().size());
        final var change = detail.changes().get(0);
        assertEquals(HistoryChange.Operation.MOVE, change.operation());
        assertEquals("translation", change.property().orElseThrow());
        assertTrue(change.before().isEmpty());
        assertEquals("(2.0,-1.0)", change.after().orElseThrow());
    }

    @Test
    void anUnevenWarpPointEditStaysABoundedCountNeverAMove() {
        final Source source = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        final WarpForm before = warp(source, "form-default", 1.0F, QUAD);
        final float[] dragged = {0.5F, 0.0F, 1.5F, 0.25F, 1.0F, 1.0F, 0.0F, 1.0F};
        final WarpForm after = warp(source, "form-default", 1.0F, dragged);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit"
        ).detail().orElseThrow();

        final var change = detail.changes().get(0);
        assertEquals(HistoryChange.Operation.SET, change.operation());
        assertEquals("controlPointPositions", change.property().orElseThrow());
        assertEquals("points=4", change.before().orElseThrow());
        assertEquals("points=4;changed=2", change.after().orElseThrow());
    }

    @Test
    void aLaterNaNPointDegradesInsteadOfBecomingTrustedGeometry() {
        final HistoryEntryDetail detail = decodeWarpPositions(
            QUAD,
            new float[] {2.0F, -1.0F, 3.0F, -1.0F, Float.NaN, 0.0F, 2.0F, 0.0F}
        );

        assertInvalidPositionDetail(detail);
    }

    @Test
    void aLaterInfinityPointDegradesInsteadOfBecomingTrustedGeometry() {
        final HistoryEntryDetail detail = decodeWarpPositions(
            QUAD,
            new float[] {2.0F, -1.0F, 3.0F, -1.0F, Float.POSITIVE_INFINITY, 0.0F, 2.0F, 0.0F}
        );

        assertInvalidPositionDetail(detail);
    }

    @Test
    void anUnchangedNaNPointDoesNotDisappearAsNoChange() {
        final float[] nonFinite = {0.0F, 0.0F, Float.NaN, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F};
        final HistoryEntryDetail detail = decodeWarpPositions(nonFinite, nonFinite.clone());

        assertInvalidPositionDetail(detail);
    }

    @Test
    void anOverflowingFinitePointDeltaDegradesInsteadOfBecomingAMove() {
        final float max = Float.MAX_VALUE;
        final HistoryEntryDetail detail = decodeWarpPositions(
            new float[] {max, 0.0F, max, 1.0F, max, 2.0F, max, 3.0F},
            new float[] {-max, 0.0F, -max, 1.0F, -max, 2.0F, -max, 3.0F}
        );

        assertInvalidPositionDetail(detail);
    }

    @Test
    void aRotationOriginOnlyChangeIsAProvenMove() {
        // A rotation deformer moves by dragging its single anchor point: an origin-only change is
        // the proven translation, reported as MOVE with the absolute before/after point.
        final Source source = new Source("Rot1", "Arm rotation", new Grid(List.of(), Map.of()));
        final RotationForm before = rotation(source, "form-default", 0.0F, 10.0F, 20.0F);
        final RotationForm after = rotation(source, "form-default", 0.0F, 14.0F, 24.0F);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Move"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("ROTATION_DEFORMER", detail.targets().get(0).type());
        assertEquals(1, detail.changes().size(),
            () -> detail.changes().stream()
                .map(c -> c.operation() + ":" + c.property().orElse("?")
                    + " " + c.before().orElse("") + "->" + c.after().orElse(""))
                .toList().toString());
        final var change = detail.changes().get(0);
        assertEquals(HistoryChange.Operation.MOVE, change.operation());
        assertEquals("translation", change.property().orElseThrow());
        assertEquals("(10.0,20.0)", change.before().orElseThrow());
        assertEquals("(14.0,24.0)", change.after().orElseThrow());
    }

    @Test
    void aRotationOriginPlusAngleIsNotAMove() {
        final Source source = new Source("Rot1", "Arm rotation", new Grid(List.of(), Map.of()));
        final RotationForm before = new RotationForm(source, new Guid("form-default"),
            1.0F, 0.0F, 10.0F, 20.0F, 1.0F, false, false);
        final RotationForm after = new RotationForm(source, new Guid("form-default"),
            1.0F, 45.0F, 14.0F, 24.0F, 1.0F, false, false);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(2, detail.changes().size());
        assertEquals("angle", detail.changes().get(0).property().orElseThrow());
        assertEquals("origin", detail.changes().get(1).property().orElseThrow());
        assertTrue(detail.changes().stream()
            .noneMatch(change -> change.operation() == HistoryChange.Operation.MOVE));
    }

    @Test
    void aSimpleUndoOnAWarpFormDecodesThroughItsStoredRedo() {
        final Source source = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        final WarpForm before = warp(source, "form-default", 1.0F, QUAD);
        final WarpForm after = warp(source, "form-default", 0.25F, QUAD);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new SimpleEntry(after, before, after),
            "Edit"
        ).detail().orElseThrow();

        assertEquals("WARP_DEFORMER", detail.targets().get(0).type());
        assertEquals("opacity", detail.changes().get(0).property().orElseThrow());
        assertEquals("0.25", detail.changes().get(0).after().orElseThrow());
    }

    @Test
    void aGroupOfUniformPerKeyformMovesRetainsTheSubjectButReportsVaryingScope() {
        // The host commits a canvas drag as one SimpleUndo per keyform of the same deformer.
        // Every child proves the same MOVE on the same target, but the different coordinates make
        // one FULL aggregate scope impossible.
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Guid firstGuid = new Guid("form-a");
        final Guid secondGuid = new Guid("form-b");
        final Grid grid = new Grid(
            List.of(binding),
            Map.of(
                firstGuid.value(), List.of(new KeyformOnGrid(new AccessKey(List.of(
                    new KeyOnParameter(binding, -30.0F)
                )))),
                secondGuid.value(), List.of(new KeyformOnGrid(new AccessKey(List.of(
                    new KeyOnParameter(binding, 30.0F)
                ))))
            )
        );
        final Source source = new Source("Warp1", "Body warp", grid);
        final float[] moved = {2.0F, -1.0F, 3.0F, -1.0F, 3.0F, 0.0F, 2.0F, 0.0F};
        final WarpForm firstBefore = warp(source, "form-a", 1.0F, QUAD);
        final WarpForm firstLive = warp(source, "form-a", 1.0F, moved);
        final WarpForm secondBefore = warp(source, "form-b", 1.0F, QUAD);
        final WarpForm secondLive = warp(source, "form-b", 1.0F, moved);
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(firstLive, firstBefore, null),
            new SimpleEntry(secondLive, secondBefore, null)
        ));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), group, "Move", true
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.detail.group-scope-vary", detail.degradationCode().orElseThrow());
        assertEquals(1, detail.targets().size());
        assertEquals("Warp1", detail.targets().get(0).id().orElseThrow());
        assertEquals(1, detail.changes().size());
        assertEquals(HistoryChange.Operation.MOVE, detail.changes().get(0).operation());
        assertEquals(HistoryEditContext.Kind.UNKNOWN, detail.changes().get(0).context().kind());
        assertTrue(detail.group().isPresent(), "bounded children stay attached for audit");
        assertEquals(2, detail.group().orElseThrow().children().size());
        assertTrue(detail.group().orElseThrow().children().stream()
            .allMatch(child -> child.detailLevel() == HistoryAction.DetailLevel.FULL));
    }

    @Test
    void aGroupOfMovesOnDifferentSubjectsStaysAPlainGroup() {
        final Source warpSource = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        final Source otherSource = new Source("Warp2", "Sleeve warp", new Grid(List.of(), Map.of()));
        final float[] moved = {2.0F, -1.0F, 3.0F, -1.0F, 3.0F, 0.0F, 2.0F, 0.0F};
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(warp(warpSource, "form-a", 1.0F, moved), warp(warpSource, "form-a", 1.0F, QUAD), null),
            new SimpleEntry(warp(otherSource, "form-a", 1.0F, moved), warp(otherSource, "form-a", 1.0F, QUAD), null)
        ));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), group, "Move", true
        ).detail().orElseThrow();

        assertTrue(detail.targets().isEmpty(), "two subjects must not be conflated into one row");
        assertTrue(detail.changes().isEmpty());
        assertEquals(2, detail.group().orElseThrow().children().size());
    }

    private static HistoryEntryDetail decodeWarpPositions(
        final float[] before,
        final float[] after
    ) {
        final Source source = new Source("Warp1", "Body warp", new Grid(List.of(), Map.of()));
        return new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(
                List.of(warp(source, "form-default", 1.0F, before)),
                List.of(warp(source, "form-default", 1.0F, after))
            ),
            "Edit"
        ).detail().orElseThrow();
    }

    private static void assertInvalidPositionDetail(final HistoryEntryDetail detail) {
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.value-codec-unavailable", detail.degradationCode().orElseThrow());
        assertTrue(detail.changes().stream()
            .noneMatch(change -> change.operation() == HistoryChange.Operation.MOVE));
        assertTrue(detail.changes().stream().noneMatch(change ->
            change.before().orElse("").contains("NaN")
                || change.before().orElse("").contains("Infinity")
                || change.after().orElse("").contains("NaN")
                || change.after().orElse("").contains("Infinity")));
    }

    private static final float[] QUAD = {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F};

    private static WarpForm warp(
        final Source source,
        final String guid,
        final float opacity,
        final float[] positions
    ) {
        return new WarpForm(source, new Guid(guid), opacity, positions);
    }

    private static RotationForm rotation(
        final Source source,
        final String guid,
        final float angle,
        final float originX,
        final float originY
    ) {
        return new RotationForm(source, new Guid(guid), 1.0F, angle, originX, originY,
            1.0F, false, false);
    }

    private static VerifiedMemberResolver resolver() {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.group.class", internal(GroupEntry.class)));
        selectors.add(method("cubism.editor-history.semantic.group.edits", GroupEntry.class, "edits", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.semantic.group.count", GroupEntry.class, "count", "()I"));
        selectors.add(method("cubism.editor-history.entry.presentation-name", GroupEntry.class, "label", "()Ljava/lang/String;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.list.class", internal(ListEntry.class)));
        selectors.add(method("cubism.editor-history.semantic.list.target", ListEntry.class, "target", "()Ljava/util/ArrayList;"));
        selectors.add(method("cubism.editor-history.semantic.list.undo", ListEntry.class, "undo", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.semantic.list.redo", ListEntry.class, "redo", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.simple.class", internal(SimpleEntry.class)));
        selectors.add(method("cubism.editor-history.semantic.simple.target", SimpleEntry.class, "target", "()Ljava/lang/Object;"));
        selectors.add(method("cubism.editor-history.semantic.simple.undo", SimpleEntry.class, "undo", "()Ljava/lang/Object;"));
        selectors.add(method("cubism.editor-history.semantic.simple.redo", SimpleEntry.class, "redo", "()Ljava/lang/Object;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.warp-form.class", internal(WarpForm.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.rotation-form.class", internal(RotationForm.class)));
        selectors.add(method("cubism.editor-history.semantic.deformer-form.source", DeformerForm.class, "source", desc(Source.class)));
        selectors.add(method("cubism.editor-history.semantic.form.guid", DeformerForm.class, "guid", desc(Guid.class)));
        selectors.add(method("cubism.editor-model.form-guid.value", Guid.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.deformer-form.opacity", DeformerForm.class, "opacity", "()F"));
        selectors.add(method("cubism.editor-model.deformer-form.multiply-color", DeformerForm.class, "multiply", desc(Color.class)));
        selectors.add(method("cubism.editor-model.deformer-form.screen-color", DeformerForm.class, "screen", desc(Color.class)));
        selectors.add(method("cubism.editor-model.warp-form.positions", WarpForm.class, "positions", "()[F"));
        selectors.add(method("cubism.editor-model.rotation-form.angle", RotationForm.class, "angle", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-x", RotationForm.class, "originX", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.origin-y", RotationForm.class, "originY", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.scale", RotationForm.class, "scale", "()F"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-x", RotationForm.class, "reflectX", "()Z"));
        selectors.add(method("cubism.editor-model.rotation-form.reflect-y", RotationForm.class, "reflectY", "()Z"));
        selectors.add(method("cubism.editor-model.float-color.red", Color.class, "red", "()F"));
        selectors.add(method("cubism.editor-model.float-color.green", Color.class, "green", "()F"));
        selectors.add(method("cubism.editor-model.float-color.blue", Color.class, "blue", "()F"));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.id", Source.class, "id", desc(HostId.class)));
        selectors.add(method("cubism.editor-model.parameter-controllable-source.local-name", Source.class, "name", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.id.value", HostId.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.parameter-controllable.keyform-grid", Source.class, "grid", desc(Grid.class)));
        selectors.add(method("cubism.editor-model.keyform-grid.bindings", Grid.class, "bindings", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-history.semantic.keyform-grid.forms-for-guid", Grid.class, "formsForGuid", "(" + type(Guid.class) + ")Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.keyform-on-grid.class", internal(KeyformOnGrid.class)));
        selectors.add(method("cubism.editor-history.semantic.keyform-on-grid.access-key", KeyformOnGrid.class, "accessKey", desc(AccessKey.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.keyform-access-key.class", internal(AccessKey.class)));
        selectors.add(method("cubism.editor-history.semantic.keyform-access-key.coordinates", AccessKey.class, "coordinates", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.key-on-parameter.class", internal(KeyOnParameter.class)));
        selectors.add(method("cubism.editor-history.semantic.key-on-parameter.binding", KeyOnParameter.class, "binding", desc(Binding.class)));
        selectors.add(method("cubism.editor-history.semantic.key-on-parameter.value", KeyOnParameter.class, "value", "()F"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.keyform-binding.class", internal(Binding.class)));
        selectors.add(method("cubism.editor-history.semantic.keyform-binding.parameter", Binding.class, "parameter", desc(Parameter.class)));
        selectors.add(method("cubism.editor-model.parameter-source.id", Parameter.class, "id", desc(HostId.class)));
        selectors.add(method("cubism.editor-model.parameter-source.name", Parameter.class, "name", "()Ljava/lang/String;"));
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.semantic-read"),
            selectors,
            DeformerFormListUndoDecoderTest.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String type(final Class<?> type) {
        return "L" + internal(type) + ";";
    }

    private static String desc(final Class<?> type) {
        return "()" + type(type);
    }

    static final class ListEntry {
        private final List<?> undo;
        private final List<?> redo;
        ListEntry(final List<?> undo, final List<?> redo) { this.undo = undo; this.redo = redo; }
        public ArrayList<Object> target() { return new ArrayList<>(); }
        public List<?> undo() { return undo; }
        public List<?> redo() { return redo; }
    }

    record SimpleEntry(Object target, Object undo, Object redo) { }

    record GroupEntry(List<Object> edits) {
        public int count() { return edits.size(); }
        public String label() { return "Grouped edit"; }
    }

    /** The shared base mirrors {@code ACDeformerForm}: identity, source and appearance scalars. */
    static class DeformerForm {
        private final Source source;
        private final Guid guid;
        private final float opacity;
        private final Color multiply;
        private final Color screen;
        DeformerForm(
            final Source source,
            final Guid guid,
            final float opacity,
            final Color multiply,
            final Color screen
        ) {
            this.source = source;
            this.guid = guid;
            this.opacity = opacity;
            this.multiply = multiply;
            this.screen = screen;
        }
        public Source source() { return source; }
        public Guid guid() { return guid; }
        public float opacity() { return opacity; }
        public Color multiply() { return multiply; }
        public Color screen() { return screen; }
    }

    static final class WarpForm extends DeformerForm {
        private final float[] positions;
        WarpForm(final Source source, final Guid guid, final float opacity, final float[] positions) {
            super(source, guid, opacity, new Color(1, 1, 1), new Color(0, 0, 0));
            this.positions = positions;
        }
        public float[] positions() { return positions; }
    }

    static final class RotationForm extends DeformerForm {
        private final float angle;
        private final float originX;
        private final float originY;
        private final float scale;
        private final boolean reflectX;
        private final boolean reflectY;
        RotationForm(
            final Source source, final Guid guid, final float opacity,
            final float angle, final float originX, final float originY,
            final float scale, final boolean reflectX, final boolean reflectY
        ) {
            super(source, guid, opacity, new Color(1, 1, 1), new Color(0, 0, 0));
            this.angle = angle;
            this.originX = originX;
            this.originY = originY;
            this.scale = scale;
            this.reflectX = reflectX;
            this.reflectY = reflectY;
        }
        public float angle() { return angle; }
        public float originX() { return originX; }
        public float originY() { return originY; }
        public float scale() { return scale; }
        public boolean reflectX() { return reflectX; }
        public boolean reflectY() { return reflectY; }
    }

    record Source(HostId id, String name, Grid grid) {
        Source(final String id, final String name, final Grid grid) { this(new HostId(id), name, grid); }
    }
    record Guid(String value) { }
    record HostId(String value) { }
    record Color(float red, float green, float blue) { }
    record Parameter(HostId id, String name) { }
    record Binding(Parameter parameter) { }
    record KeyOnParameter(Binding binding, float value) { }
    record AccessKey(List<KeyOnParameter> coordinates) { }
    record KeyformOnGrid(AccessKey accessKey) { }
    record Grid(List<Binding> bindings, Map<String, List<KeyformOnGrid>> rows) {
        public List<KeyformOnGrid> formsForGuid(final Guid guid) {
            return rows.getOrDefault(guid.value(), List.of());
        }
    }
}
