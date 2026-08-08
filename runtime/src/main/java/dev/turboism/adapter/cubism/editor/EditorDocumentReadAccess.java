package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.EditorPhysicsReadSelectorContract;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.AutoYureBinding;
import dev.turboism.sdk.cubism.model.AutoYureConfig;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import dev.turboism.sdk.cubism.model.YureDeformConfig;
import dev.turboism.sdk.cubism.model.YureRootDirection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Verified read-only projections of Editor document families: physics settings,
 * animation file contents, and auto-Yure evaluations.
 *
 * <p>Every projection is generation-bound through the shared model guard and
 * fails closed unless the exact verified capability is authorized. Reads never
 * mutate host state.</p>
 */
final class EditorDocumentReadAccess {

    private final VerifiedMemberResolver resolver;
    private final EditorParameterCombinedAccess.ModelGuard modelGuard;

    EditorDocumentReadAccess(
        final VerifiedMemberResolver resolver,
        final EditorParameterCombinedAccess.ModelGuard modelGuard
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.modelGuard = Objects.requireNonNull(modelGuard, "modelGuard");
    }

    PhysicsSettings physicsSettings(final String identity, final Object source, final Object model) {
        requireAuthorization(
            EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
            EditorPhysicsReadSelectorContract.CAPABILITY_ID,
            EditorPhysicsReadSelectorContract.REQUIRED_ALIASES,
            "Physics settings document access"
        );
        modelGuard.requireCurrent(identity, model);
        final Object settings = resolver.invoke(
            "cubism.editor-model.model-source.physics-settings-source-set", source
        );
        requireInstance(
            "cubism.editor-model.physics-settings-source-set.class",
            settings,
            "Editor physics settings are unavailable."
        );
        final Object gravity = resolver.invoke(
            "cubism.editor-model.physics-settings-source-set.gravity", settings
        );
        final Object wind = resolver.invoke(
            "cubism.editor-model.physics-settings-source-set.wind", settings
        );
        final Object rawFps = resolver.invoke(
            "cubism.editor-model.physics-settings-source-set.setting-fps", settings
        );
        final Object rawSources = resolver.invoke(
            "cubism.editor-model.physics-settings-source-set.sources", settings
        );
        final Integer fps;
        if (rawFps == null) {
            fps = null;
        } else if (rawFps instanceof Integer value) {
            fps = value;
        } else {
            throw unavailable("Editor physics settings FPS is invalid.");
        }
        return new PhysicsSettings() {
            @Override public float gravityX() { return vectorComponent(gravity, "cubism.editor-model.vector2.x", "gravity X"); }
            @Override public float gravityY() { return vectorComponent(gravity, "cubism.editor-model.vector2.y", "gravity Y"); }
            @Override public float windX() { return vectorComponent(wind, "cubism.editor-model.vector2.x", "wind X"); }
            @Override public float windY() { return vectorComponent(wind, "cubism.editor-model.vector2.y", "wind Y"); }
            @Override public Integer settingFps() { return fps; }
            @Override public List<PhysicsSettingsSource> sources() {
                return list(rawSources, "Editor physics settings sources").stream()
                    .map(EditorDocumentReadAccess.this::physicsSource)
                    .toList();
            }
        };
    }

    AutoYure autoYure(final String identity, final Object source, final Object model) {
        requireAuthorization(
            EditorAutoYureReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAutoYureReadSelectorContract.CAPABILITY_ID,
            EditorAutoYureReadSelectorContract.REQUIRED_ALIASES,
            "Auto-Yure evaluation access"
        );
        modelGuard.requireCurrent(identity, model);
        final Map<String, ParameterId> parametersByGuid = parametersByGuid(model);
        final List<AutoYureBinding> bindings = new ArrayList<>();
        for (Object deformerSource : list(
            resolver.invoke("cubism.editor-model.model-source.all-deformers", source),
            "Editor Deformer sources"
        )) {
            if (!resolver.isInstance("cubism.editor-model.warp-source.class", deformerSource)) {
                continue;
            }
            final Object extension = extension(deformerSource);
            if (extension == null) {
                continue;
            }
            final Object rawMap = resolver.invoke(
                "cubism.editor-model.auto-yure-config-extension.param-to-config-map", extension
            );
            if (!(rawMap instanceof Map<?, ?> configMap)) {
                throw unavailable("Editor auto-Yure configuration map is invalid.");
            }
            final String deformerId = deformerId(deformerSource);
            for (Map.Entry<?, ?> entry : configMap.entrySet()) {
                final String guid = guidValue(entry.getKey(), "Editor auto-Yure parameter GUID");
                final ParameterId parameterId = parametersByGuid.get(guid);
                if (parameterId == null) {
                    throw unavailable("Editor auto-Yure parameter GUID has no model parameter: " + guid);
                }
                final Object config = entry.getValue();
                requireInstance(
                    "cubism.editor-model.auto-yure-config.class",
                    config,
                    "Editor auto-Yure configuration is invalid."
                );
                bindings.add(new AutoYureBinding() {
                    @Override public DeformerId deformerId() { return new DeformerId(deformerId); }
                    @Override public ParameterId parameterId() { return parameterId; }
                    @Override public AutoYureConfig config() { return yureConfig(config); }
                });
            }
        }
        return new AutoYure() {
            @Override public List<AutoYureBinding> bindings() { return List.copyOf(bindings); }
        };
    }

    List<AnimationDocument> animationDocuments(
        final String identity,
        final Object source,
        final Object model
    ) {
        requireAuthorization(
            EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
            EditorAnimationReadSelectorContract.CAPABILITY_ID,
            EditorAnimationReadSelectorContract.REQUIRED_ALIASES,
            "Animation document access"
        );
        modelGuard.requireCurrent(identity, model);
        final Object app = resolver.invokeStatic("cubism.editor-model.app-controller.instance");
        final Object document = app == null ? null : resolver.invoke(
            "cubism.editor-model.app-controller.current-document", app
        );
        if (!resolver.isInstance("cubism.editor-model.modeling-document.class", document)) {
            throw unavailable("The active Cubism document is not a modeling document.");
        }
        modelGuard.requireCurrent(identity, model);
        final List<AnimationDocument> documents = new ArrayList<>();
        for (Object content : list(
            resolver.invoke("cubism.editor-model.modeling-document.file-content-docs", document),
            "Editor file-content documents"
        )) {
            if (!resolver.isInstance("cubism.editor-model.animation-file-content.class", content)) {
                continue;
            }
            final Object animation = resolver.invoke(
                "cubism.editor-model.animation-file-content.animation", content
            );
            requireInstance(
                "cubism.editor-model.animation.class",
                animation,
                "Editor animation file content is invalid."
            );
            final String animationName = text(
                resolver.invoke("cubism.editor-model.animation.name", animation),
                "Editor animation name"
            );
            final Object rawScenes = resolver.invoke("cubism.editor-model.animation.scenes", animation);
            final List<?> scenes = list(rawScenes, "Editor animation scenes");
            final Object currentScene = resolver.invoke(
                "cubism.editor-model.animation.current-scene", animation
            );
            final Optional<String> currentName = currentScene == null
                ? Optional.empty()
                : Optional.of(sceneName(currentScene));
            final List<String> sceneNames = scenes.stream()
                .map(EditorDocumentReadAccess.this::sceneName)
                .toList();
            documents.add(new AnimationDocument() {
                @Override public String animationName() { return animationName; }
                @Override public int sceneCount() { return sceneNames.size(); }
                @Override public Optional<String> currentSceneName() { return currentName; }
                @Override public List<String> sceneNames() { return sceneNames; }
            });
        }
        return List.copyOf(documents);
    }

    private PhysicsSettingsSource physicsSource(final Object rawSource) {
        requireInstance(
            "cubism.editor-model.physics-settings-source.class",
            rawSource,
            "Editor physics settings source is invalid."
        );
        final Object rawId = resolver.invoke(
            "cubism.editor-model.physics-settings-source.id", rawSource
        );
        final String id = text(
            resolver.invoke("cubism.editor-model.id.value", rawId),
            "Editor physics settings source id"
        );
        final String name = text(
            resolver.invoke("cubism.editor-model.physics-settings-source.name", rawSource),
            "Editor physics settings source name"
        );
        final float totalAngle = number(
            resolver.invoke("cubism.editor-model.physics-settings-source.total-angle", rawSource),
            "Editor physics settings total angle"
        );
        final int inputCount = list(
            resolver.invoke("cubism.editor-model.physics-settings-source.inputs", rawSource),
            "Editor physics inputs"
        ).size();
        final int outputCount = list(
            resolver.invoke("cubism.editor-model.physics-settings-source.outputs", rawSource),
            "Editor physics outputs"
        ).size();
        final int vertexCount = list(
            resolver.invoke("cubism.editor-model.physics-settings-source.vertices", rawSource),
            "Editor physics vertices"
        ).size();
        return new PhysicsSettingsSource() {
            @Override public String id() { return id; }
            @Override public String name() { return name; }
            @Override public float totalAngle() { return totalAngle; }
            @Override public int inputCount() { return inputCount; }
            @Override public int outputCount() { return outputCount; }
            @Override public int vertexCount() { return vertexCount; }
        };
    }

    private Object extension(final Object deformerSource) {
        for (Object extension : list(
            resolver.invoke("cubism.editor-model.parameter-controllable-source.extensions", deformerSource),
            "Editor deformer extensions"
        )) {
            if (resolver.isInstance("cubism.editor-model.auto-yure-config-extension.class", extension)) {
                return extension;
            }
        }
        return null;
    }

    private String deformerId(final Object deformerSource) {
        final Object rawId = resolver.invoke(
            "cubism.editor-model.parameter-controllable-source.id", deformerSource
        );
        if (rawId == null) {
            throw unavailable("Editor auto-Yure deformer id is unavailable.");
        }
        return text(resolver.invoke("cubism.editor-model.id.value", rawId), "Editor auto-Yure deformer id");
    }

    private Map<String, ParameterId> parametersByGuid(final Object model) {
        final Map<String, ParameterId> byGuid = new LinkedHashMap<>();
        final Object parameterSet = resolver.invoke("cubism.editor-model.model.parameter-set", model);
        if (parameterSet == null) {
            throw unavailable("Editor parameter set is unavailable.");
        }
        for (Object parameter : list(
            resolver.invoke("cubism.editor-model.parameter-set.parameters", parameterSet),
            "Editor parameters"
        )) {
            requireInstance("cubism.editor-model.parameter.class", parameter, "Editor parameter is invalid.");
            final Object parameterSource = resolver.invoke("cubism.editor-model.parameter.source", parameter);
            if (parameterSource == null) {
                throw unavailable("Editor parameter source is unavailable.");
            }
            final String guid = guidValue(
                resolver.invoke("cubism.editor-model.parameter-source.guid", parameterSource),
                "Editor parameter GUID"
            );
            final Object rawId = resolver.invoke("cubism.editor-model.parameter.id", parameter);
            final String id = text(
                resolver.invoke("cubism.editor-model.id.value", rawId),
                "Editor parameter id"
            );
            byGuid.put(guid, new ParameterId(id));
        }
        return Map.copyOf(byGuid);
    }

    private AutoYureConfig yureConfig(final Object rawConfig) {
        final Object left = resolver.invoke("cubism.editor-model.auto-yure-config.left", rawConfig);
        final Object right = resolver.invoke("cubism.editor-model.auto-yure-config.right", rawConfig);
        final boolean sync = flag(
            resolver.invoke("cubism.editor-model.auto-yure-config.sync-left-right", rawConfig),
            "Editor auto-Yure sync flag"
        );
        final YureRootDirection root = rootDirection(
            resolver.invoke("cubism.editor-model.auto-yure-config.root-direction", rawConfig)
        );
        final boolean flip = flag(
            resolver.invoke("cubism.editor-model.auto-yure-config.flip", rawConfig),
            "Editor auto-Yure flip flag"
        );
        return new AutoYureConfig() {
            @Override public YureDeformConfig left() { return yureDeform(left); }
            @Override public YureDeformConfig right() { return yureDeform(right); }
            @Override public boolean syncLeftRight() { return sync; }
            @Override public YureRootDirection rootDirection() { return root; }
            @Override public boolean isFlip() { return flip; }
        };
    }

    private YureDeformConfig yureDeform(final Object rawConfig) {
        requireInstance(
            "cubism.editor-model.yure-deform-config.class",
            rawConfig,
            "Editor auto-Yure deformation config is invalid."
        );
        final float scalePercentX = number(
            resolver.invoke("cubism.editor-model.yure-deform-config.scale-percent-x", rawConfig),
            "Editor auto-Yure scale percent X"
        );
        final float scalePercentY = number(
            resolver.invoke("cubism.editor-model.yure-deform-config.scale-percent-y", rawConfig),
            "Editor auto-Yure scale percent Y"
        );
        final float expandScale = number(
            resolver.invoke("cubism.editor-model.yure-deform-config.expand-scale", rawConfig),
            "Editor auto-Yure expand scale"
        );
        final double decayLevel = doubleValue(
            resolver.invoke("cubism.editor-model.yure-deform-config.decay-level", rawConfig),
            "Editor auto-Yure decay level"
        );
        return new YureDeformConfig() {
            @Override public float scalePercentX() { return scalePercentX; }
            @Override public float scalePercentY() { return scalePercentY; }
            @Override public float expandScale() { return expandScale; }
            @Override public double decayLevel() { return decayLevel; }
        };
    }

    private YureRootDirection rootDirection(final Object rawDirection) {
        if (sameInstance(rawDirection, "cubism.editor-model.auto-yure-config-root-direction.top")) {
            return YureRootDirection.TOP;
        }
        if (sameInstance(rawDirection, "cubism.editor-model.auto-yure-config-root-direction.right")) {
            return YureRootDirection.RIGHT;
        }
        if (sameInstance(rawDirection, "cubism.editor-model.auto-yure-config-root-direction.bottom")) {
            return YureRootDirection.BOTTOM;
        }
        if (sameInstance(rawDirection, "cubism.editor-model.auto-yure-config-root-direction.left")) {
            return YureRootDirection.LEFT;
        }
        throw unavailable("Editor auto-Yure root direction is invalid.");
    }

    private boolean sameInstance(final Object value, final String fieldAlias) {
        return value != null && value == resolver.readStaticField(fieldAlias);
    }

    private String sceneName(final Object sceneSource) {
        requireInstance(
            "cubism.editor-model.scene-source.class",
            sceneSource,
            "Editor animation scene is invalid."
        );
        return text(
            resolver.invoke("cubism.editor-model.scene-source.scene-name", sceneSource),
            "Editor animation scene name"
        );
    }

    private float vectorComponent(final Object vector, final String alias, final String label) {
        requireInstance("cubism.editor-model.vector2.class", vector, "Editor vector is invalid.");
        return number(resolver.invoke(alias, vector), label);
    }

    private String guidValue(final Object rawGuid, final String label) {
        if (rawGuid == null) {
            throw unavailable(label + " is unavailable.");
        }
        return text(resolver.invoke("cubism.editor-model.guid.value", rawGuid), label);
    }

    private static List<?> list(final Object value, final String label) {
        if (!(value instanceof List<?> list)) {
            throw unavailable(label + " is unavailable.");
        }
        return List.copyOf(list);
    }

    private void requireInstance(final String classAlias, final Object value, final String message) {
        if (!resolver.isInstance(classAlias, value)) {
            throw unavailable(message);
        }
    }

    private void requireAuthorization(
        final String sliceId,
        final String capabilityId,
        final java.util.Set<String> aliases,
        final String label
    ) {
        if (!resolver.authorizesFeature(sliceId, capabilityId, aliases)) {
            throw new UnsupportedOperationException(
                label + " is unavailable without exact verified host evidence."
            );
        }
    }

    private static String text(final Object value, final String label) {
        if (!(value instanceof String result) || result.isBlank()) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static boolean flag(final Object value, final String label) {
        if (!(value instanceof Boolean result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static float number(final Object value, final String label) {
        if (!(value instanceof Float result) || !Float.isFinite(result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static double doubleValue(final Object value, final String label) {
        if (!(value instanceof Double result) || !Double.isFinite(result)) {
            throw unavailable(label + " is invalid.");
        }
        return result;
    }

    private static IllegalStateException unavailable(final String message) {
        return new IllegalStateException(message);
    }
}
