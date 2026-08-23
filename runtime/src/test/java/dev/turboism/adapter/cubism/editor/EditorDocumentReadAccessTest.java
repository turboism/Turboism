package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Read-only Editor document projections: auto-Yure evaluations, physics
 * settings documents, and animation file-content documents.
 */
class EditorDocumentReadAccessTest {

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsPhysicsSettingsDocumentProjection(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final PhysicsSettings settings = model.physicsSettings();
        assertEquals(0.0F, settings.gravityX());
        assertEquals(-1.0F, settings.gravityY());
        assertEquals(0.5F, settings.windX());
        assertEquals(0.0F, settings.windY());
        assertEquals(Integer.valueOf(60), settings.settingFps());
        final dev.turboism.sdk.cubism.model.PhysicsSettingsSource source = settings.sources().get(0);
        assertEquals("PhysicsA", source.id());
        assertEquals("Physics A", source.name());
        assertEquals(90.0F, source.totalAngle());
        assertEquals(2, source.inputCount());
        assertEquals(1, source.outputCount());
        assertEquals(8, source.vertexCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsAutoYureEvaluationsPerWarpDeformerAndParameter(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final AutoYure autoYure = model.autoYure();
        assertEquals(1, autoYure.bindings().size());
        final var binding = autoYure.bindings().get(0);
        assertEquals(new DeformerId("WarpFace"), binding.deformerId());
        assertEquals(new ParameterId("ParamAngleX"), binding.parameterId());
        assertEquals(10.0F, binding.config().left().scalePercentX());
        assertEquals(20.0F, binding.config().left().scalePercentY());
        assertEquals(1.5F, binding.config().left().expandScale());
        assertEquals(2.0, binding.config().right().decayLevel());
        assertEquals(30.0F, binding.config().right().scalePercentX());
        assertTrue(binding.config().syncLeftRight());
        assertEquals(dev.turboism.sdk.cubism.model.YureRootDirection.TOP, binding.config().rootDirection());
        assertTrue(binding.config().isFlip());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void readsAnimationFileContentDocuments(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final List<AnimationDocument> documents = model.animationDocuments();
        assertEquals(1, documents.size());
        final AnimationDocument animation = documents.get(0);
        assertEquals("Animation A", animation.animationName());
        assertEquals(2, animation.sceneCount());
        assertEquals(List.of("Scene 1", "Scene 2"), animation.sceneNames());
        assertEquals("Scene 2", animation.currentSceneName().orElseThrow());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void documentReadsFailClosedWithoutExactCapabilityEvidence(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(
            resolver(version, false), "session-a"
        );
        final var model = access.active();
        assertThrows(UnsupportedOperationException.class, model::physicsSettings);
        assertThrows(UnsupportedOperationException.class, model::autoYure);
        assertThrows(UnsupportedOperationException.class, model::animationDocuments);
    }

    private static VerifiedMemberResolver resolver(final String version) {
        return resolver(version, true);
    }

    private static VerifiedMemberResolver resolver(
        final String version,
        final boolean includeDocumentReads
    ) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        if (includeDocumentReads) {
            capabilities.add(EditorPhysicsReadSelectorContract.CAPABILITY_ID);
            capabilities.add(EditorAutoYureReadSelectorContract.CAPABILITY_ID);
            capabilities.add(EditorAnimationReadSelectorContract.CAPABILITY_ID);
        } else {
            capabilities.add(dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract.CAPABILITY_ID);
        }
        return TestVerifiedResolvers.create(
            version,
            EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod("cubism.editor-model.app-controller.instance", internal(Host.class), "instance", "()L" + internal(Host.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.app-controller.current-document", Host.class, "currentDocument", "()L" + internal(Document.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method("cubism.editor-model.modeling-document.model-source", Document.class, "modelSource", "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.modeling-document.file-content-docs", Document.class, "fileContentDocs", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.model-source.class", ModelSource.class, "sourceType", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.model-source.current-instance", ModelSource.class, "currentInstance", "()L" + internal(Model.class) + ";"));
        values.add(method("cubism.editor-model.model-source.all-deformers", ModelSource.class, "allDeformers", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.model-source.physics-settings-source-set", ModelSource.class, "physicsSettingsSourceSet", "()L" + internal(PhysicsSettingsSourceSet.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        values.add(method("cubism.editor-model.model.parameter-set", Model.class, "parameterSet", "()L" + internal(ParameterSet.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.parameter-set.class", internal(ParameterSet.class)));
        values.add(method("cubism.editor-model.parameter-set.parameters", ParameterSet.class, "parameters", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.parameter-controllable-source.id", ObjectSource.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.parameter-controllable-source.extensions", ObjectSource.class, "extensions", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(ParameterHolder.class)));
        values.add(method("cubism.editor-model.parameter.source", ParameterHolder.class, "source", "()L" + internal(ParameterSource.class) + ";"));
        values.add(method("cubism.editor-model.parameter.id", ParameterHolder.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.parameter-source.guid", ParameterSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.auto-yure-config-extension.class", internal(AutoYureConfigExtension.class)));
        values.add(method("cubism.editor-model.auto-yure-config-extension.param-to-config-map", AutoYureConfigExtension.class, "paramToConfigMap", "()Ljava/util/Map;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.auto-yure-config.class", internal(AutoYureConfig.class)));
        values.add(method("cubism.editor-model.auto-yure-config.left", AutoYureConfig.class, "left", "()L" + internal(YureDeformConfig.class) + ";"));
        values.add(method("cubism.editor-model.auto-yure-config.right", AutoYureConfig.class, "right", "()L" + internal(YureDeformConfig.class) + ";"));
        values.add(method("cubism.editor-model.auto-yure-config.sync-left-right", AutoYureConfig.class, "syncLeftRight", "()Z"));
        values.add(method("cubism.editor-model.auto-yure-config.root-direction", AutoYureConfig.class, "rootDirection", "()L" + internal(YureRootDirection.class) + ";"));
        values.add(method("cubism.editor-model.auto-yure-config.flip", AutoYureConfig.class, "isFlip", "()Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.auto-yure-config-root-direction.class", internal(YureRootDirection.class)));
        values.add(StaticSelector.field("cubism.editor-model.auto-yure-config-root-direction.top", internal(YureRootDirection.class), "TOP", "L" + internal(YureRootDirection.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.auto-yure-config-root-direction.right", internal(YureRootDirection.class), "RIGHT", "L" + internal(YureRootDirection.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.auto-yure-config-root-direction.bottom", internal(YureRootDirection.class), "BOTTOM", "L" + internal(YureRootDirection.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.auto-yure-config-root-direction.left", internal(YureRootDirection.class), "LEFT", "L" + internal(YureRootDirection.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.classSelector("cubism.editor-model.yure-deform-config.class", internal(YureDeformConfig.class)));
        values.add(method("cubism.editor-model.yure-deform-config.scale-percent-x", YureDeformConfig.class, "scalePercentX", "()F"));
        values.add(method("cubism.editor-model.yure-deform-config.scale-percent-y", YureDeformConfig.class, "scalePercentY", "()F"));
        values.add(method("cubism.editor-model.yure-deform-config.expand-scale", YureDeformConfig.class, "expandScale", "()F"));
        values.add(method("cubism.editor-model.yure-deform-config.decay-level", YureDeformConfig.class, "decayLevel", "()D"));
        values.add(StaticSelector.classSelector("cubism.editor-model.physics-settings-source-set.class", internal(PhysicsSettingsSourceSet.class)));
        values.add(method("cubism.editor-model.physics-settings-source-set.gravity", PhysicsSettingsSourceSet.class, "gravity", "()L" + internal(GVector2.class) + ";"));
        values.add(method("cubism.editor-model.physics-settings-source-set.wind", PhysicsSettingsSourceSet.class, "wind", "()L" + internal(GVector2.class) + ";"));
        values.add(method("cubism.editor-model.physics-settings-source-set.setting-fps", PhysicsSettingsSourceSet.class, "settingFps", "()Ljava/lang/Integer;"));
        values.add(method("cubism.editor-model.physics-settings-source-set.sources", PhysicsSettingsSourceSet.class, "sources", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.physics-settings-source.class", internal(PhysicsSettingsSourceDoc.class)));
        values.add(method("cubism.editor-model.physics-settings-source.id", PhysicsSettingsSourceDoc.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.physics-settings-source.name", PhysicsSettingsSourceDoc.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.physics-settings-source.total-angle", PhysicsSettingsSourceDoc.class, "totalAngle", "()F"));
        values.add(method("cubism.editor-model.physics-settings-source.inputs", PhysicsSettingsSourceDoc.class, "inputs", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.physics-settings-source.outputs", PhysicsSettingsSourceDoc.class, "outputs", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.physics-settings-source.vertices", PhysicsSettingsSourceDoc.class, "vertices", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.vector2.class", internal(GVector2.class)));
        values.add(method("cubism.editor-model.vector2.x", GVector2.class, "x", "()F"));
        values.add(method("cubism.editor-model.vector2.y", GVector2.class, "y", "()F"));
        values.add(StaticSelector.classSelector("cubism.editor-model.animation-file-content.class", internal(AnimationFileContent.class)));
        values.add(method("cubism.editor-model.animation-file-content.animation", AnimationFileContent.class, "animation", "()L" + internal(Animation.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.animation.class", internal(Animation.class)));
        values.add(method("cubism.editor-model.animation.name", Animation.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.animation.scenes", Animation.class, "scenes", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.animation.current-scene", Animation.class, "currentScene", "()L" + internal(SceneSource.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.scene-source.class", internal(SceneSource.class)));
        values.add(method("cubism.editor-model.scene-source.scene-name", SceneSource.class, "sceneName", "()Ljava/lang/String;"));
        return List.copyOf(values);
    }

    private static StaticSelector method(
        final String alias,
        final Class<?> owner,
        final String name,
        final String descriptor
    ) {
        return StaticSelector.method(
            alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC
        );
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    private static final class Fixture {
        final Document document = new Document();
    }

    public static final class Host {
        private static final Host INSTANCE = new Host();
        static Document document;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }
    }

    public static final class Document {
        private final ModelSource source = new ModelSource();
        private final AnimationFileContent animation = new AnimationFileContent();

        public ModelSource modelSource() {
            return source;
        }

        public List<Object> fileContentDocs() {
            return List.of(animation);
        }
    }

    public static final class ModelSource {
        private final WarpSource warp = new WarpSource();
        private final ParameterHolder parameter = new ParameterHolder();
        private final PhysicsSettingsSourceSet physics = new PhysicsSettingsSourceSet();

        public String sourceType() {
            return "model-source";
        }

        public Id guid() {
            return new Id("model-a");
        }

        private final Model instance = new Model();

        public Model currentInstance() {
            return instance;
        }

        public List<WarpSource> allDeformers() {
            return List.of(warp);
        }

        public List<ParameterHolder> allParameters() {
            return List.of(parameter);
        }

        public PhysicsSettingsSourceSet physicsSettingsSourceSet() {
            return physics;
        }
    }

    public static final class Model {
        private final ParameterSet parameterSet = new ParameterSet();

        public ParameterSet parameterSet() {
            return parameterSet;
        }
    }

    public static final class ParameterSet {
        public List<ParameterHolder> parameters() {
            return List.of(new ParameterHolder());
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

    public static class ObjectSource {
        private final Id id = new Id("source");
        final List<Object> extensions = new ArrayList<>();

        public Id id() {
            return id;
        }

        public List<Object> extensions() {
            return extensions;
        }
    }

    public static final class WarpSource extends ObjectSource {
        private final AutoYureConfigExtension autoYure = new AutoYureConfigExtension();
        private final Id warpId = new Id("WarpFace");

        WarpSource() {
            extensions.add(autoYure);
        }

        @Override
        public Id id() {
            return warpId;
        }
    }

    public static final class AutoYureConfigExtension {
        private final Map<Id, AutoYureConfig> configs = new LinkedHashMap<>();

        AutoYureConfigExtension() {
            configs.put(new Id("guid:ParamAngleX"), new AutoYureConfig());
        }

        public Map<Id, AutoYureConfig> paramToConfigMap() {
            return configs;
        }
    }

    public static final class AutoYureConfig {
        public YureDeformConfig left() {
            return new YureDeformConfig(10.0F, 20.0F, 1.5F, 1.0);
        }

        public YureDeformConfig right() {
            return new YureDeformConfig(30.0F, 40.0F, 2.5F, 2.0);
        }

        public boolean syncLeftRight() {
            return true;
        }

        public YureRootDirection rootDirection() {
            return YureRootDirection.TOP;
        }

        public boolean isFlip() {
            return true;
        }
    }

    public enum YureRootDirection {
        TOP,
        RIGHT,
        BOTTOM,
        LEFT
    }

    public static final class YureDeformConfig {
        private final float scalePercentX;
        private final float scalePercentY;
        private final float expandScale;
        private final double decayLevel;

        YureDeformConfig(final float x, final float y, final float expand, final double decay) {
            this.scalePercentX = x;
            this.scalePercentY = y;
            this.expandScale = expand;
            this.decayLevel = decay;
        }

        public float scalePercentX() {
            return scalePercentX;
        }

        public float scalePercentY() {
            return scalePercentY;
        }

        public float expandScale() {
            return expandScale;
        }

        public double decayLevel() {
            return decayLevel;
        }
    }

    public static final class ParameterSource {
        private final Id guid = new Id("guid:ParamAngleX");

        public Id guid() {
            return guid;
        }
    }

    public static final class ParameterHolder {
        private final ParameterSource parameterSource = new ParameterSource();

        public String parameterType() {
            return "parameter";
        }

        public ParameterSource source() {
            return parameterSource;
        }

        public Id id() {
            return new Id("ParamAngleX");
        }
    }

    public static final class PhysicsSettingsSourceSet {
        public GVector2 gravity() {
            return new GVector2(0.0F, -1.0F);
        }

        public GVector2 wind() {
            return new GVector2(0.5F, 0.0F);
        }

        public Integer settingFps() {
            return 60;
        }

        public List<PhysicsSettingsSourceDoc> sources() {
            return List.of(new PhysicsSettingsSourceDoc());
        }
    }

    public static final class GVector2 {
        private final float x;
        private final float y;

        GVector2(final float x, final float y) {
            this.x = x;
            this.y = y;
        }

        public float x() {
            return x;
        }

        public float y() {
            return y;
        }
    }

    public static final class PhysicsSettingsSourceDoc {
        public Id id() {
            return new Id("PhysicsA");
        }

        public String name() {
            return "Physics A";
        }

        public float totalAngle() {
            return 90.0F;
        }

        public List<Object> inputs() {
            return List.of(new Object(), new Object());
        }

        public List<Object> outputs() {
            return List.of(new Object());
        }

        public List<Object> vertices() {
            return List.of(new Object(), new Object(), new Object(), new Object(),
                new Object(), new Object(), new Object(), new Object());
        }
    }

    public static final class AnimationFileContent {
        public Animation animation() {
            return new Animation();
        }
    }

    public static final class Animation {
        public String name() {
            return "Animation A";
        }

        public List<SceneSource> scenes() {
            return List.of(new SceneSource("Scene 1"), new SceneSource("Scene 2"));
        }

        public SceneSource currentScene() {
            return new SceneSource("Scene 2");
        }
    }

    public static final class SceneSource {
        private final String sceneName;

        SceneSource(final String sceneName) {
            this.sceneName = sceneName;
        }

        public String sceneName() {
            return sceneName;
        }
    }
}
