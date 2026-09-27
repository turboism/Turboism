package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

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
    void aGroupedFormEditDecodesWhenTheLiveTargetIsProven() {
        // The host commits an ArtMesh-form edit as a group of SimpleUndo children, one per keyform,
        // and each child has no stored post state. The live-target read therefore has to reach
        // inside the group, or no grouped form edit can ever be classified.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form live = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));
        final Form otherBefore = form(source, "form-key-1", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form otherLive = form(source, "form-key-1", color(1.0F, 0.0F, 0.0F), color(0, 0, 0));

        final GroupEntry inner = new GroupEntry(List.of(
            new SimpleEntry(live, before, null),
            new SimpleEntry(otherLive, otherBefore, null)
        ));
        final GroupEntry outer = new GroupEntry(List.of(inner));

        final var detail = new NativeHistoryDecoderRegistry()
            .decode(resolver(), outer, "Edit", true)
            .detail().orElseThrow();

        final var children = detail.group().orElseThrow().children().get(0)
            .group().orElseThrow().children();
        assertEquals(2, children.size());
        for (var child : children) {
            assertEquals(
                HistoryAction.DetailLevel.FULL,
                child.detailLevel(),
                "each keyform's own edit must decode: " + child.degradationCode().orElse("")
            );
        }
        assertEquals("#ffffff", children.get(0).changes().get(0).before().orElseThrow());
        assertEquals("#66ccff", children.get(0).changes().get(0).after().orElseThrow());
        assertEquals("#ff0000", children.get(1).changes().get(0).after().orElseThrow());
    }

    @Test
    void anUnknownSiblingBeforeTheSimpleChildDoesNotWithholdTheLiveRead() {
        // r93 human-in-loop evidence: the host bundles an unmapped selection-undo child into the
        // same grouped vertex edit. That unknown child sits BEFORE the keyform child in child
        // order, so it cannot be a later writer of the same form; the live read stays allowed
        // and the row decodes FULL instead of degrading to a raw host label.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form live = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));
        final GroupEntry group = new GroupEntry(List.of(
            new Object(),
            new SimpleEntry(live, before, null)
        ));

        final var child = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Move selected vertices", true)
            .detail().orElseThrow()
            .group().orElseThrow().children().get(1);

        // The group stays PARTIAL because the unmapped sibling is label-only, but the keyform
        // child itself must decode: the earlier unknown sibling must not withhold its live read.
        assertEquals(HistoryAction.DetailLevel.FULL, child.detailLevel());
        assertEquals(1, child.changes().size());
    }

    @Test
    void anUnknownSiblingAfterTheSimpleChildStillWithholdsTheLiveRead() {
        // An unknown child positioned AFTER the keyform child could be a later writer of the
        // same form, so the live read stays withheld and the child degrades conservatively.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form live = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(live, before, null),
            new Object()
        ));

        final var child = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Move selected vertices", true)
            .detail().orElseThrow()
            .group().orElseThrow().children().get(0);

        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, child.detailLevel());
        assertEquals(
            "history.detail.post-state-unavailable",
            child.degradationCode().orElseThrow()
        );
    }

    @Test
    void differentKeyformCoordinatesDoNotBecomeAnObjectLevelFullChange() {
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Grid grid = new Grid(
            List.of(binding),
            Map.of(
                "form-minus-30", List.of(new KeyformOnGrid(new AccessKey(List.of(
                    new KeyOnParameter(binding, -30.0F)
                )))),
                "form-plus-30", List.of(new KeyformOnGrid(new AccessKey(List.of(
                    new KeyOnParameter(binding, 30.0F)
                ))))
            )
        );
        final Source source = new Source("ArtMesh1", "Face shadow", grid);
        final Form firstBefore = form(source, "form-minus-30", color(1, 1, 1), color(0, 0, 0));
        final Form firstAfter = form(source, "form-minus-30", color(1, 0, 0), color(0, 0, 0));
        final Form secondBefore = form(source, "form-plus-30", color(1, 1, 1), color(0, 0, 0));
        final Form secondAfter = form(source, "form-plus-30", color(1, 0, 0), color(0, 0, 0));
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(firstAfter, firstBefore, null),
            new SimpleEntry(secondAfter, secondBefore, null)
        ));

        final HistoryEntryDetail detail = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Color", true)
            .detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.detail.group-scope-vary", detail.degradationCode().orElseThrow());
        assertEquals("ArtMesh1", detail.targets().get(0).id().orElseThrow());
        assertEquals(HistoryEditContext.Kind.UNKNOWN, detail.changes().get(0).context().kind());
        assertEquals(List.of(), detail.changes().get(0).context().coordinates());
        assertEquals(2, detail.group().orElseThrow().children().size());
        for (final HistoryEntryDetail child : detail.group().orElseThrow().children()) {
            assertEquals(HistoryAction.DetailLevel.FULL, child.detailLevel());
            assertEquals(
                HistoryEditContext.Kind.KEYFORM,
                child.changes().get(0).context().kind()
            );
        }
    }

    @Test
    void defaultFormAndKeyformScopesDoNotBecomeAnObjectLevelFullChange() {
        final Source defaultSource = new Source(
            "ArtMesh1", "Face shadow", new Grid(List.of(), Map.of())
        );
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Guid keyGuid = new Guid("form-key");
        final AccessKey key = new AccessKey(List.of(new KeyOnParameter(binding, 30.0F)));
        final Grid keyformGrid = new Grid(
            List.of(binding),
            Map.of(keyGuid.value(), List.of(new KeyformOnGrid(key)))
        );
        final Source keyformSource = new Source("ArtMesh1", "Face shadow", keyformGrid);
        final Form defaultBefore = form(
            defaultSource, "form-default", color(1, 1, 1), color(0, 0, 0)
        );
        final Form defaultAfter = form(
            defaultSource, "form-default", color(1, 0, 0), color(0, 0, 0)
        );
        final Form keyformBefore = form(
            keyformSource, keyGuid.value(), color(1, 1, 1), color(0, 0, 0)
        );
        final Form keyformAfter = form(
            keyformSource, keyGuid.value(), color(1, 0, 0), color(0, 0, 0)
        );
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(defaultAfter, defaultBefore, null),
            new SimpleEntry(keyformAfter, keyformBefore, null)
        ));

        final HistoryEntryDetail detail = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Color", true)
            .detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.detail.group-scope-vary", detail.degradationCode().orElseThrow());
        assertEquals(HistoryEditContext.Kind.UNKNOWN, detail.changes().get(0).context().kind());
        final var children = detail.group().orElseThrow().children();
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM,
            children.get(0).changes().get(0).context().kind());
        assertEquals(HistoryEditContext.Kind.KEYFORM,
            children.get(1).changes().get(0).context().kind());
        assertEquals(HistoryAction.DetailLevel.FULL, children.get(0).detailLevel());
        assertEquals(HistoryAction.DetailLevel.FULL, children.get(1).detailLevel());
    }

    @Test
    void sameKeyformScopeAndValuesRemainFullWhenHoisted() {
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Guid guid = new Guid("form-key");
        final AccessKey key = new AccessKey(List.of(new KeyOnParameter(binding, 30.0F)));
        final Source source = new Source(
            "ArtMesh1", "Face shadow", new Grid(
                List.of(binding),
                Map.of(guid.value(), List.of(new KeyformOnGrid(key)))
            )
        );
        final Form firstBefore = form(source, guid.value(), color(1, 1, 1), color(0, 0, 0));
        final Form firstAfter = form(source, guid.value(), color(1, 0, 0), color(0, 0, 0));
        final Form secondBefore = form(source, guid.value(), color(1, 1, 1), color(0, 0, 0));
        final Form secondAfter = form(source, guid.value(), color(1, 0, 0), color(0, 0, 0));
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(firstAfter, firstBefore, null),
            new SimpleEntry(secondAfter, secondBefore, null)
        ));

        final HistoryEntryDetail detail = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Color", true)
            .detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals(java.util.Optional.empty(), detail.degradationCode());
        assertEquals(HistoryEditContext.Kind.KEYFORM, detail.changes().get(0).context().kind());
        assertEquals(List.of("ParamAngleX"), detail.changes().get(0).context().coordinates()
            .stream().map(coordinate -> coordinate.parameter().id().orElseThrow()).toList());
        assertEquals(2, detail.group().orElseThrow().children().size());
    }

    @Test
    void sameKeyformScopeWithDifferentValuesKeepsItsExistingPartialDegradation() {
        final Parameter angleX = new Parameter(new HostId("ParamAngleX"), "Angle X");
        final Binding binding = new Binding(angleX);
        final Guid guid = new Guid("form-key");
        final AccessKey key = new AccessKey(List.of(new KeyOnParameter(binding, 30.0F)));
        final Source source = new Source(
            "ArtMesh1", "Face shadow", new Grid(
                List.of(binding),
                Map.of(guid.value(), List.of(new KeyformOnGrid(key)))
            )
        );
        final Form firstBefore = form(source, guid.value(), color(1, 1, 1), color(0, 0, 0));
        final Form firstAfter = form(source, guid.value(), color(1, 0, 0), color(0, 0, 0));
        final Form secondBefore = form(source, guid.value(), color(1, 1, 1), color(0, 0, 0));
        final Form secondAfter = form(source, guid.value(), color(0, 0, 1), color(0, 0, 0));
        final GroupEntry group = new GroupEntry(List.of(
            new SimpleEntry(firstAfter, firstBefore, null),
            new SimpleEntry(secondAfter, secondBefore, null)
        ));

        final HistoryEntryDetail detail = new NativeHistoryDecoderRegistry()
            .decode(resolver(), group, "Color", true)
            .detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.detail.group-values-vary", detail.degradationCode().orElseThrow());
        assertEquals(HistoryEditContext.Kind.KEYFORM, detail.changes().get(0).context().kind());
        assertEquals(java.util.Optional.empty(), detail.changes().get(0).before());
        assertEquals(java.util.Optional.empty(), detail.changes().get(0).after());
        assertEquals(2, detail.group().orElseThrow().children().size());
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
    void liveTargetStandsInForTheMissingPostStateWhenTheCallerProvesIt() {
        // The host stores no post state at commit: SimpleUndo assigns targetData and undoData and
        // fills redoData in lazily inside undo(). When the caller has proved the entry is still the
        // undo manager's tip, the live target is that entry's own result and may be read.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form live = form(source, "form-default", color(0.4F, 0.8F, 1.0F), color(0, 0, 0));

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new SimpleEntry(live, before, null),
            "Edit Artmesh",
            true
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.DECODED, result.outcome(), result.diagnosticId());
        final var detail = result.detail().orElseThrow();
        assertEquals("#ffffff", detail.changes().get(0).before().orElseThrow());
        assertEquals("#66ccff", detail.changes().get(0).after().orElseThrow());
    }

    @Test
    void onlyTheLastWriterOfOneFormMayReadTheLiveTarget() {
        // A group may write the same form twice, and the live target then holds the last writer's
        // result. Reading it for the earlier child would report that child's post state as a later
        // one's, so the earlier child must fail closed.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form middle = form(source, "form-default", color(1.0F, 0.0F, 0.0F), color(0, 0, 0));
        final Form shared = form(source, "form-default", color(0.0F, 0.0F, 1.0F), color(0, 0, 0));

        final GroupEntry group = new GroupEntry(new ArrayList<>(List.of(
            new SimpleEntry(shared, before, null),
            new SimpleEntry(shared, middle, null)
        )));
        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), group, "Grouped edit", true
        ).detail().orElseThrow();
        final var children = detail.group().orElseThrow().children();

        assertEquals(2, children.size());
        assertEquals(
            "history.detail.post-state-unavailable",
            children.get(0).degradationCode().orElseThrow(),
            "the earlier writer of the same form must not read the live target"
        );
        assertFalse(
            children.get(0).changes().size() > 0,
            "no change may be derived for the earlier writer"
        );
        assertEquals(
            "#ff0000",
            children.get(1).changes().get(0).before().orElseThrow(),
            "the last writer of the form may read the live target"
        );
        assertEquals("#0000ff", children.get(1).changes().get(0).after().orElseThrow());
    }

    @Test
    void onlyTheLastNestedWriterOfOneFormMayReadTheLiveTarget() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1.0F, 1.0F, 1.0F), color(0, 0, 0));
        final Form middle = form(source, "form-default", color(1.0F, 0.0F, 0.0F), color(0, 0, 0));
        final Form shared = form(source, "form-default", color(0.0F, 0.0F, 1.0F), color(0, 0, 0));
        final GroupEntry first = new GroupEntry(List.of(new SimpleEntry(shared, before, null)));
        final GroupEntry last = new GroupEntry(List.of(new SimpleEntry(shared, middle, null)));
        final GroupEntry root = new GroupEntry(List.of(first, last));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), root, "Nested grouped edit", true
        ).detail().orElseThrow();
        final var nestedChildren = detail.group().orElseThrow().children();
        final var earlier = nestedChildren.get(0).group().orElseThrow().children().get(0);
        final var finalWriter = nestedChildren.get(1).group().orElseThrow().children().get(0);

        assertEquals(
            HistoryAction.DetailLevel.LABEL_ONLY,
            earlier.detailLevel(),
            "a nested earlier writer must not read the later sibling's live target"
        );
        assertEquals("history.detail.post-state-unavailable", earlier.degradationCode().orElseThrow());
        assertEquals(List.of(), earlier.changes());
        assertEquals("#ff0000", finalWriter.changes().get(0).before().orElseThrow());
        assertEquals("#0000ff", finalWriter.changes().get(0).after().orElseThrow());
    }

    @Test
    void nestedWritersOnDifferentFormsDoNotWithholdEachOther() {
        final Source firstSource = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Source secondSource = new Source("ArtMesh2", "Sleeve", new Grid(List.of(), Map.of()));
        final Form firstBefore = form(firstSource, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form firstLive = form(firstSource, "form-default", color(1, 0, 0), color(0, 0, 0));
        final Form secondBefore = form(secondSource, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form secondLive = form(secondSource, "form-default", color(0, 0, 1), color(0, 0, 0));
        final GroupEntry root = new GroupEntry(List.of(
            new GroupEntry(List.of(new SimpleEntry(firstLive, firstBefore, null))),
            new GroupEntry(List.of(new SimpleEntry(secondLive, secondBefore, null)))
        ));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), root, "Nested independent edits", true
        ).detail().orElseThrow();
        final var nestedChildren = detail.group().orElseThrow().children();
        final var first = nestedChildren.get(0).group().orElseThrow().children().get(0);
        final var second = nestedChildren.get(1).group().orElseThrow().children().get(0);

        assertEquals(HistoryAction.DetailLevel.FULL, first.detailLevel());
        assertEquals("#ff0000", first.changes().get(0).after().orElseThrow());
        assertEquals(HistoryAction.DetailLevel.FULL, second.detailLevel());
        assertEquals("#0000ff", second.changes().get(0).after().orElseThrow());
    }

    @Test
    void anUnknownNestedChildWithholdsKnownLiveWriters() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form shared = form(source, "form-default", color(0, 0, 1), color(0, 0, 0));
        final GroupEntry root = new GroupEntry(List.of(
            new GroupEntry(List.of(new SimpleEntry(shared, before, null))),
            new Object()
        ));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(), root, "Unknown nested edit", true
        ).detail().orElseThrow();
        final var earlier = detail.group().orElseThrow().children().get(0)
            .group().orElseThrow().children().get(0);

        assertWithheldLiveWriter(earlier);
    }

    @Test
    void writerScanBoundariesWithholdEarlyLiveWriters() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final Form middle = form(source, "form-default", color(1, 0, 0), color(0, 0, 0));
        final Form shared = form(source, "form-default", color(0, 0, 1), color(0, 0, 0));
        final SimpleEntry repeated = new SimpleEntry(shared, before, null);
        final GroupEntry repeatedRoot = new GroupEntry(List.of(repeated, repeated));

        Object deep = new SimpleEntry(shared, middle, null);
        for (int index = 0; index < NativeHistoryDecodeContext.MAX_DEPTH + 1; index++) {
            deep = new GroupEntry(List.of(deep));
        }
        final GroupEntry deepRoot = new GroupEntry(List.of(
            new SimpleEntry(shared, before, null),
            deep
        ));

        final List<Object> oversizedChildren = new ArrayList<>();
        oversizedChildren.add(new SimpleEntry(shared, before, null));
        for (int index = 0; index < NativeHistoryDecodeContext.MAX_NODES; index++) {
            final String guid = "form-" + index;
            final Form otherBefore = form(source, guid, color(1, 1, 1), color(0, 0, 0));
            final Form otherLive = form(source, guid, color(0, 1, 0), color(0, 0, 0));
            oversizedChildren.add(new SimpleEntry(otherLive, otherBefore, null));
        }
        final GroupEntry oversizedRoot = new GroupEntry(oversizedChildren);

        for (final GroupEntry root : List.of(repeatedRoot, deepRoot, oversizedRoot)) {
            final var detail = new NativeHistoryDecoderRegistry().decode(
                resolver(), root, "Bounded writer scan", true
            ).detail().orElseThrow();
            assertWithheldLiveWriter(detail.group().orElseThrow().children().get(0));
        }
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
        final Form before = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0), QUAD);
        final Form after = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0.2F, 0.4F, 0.6F), QUAD);

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
        final Form before = new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0), QUAD);
        final Form after = new Form(source, guid, 0.5F, 0, color(1, 1, 1), color(0, 0, 0), QUAD);

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
        final Form firstBefore = new Form(source, new Guid("form-a"), 1.0F, 0, color(1, 1, 1), color(0, 0, 0), QUAD);
        final Form firstAfter = new Form(source, new Guid("form-a"), 0.5F, 10, color(1, 1, 1), color(0, 0, 0), QUAD);
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
            before.add(new Form(source, guid, 1.0F, 0, color(1, 1, 1), color(0, 0, 0), QUAD));
            after.add(new Form(source, guid, 0.5F, 1, color(0.5F, 0.5F, 0.5F), color(0.25F, 0.25F, 0.25F), QUAD));
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

    @Test
    void aGeometryOnlyEditDecodesToABoundedVertexPositionsChange() {
        // This is the native canvas-edit shape: every scalar channel is unchanged and only the
        // vertex array differs. The admitted catalog row carries counts, never coordinates, so the
        // entry gains an exact target and form context without claiming a move or deform verdict.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final float[] moved = {2.0F, 0.0F, 3.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F};
        final Form after = new Form(source, new Guid("form-default"), 1.0F, 0,
            color(1, 1, 1), color(0, 0, 0), moved);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertEquals("ArtMesh1", detail.targets().get(0).id().orElseThrow());
        assertEquals(1, detail.changes().size());
        final var change = detail.changes().get(0);
        assertEquals("vertexPositions", change.property().orElseThrow());
        assertEquals("points=4", change.before().orElseThrow());
        assertEquals("points=4;changed=2", change.after().orElseThrow());
        assertEquals(HistoryEditContext.Kind.DEFAULT_FORM, change.context().kind());
    }

    @Test
    void aLiveTargetVertexDragDecodesLikeAnyOtherPostState() {
        // 移动选定的顶点 arrives as SimpleUndo(CArtMeshForm) with no stored redo data, so the
        // only post state is the live target at the proven tip.
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final float[] dragged = {0.0F, 0.0F, 1.5F, 0.5F, 1.0F, 1.0F, 0.0F, 1.0F};
        final Form live = new Form(source, new Guid("form-default"), 1.0F, 0,
            color(1, 1, 1), color(0, 0, 0), dragged);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new SimpleEntry(live, before, null),
            "Edit Artmesh",
            true
        ).detail().orElseThrow();

        final var change = detail.changes().get(0);
        assertEquals("vertexPositions", change.property().orElseThrow());
        assertEquals("points=4;changed=1", change.after().orElseThrow());
    }

    @Test
    void addedOrRemovedPointsCountAsChangedPoints() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = form(source, "form-default", color(1, 1, 1), color(0, 0, 0));
        final float[] grown = {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F, 0.5F, 0.5F};
        final Form after = new Form(source, new Guid("form-default"), 1.0F, 0,
            color(1, 1, 1), color(0, 0, 0), grown);

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        final var change = detail.changes().get(0);
        assertEquals("vertexPositions", change.property().orElseThrow());
        assertEquals("points=4", change.before().orElseThrow());
        assertEquals("points=5;changed=1", change.after().orElseThrow());
    }

    @Test
    void unreadablePositionsDegradeWithoutDroppingScalarChanges() {
        final Source source = new Source("ArtMesh1", "Face shadow", new Grid(List.of(), Map.of()));
        final Form before = new Form(source, new Guid("form-default"), 1.0F, 0,
            color(1, 1, 1), color(0, 0, 0), null);
        final Form after = form(source, "form-default", color(1, 0, 0), color(0, 0, 0));

        final var detail = new NativeHistoryDecoderRegistry().decode(
            resolver(),
            new ListEntry(List.of(before), List.of(after)),
            "Edit Artmesh"
        ).detail().orElseThrow();

        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("history.value-codec-unavailable", detail.degradationCode().orElseThrow());
        assertEquals("multiplyColor", detail.changes().get(0).property().orElseThrow());
        assertEquals(1, detail.changes().size());
    }

    private static void assertWithheldLiveWriter(final HistoryEntryDetail detail) {
        assertEquals(HistoryAction.DetailLevel.LABEL_ONLY, detail.detailLevel());
        assertEquals("history.detail.post-state-unavailable", detail.degradationCode().orElseThrow());
        assertEquals(List.of(), detail.changes());
    }

    private static final float[] QUAD = {0.0F, 0.0F, 1.0F, 0.0F, 1.0F, 1.0F, 0.0F, 1.0F};

    private static Form form(
        final Source source,
        final String guid,
        final Color multiply,
        final Color screen
    ) {
        return new Form(source, new Guid(guid), 1.0F, 0, multiply, screen, QUAD);
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
        selectors.add(method("cubism.editor-model.art-mesh-form.positions", Form.class, "positions", "()[F"));
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

    record Form(Source source, Guid guid, float opacity, int drawOrder, Color multiply, Color screen,
                float[] positions) { }
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
