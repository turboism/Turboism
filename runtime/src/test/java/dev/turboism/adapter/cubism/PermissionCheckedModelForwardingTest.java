package dev.turboism.adapter.cubism;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.permissions.CubismPermissionGate;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.AutoYure;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.CubismModelAccess;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.ModelProfile;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
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
import dev.turboism.sdk.cubism.model.PhysicsSettings;
import dev.turboism.sdk.cubism.model.PhysicsSettingsSource;
import dev.turboism.sdk.permission.CubismPermissionException;
import dev.turboism.sdk.permission.PluginPermission;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * Focused permission-gate coverage for the Wave 1 SDK method forwardings added
 * to the PermissionChecked wrapper layer of {@link CubismFacadeImpl}.
 */
class PermissionCheckedModelForwardingTest {

    private static final Clock FIXED_CLOCK = Clock.fixed(Instant.parse("2026-07-07T00:00:00Z"), ZoneOffset.UTC);

    private static final ModelProfile PROFILE = new ModelProfile() {
        @Override
        public float pixelsPerUnit() {
            return 1.0F;
        }

        @Override
        public float originXPixels() {
            return 2.0F;
        }

        @Override
        public float originYPixels() {
            return 3.0F;
        }
    };

    private static final PhysicsSettings PHYSICS = new PhysicsSettings() {
        @Override
        public float gravityX() {
            return 0.0F;
        }

        @Override
        public float gravityY() {
            return 1.0F;
        }

        @Override
        public float windX() {
            return 0.0F;
        }

        @Override
        public float windY() {
            return 0.0F;
        }

        @Override
        public Integer settingFps() {
            return 30;
        }

        @Override
        public List<PhysicsSettingsSource> sources() {
            return List.of();
        }
    };

    private static final AutoYure AUTO_YURE = () -> List.of();

    private static final AnimationDocument ANIMATION = new AnimationDocument() {
        @Override
        public String animationName() {
            return "anim-1";
        }

        @Override
        public int sceneCount() {
            return 1;
        }

        @Override
        public Optional<String> currentSceneName() {
            return Optional.of("scene-1");
        }

        @Override
        public List<String> sceneNames() {
            return List.of("scene-1");
        }
    };

    private static final MorphTargets EMPTY_MORPH_TARGETS = new MorphTargets() {
        @Override
        public List<MorphTarget> all() {
            return List.of();
        }

        @Override
        public MorphTarget find(final ParameterId id) {
            throw new java.util.NoSuchElementException();
        }
    };

    private static final ParameterDefinition DEFINITION = new ParameterDefinition(
            new ParameterId("ParamNew"), "Param New", 0.0F, 0.5F, 1.0F, ParameterType.NORMAL, false);

    private static final ParameterId PARAMETER_ID = new ParameterId("ParamA");
    private static final dev.turboism.sdk.cubism.model.ModelTextures TEXTURES =
            new dev.turboism.sdk.cubism.model.ModelTextures() {
                @Override
                public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                    return List.of();
                }

                @Override
                public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                    return List.of();
                }

                @Override
                public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                    return List.of();
                }

                @Override
                public void addModelImageGroup(final String name) {}

                @Override
                public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {}

                @Override
                public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                        final String name, final int widthPixels, final int heightPixels) {
                    return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
                }

                @Override
                public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

                @Override
                public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {}
            };
    private static final ParameterGroupId GROUP_ID = new ParameterGroupId("GroupA");
    private static final PartId PART_ID = new PartId("PartA");
    private static final ArtMeshId MESH_ID = new ArtMeshId("ArtMeshA");
    private static final dev.turboism.sdk.cubism.id.ModelImageId MODEL_IMAGE_ID =
            new dev.turboism.sdk.cubism.id.ModelImageId("image-a");
    private static final dev.turboism.sdk.cubism.model.CubismRenderedPixels PIXELS =
            new dev.turboism.sdk.cubism.model.CubismRenderedPixels(2, 1, new int[] {1, 2});
    private static final dev.turboism.sdk.cubism.model.ModelImageBinding BINDING =
            new dev.turboism.sdk.cubism.model.ModelImageBinding(
                    "plugin.demo",
                    new ModelId("model-a"),
                    1L,
                    new dev.turboism.sdk.cubism.id.RawImageId("raw-a"),
                    MODEL_IMAGE_ID,
                    Optional.of(MESH_ID));

    private static final dev.turboism.sdk.cubism.model.CubismMocExportSettings EXPORT_SETTINGS =
            new dev.turboism.sdk.cubism.model.CubismMocExportSettings(0, 0, 100, false, false, false, false, false);
    private static final dev.turboism.sdk.cubism.model.CubismMocExport EXPORT = exportFixture();

    private static dev.turboism.sdk.cubism.model.CubismMocExport exportFixture() {
        byte[] bytes = new byte[64];
        bytes[0] = 'M';
        bytes[1] = 'O';
        bytes[2] = 'C';
        bytes[3] = '3';
        return new dev.turboism.sdk.cubism.model.CubismMocExport(bytes, List.of(), EXPORT_SETTINGS);
    }

    @Test
    void interpolationChecksRevokedPermissionsAndActiveScopeOnRetainedOperations() {
        var calls = new ArrayList<String>();
        boolean[] read = {true}, write = {true}, active = {true};
        var model = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active();
        var operations = model.parameterBindings(PARAMETER_ID);
        var target = ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshA"));
        var settings = new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 6, 1);
        assertEquals(settings, operations.interpolation(target));
        operations.setInterpolation(target, settings);
        assertEquals(List.of("interpolation.read:ArtMeshA", "interpolation.write:ArtMeshA:6"), calls);
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> operations.setInterpolation(target, settings));
        read[0] = false;
        assertThrows(CubismPermissionException.class, () -> operations.interpolation(target));
        read[0] = true;
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> operations.interpolation(target));
        assertThrows(IllegalStateException.class, () -> operations.setInterpolation(target, settings));
        assertEquals(2, calls.size());
    }

    @Test
    void modelReadForwardingsDelegateToBackendWhenReadIsGranted() {
        final List<String> calls = new ArrayList<>();
        final CubismModel model = facade(List.of(readPermission(true), writePermission(true)), calls)
                .model()
                .active();

        assertSame(PROFILE, model.profile());
        assertSame(PHYSICS, model.physicsSettings());
        assertSame(AUTO_YURE, model.autoYure());
        // Animation documents are permission-checked wrappers: identity differs but the
        // delegate's contract is preserved verbatim through the facade.
        assertEquals(
                List.of(ANIMATION.animationName()),
                model.animationDocuments().stream()
                        .map(AnimationDocument::animationName)
                        .toList());
        assertEquals(List.of(), model.textures().rawImages());
        assertSame(PIXELS, model.renderPixels(2, 1));
        assertSame(EXPORT, model.exportMoc(EXPORT_SETTINGS));
        assertEquals(
                List.of(
                        "profile",
                        "physicsSettings",
                        "autoYure",
                        "animationDocuments",
                        "textures",
                        "renderPixels",
                        "exportMoc"),
                calls);
    }

    @Test
    void modelReadForwardingsFailClosedWithoutReadPermission() {
        final List<String> calls = new ArrayList<>();
        final boolean[] readGranted = {true};
        final CubismModel model = facade(List.of(readPermission(readGranted), writePermission(true)), calls)
                .model()
                .active();
        readGranted[0] = false;

        assertThrows(CubismPermissionException.class, model::profile);
        assertThrows(CubismPermissionException.class, model::physicsSettings);
        assertThrows(CubismPermissionException.class, model::autoYure);
        assertThrows(CubismPermissionException.class, model::animationDocuments);
        assertThrows(CubismPermissionException.class, model::textures);
        assertThrows(CubismPermissionException.class, () -> model.renderPixels(2, 1));
        assertThrows(CubismPermissionException.class, () -> model.exportMoc(EXPORT_SETTINGS));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
    }

    @Test
    void parameterWritesDelegateAndWrapReturnValuesWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final Parameters parameters = facade(delegate, List.of(readPermission(true), writePermission(true)))
                .model()
                .active()
                .parameters();

        final Parameter created = parameters.create(DEFINITION);
        final Parameter createdInFolder = parameters.create(DEFINITION, Optional.of(GROUP_ID));
        final Parameter copied = parameters.copy(PARAMETER_ID);
        parameters.remove(PARAMETER_ID);

        assertNotSame(created, delegate.parameter(), "create must return a PermissionChecked wrapper");
        assertNotSame(createdInFolder, delegate.parameter(), "create must return a PermissionChecked wrapper");
        assertNotSame(copied, delegate.parameter(), "copy must return a PermissionChecked wrapper");
    }

    @Test
    void parameterWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final Parameters parameters =
                facade(List.of(readPermission(true)), calls).model().active().parameters();

        assertThrows(CubismPermissionException.class, () -> parameters.create(DEFINITION));
        assertThrows(CubismPermissionException.class, () -> parameters.create(DEFINITION, Optional.of(GROUP_ID)));
        assertThrows(CubismPermissionException.class, () -> parameters.copy(PARAMETER_ID));
        assertThrows(CubismPermissionException.class, () -> parameters.remove(PARAMETER_ID));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void parameterGroupWritesDelegateAndWrapReturnValueWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final ParameterGroups groups = facade(delegate, List.of(readPermission(true), writePermission(true)))
                .model()
                .active()
                .parameterGroups();

        final ParameterGroup created = groups.addGroup("Group B");
        groups.removeGroup(GROUP_ID);
        groups.moveParameter(PARAMETER_ID, GROUP_ID);

        assertNotSame(created, delegate.group(), "addGroup must return a PermissionChecked wrapper");
    }

    @Test
    void wrappedResultsRemainPermissionCheckedAfterWriteRevocation() {
        final List<String> calls = new ArrayList<>();
        final boolean[] writeGranted = {true};
        final CubismModel model = facade(List.of(readPermission(true), writePermission(writeGranted)), calls)
                .model()
                .active();

        final Parameter created = model.parameters().create(DEFINITION);
        final ParameterGroup addedGroup = model.parameterGroups().addGroup("Group B");
        final Part addedPart = model.parts().add(PART_ID);

        writeGranted[0] = false;

        assertThrows(CubismPermissionException.class, () -> created.setValue(1.0F));
        assertThrows(CubismPermissionException.class, () -> addedGroup.rename("renamed"));
        assertThrows(CubismPermissionException.class, () -> addedPart.setVisible(true));
        // The denied writes must not reach the delegate.
        assertEquals(List.of("create:ParamNew", "addGroup:Group B", "add:PartA"), calls);
    }

    @Test
    void parameterGroupWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final ParameterGroups groups =
                facade(List.of(readPermission(true)), calls).model().active().parameterGroups();

        assertThrows(CubismPermissionException.class, () -> groups.addGroup("Group B"));
        assertThrows(CubismPermissionException.class, () -> groups.removeGroup(GROUP_ID));
        assertThrows(CubismPermissionException.class, () -> groups.moveParameter(PARAMETER_ID, GROUP_ID));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void textureRegisterAndBindWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls).withTextures(recordingTextures(calls));
        final var textures =
                facade(delegate, List.of(readPermission(true))).model().active().textures();

        assertThrows(CubismPermissionException.class, () -> textures.registerImageAndBind(imageGrant(), MESH_ID));
        assertThrows(CubismPermissionException.class, () -> textures.bindModelImage(MODEL_IMAGE_ID, MESH_ID));
        assertEquals(
                List.of("textures"),
                calls,
                "register/bind must not reach the delegate when write permission is denied");
    }

    @Test
    void textureRegisterAndBindWritesDelegateWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls).withTextures(recordingTextures(calls));
        final var textures = facade(delegate, List.of(readPermission(true), writePermission(true)))
                .model()
                .active()
                .textures();

        assertSame(BINDING, textures.registerImageAndBind(imageGrant(), MESH_ID));
        assertSame(BINDING, textures.bindModelImage(MODEL_IMAGE_ID, MESH_ID));
        assertEquals(List.of("textures", "registerImageAndBind:ArtMeshA", "bindModelImage:ArtMeshA"), calls);
    }

    @Test
    void partWritesDelegateAndWrapReturnValuesWhenWriteIsGranted() {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls);
        final Parts parts = facade(delegate, List.of(readPermission(true), writePermission(true)))
                .model()
                .active()
                .parts();

        final Part added = parts.add(PART_ID);
        final Part addedUnder = parts.add(PART_ID, PART_ID);
        final Part copied = parts.copy(PART_ID);
        parts.remove(PART_ID);

        assertNotSame(added, delegate.part(), "add must return a PermissionChecked wrapper");
        assertNotSame(addedUnder, delegate.part(), "add must return a PermissionChecked wrapper");
        assertNotSame(copied, delegate.part(), "copy must return a PermissionChecked wrapper");

        // String overloads route through the PartId overloads (SDK default), so they
        // are permission-checked too.
        final Part addedByString = parts.add("PartString");
        final Part addedUnderByString = parts.add("PartString", PART_ID);
        assertNotSame(addedByString, delegate.part(), "String add must return a PermissionChecked wrapper");
        assertNotSame(addedUnderByString, delegate.part(), "String add must return a PermissionChecked wrapper");
    }

    @Test
    void partWritesFailClosedWithoutWritePermission() {
        final List<String> calls = new ArrayList<>();
        final Parts parts =
                facade(List.of(readPermission(true)), calls).model().active().parts();

        assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID, PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.add("PartString"));
        assertThrows(CubismPermissionException.class, () -> parts.copy(PART_ID));
        assertThrows(CubismPermissionException.class, () -> parts.remove(PART_ID));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void parameterGroupRenameDelegatesWhenWriteIsGrantedAndFailsClosedWithout() {
        final List<String> calls = new ArrayList<>();
        final ParameterGroup group = facade(List.of(readPermission(true), writePermission(true)), calls)
                .model()
                .active()
                .parameterGroups()
                .root();

        group.rename("Renamed");
        assertEquals(List.of("rename:Renamed"), calls);

        calls.clear();
        final boolean[] writeGranted = {true};
        final ParameterGroup guarded = facade(List.of(readPermission(true), writePermission(writeGranted)), calls)
                .model()
                .active()
                .parameterGroups()
                .root();
        writeGranted[0] = false;
        assertThrows(CubismPermissionException.class, () -> guarded.rename("Renamed"));
        assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
    }

    @Test
    void detachedSourcePreviewRequiresOnlyReadAndHonorsRevocation() {
        final List<String> calls = new ArrayList<>();
        final boolean[] granted = {true};
        final var values = facade(List.of(readPermission(granted), writePermission(false)), calls)
                .model()
                .active()
                .drawables();
        final var geometry = AUTHORING.sourceGeometry();
        final var result = values.previewSourceTriangulation(geometry);
        assertEquals(geometry.positions(), result.positions());
        assertEquals(geometry.triangleIndices(), result.triangleIndices());
        assertEquals(List.of("drawable.previewSourceTriangulation"), calls);
        granted[0] = false;
        assertThrows(CubismPermissionException.class, () -> values.previewSourceTriangulation(geometry));
        assertEquals(1, calls.size());
    }

    @Test
    void separateSourceCreationChecksWritePermissionAndWrapsResult() {
        final List<String> calls = new ArrayList<>();
        final boolean[] writeGranted = {true};
        final boolean[] readGranted = {true};
        final var drawables = facade(List.of(readPermission(readGranted), writePermission(writeGranted)), calls)
                .model()
                .active()
                .drawables();
        final var geometry = AUTHORING.sourceGeometry();
        final var created = drawables.createWithSourceGeometry("Unfolded", null, -1, geometry, List.of());
        assertEquals(List.of("drawable.createWithSourceGeometry:Unfolded"), calls);
        writeGranted[0] = false;
        assertThrows(
                CubismPermissionException.class,
                () -> drawables.createWithSourceGeometry("Denied", null, -1, geometry, List.of()));
        assertEquals(1, calls.size());
        readGranted[0] = false;
        assertThrows(CubismPermissionException.class, created::id);
    }

    @Test
    void morphTargetsReadsDelegateToBackendWhenReadIsGranted() {
        final List<String> calls = new ArrayList<>();
        final CubismModel model = facade(List.of(readPermission(true), writePermission(true)), calls)
                .model()
                .active();

        assertSame(EMPTY_MORPH_TARGETS, model.parts().find(PART_ID).morphTargets());
        assertSame(
                EMPTY_MORPH_TARGETS,
                model.drawables().find(new ArtMeshId("ArtMeshA")).morphTargets());
        assertEquals(List.of("part.morphTargets", "drawable.morphTargets"), calls);
    }

    @Test
    void morphTargetsReadsFailClosedWithoutReadPermission() {
        final List<String> calls = new ArrayList<>();
        final boolean[] readGranted = {true};
        final CubismModel model = facade(List.of(readPermission(readGranted), writePermission(true)), calls)
                .model()
                .active();
        final Part part = model.parts().find(PART_ID);
        final Drawable drawable = model.drawables().find(new ArtMeshId("ArtMeshA"));
        readGranted[0] = false;

        assertThrows(CubismPermissionException.class, part::morphTargets);
        assertThrows(CubismPermissionException.class, drawable::morphTargets);
        assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
    }

    private static final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot AUTHORING =
            new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                    new ArtMeshId("ArtMeshA"),
                    "source-guid",
                    new dev.turboism.sdk.cubism.model.ArtMeshGeometry(List.of(), List.of(), List.of()),
                    List.of(),
                    List.of(),
                    List.of(),
                    List.of());
    private static final dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot EVALUATION =
            new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot(
                    new ArtMeshId("ArtMeshA"),
                    "source-guid",
                    java.util.Optional.empty(),
                    new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(7, List.of()),
                    new dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot.ParameterState(13, List.of()),
                    -1);

    @Test
    void evaluationSnapshotRequiresReadAndScopeButNotWrite() {
        final List<String> calls = new ArrayList<>();
        final boolean[] granted = {true}, active = {true};
        final Drawable drawable = facade(new RecordingModel(calls), List.of(readPermission(granted)), () -> active[0])
                .model()
                .active()
                .drawables()
                .find(new ArtMeshId("ArtMeshA"));
        assertSame(EVALUATION, drawable.evaluationSnapshot());
        assertEquals(List.of("drawable.evaluationSnapshot"), calls);
        calls.clear();
        granted[0] = false;
        assertThrows(CubismPermissionException.class, drawable::evaluationSnapshot);
        granted[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, drawable::evaluationSnapshot);
        assertTrue(calls.isEmpty());
        assertEquals("source-guid", EVALUATION.sourceGuid());
    }

    @Test
    void authoringSnapshotRequiresReadAndActiveScopeWithoutRequiringWrite() {
        final List<String> calls = new ArrayList<>();
        final boolean[] granted = {true};
        final boolean[] active = {true};
        final Drawable drawable = facade(new RecordingModel(calls), List.of(readPermission(granted)), () -> active[0])
                .model()
                .active()
                .drawables()
                .find(new ArtMeshId("ArtMeshA"));
        assertSame(AUTHORING, drawable.authoringSnapshot());
        assertEquals(List.of("drawable.authoringSnapshot"), calls);
        calls.clear();
        granted[0] = false;
        assertThrows(CubismPermissionException.class, drawable::authoringSnapshot);
        granted[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, drawable::authoringSnapshot);
        assertTrue(calls.isEmpty());
    }

    @Test
    void warpStoredFormsRetainReadWriteAndActiveScopeChecks() {
        final List<String> calls = new ArrayList<>();
        final boolean[] read = {true}, write = {true}, active = {true};
        final var warp = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active()
                .warpDeformers()
                .find(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
        final var snapshot = warp.authoringSnapshot();
        assertEquals(List.of("warp.authoringSnapshot"), calls);
        calls.clear();
        warp.setOrdinaryKeyformPositions(snapshot, List.of());
        assertTrue(calls.contains("warp.setOrdinaryKeyformPositions"));
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> warp.setOrdinaryKeyformPositions(snapshot, List.of()));
        assertTrue(calls.isEmpty());
        assertSame(snapshot, warp.authoringSnapshot());
        calls.clear();
        read[0] = false;
        assertThrows(CubismPermissionException.class, warp::authoringSnapshot);
        read[0] = true;
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, warp::authoringSnapshot);
        assertThrows(IllegalStateException.class, () -> warp.setOrdinaryKeyformPositions(snapshot, List.of()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void rotationStoredFormsRetainReadWriteAndActiveScopeChecks() {
        final List<String> calls = new ArrayList<>();
        final boolean[] read = {true}, write = {true}, active = {true};
        final var rotation = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active()
                .rotationDeformers()
                .find(new dev.turboism.sdk.cubism.id.DeformerId("RollA"));
        final var snapshot = rotation.authoringSnapshot();
        assertEquals(List.of("rotation.authoringSnapshot"), calls);
        calls.clear();
        rotation.setOrdinaryKeyformForms(snapshot, List.of());
        assertTrue(calls.contains("rotation.setOrdinaryKeyformForms"));
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> rotation.setOrdinaryKeyformForms(snapshot, List.of()));
        assertTrue(calls.isEmpty());
        assertSame(snapshot, rotation.authoringSnapshot());
        calls.clear();
        read[0] = false;
        assertThrows(CubismPermissionException.class, rotation::authoringSnapshot);
        read[0] = true;
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, rotation::authoringSnapshot);
        assertThrows(IllegalStateException.class, () -> rotation.setOrdinaryKeyformForms(snapshot, List.of()));
        assertTrue(calls.isEmpty());
    }

    @Test
    void ordinaryKeyformMethodsRetainReadWriteAndScopeChecksAfterLookup() {
        final List<String> calls = new ArrayList<>();
        final boolean[] read = {true}, write = {false}, active = {true};
        final var drawable = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active()
                .drawables()
                .find(new ArtMeshId("ArtMeshA"));
        final var coordinates = List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate("x", 1));
        final var target = drawable.ordinaryKeyform(coordinates);
        assertEquals(coordinates, target.coordinates());
        assertFalse(target.defaultKeyform());
        assertEquals("form-guid", target.snapshot().formGuid());
        calls.clear();
        assertThrows(CubismPermissionException.class, () -> target.setPositions(List.of()));
        assertThrows(CubismPermissionException.class, () -> target.setDrawOrder(600));
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertTrue(calls.isEmpty());
        read[0] = false;
        assertThrows(CubismPermissionException.class, target::snapshot);
        assertThrows(CubismPermissionException.class, target::coordinates);
        assertThrows(CubismPermissionException.class, target::defaultKeyform);
        assertThrows(CubismPermissionException.class, () -> drawable.ordinaryKeyform(coordinates));
        assertTrue(calls.isEmpty());
        write[0] = true;
        drawable.setOrdinaryKeyformPositions(null, List.of());
        assertEquals(1, calls.stream().filter("drawable.keyformBatch"::equals).count());
        target.setPositions(List.of(new dev.turboism.sdk.cubism.model.Point2(4, 5)));
        assertEquals(1, calls.stream().filter("keyform.setPositions"::equals).count());
        target.setDrawOrder(600);
        assertEquals(1, calls.stream().filter("keyform.setDrawOrder"::equals).count());
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertThrows(CubismPermissionException.class, () -> target.setDrawOrder(700));
        assertTrue(calls.isEmpty());
        write[0] = true;
        calls.clear();
        read[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, target::snapshot);
        assertThrows(IllegalStateException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertThrows(IllegalStateException.class, target::coordinates);
        assertThrows(IllegalStateException.class, target::defaultKeyform);
        assertThrows(IllegalStateException.class, () -> target.setPositions(List.of()));
        assertThrows(IllegalStateException.class, () -> target.setDrawOrder(700));
        assertTrue(calls.isEmpty());
    }

    @Test
    void morphReferencesRetainReadWriteAndScopeChecksForEveryMethod() {
        final List<String> calls = new ArrayList<>();
        final boolean[] read = {true}, write = {false}, active = {true};
        final var drawable = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active()
                .drawables()
                .find(new ArtMeshId("ArtMeshA"));
        final var target = drawable.morphKeyform("m", .5f);
        assertEquals("m", target.parameterGuid());
        assertEquals(.5f, target.keyValue());
        assertEquals("morph-form", target.snapshot().formGuid());
        assertEquals("base", target.baseSnapshot().formGuid());
        calls.clear();
        assertThrows(CubismPermissionException.class, () -> target.setPositions(List.of()));
        assertThrows(CubismPermissionException.class, () -> target.setDrawOrder(700));
        assertThrows(CubismPermissionException.class, () -> drawable.createMorphKeyform("m", 1f));
        read[0] = false;
        assertThrows(CubismPermissionException.class, () -> drawable.morphKeyform("m", .5f));
        assertThrows(CubismPermissionException.class, target::parameterGuid);
        assertThrows(CubismPermissionException.class, target::keyValue);
        assertThrows(CubismPermissionException.class, target::snapshot);
        assertThrows(CubismPermissionException.class, target::baseSnapshot);
        assertTrue(calls.isEmpty());
        write[0] = true;
        target.setPositions(List.of(new dev.turboism.sdk.cubism.model.Point2(4, 5)));
        target.setDrawOrder(700);
        final var created = drawable.createMorphKeyform("m", 1f);
        assertThrows(CubismPermissionException.class, created::snapshot);
        assertTrue(calls.contains("morph.setPositions"));
        assertTrue(calls.contains("morph.setDrawOrder"));
        assertTrue(calls.contains("drawable.createMorphKeyform"));
        calls.clear();
        read[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> drawable.morphKeyform("m", .5f));
        assertThrows(IllegalStateException.class, () -> drawable.createMorphKeyform("m", 1f));
        assertThrows(IllegalStateException.class, target::parameterGuid);
        assertThrows(IllegalStateException.class, target::keyValue);
        assertThrows(IllegalStateException.class, target::snapshot);
        assertThrows(IllegalStateException.class, target::baseSnapshot);
        assertThrows(IllegalStateException.class, () -> target.setPositions(List.of()));
        assertThrows(IllegalStateException.class, () -> target.setDrawOrder(800));
        assertTrue(calls.isEmpty());
    }

    @Test
    void keyformSemanticEventsDistinguishChangedNoopAndFailedWrites() {
        final var lifecycle = new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator();
        final var semantic = lifecycle.semantic();
        final List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final var descriptor = (dev.turboism.sdk.plugin.PluginDescriptor) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(),
                new Class<?>[] {dev.turboism.sdk.plugin.PluginDescriptor.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("id")) return "keyform-observer";
                    throw new AssertionError("Unexpected descriptor method: " + method);
                });
        final var logger = new dev.turboism.sdk.plugin.PluginLogger() {
            @Override
            public void debug(String message) {}

            @Override
            public void info(String message) {}

            @Override
            public void warn(String message) {
                errors.add(message);
            }

            @Override
            public void error(String message) {
                errors.add(message);
            }

            @Override
            public void error(String message, Throwable error) {
                errors.add(message);
            }
        };
        final var hook = new dev.turboism.sdk.cubism.hook.SemanticOperationHooks() {
            private void record(String phase, dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                events.add(phase + ":" + event.operation() + ":"
                        + event.subjectId().orElse("missing"));
            }

            @Override
            public void beforeCubismOperation(dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                record("before", event);
            }

            @Override
            public void onCubismOperationConfirmed(dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                record("on", event);
            }

            @Override
            public void afterCubismOperation(dev.turboism.sdk.cubism.event.CubismOperationEvent event) {
                record("after", event);
            }
        };
        semantic.register(new dev.turboism.adapter.cubism.lifecycle.SemanticOperationLifecycleCoordinator.PluginHooks(
                descriptor, List.of(hook), logger));
        try {
            final var delegate = new RecordingModel(new ArrayList<>());
            final var target = facade(
                            delegate, List.of(readPermission(true), writePermission(true)), () -> true, lifecycle)
                    .model()
                    .active()
                    .drawables()
                    .find(new ArtMeshId("ArtMeshA"))
                    .ordinaryKeyform(
                            List.of(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate("x", 1)));
            final var positions = List.of(new dev.turboism.sdk.cubism.model.Point2(4, 5));
            final String suffix = ":SET_DRAWABLE_KEYFORM_POSITIONS:ArtMeshA:form-guid";
            target.setPositions(positions);
            semantic.awaitIdle();
            assertEquals(List.of("before" + suffix, "on" + suffix, "after" + suffix), events);
            events.clear();
            target.setPositions(positions);
            semantic.awaitIdle();
            assertEquals(List.of("before" + suffix, "after" + suffix), events);
            events.clear();
            delegate.failKeyformWrite = true;
            assertThrows(IllegalStateException.class, () -> target.setPositions(List.of()));
            semantic.awaitIdle();
            assertEquals(List.of("before" + suffix), events);
            assertEquals(positions, target.snapshot().positions());
            assertTrue(errors.isEmpty(), errors.toString());
            events.clear();
            delegate.failKeyformWrite = false;
            final String orderSuffix = ":SET_DRAWABLE_KEYFORM_DRAW_ORDER:ArtMeshA:form-guid";
            target.setDrawOrder(600);
            semantic.awaitIdle();
            assertEquals(List.of("before" + orderSuffix, "on" + orderSuffix, "after" + orderSuffix), events);
            assertEquals(positions, target.snapshot().positions());
            events.clear();
            target.setDrawOrder(600);
            semantic.awaitIdle();
            assertEquals(List.of("before" + orderSuffix, "after" + orderSuffix), events);
            events.clear();
            delegate.calls.clear();
            for (final int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> target.setDrawOrder(invalid));
            }
            semantic.awaitIdle();
            assertTrue(events.isEmpty());
            assertTrue(delegate.calls.isEmpty(), "bounds rejected before snapshots, events or writes");
            delegate.failKeyformWrite = true;
            assertThrows(IllegalStateException.class, () -> target.setDrawOrder(700));
            semantic.awaitIdle();
            assertEquals(List.of("before" + orderSuffix), events);
            assertEquals(600, target.snapshot().drawOrder());
            assertEquals(positions, target.snapshot().positions());
            assertTrue(errors.isEmpty(), errors.toString());
            events.clear();
            delegate.failKeyformWrite = false;
            final var drawable = facade(
                            delegate, List.of(readPermission(true), writePermission(true)), () -> true, lifecycle)
                    .model()
                    .active()
                    .drawables()
                    .find(new ArtMeshId("ArtMeshA"));
            final String createSuffix = ":CREATE_DRAWABLE_MORPH_KEYFORM:ArtMeshA:m:0.5";
            final var morph = drawable.createMorphKeyform("m", .5f);
            semantic.awaitIdle();
            assertEquals(List.of("before" + createSuffix, "on" + createSuffix, "after" + createSuffix), events);
            events.clear();
            final String morphSuffix = ":SET_DRAWABLE_MORPH_KEYFORM_POSITIONS:ArtMeshA:morph-form";
            morph.setPositions(positions);
            semantic.awaitIdle();
            assertEquals(List.of("before" + morphSuffix, "on" + morphSuffix, "after" + morphSuffix), events);
            events.clear();
            morph.setPositions(positions);
            semantic.awaitIdle();
            assertEquals(List.of("before" + morphSuffix, "after" + morphSuffix), events);
            events.clear();
            delegate.failKeyformWrite = true;
            assertThrows(IllegalStateException.class, () -> morph.setPositions(List.of()));
            semantic.awaitIdle();
            assertEquals(List.of("before" + morphSuffix), events);
            events.clear();
            delegate.failKeyformWrite = false;
            final String morphOrderSuffix = ":SET_DRAWABLE_MORPH_KEYFORM_DRAW_ORDER:ArtMeshA:morph-form";
            morph.setDrawOrder(700);
            semantic.awaitIdle();
            assertEquals(
                    List.of("before" + morphOrderSuffix, "on" + morphOrderSuffix, "after" + morphOrderSuffix), events);
            events.clear();
            morph.setDrawOrder(700);
            semantic.awaitIdle();
            assertEquals(List.of("before" + morphOrderSuffix, "after" + morphOrderSuffix), events);
            events.clear();
            delegate.calls.clear();
            for (final int invalid : List.of(-1, 1001, Integer.MIN_VALUE, Integer.MAX_VALUE)) {
                assertThrows(IllegalArgumentException.class, () -> morph.setDrawOrder(invalid));
            }
            semantic.awaitIdle();
            assertTrue(events.isEmpty());
            assertFalse(
                    delegate.calls.contains("morph.setDrawOrder"),
                    "bounds rejected before snapshots, events or writes");
            delegate.failKeyformWrite = true;
            assertThrows(IllegalStateException.class, () -> morph.setDrawOrder(800));
            semantic.awaitIdle();
            assertEquals(List.of("before" + morphOrderSuffix), events);
            assertEquals(700, morph.snapshot().drawOrder());
            events.clear();
            assertThrows(IllegalStateException.class, () -> drawable.createMorphKeyform("m", 1f));
            semantic.awaitIdle();
            assertEquals(List.of("before:CREATE_DRAWABLE_MORPH_KEYFORM:ArtMeshA:m:1.0"), events);
            events.clear();
            assertThrows(IllegalArgumentException.class, () -> drawable.createMorphKeyform("m", 0f));
            semantic.awaitIdle();
            assertTrue(events.isEmpty());
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            semantic.close();
        }
    }

    // ---------------------------------------------------------------- fixtures

    private static CubismFacadeImpl facade(final RecordingModel delegate, final List<PluginPermission> permissions) {
        return facade(delegate, permissions, () -> true);
    }

    private static CubismFacadeImpl facade(
            final RecordingModel delegate,
            final List<PluginPermission> permissions,
            final java.util.function.BooleanSupplier activeScope) {
        return facade(
                delegate,
                permissions,
                activeScope,
                new dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator());
    }

    private static CubismFacadeImpl facade(
            final RecordingModel delegate,
            final List<PluginPermission> permissions,
            final java.util.function.BooleanSupplier activeScope,
            final dev.turboism.adapter.cubism.lifecycle.EditorObjectLifecycleCoordinator lifecycle) {
        return new CubismFacadeImpl(
                new HostSnapshotSource() {
                    @Override
                    public Optional<HostProject> activeProject() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<HostDocument> activeDocument() {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<HostModel> activeModel() {
                        return Optional.empty();
                    }

                    @Override
                    public HostSelection selection() {
                        return new HostSelection(List.of(), Optional.empty(), Optional.empty(), Optional.empty());
                    }

                    @Override
                    public boolean isHostPresent() {
                        return false;
                    }

                    @Override
                    public long invalidationToken() {
                        return 0L;
                    }
                },
                new CubismPermissionGate("plugin.demo", permissions, ignored -> {}, FIXED_CLOCK),
                (CubismModelAccess) () -> delegate,
                new dev.turboism.adapter.cubism.lifecycle.ParameterLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.lifecycle.PartLifecycleCoordinator(),
                new dev.turboism.adapter.cubism.textureatlas.TextureAtlasLayoutCoordinator(),
                lifecycle,
                activeScope);
    }

    private static CubismFacadeImpl facade(final List<PluginPermission> permissions, final List<String> calls) {
        return facade(new RecordingModel(calls), permissions);
    }

    private static PluginPermission readPermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, granted);
    }

    private static PluginPermission readPermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_READ_PERMISSION, new boolean[] {granted});
    }

    private static PluginPermission writePermission(final boolean[] granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, granted);
    }

    private static PluginPermission writePermission(final boolean granted) {
        return revocable(CubismFacadeImpl.MODEL_WRITE_PERMISSION, new boolean[] {granted});
    }

    private static PluginPermission revocable(final String id, final boolean[] granted) {
        return new PluginPermission() {
            @Override
            public String id() {
                return granted[0] ? id : "revoked";
            }

            @Override
            public String scope() {
                return "read";
            }

            @Override
            public String reason() {
                return "test";
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.ModelTextures recordingTextures(final List<String> calls) {
        return new dev.turboism.sdk.cubism.model.ModelTextures() {
            @Override
            public List<dev.turboism.sdk.cubism.model.RawTexture> rawImages() {
                return List.of();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.ModelImageGroup> modelImageGroups() {
                return List.of();
            }

            @Override
            public List<dev.turboism.sdk.cubism.model.AtlasTexture> textureAtlases() {
                return List.of();
            }

            @Override
            public void addModelImageGroup(final String name) {}

            @Override
            public void removeModelImage(final dev.turboism.sdk.cubism.id.ModelImageId id) {}

            @Override
            public dev.turboism.sdk.cubism.id.TextureAtlasId addTextureAtlas(
                    final String name, final int widthPixels, final int heightPixels) {
                return new dev.turboism.sdk.cubism.id.TextureAtlasId("atlas");
            }

            @Override
            public void removeTextureAtlas(final dev.turboism.sdk.cubism.id.TextureAtlasId id) {}

            @Override
            public void removeRawImage(final dev.turboism.sdk.cubism.id.RawImageId id) {}

            @Override
            public dev.turboism.sdk.cubism.model.ModelImageBinding registerImageAndBind(
                    final dev.turboism.sdk.cubism.model.AuthorizedImageContent image, final ArtMeshId target) {
                calls.add("registerImageAndBind:" + target.value());
                return BINDING;
            }

            @Override
            public dev.turboism.sdk.cubism.model.ModelImageBinding bindModelImage(
                    final dev.turboism.sdk.cubism.id.ModelImageId image, final ArtMeshId target) {
                calls.add("bindModelImage:" + target.value());
                return BINDING;
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.AuthorizedImageContent imageGrant() {
        final byte[] png = new byte[33];
        final byte[] signature = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A};
        System.arraycopy(signature, 0, png, 0, signature.length);
        png[11] = 13;
        png[12] = 'I';
        png[13] = 'H';
        png[14] = 'D';
        png[15] = 'R';
        png[18] = 1;
        png[23] = (byte) 128;
        return dev.turboism.sdk.cubism.model.AuthorizedImageContent.of(png, "forwarding.png");
    }

    private static IntSequence emptyIntSequence() {
        return new IntSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public int get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    private static dev.turboism.sdk.cubism.model.FloatSequence emptyFloatSequence() {
        return new dev.turboism.sdk.cubism.model.FloatSequence() {
            @Override
            public int size() {
                return 0;
            }

            @Override
            public float get(final int index) {
                throw new IndexOutOfBoundsException(index);
            }
        };
    }

    /** Delegate model recording every Wave 1 method invocation into {@code calls}. */
    private static final class RecordingModel implements CubismModel {
        @Override
        public ParameterBindingOperations parameterBindings(final ParameterId parameterId) {
            return new ParameterBindingOperations() {
                @Override
                public ParameterInterpolation interpolation(ParameterBindingTarget target) {
                    calls.add("interpolation.read:" + target.id());
                    return new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 6, 1);
                }

                @Override
                public void setInterpolation(ParameterBindingTarget target, ParameterInterpolation settings) {
                    calls.add("interpolation.write:" + target.id() + ":" + settings.insertPointCount());
                }

                public void bind(ParameterBindingTarget t, List<ParameterBindingPoint> p) {
                    throw new UnsupportedOperationException();
                }

                public void createPoint(ParameterBindingTarget t, ParameterBindingPoint p) {
                    throw new UnsupportedOperationException();
                }

                public void movePoint(ParameterBindingTarget t, ParameterBindingPointId p, float v) {
                    throw new UnsupportedOperationException();
                }

                public void deletePoint(ParameterBindingTarget t, ParameterBindingPointId p) {
                    throw new UnsupportedOperationException();
                }

                public void unbind(ParameterBindingTarget t) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        private final List<String> calls;
        private boolean failKeyformWrite;
        private final Parameter parameter = new Parameter() {
            @Override
            public ParameterId id() {
                return PARAMETER_ID;
            }

            @Override
            public float getValue() {
                return 0.0F;
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
                return 0.0F;
            }

            @Override
            public void setValue(final float value) {}
        };
        private final ParameterGroup group = new ParameterGroup() {
            @Override
            public ParameterGroupId id() {
                return GROUP_ID;
            }

            @Override
            public Optional<String> name() {
                return Optional.of("Group A");
            }

            @Override
            public Optional<ParameterGroupId> parentId() {
                return Optional.empty();
            }

            @Override
            public List<ParameterGroupId> childGroupIds() {
                return List.of();
            }

            @Override
            public List<ParameterId> parameterIds() {
                return List.of();
            }

            @Override
            public void rename(final String name) {
                calls.add("rename:" + name);
            }
        };
        private final Part part = new Part() {
            @Override
            public PartId id() {
                return PART_ID;
            }

            @Override
            public void setName(final String name) {}

            @Override
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public int parentIndex() {
                return 0;
            }

            @Override
            public void setOpacity(final float opacity) {}

            @Override
            public MorphTargets morphTargets() {
                calls.add("part.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }
        };
        private final Drawable drawable = new Drawable() {
            @Override
            public ArtMeshId id() {
                return new ArtMeshId("ArtMeshA");
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
            public float getOpacity() {
                return 1.0F;
            }

            @Override
            public IntSequence masks() {
                return emptyIntSequence();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexPositions() {
                return emptyFloatSequence();
            }

            @Override
            public dev.turboism.sdk.cubism.model.FloatSequence vertexUvs() {
                return emptyFloatSequence();
            }

            @Override
            public IntSequence indices() {
                return emptyIntSequence();
            }

            @Override
            public dev.turboism.sdk.cubism.model.Color multiplyColor() {
                return new dev.turboism.sdk.cubism.model.Color(1, 1, 1, 1);
            }

            @Override
            public dev.turboism.sdk.cubism.model.Color screenColor() {
                return new dev.turboism.sdk.cubism.model.Color(0, 0, 0, 1);
            }

            @Override
            public int parentPartIndex() {
                return -1;
            }

            @Override
            public int parentDeformerIndex() {
                return -1;
            }

            @Override
            public IntSequence parameters() {
                return emptyIntSequence();
            }

            @Override
            public MorphTargets morphTargets() {
                calls.add("drawable.morphTargets");
                return EMPTY_MORPH_TARGETS;
            }

            private final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey> createdMorphs =
                    new ArrayList<>();

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot authoringSnapshot() {
                calls.add("drawable.authoringSnapshot");
                if (createdMorphs.isEmpty()) return AUTHORING;
                return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                        AUTHORING.artMeshId(),
                        AUTHORING.sourceGuid(),
                        AUTHORING.sourceGeometry(),
                        List.of(),
                        List.of(),
                        createdMorphs.stream()
                                .map(k -> new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                        k.formGuid(), List.of(), 500))
                                .toList(),
                        createdMorphs);
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshEvaluationSnapshot evaluationSnapshot() {
                calls.add("drawable.evaluationSnapshot");
                return EVALUATION;
            }

            @Override
            public void setOrdinaryKeyformPositions(
                    final dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot expected,
                    final List<dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions> edits) {
                calls.add("drawable.keyformBatch");
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshKeyform ordinaryKeyform(
                    final List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates) {
                calls.add("drawable.ordinaryKeyform");
                return new dev.turboism.sdk.cubism.model.ArtMeshKeyform() {
                    private List<dev.turboism.sdk.cubism.model.Point2> positions = List.of();
                    private int order = 500;

                    @Override
                    public List<dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate> coordinates() {
                        calls.add("keyform.coordinates");
                        return List.copyOf(coordinates);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        calls.add("keyform.snapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "form-guid", positions, order);
                    }

                    @Override
                    public boolean defaultKeyform() {
                        calls.add("keyform.default");
                        return false;
                    }

                    @Override
                    public void setPositions(final List<dev.turboism.sdk.cubism.model.Point2> value) {
                        calls.add("keyform.setPositions");
                        if (failKeyformWrite) throw new IllegalStateException("keyform write failed");
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        calls.add("keyform.setDrawOrder");
                        if (failKeyformWrite) throw new IllegalStateException("keyform write failed");
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform morphKeyform(final String guid, final float key) {
                calls.add("drawable.morphKeyform");
                return new dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform() {
                    private List<dev.turboism.sdk.cubism.model.Point2> positions = List.of();
                    private int order = 500;

                    @Override
                    public String parameterGuid() {
                        calls.add("morph.parameterGuid");
                        return guid;
                    }

                    @Override
                    public float keyValue() {
                        calls.add("morph.keyValue");
                        return key;
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form snapshot() {
                        calls.add("morph.snapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form(
                                "morph-form", positions, order);
                    }

                    @Override
                    public dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form baseSnapshot() {
                        calls.add("morph.baseSnapshot");
                        return new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form("base", List.of(), 500);
                    }

                    @Override
                    public void setPositions(final List<dev.turboism.sdk.cubism.model.Point2> value) {
                        calls.add("morph.setPositions");
                        if (failKeyformWrite) throw new IllegalStateException("morph write failed");
                        positions = List.copyOf(value);
                    }

                    @Override
                    public void setDrawOrder(final int value) {
                        calls.add("morph.setDrawOrder");
                        if (failKeyformWrite) throw new IllegalStateException("morph write failed");
                        order = value;
                    }
                };
            }

            @Override
            public dev.turboism.sdk.cubism.model.ArtMeshMorphKeyform createMorphKeyform(
                    final String guid, final float key) {
                calls.add("drawable.createMorphKeyform");
                if (failKeyformWrite) throw new IllegalStateException("morph create failed");
                createdMorphs.add(new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey(
                        guid, "created-" + createdMorphs.size(), Optional.of(key)));
                return morphKeyform(guid, key);
            }
        };

        private RecordingModel(final List<String> calls) {
            this.calls = calls;
        }

        Parameter parameter() {
            return parameter;
        }

        ParameterGroup group() {
            return group;
        }

        Part part() {
            return part;
        }

        @Override
        public ModelId id() {
            return new ModelId("model-a");
        }

        @Override
        public ModelProfile profile() {
            calls.add("profile");
            return PROFILE;
        }

        @Override
        public PhysicsSettings physicsSettings() {
            calls.add("physicsSettings");
            return PHYSICS;
        }

        @Override
        public AutoYure autoYure() {
            calls.add("autoYure");
            return AUTO_YURE;
        }

        @Override
        public List<AnimationDocument> animationDocuments() {
            calls.add("animationDocuments");
            return List.of(ANIMATION);
        }

        private dev.turboism.sdk.cubism.model.ModelTextures textures = TEXTURES;

        RecordingModel withTextures(final dev.turboism.sdk.cubism.model.ModelTextures value) {
            textures = value;
            return this;
        }

        @Override
        public dev.turboism.sdk.cubism.model.ModelTextures textures() {
            calls.add("textures");
            return textures;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismMocExport exportMoc(
                final dev.turboism.sdk.cubism.model.CubismMocExportSettings settings) {
            assertSame(EXPORT_SETTINGS, settings);
            calls.add("exportMoc");
            return EXPORT;
        }

        @Override
        public dev.turboism.sdk.cubism.model.CubismRenderedPixels renderPixels(final int width, final int height) {
            calls.add("renderPixels");
            return PIXELS;
        }

        @Override
        public Parameters parameters() {
            return new Parameters() {
                @Override
                public List<Parameter> all() {
                    return List.of(parameter);
                }

                @Override
                public Parameter find(final ParameterId id) {
                    return parameter;
                }

                @Override
                public Parameter create(final ParameterDefinition definition) {
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }

                @Override
                public Parameter create(
                        final ParameterDefinition definition, final Optional<ParameterGroupId> folderId) {
                    calls.add("create:" + definition.id().value());
                    return parameter;
                }

                @Override
                public Parameter copy(final ParameterId id) {
                    calls.add("copy:" + id.value());
                    return parameter;
                }

                @Override
                public void remove(final ParameterId id) {
                    calls.add("remove:" + id.value());
                }
            };
        }

        @Override
        public ParameterGroups parameterGroups() {
            return new ParameterGroups() {
                @Override
                public List<ParameterGroup> all() {
                    return List.of(group);
                }

                @Override
                public ParameterGroup root() {
                    return group;
                }

                @Override
                public ParameterGroup find(final ParameterGroupId id) {
                    return group;
                }

                @Override
                public ParameterGroup addGroup(final String name) {
                    calls.add("addGroup:" + name);
                    return group;
                }

                @Override
                public void removeGroup(final ParameterGroupId id) {
                    calls.add("removeGroup:" + id.value());
                }

                @Override
                public void moveParameter(final ParameterId parameterId, final ParameterGroupId targetGroupId) {
                    calls.add("moveParameter:" + parameterId.value());
                }
            };
        }

        @Override
        public Parts parts() {
            return new Parts() {
                @Override
                public List<Part> all() {
                    return List.of(part);
                }

                @Override
                public Part find(final PartId id) {
                    return part;
                }

                @Override
                public Part add(final PartId id) {
                    calls.add("add:" + id.value());
                    return part;
                }

                @Override
                public Part add(final PartId id, final PartId parentId) {
                    calls.add("add:" + id.value());
                    return part;
                }

                @Override
                public Part copy(final PartId id) {
                    calls.add("copy:" + id.value());
                    return part;
                }

                @Override
                public void remove(final PartId id) {
                    calls.add("remove:" + id.value());
                }
            };
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

                @Override
                public dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation previewSourceTriangulation(
                        final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry) {
                    calls.add("drawable.previewSourceTriangulation");
                    return new dev.turboism.sdk.cubism.model.ArtMeshSourceTriangulation(
                            geometry.positions(), geometry.triangleIndices());
                }

                @Override
                public Drawable createWithSourceGeometry(
                        final String name,
                        final Part parent,
                        final int index,
                        final dev.turboism.sdk.cubism.model.ArtMeshGeometry geometry,
                        final List<dev.turboism.sdk.cubism.model.Point2> defaultPositions) {
                    assertEquals(AUTHORING.sourceGeometry(), geometry);
                    assertEquals(List.of(), defaultPositions);
                    calls.add("drawable.createWithSourceGeometry:" + name);
                    return drawable;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.Deformers deformers() {
            throw new UnsupportedOperationException();
        }

        @Override
        public dev.turboism.sdk.cubism.model.RotationDeformers rotationDeformers() {
            final var id = new dev.turboism.sdk.cubism.id.DeformerId("RollA");
            final var snapshot = new dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot(
                    id,
                    "rotation-source",
                    0,
                    List.of(),
                    List.of(),
                    List.of(new dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Form(
                            "rotation-form",
                            new dev.turboism.sdk.cubism.model.RotationDeformerForm(0, 0, 0, 1, false, false))));
            final var rotation = new dev.turboism.sdk.cubism.model.RotationDeformer() {
                @Override
                public dev.turboism.sdk.cubism.id.DeformerId id() {
                    return id;
                }

                @Override
                public int parentDeformerIndex() {
                    return -1;
                }

                @Override
                public dev.turboism.sdk.cubism.model.IntSequence parameters() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public float baseAngle() {
                    return 0;
                }

                @Override
                public void setBaseAngle(float value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationDeformerForm form() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void replaceForm(dev.turboism.sdk.cubism.model.RotationDeformerForm value) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot authoringSnapshot() {
                    calls.add("rotation.authoringSnapshot");
                    return snapshot;
                }

                @Override
                public void setOrdinaryKeyformForms(
                        dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot expected,
                        List<dev.turboism.sdk.cubism.model.RotationKeyformForm> edits) {
                    calls.add("rotation.setOrdinaryKeyformForms");
                }
            };
            return new dev.turboism.sdk.cubism.model.RotationDeformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.RotationDeformer> all() {
                    return List.of(rotation);
                }

                @Override
                public dev.turboism.sdk.cubism.model.RotationDeformer find(
                        dev.turboism.sdk.cubism.id.DeformerId target) {
                    return rotation;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.WarpDeformers warpDeformers() {
            final var id = new dev.turboism.sdk.cubism.id.DeformerId("WarpA");
            final var snapshot = new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot(
                    id,
                    "warp-source",
                    1,
                    1,
                    true,
                    List.of(),
                    List.of(),
                    List.of(new dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot.Form(
                            "warp-form",
                            List.of(
                                    new dev.turboism.sdk.cubism.model.Point2(0, 0),
                                    new dev.turboism.sdk.cubism.model.Point2(1, 0),
                                    new dev.turboism.sdk.cubism.model.Point2(0, 1),
                                    new dev.turboism.sdk.cubism.model.Point2(1, 1)))));
            final var warp = new dev.turboism.sdk.cubism.model.WarpDeformer() {
                @Override
                public dev.turboism.sdk.cubism.id.DeformerId id() {
                    return id;
                }

                @Override
                public int parentDeformerIndex() {
                    return -1;
                }

                @Override
                public IntSequence parameters() {
                    return emptyIntSequence();
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpGrid grid() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public void replaceGrid(dev.turboism.sdk.cubism.model.WarpGrid grid) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot authoringSnapshot() {
                    calls.add("warp.authoringSnapshot");
                    return snapshot;
                }

                @Override
                public void setOrdinaryKeyformPositions(
                        dev.turboism.sdk.cubism.model.WarpAuthoringSnapshot expected,
                        List<dev.turboism.sdk.cubism.model.WarpKeyformPositions> edits) {
                    calls.add("warp.setOrdinaryKeyformPositions");
                }
            };
            return new dev.turboism.sdk.cubism.model.WarpDeformers() {
                @Override
                public List<dev.turboism.sdk.cubism.model.WarpDeformer> all() {
                    return List.of(warp);
                }

                @Override
                public dev.turboism.sdk.cubism.model.WarpDeformer find(dev.turboism.sdk.cubism.id.DeformerId target) {
                    return warp;
                }
            };
        }

        @Override
        public dev.turboism.sdk.cubism.model.Glues glues() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void update() {
            throw new UnsupportedOperationException();
        }
    }
}
