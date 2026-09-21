package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryEntryDetail;
import dev.turboism.sdk.cubism.history.HistoryRelationChange;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the native Part-membership decoder reports only what the admitted entry actually
 * establishes, and that a host Part move is combined into the complete relation.
 */
class PartMembershipDecoderTest {

    @Test
    void oneSidedEntryReportsTheKnownSideAndLeavesTheOtherUnknown() {
        final PartChildEntry entry = PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        );

        final NativeHistoryDecodeResult result = decode(entry);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals("ART_MESH", detail.targets().get(0).type());
        assertEquals("mesh-1", detail.targets().get(0).id().orElseThrow());
        assertEquals("BodyMesh", detail.targets().get(0).displayName().orElseThrow());
        final HistoryRelationChange relation = detail.changes().get(0).relation().orElseThrow();
        assertEquals(HistoryRelationChange.Kind.PART_MEMBERSHIP, relation.kind());
        assertEquals(
            HistoryRelationChange.State.UNKNOWN,
            relation.before().state()
        );
        assertEquals(HistoryRelationChange.State.TARGET, relation.after().state());
        assertEquals("PartB", relation.after().target().orElseThrow().id().orElseThrow());
        assertEquals("PART", relation.after().target().orElseThrow().type());
        assertTrue(detail.degradationCode().isPresent());
    }

    @Test
    void aHostPartMoveIsCombinedIntoTheCompleteRelation() {
        final GroupEntry group = new GroupEntry("Add Part");
        group.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        group.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        ));

        final NativeHistoryDecodeResult result = decode(group);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertTrue(detail.degradationCode().isEmpty());
        assertTrue(detail.group().isEmpty());
        assertEquals("Add Part", detail.summary());
        assertEquals("mesh-1", detail.targets().get(0).id().orElseThrow());
        final HistoryRelationChange relation = detail.changes().get(0).relation().orElseThrow();
        assertEquals("PartA", relation.before().target().orElseThrow().id().orElseThrow());
        assertEquals("PartB", relation.after().target().orElseThrow().id().orElseThrow());
    }

    @Test
    void theHostWrapsEachMembershipLeafInItsOwnGroupAndIsStillCombined() {
        // Exactly the native shape an operator Part drag produces on 5.3.02:
        // GroupUndo[ GroupUndo[leave], GroupUndo[join] ]. A single-child wrapper carries no
        // relation of its own, so the pair is only combinable if it is looked through.
        final GroupEntry outer = new GroupEntry("物体的移动");
        final GroupEntry leave = new GroupEntry("物体的移动");
        leave.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        final GroupEntry join = new GroupEntry("物体的移动");
        join.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        ));
        outer.children.add(leave);
        outer.children.add(join);

        final NativeHistoryDecodeResult result = decode(outer);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertTrue(detail.degradationCode().isEmpty());
        assertTrue(detail.group().isEmpty());
        assertEquals("mesh-1", detail.targets().get(0).id().orElseThrow());
        final HistoryRelationChange relation = detail.changes().get(0).relation().orElseThrow();
        assertEquals("PartA", relation.before().target().orElseThrow().id().orElseThrow());
        assertEquals("PartB", relation.after().target().orElseThrow().id().orElseThrow());
    }

    @Test
    void aWrapperWithMoreThanOneChildIsNotLookedThrough() {
        // The wrapper is only transparent while it is provably a wrapper: a nested group holding a
        // second edit describes more than the relation, so it stays a real group.
        final GroupEntry inner = new GroupEntry("物体的移动");
        inner.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        inner.children.add(new Entry("Rename part"));
        final GroupEntry join = new GroupEntry("物体的移动");
        join.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        ));
        final GroupEntry outer = new GroupEntry("物体的移动");
        outer.children.add(inner);
        outer.children.add(join);

        final NativeHistoryDecodeResult result = decode(outer);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertTrue(detail.group().isPresent());
        assertEquals(
            2,
            detail.group().orElseThrow().children().size(),
            "every observed child must remain projected"
        );
    }

    @Test
    void twoWrappedJoinsAreNotCombinedIntoAMove() {
        // Two joins with no leave are not a move; transparency must not manufacture one.
        final GroupEntry first = new GroupEntry("物体的移动");
        first.children.add(PartChildEntry.join(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        final GroupEntry second = new GroupEntry("物体的移动");
        second.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        ));
        final GroupEntry outer = new GroupEntry("物体的移动");
        outer.children.add(first);
        outer.children.add(second);

        final NativeHistoryDecodeResult result = decode(outer);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertTrue(detail.group().isPresent());
    }

    @Test
    void aGroupWithAnExtraEditIsNotCoalescedIntoOneRelation() {
        final GroupEntry group = new GroupEntry("Add Part");
        group.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        group.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        ));
        group.children.add(new Entry("Rename part"));

        final NativeHistoryDecodeResult result = decode(group);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(3, detail.group().orElseThrow().children().size());
    }

    @Test
    void reorderingInsideOnePartIsNotReportedAsAMove() {
        final GroupEntry group = new GroupEntry("Add Part");
        group.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));
        group.children.add(PartChildEntry.join(
            new PartSourceDouble("PartA"),
            new ArtMeshDouble("BodyMesh")
        ));

        final NativeHistoryDecodeResult result = decode(group);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.PARTIAL, detail.detailLevel());
        assertEquals(2, detail.group().orElseThrow().children().size());
    }

    @Test
    void aPartChildIsReadExactlyLikeAnyOtherAdmittedChild() {
        final PartChildEntry entry = PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new PartSourceDouble("BodyPart")
        );

        final NativeHistoryDecodeResult result = decode(entry);

        assertEquals(
            NativeHistoryDecodeResult.Outcome.DECODED,
            result.outcome(),
            result.diagnosticId()
        );
        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals("PART", detail.targets().get(0).type());
        assertEquals("BodyPart", detail.targets().get(0).id().orElseThrow());
    }

    @Test
    void aPartMovedIntoAnotherPartIsCombinedIntoTheCompleteRelation() {
        // The exact host shape of an operator drag when the dragged object is itself a Part.
        // getChild() is the common ACParameterControllableSource, so a Part child is a legal
        // value of the admitted selector and needs no new admission to read.
        final GroupEntry outer = new GroupEntry("\u7269\u4f53\u306e\u79fb\u52d5");
        final GroupEntry leave = new GroupEntry("\u7269\u4f53\u306e\u79fb\u52d5");
        leave.children.add(PartChildEntry.leave(
            new PartSourceDouble("PartA"),
            new PartSourceDouble("BodyPart")
        ));
        final GroupEntry join = new GroupEntry("\u7269\u4f53\u306e\u79fb\u52d5");
        join.children.add(PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new PartSourceDouble("BodyPart")
        ));
        outer.children.add(leave);
        outer.children.add(join);

        final NativeHistoryDecodeResult result = decode(outer);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals(HistoryAction.DetailLevel.FULL, detail.detailLevel());
        assertTrue(detail.degradationCode().isEmpty());
        assertTrue(detail.group().isEmpty());
        assertEquals("PART", detail.targets().get(0).type());
        assertEquals("BodyPart", detail.targets().get(0).id().orElseThrow());
        final HistoryRelationChange relation = detail.changes().get(0).relation().orElseThrow();
        assertEquals("PartA", relation.before().target().orElseThrow().id().orElseThrow());
        assertEquals("PartB", relation.after().target().orElseThrow().id().orElseThrow());
    }

    @Test
    void anEntryWhoseChildTypeIsNotAdmittedProducesNoRelation() {
        final PartChildEntry entry = PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new Controllable("Odd", "odd-1")
        );

        final NativeHistoryDecodeResult result = decode(entry);

        assertEquals(NativeHistoryDecodeResult.Outcome.FAILED, result.outcome());
        assertEquals(
            "history.detail.part-membership-target-unavailable",
            result.diagnosticId()
        );
        assertTrue(result.detail().isEmpty());
    }

    @Test
    void aChildWithoutALocalNameStillCarriesTheExactRelation() {
        final PartChildEntry entry = PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble(null)
        );

        final NativeHistoryDecodeResult result = decode(entry);

        final HistoryEntryDetail detail = result.detail().orElseThrow();
        assertEquals("mesh-1", detail.targets().get(0).id().orElseThrow());
        assertTrue(detail.targets().get(0).displayName().isEmpty());
        assertTrue(detail.changes().get(0).relation().isPresent());
    }

    @Test
    void theDecoderIsNotAdmittedWithoutItsOwnSelectorFamily() {
        final PartChildEntry entry = PartChildEntry.join(
            new PartSourceDouble("PartB"),
            new ArtMeshDouble("BodyMesh")
        );

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(groupSelectors()),
            entry,
            "Add Part"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.detail.class-unsupported", result.diagnosticId());
    }

    private static NativeHistoryDecodeResult decode(final Object entry) {
        return new NativeHistoryDecoderRegistry().decode(
            resolver(selectors()),
            entry,
            entry instanceof GroupEntry group ? group.presentationName() : "Add Part"
        );
    }

    private static VerifiedMemberResolver resolver(final List<StaticSelector> selectors) {
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.semantic-read"),
            selectors,
            PartMembershipDecoderTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final ArrayList<StaticSelector> all = new ArrayList<>(groupSelectors());
        all.add(StaticSelector.classSelector(
            "cubism.editor-history.semantic.part-membership.class",
            internal(PartChildEntry.class)
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.part",
            PartChildEntry.class,
            "getPart",
            desc(PartSourceDouble.class)
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.child",
            PartChildEntry.class,
            "getChild",
            desc(SourceBase.class)
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.index",
            PartChildEntry.class,
            "getInsertIndex",
            "()I"
        ));
        all.add(method(
            "cubism.editor-history.semantic.part-membership.is-add",
            PartChildEntry.class,
            "isAdd",
            "()Z"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.part-source.class",
            internal(PartSourceDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.part-source.id",
            PartSourceDouble.class,
            "id",
            desc(PartId.class)
        ));
        all.add(method(
            "cubism.editor-model.part-id.value",
            PartId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.art-mesh-source.class",
            internal(ArtMeshDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.warp-source.class",
            internal(WarpDouble.class)
        ));
        all.add(StaticSelector.classSelector(
            "cubism.editor-model.rotation-source.class",
            internal(RotationDouble.class)
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.id",
            Controllable.class,
            "id",
            desc(SourceId.class)
        ));
        all.add(method(
            "cubism.editor-model.parameter-controllable-source.local-name",
            SourceBase.class,
            "getLocalName",
            "()Ljava/lang/String;"
        ));
        all.add(method(
            "cubism.editor-model.id.value",
            SourceId.class,
            "value",
            "()Ljava/lang/String;"
        ));
        return List.copyOf(all);
    }

    private static List<StaticSelector> groupSelectors() {
        return List.of(
            StaticSelector.classSelector(
                "cubism.editor-history.semantic.group.class",
                internal(GroupEntry.class)
            ),
            method(
                "cubism.editor-history.semantic.group.edits",
                GroupEntry.class,
                "edits",
                "()Ljava/util/List;"
            ),
            method(
                "cubism.editor-history.semantic.group.count",
                GroupEntry.class,
                "count",
                "()I"
            ),
            method(
                "cubism.editor-history.entry.presentation-name",
                Entry.class,
                "presentationName",
                "()Ljava/lang/String;"
            )
        );
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias,
            internal(owner),
            name,
            descriptor,
            StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static String desc(final Class<?> type) {
        return "()L" + internal(type) + ";";
    }

    /** Test double for {@code ACParameterControllableSource}. */
    public static class SourceBase {
        private final String localName;

        SourceBase(final String localName) {
            this.localName = localName;
        }

        public String getLocalName() {
            return localName;
        }
    }

    /** Test double for any native parameter-controllable source. */
    public static class Controllable extends SourceBase {
        private final String id;

        Controllable(final String localName, final String id) {
            super(localName);
            this.id = id;
        }

        public SourceId id() {
            return new SourceId(id);
        }
    }

    public static final class ArtMeshDouble extends Controllable {
        ArtMeshDouble(final String localName) {
            super(localName, "mesh-1");
        }
    }

    public static final class WarpDouble extends Controllable {
        WarpDouble(final String localName) {
            super(localName, "warp-1");
        }
    }

    public static final class RotationDouble extends Controllable {
        RotationDouble(final String localName) {
            super(localName, "rotation-1");
        }
    }

    public static final class PartSourceDouble extends SourceBase {
        PartSourceDouble(final String localName) {
            super(localName);
        }

        public PartId id() {
            return new PartId(getLocalName());
        }
    }

    public record SourceId(String value) {
    }

    public record PartId(String value) {
    }

    /** Test double for {@code Editor_Part$Undo_AddOrRemovePartChild}. */
    public static final class PartChildEntry {
        private final PartSourceDouble part;
        private final SourceBase child;
        private final int insertIndex;
        private final boolean add;

        private PartChildEntry(
            final PartSourceDouble part,
            final SourceBase child,
            final boolean add
        ) {
            this.part = part;
            this.child = child;
            this.insertIndex = 0;
            this.add = add;
        }

        static PartChildEntry join(final PartSourceDouble part, final SourceBase child) {
            return new PartChildEntry(part, child, true);
        }

        static PartChildEntry leave(final PartSourceDouble part, final SourceBase child) {
            return new PartChildEntry(part, child, false);
        }

        public PartSourceDouble getPart() {
            return part;
        }

        public SourceBase getChild() {
            return child;
        }

        public int getInsertIndex() {
            return insertIndex;
        }

        public boolean isAdd() {
            return add;
        }
    }

    public static class Entry {
        private final String label;

        Entry(final String label) {
            this.label = label;
        }

        public String presentationName() {
            return label;
        }
    }

    public static final class GroupEntry extends Entry {
        private final List<Object> children = new ArrayList<>();

        GroupEntry(final String label) {
            super(label);
        }

        public List<Object> edits() {
            return children;
        }

        public int count() {
            return children.size();
        }
    }
}
