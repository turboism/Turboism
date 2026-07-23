package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ParameterType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EditorBackedCubismModelAccessTest {

    @Test
    void joinsAuthoringMetadataToTheActiveModelGenerationAndFailsClosedOnReplacement() {
        Fixture host = new Fixture("model-a", 12.0F);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.install(host);

        var model = access.active();
        var parameter = model.parameters().find(new ParameterId("ParamAngleX"));

        assertEquals("model-a", model.id().value());
        assertEquals(List.of("ParamAngleX"), model.parameters().all().stream()
            .map(value -> value.id().value()).toList());
        assertEquals(12.0F, parameter.getValue());
        assertEquals(-30.0F, parameter.getMinimumValue());
        assertEquals(30.0F, parameter.getMaximumValue());
        assertEquals(0.0F, parameter.getDefaultValue());
        assertEquals(Optional.of("Angle X"), parameter.name());
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().findByName("Angle X").stream()
                .map(value -> value.id().value()).toList()
        );
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().search("angle").stream()
                .map(value -> value.id().value()).toList()
        );
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(true), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(
            List.of("ParamAngleX"),
            model.parameters().filter(value ->
                value.isBlendShape()
                    && value.repeat().orElse(false)
                    && value.combined().orElse(false)
            ).stream().map(value -> value.id().value()).toList()
        );

        host.source.currentInstance.parameterSet.parameters.get(0).source.name = null;
        assertEquals(Optional.empty(), parameter.name());
        assertEquals(List.of(), model.parameters().findByName("Angle X"));

        host = new Fixture("model-b", -5.0F);
        Host.install(host);
        assertThrows(IllegalStateException.class, parameter::getValue);
        assertEquals("model-b", access.active().id().value());
        assertThrows(NoSuchElementException.class, () ->
            access.active().parameters().find(new ParameterId("Missing"))
        );
    }

    @Test
    void exposesTheParameterGroupHierarchyInStableEditorOrder() {
        Fixture host = new Fixture("model-a", 12.0F);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.install(host);

        var groups = access.active().parameterGroups();
        var root = groups.root();
        var face = groups.find(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace"));

        assertEquals(Optional.of("Root"), root.name());
        assertEquals(Optional.empty(), root.parentId());
        assertEquals(
            List.of(new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace")),
            root.childGroupIds()
        );
        assertEquals(List.of(), root.parameterIds());
        assertEquals(Optional.of("Face"), face.name());
        assertEquals(Optional.of(root.id()), face.parentId());
        assertEquals(List.of(), face.childGroupIds());
        assertEquals(List.of(new ParameterId("ParamAngleX")), face.parameterIds());
        assertEquals(List.of(root.id(), face.id()), groups.all().stream()
            .map(dev.turboism.sdk.cubism.model.ParameterGroup::id)
            .toList());

        Host.install(new Fixture("model-b", -5.0F));
        assertThrows(IllegalStateException.class, root::name);
        assertThrows(IllegalStateException.class, groups::all);
    }

    @Test
    void detachedParameterGroupReferencesFailClosedWithinTheSameModelGeneration() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        var face = access.active().parameterGroups().find(
            new dev.turboism.sdk.cubism.id.ParameterGroupId("GroupFace")
        );

        host.source.rootGroup.children.clear();

        assertThrows(IllegalStateException.class, face::name);
    }

    @Test
    void parameterGroupsRequireTheirSeparateVerifiedCapability() {
        Fixture host = new Fixture("model-a", 12.0F);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolverWithoutParameterGroups(), "session-a"
        );

        assertThrows(
            UnsupportedOperationException.class,
            () -> access.active().parameterGroups().root()
        );
    }

    @Test
    void malformedParameterGroupTreesFailClosed() {
        Fixture host = new Fixture("model-a", 12.0F);
        ParameterGroup face = (ParameterGroup) host.source.rootGroup.children.get(0);
        face.children.add(host.source.rootGroup);
        Host.install(host);
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );

        assertThrows(IllegalStateException.class, () -> access.active().parameterGroups().all());
    }

    @Test
    void nonModelingDocumentsAndMissingActiveInstancesRemainUnavailable() {
        EditorBackedCubismModelAccess access = new EditorBackedCubismModelAccess(
            resolver(), "session-a"
        );
        Host.currentDocument = null;
        assertTrue(assertThrows(IllegalStateException.class, access::active)
            .getMessage().contains("modeling document"));

        Fixture fixture = new Fixture("model-a", 0.0F);
        fixture.source.currentInstance = null;
        Host.install(fixture);
        assertTrue(assertThrows(IllegalStateException.class, access::active)
            .getMessage().contains("active model"));
    }

    private static VerifiedMemberResolver resolver() {
        String host = internal(Host.class);
        String document = internal(Document.class);
        String source = internal(ModelSource.class);
        String model = internal(Model.class);
        String set = internal(ParameterSet.class);
        String parameter = internal(Parameter.class);
        String metadata = internal(ParameterSource.class);
        String group = internal(ParameterGroup.class);
        String id = internal(Id.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of(
                "cubism.editor-model.read",
                dev.turboism.mapping.verification.EditorParameterGroupsReadSelectorContract.CAPABILITY_ID
            ),
            List.of(
                StaticSelector.classSelector("cubism.editor-model.app-controller.class", host),
                StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", host, "instance", desc(Host.class), StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC),
                method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", desc(Document.class)),
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", document),
                method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", desc(ModelSource.class)),
                method("cubism.editor-model.modeling-document.last-active-view", Document.class, "lastActiveView", "()Ljava/lang/Object;"),
                StaticSelector.classSelector("cubism.editor-model.modeling-view.class", internal(Object.class)),
                method("cubism.editor-model.modeling-view.model", Object.class, "toString", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.model-source.class", source),
                method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", desc(Id.class)),
                method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", desc(Model.class)),
                method("cubism.editor-model.model-source.all-parameters", ModelSource.class, "allParameters", "()Ljava/util/List;"),
                method("cubism.editor-model.model-source.root-parameter-group", ModelSource.class, "rootParameterGroup", desc(ParameterGroup.class)),
                StaticSelector.classSelector("cubism.editor-model.model.class", model),
                method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", desc(ParameterSet.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", set),
                method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.parameter.class", parameter),
                method("cubism.editor-model.parameter.id", Parameter.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter.value", Parameter.class, "value", "()F"),
                method("cubism.editor-model.parameter.source", Parameter.class, "source", desc(ParameterSource.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-source.class", metadata),
                method("cubism.editor-model.parameter-source.minimum", ParameterSource.class, "minimum", "()F"),
                method("cubism.editor-model.parameter-source.maximum", ParameterSource.class, "maximum", "()F"),
                method("cubism.editor-model.parameter-source.default", ParameterSource.class, "defaultValue", "()F"),
                method("cubism.editor-model.parameter-source.name", ParameterSource.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-source.repeat", ParameterSource.class, "repeat", "()Z"),
                method("cubism.editor-model.parameter-source.morph-target", ParameterSource.class, "morphTarget", "()Z"),
                method("cubism.editor-model.parameter-source.combined", ParameterSource.class, "combined", "()Z"),
                method("cubism.editor-model.parameter-source.id", ParameterSource.class, "id", desc(Id.class)),
                StaticSelector.classSelector("cubism.editor-model.parameter-group.class", group),
                method("cubism.editor-model.parameter-group.id", ParameterGroup.class, "id", desc(Id.class)),
                method("cubism.editor-model.parameter-group.name", ParameterGroup.class, "name", "()Ljava/lang/String;"),
                method("cubism.editor-model.parameter-group.parent", ParameterGroup.class, "parent", desc(ParameterGroup.class)),
                method("cubism.editor-model.parameter-group.children", ParameterGroup.class, "children", "()Ljava/util/List;"),
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;")
            ),
            Host.class.getClassLoader()
        );
    }

    private static VerifiedMemberResolver resolverWithoutParameterGroups() {
        final VerifiedMemberResolver full = resolver();
        final java.util.Set<String> groupAliases =
            dev.turboism.mapping.verification.EditorParameterGroupsReadSelectorContract.REQUIRED_ALIASES;
        final java.util.List<StaticSelector> selectors = new java.util.ArrayList<>();
        for (String alias : java.util.List.of(
            "cubism.editor-model.app-controller.class",
            "cubism.editor-model.app-controller.instance",
            "cubism.editor-model.app-controller.current-document",
            "cubism.editor-model.modeling-document.class",
            "cubism.editor-model.modeling-document.model-source",
            "cubism.editor-model.modeling-document.last-active-view",
            "cubism.editor-model.modeling-view.class",
            "cubism.editor-model.modeling-view.model",
            "cubism.editor-model.model-source.class",
            "cubism.editor-model.model-source.guid",
            "cubism.editor-model.model-source.current-instance",
            "cubism.editor-model.model-source.all-parameters",
            "cubism.editor-model.model.class",
            "cubism.editor-model.model.parameter-set",
            "cubism.editor-model.parameter-set.class",
            "cubism.editor-model.parameter-set.parameters",
            "cubism.editor-model.parameter.class",
            "cubism.editor-model.parameter.id",
            "cubism.editor-model.parameter.value",
            "cubism.editor-model.parameter.source",
            "cubism.editor-model.parameter-source.class",
            "cubism.editor-model.parameter-source.minimum",
            "cubism.editor-model.parameter-source.maximum",
            "cubism.editor-model.parameter-source.default",
            "cubism.editor-model.parameter-source.name",
            "cubism.editor-model.parameter-source.repeat",
            "cubism.editor-model.parameter-source.morph-target",
            "cubism.editor-model.parameter-source.combined",
            "cubism.editor-model.parameter-source.id",
            "cubism.editor-model.id.class",
            "cubism.editor-model.id.value",
            "cubism.editor-model.guid.class",
            "cubism.editor-model.guid.value"
        )) {
            if (!groupAliases.contains(alias)) selectors.add(full.verifiedSelector(alias));
        }
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readwrite",
            java.util.Set.of("cubism.editor-model.read"),
            selectors,
            Host.class.getClassLoader()
        );
    }

    private static StaticSelector method(String alias, Class<?> owner, String name, String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }
    private static String internal(Class<?> type) { return type.getName().replace('.', '/'); }
    private static String desc(Class<?> type) { return "()L" + internal(type) + ";"; }

    public static final class Host {
        static final Host INSTANCE = new Host();
        static Object currentDocument;
        public static Host instance() { return INSTANCE; }
        public Document currentDocument() { return (Document) currentDocument; }
        static void install(Fixture fixture) { currentDocument = fixture.document; }
    }
    public static final class Document {
        final ModelSource source;
        Document(ModelSource source) { this.source = source; }
        public ModelSource modelSource() { return source; }
        public Object lastActiveView() { return null; }
    }
    public static final class ModelSource {
        final Id guid;
        final ParameterGroup rootGroup;
        Model currentInstance;
        ModelSource(String id, Model model) {
            guid = new Id(id);
            currentInstance = model;
            rootGroup = new ParameterGroup("GroupRoot", "Root", null);
            ParameterGroup face = new ParameterGroup("GroupFace", "Face", rootGroup);
            rootGroup.children.add(face);
            face.children.add(model.parameterSet.parameters.get(0).source());
        }
        public Id guid() { return guid; }
        public Model currentInstance() { return currentInstance; }
        public List<ParameterSource> allParameters() { return currentInstance.parameterSet.parameters.stream().map(Parameter::source).toList(); }
        public ParameterGroup rootParameterGroup() { return rootGroup; }
    }
    public static final class Model {
        final ParameterSet parameterSet;
        ModelSource source;
        Model(ParameterSet parameterSet) { this.parameterSet = parameterSet; }
        public ModelSource source() { return source; }
        public ParameterSet parameterSet() { return parameterSet; }
    }
    public static final class ParameterSet {
        final List<Parameter> parameters;
        ParameterSet(List<Parameter> parameters) { this.parameters = parameters; }
        public List<Parameter> parameters() { return parameters; }
    }
    public static final class Parameter {
        final Id id; final ParameterSource source; float value;
        Parameter(String id, float value) { this.id = new Id(id); this.source = new ParameterSource(); this.value = value; }
        public Id id() { return id; }
        public float value() { return value; }
        public ParameterSource source() { return source; }
    }
    public static final class ParameterSource {
        final Id id = new Id("ParamAngleX");
        String name = "Angle X";
        public float minimum() { return -30.0F; }
        public float maximum() { return 30.0F; }
        public float defaultValue() { return 0.0F; }
        public Id id() { return id; }
        public String name() { return name; }
        public boolean repeat() { return true; }
        public boolean morphTarget() { return true; }
        public boolean combined() { return true; }
    }
    public static final class ParameterGroup {
        final Id id;
        final String name;
        final ParameterGroup parent;
        final java.util.ArrayList<Object> children = new java.util.ArrayList<>();
        ParameterGroup(String id, String name, ParameterGroup parent) {
            this.id = new Id(id);
            this.name = name;
            this.parent = parent;
        }
        public Id id() { return id; }
        public String name() { return name; }
        public ParameterGroup parent() { return parent; }
        public List<Object> children() { return children; }
    }
    public record Id(String value) { public String value() { return value; } }
    static final class Fixture {
        final ModelSource source;
        final Document document;
        Fixture(String id, float value) {
            Model model = new Model(new ParameterSet(List.of(new Parameter("ParamAngleX", value))));
            source = new ModelSource(id, model);
            model.source = source;
            document = new Document(source);
        }
    }
}
