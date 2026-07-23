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
        String id = internal(Id.class);
        return TestVerifiedResolvers.create(
            "5.3.02",
            "adapter.editor-model.readonly",
            java.util.Set.of("cubism.editor-model.read"),
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
                StaticSelector.classSelector("cubism.editor-model.id.class", id),
                method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"),
                StaticSelector.classSelector("cubism.editor-model.guid.class", id),
                method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;")
            ),
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
        Model currentInstance;
        ModelSource(String id, Model model) { guid = new Id(id); currentInstance = model; }
        public Id guid() { return guid; }
        public Model currentInstance() { return currentInstance; }
        public List<ParameterSource> allParameters() { return currentInstance.parameterSet.parameters.stream().map(Parameter::source).toList(); }
    }
    public static final class Model {
        final ParameterSet parameterSet;
        Model(ParameterSet parameterSet) { this.parameterSet = parameterSet; }
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
        String name = "Angle X";
        public float minimum() { return -30.0F; }
        public float maximum() { return 30.0F; }
        public float defaultValue() { return 0.0F; }
        public String name() { return name; }
        public boolean repeat() { return true; }
        public boolean morphTarget() { return true; }
        public boolean combined() { return true; }
    }
    public record Id(String value) { public String value() { return value; } }
    static final class Fixture {
        final ModelSource source;
        final Document document;
        Fixture(String id, float value) {
            Model model = new Model(new ParameterSet(List.of(new Parameter("ParamAngleX", value))));
            source = new ModelSource(id, model);
            document = new Document(source);
        }
    }
}
