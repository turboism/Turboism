package dev.turboism.adapter.cubism.editor;

import static dev.turboism.adapter.cubism.editor.EditorDocumentTestFixtures.*;
import static dev.turboism.adapter.cubism.editor.EditorTestFixtures.Id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.editor.transaction.RuntimeAuthoringTransactionProvider;
import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorAnimationReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationSceneOperationSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineEditSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAnimationTimelineReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorAutoYureReadSelectorContract;
import dev.turboism.mapping.verification.selector.EditorPhysicsReadSelectorContract;
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
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOutcome;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

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
        final dev.turboism.sdk.cubism.model.PhysicsSettingsSource source =
                settings.sources().get(0);
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
        assertEquals(
                dev.turboism.sdk.cubism.model.YureRootDirection.TOP,
                binding.config().rootDirection());
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
        final var model = new EditorBackedCubismModelAccess(shallowAnimationResolver(version), "session-a").active();

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
                                Host.class.getClassLoader()),
                        "session-a")
                .active();

        final AnimationDocument animation = model.animationDocuments().get(0);
        assertThrows(UnsupportedOperationException.class, animation::scenes);
    }

    /**
     * Fail-closed inside an ambient transaction: timeline writes run through the
     * scene-document {@code edit-mode-base} bracket, a different edit domain that cannot be
     * admitted into the modeling root edit, so the write rejects before any native edit begins.
     */
    @Test
    void timelineWriteInsideTransactionRejectsWithoutDetachedUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a");
        final var model = access.active();
        final var service = ((RuntimeAuthoringTransactionProvider) access).authoringTransactions("plugin.test");
        final AnimationAttribute parameter = scene1Parameter(model);
        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        final int beganBefore = sceneDocument.editMode.began;

        final var result = service.execute(AuthoringTransactionOptions.of("Rejected timeline write"), () -> {
            final EditorAmbientTransactionRejection rejection = assertThrows(
                    EditorAmbientTransactionRejection.class,
                    () -> parameter.setKeyframe(45, 0.75),
                    "edit-mode-base envelopes must stay fail-closed inside a transaction");
            assertTrue(rejection.getMessage().contains("detached Undo group"));
            return null;
        });

        assertEquals(AuthoringTransactionOutcome.NO_CHANGE, result.outcome());
        assertEquals(beganBefore, sceneDocument.editMode.began, "no scene edit-mode bracket may begin");
        assertTrue(fixture.document.undoManager().entries().isEmpty(), "no detached Undo entry may be committed");
    }

    @Test
    void writesScalarKeyframeThroughNativeUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
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
        assertEquals(
                AnimationCurveType.LINEAR,
                reloaded.keyframes().get(2).curveType().orElseThrow());
    }

    @Test
    void writesScalarKeyframeWithExplicitCurveType() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();

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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute point = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);

        point.setKeyframe(60, 3.5F, -2.0F);

        final var reloaded = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);
        assertEquals(List.of(0, 60, 120), frames(reloaded));
        assertEquals(
                3.5F, reloaded.keyframes().get(1).pointValue().orElseThrow().x());
        assertEquals(
                -2.0F, reloaded.keyframes().get(1).pointValue().orElseThrow().y());
    }

    @Test
    void removesKeyframeAndIgnoresMissingFrames() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();

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
        assertEquals(
                AnimationCurveType.LINEAR,
                reloaded.keyframes().get(0).curveType().orElseThrow());
    }

    @ParameterizedTest
    @CsvSource({"2.0, 0, 0, 60, 120, 49.0, 72.0, true", "0.5, 20, 10, 25, 40, 22.25, 28.0, false"})
    void scalesKeyframeTimesAroundGivenOrigin(
            final double factor,
            final int origin,
            final int f0,
            final int f1,
            final int f2,
            final float inHandle,
            final float outHandle,
            final boolean checkLinear) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();

        final int scaled = scene1Parameter(model).scaleKeyframeTimes(factor, origin);

        assertEquals(3, scaled);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(f0, f1, f2), frames(reloaded));
        final var bezierKey = reloaded.keyframes().get(1);
        assertEquals(0.5, bezierKey.value().orElseThrow());
        assertEquals(AnimationCurveType.BEZIER, bezierKey.curveType().orElseThrow());
        assertEquals(inHandle, bezierKey.inHandle().orElseThrow().frame());
        assertEquals(0.4, bezierKey.inHandle().orElseThrow().value());
        assertEquals(outHandle, bezierKey.outHandle().orElseThrow().frame());
        assertEquals(0.6, bezierKey.outHandle().orElseThrow().value());
        assertTrue(bezierKey.outHandle().orElseThrow().corner());
        if (checkLinear) {
            assertEquals(
                    AnimationCurveType.LINEAR,
                    reloaded.keyframes().get(0).curveType().orElseThrow());
        }
    }

    @Test
    void scaleKeyframeTimesPreservesHandlePrecisionAtLargeOrigins() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();

        // Identity scaling is a no-op even at origins that are not exactly
        // representable as float; the affine math must not round origin or
        // handle offsets through float before scaling.
        for (final int origin : List.of(16777217, -16777217)) {
            final int scaled = scene1Parameter(model).scaleKeyframeTimes(1.0, origin);

            assertEquals(3, scaled);
            final AnimationAttribute reloaded = scene1Parameter(model);
            assertEquals(List.of(0, 30, 60), frames(reloaded));
            final var bezierKey = reloaded.keyframes().get(1);
            assertEquals(24.5F, bezierKey.inHandle().orElseThrow().frame());
            assertEquals(36.0F, bezierKey.outHandle().orElseThrow().frame());
            assertEquals(0.4, bezierKey.inHandle().orElseThrow().value());
            assertEquals(0.6, bezierKey.outHandle().orElseThrow().value());
            assertTrue(bezierKey.outHandle().orElseThrow().corner());
        }
    }

    @Test
    void scaleKeyframeTimesRollsBackKeysAndHandlesWhenMutationFails() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final Attr host = hostAttribute(fixture, 0);
        host.failWrites = true;

        // The transform removes every source key before re-inserting; the
        // failing insert must roll keys and stored bezier handles back through
        // the cancelled edit.
        assertThrows(
                dev.turboism.mapping.verification.VerifiedAccessException.class,
                () -> parameter.scaleKeyframeTimes(2.0, 0));

        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertTrue(sceneDocument.editMode.lastCancelled);
        assertFalse(sceneDocument.modified);
        assertEquals(Map.of(0, 0.0, 30, 0.5, 60, 1.0), host.values);
        final var restoredPoint = ((AttrF) host).valueData().point(30);
        assertEquals(24.5F, restoredPoint.prev().posF());
        assertEquals(0.4, restoredPoint.prev().doubleValue());
        assertEquals(36.0F, restoredPoint.next().posF());
        assertEquals(0.6, restoredPoint.next().doubleValue());
        assertTrue(restoredPoint.next().corner());
        assertEquals(
                AnimationCurveType.BEZIER,
                scene1Parameter(model).keyframes().get(1).curveType().orElseThrow());
    }

    @Test
    void quantizesKeyframesCollapsingCollisionsInInsertionOrder() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();

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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute source = model.animationDocuments()
                .get(0)
                .scenes()
                .get(1)
                .tracks()
                .get(0)
                .attributes()
                .get(0);
        assertEquals(AnimationAttributeKind.FLOAT, source.kind());

        final int copied = scene1Parameter(model).copyKeyframesFrom(source, true);

        assertEquals(2, copied);
        final AnimationAttribute reloaded = scene1Parameter(model);
        assertEquals(List.of(10, 50), frames(reloaded));
        assertEquals(1.0, reloaded.keyframes().get(0).value().orElseThrow());
        assertEquals(2.0, reloaded.keyframes().get(1).value().orElseThrow());
        assertEquals(
                AnimationCurveType.SMOOTH,
                reloaded.keyframes().get(0).curveType().orElseThrow());
    }

    @Test
    void copyKeyframesRejectsMismatchedKinds() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute point = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);

        assertThrows(
                IllegalArgumentException.class, () -> scene1Parameter(model).copyKeyframesFrom(point, false));
    }

    @Test
    void renamesSceneThroughNativeUndoEnvelope() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final Attr host = hostAttribute(fixture, 0);
        host.failWrites = true;

        // The offset removes every source key before re-inserting; the failing
        // insert must roll the removal back through the cancelled edit.
        assertThrows(
                dev.turboism.mapping.verification.VerifiedAccessException.class, () -> parameter.offsetKeyframes(10));

        final SceneDocument sceneDocument = sceneDocument(fixture, 0);
        assertTrue(sceneDocument.editMode.lastCancelled);
        assertFalse(sceneDocument.modified);
        assertEquals(Map.of(0, 0.0, 30, 0.5, 60, 1.0), host.values);
    }

    @Test
    void timelineWritesFailClosedWithoutWriteCapability() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(resolver("5.3.02"), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);
        final AnimationAttribute parameter = scene.tracks().get(0).attributes().get(0);

        assertThrows(UnsupportedOperationException.class, () -> parameter.setKeyframe(15, 1.0));
        assertThrows(UnsupportedOperationException.class, () -> parameter.removeKeyframe(30));
        assertThrows(UnsupportedOperationException.class, () -> parameter.offsetKeyframes(5));
        assertThrows(UnsupportedOperationException.class, () -> scene.rename("Nope"));
    }

    @Test
    void timelineWritesFailClosedWhenWriteAliasIsMissing() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final List<StaticSelector> reduced = selectors().stream()
                .filter(selector -> !"cubism.editor-model.attr.set-value-auto".equals(selector.alias()))
                .toList();
        final var model = new EditorBackedCubismModelAccess(
                        TestVerifiedResolvers.create(
                                "5.3.02",
                                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                                java.util.Set.of(
                                        EditorAnimationReadSelectorContract.CAPABILITY_ID,
                                        EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID,
                                        EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID),
                                reduced,
                                Host.class.getClassLoader()),
                        "session-a")
                .active();
        final AnimationAttribute parameter = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(0)
                .attributes()
                .get(0);

        assertThrows(UnsupportedOperationException.class, () -> parameter.setKeyframe(15, 1.0));
    }

    @Test
    void playheadReadsAndSeekRepaintsWithoutUndo() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);
        final SceneDocument document = sceneDocument(fixture, 0);
        final SceneInstance instance = document.sceneSource().sceneInstances().get(0);

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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final List<AnimationScene> scenes = model.animationDocuments().get(0).scenes();
        final AnimationFileContent fileContent =
                Host.instance().currentProject().animation();
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AnimationAttribute integer = model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(0)
                .attributes()
                .get(1);

        assertThrows(IllegalArgumentException.class, () -> parameter.applyCurveType(AnimationCurveType.SMOOTH, 30, 0));
        assertThrows(IllegalArgumentException.class, () -> integer.applyCurveType(AnimationCurveType.SMOOTH));
    }

    @Test
    void recordKeyframeWritesCurrentEvaluatedValue() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        // Scene 2's parameter attribute rides a text track — no model instance.
        final AnimationAttribute parameter = model.animationDocuments()
                .get(0)
                .scenes()
                .get(1)
                .tracks()
                .get(0)
                .attributes()
                .get(0);

        assertThrows(IllegalStateException.class, () -> parameter.recordKeyframe(10, AnimationCurveType.LINEAR));
    }

    @Test
    void bakeEvaluatedStepsThroughRangeAndRestoresPlayhead() {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);
        final AttrF attribute = (AttrF) hostAttribute(fixture, 0);
        final SceneDocument document = sceneDocument(fixture, 0);
        final SceneInstance instance = document.sceneSource().sceneInstances().get(0);
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
        final var model = new EditorBackedCubismModelAccess(writeResolver("5.3.02"), "session-a").active();
        final AnimationAttribute parameter = scene1Parameter(model);

        assertThrows(
                IllegalArgumentException.class, () -> parameter.bakeEvaluated(10, 0, 1, AnimationCurveType.LINEAR));
        assertThrows(
                IllegalArgumentException.class, () -> parameter.bakeEvaluated(0, 10, 0, AnimationCurveType.LINEAR));
    }

    @ParameterizedTest
    @ValueSource(strings = {"playback", "sceneEdit", "eval"})
    void sceneCapabilitiesFailClosedWithoutCapability(final String capability) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final String capabilityId;
        if ("playback".equals(capability)) {
            capabilityId = EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID;
        } else if ("sceneEdit".equals(capability)) {
            capabilityId = EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID;
        } else {
            capabilityId = EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID;
        }
        final var model =
                new EditorBackedCubismModelAccess(writeResolverWithout("5.3.02", capabilityId), "session-a").active();
        final AnimationScene scene = model.animationDocuments().get(0).scenes().get(0);
        if ("playback".equals(capability)) {
            assertThrows(UnsupportedOperationException.class, scene::playheadFrame);
            assertThrows(UnsupportedOperationException.class, () -> scene.seekTo(10));
        } else if ("sceneEdit".equals(capability)) {
            assertThrows(UnsupportedOperationException.class, scene::current);
            assertThrows(UnsupportedOperationException.class, scene::activate);
            assertThrows(UnsupportedOperationException.class, scene::defaultCurveType);
            assertThrows(UnsupportedOperationException.class, () -> scene.setDefaultCurveType(AnimationCurveType.STEP));
        } else {
            final AnimationAttribute parameter = scene1Parameter(model);
            assertThrows(
                    UnsupportedOperationException.class, () -> parameter.recordKeyframe(10, AnimationCurveType.LINEAR));
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> parameter.bakeEvaluated(0, 10, 5, AnimationCurveType.LINEAR));
        }
    }

    private static AnimationAttribute scene1Parameter(final dev.turboism.sdk.cubism.model.CubismModel model) {
        // FLOAT attribute "Angle X" on Scene 1's model track.
        return model.animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(0)
                .attributes()
                .get(0);
    }

    private static SceneDocument sceneDocument(final Fixture fixture, final int index) {
        return Host.instance().currentProject().animation().sceneDocs().get(index);
    }

    private static Attr hostAttribute(final Fixture fixture, final int sceneIndex) {
        final GroupTrack root =
                (GroupTrack) sceneDocument(fixture, sceneIndex).sceneSource().rootTrack();
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

    private static VerifiedMemberResolver writeResolverWithout(final String version, final String excludedCapability) {
        final java.util.HashSet<String> capabilities = new java.util.HashSet<>();
        capabilities.add(EditorPhysicsReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAutoYureReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationTimelineReadSelectorContract.CAPABILITY_ID);
        capabilities.add(EditorAnimationTimelineEditSelectorContract.WRITE_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.PLAYBACK_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.SCENE_EDIT_CAPABILITY_ID);
        capabilities.add(EditorAnimationSceneOperationSelectorContract.EVAL_CAPABILITY_ID);
        capabilities.add("cubism.editor-history.read");
        capabilities.remove(excludedCapability);
        return TestVerifiedResolvers.create(
                version,
                EditorPhysicsReadSelectorContract.ADAPTER_SLICE_ID,
                capabilities,
                selectors(),
                Host.class.getClassLoader());
    }

    @ParameterizedTest
    @ValueSource(strings = {"5.2.03", "5.3.02"})
    void documentReadsFailClosedWithoutExactCapabilityEvidence(final String version) {
        final Fixture fixture = new Fixture();
        Host.document = fixture.document;
        final var access = new EditorBackedCubismModelAccess(resolver(version, false), "session-a");
        final var model = access.active();
        assertThrows(UnsupportedOperationException.class, model::physicsSettings);
        assertThrows(UnsupportedOperationException.class, model::autoYure);
        assertThrows(UnsupportedOperationException.class, model::animationDocuments);
    }

    private static VerifiedMemberResolver resolver(final String version) {
        return resolver(version, true);
    }

    private static VerifiedMemberResolver resolver(final String version, final boolean includeDocumentReads) {
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
                Host.class.getClassLoader());
    }

    private static VerifiedMemberResolver shallowAnimationResolver(final String version) {
        return TestVerifiedResolvers.create(
                version,
                EditorAnimationReadSelectorContract.ADAPTER_SLICE_ID,
                java.util.Set.of(EditorAnimationReadSelectorContract.CAPABILITY_ID),
                selectors(),
                Host.class.getClassLoader());
    }

    private static List<StaticSelector> selectors() {
        final List<StaticSelector> values = new ArrayList<>();
        values.add(StaticSelector.classSelector("cubism.editor-model.app-controller.class", internal(Host.class)));
        values.add(StaticSelector.staticMethod(
                "cubism.editor-model.app-controller.instance",
                internal(Host.class),
                "instance",
                "()L" + internal(Host.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method(
                "cubism.editor-model.app-controller.current-document",
                Host.class,
                "currentDocument",
                "()L" + internal(Document.class) + ";"));
        values.add(
                StaticSelector.classSelector("cubism.editor-model.modeling-document.class", internal(Document.class)));
        values.add(method(
                "cubism.editor-model.modeling-document.model-source",
                Document.class,
                "modelSource",
                "()L" + internal(ModelSource.class) + ";"));
        values.add(method("cubism.editor-model.file-content.file", Document.class, "file", "()Ljava/io/File;"));
        values.add(method(
                "cubism.editor-model.app-controller.current-project",
                Host.class,
                "currentProject",
                "()L" + internal(Project.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.project.class", internal(Project.class)));
        values.add(method("cubism.editor-model.project.children", Project.class, "children", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model-source.class", internal(ModelSource.class)));
        values.add(method(
                "cubism.editor-model.model-source.guid", ModelSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method(
                "cubism.editor-model.model-source.current-instance",
                ModelSource.class,
                "currentInstance",
                "()L" + internal(Model.class) + ";"));
        values.add(method(
                "cubism.editor-model.model-source.all-deformers",
                ModelSource.class,
                "allDeformers",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.model-source.physics-settings-source-set",
                ModelSource.class,
                "physicsSettingsSourceSet",
                "()L" + internal(PhysicsSettingsSourceSet.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.model.class", internal(Model.class)));
        values.add(method(
                "cubism.editor-model.model.parameter-set",
                Model.class,
                "parameterSet",
                "()L" + internal(ParameterSet.class) + ";"));
        values.add(
                StaticSelector.classSelector("cubism.editor-model.parameter-set.class", internal(ParameterSet.class)));
        values.add(method(
                "cubism.editor-model.parameter-set.parameters",
                ParameterSet.class,
                "parameters",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.parameter-controllable-source.id",
                ObjectSource.class,
                "id",
                "()L" + internal(Id.class) + ";"));
        values.add(method(
                "cubism.editor-model.parameter-controllable-source.extensions",
                ObjectSource.class,
                "extensions",
                "()Ljava/util/List;"));
        values.add(
                StaticSelector.classSelector("cubism.editor-model.parameter.class", internal(ParameterHolder.class)));
        values.add(method(
                "cubism.editor-model.parameter.source",
                ParameterHolder.class,
                "source",
                "()L" + internal(ParameterSource.class) + ";"));
        values.add(method(
                "cubism.editor-model.parameter.id", ParameterHolder.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method(
                "cubism.editor-model.parameter-source.guid",
                ParameterSource.class,
                "guid",
                "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.id.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.guid.value", Id.class, "value", "()Ljava/lang/String;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.warp-source.class", internal(WarpSource.class)));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.auto-yure-config-extension.class", internal(AutoYureConfigExtension.class)));
        values.add(method(
                "cubism.editor-model.auto-yure-config-extension.param-to-config-map",
                AutoYureConfigExtension.class,
                "paramToConfigMap",
                "()Ljava/util/Map;"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.auto-yure-config.class", internal(AutoYureConfig.class)));
        values.add(method(
                "cubism.editor-model.auto-yure-config.left",
                AutoYureConfig.class,
                "left",
                "()L" + internal(YureDeformConfig.class) + ";"));
        values.add(method(
                "cubism.editor-model.auto-yure-config.right",
                AutoYureConfig.class,
                "right",
                "()L" + internal(YureDeformConfig.class) + ";"));
        values.add(method(
                "cubism.editor-model.auto-yure-config.sync-left-right", AutoYureConfig.class, "syncLeftRight", "()Z"));
        values.add(method(
                "cubism.editor-model.auto-yure-config.root-direction",
                AutoYureConfig.class,
                "rootDirection",
                "()L" + internal(YureRootDirection.class) + ";"));
        values.add(method("cubism.editor-model.auto-yure-config.flip", AutoYureConfig.class, "isFlip", "()Z"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.auto-yure-config-root-direction.class", internal(YureRootDirection.class)));
        values.add(StaticSelector.field(
                "cubism.editor-model.auto-yure-config-root-direction.top",
                internal(YureRootDirection.class),
                "TOP",
                "L" + internal(YureRootDirection.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.auto-yure-config-root-direction.right",
                internal(YureRootDirection.class),
                "RIGHT",
                "L" + internal(YureRootDirection.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.auto-yure-config-root-direction.bottom",
                internal(YureRootDirection.class),
                "BOTTOM",
                "L" + internal(YureRootDirection.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.auto-yure-config-root-direction.left",
                internal(YureRootDirection.class),
                "LEFT",
                "L" + internal(YureRootDirection.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.yure-deform-config.class", internal(YureDeformConfig.class)));
        values.add(method(
                "cubism.editor-model.yure-deform-config.scale-percent-x",
                YureDeformConfig.class,
                "scalePercentX",
                "()F"));
        values.add(method(
                "cubism.editor-model.yure-deform-config.scale-percent-y",
                YureDeformConfig.class,
                "scalePercentY",
                "()F"));
        values.add(method(
                "cubism.editor-model.yure-deform-config.expand-scale", YureDeformConfig.class, "expandScale", "()F"));
        values.add(method(
                "cubism.editor-model.yure-deform-config.decay-level", YureDeformConfig.class, "decayLevel", "()D"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.physics-settings-source-set.class", internal(PhysicsSettingsSourceSet.class)));
        values.add(method(
                "cubism.editor-model.physics-settings-source-set.gravity",
                PhysicsSettingsSourceSet.class,
                "gravity",
                "()L" + internal(GVector2.class) + ";"));
        values.add(method(
                "cubism.editor-model.physics-settings-source-set.wind",
                PhysicsSettingsSourceSet.class,
                "wind",
                "()L" + internal(GVector2.class) + ";"));
        values.add(method(
                "cubism.editor-model.physics-settings-source-set.setting-fps",
                PhysicsSettingsSourceSet.class,
                "settingFps",
                "()Ljava/lang/Integer;"));
        values.add(method(
                "cubism.editor-model.physics-settings-source-set.sources",
                PhysicsSettingsSourceSet.class,
                "sources",
                "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.physics-settings-source.class", internal(PhysicsSettingsSourceDoc.class)));
        values.add(method(
                "cubism.editor-model.physics-settings-source.id",
                PhysicsSettingsSourceDoc.class,
                "id",
                "()L" + internal(Id.class) + ";"));
        values.add(method(
                "cubism.editor-model.physics-settings-source.name",
                PhysicsSettingsSourceDoc.class,
                "name",
                "()Ljava/lang/String;"));
        values.add(method(
                "cubism.editor-model.physics-settings-source.total-angle",
                PhysicsSettingsSourceDoc.class,
                "totalAngle",
                "()F"));
        values.add(method(
                "cubism.editor-model.physics-settings-source.inputs",
                PhysicsSettingsSourceDoc.class,
                "inputs",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.physics-settings-source.outputs",
                PhysicsSettingsSourceDoc.class,
                "outputs",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.physics-settings-source.vertices",
                PhysicsSettingsSourceDoc.class,
                "vertices",
                "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.vector2.class", internal(GVector2.class)));
        values.add(method("cubism.editor-model.vector2.x", GVector2.class, "x", "()F"));
        values.add(method("cubism.editor-model.vector2.y", GVector2.class, "y", "()F"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.animation-file-content.class", internal(AnimationFileContent.class)));
        values.add(method(
                "cubism.editor-model.animation-file-content.animation",
                AnimationFileContent.class,
                "animation",
                "()L" + internal(Animation.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.animation.class", internal(Animation.class)));
        values.add(method("cubism.editor-model.animation.name", Animation.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.animation.scenes", Animation.class, "scenes", "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.animation.current-scene",
                Animation.class,
                "currentScene",
                "()L" + internal(SceneSource.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.scene-source.class", internal(SceneSource.class)));
        values.add(method(
                "cubism.editor-model.scene-source.scene-name", SceneSource.class, "sceneName", "()Ljava/lang/String;"));
        values.add(method(
                "cubism.editor-model.scene-source.guid", SceneSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.scene-source.tag", SceneSource.class, "tag", "()Ljava/lang/String;"));
        values.add(method(
                "cubism.editor-model.scene-source.marker", SceneSource.class, "marker", "()Ljava/util/HashMap;"));
        values.add(method(
                "cubism.editor-model.scene-source.movie-info",
                SceneSource.class,
                "movieInfo",
                "()L" + internal(MovieInfo.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-source.root-track",
                SceneSource.class,
                "rootTrack",
                "()L" + internal(GroupTrack.class) + ";"));
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
        values.add(method(
                "cubism.editor-model.track-source.guid", TrackSource.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.track-source.start", TrackSource.class, "start", "()I"));
        values.add(method("cubism.editor-model.track-source.duration", TrackSource.class, "duration", "()I"));
        values.add(method("cubism.editor-model.track-source.editable", TrackSource.class, "editable", "()Z"));
        values.add(method("cubism.editor-model.track-source.visible", TrackSource.class, "visible", "()Z"));
        values.add(method("cubism.editor-model.track-source.mute", TrackSource.class, "mute", "()Z"));
        values.add(method("cubism.editor-model.track-source.repeat", TrackSource.class, "repeat", "()Z"));
        values.add(method("cubism.editor-model.track-source.key-frames", TrackSource.class, "keyFrames", "()[I"));
        values.add(method(
                "cubism.editor-model.track-source.effect-manager",
                TrackSource.class,
                "effectManager",
                "()L" + internal(EffectManager.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-group.class", internal(GroupTrack.class)));
        values.add(method(
                "cubism.editor-model.track-group.children", GroupTrack.class, "childTracks", "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-model.class", internal(ModelTrack.class)));
        values.add(method(
                "cubism.editor-model.track-model.model",
                ModelTrack.class,
                "model",
                "()L" + internal(ModelSource.class) + ";"));
        values.add(method(
                "cubism.editor-model.track-model.resource-ref",
                ModelTrack.class,
                "resourceRef",
                "()L" + internal(ResourceFile.class) + ";"));
        values.add(method(
                "cubism.editor-model.resource-file.src-file", ResourceFile.class, "srcFile", "()Ljava/io/File;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-scene.class", internal(SceneTrack.class)));
        values.add(method(
                "cubism.editor-model.track-scene.resource-scene-guid",
                SceneTrack.class,
                "resourceGuid",
                "()L" + internal(Id.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-image.class", internal(ImageTrack.class)));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.track-guide-image.class", internal(GuideImageTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-text.class", internal(TextTrack.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-moc3.class", internal(Moc3Track.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.track-sound.class", internal(SoundTrack.class)));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.effect-manager.class", internal(EffectManager.class)));
        values.add(method(
                "cubism.editor-model.effect-manager.effects",
                EffectManager.class,
                "effectList",
                "()[L" + internal(Effect.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.effect.class", internal(Effect.class)));
        values.add(method("cubism.editor-model.effect.id", Effect.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method(
                "cubism.editor-model.effect.attrs", Effect.class, "attrList", "()[L" + internal(Attr.class) + ";"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.effect-parameter.class", internal(ParamEffect.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr.class", internal(Attr.class)));
        values.add(method("cubism.editor-model.attr.id", Attr.class, "id", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.attr.name", Attr.class, "name", "()Ljava/lang/String;"));
        values.add(method("cubism.editor-model.attr.guid", Attr.class, "guid", "()L" + internal(Id.class) + ";"));
        values.add(method("cubism.editor-model.attr.active", Attr.class, "active", "()Z"));
        values.add(method("cubism.editor-model.attr.editable", Attr.class, "editable", "()Z"));
        values.add(method("cubism.editor-model.attr.key-frames", Attr.class, "keyFrames", "()[I"));
        values.add(method("cubism.editor-model.attr.value", Attr.class, "value", "(I)Ljava/lang/Object;"));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-f.class", internal(AttrF.class)));
        values.add(method(
                "cubism.editor-model.attr-f.value-data",
                AttrF.class,
                "valueData",
                "()L" + internal(MutableSequence.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-i.class", internal(AttrI.class)));
        values.add(StaticSelector.classSelector("cubism.editor-model.attr-pt.class", internal(AttrPt.class)));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.mutable-sequence.class", internal(MutableSequence.class)));
        values.add(method(
                "cubism.editor-model.mutable-sequence.curve-type",
                MutableSequence.class,
                "curveType",
                "(I)L" + internal(CurveType.class) + ";"));
        values.add(method(
                "cubism.editor-model.mutable-sequence.point",
                MutableSequence.class,
                "point",
                "(I)L" + internal(BezierPt.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.bezier-point.class", internal(BezierPt.class)));
        values.add(method(
                "cubism.editor-model.bezier-point.prev", BezierPt.class, "prev", "()L" + internal(CtrlPt.class) + ";"));
        values.add(method(
                "cubism.editor-model.bezier-point.next", BezierPt.class, "next", "()L" + internal(CtrlPt.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-model.bezier-ctrl-point.class", internal(CtrlPt.class)));
        values.add(method("cubism.editor-model.bezier-ctrl-point.pos", CtrlPt.class, "posF", "()F"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.value", CtrlPt.class, "doubleValue", "()D"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.corner", CtrlPt.class, "corner", "()Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.curve-type.class", internal(CurveType.class)));
        values.add(StaticSelector.field(
                "cubism.editor-model.curve-type.linear",
                internal(CurveType.class),
                "LINEAR",
                "L" + internal(CurveType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.curve-type.bezier",
                internal(CurveType.class),
                "BEZIER",
                "L" + internal(CurveType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.curve-type.smooth",
                internal(CurveType.class),
                "SMOOTH",
                "L" + internal(CurveType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.curve-type.step",
                internal(CurveType.class),
                "STEP",
                "L" + internal(CurveType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(StaticSelector.field(
                "cubism.editor-model.curve-type.inverse-step",
                internal(CurveType.class),
                "INVERSE_STEP",
                "L" + internal(CurveType.class) + ";",
                StaticSelector.ACCESS_PUBLIC | StaticSelector.ACCESS_STATIC));
        values.add(method(
                "cubism.editor-model.animation-file-content.scene-docs",
                AnimationFileContent.class,
                "sceneDocs",
                "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.scene-document.class", internal(SceneDocument.class)));
        values.add(method(
                "cubism.editor-model.scene-document.scene-source",
                SceneDocument.class,
                "sceneSource",
                "()L" + internal(SceneSource.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-document.current-edit-mode",
                SceneDocument.class,
                "currentEditMode",
                "()L" + internal(SceneEditMode.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-document.complete-pack",
                SceneDocument.class,
                "completePack",
                "()L" + internal(CompletePack.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-document.update-modified", SceneDocument.class, "updateModified", "()V"));
        values.add(method(
                "cubism.editor-model.edit-mode-base.begin",
                EditModeBase.class,
                "begin",
                "(Ljava/lang/String;)L" + internal(GroupUndo.class) + ";"));
        values.add(
                method("cubism.editor-model.edit-mode-base.end", EditModeBase.class, "end", "(ZLjava/lang/Object;)Z"));
        values.add(method(
                "cubism.editor-history.document.undo-manager",
                Document.class,
                "undoManager",
                "()L" + internal(UndoManager.class) + ";"));
        values.add(StaticSelector.classSelector("cubism.editor-history.manager.class", internal(UndoManager.class)));
        values.add(method("cubism.editor-history.manager.entries", UndoManager.class, "entries", "()Ljava/util/List;"));
        values.add(method("cubism.editor-history.manager.position", UndoManager.class, "position", "()I"));
        values.add(method("cubism.editor-history.manager.can-undo", UndoManager.class, "canUndo", "()Z"));
        values.add(method("cubism.editor-history.manager.can-redo", UndoManager.class, "canRedo", "()Z"));
        values.add(StaticSelector.classSelector("cubism.editor-history.entry.class", internal(HistoryEntry.class)));
        values.add(method(
                "cubism.editor-history.entry.presentation-name",
                HistoryEntry.class,
                "presentationName",
                "()Ljava/lang/String;"));
        values.add(method("cubism.editor-history.entry.significant", HistoryEntry.class, "significant", "()Z"));
        values.add(method(
                "cubism.editor-model.attr.set-value-auto", Attr.class, "setValueAuto", "(ILjava/lang/Object;)V"));
        values.add(method("cubism.editor-model.attr.remove-value-auto", Attr.class, "removeValueAuto", "(I)V"));
        values.add(method(
                "cubism.editor-model.attr.track", Attr.class, "track", "()L" + internal(TrackSource.class) + ";"));
        values.add(method(
                "cubism.editor-model.attr-f.set-value-curve",
                AttrF.class,
                "setValueAndCurveType",
                "(IDL" + internal(CurveType.class) + ";)V"));
        values.add(method("cubism.editor-model.attr-f.read-only", AttrF.class, "isReadOnly", "()Z"));
        values.add(method("cubism.editor-model.attr-i.set-value-auto", AttrI.class, "setValueAuto", "(ID)V"));
        values.add(method("cubism.editor-model.attr-pt.set-value-auto", AttrPt.class, "setValueAuto", "(IFF)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-pos", CtrlPt.class, "setPos", "(F)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-value", CtrlPt.class, "setValue", "(D)V"));
        values.add(method("cubism.editor-model.bezier-ctrl-point.set-corner", CtrlPt.class, "setCorner", "(Z)V"));
        values.add(method(
                "cubism.editor-model.mutable-sequence.force-update", MutableSequence.class, "forceUpdate", "()V"));
        values.add(method(
                "cubism.editor-model.scene-source.set-scene-name",
                SceneSource.class,
                "setSceneName",
                "(Ljava/lang/String;)V"));
        values.add(
                method("cubism.editor-model.complete-pack.update-project", CompletePack.class, "updateProject", "()V"));
        values.add(method("cubism.editor-model.complete-pack.repaint-canvas", CompletePack.class, "repaint", "(Z)V"));
        values.add(StaticSelector.constructor(
                "cubism.editor-model.simple-undo.create",
                internal(SimpleUndo.class),
                "(Ljava/lang/String;Ljava/lang/Object;Ljava/lang/Object;)V",
                StaticSelector.ACCESS_PUBLIC));
        values.add(StaticSelector.constructor(
                "cubism.editor-model.scene-handler.create",
                internal(SceneHandler.class),
                "(L" + internal(SceneSource.class) + ";)V",
                StaticSelector.ACCESS_PUBLIC));
        values.add(method(
                "cubism.editor-model.scene-handler.basic-undo",
                SceneHandler.class,
                "a",
                "(Ljava/lang/String;)L" + internal(Undo.class) + ";"));
        values.add(
                method("cubism.editor-model.undo.add", GroupUndo.class, "add", "(L" + internal(Undo.class) + ";Z)Z"));
        values.add(method(
                "cubism.editor-model.undo.add-listener",
                Undo.class,
                "addListener",
                "(L" + internal(Listener.class) + ";)Z"));
        values.add(StaticSelector.classSelector("cubism.editor-model.undo-listener.class", internal(Listener.class)));
        values.add(method(
                "cubism.editor-model.scene-source.scene-instances",
                SceneSource.class,
                "sceneInstances",
                "()Ljava/util/List;"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.scene-instance.class", internal(SceneInstance.class)));
        values.add(method(
                "cubism.editor-model.scene-instance.current-time",
                SceneInstance.class,
                "currentTime",
                "()L" + internal(SceneTime.class) + ";"));
        values.add(method("cubism.editor-model.scene-time.frame", SceneTime.class, "frame", "()I"));
        values.add(method("cubism.editor-model.scene-time.set-frame", SceneTime.class, "frame", "(I)V"));
        values.add(method(
                "cubism.editor-model.animation.set-current-scene",
                Animation.class,
                "setCurrentScene",
                "(L" + internal(SceneSource.class) + ";)V"));
        values.add(method(
                "cubism.editor-model.animation-file-content.current-scene-doc",
                AnimationFileContent.class,
                "currentSceneDoc",
                "()L" + internal(SceneDocument.class) + ";"));
        values.add(method(
                "cubism.editor-model.animation-file-content.set-current-scene-doc",
                AnimationFileContent.class,
                "setCurrentSceneDoc",
                "(L" + internal(SceneDocument.class) + ";)V"));
        values.add(method(
                "cubism.editor-model.scene-document.animation",
                SceneDocument.class,
                "animation",
                "()L" + internal(Animation.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-document.view-contexts",
                SceneDocument.class,
                "viewContexts",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.scene-source.default-curve-type",
                SceneSource.class,
                "defaultCurveType",
                "()L" + internal(CurveType.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-source.set-default-curve-type",
                SceneSource.class,
                "setDefaultCurveType",
                "(L" + internal(CurveType.class) + ";)V"));
        values.add(method(
                "cubism.editor-model.mutable-sequence.set-curve-type",
                MutableSequence.class,
                "setCurveType",
                "(IL" + internal(CurveType.class) + ";)V"));
        values.add(method(
                "cubism.editor-model.scene-instance.root-track",
                SceneInstance.class,
                "rootTrack",
                "()L" + internal(GroupTrackInstance.class) + ";"));
        values.add(method(
                "cubism.editor-model.scene-instance.all-tracks",
                SceneInstance.class,
                "allTracks",
                "()Ljava/util/List;"));
        values.add(method(
                "cubism.editor-model.track-instance.update",
                TrackInstance.class,
                "update",
                "(Ljava/lang/Object;L" + internal(SceneTime.class) + ";L" + internal(EvalFlags.class) + ";)V"));
        values.add(method(
                "cubism.editor-model.track-instance.source",
                TrackInstance.class,
                "source",
                "()L" + internal(TrackSource.class) + ";"));
        values.add(StaticSelector.classSelector(
                "cubism.editor-model.track-model-instance.class", internal(ModelTrackInstance.class)));
        values.add(method(
                "cubism.editor-model.track-model-instance.parameter-set",
                ModelTrackInstance.class,
                "parameterSet",
                "()L" + internal(ParameterSet.class) + ";"));
        values.add(StaticSelector.constructor(
                "cubism.editor-model.eval-flags.create",
                internal(EvalFlags.class),
                "(Z)V",
                StaticSelector.ACCESS_PUBLIC));
        values.add(method("cubism.editor-model.parameter.value", ParameterHolder.class, "value", "()F"));
        return List.copyOf(values);
    }

    private static StaticSelector method(
            final String alias, final Class<?> owner, final String name, final String descriptor) {
        return StaticSelector.method(alias, internal(owner), name, descriptor, StaticSelector.ACCESS_PUBLIC);
    }

    private static String internal(final Class<?> type) {
        return type.getName().replace('.', '/');
    }
}
