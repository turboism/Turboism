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
            desc(Controllable.class)
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
        private final Controllable child;
        private final int insertIndex;
        private final boolean add;

        private PartChildEntry(
            final PartSourceDouble part,
            final Controllable child,
            final boolean add
        ) {
            this.part = part;
            this.child = child;
            this.insertIndex = 0;
            this.add = add;
        }

        static PartChildEntry join(final PartSourceDouble part, final Controllable child) {
            return new PartChildEntry(part, child, true);
        }

        static PartChildEntry leave(final PartSourceDouble part, final Controllable child) {
            return new PartChildEntry(part, child, false);
        }

        public PartSourceDouble getPart() {
            return part;
        }

        public Controllable getChild() {
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
