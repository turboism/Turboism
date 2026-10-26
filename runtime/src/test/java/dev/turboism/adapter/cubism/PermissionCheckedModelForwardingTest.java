package dev.turboism.adapter.cubism;

import static dev.turboism.adapter.cubism.CubismFacadeTestFixtures.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.AnimationDocument;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterInterpolation;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.permission.CubismPermissionException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Focused permission-gate coverage for the Wave 1 SDK method forwardings added
 * to the PermissionChecked wrapper layer of {@link CubismFacadeImpl}.
 */
class PermissionCheckedModelForwardingTest {

    @Test
    void valueBasedBindingChecksWritePermissionAndActiveScope() {
        var calls = new ArrayList<String>();
        boolean[] read = {true}, write = {true}, active = {true};
        var recording = new RecordingModel(calls);
        recording.bindingProjectionAvailable = true;
        var model = facade(recording, List.of(readPermission(read), writePermission(write)), () -> active[0])
                .model()
                .active();
        var operations = model.parameterBindings(PARAMETER_ID);
        var target = ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshA"));
        operations.bindValues(target, List.of(-1f, 0f, 1f));
        assertEquals(List.of("binding.values:ArtMeshA:[-1.0, 0.0, 1.0]"), calls);
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> operations.bindValues(target, List.of(0f)));
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> operations.bindValues(target, List.of(0f)));
        assertEquals(1, calls.size());
    }

    @Test
    void ordinaryWriterObservationChecksReadAndReportsRevokedWriteWithoutMutation() {
        final var calls = new ArrayList<String>();
        final boolean[] read = {true}, write = {false}, active = {true};
        var model = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active();
        var mesh = model.drawables().find(new ArtMeshId("ArtMeshA"));
        var warp = model.warpDeformers().find(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
        var rotation = model.rotationDeformers().find(new dev.turboism.sdk.cubism.id.DeformerId("RollA"));
        calls.clear();
        for (var target : List.of(warp, rotation)) {
            assertEquals(
                    dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check.ABSENT,
                    target.ordinaryKeyformWriteSupport().writePermission());
            assertEquals(
                    dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check.UNKNOWN,
                    target.ordinaryKeyformWriteSupport().writerSelectors());
        }
        assertEquals(
                dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check.ABSENT,
                mesh.ordinaryKeyformWriteSupport().writePermission());
        write[0] = true;
        assertEquals(
                dev.turboism.sdk.cubism.model.OrdinaryKeyformWriteSupport.Check.PRESENT,
                mesh.ordinaryKeyformWriteSupport().writePermission());
        read[0] = false;
        assertThrows(CubismPermissionException.class, mesh::ordinaryKeyformWriteSupport);
        assertThrows(CubismPermissionException.class, warp::ordinaryKeyformWriteSupport);
        assertThrows(CubismPermissionException.class, rotation::ordinaryKeyformWriteSupport);
        active[0] = false;
        assertThrows(IllegalStateException.class, mesh::ordinaryKeyformWriteSupport);
        assertTrue(calls.isEmpty(), calls.toString());
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
        var batch = model.parameterBindingBatch();
        var operations = model.parameterBindings(PARAMETER_ID);
        var target = ParameterBindingTarget.artMesh(new ArtMeshId("ArtMeshA"));
        var settings = new ParameterInterpolation(ParameterInterpolation.Method.ELLIPSE, 6, 1);
        assertEquals(settings, operations.interpolation(target));
        operations.setInterpolation(target, settings);
        batch.setInterpolations(target, java.util.Map.of(new ParameterId("ParamA"), settings));
        assertEquals(
                List.of(
                        "interpolation.read:ArtMeshA",
                        "interpolation.write:ArtMeshA:6",
                        "interpolation.batch:ArtMeshA:1"),
                calls);
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> batch.setInterpolations(target, java.util.Map.of()));
        assertThrows(CubismPermissionException.class, () -> operations.setInterpolation(target, settings));
        read[0] = false;
        assertThrows(CubismPermissionException.class, () -> operations.interpolation(target));
        read[0] = true;
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> operations.interpolation(target));
        assertThrows(IllegalStateException.class, () -> operations.setInterpolation(target, settings));
        assertThrows(IllegalStateException.class, () -> batch.setInterpolations(target, java.util.Map.of()));
        assertEquals(3, calls.size());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void modelReadForwardingsEnforceReadPermission(final boolean readGranted) {
        final List<String> calls = new ArrayList<>();
        final boolean[] granted = {true};
        final CubismModel model = facade(List.of(readPermission(granted), writePermission(true)), calls)
                .model()
                .active();
        granted[0] = readGranted;

        if (readGranted) {
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
        } else {
            assertThrows(CubismPermissionException.class, model::profile);
            assertThrows(CubismPermissionException.class, model::physicsSettings);
            assertThrows(CubismPermissionException.class, model::autoYure);
            assertThrows(CubismPermissionException.class, model::animationDocuments);
            assertThrows(CubismPermissionException.class, model::textures);
            assertThrows(CubismPermissionException.class, () -> model.renderPixels(2, 1));
            assertThrows(CubismPermissionException.class, () -> model.exportMoc(EXPORT_SETTINGS));
            assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parameterWritesEnforceWritePermission(final boolean writeGranted) {
        final List<String> calls = new ArrayList<>();
        if (writeGranted) {
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
        } else {
            final Parameters parameters = facade(List.of(readPermission(true)), calls)
                    .model()
                    .active()
                    .parameters();

            assertThrows(CubismPermissionException.class, () -> parameters.create(DEFINITION));
            assertThrows(CubismPermissionException.class, () -> parameters.create(DEFINITION, Optional.of(GROUP_ID)));
            assertThrows(CubismPermissionException.class, () -> parameters.copy(PARAMETER_ID));
            assertThrows(CubismPermissionException.class, () -> parameters.remove(PARAMETER_ID));
            assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void parameterGroupWritesEnforceWritePermission(final boolean writeGranted) {
        final List<String> calls = new ArrayList<>();
        if (writeGranted) {
            final RecordingModel delegate = new RecordingModel(calls);
            final ParameterGroups groups = facade(delegate, List.of(readPermission(true), writePermission(true)))
                    .model()
                    .active()
                    .parameterGroups();

            final ParameterGroup created = groups.addGroup("Group B");
            groups.removeGroup(GROUP_ID);
            groups.moveParameter(PARAMETER_ID, GROUP_ID);

            assertNotSame(created, delegate.group(), "addGroup must return a PermissionChecked wrapper");
        } else {
            final ParameterGroups groups = facade(List.of(readPermission(true)), calls)
                    .model()
                    .active()
                    .parameterGroups();

            assertThrows(CubismPermissionException.class, () -> groups.addGroup("Group B"));
            assertThrows(CubismPermissionException.class, () -> groups.removeGroup(GROUP_ID));
            assertThrows(CubismPermissionException.class, () -> groups.moveParameter(PARAMETER_ID, GROUP_ID));
            assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
        }
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void textureRegisterAndBindWritesEnforceWritePermission(final boolean writeGranted) {
        final List<String> calls = new ArrayList<>();
        final RecordingModel delegate = new RecordingModel(calls).withTextures(recordingTextures(calls));
        final var permissions =
                writeGranted ? List.of(readPermission(true), writePermission(true)) : List.of(readPermission(true));
        final var textures = facade(delegate, permissions).model().active().textures();

        if (writeGranted) {
            assertSame(BINDING, textures.registerImageAndBind(imageGrant(), MESH_ID));
            assertSame(BINDING, textures.bindModelImage(MODEL_IMAGE_ID, MESH_ID));
            assertEquals(List.of("textures", "registerImageAndBind:ArtMeshA", "bindModelImage:ArtMeshA"), calls);
        } else {
            assertThrows(CubismPermissionException.class, () -> textures.registerImageAndBind(imageGrant(), MESH_ID));
            assertThrows(CubismPermissionException.class, () -> textures.bindModelImage(MODEL_IMAGE_ID, MESH_ID));
            assertEquals(
                    List.of("textures"),
                    calls,
                    "register/bind must not reach the delegate when write permission is denied");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void partWritesEnforceWritePermission(final boolean writeGranted) {
        final List<String> calls = new ArrayList<>();
        if (writeGranted) {
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
        } else {
            final Parts parts = facade(List.of(readPermission(true)), calls)
                    .model()
                    .active()
                    .parts();

            assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID));
            assertThrows(CubismPermissionException.class, () -> parts.add(PART_ID, PART_ID));
            assertThrows(CubismPermissionException.class, () -> parts.add("PartString"));
            assertThrows(CubismPermissionException.class, () -> parts.copy(PART_ID));
            assertThrows(CubismPermissionException.class, () -> parts.remove(PART_ID));
            assertTrue(calls.isEmpty(), "delegate must not be invoked when write permission is denied");
        }
    }

    @Test
    void meshBatchForwardsGeometryAndRequiresWriteAndActiveScope() {
        var calls = new ArrayList<String>();
        boolean[] write = {true}, active = {true};
        var delegate = new RecordingModel(calls);
        var values = facade(delegate, List.of(readPermission(true), writePermission(write)), () -> active[0])
                .model()
                .active()
                .drawables();
        var geometry = new dev.turboism.sdk.cubism.model.ArtMeshGeometry(
                List.of(
                        new dev.turboism.sdk.cubism.model.Point2(0, 0),
                        new dev.turboism.sdk.cubism.model.Point2(1, 0),
                        new dev.turboism.sdk.cubism.model.Point2(0, 1)),
                List.of(
                        new dev.turboism.sdk.cubism.model.Point2(0, 0),
                        new dev.turboism.sdk.cubism.model.Point2(1, 0),
                        new dev.turboism.sdk.cubism.model.Point2(0, 1)),
                List.of(0, 1, 2));
        var requests = List.of(
                new Drawables.SourceGeometryRequest("Mesh", Optional.of(PART_ID), geometry, geometry.positions()));
        var meshes = values.createManyWithSourceGeometry(requests);
        assertEquals(requests, delegate.batchMeshRequests);
        assertEquals(1, meshes.size());
        assertNotSame(delegate.drawable, meshes.get(0));
        assertEquals(List.of("drawables.createManyWithSourceGeometry"), calls);
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> values.createManyWithSourceGeometry(requests));
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> values.createManyWithSourceGeometry(requests));
        assertTrue(calls.isEmpty());
    }

    @Test
    void batchPartsForwardNamesUnwrapParentAndGuardWriteAndScope() {
        var calls = new ArrayList<String>();
        boolean[] write = {true}, active = {true};
        var delegate = new RecordingModel(calls);
        var parts = facade(delegate, List.of(readPermission(true), writePermission(write)), () -> active[0])
                .model()
                .active()
                .parts();
        var parent = parts.find(PART_ID);
        var created = parts.createMany(List.of("First", "Second"), parent);
        assertEquals(List.of("parts.createMany:[First, Second]"), calls);
        assertEquals(2, created.size());
        assertNotSame(delegate.part(), created.get(0));
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> parts.createMany(List.of("Denied"), parent));
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> parts.createMany(List.of("Stale"), parent));
        assertTrue(calls.isEmpty());
    }

    @Test
    void parameterGuidRequiresReadAndActiveScopeWithoutWritePermission() {
        var calls = new ArrayList<String>();
        boolean[] read = {true}, active = {true};
        var parameter = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(false)),
                        () -> active[0])
                .model()
                .active()
                .parameters()
                .find(PARAMETER_ID);
        calls.clear();
        assertEquals("native-parameter-guid", parameter.guid());
        assertEquals(List.of("parameter.guid"), calls);
        calls.clear();
        read[0] = false;
        assertThrows(CubismPermissionException.class, parameter::guid);
        assertTrue(calls.isEmpty());
        read[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, parameter::guid);
        assertTrue(calls.isEmpty());
    }

    @Test
    void indexedParameterMoveAndMixedReadCheckPermissionsOnEveryCall() {
        final List<String> calls = new ArrayList<>();
        final boolean[] read = {true}, write = {true};
        final var groups = facade(List.of(readPermission(read), writePermission(write)), calls)
                .model()
                .active()
                .parameterGroups();
        final var root = groups.root();
        groups.moveParameter(PARAMETER_ID, GROUP_ID, 2);
        assertEquals(1, root.orderedChildren().size());
        assertEquals(List.of("moveParameter:" + PARAMETER_ID.value() + ":2", "orderedChildren"), calls);
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> groups.moveParameter(PARAMETER_ID, GROUP_ID, 0));
        read[0] = false;
        assertThrows(CubismPermissionException.class, root::orderedChildren);
        assertTrue(calls.isEmpty());
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

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void morphTargetsReadsEnforceReadPermission(final boolean readGranted) {
        final List<String> calls = new ArrayList<>();
        final boolean[] granted = {true};
        final CubismModel model = facade(List.of(readPermission(granted), writePermission(true)), calls)
                .model()
                .active();
        final Part part = model.parts().find(PART_ID);
        final Drawable drawable = model.drawables().find(new ArtMeshId("ArtMeshA"));
        granted[0] = readGranted;

        if (readGranted) {
            assertSame(EMPTY_MORPH_TARGETS, part.morphTargets());
            assertSame(EMPTY_MORPH_TARGETS, drawable.morphTargets());
            assertEquals(List.of("part.morphTargets", "drawable.morphTargets"), calls);
        } else {
            assertThrows(CubismPermissionException.class, part::morphTargets);
            assertThrows(CubismPermissionException.class, drawable::morphTargets);
            assertTrue(calls.isEmpty(), "delegate must not be invoked when read permission is denied");
        }
    }

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
    void initializedWarpCreationForwardsExactInputsAndRequiresWriteAndActiveScope() {
        var calls = new ArrayList<String>();
        boolean[] write = {true}, active = {true};
        var delegate = new RecordingModel(calls);
        var deformers = facade(delegate, List.of(readPermission(true), writePermission(write)), () -> active[0])
                .model()
                .active()
                .deformers();
        var target = Optional.of(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
        var grid = new dev.turboism.sdk.cubism.model.WarpGrid(
                1,
                1,
                true,
                List.of(
                        new dev.turboism.sdk.cubism.model.Point2(0, 0), new dev.turboism.sdk.cubism.model.Point2(1, 0),
                        new dev.turboism.sdk.cubism.model.Point2(0, 1),
                                new dev.turboism.sdk.cubism.model.Point2(1, 1)));
        deformers.createWarp("Final", null, -1, target, grid);
        assertEquals(java.util.Arrays.asList("Final", null, -1, target, grid), delegate.initializedWarpArgs);
        assertEquals(List.of("warp.createInitialized"), calls);
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> deformers.createWarp("Final", null, -1, target, grid));
        write[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, () -> deformers.createWarp("Final", null, -1, target, grid));
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
    void warpMorphForwardingRequiresPermissionsAndActiveScope() {
        var calls = new ArrayList<String>();
        boolean[] read = {true}, write = {true}, active = {true};
        var warp = facade(
                        new RecordingModel(calls),
                        List.of(readPermission(read), writePermission(write)),
                        () -> active[0])
                .model()
                .active()
                .warpDeformers()
                .find(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
        assertTrue(warp.morphSnapshot().keys().isEmpty());
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> warp.createOpacityMorphKeyform(new ParameterId("P"), 1, 0));
        assertThrows(
                CubismPermissionException.class, () -> warp.createMorphKeyform(new ParameterId("P"), -1, List.of()));
        assertTrue(calls.isEmpty());
        write[0] = true;
        read[0] = false;
        assertThrows(CubismPermissionException.class, warp::morphSnapshot);
        warp.createOpacityMorphKeyform(new ParameterId("P"), 1, 0);
        assertTrue(calls.contains("warp.createOpacityMorphKeyform"));
        calls.clear();
        read[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, warp::morphSnapshot);
        assertThrows(IllegalStateException.class, () -> warp.createMorphKeyform(new ParameterId("P"), -1, List.of()));
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
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyforms(null, List.of(), List.of()));
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformValues(null, List.of()));
        assertTrue(calls.isEmpty());
        read[0] = false;
        assertThrows(CubismPermissionException.class, target::snapshot);
        assertThrows(CubismPermissionException.class, target::coordinates);
        assertThrows(CubismPermissionException.class, target::defaultKeyform);
        assertThrows(CubismPermissionException.class, () -> drawable.ordinaryKeyform(coordinates));
        assertTrue(calls.isEmpty());
        write[0] = true;
        drawable.setOrdinaryKeyformValues(null, List.of());
        assertEquals(
                1,
                calls.stream()
                        .filter("drawable.keyformSparseValuesBatch"::equals)
                        .count());
        drawable.setOrdinaryKeyforms(null, List.of(), List.of());
        assertEquals(
                1, calls.stream().filter("drawable.keyformValuesBatch"::equals).count());
        drawable.setOrdinaryKeyformPositions(null, List.of());
        assertEquals(1, calls.stream().filter("drawable.keyformBatch"::equals).count());
        target.setPositions(List.of(new dev.turboism.sdk.cubism.model.Point2(4, 5)));
        assertEquals(1, calls.stream().filter("keyform.setPositions"::equals).count());
        target.setDrawOrder(600);
        assertEquals(1, calls.stream().filter("keyform.setDrawOrder"::equals).count());
        calls.clear();
        write[0] = false;
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyforms(null, List.of(), List.of()));
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertThrows(CubismPermissionException.class, () -> drawable.setOrdinaryKeyformValues(null, List.of()));
        assertThrows(CubismPermissionException.class, () -> target.setDrawOrder(700));
        assertTrue(calls.isEmpty());
        write[0] = true;
        calls.clear();
        read[0] = true;
        active[0] = false;
        assertThrows(IllegalStateException.class, target::snapshot);
        assertThrows(IllegalStateException.class, () -> drawable.setOrdinaryKeyforms(null, List.of(), List.of()));
        assertThrows(IllegalStateException.class, () -> drawable.setOrdinaryKeyformPositions(null, List.of()));
        assertThrows(IllegalStateException.class, () -> drawable.setOrdinaryKeyformValues(null, List.of()));
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
            delegate.failKeyformWrite = false;
            var warp = facade(delegate, List.of(readPermission(true), writePermission(true)), () -> true, lifecycle)
                    .model()
                    .active()
                    .warpDeformers()
                    .find(new dev.turboism.sdk.cubism.id.DeformerId("WarpA"));
            var parameter = new ParameterId("Shape");
            String opacitySuffix = ":CREATE_WARP_OPACITY_MORPH_KEYFORM:WarpA:Shape:1.0";
            warp.createOpacityMorphKeyform(parameter, 1, 0);
            semantic.awaitIdle();
            assertEquals(List.of("before" + opacitySuffix, "on" + opacitySuffix, "after" + opacitySuffix), events);
            events.clear();
            String geometrySuffix = ":CREATE_WARP_MORPH_KEYFORM:WarpA:Shape:-1.0";
            warp.createMorphKeyform(parameter, -1, warp.morphSnapshot().base().controlPoints());
            semantic.awaitIdle();
            assertEquals(List.of("before" + geometrySuffix, "on" + geometrySuffix, "after" + geometrySuffix), events);
            events.clear();
            delegate.failKeyformWrite = true;
            assertThrows(IllegalStateException.class, () -> warp.createOpacityMorphKeyform(parameter, .5f, .5f));
            semantic.awaitIdle();
            assertEquals(List.of("before:CREATE_WARP_OPACITY_MORPH_KEYFORM:WarpA:Shape:0.5"), events);
            events.clear();
            assertThrows(IllegalArgumentException.class, () -> warp.createMorphKeyform(parameter, 0, List.of()));
            semantic.awaitIdle();
            assertTrue(events.isEmpty());
            assertTrue(errors.isEmpty(), errors.toString());
        } finally {
            semantic.close();
        }
    }

    // ---------------------------------------------------------------- fixtures

}
