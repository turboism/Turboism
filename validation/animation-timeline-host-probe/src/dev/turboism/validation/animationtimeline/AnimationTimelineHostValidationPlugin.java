package dev.turboism.validation.animationtimeline;

import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationCurveType;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationKeyframe;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.AnimationTrackKind;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.SwingUtilities;
import java.io.File;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task-local exerciser for the exact-host animation workspace path.
 *
 * <p>Phase A builds a {@code CAnimationFileContent} inside the running editor
 * through native host constructors (project child, two scenes, one Live2D model
 * track linked to the open model's {@code CModelSource}, a parameter effect with
 * two keyed attributes). Phase B exercises the public SDK animation surface:
 * enumeration, timeline projection, playback, scene ops, keyframe writes,
 * easing, evaluated record/bake, and native undo via the scene document's
 * {@code CUndoManager}. Validation tooling only; never part of the product
 * build.</p>
 */
public final class AnimationTimelineHostValidationPlugin implements TurboismPlugin {

    private static final String FLAG = "exerciser.flag";
    private static final long FLAG_TIMEOUT_MILLIS = 240_000L;
    private static final long MODEL_AWAIT_MAX_MILLIS = 240_000L;
    private static final String ANIMATION_NAME = "TsmProbeAnim";
    private static final String SCENE_A = "01_ProbeA";
    private static final String SCENE_B = "02_ProbeB";
    private static final String SCENE_B_RENAMED = "00_ProbeBRenamed";
    private static final int[] FIXTURE_FRAMES = {0, 30, 60};

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;
    private final List<Assertion> assertions = new ArrayList<>();

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenFlagged, "animation-timeline-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
        logger.info("ANIM_PROBE_READY stateDir=" + stateDir);
    }

    @Override
    public void enable() {
        logger.info("ANIM_PROBE_ENABLED");
    }

    @Override
    public void disable() {
        logger.info("ANIM_PROBE_DISABLED");
    }

    @Override
    public void shutdown() {
        logger.info("ANIM_PROBE_SHUTDOWN");
    }

    private void runWhenFlagged() {
        final Path flag = stateDir.resolve(FLAG);
        final long deadline = System.currentTimeMillis() + FLAG_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(flag)) {
                runMatrix();
                return;
            }
            try {
                Thread.sleep(2_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        logger.warn("ANIM_PROBE_FLAG_TIMEOUT flag=" + flag);
        Runtime.getRuntime().halt(2);
    }

    private void runMatrix() {
        final long startedNanos = System.nanoTime();
        try {
            final CubismModel model = awaitActiveModel();
            recordAssertion("host.activeModel", "model with drawables", "active", "PASS");
            final Fixture fixture = buildFixture();
            runReadMatrix(model, fixture);
            runSceneMatrix(model, fixture);
            runAttributeMatrix(model, fixture);
            runEvalMatrix(model, fixture);
            runOffEdtProbe(model);
        } catch (Exception failure) {
            recordAssertion("matrix.unexpectedFailure", "no exception", singleLine(failure), "FAIL");
            logger.error("ANIM_MATRIX_FAILED " + singleLine(failure), failure);
        }
        final String terminal = computeTerminal();
        writeResultFile(startedNanos);
        logger.info("ANIM_MATRIX_RESULT status=" + terminal
            + " assertions=" + assertions.size()
            + " durationMillis=" + ((System.nanoTime() - startedNanos) / 1_000_000L));
        try {
            Thread.sleep(3_000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        Runtime.getRuntime().exit(0);
    }

    private CubismModel awaitActiveModel() throws Exception {
        final long deadline = System.currentTimeMillis() + MODEL_AWAIT_MAX_MILLIS;
        Exception lastFailure = null;
        while (System.currentTimeMillis() < deadline) {
            try {
                final CubismModel model = onHostThread(() -> context.cubism().model().active());
                final boolean hasDrawables = onHostThread(() -> !model.drawables().all().isEmpty());
                if (model != null && hasDrawables) {
                    return model;
                }
            } catch (Exception exception) {
                lastFailure = exception;
            }
            Thread.sleep(1_000L);
        }
        throw new IllegalStateException(
            "No active model with drawables within " + MODEL_AWAIT_MAX_MILLIS + " ms",
            lastFailure
        );
    }

    // ------------------------------------------------------------------
    // Phase A: native fixture construction
    // ------------------------------------------------------------------

    private static final class Fixture {
        Object app;
        Object modelDoc;
        Object modelSource;
        Object content;
        Object sceneSourceA;
        Object sceneSourceB;
        Object sceneDocA;
        Object sceneDocB;
        Object attrA1;
        Object attrA2;
        Object trackSource;
        File modelFile;
    }

    private Fixture buildFixture() throws Exception {
        return onHostThread(() -> {
            final Fixture fixture = new Fixture();
            final Class<?> appClass = hostClass("com.live2d.cubism.CEAppCtrl");
            fixture.app = appClass.getDeclaredMethod("access$get_instance$cp").invoke(null);
            fixture.modelDoc = invoke(fixture.app, "getCurrentDoc");
            if (fixture.modelDoc == null
                || !fixture.modelDoc.getClass().getName().equals(
                    "com.live2d.cubism.doc.modeling.CModelingDocument")) {
                final List<?> modelDocs =
                    (List<?>) invoke(fixture.app, "getAllModelDocs");
                fixture.modelDoc = modelDocs == null || modelDocs.isEmpty() ? null : modelDocs.get(0);
            }
            if (fixture.modelDoc == null) {
                recordAssertion("fixture.modelDoc", "active CModelingDocument", "none", "BLOCKED");
                return null;
            }
            fixture.modelFile = (File) invoke(fixture.modelDoc, "getFile");
            fixture.modelSource = invoke(fixture.modelDoc, "getModelSource");
            recordAssertion("fixture.modelDoc", "active CModelingDocument",
                fixture.modelFile != null ? fixture.modelFile.getName() : "no file", "PASS");

            final Object pack = invoke(fixture.app, "getCompletePack");
            final Class<?> animClass = hostClass("com.live2d.cubism.doc.animation.CAnimation");
            final Object anim = animClass.getConstructor(String.class).newInstance(ANIMATION_NAME);
            invoke(anim, "setFile", new Class<?>[]{File.class},
                stateDir.resolve("probe.can3").toFile());
            final Class<?> targetVersion =
                hostClass("com.live2d.cubism.CETargetVersion$Animation");
            invoke(anim, "setTargetVersion", new Class<?>[]{targetVersion},
                targetVersion.getField("FOR_MOVIE").get(null));
            final Class<?> contentClass =
                hostClass("com.live2d.cubism.doc.animation.CAnimationFileContent");
            fixture.content = contentClass
                .getConstructor(hostClass("com.live2d.cubism.pack.CECompletePack"), animClass)
                .newInstance(pack, anim);
            final Object project = invoke(fixture.app, "getCurrentProject");
            invoke(project, "add",
                new Class<?>[]{hostClass("com.live2d.cubism.doc.ICProjectEntry"), int.class},
                fixture.content, -1);
            recordAssertion("fixture.projectChild", "animation file content in project children",
                String.valueOf(((List<?>) invoke(project, "getChildren")).contains(fixture.content)),
                "PASS");

            buildScene(fixture, anim, SCENE_A, true);
            buildScene(fixture, anim, SCENE_B, false);
            invoke(anim, "setCurrentScene",
                new Class<?>[]{hostClass("com.live2d.cubism.doc.animation.CSceneSource")},
                fixture.sceneSourceA);
            invoke(fixture.content, "setCurrentSceneDoc",
                new Class<?>[]{hostClass("com.live2d.cubism.doc.animation.CSceneDocument")},
                fixture.sceneDocA);
            final List<?> viewContexts = (List<?>) invoke(fixture.modelDoc, "getViewContexts");
            if (viewContexts != null && !viewContexts.isEmpty()) {
                invoke(fixture.app, "setCurrentViewContext",
                    new Class<?>[]{hostClass("com.live2d.cubism.view.context.CEViewContext")},
                    viewContexts.get(0));
            }
            return fixture;
        });
    }

    private void buildScene(
        final Fixture fixture,
        final Object anim,
        final String sceneName,
        final boolean withModelTrack
    ) throws Exception {
        final Class<?> sceneSourceClass =
            hostClass("com.live2d.cubism.doc.animation.CSceneSource");
        final Object scene = sceneSourceClass
            .getConstructor(anim.getClass(), String.class)
            .newInstance(anim, sceneName);
        if (withModelTrack) {
            final Class<?> trackClass = hostClass(
                "com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Source");
            final Object companion = trackClass.getField("Companion").get(null);
            final Object track = invoke(companion, "a", new Class<?>[]{
                    hostClass("com.live2d.cubism.doc.animation.CSceneSource"),
                    File.class},
                scene, fixture.modelFile);
            final Object rootTrack = invoke(scene, "getRootTrack");
            invoke(rootTrack, "addChild",
                new Class<?>[]{
                    hostClass("com.live2d.cubism.doc.animation.movie.track.ICMvTrack_Source"),
                    int.class, boolean.class},
                track, -1, true);
            final Object trackSourceSet = invoke(scene, "getTrackSourceSet");
            final List<?> sources = (List<?>) invoke(trackSourceSet, "getSources");
            if (sources != null && !sources.contains(track)) {
                @SuppressWarnings("unchecked") final List<Object> mutable = (List<Object>) sources;
                mutable.add(track);
            }
            fixture.trackSource = track;
            final Object linkedModel = invoke(track, "getModel");
            final Object linkedGuid = linkedModel == null ? null
                : invoke(invoke(linkedModel, "getGuid"), "getUuidString");
            final Object activeGuid =
                invoke(invoke(fixture.modelSource, "getGuid"), "getUuidString");
            final Object resourceRef = invoke(track, "getResourceRef");
            final File resourceSrc = resourceRef == null ? null
                : (File) invoke(resourceRef, "getSrcFile");
            final File modelFile = fixture.modelFile;
            final String resourcePath = resourceSrc == null ? "null"
                : resourceSrc.getCanonicalPath();
            final String modelPath = modelFile == null ? "null"
                : modelFile.getCanonicalPath();
            recordAssertion("fixture.modelTrack.linked",
                "resource srcFile canonical-path match",
                "identity=" + (linkedModel == fixture.modelSource)
                    + " linkedGuid=" + linkedGuid + " activeGuid=" + activeGuid
                    + " resourceSrc=" + resourcePath + " modelFile=" + modelPath,
                resourceSrc != null && modelFile != null
                    && resourceSrc.getCanonicalFile().equals(modelFile.getCanonicalFile())
                    ? "PASS" : "FAIL");

            final Object effect = invoke(track, "getKeyParamEffect$cubism");

            final List<?> parameters =
                (List<?>) invoke(fixture.modelSource, "getAllParameters");
            recordAssertion("fixture.parameters", ">= 2 parameter sources",
                parameters == null ? "null" : String.valueOf(parameters.size()),
                parameters != null && parameters.size() >= 2 ? "PASS" : "BLOCKED");
            if (parameters == null || parameters.size() < 2) {
                return;
            }
            final Class<?> paramSourceClass =
                hostClass("com.live2d.cubism.doc.model.param.CParameterSource");
            final Object[] attrs = new Object[2];
            for (int i = 0; i < 2; i++) {
                final Object paramSource = parameters.get(i);
                invoke(effect, "addAttr", new Class<?>[]{paramSourceClass}, paramSource);
                final Object attr = invoke(effect, "getAttrByParamId",
                    new Class<?>[]{paramSourceClass}, paramSource);
                final Object sequence = invoke(attr, "getValueData");
                final double base = ((Number) invoke(paramSource, "getDefaultValue")).doubleValue();
                for (int k = 0; k < FIXTURE_FRAMES.length; k++) {
                    invoke(sequence, "setDoubleValueAuto",
                        new Class<?>[]{int.class, double.class},
                        FIXTURE_FRAMES[k], base + k + i);
                }
                attrs[i] = attr;
            }
            fixture.attrA1 = attrs[0];
            fixture.attrA2 = attrs[1];

            final Object guidCompanion =
                hostClass("com.live2d.type.Guid").getField("Companion").get(null);
            final Object effectManager = invoke(track, "getEffectManager");
            final Object[] effects =
                (Object[]) invoke(effectManager, "getEffectList");
            int nullGuids = 0;
            for (Object e : effects) {
                for (Object a : (Object[]) invoke(e, "getAttrList")) {
                    if (invoke(a, "getGuid") == null) {
                        nullGuids++;
                        invoke(a, "setGuid",
                            new Class<?>[]{hostClass("com.live2d.type.Guid")},
                            invoke(guidCompanion, "b"));
                    }
                }
            }
            recordAssertion("fixture.attr.guids",
                "all effect attributes carry a guid",
                "assigned=" + nullGuids, "PASS");
        }
        invoke(fixture.content, "addScene",
            new Class<?>[]{sceneSourceClass, boolean.class}, scene, true);
        final List<?> sceneDocs = (List<?>) invoke(fixture.content, "getSceneDocs");
        final Object sceneDoc = sceneDocs.get(sceneDocs.size() - 1);
        if (SCENE_A.equals(sceneName)) {
            fixture.sceneSourceA = scene;
            fixture.sceneDocA = sceneDoc;
        } else {
            fixture.sceneSourceB = scene;
            fixture.sceneDocB = sceneDoc;
        }
        recordAssertion("fixture.scene." + sceneName, "scene doc created with view contexts",
            "viewContexts=" + ((List<?>) invoke(sceneDoc, "getViewContexts")).size()
                + " sceneInstances=" + ((List<?>) invoke(scene, "getSceneInstances")).size(),
            "PASS");
    }

    // ------------------------------------------------------------------
    // Phase B: SDK matrices
    // ------------------------------------------------------------------

    private AnimationDocument probeDocument(final CubismModel model) throws Exception {
        return onHostThread(() -> {
            final List<AnimationDocument> docs = model.animationDocuments();
            for (final AnimationDocument doc : docs) {
                if (ANIMATION_NAME.equals(doc.animationName())) {
                    return doc;
                }
            }
            return null;
        });
    }

    private void runReadMatrix(final CubismModel model, final Fixture fixture) throws Exception {
        if (fixture == null) {
            recordAssertion("read.enumerate", "fixture built", "no fixture", "BLOCKED");
            return;
        }
        final List<AnimationDocument> docs = onHostThread(model::animationDocuments);
        recordAssertion("read.enumerate.count", ">= 1 animation document",
            String.valueOf(docs.size()), docs.isEmpty() ? "FAIL" : "PASS");
        final AnimationDocument doc = docs.stream()
            .filter(d -> ANIMATION_NAME.equals(d.animationName())).findFirst().orElse(null);
        if (doc == null) {
            recordAssertion("read.enumerate.probe", ANIMATION_NAME, "absent", "FAIL");
            return;
        }
        recordAssertion("read.enumerate.probe", ANIMATION_NAME + " found",
            "scenes=" + doc.sceneCount() + " names=" + doc.sceneNames()
                + " current=" + doc.currentSceneName().orElse("none"), "PASS");
        recordAssertion("read.scene.count", "2", String.valueOf(doc.sceneCount()),
            doc.sceneCount() == 2 ? "PASS" : "FAIL");
        recordAssertion("read.scene.current", SCENE_A,
            doc.currentSceneName().orElse("none"),
            doc.currentSceneName().map(SCENE_A::equals).orElse(false) ? "PASS" : "FAIL");
        final List<AnimationScene> scenes = onHostThread(doc::scenes);
        final AnimationScene sceneA = scenes.stream()
            .filter(s -> SCENE_A.equals(s.name())).findFirst().orElse(null);
        if (sceneA == null) {
            recordAssertion("read.scene.projection", SCENE_A, "absent", "FAIL");
            return;
        }
        recordAssertion("read.scene.projection", SCENE_A,
            "duration=" + sceneA.durationFrames() + " tracks=" + sceneA.tracks().size(), "PASS");
        final AnimationTrack modelTrack = sceneA.tracks().stream()
            .filter(t -> t.kind() == AnimationTrackKind.LIVE2D_MODEL).findFirst().orElse(null);
        if (modelTrack == null) {
            recordAssertion("read.track.model", "one LIVE2D_MODEL track", "absent", "FAIL");
            return;
        }
        recordAssertion("read.track.model", "LIVE2D_MODEL track with model guid",
            "name=" + modelTrack.name() + " guid=" + modelTrack.linkedModelGuid().orElse("none")
                + " attrs=" + modelTrack.attributes().size(),
            modelTrack.linkedModelGuid().isPresent() ? "PASS" : "FAIL");
        final List<AnimationAttribute> attrs = modelTrack.attributes();
        final List<AnimationAttribute> keyed = attrs.stream()
            .filter(a -> !a.keyframes().isEmpty()).toList();
        recordAssertion("read.attr.count", "exactly the 2 keyed parameter attributes",
            "total=" + attrs.size() + " keyed=" + keyed.size(),
            keyed.size() == 2 ? "PASS" : "FAIL");
        for (final AnimationAttribute attr : keyed) {
            final List<Integer> frames =
                attr.keyframes().stream().map(AnimationKeyframe::frame).toList();
            recordAssertion("read.attr.keys." + attr.id(), "frames [0, 30, 60]",
                frames.toString(),
                frames.equals(List.of(0, 30, 60)) ? "PASS" : "FAIL");
        }
    }

    private void runSceneMatrix(final CubismModel model, final Fixture fixture) throws Exception {
        final AnimationDocument doc = probeDocument(model);
        if (doc == null) {
            recordAssertion("scene.matrix", "probe document", "absent", "BLOCKED");
            return;
        }
        final List<AnimationScene> scenes = onHostThread(doc::scenes);
        final AnimationScene sceneA = scenes.stream()
            .filter(s -> SCENE_A.equals(s.name())).findFirst().orElse(null);
        final AnimationScene sceneB = scenes.stream()
            .filter(s -> SCENE_B.equals(s.name())).findFirst().orElse(null);
        if (sceneA == null || sceneB == null) {
            recordAssertion("scene.matrix", "both scenes projected", "missing", "BLOCKED");
            return;
        }
        try {
            final int before = onHostThread(sceneA::playheadFrame);
            onHostThread(() -> {
                sceneA.seekTo(15);
                return null;
            });
            final int after = onHostThread(sceneA::playheadFrame);
            recordAssertion("playback.seekTo", "playhead 0 -> 15",
                before + " -> " + after, after == 15 ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("playback.seekTo", "no exception", singleLine(failure), "FAIL");
        }
        try {
            onHostThread(() -> {
                sceneB.activate();
                return null;
            });
            final boolean currentB = onHostThread(sceneB::current);
            final String currentName =
                onHostThread(() -> probeDocumentUnchecked(model).currentSceneName().orElse("none"));
            recordAssertion("scene.activate", "B becomes current",
                "current=" + currentB + " name=" + currentName,
                currentB && SCENE_B.equals(currentName) ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("scene.activate", "no exception", singleLine(failure), "FAIL");
        }
        try {
            onHostThread(() -> {
                sceneB.rename(SCENE_B_RENAMED);
                return null;
            });
            final List<String> names =
                onHostThread(() -> probeDocumentUnchecked(model).sceneNames());
            recordAssertion("scene.rename", SCENE_B + " -> " + SCENE_B_RENAMED,
                names.toString(),
                names.contains(SCENE_B_RENAMED) ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("scene.rename", "no exception", singleLine(failure), "FAIL");
        }
        try {
            final AnimationCurveType original = onHostThread(sceneA::defaultCurveType);
            onHostThread(() -> {
                sceneA.setDefaultCurveType(AnimationCurveType.STEP);
                return null;
            });
            final AnimationCurveType updated = onHostThread(sceneA::defaultCurveType);
            recordAssertion("scene.defaultCurveType", "get/set roundtrip",
                original + " -> " + updated,
                updated == AnimationCurveType.STEP ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("scene.defaultCurveType", "no exception", singleLine(failure), "FAIL");
        }
    }

    private void runAttributeMatrix(final CubismModel model, final Fixture fixture) throws Exception {
        final AnimationAttribute attr = probeAttribute(model);
        if (attr == null) {
            recordAssertion("attr.matrix", "parameter attribute", "absent", "BLOCKED");
            return;
        }
        try {
            onHostThread(() -> {
                attr.setKeyframe(45, 0.5);
                return null;
            });
            final List<Integer> frames = keyFrames(model, attr.id());
            recordAssertion("attr.setKeyframe", "key added at 45", frames.toString(),
                frames.contains(45) ? "PASS" : "FAIL");
            final int undoDepthBefore = undoDepth(fixture);
            onHostThread(() -> {
                attr.removeKeyframe(45);
                return null;
            });
            final List<Integer> afterRemove = keyFrames(model, attr.id());
            recordAssertion("attr.removeKeyframe", "key 45 removed", afterRemove.toString(),
                !afterRemove.contains(45) ? "PASS" : "FAIL");
            final int undoDepthAfter = undoDepth(fixture);
            recordAssertion("undo.envelope", "native undo step recorded",
                undoDepthBefore + " -> " + undoDepthAfter,
                undoDepthAfter > undoDepthBefore ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("attr.setRemove", "no exception", singleLine(failure), "FAIL");
        }
        try {
            final int moved = onHostThread(() -> attr.offsetKeyframes(10));
            final List<Integer> afterOffset = keyFrames(model, attr.id());
            recordAssertion("attr.offsetKeyframes", "frames +10",
                "moved=" + moved + " frames=" + afterOffset,
                moved == 3 && afterOffset.equals(List.of(10, 40, 70)) ? "PASS" : "FAIL");
            onHostThread(() -> attr.quantizeKeyframes(10));
            final List<Integer> afterQuantize = keyFrames(model, attr.id());
            recordAssertion("attr.quantizeKeyframes", "frames quantized to 10s",
                afterQuantize.toString(),
                afterQuantize.equals(List.of(10, 40, 70)) ? "PASS" : "FAIL");
            final int scaled = onHostThread(() -> attr.scaleKeyframeTimes(0.5, 0));
            final List<Integer> afterScale = keyFrames(model, attr.id());
            recordAssertion("attr.scaleKeyframeTimes", "frames halved from 0",
                "scaled=" + scaled + " frames=" + afterScale,
                afterScale.equals(List.of(5, 20, 35)) ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("attr.transform", "no exception", singleLine(failure), "FAIL");
        }
        try {
            onHostThread(() -> {
                attr.applyCurveType(AnimationCurveType.STEP);
                return null;
            });
            final List<AnimationCurveType> types = curveTypes(model, attr.id());
            final List<AnimationCurveType> segmentTypes =
                types.size() > 1 ? types.subList(0, types.size() - 1) : types;
            recordAssertion("attr.applyCurveType", "all segment keys STEP (last key has no outgoing segment)",
                types.toString(),
                !segmentTypes.isEmpty()
                    && segmentTypes.stream().allMatch(AnimationCurveType.STEP::equals)
                    ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("attr.applyCurveType", "no exception", singleLine(failure), "FAIL");
        }
        try {
            final Object undoManager = invoke(fixture.sceneDocA, "getUndoManager");
            final List<?> undoList = (List<?>) invoke(undoManager, "getUndoList");
            final int posBefore = ((Number) invoke(undoManager, "getCurrentPos")).intValue();
            final int sizeBefore = undoList.size();
            invoke(undoManager, "undo");
            final List<Integer> afterUndo = keyFrames(model, attr.id());
            invoke(undoManager, "redo");
            final List<Integer> afterRedo = keyFrames(model, attr.id());
            recordAssertion("undo.native",
                "undo/redo restores keyframes (pos=" + posBefore + " size=" + sizeBefore + ")",
                "undo=" + afterUndo + " redo=" + afterRedo,
                !afterUndo.equals(afterRedo) || !afterRedo.isEmpty() ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("undo.native", "no exception", singleLine(failure), "FAIL");
        }
    }

    private void runEvalMatrix(final CubismModel model, final Fixture fixture) throws Exception {
        final AnimationAttribute attr = probeAttribute(model);
        if (attr == null) {
            recordAssertion("eval.matrix", "parameter attribute", "absent", "BLOCKED");
            return;
        }
        recordEvalDiagnostics(fixture);
        try {
            onHostThread(() -> {
                attr.recordKeyframe(20, AnimationCurveType.LINEAR);
                return null;
            });
            final List<Integer> frames = keyFrames(model, attr.id());
            recordAssertion("eval.recordKeyframe", "evaluated key at 20", frames.toString(),
                frames.contains(20) ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("eval.recordKeyframe", "no exception", singleLine(failure), "FAIL");
        }
        try {
            final int written =
                onHostThread(() -> attr.bakeEvaluated(80, 100, 10, AnimationCurveType.SMOOTH));
            final List<Integer> frames = keyFrames(model, attr.id());
            recordAssertion("eval.bakeEvaluated", "keys at 80/90/100",
                "written=" + written + " frames=" + frames,
                frames.containsAll(List.of(80, 90, 100)) ? "PASS" : "FAIL");
        } catch (Exception failure) {
            recordAssertion("eval.bakeEvaluated", "no exception", singleLine(failure), "FAIL");
        }
    }

    /** Raw host diagnostics: model-instance presence + evaluated parameter ids. */
    private void recordEvalDiagnostics(final Fixture fixture) {
        try {
            onHostThread(() -> {
                final List<?> instances = (List<?>) invoke(
                    fixture.sceneSourceA, "getSceneInstances");
                if (instances == null || instances.isEmpty()) {
                    recordAssertion("diag.eval.modelInstance", "scene instance",
                        "none", "INFO");
                    return null;
                }
                final Object sceneInstance = instances.get(0);
                final List<?> tracks =
                    (List<?>) invoke(sceneInstance, "getAllTracks");
                final StringBuilder summary = new StringBuilder();
                for (Object ti : tracks) {
                    if (!ti.getClass().getName().equals(
                        "com.live2d.cubism.doc.animation.movie.track.CMvTrack_Live2DModel_Instance")) {
                        continue;
                    }
                    final Object mi = invoke(ti, "getModelInstance");
                    summary.append("modelInstance=").append(mi == null ? "null" : "live");
                    final Object linkedSource = invoke(
                        fixture.trackSource, "getModel");
                    summary.append(" linkedSame=")
                        .append(linkedSource == fixture.modelSource);
                    if (linkedSource != null) {
                        final Object pss = invoke(
                            linkedSource, "getParameterSourceSet");
                        summary.append(" srcParams=").append(
                            ((List<?>) invoke(pss, "getSources")).size());
                        summary.append(" srcAll=").append(
                            ((List<?>) invoke(linkedSource, "getAllObjects")).size());
                    }
                    if (mi != null) {
                        final Object ps = invoke(mi, "getParameterSet");
                        final List<?> params = ps == null ? null
                            : (List<?>) invoke(ps, "getParameters");
                        summary.append(" params=")
                            .append(params == null ? "null" : params.size());
                        if (params != null) {
                            final List<String> ids = new java.util.ArrayList<>();
                            for (Object p : params) {
                                final Object pid = invoke(p, "getId");
                                ids.add(String.valueOf(
                                    invoke(pid, "getIdString")));
                            }
                            summary.append(" ids=").append(ids.subList(
                                0, Math.min(6, ids.size())));
                        }
                    }
                }
                recordAssertion("diag.eval.modelInstance",
                    "live model track instance with populated parameter set",
                    summary.length() == 0 ? "no model track instance" : summary.toString(),
                    "INFO");
                return null;
            });
        } catch (Exception failure) {
            recordAssertion("diag.eval.modelInstance", "no exception",
                singleLine(failure), "INFO");
        }
        try {
            final boolean readOnly = (Boolean) onHostThread(
                () -> invoke(fixture.attrA1, "isReadOnly"));
            recordAssertion("diag.attr.readOnly", "isReadOnly flag",
                String.valueOf(readOnly), "INFO");
        } catch (Exception failure) {
            recordAssertion("diag.attr.readOnly", "no exception",
                singleLine(failure), "INFO");
        }
    }

    private void runOffEdtProbe(final CubismModel model) {
        try {
            final List<AnimationDocument> docs = model.animationDocuments();
            recordAssertion("thread.offEdt.enumerate", "off-EDT enumeration outcome",
                "returned " + docs.size() + " documents", "INFO");
        } catch (Exception failure) {
            recordAssertion("thread.offEdt.enumerate", "off-EDT enumeration outcome",
                "threw " + singleLine(failure), "INFO");
        }
    }

    private AnimationDocument probeDocumentUnchecked(final CubismModel model) {
        for (final AnimationDocument doc : model.animationDocuments()) {
            if (ANIMATION_NAME.equals(doc.animationName())) {
                return doc;
            }
        }
        return null;
    }

    private AnimationAttribute probeAttribute(final CubismModel model) throws Exception {
        return onHostThread(() -> probeAttributeById(model, null));
    }

    private AnimationAttribute probeAttributeById(
        final CubismModel model, final String wantedId
    ) {
        final AnimationDocument doc = probeDocumentUnchecked(model);
        if (doc == null) {
            return null;
        }
        for (final AnimationScene scene : doc.scenes()) {
            for (final AnimationTrack track : scene.tracks()) {
                for (final AnimationAttribute attr : track.attributes()) {
                    if (wantedId != null
                        ? wantedId.equals(attr.id()) : attr.parameterId().isPresent()) {
                        return attr;
                    }
                }
            }
        }
        return null;
    }

    /** Re-enumerates the attribute so keyframes reflect post-write host state. */
    private List<Integer> keyFrames(final CubismModel model, final String attrId)
        throws Exception {
        return onHostThread(() -> {
            final AnimationAttribute fresh = probeAttributeById(model, attrId);
            return fresh == null ? List.of()
                : fresh.keyframes().stream().map(AnimationKeyframe::frame).sorted().toList();
        });
    }

    private List<AnimationCurveType> curveTypes(
        final CubismModel model, final String attrId
    ) throws Exception {
        return onHostThread(() -> {
            final AnimationAttribute fresh = probeAttributeById(model, attrId);
            return fresh == null ? List.of()
                : fresh.keyframes().stream()
                    .map(k -> k.curveType().orElse(null)).toList();
        });
    }

    private int undoDepth(final Fixture fixture) {
        try {
            final Object undoManager = invoke(fixture.sceneDocA, "getUndoManager");
            return ((List<?>) invoke(undoManager, "getUndoList")).size();
        } catch (Exception failure) {
            return -1;
        }
    }

    // ------------------------------------------------------------------
    // Reflection + thread + evidence helpers
    // ------------------------------------------------------------------

    private static Class<?> hostClass(final String name) throws Exception {
        // The plugin classloader cannot see host classes; the Cubism app
        // classloader can. Try the EDT context loader first, then the system
        // loader, then the loader of any reachable host object.
        final List<ClassLoader> loaders = new ArrayList<>();
        loaders.add(Thread.currentThread().getContextClassLoader());
        loaders.add(ClassLoader.getSystemClassLoader());
        for (ClassLoader loader = AnimationTimelineHostValidationPlugin.class
                .getClassLoader(); loader != null; loader = loader.getParent()) {
            loaders.add(loader);
        }
        for (final ClassLoader loader : loaders) {
            if (loader == null) {
                continue;
            }
            try {
                return Class.forName(name, true, loader);
            } catch (ClassNotFoundException ignored) {
            }
        }
        return Class.forName(name);
    }

    private static Object invoke(
        final Object target, final String method
    ) throws Exception {
        try {
            return lookup(target.getClass(), method).invoke(target);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            throw unwrap(wrapped);
        }
    }

    private static Object invoke(
        final Object target, final String method, final Class<?>[] types, final Object... args
    ) throws Exception {
        try {
            return lookup(target.getClass(), method, types).invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException wrapped) {
            throw unwrap(wrapped);
        }
    }

    private static Method lookup(
        final Class<?> type, final String method, final Class<?>... types
    ) throws NoSuchMethodException {
        try {
            return type.getMethod(method, types);
        } catch (NoSuchMethodException missing) {
            Class<?> current = type;
            while (current != null) {
                try {
                    final Method found = current.getDeclaredMethod(method, types);
                    found.setAccessible(true);
                    return found;
                } catch (NoSuchMethodException ignored) {
                    current = current.getSuperclass();
                }
            }
            throw missing;
        }
    }

    private static Exception unwrap(final java.lang.reflect.InvocationTargetException wrapped) {
        final Throwable cause = wrapped.getCause();
        if (cause instanceof Exception exception) {
            return exception;
        }
        return new IllegalStateException(cause == null ? wrapped : cause);
    }

    private <T> T onHostThread(final Callable<T> operation) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return operation.call();
        }
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch latch =
            new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                latch.countDown();
            }
        });
        if (!latch.await(120, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("EDT operation timed out");
        }
        if (failure.get() != null) {
            if (failure.get() instanceof RuntimeException runtime) {
                throw runtime;
            }
            if (failure.get() instanceof Exception checked) {
                throw checked;
            }
            throw new IllegalStateException(failure.get());
        }
        return result.get();
    }

    private void recordAssertion(
        final String name, final Object expected, final Object actual, final String status
    ) {
        assertions.add(new Assertion(name, singleLine(expected), singleLine(actual), status));
    }

    private static String singleLine(final Object value) {
        if (value == null) {
            return "null";
        }
        final String text = value.toString().replace('\n', ' ').replace('\r', ' ');
        return text.length() > 4000 ? text.substring(0, 4000) : text;
    }

    private String computeTerminal() {
        boolean anyFail = false;
        for (final Assertion assertion : assertions) {
            if ("FAIL".equals(assertion.status())) {
                anyFail = true;
            }
        }
        return anyFail ? "FAIL" : "PASS";
    }

    private void writeResultFile(final long startedNanos) {
        final Path result = stateDir.getParent()
            .resolve("animation-timeline-validation-result.properties");
        try {
            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=")
                .append(System.getProperty("turboism.validation.runId", "unknown")).append('\n')
                .append("pluginId=dev.turboism.validation.animation-timeline\n")
                .append("hostVersion=")
                .append(System.getProperty("turboism.validation.hostVersion", "unknown"))
                .append('\n')
                .append("terminal=").append(computeTerminal()).append('\n')
                .append("durationMillis=")
                .append((System.nanoTime() - startedNanos) / 1_000_000L).append('\n')
                .append("assertions=").append(assertions.size()).append('\n');
            for (int i = 0; i < assertions.size(); i++) {
                final Assertion assertion = assertions.get(i);
                report.append("assertion.").append(i).append(".name=").append(assertion.name())
                    .append('\n')
                    .append("assertion.").append(i).append(".expected=")
                    .append(escape(assertion.expected())).append('\n')
                    .append("assertion.").append(i).append(".actual=")
                    .append(escape(assertion.actual())).append('\n')
                    .append("assertion.").append(i).append(".status=").append(assertion.status())
                    .append('\n');
            }
            Files.writeString(result, report.toString(),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception failure) {
            logger.error("ANIM_RESULT_WRITE_FAILED " + singleLine(failure), failure);
        }
    }

    private static String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\n", " ").replace("=", "\\=");
    }

    private record Assertion(String name, String expected, String actual, String status) {
    }
}
