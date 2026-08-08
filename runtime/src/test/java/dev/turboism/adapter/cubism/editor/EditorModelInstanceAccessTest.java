package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorModelInstanceReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.InstanceRenderType;
import dev.turboism.sdk.cubism.model.ModelInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Model-instance reads: authorized projections and the fail-closed gate
 * (mirrors the Wave 1 host BLOCKED state where the capability was absent).
 */
class EditorModelInstanceAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void readsInstancesCurrentInstanceAndEditingFlag(final String version) {
        Host.instances.clear();
        Host.instances.add(new Instance(RenderType.NORMAL));
        Host.instances.add(new Instance(RenderType.ART_PATH));
        Host.current = Host.instances.get(1);

        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();

        final List<ModelInstance> instances = model.modelInstances();
        assertEquals(2, instances.size());
        assertEquals(InstanceRenderType.NORMAL, instances.get(0).renderType());
        assertEquals(InstanceRenderType.ART_PATH, instances.get(1).renderType());

        final var current = model.currentModelInstance();
        assertTrue(current.isPresent());
        assertEquals(instances.get(1), current.orElseThrow());
        assertTrue(model.modelEditing());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.3.02"})
    void mapsOnionSkinRenderTypeOn5302Only(final String version) {
        Host.instances.clear();
        Host.instances.add(new Instance(RenderType.ONION_SKIN_FOR_MODELING));
        Host.current = Host.instances.get(0);

        final var model = new EditorBackedCubismModelAccess(resolver(version, true), "session-a").active();
        assertEquals(
            InstanceRenderType.ONION_SKIN_FOR_MODELING,
            model.modelInstances().get(0).renderType()
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.0", "5.3.02"})
    void failsClosedWithoutExactCapabilityEvidence(final String version) {
        Host.instances.clear();
        Host.instances.add(new Instance(RenderType.NORMAL));
        Host.current = Host.instances.get(0);

        final var model = new EditorBackedCubismModelAccess(resolver(version, false), "session-a").active();
        assertThrows(UnsupportedOperationException.class, model::modelInstances);
        assertThrows(UnsupportedOperationException.class, model::currentModelInstance);
        assertThrows(UnsupportedOperationException.class, model::modelEditing);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean authorized) {
        final HashSet<String> capabilities = new HashSet<>();
        if (authorized) {
            capabilities.add(EditorModelInstanceReadSelectorContract.CAPABILITY_ID);
        } else {
            capabilities.add("fixture.unrelated");
        }
        return TestVerifiedResolvers.create(
            version,
            EditorModelInstanceReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            EditorModelInstanceAccessTest.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod(
            "cubism.editor-model.app-controller.instance", internal(Host.class), "instance",
            "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument",
            "()L" + internal(Document.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource",
            "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance",
            "()L" + internal(Instance.class) + ";"));
        values.add(method("cubism.editor-model.model-source.model-instances", ModelSource.class, "modelInstances",
            "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.model-source.model-editing", ModelSource.class, "modelEditing", "()Z"));
        values.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid",
            "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Instance.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-instance.class", internal(Instance.class)));
        values.add(method("cubism.editor-model.model-instance.render-type", Instance.class, "renderType",
            "()L" + internal(RenderType.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.render-type.class", internal(RenderType.class)));
        for (String alias : List.of(
            "cubism.editor-model.render-type.normal",
            "cubism.editor-model.render-type.psd-export",
            "cubism.editor-model.render-type.art-path",
            "cubism.editor-model.render-type.art-path-illegal",
            "cubism.editor-model.render-type.onion-skin-for-modeling"
        )) {
            values.add(StaticSelector.field(alias, internal(RenderType.class),
                alias.substring(alias.lastIndexOf('.') + 1).toUpperCase().replace('-', '_'),
                "L" + internal(RenderType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        }
        return List.copyOf(values);
    }

    private static StaticSelector method(
        final String alias, final Class<?> owner, final String name, final String descriptor
    ) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static final List<Instance> instances = new ArrayList<>();
        static Instance current;
        static final Document document = new Document();

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }
    }

    public static final class Document {
        private final ModelSource source = new ModelSource();

        public ModelSource modelSource() {
            return source;
        }
    }

    public static final class ModelSource {
        public List<Instance> modelInstances() {
            return Host.instances;
        }

        public Instance currentInstance() {
            return Host.current;
        }

        public boolean modelEditing() {
            return true;
        }

        public Id guid() {
            return new Id("model-a");
        }
    }

    public static final class Id {
        private final String value;

        Id(final String value) {
            this.value = value;
        }

        public String value() {
            return value;
        }
    }

    public static final class Instance {
        private final RenderType type;

        Instance(final RenderType type) {
            this.type = type;
        }

        public RenderType renderType() {
            return type;
        }
    }

    public enum RenderType {
        NORMAL, PSD_EXPORT, ART_PATH, ART_PATH_ILLEGAL, ONION_SKIN_FOR_MODELING
    }
}
