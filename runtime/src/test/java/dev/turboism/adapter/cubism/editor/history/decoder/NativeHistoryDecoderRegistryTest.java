package dev.turboism.adapter.cubism.editor.history.decoder;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.history.HistoryAction;
import dev.turboism.sdk.cubism.history.HistoryChange;
import dev.turboism.sdk.cubism.history.HistoryEditContext;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeHistoryDecoderRegistryTest {

    @Test
    void exactGroupRoutingBoundsRepeatedReferencesAndKeepsUnknownChildrenLabelOnly() {
        final GroupEntry group = new GroupEntry("Group");
        group.children.add(new BaseEntry("Unknown child"));
        group.children.add(group);

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(groupSelectors()),
            group,
            group.presentationName()
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.DECODED, result.outcome());
        assertEquals(HistoryAction.DetailLevel.PARTIAL, result.detail().orElseThrow().detailLevel());
        assertEquals(2, result.detail().orElseThrow().group().orElseThrow().children().size());
        assertTrue(result.detail().orElseThrow().group().orElseThrow().truncated());
        assertEquals(
            HistoryAction.DetailLevel.LABEL_ONLY,
            result.detail().orElseThrow().group().orElseThrow().children().get(0).detailLevel()
        );
    }

    @Test
    void propertyDecoderNeverCallsUnknownValueToStringAndReportsMissingPostState() {
        final PropertyEntry property = new PropertyEntry(
            "opacity",
            new Object(),
            new ExplosiveValue(),
            null
        );

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(propertySelectors()),
            property,
            "Set opacity"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.semantic-operation-unmapped", result.diagnosticId());
        assertTrue(result.detail().isEmpty());
    }

    @Test
    void approvedAddRemoveSubtypeProjectsVerifiedDirectionAndStableTargetId() {
        final AddParameterEntry entry = new AddParameterEntry(
            new Object(),
            3,
            true,
            new Parameter(new HostId("ParamAngleX"))
        );

        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(addRemoveSelectors()),
            entry,
            "Add parameter"
        );

        assertEquals(HistoryAction.DetailLevel.FULL, result.detail().orElseThrow().detailLevel());
        assertEquals("ParamAngleX", result.detail().orElseThrow().targets().get(0).id().orElseThrow());
        assertEquals(
            HistoryChange.Operation.ADD,
            result.detail().orElseThrow().changes().get(0).operation()
        );
        assertTrue(result.detail().orElseThrow().changes().get(0).property().isEmpty());
        assertTrue(result.detail().orElseThrow().changes().get(0).after().isEmpty());
        assertEquals(
            HistoryEditContext.Kind.OBJECT,
            result.detail().orElseThrow().changes().get(0).context().kind()
        );
    }

    @Test
    void unsupportedExactClassDoesNotReceiveAHeuristicDecoder() {
        final NativeHistoryDecodeResult result = new NativeHistoryDecoderRegistry().decode(
            resolver(List.of(StaticSelector.classSelector("test.unknown", internal(BaseEntry.class)))),
            new BaseEntry("Unknown"),
            "Unknown"
        );

        assertEquals(NativeHistoryDecodeResult.Outcome.UNSUPPORTED, result.outcome());
        assertEquals("history.detail.class-unsupported", result.diagnosticId());
        assertTrue(result.detail().isEmpty());
    }

    private static VerifiedMemberResolver resolver(final List<StaticSelector> selectors) {
        final ArrayList<StaticSelector> all = new ArrayList<>(selectors);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            Set.of("cubism.editor-history.semantic-read"),
            all,
            NativeHistoryDecoderRegistryTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> groupSelectors() {
        return List.of(
            StaticSelector.classSelector(
                "cubism.editor-history.semantic.group.class",
                internal(GroupEntry.class)
            ),
            method("cubism.editor-history.semantic.group.edits", GroupEntry.class, "edits", "()Ljava/util/List;"),
            method("cubism.editor-history.semantic.group.count", GroupEntry.class, "count", "()I"),
            method("cubism.editor-history.entry.presentation-name", BaseEntry.class, "presentationName", "()Ljava/lang/String;")
        );
    }

    private static List<StaticSelector> propertySelectors() {
        return List.of(
            StaticSelector.classSelector(
                "cubism.editor-history.semantic.property.class",
                internal(PropertyEntry.class)
            ),
            method("cubism.editor-history.semantic.property.name", PropertyEntry.class, "name", "()Ljava/lang/String;"),
            method("cubism.editor-history.semantic.property.object", PropertyEntry.class, "object", "()Ljava/lang/Object;"),
            method("cubism.editor-history.semantic.property.previous", PropertyEntry.class, "previous", "()Ljava/lang/Object;"),
            method("cubism.editor-history.semantic.property.post", PropertyEntry.class, "post", "()Ljava/lang/Object;")
        );
    }

    private static List<StaticSelector> addRemoveSelectors() {
        return List.of(
            StaticSelector.classSelector(
                "cubism.editor-history.semantic.add-remove.class",
                internal(AddParameterEntry.class)
            ),
            method("cubism.editor-history.semantic.add-remove.owner", AddParameterEntry.class, "owner", "()Ljava/lang/Object;"),
            method("cubism.editor-history.semantic.add-remove.index", AddParameterEntry.class, "index", "()I"),
            method("cubism.editor-history.semantic.add-remove.is-add", AddParameterEntry.class, "isAdd", "()Z"),
            StaticSelector.classSelector(
                "cubism.editor-history.semantic.add-remove.parameter.class",
                internal(AddParameterEntry.class)
            ),
            method("cubism.editor-history.semantic.add-remove.parameter.item", AddParameterEntry.class, "item", desc(Parameter.class)),
            method("cubism.editor-model.parameter-source.id", Parameter.class, "id", desc(HostId.class)),
            method("cubism.editor-model.id.value", HostId.class, "value", "()Ljava/lang/String;")
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

    public static class BaseEntry {
        private final String label;
        BaseEntry(final String label) { this.label = label; }
        public String presentationName() { return label; }
    }

    public static final class GroupEntry extends BaseEntry {
        private final List<BaseEntry> children = new ArrayList<>();
        GroupEntry(final String label) { super(label); }
        public List<BaseEntry> edits() { return children; }
        public int count() { return children.size(); }
    }

    public static final class PropertyEntry {
        private final String name;
        private final Object object;
        private final Object previous;
        private final Object post;
        PropertyEntry(final String name, final Object object, final Object previous, final Object post) {
            this.name = name;
            this.object = object;
            this.previous = previous;
            this.post = post;
        }
        public String name() { return name; }
        public Object object() { return object; }
        public Object previous() { return previous; }
        public Object post() { return post; }
    }

    public static final class AddParameterEntry {
        private final Object owner;
        private final int index;
        private final boolean add;
        private final Parameter item;
        AddParameterEntry(final Object owner, final int index, final boolean add, final Parameter item) {
            this.owner = owner;
            this.index = index;
            this.add = add;
            this.item = item;
        }
        public Object owner() { return owner; }
        public int index() { return index; }
        public boolean isAdd() { return add; }
        public Parameter item() { return item; }
    }

    public record Parameter(HostId id) {
    }

    public record HostId(String value) {
    }

    private static final class ExplosiveValue {
        @Override
        public String toString() {
            throw new AssertionError("unknown native value toString must not be called");
        }
    }
}
