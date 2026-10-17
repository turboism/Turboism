package dev.turboism.plugin.historypanel.service;

import static dev.turboism.plugin.historypanel.service.HistoryPanelTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntry;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryEntryId;
import dev.turboism.sdk.cubism.history.HistoryGroup;
import dev.turboism.sdk.cubism.history.HistoryOrigin;
import dev.turboism.sdk.cubism.history.HistoryParameterCoordinate;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import dev.turboism.sdk.cubism.history.HistorySnapshot;
import dev.turboism.sdk.cubism.history.HistoryTarget;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskScheduler;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.ui.PanelView;
import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.CubismIcon;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class HistoryPanelServiceTest {

    @Test
    void rendersEntriesInsideScrollWithCheckboxPerRowAndNoTopButtons() {
        final HistorySnapshot snapshot = available(
                1,
                2,
                1,
                List.of(
                        new HistoryEntry(
                                0,
                                "Set Parameter Value",
                                true,
                                Optional.of(action("ParamAngleX", "-19.8", "-4.199999")),
                                Optional.of(new HistoryEntryId("entry-0")),
                                Optional.empty()),
                        new HistoryEntry(
                                1,
                                "Set Parameter Value",
                                true,
                                Optional.of(action("ParamAngleX", "-4.199999", "12.599998")),
                                Optional.of(new HistoryEntryId("entry-1")),
                                Optional.empty())),
                true,
                false);

        final PanelView view = render(snapshot);

        // The whole undo/redo list is wrapped in a scroll view.
        assertTrue(view instanceof PanelView.Scroll, "list must be inside a scroll view");

        // The top Scroll -> Column children form the exact node-type sequence for
        // two entries: entry toggle, row separator, entry toggle. The last entry
        // has no trailing separator; there is no count top bar.
        final PanelView.Column column = (PanelView.Column) ((PanelView.Scroll) view).child();
        final List<PanelView> children = column.children();
        assertEquals(3, children.size(), "children=" + children);
        assertTrue(children.get(0) instanceof PanelView.Toggle, "first entry toggle");
        assertTrue(children.get(1) instanceof PanelView.Separator, "row separator between entries");
        assertTrue(children.get(2) instanceof PanelView.Toggle, "second entry toggle");
        final String text = flatten(view);
        assertFalse(text.contains("Current records"), "no count top bar");
        assertFalse(text.contains("cursor"), "no cursor statistics");
        assertFalse(text.contains("Set Parameter Value"), "semantic rows do not repeat the raw host label");
        assertTrue(text.contains("Parameter(ParamAngleX) value changed from -4.199999 to 12.599998"), text);
        assertFalse(text.contains("jump to that state"), "no bottom click hint");
        assertFalse(text.contains("unattributed"), "host-unattributed origin is hidden from panel rows");

        // Top level carries no undo/redo buttons.
        assertFalse(hasButton(view, "history.panel.undo"), "top undo button removed");
        assertFalse(hasButton(view, "history.panel.redo"), "top redo button removed");

        // Each row leads with a checkbox: applied (index < cursor) checked and
        // full color; undone (index >= cursor) unchecked and grayed.
        final List<PanelView.Toggle> toggles = toggles(view);
        assertEquals(2, toggles.size());
        assertTrue(toggles.get(0).selected(), "applied entry is checked");
        assertFalse(toggles.get(0).grayed(), "applied entry keeps its color");
        assertFalse(toggles.get(1).selected(), "undone entry is unchecked");
        assertTrue(toggles.get(1).grayed(), "undone entry label is grayed");
    }

    @Test
    void eachEntryIsOneWrappingToggleWithRetainedIdentityAndDetail() {
        final HistorySnapshot snapshot = available(
                1,
                2,
                1,
                List.of(
                        new HistoryEntry(
                                0,
                                "Set Parameter Value",
                                true,
                                Optional.of(action("ParamAngleX", "-19.8", "-4.199999")),
                                Optional.of(new HistoryEntryId("entry-0")),
                                Optional.empty()),
                        new HistoryEntry(
                                1,
                                "Set Parameter Value",
                                true,
                                Optional.of(action("ParamAngleX", "-4.199999", "12.599998")),
                                Optional.of(new HistoryEntryId("entry-1")),
                                Optional.empty())),
                true,
                false);

        final PanelView view = render(snapshot);
        final List<PanelView.Toggle> toggles = toggles(view);
        assertEquals(2, toggles.size(), "exactly one functional Toggle per entry");

        // The wrapping toggle carries the full label plus the structured
        // detail in one label, so long entries wrap inside the viewport.
        final PanelView.Toggle first = toggles.get(0);
        assertEquals("history.entry.toggle.ZW50cnktMA", first.id());
        assertEquals(HistoryPanelService.moveActionId("entry-0"), first.actionId());
        assertTrue(first.label().startsWith("1 Parameter(ParamAngleX)"), first.label());
        assertTrue(
                first.label().contains("Parameter(ParamAngleX) value changed from -19.8 to -4.199999"), first.label());
        assertTrue(first.selected(), "applied entry is checked");
        assertFalse(first.grayed(), "applied entry keeps its color");

        final PanelView.Toggle second = toggles.get(1);
        assertEquals("history.entry.toggle.ZW50cnktMQ", second.id());
        assertEquals(HistoryPanelService.moveActionId("entry-1"), second.actionId());
        assertTrue(second.label().startsWith("2 Parameter(ParamAngleX)"), second.label());
        assertTrue(
                second.label().contains("Parameter(ParamAngleX) value changed from -4.199999 to 12.599998"),
                second.label());
        assertFalse(second.selected(), "undone entry is unchecked");
        assertTrue(second.grayed(), "undone entry label is grayed");
    }

    @Test
    void rendersArtmeshDefaultShapeAsDomainSemantics() {
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Host technical label",
                HistoryAction.DetailLevel.PARTIAL,
                HistoryOrigin.hostUnattributed(),
                List.of(new HistoryTarget("ART_MESH", Optional.of("ArtMesh1"), Optional.of("Face shadow"))),
                List.of(new HistoryChange(
                        HistoryChange.Operation.SET,
                        Optional.of(0),
                        Optional.of("multiplyColor"),
                        Optional.empty(),
                        Optional.of("#66ccff"),
                        new HistoryEditContext(
                                HistoryEditContext.Kind.DEFAULT_FORM, Optional.of("default-form"), List.of()))),
                Optional.empty(),
                Optional.of("history.before-value-unavailable"));
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Host technical label",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("artmesh-default")),
                Optional.empty(),
                detail);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final PanelView.Toggle toggle = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0);

        assertNotNull(toggle.inlineLabel());
        assertEquals("1 Set multiply color Artmesh icon Face shadow · partial detail", toggle.label());
        assertFalse(toggle.label().contains("#66ccff"), toggle.label());
        assertFalse(toggle.label().contains("Host technical label"), toggle.label());
    }

    @Test
    void rendersCompleteParameterTupleForArtmeshKeyform() {
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Host technical label",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(new HistoryTarget("ART_MESH", Optional.of("ArtMesh1"), Optional.of("Face shadow"))),
                List.of(new HistoryChange(
                        HistoryChange.Operation.SET,
                        Optional.of(0),
                        Optional.of("multiplyColor"),
                        Optional.of("#ffffff"),
                        Optional.of("#66ccff"),
                        new HistoryEditContext(
                                HistoryEditContext.Kind.KEYFORM,
                                Optional.of("form-1"),
                                List.of(
                                        new HistoryParameterCoordinate(
                                                new HistoryTarget(
                                                        "PARAMETER",
                                                        Optional.of("ParamAngleX"),
                                                        Optional.of("Angle X")),
                                                "30"),
                                        new HistoryParameterCoordinate(
                                                new HistoryTarget(
                                                        "PARAMETER",
                                                        Optional.of("ParamAngleY"),
                                                        Optional.of("Angle Y")),
                                                "-10"))))),
                Optional.empty(),
                Optional.empty());
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Host technical label",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("artmesh-keyform")),
                Optional.empty(),
                detail);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final PanelView.Toggle toggle = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0);

        assertNotNull(toggle.inlineLabel());
        assertEquals("1 Set multiply color Artmesh icon Face shadow", toggle.label());
        assertFalse(toggle.label().contains("Angle X=30"), toggle.label());
        assertFalse(toggle.label().contains("#ffffff"), toggle.label());
    }

    @Test
    void aProvenSingleChildRelationRendersTheTypedRelationInsteadOfTheHostLabel() {
        // r90 human-in-loop evidence: the host recorded an ArtMesh joining a Part as one
        // grouped move labeled by the host. The row must render the typed relation copy
        // instead of falling back to the raw host label.
        final HistoryTarget child = new HistoryTarget("ART_MESH", Optional.of("art-1"), Optional.of("图形网格"));
        final HistoryTarget nextParent = new HistoryTarget("PART", Optional.of("part-1"), Optional.of("PSD剪切蒙版导入测试"));
        final HistoryRelationChange relation = new HistoryRelationChange(
                HistoryRelationChange.Kind.PART_MEMBERSHIP,
                new HistoryRelationChange.Endpoint(
                        HistoryRelationChange.State.TARGET,
                        Optional.of(new HistoryTarget("PART", Optional.of("part-0"), Optional.of("杂项")))),
                new HistoryRelationChange.Endpoint(HistoryRelationChange.State.TARGET, Optional.of(nextParent)));
        final HistoryChange change = new HistoryChange(
                HistoryChange.Operation.SET,
                Optional.of(0),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                context(HistoryEditContext.Kind.OBJECT),
                Optional.of(relation));
        final HistoryEntryDetail childDetail = new HistoryEntryDetail(
                "Add Part",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(child),
                List.of(change),
                Optional.empty(),
                Optional.empty());
        final HistoryEntryDetail groupDetail = new HistoryEntryDetail(
                "物体的移动",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(),
                List.of(),
                Optional.of(new HistoryGroup(Optional.empty(), 1, List.of(childDetail), false)),
                Optional.empty());
        final HistoryEntry entry = new HistoryEntry(
                0,
                "物体的移动",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("grouped-part-join")),
                Optional.empty(),
                groupDetail);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final PanelView.Toggle row = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0);

        assertNotNull(row.inlineLabel());
        assertFalse(row.label().contains("物体的移动"), row.label());
        assertTrue(row.label().contains("图形网格"), row.label());
        assertTrue(row.label().contains("joins"), row.label());
        assertTrue(row.label().contains("PSD剪切蒙版导入测试"), row.label());
    }

    @Test
    void trustedSingleTargetRowsUseClosedIconsAndNeverInferMoveFromVertexPositions() {
        final HistorySnapshot snapshot = available(
                1,
                1,
                3,
                List.of(
                        richEntry(
                                0,
                                "art-entry",
                                "ART_MESH",
                                "<literal>&mesh",
                                HistoryChange.Operation.SET,
                                Optional.of("vertexPositions"),
                                HistoryEditContext.Kind.DEFAULT_FORM),
                        richEntry(
                                1,
                                "warp-entry",
                                "WARP_DEFORMER",
                                "Warp name",
                                HistoryChange.Operation.ADD,
                                Optional.empty(),
                                HistoryEditContext.Kind.OBJECT),
                        richEntry(
                                2,
                                "rotation-entry",
                                "ROTATION_DEFORMER",
                                "Rotation name",
                                HistoryChange.Operation.REMOVE,
                                Optional.empty(),
                                HistoryEditContext.Kind.OBJECT)),
                true,
                false);

        final List<PanelView.Toggle> rows = toggles(render(snapshot));

        assertEquals(3, rows.size());
        assertEquals(CubismIcon.ART_MESH, icon(rows.get(0)).icon().icon());
        assertEquals(CubismIcon.WARP_DEFORMER, icon(rows.get(1)).icon().icon());
        assertEquals(CubismIcon.ROTATION_DEFORMER, icon(rows.get(2)).icon().icon());
        assertEquals("1 Artmesh icon <literal>&mesh Vertex move", rows.get(0).label());
        assertEquals("2 Add Warp deformer icon Warp name", rows.get(1).label());
        assertEquals(
                "3 Remove Rotation deformer icon Rotation name", rows.get(2).label());
        assertFalse(rows.get(0).label().contains("Move"), rows.get(0).label());
        assertFalse(
                rows.get(0).label().contains("Set vertex positions"),
                rows.get(0).label());
        assertTrue(rows.get(0).label().contains("<literal>&mesh"), rows.get(0).label());
    }

    @ParameterizedTest(name = "{0} move renders subject with icon and headline")
    @CsvSource({
        "WARP_DEFORMER, A, '(2.0,-1.0)', 1 Warp deformer icon A Move",
        "ART_MESH, Face mesh, '(0.5,0.25)', 1 Artmesh icon Face mesh Move",
        "ROTATION_DEFORMER, Dial, '(10.0,20.0)', 1 Rotation deformer icon Dial Move"
    })
    void aProvenMoveRendersTheMovedSubjectWithNativeIconAndHeadline(
            String kind, String name, String delta, String expectedLabel) {
        final HistorySnapshot snapshot =
                available(1, 1, 1, List.of(moveEntry(0, "test-move", kind, name, delta)), true, false);

        final PanelView.Toggle row = toggles(render(snapshot)).get(0);

        assertEquals(CubismIcon.valueOf(kind), icon(row).icon().icon());
        assertEquals(expectedLabel, row.label());
        assertNotNull(row.inlineLabel());
    }

    @Test
    void aHoistedMoveGroupRendersTheSameRichRowAsALoneMove() {
        // A canvas drag decoded as a uniform single-subject group carries the hoisted subject and
        // one MOVE change at the root; the attached children must not hide the rich row.
        final HistoryTarget warp = new HistoryTarget("WARP_DEFORMER", Optional.of("warp-1"), Optional.of("A"));
        final HistoryChange move = new HistoryChange(
                HistoryChange.Operation.MOVE,
                Optional.of(0),
                Optional.of("translation"),
                Optional.empty(),
                Optional.of("(2.0,-1.0)"),
                context(HistoryEditContext.Kind.OBJECT));
        final HistoryEntryDetail keyformChild = new HistoryEntryDetail(
                "Move",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(warp),
                List.of(new HistoryChange(
                        HistoryChange.Operation.MOVE,
                        Optional.of(0),
                        Optional.of("translation"),
                        Optional.empty(),
                        Optional.of("(2.0,-1.0)"),
                        context(HistoryEditContext.Kind.DEFAULT_FORM))),
                Optional.empty(),
                Optional.empty());
        final HistoryEntryDetail hoisted = new HistoryEntryDetail(
                "Move",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(warp),
                List.of(move),
                Optional.of(new HistoryGroup(Optional.empty(), 2, List.of(keyformChild, keyformChild), false)),
                Optional.empty());
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Raw move",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("grouped-move")),
                Optional.empty(),
                hoisted);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final PanelView.Toggle row = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0);

        assertNotNull(row.inlineLabel(), "a hoisted single-subject group keeps the rich row");
        assertEquals("1 Warp deformer icon A Move", row.label());
    }

    @Test
    void rendersTypedRelationsFromCapturedEndpointsWithStableActions() {
        final HistoryTarget joinMesh =
                new HistoryTarget("ART_MESH", Optional.of("join-mesh"), Optional.of("Mesh join"));
        final HistoryTarget childA = new HistoryTarget("ART_MESH", Optional.of("mesh-a"), Optional.of("A frozen"));
        final HistoryTarget oldWarpB =
                new HistoryTarget("WARP_DEFORMER", Optional.of("warp-b"), Optional.of("B captured"));
        final HistoryTarget newRotationC =
                new HistoryTarget("ROTATION_DEFORMER", Optional.of("rotation-c"), Optional.of("C captured"));
        final HistoryTarget deformerChild =
                new HistoryTarget("WARP_DEFORMER", Optional.of("warp-child"), Optional.of("Deformer child"));
        final HistoryTarget oldRotation =
                new HistoryTarget("ROTATION_DEFORMER", Optional.of("rotation-old"), Optional.of("Old deformer"));
        final HistoryTarget partJoinChild =
                new HistoryTarget("ROTATION_DEFORMER", Optional.of("rotation-join"), Optional.of("Part join"));
        final HistoryTarget partParent =
                new HistoryTarget("PART", Optional.of("part-parent"), Optional.of("Part parent"));
        final HistoryTarget partChild =
                new HistoryTarget("ART_MESH", Optional.of("part-child"), Optional.of("Part child"));
        final HistoryTarget oldPart = new HistoryTarget("PART", Optional.of("part-old"), Optional.of("Old part"));

        final List<HistoryEntry> entries = List.of(
                relationEntry(
                        0,
                        "deformer-root-join",
                        List.of(joinMesh),
                        0,
                        HistoryRelationChange.Kind.DEFORMER_PARENT,
                        rootEndpoint(),
                        targetEndpoint(oldWarpB),
                        HistoryAction.DetailLevel.FULL,
                        HistoryOrigin.hostUnattributed(),
                        Optional.empty()),
                relationEntry(
                        1,
                        "deformer-reparent",
                        List.of(new HistoryTarget("PART", Optional.of("decoy"), Optional.of("Decoy")), childA),
                        1,
                        HistoryRelationChange.Kind.DEFORMER_PARENT,
                        targetEndpoint(oldWarpB),
                        targetEndpoint(newRotationC),
                        HistoryAction.DetailLevel.FULL,
                        HistoryOrigin.hostUnattributed(),
                        Optional.empty()),
                relationEntry(
                        2,
                        "deformer-detach",
                        List.of(deformerChild),
                        0,
                        HistoryRelationChange.Kind.DEFORMER_PARENT,
                        targetEndpoint(oldRotation),
                        rootEndpoint(),
                        HistoryAction.DetailLevel.FULL,
                        HistoryOrigin.hostUnattributed(),
                        Optional.empty()),
                relationEntry(
                        3,
                        "part-root-join",
                        List.of(partJoinChild),
                        0,
                        HistoryRelationChange.Kind.PART_MEMBERSHIP,
                        rootEndpoint(),
                        targetEndpoint(partParent),
                        HistoryAction.DetailLevel.FULL,
                        HistoryOrigin.hostUnattributed(),
                        Optional.empty()),
                relationEntry(
                        4,
                        "part-detach",
                        List.of(partChild),
                        0,
                        HistoryRelationChange.Kind.PART_MEMBERSHIP,
                        targetEndpoint(oldPart),
                        rootEndpoint(),
                        HistoryAction.DetailLevel.PARTIAL,
                        HistoryOrigin.turboism("relation.test", "detach"),
                        Optional.of("history.detail.relation-partial")),
                relationEntry(
                        5,
                        "unknown-relation",
                        List.of(joinMesh),
                        0,
                        HistoryRelationChange.Kind.PART_MEMBERSHIP,
                        unknownEndpoint(),
                        targetEndpoint(partParent),
                        HistoryAction.DetailLevel.PARTIAL,
                        HistoryOrigin.hostUnattributed(),
                        Optional.of("history.detail.relation-unknown")));
        final HistorySnapshot snapshot = available(6, 6, 6, entries, true, false);

        final List<PanelView.Toggle> rows = toggles(render(snapshot));

        assertEquals(6, rows.size());
        for (int index = 0; index < rows.size(); index++) {
            assertEquals(
                    HistoryPanelService.moveActionId(
                            entries.get(index).entryId().orElseThrow().value()),
                    rows.get(index).actionId());
        }
        assertEquals(List.of(CubismIcon.ART_MESH, CubismIcon.WARP_DEFORMER), icons(rows.get(0)));
        assertEquals(
                "1 Artmesh icon Mesh join is set under Warp deformer icon B captured as its child",
                rows.get(0).label());
        assertEquals(List.of(CubismIcon.ART_MESH, CubismIcon.ROTATION_DEFORMER), icons(rows.get(1)));
        assertEquals(
                "2 Artmesh icon A frozen is set under Rotation deformer icon C captured as its child",
                rows.get(1).label());
        assertTrue(rows.get(1).label().contains("A frozen"), rows.get(1).label());
        assertTrue(rows.get(1).label().contains("C captured"), rows.get(1).label());
        assertFalse(rows.get(1).label().contains("Decoy"), rows.get(1).label());
        assertEquals(
                "3 Warp deformer icon Deformer child leaves deformer Rotation deformer icon Old deformer",
                rows.get(2).label());
        assertEquals(List.of(CubismIcon.ROTATION_DEFORMER, CubismIcon.PART), icons(rows.get(3)));
        assertEquals(
                "4 Rotation deformer icon Part join joins Part icon Part parent",
                rows.get(3).label());
        assertEquals(
                "5 Artmesh icon Part child leaves part Part icon Old part · Turboism (relation.test) · partial detail",
                rows.get(4).label());
        assertEquals(List.of(CubismIcon.ART_MESH, CubismIcon.PART), icons(rows.get(4)));
        assertTrue(
                rows.get(4).label().contains("Turboism (relation.test)"),
                rows.get(4).label());
        assertTrue(rows.get(4).label().contains("partial detail"), rows.get(4).label());
        assertTrue(rows.get(5).inlineLabel() == null, "unknown relation falls back to text-only row");
        assertTrue(rows.get(5).label().contains("unknown-relation"), rows.get(5).label());
        assertTrue(rows.get(5).label().contains("partial detail"), rows.get(5).label());
        assertFalse(rows.get(5).label().contains("joins"), rows.get(5).label());
    }

    @Test
    void preservesMaxLengthSupplementaryDisplayNameInRichFallback() {
        final String maxName = "💠".repeat(128);
        assertEquals(256, maxName.length(), "supplementary name reaches the SDK UTF-16 bound");
        final HistorySnapshot snapshot = available(
                1,
                1,
                1,
                List.of(richEntry(
                        0,
                        "max-name",
                        "ART_MESH",
                        maxName,
                        HistoryChange.Operation.SET,
                        Optional.of("multiplyColor"),
                        HistoryEditContext.Kind.OBJECT)),
                true,
                false);

        final PanelView.Toggle row = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0);

        assertNotNull(row.inlineLabel());
        assertEquals(HistoryPanelService.moveActionId("max-name"), row.actionId());
        assertEquals("1 Set multiply color Artmesh icon " + maxName, row.label());
        assertTrue(row.label().endsWith(maxName), "the complete captured name remains literal");
        assertEquals(row.label(), row.inlineLabel().fallbackText());
        assertTrue(
                row.inlineLabel().runs().stream()
                        .filter(UiInlineLabel.TextRun.class::isInstance)
                        .map(UiInlineLabel.TextRun.class::cast)
                        .allMatch(run -> run.text().length() <= UiInlineLabel.MAX_RUN_TEXT_LENGTH),
                "every generated text run stays within the SDK bound");
        assertTrue(
                row.inlineLabel().fallbackText().length() <= UiInlineLabel.MAX_TOTAL_TEXT_LENGTH,
                "the complete fallback stays within the SDK bound");
    }

    @Test
    void unknownPropertyTargetOrContextKeepsConservativeTextFallback() {
        final HistorySnapshot snapshot = available(
                1,
                1,
                4,
                List.of(
                        partialEntry(
                                0,
                                "unknown-property",
                                "Unknown property",
                                "ART_MESH",
                                "Mesh",
                                "foregroundColor",
                                HistoryEditContext.Kind.DEFAULT_FORM),
                        partialEntry(
                                1,
                                "unknown-target",
                                "Unknown target",
                                "UNKNOWN_TARGET",
                                "Mystery",
                                "multiplyColor",
                                HistoryEditContext.Kind.DEFAULT_FORM),
                        partialEntry(
                                2,
                                "unknown-context",
                                "Unknown context",
                                "ART_MESH",
                                "Mesh",
                                "multiplyColor",
                                HistoryEditContext.Kind.UNKNOWN),
                        new HistoryEntry(
                                3,
                                "Host label",
                                true,
                                Optional.empty(),
                                Optional.of(new HistoryEntryId("label-only")),
                                Optional.empty(),
                                HistoryEntryDetail.labelOnly("Fallback summary"))),
                true,
                false);

        final List<PanelView.Toggle> rows = toggles(render(snapshot));

        assertEquals(4, rows.size());
        assertTrue(rows.stream().allMatch(row -> row.inlineLabel() == null), "fallback rows stay text-only");
        assertTrue(rows.get(0).label().contains("Unknown property"), rows.get(0).label());
        assertTrue(rows.get(1).label().contains("Unknown target"), rows.get(1).label());
        assertTrue(rows.get(2).label().contains("Unknown context"), rows.get(2).label());
        assertTrue(rows.get(3).label().contains("Host label"), rows.get(3).label());
        assertFalse(rows.stream().anyMatch(row -> row.label().contains("icon")), "no fallback row invents an icon");
    }

    @Test
    void rendersGroupAsOneRowWithoutGroupedChanges() {
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Edit model group",
                HistoryAction.DetailLevel.PARTIAL,
                HistoryOrigin.hostUnattributed(),
                List.of(),
                List.of(),
                Optional.of(new HistoryGroup(
                        Optional.empty(),
                        2,
                        List.of(
                                HistoryEntryDetail.labelOnly("Add parameter"),
                                HistoryEntryDetail.labelOnly("Rename part")),
                        false)),
                Optional.of("history.detail.group-partial"));
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Group",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("group-entry")),
                Optional.empty(),
                detail);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final PanelView view = render(snapshot);
        final String text = flatten(view);

        assertEquals(1, toggles(view).size(), "a group remains one navigable history row");
        assertTrue(toggles(view).get(0).inlineLabel() == null, "group rows stay conservative");
        assertTrue(toggles(view).get(0).label().startsWith("1 Edit model group"), text);
        assertTrue(text.contains("Edit model group"), text);
        assertFalse(text.contains("Add parameter"), "group children are not rendered");
        assertFalse(text.contains("Rename part"), "group children are not rendered");
        assertFalse(text.contains("grouped changes"), "group change affordance is removed");
        assertFalse(text.contains("unattributed"), "host-unattributed origin is hidden");
        assertFalse(hasCollapsibleSection(view), "group row has no expandable child section");
    }

    @Test
    void aggregatedGroupShowsOnlyItsSummaryNotTheFirstChangeOrRemainder() {
        final HistoryEntryDetail child = HistoryEntryDetail.fromAction(
                "Hidden child", action("ParamAngleX", "0", "10"), HistoryOrigin.hostUnattributed());
        final HistoryEntryDetail group = new HistoryEntryDetail(
                "Batch parameter adjustment",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.turboism("plugin.test", "batch"),
                child.targets(),
                List.of(child.changes().get(0), child.changes().get(0)),
                Optional.of(new HistoryGroup(Optional.of("tx"), 2, List.of(child, child), false)),
                Optional.empty());
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Raw group label",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("group")),
                Optional.of("tx"),
                group);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);
        final PanelView view = render(snapshot);
        final String label = toggles(view).get(0).label();
        assertTrue(label.startsWith("1 Batch parameter adjustment"), label);
        assertFalse(label.contains("Raw group label"), label);
        assertFalse(label.contains("ParamAngleX"), label);
        assertFalse(label.contains("changed from"), label);
        assertFalse(label.contains("+1"), label);
        assertTrue(label.contains("Turboism (plugin.test)"), label);
        assertEquals(1, toggles(view).size());
        assertFalse(hasCollapsibleSection(view));
        assertEquals(2, entry.detail().group().orElseThrow().children().size());
    }

    @Test
    void labelOnlyRowUsesHostLabelOnceRatherThanFallbackSummary() {
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Host operation",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("unknown")),
                Optional.empty(),
                HistoryEntryDetail.labelOnly("Fallback summary"));
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);
        final String label = toggles(service(new FakeHistory(snapshot), new RecordingUiHost())
                        .render(snapshot))
                .get(0)
                .label();
        assertTrue(label.startsWith("1 Host operation ·"), label);
        assertFalse(label.contains("Fallback summary"), label);
        assertEquals(label.indexOf("Host operation"), label.lastIndexOf("Host operation"));
    }

    @Test
    void keepsKnownTurboismOriginVisible() {
        final HistoryEntryDetail detail = HistoryEntryDetail.labelOnly(
                "Known edit",
                HistoryOrigin.turboism("dev.turboism.test", "test.operation"),
                "history.detail.label-only");
        final HistoryEntry entry = new HistoryEntry(
                0,
                "Known edit",
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId("known-origin")),
                Optional.empty(),
                detail);
        final HistorySnapshot snapshot = available(1, 1, 1, List.of(entry), true, false);

        final String text = flatten(render(snapshot));

        assertTrue(text.contains("Turboism (dev.turboism.test)"), text);
    }

    @Test
    void rendersUnavailableStateWithoutEntries() {
        final PanelView view = service(new FakeHistory(HistorySnapshot.unavailable()), new RecordingUiHost())
                .render(HistorySnapshot.unavailable());

        final String text = flatten(view);
        assertTrue(text.contains("History unavailable"), text);
        assertFalse(text.contains("[toggle:"), "unavailable state renders no checkboxes");
    }

    @Test
    void labelsEntriesWithoutStructuredDetail() {
        final HistorySnapshot snapshot =
                available(1, 0, 0, List.of(new HistoryEntry(0, "Native Action", true, Optional.empty())), true, false);

        final PanelView view = render(snapshot);
        final String text = flatten(view);
        assertTrue(text.contains("1 Native Action"), text);
        assertTrue(text.contains("no structured detail"), text);
        final PanelView.Toggle toggle = toggles(view).get(0);
        assertTrue(toggle.grayed(), "missing stable ID disables navigation");
        assertEquals("history.entry.unavailable.0", toggle.actionId());
    }

    @Test
    void enableRefreshesImmediatelyThenSchedulesOneFixedDelayPollWithCadenceTickAndIdempotentClose() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service =
                new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        final Registration registration = service.enable();

        // One immediate refresh, then exactly one scheduled fixed-delay task.
        assertEquals(1, uiHost.panels().size());
        assertEquals(1, tasks.requests().size(), "exactly one poll task scheduled");
        final FixedDelayTaskRequest request = tasks.requests().get(0);
        assertEquals(new TaskId("history-panel-poll"), request.id());
        assertEquals(PluginTaskKind.LOW_FREQUENCY_REFRESH, request.kind());
        assertEquals(PluginTaskPriority.LOW, request.priority());
        assertEquals(Duration.ofSeconds(1), request.initialDelay());
        assertEquals(Duration.ofSeconds(1), request.delay());
        assertEquals(1, tasks.handles().size());

        // A tick picks up a snapshot change.
        history.push(available(2, 1, 1, List.of(new HistoryEntry(0, "Write", true, Optional.empty())), true, false));
        tasks.tick();
        assertEquals(1, uiHost.panels().size());
        assertEquals(1, toggles(uiHost.panels().get(0).content()).size());

        // Close is idempotent and closes the accepted handle exactly once.
        registration.close();
        registration.close();
        assertEquals(1, tasks.closedHandles(), "handle closed exactly once");
        assertEquals(0, uiHost.panels().size());
    }

    @ParameterizedTest(name = "{0} scheduler keeps panel usable and logs warning")
    @ValueSource(strings = {"rejecting", "throwing"})
    void brokenSchedulerKeepsPanelUsableAndLogsBoundedWarning(String schedulerType) {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingLogger logger = new RecordingLogger();
        final PluginTaskScheduler scheduler =
                "rejecting".equals(schedulerType) ? new RecordingTaskScheduler(true) : new ThrowingTaskScheduler();
        final HistoryPanelService service =
                new HistoryPanelService(history, uiHost, scheduler, logger, new FakeLocalization());

        final Registration registration = service.enable();

        assertEquals(1, uiHost.panels().size(), "initial refresh keeps the panel usable");
        if ("rejecting".equals(schedulerType)) {
            assertEquals(0, ((RecordingTaskScheduler) scheduler).handles().size());
        }
        assertTrue(
                logger.warns().stream().anyMatch(message -> message.contains("poller")),
                "bounded warning logged: " + logger.warns());
        registration.close();
        assertEquals(0, uiHost.panels().size());
    }

    @Test
    void enableRegistersPanelAndPollingThenCloseStopsBoth() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service =
                new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        final Registration registration = service.enable();

        assertEquals(1, uiHost.panels().size());
        assertEquals(0, toggles(uiHost.panels().get(0).content()).size());

        // A snapshot change is picked up by the next poll tick.
        history.push(available(2, 1, 1, List.of(new HistoryEntry(0, "Write", true, Optional.empty())), true, false));
        tasks.tick();
        assertEquals(1, uiHost.panels().size());
        assertEquals(1, toggles(uiHost.panels().get(0).content()).size());

        registration.close();
        assertEquals(0, uiHost.panels().size());
    }

    @Test
    void unchangedSnapshotDoesNotRecontribute() {
        final FakeHistory history = new FakeHistory(available(1, 0, 0, List.of(), false, false));
        final RecordingUiHost uiHost = new RecordingUiHost();
        final RecordingTaskScheduler tasks = new RecordingTaskScheduler();
        final HistoryPanelService service =
                new HistoryPanelService(history, uiHost, tasks, new NullLogger(), new FakeLocalization());

        service.enable();
        tasks.tick();
        tasks.tick();

        assertEquals(1, uiHost.panels().size());
        assertEquals(1, uiHost.registrations().size(), "unchanged snapshots never re-contribute");
    }

    private static UiInlineLabel.IconRun icon(final PanelView.Toggle toggle) {
        return (UiInlineLabel.IconRun) toggle.inlineLabel().runs().stream()
                .filter(UiInlineLabel.IconRun.class::isInstance)
                .findFirst()
                .orElseThrow();
    }

    private static List<CubismIcon> icons(final PanelView.Toggle toggle) {
        return toggle.inlineLabel().runs().stream()
                .filter(UiInlineLabel.IconRun.class::isInstance)
                .map(UiInlineLabel.IconRun.class::cast)
                .map(run -> run.icon().icon())
                .toList();
    }

    private static HistoryEntry relationEntry(
            final int index,
            final String entryId,
            final List<HistoryTarget> targets,
            final int childIndex,
            final HistoryRelationChange.Kind kind,
            final HistoryRelationChange.Endpoint before,
            final HistoryRelationChange.Endpoint after,
            final HistoryAction.DetailLevel detailLevel,
            final HistoryOrigin origin,
            final Optional<String> degradation) {
        final HistoryRelationChange relation = new HistoryRelationChange(kind, before, after);
        final HistoryChange change = new HistoryChange(
                HistoryChange.Operation.SET,
                Optional.of(childIndex),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                context(HistoryEditContext.Kind.OBJECT),
                Optional.of(relation));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Relation " + entryId, detailLevel, origin, targets, List.of(change), Optional.empty(), degradation);
        return new HistoryEntry(
                index,
                "Native relation " + entryId,
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId(entryId)),
                Optional.empty(),
                detail);
    }

    private static HistoryRelationChange.Endpoint targetEndpoint(final HistoryTarget target) {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.TARGET, Optional.of(target));
    }

    private static HistoryRelationChange.Endpoint rootEndpoint() {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.ROOT, Optional.empty());
    }

    private static HistoryRelationChange.Endpoint unknownEndpoint() {
        return new HistoryRelationChange.Endpoint(HistoryRelationChange.State.UNKNOWN, Optional.empty());
    }

    private static HistoryEntry richEntry(
            final int index,
            final String entryId,
            final String targetType,
            final String displayName,
            final HistoryChange.Operation operation,
            final Optional<String> property,
            final HistoryEditContext.Kind contextKind) {
        final HistoryTarget target =
                new HistoryTarget(targetType, Optional.of(entryId + "-target"), Optional.of(displayName));
        final HistoryChange change = new HistoryChange(
                operation,
                Optional.of(0),
                property,
                operation == HistoryChange.Operation.SET ? Optional.of("before") : Optional.empty(),
                operation == HistoryChange.Operation.SET ? Optional.of("after") : Optional.empty(),
                context(contextKind));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Rich entry",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(target),
                List.of(change),
                Optional.empty(),
                Optional.empty());
        return new HistoryEntry(
                index,
                "Raw " + displayName,
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId(entryId)),
                Optional.empty(),
                detail);
    }

    private static HistoryEntry moveEntry(
            final int index,
            final String entryId,
            final String targetType,
            final String displayName,
            final String delta) {
        final HistoryTarget target =
                new HistoryTarget(targetType, Optional.of(entryId + "-target"), Optional.of(displayName));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                "Move",
                HistoryAction.DetailLevel.FULL,
                HistoryOrigin.hostUnattributed(),
                List.of(target),
                List.of(new HistoryChange(
                        HistoryChange.Operation.MOVE,
                        Optional.of(0),
                        Optional.of("translation"),
                        Optional.empty(),
                        Optional.of(delta),
                        context(HistoryEditContext.Kind.OBJECT))),
                Optional.empty(),
                Optional.empty());
        return new HistoryEntry(
                index,
                "Raw " + displayName,
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId(entryId)),
                Optional.empty(),
                detail);
    }

    private static HistoryEntry partialEntry(
            final int index,
            final String entryId,
            final String summary,
            final String targetType,
            final String displayName,
            final String property,
            final HistoryEditContext.Kind contextKind) {
        final HistoryTarget target =
                new HistoryTarget(targetType, Optional.of(entryId + "-target"), Optional.of(displayName));
        final HistoryEntryDetail detail = new HistoryEntryDetail(
                summary,
                HistoryAction.DetailLevel.PARTIAL,
                HistoryOrigin.hostUnattributed(),
                List.of(target),
                List.of(new HistoryChange(
                        HistoryChange.Operation.SET,
                        Optional.of(0),
                        Optional.of(property),
                        Optional.empty(),
                        Optional.of("after"),
                        context(contextKind))),
                Optional.empty(),
                Optional.of("history.detail.partial"));
        return new HistoryEntry(
                index,
                summary,
                true,
                Optional.empty(),
                Optional.of(new HistoryEntryId(entryId)),
                Optional.empty(),
                detail);
    }

    private static HistoryEditContext context(final HistoryEditContext.Kind kind) {
        return new HistoryEditContext(kind, Optional.empty(), List.of());
    }

    private static HistoryAction action(final String targetId, final String before, final String after) {
        return new HistoryAction(
                HistoryAction.Kind.SET_PARAMETER_VALUE,
                "PARAMETER",
                targetId,
                "value",
                Optional.of(before),
                Optional.of(after),
                HistoryAction.DetailLevel.FULL);
    }

    private static HistorySnapshot available(
            final long generation,
            final long revision,
            final int position,
            final List<HistoryEntry> entries,
            final boolean canUndo,
            final boolean canRedo) {
        return new HistorySnapshot(
                HistorySnapshot.Availability.AVAILABLE, generation, revision, position, entries, canUndo, canRedo);
    }

    private static String flatten(final PanelView view) {
        final StringBuilder builder = new StringBuilder();
        flatten(view, builder);
        return builder.toString();
    }

    private static void flatten(final PanelView view, final StringBuilder builder) {
        if (view instanceof PanelView.Column column) {
            column.children().forEach(child -> flatten(child, builder));
        } else if (view instanceof PanelView.Row row) {
            row.children().forEach(child -> flatten(child, builder));
        } else if (view instanceof PanelView.Scroll scroll) {
            flatten(scroll.child(), builder);
        } else if (view instanceof PanelView.Button button) {
            builder.append("[button:").append(button.label()).append("]\n");
        } else if (view instanceof PanelView.Toggle toggle) {
            builder.append("[toggle:")
                    .append(toggle.selected() ? "1" : "0")
                    .append(":")
                    .append(toggle.id())
                    .append(":")
                    .append(toggle.label())
                    .append("]\n");
        } else if (view instanceof PanelView.Text text) {
            builder.append(text.value()).append('\n');
        } else if (view instanceof PanelView.CollapsibleSection section) {
            builder.append(section.title()).append('\n');
            section.children().forEach(child -> flatten(child, builder));
        }
    }

    private static boolean hasButton(final PanelView view, final String id) {
        if (view instanceof PanelView.Button button) {
            return button.id().equals(id);
        }
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(child -> hasButton(child, id));
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(child -> hasButton(child, id));
        }
        if (view instanceof PanelView.Scroll scroll) {
            return hasButton(scroll.child(), id);
        }
        return false;
    }

    private static boolean hasCollapsibleSection(final PanelView view) {
        if (view instanceof PanelView.CollapsibleSection) return true;
        if (view instanceof PanelView.Scroll scroll) return hasCollapsibleSection(scroll.child());
        if (view instanceof PanelView.Column column) {
            return column.children().stream().anyMatch(HistoryPanelServiceTest::hasCollapsibleSection);
        }
        if (view instanceof PanelView.Row row) {
            return row.children().stream().anyMatch(HistoryPanelServiceTest::hasCollapsibleSection);
        }
        return false;
    }

    private static List<PanelView.Toggle> toggles(final PanelView view) {
        final List<PanelView.Toggle> result = new ArrayList<>();
        collectToggles(view, result);
        return result;
    }

    private static void collectToggles(final PanelView view, final List<PanelView.Toggle> result) {
        if (view instanceof PanelView.Toggle toggle) {
            result.add(toggle);
        } else if (view instanceof PanelView.Column column) {
            column.children().forEach(child -> collectToggles(child, result));
        } else if (view instanceof PanelView.Row row) {
            row.children().forEach(child -> collectToggles(child, result));
        } else if (view instanceof PanelView.Scroll scroll) {
            collectToggles(scroll.child(), result);
        }
    }
}
