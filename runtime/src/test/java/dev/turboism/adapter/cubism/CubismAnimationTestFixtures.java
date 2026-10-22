package dev.turboism.adapter.cubism;

import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;
import java.util.List;
import java.util.Optional;

/** Shared recording animation graph for facade and generation-fence contracts. */
public final class CubismAnimationTestFixtures {
    private CubismAnimationTestFixtures() {}

    public static AnimationTrack animationTrack(
            final String name,
            final AnimationTrackKind kind,
            final List<AnimationTrack> children,
            final List<AnimationAttribute> attributes,
            final List<String> calls) {
        return new AnimationTrack() {
            @Override
            public String guid() {
                return name + "-guid";
            }

            @Override
            public String name() {
                calls.add("track.name");
                return name;
            }

            @Override
            public AnimationTrackKind kind() {
                return kind;
            }

            @Override
            public int startFrame() {
                return 0;
            }

            @Override
            public int durationFrames() {
                return 60;
            }

            @Override
            public List<Integer> keyframeFrames() {
                return List.of();
            }

            @Override
            public boolean visible() {
                return true;
            }

            @Override
            public boolean editable() {
                return true;
            }

            @Override
            public boolean muted() {
                return false;
            }

            @Override
            public boolean repeat() {
                return false;
            }

            @Override
            public List<AnimationTrack> children() {
                calls.add("track.children");
                return children;
            }

            @Override
            public List<AnimationAttribute> attributes() {
                calls.add("track.attributes");
                return attributes;
            }

            @Override
            public Optional<String> linkedModelGuid() {
                return Optional.empty();
            }

            @Override
            public Optional<String> linkedSceneGuid() {
                return Optional.empty();
            }
        };
    }

    public static CubismModel animationGraphModel(final List<String> calls, final boolean recordMetadata) {
        final AnimationAttribute leaf = animationAttribute("attr-leaf", calls, recordMetadata);
        final AnimationTrack childTrack =
                animationTrack("track-child", AnimationTrackKind.IMAGE, List.of(), List.of(leaf), calls);
        final AnimationTrack modelTrack = animationTrack(
                "track-model",
                AnimationTrackKind.LIVE2D_MODEL,
                List.of(),
                List.of(animationAttribute("attr-model", calls, recordMetadata)),
                calls);
        final AnimationTrack groupTrack =
                animationTrack("track-group", AnimationTrackKind.GROUP, List.of(childTrack), List.of(), calls);
        final AnimationScene scene = new AnimationScene() {
            @Override
            public String name() {
                calls.add("scene.name");
                return "SceneA";
            }

            @Override
            public String guid() {
                return "scene-guid";
            }

            @Override
            public Optional<String> tag() {
                return Optional.empty();
            }

            @Override
            public java.util.Map<Integer, String> markers() {
                if (recordMetadata) calls.add("scene.markers");
                return java.util.Map.of();
            }

            @Override
            public int startFrame() {
                return 0;
            }

            @Override
            public int durationFrames() {
                return 120;
            }

            @Override
            public double framesPerSecond() {
                return 30.0;
            }

            @Override
            public int width() {
                return 100;
            }

            @Override
            public int height() {
                return 100;
            }

            @Override
            public boolean loopMotion() {
                return false;
            }

            @Override
            public int workspaceStartFrame() {
                return 0;
            }

            @Override
            public int workspaceEndFrame() {
                return 60;
            }

            @Override
            public List<AnimationTrack> tracks() {
                calls.add("scene.tracks");
                return List.of(modelTrack, groupTrack);
            }

            @Override
            public int playheadFrame() {
                calls.add("scene.playheadFrame");
                return 7;
            }

            @Override
            public void seekTo(final int frame) {
                calls.add("scene.seekTo");
            }

            @Override
            public boolean current() {
                return true;
            }

            @Override
            public void activate() {
                calls.add("scene.activate");
            }

            @Override
            public AnimationCurveType defaultCurveType() {
                return AnimationCurveType.LINEAR;
            }

            @Override
            public void setDefaultCurveType(final AnimationCurveType curveType) {
                calls.add("scene.setDefaultCurveType");
            }

            @Override
            public void rename(final String name) {
                calls.add("scene.rename");
            }
        };
        final AnimationDocument document = new AnimationDocument() {
            @Override
            public String animationName() {
                calls.add("doc.animationName");
                return "AnimDoc";
            }

            @Override
            public int sceneCount() {
                if (recordMetadata) calls.add("doc.sceneCount");
                return 1;
            }

            @Override
            public Optional<String> currentSceneName() {
                if (recordMetadata) calls.add("doc.currentSceneName");
                return Optional.of("SceneA");
            }

            @Override
            public List<String> sceneNames() {
                calls.add("doc.sceneNames");
                return List.of("SceneA");
            }

            @Override
            public List<AnimationScene> scenes() {
                calls.add("doc.scenes");
                return List.of(scene);
            }
        };
        return new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-anim");
            }

            @Override
            public Parameters parameters() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Parts parts() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Drawables drawables() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Deformers deformers() {
                throw new UnsupportedOperationException();
            }

            @Override
            public Glues glues() {
                throw new UnsupportedOperationException();
            }

            @Override
            public void update() {
                throw new UnsupportedOperationException();
            }

            @Override
            public List<AnimationDocument> animationDocuments() {
                calls.add("model.animationDocuments");
                return List.of(document);
            }
        };
    }

    public static AnimationAttribute animationAttribute(
            final String id, final List<String> calls, final boolean recordMetadata) {
        return new AnimationAttribute() {
            @Override
            public String id() {
                calls.add("attr.id");
                return id;
            }

            @Override
            public String name() {
                return id;
            }

            @Override
            public String guid() {
                return id + "-guid";
            }

            @Override
            public String effectId() {
                return "effect-param";
            }

            @Override
            public Optional<ParameterId> parameterId() {
                if (recordMetadata) calls.add("attr.parameterId");
                return Optional.of(new ParameterId("ParamAngleX"));
            }

            @Override
            public AnimationAttributeKind kind() {
                return AnimationAttributeKind.FLOAT;
            }

            @Override
            public boolean active() {
                return true;
            }

            @Override
            public boolean editable() {
                return true;
            }

            @Override
            public List<AnimationKeyframe> keyframes() {
                calls.add("attr.keyframes");
                return List.of();
            }

            @Override
            public void setKeyframe(final int frame, final double value) {
                calls.add("attr.setKeyframe");
            }

            @Override
            public void setKeyframe(final int frame, final double value, final AnimationCurveType curveType) {
                calls.add("attr.setKeyframeCurve");
            }

            @Override
            public void setKeyframe(final int frame, final float x, final float y) {
                calls.add("attr.setKeyframePoint");
            }

            @Override
            public void removeKeyframe(final int frame) {
                calls.add("attr.removeKeyframe");
            }

            @Override
            public int offsetKeyframes(final int frameDelta) {
                calls.add("attr.offsetKeyframes");
                return 0;
            }

            @Override
            public int scaleKeyframeTimes(final double factor, final int originFrame) {
                calls.add("attr.scaleKeyframeTimes");
                return 0;
            }

            @Override
            public int quantizeKeyframes(final int stepFrames) {
                calls.add("attr.quantizeKeyframes");
                return 0;
            }

            @Override
            public int copyKeyframesFrom(final AnimationAttribute source, final boolean replace) {
                calls.add("attr.copyKeyframesFrom");
                return 0;
            }

            @Override
            public int applyCurveType(final AnimationCurveType curveType) {
                calls.add("attr.applyCurveType");
                return 0;
            }

            @Override
            public int applyCurveType(final AnimationCurveType curveType, final int fromFrame, final int toFrame) {
                calls.add("attr.applyCurveTypeRange");
                return 0;
            }

            @Override
            public void recordKeyframe(final int frame, final AnimationCurveType curveType) {
                calls.add("attr.recordKeyframe");
            }

            @Override
            public int bakeEvaluated(
                    final int fromFrame, final int toFrame, final int stepFrames, final AnimationCurveType curveType) {
                calls.add("attr.bakeEvaluated");
                return 0;
            }
        };
    }
}
