package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationSceneOperationSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorObjectReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationAttributeKind;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void readsAnimationSceneTimelines(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver(version), "session-a").active();

        final AnimationDocument animation = model.animationDocuments().get(0);
        final List<AnimationScene> scenes = animation.scenes();
        assertEquals(2, scenes.size());

        final AnimationScene scene = scenes.get(0);
        assertEquals("Scene 1", scene.name());
        assertEquals("scene-guid-1", scene.guid());
        assertEquals("intro", scene.tag().orElseThrow());
        assertEquals(Map.of(0, "start", 60, "peak"), scene.markers());
        assertEquals(0, scene.startFrame());
        assertEquals(120, scene.durationFrames());
        assertEquals(30.0, scene.framesPerSecond());
        assertEquals(1920, scene.width());
        assertEquals(1080, scene.height());
        assertTrue(scene.loopMotion());
        assertEquals(10, scene.workspaceStartFrame());
        assertEquals(100, scene.workspaceEndFrame());

        assertEquals(4, scene.tracks().size());
        final var modelTrack = scene.tracks().get(0);
        assertEquals(AnimationTrackKind.LIVE2D_MODEL, modelTrack.kind());
        assertEquals("track-model", modelTrack.guid());
        assertEquals("Model A", modelTrack.name());
        assertEquals(0, modelTrack.startFrame());
        assertEquals(120, modelTrack.durationFrames());
        assertEquals(List.of(0, 60, 120), modelTrack.keyframeFrames());
        assertTrue(modelTrack.visible());
        assertTrue(modelTrack.editable());
        assertEquals("model-a-reloaded", modelTrack.linkedModelGuid().orElseThrow());
        assertTrue(modelTrack.linkedSceneGuid().isEmpty());
        assertTrue(modelTrack.children().isEmpty());

        assertEquals(2, modelTrack.attributes().size());
        final var parameter = modelTrack.attributes().get(0);
        assertEquals("live2dParam_ParamAngleX", parameter.id());
        assertEquals("Angle X", parameter.name());
        assertEquals("attr-guid-1", parameter.guid());
        assertEquals("effect-param", parameter.effectId());
        assertEquals(new ParameterId("ParamAngleX"), parameter.parameterId().orElseThrow());
        assertEquals(AnimationAttributeKind.FLOAT, parameter.kind());
        assertTrue(parameter.active());
        assertTrue(parameter.editable());
        assertEquals(3, parameter.keyframes().size());
        final var first = parameter.keyframes().get(0);
        assertEquals(0, first.frame());
        assertEquals(0.0, first.value().orElseThrow());
        assertTrue(first.pointValue().isEmpty());
        assertEquals(AnimationCurveType.LINEAR, first.curveType().orElseThrow());
        assertTrue(first.inHandle().isEmpty());
        assertTrue(first.outHandle().isEmpty());
        final var middle = parameter.keyframes().get(1);
        assertEquals(30, middle.frame());
        assertEquals(0.5, middle.value().orElseThrow());
        assertEquals(AnimationCurveType.BEZIER, middle.curveType().orElseThrow());
        assertEquals(24.5F, middle.inHandle().orElseThrow().frame());
        assertEquals(0.4, middle.inHandle().orElseThrow().value());
        assertEquals(36.0F, middle.outHandle().orElseThrow().frame());
        assertEquals(0.6, middle.outHandle().orElseThrow().value());
        assertTrue(middle.outHandle().orElseThrow().corner());

        final var frameStep = modelTrack.attributes().get(1);
        assertEquals(AnimationAttributeKind.INTEGER, frameStep.kind());
        assertTrue(frameStep.parameterId().isEmpty());
        assertEquals(2, frameStep.keyframes().size());
        assertEquals(2.0, frameStep.keyframes().get(1).value().orElseThrow());
        assertTrue(frameStep.keyframes().get(1).curveType().isEmpty());

        final var group = scene.tracks().get(1);
        assertEquals(AnimationTrackKind.GROUP, group.kind());
        assertEquals(1, group.children().size());
        assertEquals(AnimationTrackKind.IMAGE, group.children().get(0).kind());
        final var point = group.children().get(0).attributes().get(0);
        assertEquals(AnimationAttributeKind.POINT, point.kind());
        assertEquals(120, point.keyframes().get(1).frame());
        assertEquals(10.0F, point.keyframes().get(1).pointValue().orElseThrow().x());
        assertEquals(5.0F, point.keyframes().get(1).pointValue().orElseThrow().y());
        assertTrue(point.keyframes().get(1).value().isEmpty());

        final var sceneTrack = scene.tracks().get(2);
        assertEquals(AnimationTrackKind.SCENE, sceneTrack.kind());
        assertEquals("scene-guid-2", sceneTrack.linkedSceneGuid().orElseThrow());
        assertTrue(sceneTrack.linkedModelGuid().isEmpty());
        assertTrue(sceneTrack.attributes().isEmpty());
        assertEquals(AnimationTrackKind.SOUND, scene.tracks().get(3).kind());

        final AnimationScene second = scenes.get(1);
        assertEquals("scene-guid-2", second.guid());
        assertTrue(second.tag().isEmpty());
        assertTrue(second.markers().isEmpty());
        assertEquals(24.0, second.framesPerSecond());
        assertTrue(!second.loopMotion());
        assertEquals(0, second.workspaceStartFrame());
        assertEquals(60, second.workspaceEndFrame());
        assertEquals(1, second.tracks().size());
        assertEquals(AnimationTrackKind.TEXT, second.tracks().get(0).kind());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void animationScenesFailClosedWithoutTimelineCapability(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            shallowAnimationResolver(version), "session-a"
        ).active();

        final AnimationDocument animation = model.animationDocuments().get(0);
        assertEquals("Animation A", animation.animationName());
        assertThrows(UnsupportedOperationException.class, animation::scenes);
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void animationScenesFailClosedWhenTimelineAliasesAreMissing(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final List<StaticSelector> reduced = selectors().stream()
            .filter(selector -> !"cubism.editor-model.track-source.name".equals(selector.alias()))
            .toList();
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorAnimationReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID);
        final var model = new EditorBackedCubismModelAccess(
            TestVerifiedResolvers.create(
                version,
                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                reduced,
                Host.class.getClassLoader()
            ),
            "session-a"
        ).active();

        final AnimationDocument animation = model.animationDocuments().get(0);
        assertThrows(UnsupportedOperationException.class, animation::scenes);
    }

    @Test
    void writesScalarKeyframeThroughNativeUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute parameter = scene1Parameter(model);

        parameter.setKeyframe(45, 0.75);
        parameter.setKeyframe(30, 0.9);

        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertEquals(2, sceneDocument.editMode.began);
        assertEquals(2, sceneDocument.editMode.ended);
        assertFalse(sceneDocument.editMode.lastCancelled);
        assertTrue(sceneDocument.modified);
        assertEquals(2, sceneDocument.completePack.projectUpdates);
        assertEquals(2, sceneDocument.completePack.repaints);

        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(0, 30, 45, 60), frames(reloaded));
        assertEquals(0.75, reloaded.keyframes().get(2).value().orElseThrow());
        final var overwritten = reloaded.keyframes().get(1);
        assertEquals(0.9, overwritten.value().orElseThrow());
        assertEquals(AnimationCurveType.BEZIER, overwritten.curveType().orElseThrow());
        assertEquals(AnimationCurveType.LINEAR,
            reloaded.keyframes().get(2).curveType().orElseThrow());
    }

    @Test
    void writesScalarKeyframeWithExplicitCurveType() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();

        scene1Parameter(model).setKeyframe(15, 0.25, AnimationCurveType.STEP);

        final var written = scene1Parameter(model).keyframes().get(1);
        assertEquals(15, written.frame());
        assertEquals(0.25, written.value().orElseThrow());
        assertEquals(AnimationCurveType.STEP, written.curveType().orElseThrow());
    }

    @Test
    void writesPointKeyframe() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute point = model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(1).children().get(0).attributes().get(0);

        point.setKeyframe(60, 3.5F, -2.0F);

        final var reloaded = model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(1).children().get(0).attributes().get(0);
        assertEquals(List.of(0, 60, 120), frames(reloaded));
        assertEquals(3.5F, reloaded.keyframes().get(1).pointValue().orElseThrow().x());
        assertEquals(-2.0F, reloaded.keyframes().get(1).pointValue().orElseThrow().y());
    }

    @Test
    void removesKeyframeAndIgnoresMissingFrames() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute parameter = scene1Parameter(model);

        parameter.removeKeyframe(30);
        parameter.removeKeyframe(999);

        assertEquals(List.of(0, 60), frames(scene1Parameter(model)));
        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertEquals(1, sceneDocument.editMode.began);
        assertFalse(sceneDocument.editMode.lastCancelled);
    }

    @Test
    void offsetsKeyframesPreservingCurvesAndHandles() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();

        final int moved = scene1Parameter(model).offsetKeyframes(10);

        assertEquals(3, moved);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(10, 40, 70), frames(reloaded));
        final var bezierKey = reloaded.keyframes().get(1);
        assertEquals(0.5, bezierKey.value().orElseThrow());
        assertEquals(AnimationCurveType.BEZIER, bezierKey.curveType().orElseThrow());
        assertEquals(34.5F, bezierKey.inHandle().orElseThrow().frame());
        assertEquals(0.4, bezierKey.inHandle().orElseThrow().value());
        assertEquals(46.0F, bezierKey.outHandle().orElseThrow().frame());
        assertTrue(bezierKey.outHandle().orElseThrow().corner());
        assertEquals(AnimationCurveType.LINEAR,
            reloaded.keyframes().get(0).curveType().orElseThrow());
    }

    @Test
    void scalesKeyframeTimesAroundOrigin() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();

        final int scaled = scene1Parameter(model).scaleKeyframeTimes(2.0, 0);

        assertEquals(3, scaled);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(0, 60, 120), frames(reloaded));
        assertEquals(0.5, reloaded.keyframes().get(1).value().orElseThrow());
    }

    @Test
    void quantizesKeyframesCollapsingCollisionsInInsertionOrder() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();

        final int processed = scene1Parameter(model).quantizeKeyframes(45);

        assertEquals(3, processed);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(0, 45), frames(reloaded));
        assertEquals(1.0, reloaded.keyframes().get(1).value().orElseThrow());
    }

    @Test
    void copiesKeyframesAcrossAttributesReplacingTargets() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute source = model.animationDocuments().get(0)
            .scenes().get(1).tracks().get(0).attributes().get(0);
        assertEquals(AnimationAttributeKind.FLOAT, source.kind());

        final int copied = scene1Parameter(model).copyKeyframesFrom(source, true);

        assertEquals(2, copied);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(10, 50), frames(reloaded));
        assertEquals(1.0, reloaded.keyframes().get(0).value().orElseThrow());
        assertEquals(2.0, reloaded.keyframes().get(1).value().orElseThrow());
        assertEquals(AnimationCurveType.SMOOTH,
            reloaded.keyframes().get(0).curveType().orElseThrow());
    }

    @Test
    void copyKeyframesRejectsMismatchedKinds() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute point = model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(1).children().get(0).attributes().get(0);

        assertThrows(IllegalArgumentException.class,
            () -> scene1Parameter(model).copyKeyframesFrom(point, false));
    }

    @Test
    void renamesSceneThroughNativeUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);

        scene.rename("Renamed Scene");

        final SceneSource source = sceneDocument(fixture, 0).sceneSource();
        assertEquals("Renamed Scene", source.sceneName());
        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertEquals(1, sceneDocument.editMode.began);
        assertFalse(sceneDocument.editMode.lastCancelled);
        assertTrue(sceneDocument.modified);
        assertThrows(IllegalArgumentException.class, () -> scene.rename("  "));
    }

    @Test
    void rollsBackSceneEditWhenMutationFails() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a"
        ).active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final Attr host = hostAttribute(fixture, 0);
        host.failWrites = true;

        // The offset removes every source key before re-inserting; the failing
        // insert must roll the removal back through the cancelled edit.
        assertThrows(
            dev.turboism.mapping.verification.VerifiedAccessException.class,
            () -> parameter.offsetKeyframes(10));

        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertTrue(sceneDocument.editMode.lastCancelled);
        assertFalse(sceneDocument.modified);
        assertEquals(Map.of(0, 0.0, 30, 0.5, 60, 1.0), host.values);
    }

    @Test
    void timelineWritesFailClosedWithoutWriteCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            resolver("5.3.02"), "session-a"
        ).active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);
        final AnimationAttribute parameter = scene.tracks().get(0).attributes().get(0);

        assertThrows(UnsupportedOperationException.class,
            () -> parameter.setKeyframe(15, 1.0));
        assertThrows(UnsupportedOperationException.class,
            () -> parameter.removeKeyframe(30));
        assertThrows(UnsupportedOperationException.class,
            () -> parameter.offsetKeyframes(5));
        assertThrows(UnsupportedOperationException.class,
            () -> scene.rename("Nope"));
    }

    @Test
    void timelineWritesFailClosedWhenWriteAliasIsMissing() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final List<StaticSelector> reduced = selectors().stream()
            .filter(selector ->
                !"cubism.editor-model.attr.set-value-auto".equals(selector.alias()))
            .toList();
        final var model = new EditorBackedCubismModelAccess(
            TestVerifiedResolvers.create(
                "5.3.02",
                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(
                    EditorAnimationReadSelectorContract.CAPABILITY_ID,
                    EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID,
                    EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID
                ),
                reduced,
                Host.class.getClassLoader()
            ),
            "session-a"
        ).active();
        final AnimationAttribute parameter = model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(0).attributes().get(0);

        assertThrows(UnsupportedOperationException.class,
            () -> parameter.setKeyframe(15, 1.0));
    }

    @Test
    void playheadReadsAndSeekRepaintsWithoutUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0)
            .scenes().get(0);
        final SceneDocument document = sceneDocument(fixture, 0);
        final SceneInstance instance =
            document.sceneSource().sceneInstances().get(0);

        assertEquals(0, scene.playheadFrame());
        scene.seekTo(42);

        assertEquals(42, instance.currentTime().frame());
        assertEquals(42, scene.playheadFrame());
        assertEquals(1, document.completePack().repaints);
        assertEquals(0, document.currentEditMode().began);
    }

    @Test
    void sceneActivationSwitchesCurrentSceneWithoutUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final List<AnimationScene> scenes = model.animationDocuments().get(0)
            .scenes();
        final AnimationFileContent fileContent = Host.instance().currentProject().animation();
        final SceneDocument target = sceneDocument(fixture, 1);

        assertTrue(scenes.get(0).current());
        assertFalse(scenes.get(1).current());

        scenes.get(1).activate();

        assertSame(target, fileContent.currentSceneDoc());
        assertSame(target.sceneSource(), fileContent.animation().currentScene());
        assertFalse(scenes.get(0).current());
        assertTrue(scenes.get(1).current());
        assertEquals(0, target.currentEditMode().began);
        assertEquals(1, target.completePack().repaints);
    }

    @Test
    void defaultCurveTypeReadsAndWritesThroughUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0)
            .scenes().get(0);
        final SceneDocument document = sceneDocument(fixture, 0);

        assertEquals(AnimationCurveType.LINEAR, scene.defaultCurveType());
        scene.setDefaultCurveType(AnimationCurveType.STEP);
        assertEquals(AnimationCurveType.STEP, scene.defaultCurveType());
        assertEquals(1, document.currentEditMode().began);
        assertEquals(1, document.currentEditMode().ended);
    }

    @Test
    void applyCurveTypeRetimesOnlyKeyframesInRange() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AttrF attribute = (AttrF) hostAttribute(fixture, 0);
        final SceneEditMode editMode = sceneDocument(fixture, 0).currentEditMode();

        assertEquals(2, parameter.applyCurveType(AnimationCurveType.SMOOTH, 0, 30));

        assertEquals(CurveType.SMOOTH, attribute.valueData().curveType(0));
        assertEquals(CurveType.SMOOTH, attribute.valueData().curveType(30));
        assertEquals(CurveType.STEP, attribute.valueData().curveType(60));
        assertEquals(1, editMode.began);

        assertEquals(0, parameter.applyCurveType(AnimationCurveType.SMOOTH, 500, 900));
        assertEquals(1, editMode.began);
    }

    @Test
    void applyCurveTypeRejectsNonFloatAttributesAndBadRanges() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AnimationAttribute integer = model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(0).attributes().get(1);

        assertThrows(IllegalArgumentException.class,
            () -> parameter.applyCurveType(AnimationCurveType.SMOOTH, 30, 0));
        assertThrows(IllegalArgumentException.class,
            () -> integer.applyCurveType(AnimationCurveType.SMOOTH));
    }

    @Test
    void recordKeyframeWritesCurrentEvaluatedValue() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AttrF attribute = (AttrF) hostAttribute(fixture, 0);
        final SceneEditMode editMode = sceneDocument(fixture, 0).currentEditMode();

        parameter.recordKeyframe(45, AnimationCurveType.BEZIER);

        assertEquals(7.5, ((Number) attribute.values.get(45)).doubleValue(), 1e-6);
        assertEquals(CurveType.BEZIER, attribute.valueData().curveType(45));
        assertEquals(1, editMode.began);
        assertFalse(editMode.lastCancelled);
    }

    @Test
    void recordKeyframeFailsWithoutLiveModelInstance() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        // Scene 2's parameter attribute rides a text track — no model instance.
        final AnimationAttribute parameter = model.animationDocuments().get(0)
            .scenes().get(1).tracks().get(0).attributes().get(0);

        assertThrows(IllegalStateException.class,
            () -> parameter.recordKeyframe(10, AnimationCurveType.LINEAR));
    }

    @Test
    void bakeEvaluatedStepsThroughRangeAndRestoresPlayhead() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AttrF attribute = (AttrF) hostAttribute(fixture, 0);
        final SceneDocument document = sceneDocument(fixture, 0);
        final SceneInstance instance =
            document.sceneSource().sceneInstances().get(0);
        final ModelTrackInstance modelInstance =
            (ModelTrackInstance) instance.allTracks().get(0);

        assertEquals(3, parameter.bakeEvaluated(0, 10, 5, AnimationCurveType.STEP));

        assertEquals(3, modelInstance.evaluations);
        assertEquals(0.0, ((Number) attribute.values.get(0)).doubleValue(), 1e-6);
        assertEquals(2.5, ((Number) attribute.values.get(5)).doubleValue(), 1e-6);
        assertEquals(5.0, ((Number) attribute.values.get(10)).doubleValue(), 1e-6);
        assertEquals(CurveType.STEP, attribute.valueData().curveType(5));
        assertEquals(0, instance.currentTime().frame());
        assertEquals(1, document.currentEditMode().began);
        assertFalse(document.currentEditMode().lastCancelled);
    }

    @Test
    void bakeEvaluatedRejectsInvalidRanges() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);

        assertThrows(IllegalArgumentException.class,
            () -> parameter.bakeEvaluated(10, 0, 1, AnimationCurveType.LINEAR));
        assertThrows(IllegalArgumentException.class,
            () -> parameter.bakeEvaluated(0, 10, 0, AnimationCurveType.LINEAR));
    }

    @Test
    void playbackFailsClosedWithoutPlaybackCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolverWithout(
                "5.3.02",
                EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID
            ), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0)
            .scenes().get(0);

        assertThrows(UnsupportedOperationException.class, scene::playheadFrame);
        assertThrows(UnsupportedOperationException.class, () -> scene.seekTo(10));
    }

    @Test
    void sceneOperationsFailClosedWithoutSceneEditCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolverWithout(
                "5.3.02",
                EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID
            ), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0)
            .scenes().get(0);

        assertThrows(UnsupportedOperationException.class, scene::current);
        assertThrows(UnsupportedOperationException.class, scene::activate);
        assertThrows(UnsupportedOperationException.class, scene::defaultCurveType);
        assertThrows(UnsupportedOperationException.class,
            () -> scene.setDefaultCurveType(AnimationCurveType.STEP));
    }

    @Test
    void evalWritesFailClosedWithoutEvalCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(
            writeResolverWithout(
                "5.3.02",
                EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID
            ), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);

        assertThrows(UnsupportedOperationException.class,
            () -> parameter.recordKeyframe(10, AnimationCurveType.LINEAR));
        assertThrows(UnsupportedOperationException.class,
            () -> parameter.bakeEvaluated(0, 10, 5, AnimationCurveType.LINEAR));
    }

    private static AnimationAttribute scene1Parameter(
        final dev.turboism.sdk.cubism.model.CubismModel model
    ) {
        // FLOAT attribute "Angle X" on Scene 1's model track.
        return model.animationDocuments().get(0)
            .scenes().get(0).tracks().get(0).attributes().get(0);
    }

    private static SceneDocument sceneDocument(final Fixture fixture, final int index) {
        return Host.instance().currentProject().animation().sceneDocs().get(index);
    }

    private static Attr hostAttribute(final Fixture fixture, final int sceneIndex) {
        final GroupTrack root = (GroupTrack)
            sceneDocument(fixture, sceneIndex).sceneSource().rootTrack();
        final ModelTrack modelTrack = (ModelTrack) root.childTracks().get(0);
        return modelTrack.effectManager().effectList()[0].attrList()[0];
    }

    private static List<Integer> frames(final AnimationAttribute attribute) {
        final List<Integer> frames = new ArrayList<>();
        for (var keyframe : attribute.keyframes()) {
            frames.add(keyframe.frame());
        }
        return frames;
    }

    private static VerifiedMemberResolver writeResolver(final String version) {
        return writeResolverWithout(version, null);
    }

    private static VerifiedMemberResolver writeResolverWithout(
        final String version,
        final String excludedCapability
    ) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPhysicsReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAutoYureReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID);
        capabilities.remove(excludedCapability);
        return TestVerifiedResolvers.create(
            version,
            EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
            capabilities,
            selectors(),
            Host.class.getClassLoader()
        );
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
            capabilities.add(EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID);
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

    private static VerifiedMemberResolver shallowAnimationResolver(final String version) {
        return TestVerifiedResolvers.create(
            version,
            EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
            java.util.Set.of(EditorAnimationReadSelectorContract.CAPABILITY_ID),
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
        values.add(method("cubism.editor-model.file-content.file", Document.class, "file", "()Ljava/io/File;"));
        values.add(method("cubism.editor-model.app-controller.current-project", Host.class, "currentProject", "()L" + internal(Project.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.project.class", internal(Project.class)));
        values.add(method("cubism.editor-model.project.children", Project.class, "children", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)));
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
        values.add(method("cubism.editor-model.scene-source.guid", SceneSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.scene-source.tag", SceneSource.class, "tag", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.scene-source.marker", SceneSource.class, "marker", "()Ljava/util/HashMap;"));
        values.add(method("cubism.editor-model.scene-source.movie-info", SceneSource.class, "movieInfo", "()L" + internal(MovieInfo.class) + ";"));
        values.add(method("cubism.editor-model.scene-source.root-track", SceneSource.class, "rootTrack", "()L" + internal(GroupTrack.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.movie-info.class", internal(MovieInfo.class)));
        values.add(method("cubism.editor-model.movie-info.start-frame", MovieInfo.class, "startFrame", "()I"));
        values.add(method("cubism.editor-model.movie-info.duration", MovieInfo.class, "duration", "()I"));
        values.add(method("cubism.editor-model.movie-info.fps", MovieInfo.class, "fps", "()D"));
        values.add(method("cubism.editor-model.movie-info.width", MovieInfo.class, "width", "()I"));
        values.add(method("cubism.editor-model.movie-info.height", MovieInfo.class, "height", "()I"));
        values.add(method("cubism.editor-model.movie-info.loop-motion", MovieInfo.class, "isLoopMotion", "()Z"));
        values.add(method("cubism.editor-model.movie-info.workspace-start", MovieInfo.class, "workspaceStart", "()I"));
        values.add(method("cubism.editor-model.movie-info.workspace-end", MovieInfo.class, "workspaceEnd", "()I"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-source.class", internal(TrackSource.class)));
        values.add(method("cubism.editor-model.track-source.name", TrackSource.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.track-source.guid", TrackSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.track-source.start", TrackSource.class, "start", "()I"));
        values.add(method("cubism.editor-model.track-source.duration", TrackSource.class, "duration", "()I"));
        values.add(method("cubism.editor-model.track-source.editable", TrackSource.class, "editable", "()Z"));
        values.add(method("cubism.editor-model.track-source.visible", TrackSource.class, "visible", "()Z"));
        values.add(method("cubism.editor-model.track-source.mute", TrackSource.class, "mute", "()Z"));
        values.add(method("cubism.editor-model.track-source.repeat", TrackSource.class, "repeat", "()Z"));
        values.add(method("cubism.editor-model.track-source.key-frames", TrackSource.class, "keyFrames", "()[I"));
        values.add(method("cubism.editor-model.track-source.effect-manager", TrackSource.class, "effectManager", "()L" + internal(EffectManager.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-group.class", internal(GroupTrack.class)));
        values.add(method("cubism.editor-model.track-group.children", GroupTrack.class, "childTracks", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-model.class", internal(ModelTrack.class)));
        values.add(method("cubism.editor-model.track-model.model", ModelTrack.class, "model", "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.track-model.resource-ref", ModelTrack.class, "resourceRef", "()L" + internal(ResourceFile.class) + ";"));
        values.add(method("cubism.editor-model.resource-file.src-file", ResourceFile.class, "srcFile", "()Ljava/io/File;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-scene.class", internal(SceneTrack.class)));
        values.add(method("cubism.editor-model.track-scene.resource-scene-guid", SceneTrack.class, "resourceGuid", "()L" + internal(Id.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-image.class", internal(ImageTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-guide-image.class", internal(GuideImageTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-text.class", internal(TextTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-moc3.class", internal(Moc3Track.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-sound.class", internal(SoundTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.effect-manager.class", internal(EffectManager.class)));
        values.add(method("cubism.editor-model.effect-manager.effects", EffectManager.class, "effectList", "()[L" + internal(Effect.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.effect.class", internal(Effect.class)));
        values.add(method("cubism.editor-model.effect.id", Effect.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.effect.attrs", Effect.class, "attrList", "()[L" + internal(Attr.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.effect-parameter.class", internal(ParamEffect.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr.class", internal(Attr.class)));
        values.add(method("cubism.editor-model.attr.id", Attr.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.attr.name", Attr.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.attr.guid", Attr.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.attr.active", Attr.class, "active", "()Z"));
        values.add(method("cubism.editor-model.attr.editable", Attr.class, "editable", "()Z"));
        values.add(method("cubism.editor-model.attr.key-frames", Attr.class, "keyFrames", "()[I"));
        values.add(method("cubism.editor-model.attr.value", Attr.class, "value", "(I)Ljava/lang/Object;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-f.class", internal(AttrF.class)));
        values.add(method("cubism.editor-model.attr-f.value-data", AttrF.class, "valueData", "()L" + internal(MutableSequence.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-i.class", internal(AttrI.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-pt.class", internal(AttrPt.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.mutable-sequence.class", internal(MutableSequence.class)));
        values.add(method("cubism.editor-model.mutable-sequence.curve-type", MutableSequence.class, "curveType", "(I)L" + internal(CurveType.class) + ";"));
        values.add(method("cubism.editor-model.mutable-sequence.point", MutableSequence.class, "point", "(I)L" + internal(BezierPt.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.bezier-point.class", internal(BezierPt.class)));
        values.add(method("cubism.editor-model.bezier-point.prev", BezierPt.class, "prev", "()L" + internal(CtrlPt.class) + ";"));
        values.add(method("cubism.editor-model.bezier-point.next", BezierPt.class, "next", "()L" + internal(CtrlPt.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.bezier-ctrl-point.class", internal(CtrlPt.class)));
        values.add(method("cubism.editor-model.bezier-ctrl-point.pos", CtrlPt.class, "posF", "()F"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.value", CtrlPt.class, "doubleValue", "()D"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.corner", CtrlPt.class, "corner", "()Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.curve-type.class", internal(CurveType.class)));
        values.add(StaticSelector.field("cubism.editor-model.curve-type.linear", internal(CurveType.class), "LINEAR", "L" + internal(CurveType.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.curve-type.bezier", internal(CurveType.class), "BEZIER", "L" + internal(CurveType.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.curve-type.smooth", internal(CurveType.class), "SMOOTH", "L" + internal(CurveType.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.curve-type.step", internal(CurveType.class), "STEP", "L" + internal(CurveType.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field("cubism.editor-model.curve-type.inverse-step", internal(CurveType.class), "INVERSE_STEP", "L" + internal(CurveType.class) + ";", StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method("cubism.editor-model.animation-file-content.scene-docs", AnimationFileContent.class, "sceneDocs", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.scene-document.class", internal(SceneDocument.class)));
        values.add(method("cubism.editor-model.scene-document.scene-source", SceneDocument.class, "sceneSource", "()L" + internal(SceneSource.class) + ";"));
        values.add(method("cubism.editor-model.scene-document.current-edit-mode", SceneDocument.class, "currentEditMode", "()L" + internal(SceneEditMode.class) + ";"));
        values.add(method("cubism.editor-model.scene-document.complete-pack", SceneDocument.class, "completePack", "()L" + internal(CompletePack.class) + ";"));
        values.add(method("cubism.editor-model.scene-document.update-modified", SceneDocument.class, "updateModified", "()V"));
        values.add(method("cubism.editor-model.edit-mode-base.begin", EditModeBase.class, "begin", "(Ljava/lang/String;)L" + internal(GroupUndo.class) + ";"));
        values.add(method("cubism.editor-model.edit-mode-base.end", EditModeBase.class, "end", "(ZLjava/lang/Object;)Z"));
        values.add(method("cubism.editor-model.attr.set-value-auto", Attr.class, "setValueAuto", "(ILjava/lang/Object;)V"));
        values.add(method("cubism.editor-model.attr.remove-value-auto", Attr.class, "removeValueAuto", "(I)V"));
        values.add(method("cubism.editor-model.attr.track", Attr.class, "track", "()L" + internal(TrackSource.class) + ";"));
        values.add(method("cubism.editor-model.attr-f.set-value-curve", AttrF.class, "setValueAndCurveType", "(IDL" + internal(CurveType.class) + ";)V"));
        values.add(method("cubism.editor-model.attr-f.read-only", AttrF.class, "isReadOnly", "()Z"));
        values.add(method("cubism.editor-model.attr-i.set-value-auto", AttrI.class, "setValueAuto", "(ID)V"));
        values.add(method("cubism.editor-model.attr-pt.set-value-auto", AttrPt.class, "setValueAuto", "(IFF)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-pos", CtrlPt.class, "setPos", "(F)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-value", CtrlPt.class, "setValue", "(D)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-corner", CtrlPt.class, "setCorner", "(Z)V"));
        values.add(method("cubism.editor-model.mutable-sequence.force-update", MutableSequence.class, "forceUpdate", "()V"));
        values.add(method("cubism.editor-model.scene-source.set-scene-name", SceneSource.class, "setSceneName", "(Ljava/lang/String;)V"));
        values.add(method("cubism.editor-model.complete-pack.update-project", CompletePack.class, "updateProject", "()V"));
        values.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        values.add(StaticSelector.constructor("cubism.editor-model.simple-undo.create", internal(SimpleUndo.class), "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC));
        values.add(StaticSelector.constructor("cubism.editor-model.scene-handler.create", internal(SceneHandler.class), "(L" + internal(SceneSource.class) + ";)V", StaticSelector.ACCESS_PUBLIC));
        values.add(method("cubism.editor-model.scene-handler.basic-undo", SceneHandler.class, "a", "(Ljava/lang/String;)L" + internal(Undo.class) + ";"));
        values.add(method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(L" + internal(Undo.class) + ";Z)Z"));
        values.add(method("cubism.editor-model.undo.add-listener", Undo.class, "addListener", "(L" + internal(Listener.class) + ";)Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        values.add(method("cubism.editor-model.scene-source.scene-instances", SceneSource.class, "sceneInstances", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.scene-instance.class", internal(SceneInstance.class)));
        values.add(method("cubism.editor-model.scene-instance.current-time", SceneInstance.class, "currentTime", "()L" + internal(SceneTime.class) + ";"));
        values.add(method("cubism.editor-model.scene-time.frame", SceneTime.class, "frame", "()I"));
        values.add(method("cubism.editor-model.scene-time.set-frame", SceneTime.class, "frame", "(I)V"));
        values.add(method("cubism.editor-model.animation.set-current-scene", Animation.class, "setCurrentScene", "(L" + internal(SceneSource.class) + ";)V"));
        values.add(method("cubism.editor-model.animation-file-content.current-scene-doc", AnimationFileContent.class, "currentSceneDoc", "()L" + internal(SceneDocument.class) + ";"));
        values.add(method("cubism.editor-model.animation-file-content.set-current-scene-doc", AnimationFileContent.class, "setCurrentSceneDoc", "(L" + internal(SceneDocument.class) + ";)V"));
        values.add(method("cubism.editor-model.scene-document.animation", SceneDocument.class, "animation", "()L" + internal(Animation.class) + ";"));
        values.add(method("cubism.editor-model.scene-document.view-contexts", SceneDocument.class, "viewContexts", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.scene-source.default-curve-type", SceneSource.class, "defaultCurveType", "()L" + internal(CurveType.class) + ";"));
        values.add(method("cubism.editor-model.scene-source.set-default-curve-type", SceneSource.class, "setDefaultCurveType", "(L" + internal(CurveType.class) + ";)V"));
        values.add(method("cubism.editor-model.mutable-sequence.set-curve-type", MutableSequence.class, "setCurveType", "(IL" + internal(CurveType.class) + ";)V"));
        values.add(method("cubism.editor-model.scene-instance.root-track", SceneInstance.class, "rootTrack", "()L" + internal(GroupTrackInstance.class) + ";"));
        values.add(method("cubism.editor-model.scene-instance.all-tracks", SceneInstance.class, "allTracks", "()Ljava/util/List;"));
        values.add(method("cubism.editor-model.track-instance.update", TrackInstance.class, "update", "(Ljava/lang/Object;L" + internal(SceneTime.class) + ";L" + internal(EvalFlags.class) + ";)V"));
        values.add(method("cubism.editor-model.track-instance.source", TrackInstance.class, "source", "()L" + internal(TrackSource.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-model-instance.class", internal(ModelTrackInstance.class)));
        values.add(method("cubism.editor-model.track-model-instance.parameter-set", ModelTrackInstance.class, "parameterSet", "()L" + internal(ParameterSet.class) + ";"));
        values.add(StaticSelector.constructor("cubism.editor-model.eval-flags.create", internal(EvalFlags.class), "(Z)V", StaticSelector.ACCESS_PUBLIC));
        values.add(method("cubism.editor-model.parameter.value", ParameterHolder.class, "value", "()F"));
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
        private static Project project;

        public static Host instance() {
            return INSTANCE;
        }

        public Document currentDocument() {
            return document;
        }

        public Project currentProject() {
            if (project == null || project.document != document) {
                project = new Project(document);
            }
            return project;
        }
    }

    public static final class Project {
        private final Document document;
        private final AnimationFileContent animation;
        private final AnimationFileContent decoy;

        Project(final Document document) {
            this.document = document;
            this.animation = new AnimationFileContent(
                new ModelSource("model-a-reloaded"), document.file(), "Animation A"
            );
            this.decoy = new AnimationFileContent(
                new ModelSource("model-b"), new java.io.File("probe-model-b.cmo3"),
                "Decoy Animation"
            );
        }

        public List<Object> children() {
            return List.of(document, animation, decoy);
        }

        AnimationFileContent animation() {
            return animation;
        }
    }

    public static final class Document {
        private final ModelSource source = new ModelSource();
        private final java.io.File file = new java.io.File("probe-model-a.cmo3");

        public ModelSource modelSource() {
            return source;
        }

        public java.io.File file() {
            return file;
        }
    }

    public static final class ModelSource {
        private final WarpSource warp = new WarpSource();
        private final ParameterHolder parameter = new ParameterHolder();
        private final PhysicsSettingsSourceSet physics = new PhysicsSettingsSourceSet();
        private final Id guid;

        ModelSource() {
            this("model-a");
        }

        ModelSource(final String guid) {
            this.guid = new Id(guid);
        }

        public String sourceType() {
            return "model-source";
        }

        public Id guid() {
            return guid;
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
        private final ParameterHolder parameter = new ParameterHolder();

        public List<ParameterHolder> parameters() {
            return List.of(parameter);
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
        float value = 7.5F;

        public String parameterType() {
            return "parameter";
        }

        public ParameterSource source() {
            return parameterSource;
        }

        public Id id() {
            return new Id("ParamAngleX");
        }

        public float value() {
            return value;
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
        private final Animation animation;
        private final List<SceneDocument> sceneDocs = new ArrayList<>();
        private SceneDocument currentSceneDoc;

        AnimationFileContent(
            final ModelSource linked, final java.io.File linkedFile, final String name
        ) {
            this.animation = new Animation(linked, linkedFile, name);
            for (SceneSource scene : animation.scenes()) {
                sceneDocs.add(new SceneDocument(scene, animation));
            }
            currentSceneDoc = sceneDocs.get(0);
        }

        public Animation animation() {
            return animation;
        }

        public List<SceneDocument> sceneDocs() {
            return sceneDocs;
        }

        public SceneDocument currentSceneDoc() {
            return currentSceneDoc;
        }

        public void setCurrentSceneDoc(final SceneDocument doc) {
            currentSceneDoc = doc;
        }
    }

    public static final class SceneDocument {
        private final SceneSource sceneSource;
        private final Animation animation;
        private final SceneEditMode editMode = new SceneEditMode();
        private final CompletePack completePack = new CompletePack();
        private final List<ViewContext> viewContexts = List.of(new ViewContext());
        private boolean modified;

        SceneDocument(final SceneSource sceneSource, final Animation animation) {
            this.sceneSource = sceneSource;
            this.animation = animation;
        }

        public Animation animation() {
            return animation;
        }

        public List<ViewContext> viewContexts() {
            return viewContexts;
        }

        public SceneSource sceneSource() {
            return sceneSource;
        }

        public SceneEditMode currentEditMode() {
            return editMode;
        }

        public CompletePack completePack() {
            return completePack;
        }

        public void updateModified() {
            modified = true;
        }
    }

    public static class EditModeBase {
        private GroupUndo current;
        int began;
        int ended;
        boolean lastCancelled;

        public GroupUndo begin(final String name) {
            began++;
            current = new GroupUndo();
            return current;
        }

        public boolean end(final boolean cancelled, final Object fn) {
            ended++;
            lastCancelled = cancelled;
            if (cancelled && current != null) {
                current.undo();
            }
            current = null;
            return true;
        }
    }

    public static final class SceneEditMode extends EditModeBase {
    }

    public static final class CompletePack {
        int projectUpdates;
        int repaints;

        public void updateProject() {
            projectUpdates++;
        }

        public void repaint(final boolean all) {
            repaints++;
        }
    }

    public interface Listener {
        void changed(Object event);
    }

    public static class Undo {
        private final List<Listener> listeners = new ArrayList<>();

        public boolean addListener(final Listener listener) {
            return listeners.add(listener);
        }

        public void undo() {
        }
    }

    public static final class GroupUndo extends Undo {
        private final List<Undo> edits = new ArrayList<>();

        public boolean add(final Undo undo, final boolean unused) {
            return edits.add(undo);
        }

        @Override public void undo() {
            for (int index = edits.size() - 1; index >= 0; index--) {
                edits.get(index).undo();
            }
        }
    }

    public static final class SimpleUndo extends Undo {
        private final Object target;
        private final Object snapshot;

        public SimpleUndo(final String label, final Object target, final Object copier) {
            this.target = target;
            // Mirrors the real SimpleUndo: list targets are not restorable —
            // the host prints a warning telling callers to use ListUndo.
            if (target instanceof Attr attribute) {
                this.snapshot = attribute.snapshot();
            } else if (target instanceof SceneSource scene) {
                this.snapshot = scene.sceneName();
            } else {
                this.snapshot = null;
            }
        }

        @Override public void undo() {
            if (target instanceof Attr attribute) {
                attribute.restore(snapshot);
            } else if (target instanceof SceneSource scene) {
                scene.setSceneName((String) snapshot);
            }
        }
    }

    /**
     * Mirrors the real {@code SceneHandler.a(label)}: wraps a
     * {@code SceneSourceBasicData_forUndo} DTO inside a SimpleUndo so scene
     * name/curve-type edits roll back through the scene's own fields.
     */
    public static final class SceneHandler {
        private final SceneSource scene;

        public SceneHandler(final SceneSource scene) {
            this.scene = scene;
        }

        public Undo a(final String label) {
            return new SceneBasicDataUndo(scene);
        }
    }

    private static final class SceneBasicDataUndo extends Undo {
        private final SceneSource scene;
        private final String name;
        private final CurveType curveType;

        SceneBasicDataUndo(final SceneSource scene) {
            this.scene = scene;
            this.name = scene.sceneName();
            this.curveType = scene.defaultCurveType();
        }

        @Override public void undo() {
            scene.setSceneName(name);
            scene.setDefaultCurveType(curveType);
        }
    }

    public static final class Animation {
        private final String name;
        private final List<SceneSource> scenes;
        private SceneSource currentScene;

        Animation(
            final ModelSource linked, final java.io.File linkedFile, final String name
        ) {
            this.name = name;
            this.scenes = List.of(sceneOne(linked, linkedFile), sceneTwo());
            this.currentScene = scenes.get(1);
        }

        public String name() {
            return name;
        }

        public List<SceneSource> scenes() {
            return scenes;
        }

        public SceneSource currentScene() {
            return currentScene;
        }

        public void setCurrentScene(final SceneSource scene) {
            currentScene = scene;
        }

        private static SceneSource sceneOne(final ModelSource linked, final java.io.File linkedFile) {
            final MutableSequence sequence = new MutableSequence(
                Map.of(0, CurveType.LINEAR, 30, CurveType.BEZIER, 60, CurveType.STEP),
                Map.of(30, new BezierPt(new CtrlPt(24.5F, 0.4, false), new CtrlPt(36.0F, 0.6, true)))
            );
            final ModelTrack modelTrack = new ModelTrack(
                "track-model", "Model A", new int[]{0, 60, 120}, linked, linkedFile,
                new EffectManager(
                    new ParamEffect("effect-param", "Live2D Parameters",
                        new AttrF("live2dParam_ParamAngleX", "Angle X", "attr-guid-1",
                            new int[]{0, 30, 60}, Map.of(0, 0.0, 30, 0.5, 60, 1.0), sequence)),
                    new Effect("effect-visual", "Placement",
                        new AttrI("frameStep", "Frame Step", "attr-guid-2",
                            new int[]{0, 120}, Map.of(0, 0, 120, 2)))
                )
            );
            final GroupTrack subGroup = new GroupTrack(
                "track-group", "Nested",
                new ImageTrack("track-image", "Sprite", new int[]{0, 120},
                    new EffectManager(new Effect("effect-layout", "Layout",
                        new AttrPt("position", "Position", "attr-guid-3",
                            new int[]{0, 120}, Map.of(0, new GVector2(0.0F, 0.0F), 120, new GVector2(10.0F, 5.0F)))))
            ));
            return new SceneSource(
                "Scene 1", "scene-guid-1", "intro",
                new LinkedHashMap<>(Map.of(0, "start", 60, "peak")),
                new MovieInfo(0, 120, 30.0, 1920, 1080, true, 10, 100),
                new GroupTrack("track-root", "Root",
                    modelTrack, subGroup,
                    new SceneTrack("track-scene", "Scene Ref", "scene-guid-2"),
                    new SoundTrack("track-sound", "Voice", new int[]{10, 40}))
            );
        }

        private static SceneSource sceneTwo() {
            return new SceneSource(
                "Scene 2", "scene-guid-2", "",
                new LinkedHashMap<>(),
                new MovieInfo(0, 60, 24.0, 800, 600, false, 0, 60),
                new GroupTrack("track-root-2", "Root",
                    new TextTrack("track-text", "Caption", new int[]{0, 60},
                        new EffectManager(new ParamEffect("effect-param-2", "Live2D Parameters",
                            new AttrF("live2dParam_ParamAngleY", "Angle Y", "attr-guid-4",
                                new int[]{10, 50}, Map.of(10, 1.0, 50, 2.0),
                                new MutableSequence(
                                    Map.of(10, CurveType.SMOOTH, 50, CurveType.SMOOTH),
                                    Map.of()))))))
            );
        }
    }

    public static class SceneSource {
        private String sceneName;
        private final Id guid;
        private final String tag;
        private final HashMap<Integer, String> marker;
        private final MovieInfo movieInfo;
        private final GroupTrack rootTrack;
        private final SceneInstance sceneInstance;
        private CurveType defaultCurveType = CurveType.LINEAR;

        SceneSource(final String sceneName) {
            this(sceneName, "scene-guid-" + sceneName, null, new LinkedHashMap<>(),
                new MovieInfo(0, 60, 30.0, 800, 600, false, 0, 60),
                new GroupTrack("track-root-" + sceneName, "Root"));
        }

        SceneSource(
            final String sceneName,
            final String guid,
            final String tag,
            final HashMap<Integer, String> marker,
            final MovieInfo movieInfo,
            final GroupTrack rootTrack
        ) {
            this.sceneName = sceneName;
            this.guid = new Id(guid);
            this.tag = tag;
            this.marker = marker;
            this.movieInfo = movieInfo;
            this.rootTrack = rootTrack;
            this.sceneInstance = new SceneInstance(this);
        }

        public List<SceneInstance> sceneInstances() {
            return List.of(sceneInstance);
        }

        public CurveType defaultCurveType() {
            return defaultCurveType;
        }

        public void setDefaultCurveType(final CurveType curveType) {
            this.defaultCurveType = curveType;
        }

        public String sceneName() {
            return sceneName;
        }

        public void setSceneName(final String name) {
            this.sceneName = name;
        }

        public Id guid() {
            return guid;
        }

        public String tag() {
            return tag;
        }

        public HashMap<Integer, String> marker() {
            return marker;
        }

        public MovieInfo movieInfo() {
            return movieInfo;
        }

        public GroupTrack rootTrack() {
            return rootTrack;
        }
    }

    public static final class MovieInfo {
        private final int startFrame;
        private final int duration;
        private final double fps;
        private final int width;
        private final int height;
        private final boolean loopMotion;
        private final int workspaceStart;
        private final int workspaceEnd;

        MovieInfo(
            final int startFrame,
            final int duration,
            final double fps,
            final int width,
            final int height,
            final boolean loopMotion,
            final int workspaceStart,
            final int workspaceEnd
        ) {
            this.startFrame = startFrame;
            this.duration = duration;
            this.fps = fps;
            this.width = width;
            this.height = height;
            this.loopMotion = loopMotion;
            this.workspaceStart = workspaceStart;
            this.workspaceEnd = workspaceEnd;
        }

        public int startFrame() { return startFrame; }
        public int duration() { return duration; }
        public double fps() { return fps; }
        public int width() { return width; }
        public int height() { return height; }
        public boolean isLoopMotion() { return loopMotion; }
        public int workspaceStart() { return workspaceStart; }
        public int workspaceEnd() { return workspaceEnd; }
    }

    public static class TrackSource {
        private final Id guid;
        private final String name;
        private final int[] keyFrames;
        private final EffectManager effectManager;

        TrackSource(
            final String guid,
            final String name,
            final int[] keyFrames,
            final EffectManager effectManager
        ) {
            this.guid = new Id(guid);
            this.name = name;
            this.keyFrames = keyFrames;
            this.effectManager = effectManager;
            for (Effect effect : effectManager.effectList()) {
                for (Attr attribute : effect.attrList()) {
                    attribute.track = this;
                }
            }
        }

        public String name() { return name; }
        public Id guid() { return guid; }
        public int start() { return 0; }
        public int duration() { return 120; }
        public boolean editable() { return true; }
        public boolean visible() { return true; }
        public boolean mute() { return false; }
        public boolean repeat() { return false; }
        public int[] keyFrames() { return keyFrames; }
        public EffectManager effectManager() { return effectManager; }
    }

    public static class GroupTrack extends TrackSource {
        private final List<TrackSource> childTracks;

        GroupTrack(final String guid, final String name, final TrackSource... children) {
            super(guid, name, new int[0], new EffectManager());
            this.childTracks = List.of(children);
        }

        public List<TrackSource> childTracks() {
            return childTracks;
        }
    }

    public static final class ModelTrack extends TrackSource {
        private final ModelSource model;
        private final ResourceFile resourceRef;

        ModelTrack(
            final String guid,
            final String name,
            final int[] keyFrames,
            final ModelSource model,
            final java.io.File srcFile,
            final EffectManager effectManager
        ) {
            super(guid, name, keyFrames, effectManager);
            this.model = model;
            this.resourceRef = new ResourceFile(srcFile);
        }

        public ModelSource model() {
            return model;
        }

        public ResourceFile resourceRef() {
            return resourceRef;
        }
    }

    public static final class ResourceFile {
        private final java.io.File srcFile;

        ResourceFile(final java.io.File srcFile) {
            this.srcFile = srcFile;
        }

        public java.io.File srcFile() {
            return srcFile;
        }
    }

    public static final class SceneTrack extends TrackSource {
        private final Id resourceGuid;

        SceneTrack(final String guid, final String name, final String sceneGuid) {
            super(guid, name, new int[0], new EffectManager());
            this.resourceGuid = new Id(sceneGuid);
        }

        public Id resourceGuid() {
            return resourceGuid;
        }
    }

    public static class ImageTrack extends TrackSource {
        ImageTrack(
            final String guid,
            final String name,
            final int[] keyFrames,
            final EffectManager effectManager
        ) {
            super(guid, name, keyFrames, effectManager);
        }
    }

    public static final class GuideImageTrack extends TrackSource {
        GuideImageTrack(final String guid, final String name) {
            super(guid, name, new int[0], new EffectManager());
        }
    }

    public static final class TextTrack extends TrackSource {
        TextTrack(final String guid, final String name, final int[] keyFrames) {
            this(guid, name, keyFrames, new EffectManager());
        }

        TextTrack(
            final String guid,
            final String name,
            final int[] keyFrames,
            final EffectManager effectManager
        ) {
            super(guid, name, keyFrames, effectManager);
        }
    }

    public static final class Moc3Track extends TrackSource {
        Moc3Track(final String guid, final String name) {
            super(guid, name, new int[0], new EffectManager());
        }
    }

    public static final class SoundTrack extends TrackSource {
        SoundTrack(final String guid, final String name, final int[] keyFrames) {
            super(guid, name, keyFrames, new EffectManager());
        }
    }

    public static final class EffectManager {
        private final Effect[] effectList;

        EffectManager(final Effect... effects) {
            this.effectList = effects;
        }

        public Effect[] effectList() {
            return effectList;
        }
    }

    public static class Effect {
        private final Id id;
        private final String name;
        private final Attr[] attrList;

        Effect(final String id, final String name, final Attr... attrs) {
            this.id = new Id(id);
            this.name = name;
            this.attrList = attrs;
        }

        public Id id() { return id; }
        public String name() { return name; }
        public boolean active() { return true; }
        public Attr[] attrList() { return attrList; }
    }

    public static final class ParamEffect extends Effect {
        ParamEffect(final String id, final String name, final Attr... attrs) {
            super(id, name, attrs);
        }
    }

    public static class Attr {
        private final Id id;
        private final String name;
        private final Id guid;
        final TreeMap<Integer, Object> values;
        TrackSource track;
        boolean failWrites;

        Attr(
            final String id,
            final String name,
            final String guid,
            final int[] keyFrames,
            final Map<Integer, Object> values
        ) {
            this.id = new Id(id);
            this.name = name;
            this.guid = new Id(guid);
            this.values = new TreeMap<>(values);
        }

        public Id id() { return id; }
        public String name() { return name; }
        public Id guid() { return guid; }
        public boolean active() { return true; }
        public boolean editable() { return true; }

        public int[] keyFrames() {
            return values.keySet().stream().mapToInt(Integer::intValue).toArray();
        }

        public Object value(final int frame) { return values.get(frame); }
        public TrackSource track() { return track; }

        public void setValueAuto(final int frame, final Object value) {
            failIfRequested();
            values.put(frame, value);
        }

        public void removeValueAuto(final int frame) {
            values.remove(frame);
        }

        void failIfRequested() {
            if (failWrites) {
                throw new IllegalStateException("host mutation rejected");
            }
        }

        Object snapshot() {
            return new TreeMap<>(values);
        }

        void restore(final Object snapshot) {
            values.clear();
            @SuppressWarnings("unchecked")
            final TreeMap<Integer, Object> state = (TreeMap<Integer, Object>) snapshot;
            values.putAll(state);
        }
    }

    public static final class AttrF extends Attr {
        private final MutableSequence valueData;
        private boolean readOnly;

        AttrF(
            final String id,
            final String name,
            final String guid,
            final int[] keyFrames,
            final Map<Integer, Object> values,
            final MutableSequence valueData
        ) {
            super(id, name, guid, keyFrames, values);
            this.valueData = valueData;
        }

        public MutableSequence valueData() {
            return valueData;
        }

        public boolean isReadOnly() {
            return readOnly;
        }

        public void setValueAndCurveType(
            final int frame,
            final double value,
            final CurveType curveType
        ) {
            failIfRequested();
            values.put(frame, value);
            valueData.curveTypes.put(frame, curveType);
            valueData.points.computeIfAbsent(frame, key -> new BezierPt(
                new CtrlPt(frame - 5.0F, value, false), new CtrlPt(frame + 5.0F, value, false)));
        }

        @Override public void setValueAuto(final int frame, final Object value) {
            failIfRequested();
            values.put(frame, value);
            valueData.curveTypes.putIfAbsent(frame, CurveType.LINEAR);
            valueData.points.computeIfAbsent(frame, key -> new BezierPt(
                new CtrlPt(frame - 5.0F, ((Number) value).doubleValue(), false),
                new CtrlPt(frame + 5.0F, ((Number) value).doubleValue(), false)));
        }

        @Override public void removeValueAuto(final int frame) {
            values.remove(frame);
            valueData.curveTypes.remove(frame);
            valueData.points.remove(frame);
        }

        @Override Object snapshot() {
            return new Object[]{new TreeMap<>(values), valueData.snapshot()};
        }

        @Override void restore(final Object snapshot) {
            final Object[] state = (Object[]) snapshot;
            values.clear();
            @SuppressWarnings("unchecked")
            final TreeMap<Integer, Object> restored = (TreeMap<Integer, Object>) state[0];
            values.putAll(restored);
            valueData.restore(state[1]);
        }
    }

    public static final class AttrI extends Attr {
        AttrI(
            final String id,
            final String name,
            final String guid,
            final int[] keyFrames,
            final Map<Integer, Object> values
        ) {
            super(id, name, guid, keyFrames, values);
        }

        public void setValueAuto(final int frame, final double value) {
            failIfRequested();
            values.put(frame, value);
        }
    }

    public static final class AttrPt extends Attr {
        AttrPt(
            final String id,
            final String name,
            final String guid,
            final int[] keyFrames,
            final Map<Integer, Object> values
        ) {
            super(id, name, guid, keyFrames, values);
        }

        public void setValueAuto(final int frame, final float x, final float y) {
            failIfRequested();
            values.put(frame, new GVector2(x, y));
        }
    }

    public static final class MutableSequence {
        final Map<Integer, CurveType> curveTypes;
        final Map<Integer, BezierPt> points;
        int forceUpdates;
        boolean failWrites;

        MutableSequence(
            final Map<Integer, CurveType> curveTypes,
            final Map<Integer, BezierPt> points
        ) {
            this.curveTypes = new LinkedHashMap<>(curveTypes);
            this.points = new LinkedHashMap<>(points);
        }

        public CurveType curveType(final int frame) {
            return curveTypes.get(frame);
        }

        public BezierPt point(final int frame) {
            return points.get(frame);
        }

        public void setCurveType(final int frame, final CurveType curveType) {
            if (failWrites) {
                throw new IllegalStateException("host mutation rejected");
            }
            curveTypes.put(frame, curveType);
        }

        public void forceUpdate() {
            forceUpdates++;
        }

        Object snapshot() {
            final Map<Integer, BezierPt> pointCopies = new LinkedHashMap<>();
            points.forEach((frame, point) -> pointCopies.put(frame, new BezierPt(
                point.prev() == null ? null : point.prev().copy(),
                point.next() == null ? null : point.next().copy())));
            return new Object[]{new LinkedHashMap<>(curveTypes), pointCopies};
        }

        void restore(final Object snapshot) {
            final Object[] state = (Object[]) snapshot;
            curveTypes.clear();
            @SuppressWarnings("unchecked")
            final Map<Integer, CurveType> restoredTypes =
                (Map<Integer, CurveType>) state[0];
            curveTypes.putAll(restoredTypes);
            points.clear();
            @SuppressWarnings("unchecked")
            final Map<Integer, BezierPt> restoredPoints =
                (Map<Integer, BezierPt>) state[1];
            points.putAll(restoredPoints);
        }
    }

    public static final class BezierPt {
        private final CtrlPt prev;
        private final CtrlPt next;

        BezierPt(final CtrlPt prev, final CtrlPt next) {
            this.prev = prev;
            this.next = next;
        }

        public CtrlPt prev() {
            return prev;
        }

        public CtrlPt next() {
            return next;
        }
    }

    public static final class CtrlPt {
        private float posF;
        private double value;
        private boolean corner;

        CtrlPt(final float posF, final double value, final boolean corner) {
            this.posF = posF;
            this.value = value;
            this.corner = corner;
        }

        public float posF() { return posF; }
        public double doubleValue() { return value; }
        public boolean corner() { return corner; }
        public void setPos(final float pos) { this.posF = pos; }
        public void setValue(final double newValue) { this.value = newValue; }
        public void setCorner(final boolean isCorner) { this.corner = isCorner; }

        CtrlPt copy() {
            return new CtrlPt(posF, value, corner);
        }
    }

    public enum CurveType {
        LINEAR,
        BEZIER,
        SMOOTH,
        STEP,
        INVERSE_STEP
    }

    public static final class ViewContext {
    }

    public static final class EvalFlags {
        private final boolean includePreview;

        public EvalFlags(final boolean includePreview) {
            this.includePreview = includePreview;
        }
    }

    public static final class SceneTime {
        private int frame;

        public int frame() {
            return frame;
        }

        public void frame(final int newFrame) {
            this.frame = newFrame;
        }
    }

    public static final class SceneInstance {
        private final SceneTime currentTime = new SceneTime();
        private final GroupTrackInstance rootTrack;

        SceneInstance(final SceneSource source) {
            final List<TrackInstance> children = new ArrayList<>();
            for (TrackSource child : source.rootTrack().childTracks()) {
                children.add(child instanceof ModelTrack model
                    ? new ModelTrackInstance(model)
                    : new TrackInstance(child));
            }
            this.rootTrack = new GroupTrackInstance(source.rootTrack(), children);
        }

        public SceneTime currentTime() {
            return currentTime;
        }

        public GroupTrackInstance rootTrack() {
            return rootTrack;
        }

        public List<TrackInstance> allTracks() {
            return rootTrack.children;
        }
    }

    public static class TrackInstance {
        private final TrackSource source;

        TrackInstance(final TrackSource source) {
            this.source = source;
        }

        public TrackSource source() {
            return source;
        }

        public void update(final Object viewContext, final SceneTime time, final EvalFlags flags) {
        }
    }

    public static final class GroupTrackInstance extends TrackInstance {
        private final List<TrackInstance> children;

        GroupTrackInstance(final TrackSource source, final List<TrackInstance> children) {
            super(source);
            this.children = children;
        }

        @Override public void update(
            final Object viewContext,
            final SceneTime time,
            final EvalFlags flags
        ) {
            for (TrackInstance child : children) {
                child.update(viewContext, time, flags);
            }
        }
    }

    public static final class ModelTrackInstance extends TrackInstance {
        private final ParameterSet parameterSet = new ParameterSet();
        int evaluations;

        ModelTrackInstance(final TrackSource source) {
            super(source);
        }

        public ParameterSet parameterSet() {
            return parameterSet;
        }

        @Override public void update(
            final Object viewContext,
            final SceneTime time,
            final EvalFlags flags
        ) {
            evaluations++;
            parameterSet.parameters().get(0).value = time.frame() * 0.5F;
        }
    }
}
