package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorPartNameSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.PartId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EditorPartNameAccessTest {

    @Test
    void readsLocalNameAndFallsBackToIdText() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        assertEquals("Clipping Part", part.name());
        fixture.partSource.localName = "";
        assertEquals("PartClip", part.name());
    }

    @Test
    void sameIdReplacementMakesNameReferenceStale() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(true), "session-a");
        final var part = access.active().parts().find(new PartId("PartClip"));

        fixture.replacePartWithSameId();

        assertThrows(IllegalStateException.class, part::name);
    }

    @Test
    void resolverWithoutNameCapabilityFailsClosedBeforeNameSelectorUse() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(false), "session-a");

        assertThrows(UnsupportedOperationException.class, () -> access.active().parts());
    }

    private static VerifiedMemberResolver resolver(final boolean includeNameCapability) {
        final List<StaticSelector> selectors = new ArrayList<>();
        selectors.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        selectors.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC
        ));
        selectors.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        selectors.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)));
        selectors.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)));
        selectors.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.guid.class", internal(Id.class)));
        selectors.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        selectors.add(method("cubism.editor-model.model-source.parts", ModelSource.class, "parts", "()Ljava/util/List;"));
        selectors.add(method("cubism.editor-model.model.parts", Model.class, "parts", "()Ljava/util/List;"));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part.class", internal(HostPart.class)));
        selectors.add(method("cubism.editor-model.part.source", HostPart.class, "source", desc(PartSource.class)));
        selectors.add(StaticSelector.classSelector("cubism.editor-model.part-source.class", internal(PartSource.class)));
        selectors.add(method("cubism.editor-model.part-source.id", PartSource.class, "id", desc(Id.class)));
        selectors.add(method("cubism.editor-model.part-id.value", Id.class, "value", "()Ljava/lang/String;"));
        if (includeNameCapability) {
            selectors.add(method("cubism.editor-model.part-source.local-name", PartSource.class, "localName", "()Ljava/lang/String;"));
        }
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            includeNameCapability
                ? java.util.Set.of(EditorPartNameSelectorContract.CAPABILITY_ID)
                : java.util.Set.of("cubism.editor-model.read"),
            selectors,
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) { return type.getName().replace('.', '/'); }
    private static String type(final Class<?> type) { return "L" + internal(type) + ";"; }
    private static String desc(final Class<?> type) { return "()" + type(type); }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return document; }
    }

    public static final class Document {
        final ModelSource source;
        Document(final ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
    }

    public static final class ModelSource {
        final Id guid = new Id("model-a");
        final List<PartSource> sources = new ArrayList<>();
        Model model;
        public Id guid() { return guid; }
        public Model currentInstance() { return model; }
        public List<PartSource> parts() { return sources; }
    }

    public static final class Model {
        final List<HostPart> parts = new ArrayList<>();
        public List<HostPart> parts() { return parts; }
    }

    public static final class HostPart {
        final PartSource source;
        HostPart(final PartSource source) { this.source = source; }
        public PartSource source() { return source; }
    }

    public static final class PartSource {
        final Id id;
        String localName;
        PartSource(final String id, final String localName) {
            this.id = new Id(id);
            this.localName = localName;
        }
        public Id id() { return id; }
        public String localName() { return localName; }
    }

    public static final class Id {
        final String value;
        Id(final String value) { this.value = value; }
        public String value() { return value; }
    }

    private static final class Fixture {
        final ModelSource source = new ModelSource();
        final PartSource partSource = new PartSource("PartClip", "Clipping Part");
        final Document document;
        Fixture() {
            source.sources.add(partSource);
            source.model = new Model();
            source.model.parts.add(new HostPart(partSource));
            document = new Document(source);
        }
        void replacePartWithSameId() {
            final PartSource replacement = new PartSource("PartClip", "Replacement");
            source.sources.clear();
            source.sources.add(replacement);
            source.model.parts.clear();
            source.model.parts.add(new HostPart(replacement));
        }
    }
}
