package dev.turboism.adapter.host;

import static dev.turboism.adapter.host.DynamicCubismModelTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.adapter.cubism.NativeLabelColorTarget;
import dev.turboism.adapter.cubism.model.ModelObjectProviderUnavailableException;
import dev.turboism.sdk.cubism.clipmask.ClipMaskReplacement;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationAttribute;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AnimationScene;
import dev.turboism.sdk.cubism.model.AnimationTrack;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import dev.turboism.sdk.cubism.model.ModelObjectCreateRequest;
import dev.turboism.sdk.cubism.model.ModelObjectKind;
import dev.turboism.sdk.cubism.model.ModelObjectReference;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterInterpolation;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationDeformers;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformers;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DynamicCubismModelAccessTest {

    @Test
    void interpolationOperationsCannotCrossModelReplacementOrDeactivation() {
        var recording = new RecordingModel();
        var access = new DynamicCubismModelAccess();
        access.connect(() -> recording);
        var batch = access.active().parameterBindingBatch();
        var operations = access.active().parameterBindings(new ParameterId("ParamA"));
        var target = ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshA"));
        var settings = new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 6, 1);
        assertEquals(settings, operations.interpolation(target));
        operations.setInterpolation(target, settings);
        batch.setInterpolations(target, java.util.Map.of(new ParameterId("ParamA"), settings));
        access.connect(() -> recording);
        assertThrows(IllegalStateException.class, () -> batch.setInterpolations(target, java.util.Map.of()));
        assertThrows(IllegalStateException.class, () -> operations.interpolation(target));
        assertThrows(IllegalStateException.class, () -> operations.setInterpolation(target, settings));
        var current = access.active().parameterBindings(new ParameterId("ParamA"));
        access.deactivate();
        assertThrows(IllegalStateException.class, () -> current.interpolation(target));
        assertThrows(IllegalStateException.class, () -> current.setInterpolation(target, settings));
        assertEquals(
                List.of(
                        "interpolation.read:ArtMeshA",
                        "interpolation.write:ArtMeshA:6",
                        "interpolation.batch:ArtMeshA:1"),
                recording.calls);
    }

    @Test
    void stagedPsdNameAndContentStayBoundAcrossTheDynamicDelegate() {
        final var calls = new ArrayList<List<Object>>();
        final var admissions = new java.util.concurrent.atomic.AtomicInteger();
        final var target = new dev.turboism.sdk.cubism.id.RawImageId("exact-raw");
        final var stage = java.nio.file.Path.of("revision-123.psd");
        final String sourceName = "外部 编辑.psd";
        final var recording = new RecordingModel();
        recording.textures = (dev.turboism.sdk.cubism.model.ModelTextures) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {
                    dev.turboism.sdk.cubism.model.ModelTextures.class,
                    dev.turboism.core.runtime.psd.PsdReplaceHost.class
                },
                (proxy, method, args) -> {
                    if (!"replaceWithStagedPsd".equals(method.getName())) {
                        throw new AssertionError("unexpected texture operation: " + method.getName());
                    }
                    ((Runnable) args[3]).run();
                    calls.add(List.of(args[0], args[1], args[2]));
                    return dev.turboism.core.runtime.psd.PsdReplaceHost.Replacement.unavailable();
                });
        final var access = new DynamicCubismModelAccess();
        access.connect(() -> recording);
        final var host =
                (dev.turboism.core.runtime.psd.PsdReplaceHost) access.active().textures();
        host.replaceWithStagedPsd(target, stage, sourceName, admissions::incrementAndGet);
        assertEquals(List.of(List.of(target, stage, sourceName)), calls);
        assertEquals(1, admissions.get());
        access.deactivate();
        assertThrows(
                IllegalStateException.class,
                () -> host.replaceWithStagedPsd(target, stage, sourceName, admissions::incrementAndGet));
        assertEquals(1, calls.size());
        assertEquals(1, admissions.get());
    }

    @Test
    void replacementInvalidatesModelCollectionsAndChildren() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(modelAccess("model-a", 1.0F));

        CubismModel staleModel = access.active();
        assertTrue(staleModel.defaultKeyformLocked());
        Parameters staleParameters = staleModel.parameters();
        assertEquals(ModelEditLevel.LEVEL_1, staleModel.editLevel());
        staleModel.setEditLevel(ModelEditLevel.LEVEL_2);
        assertEquals(ModelEditLevel.LEVEL_2, staleModel.editLevel());
        Parameter staleParameter = staleParameters.find(new ParameterId("ParamA"));
        assertEquals(1.0F, staleParameter.getValue());

        access.connect(modelAccess("model-b", 2.0F));

        assertThrows(IllegalStateException.class, staleModel::id);
        assertThrows(IllegalStateException.class, () -> staleModel.exportMoc(null));
        assertThrows(IllegalStateException.class, staleModel::defaultKeyformLocked);
        assertThrows(IllegalStateException.class, () -> staleModel.setDefaultKeyformLocked(false));
        assertThrows(IllegalStateException.class, staleModel::editLevel);
        assertThrows(IllegalStateException.class, () -> staleModel.setEditLevel(ModelEditLevel.LEVEL_3));
        assertThrows(IllegalStateException.class, staleParameters::all);
        assertThrows(IllegalStateException.class, staleParameter::getValue);
        assertEquals(new ModelId("model-b"), access.active().id());
    }

    @Test
    void modelObjectCreateProviderUsesTheCapturedSessionGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final RecordingCreateAccess provider = new RecordingCreateAccess("model-a");
        access.connect(provider);
        final CubismModel captured = access.active();
        final ModelObjectCreateRequest request = new ModelObjectCreateRequest.Part("Created", Optional.empty());

        access.requireCreateSupported(request);
        assertEquals(
                new ModelObjectReference(ModelObjectKind.PART, "PartStable"),
                access.createModelObject(captured, request));
        assertSame(provider.model, provider.createdAgainst);

        access.connect(modelAccess("model-b", 2.0F));
        assertThrows(IllegalStateException.class, () -> access.createModelObject(captured, request));
    }

    @Test
    void modelObjectCreateProviderUnavailableIsTypedBeforeModelMutation() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(modelAccess("model-a", 1.0F));

        assertThrows(
                ModelObjectProviderUnavailableException.class,
                () -> access.requireCreateSupported(new ModelObjectCreateRequest.Part("Blocked", Optional.empty())));
    }

    @Test
    void clipMaskReplacementIsForwardedOnlyForTheCurrentSession() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final List<List<ClipMaskReplacement>> calls = new ArrayList<>();
        final CubismModel base = model("model-a", parameter(1.0F));
        final CubismModel delegate = new CubismModel() {
            @Override
            public ModelId id() {
                return base.id();
            }

            @Override
            public Parameters parameters() {
                return base.parameters();
            }

            @Override
            public Parts parts() {
                return base.parts();
            }

            @Override
            public Drawables drawables() {
                return base.drawables();
            }

            @Override
            public Deformers deformers() {
                return base.deformers();
            }

            @Override
            public Glues glues() {
                return base.glues();
            }

            @Override
            public void update() {
                base.update();
            }

            @Override
            public void replaceArtMeshClipMasks(final List<ClipMaskReplacement> replacements) {
                calls.add(List.copyOf(replacements));
            }
        };
        final ClipMaskReplacement replacement = new ClipMaskReplacement(
                new ArtMeshId("target"), List.of(), false, List.of(new ArtMeshId("mask")), false);

        access.connect(() -> delegate);
        final CubismModel session = access.active();
        session.replaceArtMeshClipMasks(List.of(replacement));
        assertEquals(List.of(replacement), calls.get(0));

        access.deactivate();
        assertThrows(IllegalStateException.class, () -> session.replaceArtMeshClipMasks(List.of(replacement)));
    }

    @Test
    void sessionWrappersPreserveParameterGroupsAndGuardThemByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> model("model-a", parameter(1.0F), parameterGroups()));

        final ParameterGroups groups = access.active().parameterGroups();
        final ParameterGroup group = groups.find(new ParameterGroupId("GroupFace"));
        assertEquals(Optional.of("Face"), group.name());
        assertEquals(List.of(new ParameterId("ParamA")), group.parameterIds());

        access.deactivate();
        assertThrows(IllegalStateException.class, groups::all);
        assertThrows(IllegalStateException.class, group::name);
    }

    @Test
    void separateSourceCreationAndReturnedDrawableExpireWithSession() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> modelWithDrawable(drawable("guid-source", false, null)));
        final var meshes = access.active().drawables();
        final var geometry = new ArtMeshGeometry(List.of(), List.of(), List.of());
        final var created = meshes.createWithSourceGeometry("Source", null, -1, geometry, List.of());
        assertEquals("guid-source", created.guid());
        access.deactivate();
        assertThrows(IllegalStateException.class, created::guid);
        assertThrows(
                IllegalStateException.class,
                () -> meshes.createWithSourceGeometry("Source", null, -1, geometry, List.of()));
    }

    @Test
    void sessionDrawableForwardsGuidToDelegateAndPropagatesUnsupported() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> modelWithDrawable(drawable("guid-x", false, null)));

        final Drawable wrapped = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        assertEquals("guid-x", wrapped.guid());

        access.deactivate();
        assertThrows(IllegalStateException.class, wrapped::guid);

        access.connect(() -> modelWithDrawable(drawable(null, true, null)));
        final Drawable unsupported = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        assertThrows(UnsupportedOperationException.class, unsupported::guid);
    }

    @Test
    void sessionDeformersForwardApplyToChildrenOnceAndRejectStaleWrapper() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final Deformer backend = new Deformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("WarpA");
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }
        };
        final AtomicReference<Deformer> forwarded = new AtomicReference<>();
        final int[] calls = {0};
        final Deformers backendDeformers = new Deformers() {
            @Override
            public List<Deformer> all() {
                return List.of(backend);
            }

            @Override
            public Deformer find(final DeformerId id) {
                return backend;
            }

            @Override
            public void applyToChildren(final Deformer deformer) {
                calls[0]++;
                forwarded.set(deformer);
            }
        };
        final CubismModel base = model("model-a", parameter(1.0F));
        final CubismModel backendModel = new CubismModel() {
            @Override
            public ModelId id() {
                return base.id();
            }

            @Override
            public Parameters parameters() {
                return base.parameters();
            }

            @Override
            public Parts parts() {
                throw unsupported();
            }

            @Override
            public Drawables drawables() {
                throw unsupported();
            }

            @Override
            public Deformers deformers() {
                return backendDeformers;
            }

            @Override
            public Glues glues() {
                throw unsupported();
            }

            @Override
            public void update() {
                throw unsupported();
            }
        };
        access.connect(() -> backendModel);

        final CubismModel session = access.active();
        final Deformers sessionDeformers = session.deformers();
        final Deformer wrapped = sessionDeformers.find(new DeformerId("WarpA"));
        sessionDeformers.applyToChildren(wrapped);

        assertEquals(1, calls[0]);
        assertSame(backend, forwarded.get());
        access.deactivate();
        assertThrows(IllegalStateException.class, () -> sessionDeformers.applyToChildren(wrapped));
    }

    @Test
    void sessionWrappersForwardWave1WritesToDelegateAndReturnGuardedWrappers() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final Part added = access.active().parts().add(new PartId("PartAdded"));
        final Part addedUnder = access.active().parts().add(new PartId("PartAdded"), new PartId("PartRoot"));
        final Part copied = access.active().parts().copy(new PartId("PartSource"));
        access.active().parts().remove(new PartId("PartGone"));
        assertEquals(new PartId("PartReturned"), added.id());
        assertEquals(new PartId("PartReturned"), addedUnder.id());
        assertEquals(new PartId("PartReturned"), copied.id());

        final Parameter created = access.active().parameters().create(definition());
        final Parameter createdInFolder =
                access.active().parameters().create(definition(), Optional.of(new ParameterGroupId("GroupNew")));
        final Parameter duplicated = access.active().parameters().copy(new ParameterId("ParamA"));
        access.active().parameters().remove(new ParameterId("ParamA"));
        assertEquals(new ParameterId("ParamA"), created.id());
        assertEquals(new ParameterId("ParamA"), createdInFolder.id());
        assertEquals(new ParameterId("ParamA"), duplicated.id());

        final ParameterGroup addedGroup = access.active().parameterGroups().addGroup("GroupNew");
        access.active().parameterGroups().removeGroup(new ParameterGroupId("GroupGone"));
        access.active().parameterGroups().moveParameter(new ParameterId("ParamA"), new ParameterGroupId("GroupNew"));
        assertEquals(new ParameterGroupId("GroupNew"), addedGroup.id());

        final ParameterGroup wrappedGroup = access.active().parameterGroups().find(new ParameterGroupId("GroupNew"));
        wrappedGroup.rename("Renamed");
        assertEquals(Optional.of("Renamed"), wrappedGroup.name());

        assertEquals(
                List.of(
                        "parts.add",
                        "parts.add.parent",
                        "parts.copy",
                        "parts.remove",
                        "parameters.create",
                        "parameters.create.folder",
                        "parameters.copy",
                        "parameters.remove",
                        "groups.addGroup",
                        "groups.removeGroup",
                        "groups.moveParameter",
                        "group.rename"),
                recording.calls);

        access.deactivate();
        assertThrows(IllegalStateException.class, added::id);
        assertThrows(IllegalStateException.class, created::id);
        assertThrows(IllegalStateException.class, addedGroup::id);
        assertThrows(IllegalStateException.class, wrappedGroup::name);
    }

    @Test
    void wave1WritesFailClosedWithoutAnActiveSession() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final Parts parts = access.active().parts();
        final Parameters parameters = access.active().parameters();
        final ParameterGroups groups = access.active().parameterGroups();
        final ParameterGroup group = groups.find(new ParameterGroupId("GroupNew"));

        access.deactivate();

        assertThrows(IllegalStateException.class, () -> parts.add(new PartId("PartAdded")));
        assertThrows(IllegalStateException.class, () -> parts.add(new PartId("PartAdded"), new PartId("PartRoot")));
        assertThrows(IllegalStateException.class, () -> parts.copy(new PartId("PartSource")));
        assertThrows(IllegalStateException.class, () -> parts.remove(new PartId("PartGone")));
        assertThrows(IllegalStateException.class, () -> parameters.create(definition()));
        assertThrows(
                IllegalStateException.class,
                () -> parameters.create(definition(), Optional.of(new ParameterGroupId("GroupNew"))));
        assertThrows(IllegalStateException.class, () -> parameters.copy(new ParameterId("ParamA")));
        assertThrows(IllegalStateException.class, () -> parameters.remove(new ParameterId("ParamA")));
        assertThrows(IllegalStateException.class, () -> groups.addGroup("GroupNew"));
        assertThrows(IllegalStateException.class, () -> groups.removeGroup(new ParameterGroupId("GroupGone")));
        assertThrows(
                IllegalStateException.class,
                () -> groups.moveParameter(new ParameterId("ParamA"), new ParameterGroupId("GroupNew")));
        assertThrows(IllegalStateException.class, () -> group.rename("Renamed"));

        assertEquals(List.of(), recording.calls);
    }

    @Test
    void sessionModelForwardsWave1ReadsAndGuardsThemByGeneration() {
        final RecordingModel recording = new RecordingModel();
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> recording);

        final CubismModel model = access.active();
        assertSame(recording.profile, model.profile());
        assertSame(recording.physics, model.physicsSettings());
        assertSame(recording.autoYure, model.autoYure());
        final List<AnimationDocument> sessionAnimations = model.animationDocuments();
        assertEquals(1, sessionAnimations.size());
        assertEquals("AnimA", sessionAnimations.get(0).animationName());
        final var sessionTextures = model.textures();
        assertEquals(recording.textures.rawImages(), sessionTextures.rawImages());
        assertEquals(recording.textures.relations(), sessionTextures.relations());
        assertSame(
                recording.partTargets,
                model.parts().find(new PartId("PartReturned")).morphTargets());
        assertSame(
                recording.drawableTargets,
                model.drawables().find(new ArtMeshId("ArtMeshA")).morphTargets());

        access.deactivate();
        assertThrows(IllegalStateException.class, model::profile);
        assertThrows(IllegalStateException.class, model::physicsSettings);
        assertThrows(IllegalStateException.class, model::autoYure);
        assertThrows(IllegalStateException.class, model::animationDocuments);
        assertThrows(IllegalStateException.class, sessionAnimations.get(0)::animationName);
        assertThrows(IllegalStateException.class, model::textures);
        assertThrows(IllegalStateException.class, sessionTextures::relations);
        assertThrows(
                IllegalStateException.class,
                () -> model.parts().find(new PartId("PartReturned")).morphTargets());
        assertThrows(
                IllegalStateException.class,
                () -> model.drawables().find(new ArtMeshId("ArtMeshA")).morphTargets());
    }

    @Test
    void sessionAnimationGraphGuardsEveryLevelByGeneration() {
        final List<String> calls = new ArrayList<>();
        final CubismModel backend = animationGraphModel(calls);
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> backend);

        final CubismModel model = access.active();
        final List<AnimationDocument> documents = model.animationDocuments();
        final AnimationDocument document = documents.get(0);
        final List<AnimationScene> scenes = document.scenes();
        final AnimationScene scene = scenes.get(0);
        final AnimationTrack modelTrack = scene.tracks().get(0);
        final AnimationTrack groupTrack = scene.tracks().get(1);
        final AnimationTrack childTrack = groupTrack.children().get(0);
        final List<AnimationAttribute> attributes = childTrack.attributes();
        final AnimationAttribute attribute = attributes.get(0);
        assertEquals("AnimDoc", document.animationName());
        assertEquals("SceneA", scene.name());
        assertEquals("track-model", modelTrack.name());
        assertEquals("track-child", childTrack.name());
        assertEquals("attr-leaf", attribute.id());
        scene.rename("Renamed");
        scene.seekTo(12);
        attribute.setKeyframe(5, 0.5);
        attribute.offsetKeyframes(3);
        attribute.removeKeyframe(60);
        assertEquals(
                List.of(
                        "model.animationDocuments",
                        "doc.scenes",
                        "scene.tracks",
                        "scene.tracks",
                        "track.children",
                        "track.attributes",
                        "doc.animationName",
                        "scene.name",
                        "track.name",
                        "track.name",
                        "attr.id",
                        "scene.rename",
                        "scene.seekTo",
                        "attr.setKeyframe",
                        "attr.offsetKeyframes",
                        "attr.removeKeyframe"),
                calls);

        calls.clear();
        access.deactivate();

        assertThrows(IllegalStateException.class, model::animationDocuments);
        assertThrows(IllegalStateException.class, document::animationName);
        assertThrows(IllegalStateException.class, document::sceneNames);
        assertThrows(IllegalStateException.class, document::scenes);
        assertThrows(IllegalStateException.class, scene::name);
        assertThrows(IllegalStateException.class, scene::tracks);
        assertThrows(IllegalStateException.class, scene::playheadFrame);
        assertThrows(IllegalStateException.class, () -> scene.rename("x"));
        assertThrows(IllegalStateException.class, () -> scene.seekTo(0));
        assertThrows(IllegalStateException.class, modelTrack::name);
        assertThrows(IllegalStateException.class, modelTrack::attributes);
        assertThrows(IllegalStateException.class, groupTrack::children);
        assertThrows(IllegalStateException.class, childTrack::attributes);
        assertThrows(IllegalStateException.class, attribute::id);
        assertThrows(IllegalStateException.class, attribute::keyframes);
        assertThrows(IllegalStateException.class, () -> attribute.setKeyframe(6, 0.5));
        assertThrows(IllegalStateException.class, () -> attribute.offsetKeyframes(1));
        assertThrows(IllegalStateException.class, () -> attribute.copyKeyframesFrom(attribute, false));
        assertEquals(List.of(), calls);
    }

    @Test
    void sessionAnimationCopyRejectsStaleAndForeignSources() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        access.connect(() -> animationGraphModel(new ArrayList<>()));
        final AnimationAttribute staleSource = access.active()
                .animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);

        final List<String> calls = new ArrayList<>();
        access.connect(() -> animationGraphModel(calls));
        final AnimationAttribute target = access.active()
                .animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);
        final AnimationAttribute sameGeneration = access.active()
                .animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(0)
                .attributes()
                .get(0);
        final AnimationAttribute foreign = rawAnimationAttribute("foreign");

        final List<String> foreignCalls = new ArrayList<>();
        final DynamicCubismModelAccess foreignAccess = new DynamicCubismModelAccess();
        foreignAccess.connect(() -> animationGraphModel(foreignCalls));
        // Align the captured generation number with the target session so only
        // ownership, not the numeric generation, can reject this source.
        foreignAccess.connect(() -> animationGraphModel(foreignCalls));
        final AnimationAttribute foreignAccessSource = foreignAccess
                .active()
                .animationDocuments()
                .get(0)
                .scenes()
                .get(0)
                .tracks()
                .get(1)
                .children()
                .get(0)
                .attributes()
                .get(0);
        calls.clear();
        foreignCalls.clear();

        assertThrows(IllegalStateException.class, () -> target.copyKeyframesFrom(staleSource, false));
        assertThrows(IllegalStateException.class, () -> target.copyKeyframesFrom(foreign, false));

        // A source from a different DynamicCubismModelAccess is not owned by
        // this session even when both captured the same generation number;
        // it must be rejected without touching either backend.
        assertThrows(IllegalStateException.class, () -> target.copyKeyframesFrom(foreignAccessSource, false));
        foreignAccess.deactivate();
        assertThrows(IllegalStateException.class, () -> target.copyKeyframesFrom(foreignAccessSource, false));

        assertThrows(NullPointerException.class, () -> target.copyKeyframesFrom(null, false));

        assertEquals(0, target.copyKeyframesFrom(sameGeneration, false));
        assertEquals(List.of("attr.copyKeyframesFrom"), calls);
        assertEquals(List.of(), foreignCalls);
    }

    @Test
    void reportsAvailabilityOfTheCurrentDelegate() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();

        assertFalse(access.isAvailable(), "a detached access reports unavailable");
        assertFalse(UnavailableCubismModelAccess.INSTANCE.isAvailable(), "the fail-closed backing reports unavailable");

        access.connect(modelAccess("model-a", 1.0F));
        assertTrue(access.isAvailable(), "a connected access forwards the live delegate");

        access.deactivate();
        assertFalse(access.isAvailable(), "a deactivated access reports unavailable again");
    }

    /** Recording fixture: Wave 1 write/read delegate calls land in {@link #calls}. */
    @Test
    void nativeLabelColorSeamFollowsTheSessionLeaseAndFailsClosed() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final NativeLabelColorTarget folder =
                new NativeLabelColorTarget(NativeLabelColorTarget.Palette.PARAMETER_GROUP, "GroupFace");
        final NativeLabelColorState nativeState =
                new NativeLabelColorState(new NativeLabelColor.Default(), Optional.empty());
        access.connect(authoringModelAccess(nativeState));

        assertEquals(nativeState, access.readNativeLabelColor(folder));

        access.deactivate();
        assertThrows(IllegalStateException.class, () -> access.readNativeLabelColor(folder));
        assertThrows(
                IllegalStateException.class, () -> access.setNativeLabelColor(folder, new NativeLabelColor.Default()));

        access.connect(() -> model("model-a", parameter(1.0F), parameterGroups()));
        assertThrows(UnsupportedOperationException.class, () -> access.readNativeLabelColor(folder));
        assertThrows(
                UnsupportedOperationException.class,
                () -> access.setNativeLabelColor(folder, new NativeLabelColor.Default()));
    }

    @Test
    void sessionWrappersPreserveParameterMetadataAndGuardItByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final Parameter delegate = new Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamCheek");
            }

            @Override
            public int index() {
                return 7;
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence keyValues() {
                return new dev.turboism.sdk.cubism.model.FloatSequence() {
                    @Override
                    public int size() {
                        return 1;
                    }

                    @Override
                    public float get(final int index) {
                        if (index != 0) throw new IndexOutOfBoundsException(index);
                        return 0.25F;
                    }
                };
            }

            @Override
            public Optional<String> name() {
                return Optional.of("Cheek");
            }

            @Override
            public ParameterType type() {
                return ParameterType.BLEND_SHAPE;
            }

            @Override
            public Optional<Boolean> repeat() {
                return Optional.of(false);
            }

            @Override
            public Optional<Boolean> combined() {
                return Optional.of(true);
            }

            @Override
            public Optional<ParameterId> combinedWith() {
                return Optional.of(new ParameterId("ParamSmile"));
            }

            @Override
            public float getValue() {
                return 0.5F;
            }

            @Override
            public float getMinimumValue() {
                return 0.0F;
            }

            @Override
            public float getMaximumValue() {
                return 1.0F;
            }

            @Override
            public float getDefaultValue() {
                return 0.0F;
            }

            @Override
            public void setValue(final float value) {
                throw unsupported();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ParameterBinding> getParameterBindings() {
                return List.of();
            }
        };
        access.connect(() -> model("model-a", delegate));

        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamCheek"));
        final dev.turboism.sdk.cubism.model.FloatSequence keyValues = parameter.keyValues();
        assertEquals(7, parameter.index());
        assertEquals(1, keyValues.size());
        assertEquals(0.25F, keyValues.get(0));
        assertEquals(Optional.of("Cheek"), parameter.name());
        assertEquals(ParameterType.BLEND_SHAPE, parameter.type());
        assertEquals(Optional.of(false), parameter.repeat());
        assertEquals(Optional.of(true), parameter.combined());
        assertEquals(Optional.of(new ParameterId("ParamSmile")), parameter.combinedWith());
        assertEquals(List.of(), parameter.getParameterBindings());

        access.deactivate();
        assertThrows(IllegalStateException.class, parameter::index);
        assertThrows(IllegalStateException.class, keyValues::size);
        assertThrows(IllegalStateException.class, parameter::name);
        assertThrows(IllegalStateException.class, parameter::type);
        assertThrows(IllegalStateException.class, parameter::repeat);
        assertThrows(IllegalStateException.class, parameter::combined);
        assertThrows(IllegalStateException.class, parameter::combinedWith);
        assertThrows(IllegalStateException.class, parameter::getParameterBindings);
        assertThrows(IllegalStateException.class, () -> parameter.combineWith(new ParameterId("ParamSmile")));
        assertThrows(IllegalStateException.class, parameter::uncombine);
    }

    @ParameterizedTest
    @ValueSource(strings = {"reset", "definitionUpdate"})
    void parameterDirectOperationsAreForwardedOnlyWhileTheSessionGenerationIsCurrent(final String operation) {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final float[] value = {0.75F};
        final AtomicReference<ParameterDefinition> updated = new AtomicReference<>();
        final Parameter delegate = new Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamA");
            }

            @Override
            public float getValue() {
                return value[0];
            }

            @Override
            public float getMinimumValue() {
                return -1.0F;
            }

            @Override
            public float getMaximumValue() {
                return 1.0F;
            }

            @Override
            public float getDefaultValue() {
                return -0.25F;
            }

            @Override
            public void setValue(final float nextValue) {
                value[0] = nextValue;
            }

            @Override
            public void updateDefinition(final ParameterDefinition definition) {
                updated.set(definition);
            }
        };
        access.connect(() -> model("model-a", delegate));
        final Parameter parameter = access.active().parameters().find(new ParameterId("ParamA"));
        final ParameterDefinition definition = new ParameterDefinition(
                new ParameterId("ParamRenamed"), "Renamed", -2.0F, 0.0F, 2.0F, ParameterType.NORMAL, false);

        if ("reset".equals(operation)) {
            parameter.resetToDefault();
            assertEquals(-0.25F, value[0]);
            value[0] = 0.5F;
        } else {
            parameter.updateDefinition(definition);
            assertEquals(definition, updated.get());
        }

        access.connect(modelAccess("model-b", 2.0F));
        if ("reset".equals(operation)) {
            assertThrows(IllegalStateException.class, parameter::resetToDefault);
            assertEquals(0.5F, value[0]);
        } else {
            assertThrows(IllegalStateException.class, () -> parameter.updateDefinition(definition));
            assertEquals(definition, updated.get());
        }
    }

    @Test
    void rapidReplacementAndDeactivationInvalidateEveryPriorGeneration() {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        java.util.List<CubismModel> staleModels = new java.util.ArrayList<>();
        java.util.List<Parameter> staleParameters = new java.util.ArrayList<>();

        for (int generation = 0; generation < 200; generation++) {
            access.connect(modelAccess("model-" + generation, generation));
            CubismModel model = access.active();
            staleModels.add(model);
            staleParameters.add(model.parameters().find(new ParameterId("ParamA")));
            assertEquals(new ModelId("model-" + generation), model.id());
        }
        access.deactivate();

        for (CubismModel model : staleModels) {
            assertThrows(IllegalStateException.class, model::id);
        }
        for (Parameter parameter : staleParameters) {
            assertThrows(IllegalStateException.class, parameter::getValue);
        }
        assertThrows(IllegalStateException.class, access::active);
    }

    @Test
    void sessionWrappersGuardEditorObjectReadsWritesAndFormsByGeneration() {
        final DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        final float[] drawableOpacity = {1.0F};
        final float[] warpOpacity = {1.0F};
        final ArtMeshGeometry[] geometry = {
            new ArtMeshGeometry(
                    List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F), new Point2(0.0F, 1.0F)),
                    List.of(new Point2(0.0F, 0.0F), new Point2(1.0F, 0.0F), new Point2(0.0F, 1.0F)),
                    List.of(0, 1, 2))
        };
        final WarpGrid[] grid = {
            new WarpGrid(
                    1,
                    1,
                    false,
                    List.of(
                            new Point2(0.0F, 0.0F),
                            new Point2(1.0F, 0.0F),
                            new Point2(0.0F, 1.0F),
                            new Point2(1.0F, 1.0F)))
        };
        final RotationDeformerForm[] rotationForm = {new RotationDeformerForm(0.0F, 0.0F, 0.0F, 1.0F, false, false)};
        final Drawable drawable = new Drawable() {
            @Override
            public ArtMeshId id() {
                return new ArtMeshId("ArtMeshA");
            }

            @Override
            public float getOpacity() {
                return drawableOpacity[0];
            }

            @Override
            public void setOpacity(final float value) {
                drawableOpacity[0] = value;
            }

            @Override
            public ArtMeshGeometry geometry() {
                return geometry[0];
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot authoringSnapshot() {
                return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                        id(), "source-guid", geometry[0], List.of(), List.of(), List.of(), List.of());
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot evaluationSnapshot() {
                return new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(
                        id(),
                        "source-guid",
                        java.util.Optional.empty(),
                        new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(7, List.of()),
                        new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(13, List.of()),
                        -1);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshKeyform ordinaryKeyform(
                    final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
                return new dev.turboism.sdk.cubism.model.ArtMeshKeyform() {
                    private List<Point2> positions = List.of(new Point2(2, 3));
                    private int order = 500;

                    @Override
                    public List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                        return List.copyOf(coordinates);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "stored-form", positions, order);
                    }

                    @Override
                    public boolean defaultKeyform() {
                        return false;
                    }

                    @Override
                    public void setPositions(final List<Point2> value) {
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(final String guid, final float key) {
                return new dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform() {
                    private List<Point2> positions = List.of(new Point2(2, 3));
                    private int order = 500;

                    @Override
                    public String parameterGuid() {
                        return guid;
                    }

                    @Override
                    public float keyValue() {
                        return key;
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "morph-form", positions, order);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form baseSnapshot() {
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "base-form", List.of(new Point2(2, 3)), 500);
                    }

                    @Override
                    public void setPositions(final List<Point2> value) {
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform createMorphKeyform(
                    final String guid, final float key) {
                return morphKeyform(guid, key);
            }

            @Override
            public void replaceGeometry(final ArtMeshGeometry value) {
                geometry[0] = value;
            }

            @Override
            public byte constantFlag() {
                return 0;
            }

            @Override
            public byte dynamicFlag() {
                return 0;
            }

            @Override
            public dev.turboism.sdk.cubism.model.BlendMode blendMode() {
                return dev.turboism.sdk.cubism.model.BlendMode.NORMAL;
            }

            @Override
            public int textureIndex() {
                return 0;
            }

            @Override
            public int drawOrder() {
                return 0;
            }

            @Override
            public int renderOrder() {
                return 0;
            }

            @Override
            public Color multiplyColor() {
                return new Color(1.0F, 1.0F, 1.0F, 1.0F);
            }

            @Override
            public Color screenColor() {
                return new Color(0.0F, 0.0F, 0.0F, 1.0F);
            }

            @Override
            public IntSequence masks() {
                return emptyInts();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloats();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloats();
            }

            @Override
            public IntSequence indices() {
                return emptyInts();
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public int parentPartIndex() {
                return -1;
            }
        };
        final int[] warpOrdinaryWrites = {0};
        final var warpMorphKeys = new java.util.ArrayList<dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key>();
        final WarpDeformer warp = new WarpDeformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("WarpA");
            }

            @Override
            public float getOpacity() {
                return warpOpacity[0];
            }

            @Override
            public void setOpacity(final float value) {
                warpOpacity[0] = value;
            }

            @Override
            public WarpGrid grid() {
                return grid[0];
            }

            @Override
            public void replaceGrid(final WarpGrid value) {
                grid[0] = value;
            }

            @Override
            public dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot authoringSnapshot() {
                return new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot(
                        id(),
                        "warp-source",
                        grid[0].rows(),
                        grid[0].columns(),
                        grid[0].quadTransform(),
                        List.of(),
                        List.of(),
                        List.of(new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form(
                                "warp-form", grid[0].controlPoints())));
            }

            @Override
            public void setOrdinaryKeyformPositions(
                    dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot expected,
                    List<dev.turboism.sdk.cubism.model.WarpKeyformPositions> edits) {
                warpOrdinaryWrites[0]++;
            }

            @Override
            public dev.turboism.sdk.cubism.model.WarpMorphSnapshot morphSnapshot() {
                return new dev.turboism.sdk.cubism.model.WarpMorphSnapshot(
                        id(),
                        "warp-source",
                        new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                "base", grid[0].controlPoints(), warpOpacity[0]),
                        warpMorphKeys);
            }

            @Override
            public void createOpacityMorphKeyform(ParameterId parameter, float key, float opacity) {
                warpMorphKeys.add(new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key(
                        parameter.value(),
                        key,
                        new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                "key-" + warpMorphKeys.size(), grid[0].controlPoints(), opacity)));
            }

            @Override
            public void createMorphKeyform(ParameterId parameter, float key, List<Point2> controls) {
                warpMorphKeys.add(new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Key(
                        parameter.value(),
                        key,
                        new dev.turboism.sdk.cubism.model.WarpMorphSnapshot.Form(
                                "key-" + warpMorphKeys.size(), controls, warpOpacity[0])));
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }
        };
        final RotationDeformer rotation = new RotationDeformer() {
            @Override
            public DeformerId id() {
                return new DeformerId("RotationA");
            }

            @Override
            public float baseAngle() {
                return 30.0F;
            }

            @Override
            public RotationDeformerForm form() {
                return rotationForm[0];
            }

            @Override
            public void replaceForm(final RotationDeformerForm value) {
                rotationForm[0] = value;
            }

            @Override
            public void setBaseAngle(final float angle) {}

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyInts();
            }
        };
        access.connect(() -> new CubismModel() {
            @Override
            public ModelId id() {
                return new ModelId("model-a");
            }

            @Override
            public Parameters parameters() {
                throw unsupported();
            }

            @Override
            public Parts parts() {
                throw unsupported();
            }

            @Override
            public Drawables drawables() {
                return new Drawables() {
                    @Override
                    public List<Drawable> all() {
                        return List.of(drawable);
                    }

                    @Override
                    public Drawable find(final ArtMeshId id) {
                        return drawable;
                    }
                };
            }

            @Override
            public Deformers deformers() {
                throw unsupported();
            }

            @Override
            public WarpDeformers warpDeformers() {
                return new WarpDeformers() {
                    @Override
                    public List<WarpDeformer> all() {
                        return List.of(warp);
                    }

                    @Override
                    public WarpDeformer find(final DeformerId id) {
                        return warp;
                    }
                };
            }

            @Override
            public RotationDeformers rotationDeformers() {
                return new RotationDeformers() {
                    @Override
                    public List<RotationDeformer> all() {
                        return List.of(rotation);
                    }

                    @Override
                    public RotationDeformer find(final DeformerId id) {
                        return rotation;
                    }
                };
            }

            @Override
            public Glues glues() {
                throw unsupported();
            }

            @Override
            public void update() {
                throw unsupported();
            }
        });

        final Drawable wrappedDrawable = access.active().drawables().find(new ArtMeshId("ArtMeshA"));
        final WarpDeformer wrappedWarp = access.active().warpDeformers().find(new DeformerId("WarpA"));
        final RotationDeformer wrappedRotation =
                access.active().rotationDeformers().find(new DeformerId("RotationA"));
        wrappedDrawable.setOpacity(0.75F);
        wrappedWarp.setOpacity(0.5F);
        final var warpAuthoring = wrappedWarp.authoringSnapshot();
        assertEquals("warp-source", warpAuthoring.sourceGuid());
        wrappedWarp.setOrdinaryKeyformPositions(warpAuthoring, List.of());
        assertEquals(1, warpOrdinaryWrites[0]);
        wrappedWarp.createOpacityMorphKeyform(new ParameterId("Opacity"), 1, 0);
        wrappedWarp.createMorphKeyform(new ParameterId("Shape"), -1, grid[0].controlPoints());
        var warpMorphSnapshot = wrappedWarp.morphSnapshot();
        assertEquals(2, warpMorphSnapshot.keys().size());
        wrappedRotation.replaceForm(new RotationDeformerForm(10.0F, 2.0F, 3.0F, 1.5F, true, false));
        assertEquals(0.75F, wrappedDrawable.getOpacity());
        final var authoring = wrappedDrawable.authoringSnapshot();
        assertEquals("source-guid", authoring.sourceGuid());
        assertEquals(geometry[0], authoring.sourceGeometry());
        final var evaluation = wrappedDrawable.evaluationSnapshot();
        assertEquals("source-guid", evaluation.sourceGuid());
        assertEquals(7, evaluation.liveParameters().updateVersion());
        assertEquals(0.5F, wrappedWarp.getOpacity());
        assertEquals(10.0F, wrappedRotation.form().angle());
        final var coordinates =
                List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate("x-guid", 1));
        final var keyform = wrappedDrawable.ordinaryKeyform(coordinates);
        assertEquals(coordinates, keyform.coordinates());
        assertFalse(keyform.defaultKeyform());
        final var priorForm = keyform.snapshot();
        keyform.setPositions(List.of(new Point2(7, 8)));
        assertEquals(List.of(new Point2(7, 8)), keyform.snapshot().positions());
        assertEquals(List.of(new Point2(2, 3)), priorForm.positions());
        keyform.setDrawOrder(750);
        assertEquals(750, keyform.snapshot().drawOrder());
        assertEquals(500, priorForm.drawOrder());
        assertEquals(List.of(new Point2(7, 8)), keyform.snapshot().positions());
        final var morph = wrappedDrawable.morphKeyform("morph-guid", .5f);
        final var createdMorph = wrappedDrawable.createMorphKeyform("morph-guid", 1f);
        assertEquals("morph-guid", morph.parameterGuid());
        assertEquals(.5f, morph.keyValue());
        assertEquals(1f, createdMorph.keyValue());
        final var priorMorph = morph.snapshot();
        final var priorBase = morph.baseSnapshot();
        morph.setPositions(List.of(new Point2(12, 13)));
        assertEquals(List.of(new Point2(12, 13)), morph.snapshot().positions());
        assertEquals(List.of(new Point2(2, 3)), priorMorph.positions());
        morph.setDrawOrder(250);
        assertEquals(250, morph.snapshot().drawOrder());
        assertEquals(500, priorMorph.drawOrder());
        assertEquals(List.of(new Point2(12, 13)), morph.snapshot().positions());

        access.deactivate();
        assertThrows(
                IllegalStateException.class,
                () -> wrappedDrawable.setOrdinaryKeyforms(authoring, List.of(), List.of()));
        assertThrows(IllegalStateException.class, wrappedDrawable::geometry);
        assertThrows(IllegalStateException.class, wrappedDrawable::authoringSnapshot);
        assertThrows(IllegalStateException.class, wrappedDrawable::evaluationSnapshot);
        assertEquals("source-guid", evaluation.sourceGuid(), "calculated values survive the lease");
        assertEquals("source-guid", authoring.sourceGuid(), "detached values survive the lease");
        assertThrows(IllegalStateException.class, () -> wrappedDrawable.setOpacity(0.25F));
        assertThrows(IllegalStateException.class, () -> wrappedDrawable.ordinaryKeyform(coordinates));
        assertThrows(
                IllegalStateException.class, () -> wrappedDrawable.setOrdinaryKeyformPositions(authoring, List.of()));
        assertThrows(IllegalStateException.class, keyform::coordinates);
        assertThrows(IllegalStateException.class, keyform::snapshot);
        assertThrows(IllegalStateException.class, keyform::defaultKeyform);
        assertThrows(IllegalStateException.class, () -> keyform.setPositions(List.of(new Point2(9, 9))));
        assertThrows(IllegalStateException.class, () -> keyform.setDrawOrder(600));
        assertEquals(500, priorForm.drawOrder(), "detached order survives the lease");
        assertThrows(IllegalStateException.class, () -> wrappedDrawable.morphKeyform("morph-guid", .5f));
        assertThrows(IllegalStateException.class, () -> wrappedDrawable.createMorphKeyform("morph-guid", .5f));
        assertThrows(IllegalStateException.class, morph::parameterGuid);
        assertThrows(IllegalStateException.class, morph::keyValue);
        assertThrows(IllegalStateException.class, morph::snapshot);
        assertThrows(IllegalStateException.class, morph::baseSnapshot);
        assertThrows(IllegalStateException.class, () -> morph.setPositions(List.of(new Point2(1, 1))));
        assertThrows(IllegalStateException.class, () -> morph.setDrawOrder(600));
        assertEquals(500, priorMorph.drawOrder(), "detached order survives the lease");
        assertThrows(IllegalStateException.class, createdMorph::snapshot);
        assertEquals("base-form", priorBase.formGuid());
        assertThrows(IllegalStateException.class, wrappedWarp::morphSnapshot);
        assertThrows(
                IllegalStateException.class,
                () -> wrappedWarp.createOpacityMorphKeyform(new ParameterId("Opacity"), 1, 0));
        assertThrows(
                IllegalStateException.class,
                () -> wrappedWarp.createMorphKeyform(new ParameterId("Shape"), -1, grid[0].controlPoints()));
        assertEquals(2, warpMorphSnapshot.keys().size(), "detached Warp morphs survive the lease");
        assertEquals(2, warpMorphKeys.size(), "stale writes never reach the delegate");
        assertThrows(IllegalStateException.class, wrappedWarp::grid);
        assertThrows(IllegalStateException.class, wrappedWarp::authoringSnapshot);
        assertThrows(
                IllegalStateException.class, () -> wrappedWarp.setOrdinaryKeyformPositions(warpAuthoring, List.of()));
        assertEquals(1, warpOrdinaryWrites[0]);
        assertThrows(IllegalStateException.class, () -> wrappedWarp.setOpacity(0.25F));
        assertThrows(IllegalStateException.class, wrappedRotation::form);
        assertThrows(IllegalStateException.class, () -> wrappedRotation.replaceForm(rotationForm[0]));
    }

    @Test
    void replacementDuringDelegateReadCannotReturnAnApparentlyCurrentValue() throws Exception {
        DynamicCubismModelAccess access = new DynamicCubismModelAccess();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        access.connect(() -> model("model-a", new Parameter() {
            @Override
            public ParameterId id() {
                return new ParameterId("ParamA");
            }

            @Override
            public float getValue() {
                entered.countDown();
                await(release);
                return 1.0F;
            }

            @Override
            public float getMinimumValue() {
                return 0.0F;
            }

            @Override
            public float getMaximumValue() {
                return 2.0F;
            }

            @Override
            public float getDefaultValue() {
                return 1.0F;
            }

            @Override
            public void setValue(final float value) {
                throw unsupported();
            }
        }));
        Parameter stale = access.active().parameters().find(new ParameterId("ParamA"));
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread reader = new Thread(() -> {
            try {
                stale.getValue();
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        reader.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CountDownLatch replacementFinished = new CountDownLatch(1);
        Thread replacement = new Thread(() -> {
            access.connect(modelAccess("model-b", 2.0F));
            replacementFinished.countDown();
        });
        replacement.start();
        assertTrue(
                !replacementFinished.await(100, TimeUnit.MILLISECONDS),
                "replacement must wait for the in-flight delegate read");
        release.countDown();
        reader.join(5_000);
        replacement.join(5_000);

        assertEquals(null, failure.get());
        assertEquals(new ModelId("model-b"), access.active().id());
    }
}
