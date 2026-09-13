package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ArtMeshFormListUndoDecoderTest {

    @Test
    void operationTimeCaptureFreezesBeforeActualAfterAndAlpha() {
        final Source source = new Source("ArtMesh1", "Original name", new Grid(List.of(), Map.of()));
        final var beforeForm = form(source, "default", color(1, 1, 1), color(0, 0, 0));
        final var afterForm = form(source, "default", new Color(1, 0, 0, 0.5F), color(0, 0, 0));
        final var before = ArtMeshPropertyCapture.capture(resolver(), beforeForm, "multiplyColor").orElseThrow();
        final var origin = dev.turboism.sdk.cubism.history.HistoryOrigin.turboism("plugin.test", "color");
        final var pending = before.detail("Color", origin, java.util.Optional.empty());
        assertEquals(HistoryAction.DetailLevel.PARTIAL, pending.detailLevel());
        assertEquals(java.util.Optional.empty(), pending.changes().get(0).after());
        final var captured = before.detail("Color", origin, ArtMeshPropertyCapture.capture(resolver(), afterForm, "multiplyColor"));
        assertEquals(HistoryAction.DetailLevel.FULL, captured.detailLevel());
        assertEquals("#ffffff", captured.changes().get(0).before().orElseThrow());
        assertEquals("#ff000080", captured.changes().get(0).after().orElseThrow());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, captured.changes().get(0).context().kind());
        assertEquals("Original name", captured.targets().get(0).displayName().orElseThrow());
        assertEquals("#ffffff", before.value());
    }

    @Test
    void operationTimeCaptureRejectsDifferentFormAndUnmappedProperty() {
        final Source source = new Source("ArtMesh1", "Face", new Grid(List.of(), Map.of()));
        final var before = ArtMeshPropertyCapture.capture(resolver(),
            form(source, "first", color(1, 1, 1), color(0, 0, 0)), "opacity").orElseThrow();
        final var after = ArtMeshPropertyCapture.capture(resolver(),
            form(source, "second", color(1, 1, 1), color(0, 0, 0)), "opacity");
        final var detail = before.detail("Opacity", dev.turboism.sdk.cubism.history.HistoryOrigin.hostUnattributed(), after);
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(java.util.Optional.empty(), detail.changes().get(0).after());
        assertEquals(java.util.Optional.empty(), ArtMeshPropertyCapture.capture(resolver(),
            form(source, "first", color(1, 1, 1), color(0, 0, 0)), "foregroundColor"));
    }

    @Test
    void decodesMultiplyColorOnVerifiedDefaultShape() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form after = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.DECODED, result.outcome());
        final var detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("ART_MESH", detail.targets().get(0).type());
        assertEquals("ArtMesh1", detail.targets().get(0).id().orElseThrow());
        assertEquals("multiplyColor", detail.changes().get(0).property().orElseThrow());
        assertEquals("#ffffff", detail.changes().get(0).before().orElseThrow());
        assertEquals("#66ccff", detail.changes().get(0).after().orElseThrow());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, detail.changes().get(0).context().kind());
        assertEquals("form-default", detail.changes().get(0).context().formId().orElseThrow());
    }

    @Test
    void rejectsSimpleUndoWithoutStoredPostState() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form after = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new SimpleEntry(after, before, null),
            "Edit Artmesh"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.detail.post-state-unavailable", result.diagnosticId());
    }

    @Test
    void groupedSameTargetWithoutRedoDoesNotInventIntermediateAfter() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form a = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form b = form(source, "form-default", color(1, 0, 0), color(0, 0, 0));
        final Form c = form(source, "form-default", color(0, 0, 1), color(0, 0, 0));
        final GroupEntry root = new GroupEntry(List.of(
            new SimpleEntry(c, a, null), new SimpleEntry(c, b, null)
        ));
        final var detail = new NativeHistoryDecoderRegistry().decode(resolver(), root, "Group").detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        final var children = detail.group().orElseThrow().children();
        assertEquals(2, children.size());
        for (var child : children) {
            assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, child.detailLevel());
            assertEquals(List.of(), child.changes());
            assertEquals("history.detail.post-state-unavailable", child.degradationCode().orElseThrow());
        }
    }
    @Test
    void rejectsSimpleUndoMutableTargetAwayFromAppliedBoundary() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form current = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new SimpleEntry(current, before, null),
            "Edit Artmesh"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.detail.post-state-unavailable", result.diagnosticId());
    }

    @Test
    void storedRedoPreservesIntermediateValuesWhenTargetHasAdvanced() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form a = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form b = form(source, "form-default", color(1, 0, 0), color(0, 0, 0));
        final Form c = form(source, "form-default", color(0, 0, 1), color(0, 0, 0));
        final NativeHistoryDecoderRegistry registry = new NativeHistoryDecoderRegistry();
        final var first = registry.decode(resolver(), new SimpleEntry(c, a, b), "Edit").detail().orElseThrow();
        final var second = registry.decode(resolver(), new SimpleEntry(c, b, c), "Edit").detail().orElseThrow();
        assertEquals("#ffffff", first.changes().get(0).before().orElseThrow());
        assertEquals("#ff0000", first.changes().get(0).after().orElseThrow());
        assertEquals("#ff0000", second.changes().get(0).before().orElseThrow());
        assertEquals("#0000ff", second.changes().get(0).after().orElseThrow());
        assertEquals(first, registry.decode(resolver(), new SimpleEntry(a, a, b), "Edit").detail().orElseThrow());
    }

    @Test
    void rejectsMalformedRedoInsteadOfSubstitutingCurrentTarget() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form a = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form b = form(source, "form-default", color(1, 0, 0), color(0, 0, 0));
        final var result = new NativeHistoryDecoderRegistry().decode(
            resolver(), new SimpleEntry(b, a, "not a form"), "Edit"
        );
        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.detail.value-unsupported", result.diagnosticId());
    }

    @Test
    void decodesScreenColorWithCompleteOrderedKeyformCoordinates() {
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Parameter angleY = new Parameter(new HostId("ParamAngleY"), "Angle Y");
        final Binding xBinding = new Binding(angleX);
        final Binding yBinding = new Binding(angleY);
        final Guid guid = new Guid("form-key");
        final AccessKey accessKey = new AccessKey(List.of(
            new KeyOnParameter(xBinding, 30.0F),
            new KeyOnParameter(yBinding, -10.0F)
        ));
        final Grid grid = new Grid(
            List.of(xBinding, yBinding),
            Map.of(guid.value(), List.of(new KeyformOnGrid(accessKey)))
        );
        final Source source = new Source("ArtMesh1", "Face shadow", grid);
        final Form before = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0));
        final Form after = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0.2F, 0.4F, 0.6F));

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        );

        final var change = result.detail().orElseThrow().changes().get(0);
        assertEquals(HistoryAction.DetailLevel.FULL, result.detail().orElseThrow().detailLevel());
        assertEquals("screenColor", change.property().orElseThrow());
        assertEquals(HistoryEditContext.Kind.KEYFORM, change.context().kind());
        assertEquals(
            List.of("ParamAngleX", "ParamAngleY"),
            change.context().coordinates().stream()
                .map(value -> value.parameter().id().orElseThrow())
                .toList()
        );
        assertEquals(
            List.of("30.0", "-10.0"),
            change.context().coordinates().stream().map(value -> value.value()).toList()
        );
    }

    @Test
    void incompleteBindingTupleDegradesInsteadOfClaimingDefaultOrFull() {
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Guid guid = new Guid("form-key");
        final Grid grid = new Grid(
            List.of(binding),
            Map.of(guid.value(), List.of(new KeyformOnGrid(new AccessKey(List.of()))))
        );
        final Source source = new Source("ArtMesh1", "Face shadow", grid);
        final Form before = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0));
        final Form after = new Form(source, guid, 0.5F, 0, color(1, 1, 1), color(0, 0, 0));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.keyform-coordinates-incomplete", detail.degradationCode().orElseThrow());
        assertEquals(HistoryEditContext.Kind.UNKNOWN, detail.changes().get(0).context().kind());
    }

    @Test
    void invalidColorDegradesWithoutStringifyingTheHostObject() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form after = form(source, "form-default", color(Float.NaN, 0.8F, 1.0F), color(0, 0, 0));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.value-codec-unavailable", detail.degradationCode().orElseThrow());
        assertEquals(List.of(), detail.changes());
    }

    @Test
    void preservesMultipleFormAndPropertyChangesInUndoOrder() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form firstBefore = new Form(source, new Guid("form-a"), 1.0F, 0, color(1, 1, 1), color(0, 0, 0));
        final Form firstAfter = new Form(source, new Guid("form-a"), 0.5F, 10, color(1, 1, 1), color(0, 0, 0));
        final Form secondBefore = form(source, "form-b", color(1, 1, 1), color(0, 0, 0));
        final Form secondAfter = form(source, "form-b", color(0.5F, 0.6F, 0.7F), color(0.1F, 0.2F, 0.3F));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(firstBefore, secondBefore), List.of(firstAfter, secondAfter)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(
            List.of("opacity", "drawOrder", "multiplyColor", "screenColor"),
            detail.changes().stream().map(change -> change.property().orElseThrow()).toList()
        );
        assertEquals(
            List.of("form-a", "form-a", "form-b", "form-b"),
            detail.changes().stream().map(change -> change.context().formId().orElseThrow()).toList()
        );
    }

    @Test
    void capsProjectedChangesAtTheNativeDetailBudget() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final List<Form> before = new ArrayList<>();
        final List<Form> after = new ArrayList<>();
        for (int index = 0; index < 17; index++) {
            final Guid guid = new Guid("form-" + index);
            before.add(new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0)));
            after.add(new Form(source, guid, 0.5F, 1, color(0.5F, 0.5F, 0.5F), color(0.25F, 0.25F, 0.25F)));
        }

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(before, after),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(64, detail.changes().size());
        assertEquals("history.detail.node-or-depth-limit", detail.degradationCode().orElseThrow());
    }

    @Test
    void duplicateFormGuidsFailClosed() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form first = form(source, "duplicate", color(1, 1, 1), color(0, 0, 0));
        final Form second = form(source, "duplicate", color(0.5F, 0.5F, 0.5F), color(0, 0, 0));

        final var result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(first, second), List.of(first, second)),
            "Edit Artmesh"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.form-scope-unresolved", result.diagnosticId());
    }

    @Test
    void mismatchedBeforeAndAfterTargetsFailClosed() {
        final Source beforeSource = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Source afterSource = new Source("ArtMesh2", "Hair shadow", new Grid(List.of(), Map.of()));
        final Form before = form(beforeSource, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form after = form(afterSource, "form-default", color(0.5F, 0.5F, 0.5F), color(0, 0, 0));

        final var result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.target-unresolved", result.diagnosticId());
    }

    private static Form form(
        final Source source,
        final String guid,
        final Color multiply,
        final Color screen
    ) {
        return new Form(source, new Guid(guid), 1.0F, 0, multiply, screen);
    }

    private static Color color(final float red, final float green, final float blue) {
        return new Color(red, green, blue);
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
        selectors.add(StaticSelector.classSelector("cubism.editor-history.semantic.art-mesh-form.class", internal(Form.class)));
        selectors.add(method("cubism.editor-history.semantic.art-mesh-form.source", Form.class, "source", desc(Source.class)));
        selectors.add(method("cubism.editor-history.semantic.form.guid", Form.class, "guid", desc(Guid.class)));
        selectors.add(method("cubism.editor-model.form-guid.value", Guid.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.drawable-form.opacity", Form.class, "opacity", "()F"));
        selectors.add(method("cubism.editor-model.drawable-form.draw-order", Form.class, "drawOrder", "()I"));
        selectors.add(method("cubism.editor-model.drawable-form.multiply-color", Form.class, "multiply", desc(Color.class)));
        selectors.add(method("cubism.editor-model.drawable-form.screen-color", Form.class, "screen", desc(Color.class)));
        selectors.add(method("cubism.editor-model.float-color.red", Color.class, "red", "()F"));
        selectors.add(method("cubism.editor-model.float-color.green", Color.class, "green", "()F"));
        selectors.add(method("cubism.editor-model.float-color.blue", Color.class, "blue", "()F"));
        selectors.add(method("cubism.editor-model.float-color.alpha", Color.class, "alpha", "()F"));
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
            ArtMeshFormListUndoDecoderTest.class.getClassLoader()
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

    record Form(Source source, Guid guid, float opacity, int drawOrder, Color multiply, Color screen) { }
    record Source(HostId id, String name, Grid grid) {
        Source(final String id, final String name, final Grid grid) { this(new HostId(id), name, grid); }
    }
    record Guid(String value) { }
    record HostId(String value) { }
    record Color(float red, float green, float blue, float alpha) {
        Color(final float red, final float green, final float blue) { this(red, green, blue, 1.0F); }
    }
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
