package dev.turboism.exportsettings;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedCanvasInfo;
import dev.turboism.sdk.cubism.core.OwnedDeformer;
import dev.turboism.sdk.cubism.core.OwnedDrawable;
import dev.turboism.sdk.cubism.core.OwnedGlue;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import dev.turboism.sdk.cubism.core.OwnedParameter;
import dev.turboism.sdk.cubism.core.OwnedPart;
import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

/** Shared host model and fault injection for protected export transactions. */
final class ProtectedExportTestFixtures {
    private ProtectedExportTestFixtures() {}

    private static final ProtectedExportOrchestrator.EdtDispatcher INLINE_EDT =
            new ProtectedExportOrchestrator.EdtDispatcher() {
                @Override
                public <T> T call(final Callable<T> action) throws Exception {
                    return action.call();
                }

                @Override
                public void submit(final Runnable task) {
                    task.run();
                }
            };

    static final class Fixture {
        final FakeHost host = new FakeHost();
        final Object outerDialog = new Object();
        final File realPick;
        final Path stagingRoot;
        final AtomicLong hostGeneration = new AtomicLong(7L);
        final AtomicBoolean seamInstalled = new AtomicBoolean(true);
        final AtomicBoolean bindingLive = new AtomicBoolean(true);
        final BlockingQueue<ProtectedExportOrchestrator.Report> reports = new LinkedBlockingQueue<>();
        volatile boolean mocLoads = true;
        /** Injected into the staging move seam — drives real rollback paths. */
        volatile ProtectedExportStaging.MoveOp publishMoveOp;

        ProtectedExportOrchestrator orchestrator;

        Fixture(final Path tempDir) throws IOException {
            stagingRoot = tempDir.resolve("staging");
            final Path destinationDir = tempDir.resolve("destination");
            Files.createDirectories(destinationDir);
            realPick = destinationDir.resolve("model.moc3").toFile();
            host.original.file = tempDir.resolve("source.cmo3").toFile();
            Files.writeString(host.original.file.toPath(), "fixture-cmo3");
            host.activeDoc = host.original;
            host.project.add(host.original);
            host.project.add(host.otherDoc);
            host.realPick = realPick;
            host.bindingLiveFlag = bindingLive;
            host.hostGenerationFlag = hostGeneration;
            host.orchestratorRef = () -> orchestrator;
        }

        /** Live parameter values per instantiated fake model — the writer's target. */
        final IdentityHashMap<OwnedModel, Map<String, Float>> fakeModelValues = new IdentityHashMap<>();

        /**
         * Fake Core-parameter write seam mirroring {@code OwnedMocRuntime}'s
         * runtime-private path: writes the model's live value table, which
         * {@code drawables()} reads when re-projecting evaluated positions.
         */
        private void writeFakeParameter(final OwnedModel model, final String parameterId, final float value) {
            final Map<String, Float> values = fakeModelValues.get(model);
            if (values == null || !values.containsKey(parameterId)) {
                throw new IllegalStateException("parameter absent: " + parameterId);
            }
            values.put(parameterId, value);
        }

        ProtectedExportOrchestrator orchestrator() {
            if (orchestrator == null) {
                orchestrator = new ProtectedExportOrchestrator(
                        host,
                        new ProtectedExportStaging(
                                data -> mocLoads ? fakeMoc() : null,
                                this::writeFakeParameter,
                                publishMoveOp != null
                                        ? publishMoveOp
                                        : (source, target) ->
                                                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING)),
                        stagingRoot,
                        "dev.turboism.plugin.protected-export",
                        "protected-export",
                        seamInstalled::get,
                        bindingLive::get,
                        hostGeneration::get,
                        INLINE_EDT,
                        reports::add,
                        400L,
                        400L);
            }
            return orchestrator;
        }

        ProtectedExportOrchestrator.Report awaitReport() throws InterruptedException {
            final ProtectedExportOrchestrator.Report report = reports.poll(30L, TimeUnit.SECONDS);
            assertNotNull(report, "session report never arrived");
            return report;
        }

        /** Regular files currently in the user's real destination directory. */
        List<Path> destinationFiles() throws IOException {
            try (var stream = Files.list(realPick.toPath().getParent())) {
                return stream.filter(Files::isRegularFile).toList();
            }
        }

        private OwnedMoc fakeMoc() {
            return new OwnedMoc() {
                @Override
                public MocVersion version() {
                    return MocVersion.V5_0;
                }

                @Override
                public MocConsistency consistency() {
                    return MocConsistency.CONSISTENT;
                }

                @Override
                public long nativeHandle() {
                    return 1L;
                }

                @Override
                public OwnedModel instantiateModel() {
                    final FakeModel exported = host.exportedModel;
                    if (exported == null) {
                        return null;
                    }
                    return fakeModel(exported);
                }

                @Override
                public void close() {}
            };
        }

        /**
         * Projects the model the fake export saw into the read surface the staged
         * validator consumes — drawable IDs reflect the copy's (obfuscated) state
         * unless a fault knob rewrites them.
         */
        private OwnedModel fakeModel(final FakeModel exported) {
            // Live parameter values the writer mutates; evaluated drawable
            // positions re-project from this table exactly like the real Core.
            final Map<String, Float> liveValues = new HashMap<>();
            exported.parameters.forEach(parameter -> liveValues.put(parameter.id, parameter.defaultValue));
            final List<OwnedPart> parts = new ArrayList<>();
            exported.parts.forEach(part -> parts.add(new OwnedPart(part.id, 1f, -1)));
            final List<OwnedDeformer> deformers = new ArrayList<>();
            if (host.exportLeavesDeformer) {
                deformers.add(new OwnedDeformer("d-left", -1, List.of()));
            }
            final OwnedModel model = new OwnedModel() {
                @Override
                public long nativeHandle() {
                    return 1L;
                }

                @Override
                public OwnedCanvasInfo canvasInfo() {
                    return new OwnedCanvasInfo(1000f, 1000f, 500f, 500f, 1000f);
                }

                @Override
                public List<OwnedParameter> parameters() {
                    final List<OwnedParameter> projected = new ArrayList<>();
                    exported.parameters.forEach(parameter -> {
                        // Serialized keys = union of key positions across the
                        // model's bindings for the parameter.
                        final Set<Float> keys = new java.util.TreeSet<>();
                        exported.artMeshes.forEach(mesh -> mesh.bindings.forEach(binding -> {
                            if (binding.parameterId.equals(parameter.id)) {
                                keys.addAll(binding.keys);
                            }
                        }));
                        exported.deformers.forEach(deformer -> deformer.bindings.forEach(binding -> {
                            if (binding.parameterId.equals(parameter.id)) {
                                keys.addAll(binding.keys);
                            }
                        }));
                        projected.add(new OwnedParameter(
                                parameter.id,
                                0,
                                parameter.min,
                                parameter.max,
                                parameter.defaultValue,
                                liveValues.get(parameter.id),
                                List.copyOf(keys),
                                java.util.Optional.empty()));
                    });
                    if (host.exportAddsParameter) {
                        projected.add(new OwnedParameter(
                                "param-injected", 0, 0f, 1f, 0f, 0f, List.of(0f, 1f), java.util.Optional.empty()));
                    }
                    return projected;
                }

                @Override
                public List<OwnedPart> parts() {
                    return parts;
                }

                @Override
                public List<OwnedDrawable> drawables() {
                    final List<OwnedDrawable> projected = new ArrayList<>();
                    exported.artMeshes.forEach(mesh -> {
                        final float[] positions = evalPositions(mesh.bindings, liveValues);
                        for (int i = 0; i < positions.length; i++) {
                            positions[i] += mesh.basePositions[i];
                        }
                        if (host.exportDriftsGeometry) {
                            // The exporter's serialized geometry diverges from
                            // live evaluation — contracts still match, only the
                            // behavior oracle can see the positional drift.
                            for (int i = 0; i < positions.length; i++) {
                                positions[i] *= 1.5f;
                            }
                        }
                        if (host.exportWritesNonFinite && positions.length > 0) {
                            // NaN output must never equal finite evidence —
                            // Math.abs(NaN - x) > tolerance is false.
                            positions[0] = Float.NaN;
                        }
                        final float[] stagedPositions = host.exportWritesEmptyGeometry ? new float[0] : positions;
                        // Serialize in moc model space like the real exporter:
                        // origin-centered units, Y flipped (moc Y points up).
                        final List<Float> vertexPositions = new ArrayList<>();
                        for (int i = 0; i < stagedPositions.length; i++) {
                            vertexPositions.add(((i & 1) == 0 ? 1f : -1f) * (stagedPositions[i] - 500f) / 1000f);
                        }
                        projected.add(new OwnedDrawable(
                                host.exportKeepsOriginalDrawableIds
                                        ? mesh.guid.replace("-guid", "-original")
                                        : mesh.drawableId,
                                (byte) 0,
                                (byte) 0,
                                BlendMode.NORMAL,
                                0,
                                0,
                                0,
                                1f,
                                List.of(),
                                vertexPositions,
                                List.of(),
                                List.of(),
                                new Color(1f, 1f, 1f, 1f),
                                new Color(0f, 0f, 0f, 0f),
                                -1,
                                -1,
                                List.of()));
                    });
                    return projected;
                }

                @Override
                public List<OwnedGlue> glues() {
                    // The fake exporter serializes glues under their unchanged
                    // IDs, with drawable A/B as indices into the drawable table.
                    final List<OwnedGlue> projected = new ArrayList<>();
                    if (!host.exportDropsGlue) {
                        exported.glues.forEach(glue -> projected.add(new OwnedGlue(
                                glue.id,
                                exported.artMeshes.indexOf(glue.targetA),
                                exported.artMeshes.indexOf(glue.targetB),
                                List.of())));
                    }
                    return projected;
                }

                @Override
                public List<OwnedDeformer> deformers() {
                    return deformers;
                }

                @Override
                public void update() {}

                @Override
                public void close() {}
            };
            fakeModelValues.put(model, liveValues);
            return model;
        }
    }

    static final class FakeSelector {
        final List<Object> selected = new ArrayList<>();
    }

    /** Minimal document/model graph the orchestrator's phases actually read. */
    static final class FakeDoc {
        File file;
        final FakeModel model = new FakeModel();
        final Object content = new Object();
        final FakeSelector selector = new FakeSelector();
        final Object editMode = new Object();
        final Object undo = new Object();
        boolean modified;

        FakeDoc() {
            model.document = this;
        }
    }

    /** One keyform binding: parameter ID plus the key positions it contributes. */
    static final class FakeBinding {
        final String parameterId;
        final List<Float> keys;

        FakeBinding(final String parameterId, final List<Float> keys) {
            this.parameterId = parameterId;
            this.keys = List.copyOf(keys);
        }
    }

    static final class FakeDeformer {
        final String guid;
        final String targetGuid;
        final List<FakeBinding> bindings;
        /**
         * Constant deformation this deformer contributes to every mesh it
         * contains — the fake's stand-in for a deformer-local transform. A
         * bound deformer carries it into the meshes' keyform shapes when the
         * host apply runs; an unbound deformer's constant is silently dropped
         * by apply, mirroring the real host gap the behavior oracle must
         * fail closed on.
         */
        final float constant;

        FakeDeformer(final String guid, final String targetGuid) {
            this(guid, targetGuid, List.of());
        }

        FakeDeformer(final String guid, final String targetGuid, final List<FakeBinding> bindings) {
            this(guid, targetGuid, bindings, 0f);
        }

        FakeDeformer(
                final String guid, final String targetGuid, final List<FakeBinding> bindings, final float constant) {
            this.guid = guid;
            this.targetGuid = targetGuid;
            this.bindings = new ArrayList<>(bindings);
            this.constant = constant;
        }
    }

    static final class FakeArtMesh {
        final String guid;
        String name;
        String drawableId;
        final List<FakeBinding> bindings;
        /** Authored base shape contribution added into evaluated positions. */
        float[] basePositions = new float[4];
        /** Post-evaluation vertex positions, filled by {@code evaluateModelInstance}. */
        float[] evaluatedPositions;

        FakeArtMesh(final String guid, final String name, final String drawableId) {
            this(guid, name, drawableId, List.of());
        }

        FakeArtMesh(final String guid, final String name, final String drawableId, final List<FakeBinding> bindings) {
            this.guid = guid;
            this.name = name;
            this.drawableId = drawableId;
            this.bindings = new ArrayList<>(bindings);
        }
    }

    static final class FakeParameter {
        final String id;
        /** Stable GUID — physics input/output entries reference parameters by it. */
        final String guid;

        final float min;
        final float max;
        final float defaultValue;
        final boolean repeat;
        /** Live current value — the fake's parameter-instance write target. */
        float currentValue;

        FakeParameter(final String id) {
            this(id, 0f, 1f, 0f, false);
        }

        FakeParameter(
                final String id, final float min, final float max, final float defaultValue, final boolean repeat) {
            this.id = id;
            this.guid = id + "-guid";
            this.min = min;
            this.max = max;
            this.defaultValue = defaultValue;
            this.repeat = repeat;
            this.currentValue = defaultValue;
        }
    }

    static final class FakePart {
        final String guid;
        final String id;
        String name;
        final List<String> childGuids;

        FakePart(final String guid, final String id) {
            this(guid, id, "part-name-" + id, new ArrayList<>());
        }

        FakePart(final String guid, final String id, final String name, final List<String> childGuids) {
            this.guid = guid;
            this.id = id;
            this.name = name;
            this.childGuids = childGuids;
        }
    }

    /**
     * A Glue affecter: stable GUID + ID + local name plus the two target ArtMesh
     * references it binds. Targets hold the resolved mesh objects like the real
     * {@code CGlueSource} reference; a {@code null} target models a reference
     * that stopped resolving.
     */
    static final class FakeGlue {
        final String guid;
        String id;
        String name;
        FakeArtMesh targetA;
        FakeArtMesh targetB;

        FakeGlue(
                final String guid,
                final String id,
                final String name,
                final FakeArtMesh targetA,
                final FakeArtMesh targetB) {
            this.guid = guid;
            this.id = id;
            this.name = name;
            this.targetA = targetA;
            this.targetB = targetB;
        }
    }

    /**
     * A pass-through census member — art-path, alias or a named family token —
     * with a pinnable identity (stable GUID + ID + name), a parent-deformer edge
     * and ordered reference/flag signatures the census records.
     */
    static final class FakePassThrough {
        final String family;
        final String guid;
        final String id;
        String name;
        String targetDeformerGuid;
        final List<String> referenceGuids;
        final List<String> flags;

        FakePassThrough(
                final String family,
                final String guid,
                final String id,
                final String name,
                final List<String> referenceGuids) {
            this.family = family;
            this.guid = guid;
            this.id = id;
            this.name = name;
            this.referenceGuids = new ArrayList<>(referenceGuids);
            this.flags = new ArrayList<>();
        }
    }

    /** A census object that is not a controllable source — nothing to pin. */
    static final class FakeUnsupported {
        final String family;

        FakeUnsupported(final String family) {
            this.family = family;
        }
    }

    /** A physics input entry: parameter GUID reference plus its pinned values. */
    static final class FakePhysicsInput {
        final String paramGuid;
        final float weight;
        final String type;
        final boolean reflect;
        final float angleScale;
        final float tx;
        final float ty;

        FakePhysicsInput(final String paramGuid, final float weight, final String type, final boolean reflect) {
            this(paramGuid, weight, type, reflect, 1f, 2f, 3f);
        }

        FakePhysicsInput(
                final String paramGuid,
                final float weight,
                final String type,
                final boolean reflect,
                final float angleScale,
                final float tx,
                final float ty) {
            this.paramGuid = paramGuid;
            this.weight = weight;
            this.type = type;
            this.reflect = reflect;
            this.angleScale = angleScale;
            this.tx = tx;
            this.ty = ty;
        }
    }

    /** A physics output entry: destination parameter, vertex and pinned values. */
    static final class FakePhysicsOutput {
        final String paramGuid;
        final int vertexIndex;
        final float weight;
        final String type;
        final boolean reflect;
        final float angleScale;
        final float tx;
        final float ty;
        final float belowMin;
        final float exceededMax;

        FakePhysicsOutput(
                final String paramGuid,
                final int vertexIndex,
                final float weight,
                final String type,
                final boolean reflect) {
            this(paramGuid, vertexIndex, weight, type, reflect, 4f, 5f, 6f, -30f, 30f);
        }

        FakePhysicsOutput(
                final String paramGuid,
                final int vertexIndex,
                final float weight,
                final String type,
                final boolean reflect,
                final float angleScale,
                final float tx,
                final float ty,
                final float belowMin,
                final float exceededMax) {
            this.paramGuid = paramGuid;
            this.vertexIndex = vertexIndex;
            this.weight = weight;
            this.type = type;
            this.reflect = reflect;
            this.angleScale = angleScale;
            this.tx = tx;
            this.ty = ty;
            this.belowMin = belowMin;
            this.exceededMax = exceededMax;
        }
    }

    /** One pendulum vertex: position plus mobility/delay/acceleration/radius. */
    static final class FakePhysicsVertex {
        final float x;
        final float y;
        final float mobility;
        final float delay;
        final float acceleration;
        final float radius;

        FakePhysicsVertex(
                final float x,
                final float y,
                final float mobility,
                final float delay,
                final float acceleration,
                final float radius) {
            this.x = x;
            this.y = y;
            this.mobility = mobility;
            this.delay = delay;
            this.acceleration = acceleration;
            this.radius = radius;
        }
    }

    /**
     * A physics settings object: identity plus the authored behavior content —
     * the fake mirrors the real members the census signature pins. Obfuscation
     * rewrites {@code id}/{@code name} in place, exactly like the host setters.
     */
    static final class FakePhysicsSettings {
        final String guid;
        String id;
        String name;
        boolean enable = true;
        final float[] normPosition = {0f, 0f, 1f};
        final float[] normAngle = {-10f, 0f, 10f};
        float totalAngle = 90f;
        final List<FakePhysicsInput> inputs = new ArrayList<>();
        final List<FakePhysicsOutput> outputs = new ArrayList<>();
        final List<FakePhysicsVertex> vertices = new ArrayList<>();

        FakePhysicsSettings(final String guid, final String id, final String name) {
            this.guid = guid;
            this.id = id;
            this.name = name;
        }

        FakePhysicsSettings copy() {
            final FakePhysicsSettings copy = new FakePhysicsSettings(guid, id, name);
            copy.enable = enable;
            System.arraycopy(normPosition, 0, copy.normPosition, 0, 3);
            System.arraycopy(normAngle, 0, copy.normAngle, 0, 3);
            copy.totalAngle = totalAngle;
            inputs.forEach(input -> copy.inputs.add(new FakePhysicsInput(
                    input.paramGuid, input.weight, input.type, input.reflect, input.angleScale, input.tx, input.ty)));
            outputs.forEach(output -> copy.outputs.add(new FakePhysicsOutput(
                    output.paramGuid,
                    output.vertexIndex,
                    output.weight,
                    output.type,
                    output.reflect,
                    output.angleScale,
                    output.tx,
                    output.ty,
                    output.belowMin,
                    output.exceededMax)));
            vertices.forEach(vertex -> copy.vertices.add(new FakePhysicsVertex(
                    vertex.x, vertex.y, vertex.mobility, vertex.delay, vertex.acceleration, vertex.radius)));
            return copy;
        }
    }

    /** Physics settings-set level state: effective forces, FPS, selection. */
    static final class FakePhysicsSet {
        final float gravityX = 0f;
        final float gravityY = -1f;
        final float windX = 0f;
        final float windY = 0f;
        final Integer fps = 60;
        String selectedGuid;

        FakePhysicsSet copy() {
            final FakePhysicsSet copy = new FakePhysicsSet();
            copy.selectedGuid = selectedGuid;
            return copy;
        }
    }

    /**
     * A motion-sync setting object: identity plus the decomposed content pin
     * (version + mapping/post-processing checksums) — the fake mirrors that the
     * host's whole-object checksum covers the name, so the census pins the
     * content components that survive a rename.
     */
    static final class FakeMotionSyncSettings {
        final String guid;
        String id;
        String name;
        final String version;
        final int mappingChecksum;
        final int postProcChecksum;

        FakeMotionSyncSettings(final String guid, final String id, final String name, final int checksum) {
            this(guid, id, name, "MOTION_SYNC_V5", checksum, checksum + 1);
        }

        FakeMotionSyncSettings(
                final String guid,
                final String id,
                final String name,
                final String version,
                final int mappingChecksum,
                final int postProcChecksum) {
            this.guid = guid;
            this.id = id;
            this.name = name;
            this.version = version;
            this.mappingChecksum = mappingChecksum;
            this.postProcChecksum = postProcChecksum;
        }

        FakeMotionSyncSettings copy() {
            return new FakeMotionSyncSettings(guid, id, name, version, mappingChecksum, postProcChecksum);
        }
    }

    static final class FakeModel {
        final List<FakeDeformer> deformers = new ArrayList<>();
        final List<FakeArtMesh> artMeshes = new ArrayList<>();
        final List<FakeParameter> parameters = new ArrayList<>();
        final List<FakePart> parts = new ArrayList<>();
        /** Glue affecters — an admitted pass-through channel. */
        final List<FakeGlue> glues = new ArrayList<>();
        /** Other admitted pass-through census members (art-path, alias, ...). */
        final List<FakePassThrough> passThrough = new ArrayList<>();
        /** Census members with no pinnable identity — must still reject. */
        final List<Object> unpinnableObjects = new ArrayList<>();

        final List<Object> physicsSettings = new ArrayList<>();
        final List<Object> motionSyncSettings = new ArrayList<>();
        /** Physics settings-set state — always present like the host's final field. */
        FakePhysicsSet physicsSet = new FakePhysicsSet();
        // The host enumerates a synthetic root part in getAllParts but never
        // serializes it into exported output.
        final FakePart rootPart = new FakePart("root-part-guid", "__RootPart__");
        FakeDoc document;
    }

    /**
     * Instance-side ArtMesh handle — the fake mirrors the real host split where
     * evaluated geometry lives on instance objects that point back to sources.
     */
    static final class FakeInstanceMesh {
        final FakeArtMesh source;

        FakeInstanceMesh(final FakeArtMesh source) {
            this.source = source;
        }
    }

    /**
     * Deterministic evaluated geometry shared by the fake host (post-evaluation
     * positions) and the fake staged model (Core-side vertex positions): every
     * bound parameter's current value contributes, weighted by that binding's
     * key count — a dropped or duplicated binding changes the output.
     */
    private static float[] evalPositions(final List<FakeBinding> bindings, final Map<String, Float> values) {
        float sum = 0f;
        float weighted = 0f;
        for (FakeBinding binding : bindings) {
            final Float value = values.get(binding.parameterId);
            if (value != null) {
                sum += value;
                weighted += value * binding.keys.size();
            }
        }
        return new float[] {sum, weighted, sum + weighted, sum - weighted};
    }

    static final class FakeHost implements ProtectedExportHostOperations {
        final FakeDoc original = new FakeDoc();
        final FakeDoc otherDoc = new FakeDoc();
        final Set<FakeDoc> project = new LinkedHashSet<>();
        volatile FakeDoc activeDoc;
        volatile FakeDoc copy;
        volatile File realPick;
        volatile Supplier<ProtectedExportOrchestrator> orchestratorRef;
        volatile AtomicBoolean bindingLiveFlag;
        volatile AtomicLong hostGenerationFlag;
        volatile BiConsumer<File, List<String>> completion;
        volatile FakeModel exportedModel;

        // fault knobs
        volatile boolean bindCopyDoc = true;
        volatile boolean serializeFails;
        volatile long bindDelayMillis;
        volatile boolean bindingDiesOnBind;
        volatile boolean generationBumpsOnBind;
        volatile boolean originalRemovedOnBind;
        volatile boolean applyRemovesDeformer = true;
        volatile boolean vanishDeformerAfterBindCensus;
        volatile boolean duplicateDeformerGuids;
        volatile boolean extendedInterpolation;
        volatile boolean exportShowsDialog = true;
        volatile boolean exportWritesFiles = true;
        volatile boolean exportCompletes = true;
        volatile boolean cancelInnerDialog;
        volatile boolean cancelChooser;
        volatile boolean restoreOriginal = true;
        volatile boolean releaseCopyHandle = true;
        volatile boolean exportKeepsOriginalDrawableIds;
        volatile boolean exportLeavesDeformer;
        volatile boolean exportDriftsGeometry;
        volatile boolean exportWritesNonFinite;
        volatile boolean exportWritesEmptyGeometry;
        volatile boolean exportAddsParameter;
        volatile boolean vanishArtMeshAfterCensus;
        volatile boolean copyFileMarkedReadOnlyOnOpen;
        volatile boolean selectsExtraDeformer;
        volatile boolean mutatePartNameMidRun;
        volatile boolean mutateGlueMidRun;
        volatile boolean mutatePassThroughMidRun;
        volatile boolean mutateSettingsMidRun;
        volatile boolean exportDropsGlue;
        volatile boolean exportWritesPhysics = true;
        volatile boolean exportDropsPhysicsSetting;
        volatile boolean exportMutatesPhysicsContent;
        volatile boolean switchActiveDocOnSelect;
        volatile boolean multiplyColor;
        volatile boolean embeddedMorphTargets;
        volatile boolean attachedExtension;
        volatile boolean revokeBindingOnNativeExport;
        volatile boolean bumpGenerationOnNativeExport;
        volatile boolean flattenCorruptsGeometry;
        volatile boolean copyParameterStartsOffDefault;
        /**
         * Constant deformation carried by the unbound fixture deformer —
         * nonzero exercises the real host gap: the apply step silently drops
         * it, and the post-flatten behavior oracle must fail closed.
         */
        volatile float unboundDeformerConstant;

        volatile boolean removeOriginalOnNativeExport;
        volatile boolean removeCopyOnNativeExport;
        final AtomicInteger applyCalls = new AtomicInteger();
        final AtomicInteger serializeCalls = new AtomicInteger();
        /** The live model source the last serialization captured — staging proof. */
        volatile Object serializedFrom;

        volatile String serializedPayload;
        private int copyCensusCalls;
        private int artMeshCensusCalls;

        FakeHost() {
            original.model.deformers.add(
                    new FakeDeformer("g-leaf", "g-root", List.of(new FakeBinding("param-1", List.of(0f, 0.5f, 1f)))));
            original.model.deformers.add(
                    new FakeDeformer("g-root", null, List.of(new FakeBinding("param-1", List.of(0f, 1f)))));
            // Nested under g-root so flattening order and reparenting are
            // exercised for an unbound deformer too.
            original.model.deformers.add(new FakeDeformer("g-unbound", "g-root", List.of(), unboundDeformerConstant));
            original.model.artMeshes.add(
                    new FakeArtMesh("m-a-guid", "meshA", "id-a", List.of(new FakeBinding("param-1", List.of(0f)))));
            original.model.artMeshes.add(new FakeArtMesh("m-b-guid", "meshB", "id-b"));
            original.model.parameters.add(new FakeParameter("param-1"));
            final FakePart part = new FakePart("part-1-guid", "part-1");
            part.childGuids.addAll(List.of("m-a-guid", "m-b-guid", "g-leaf", "g-root", "g-unbound"));
            original.model.parts.add(part);
            otherDoc.file = new File("other.cmo3");
        }

        private FakeDoc docFor(final File file) {
            if (file.equals(original.file)) {
                return original;
            }
            if (copy != null && file.equals(copy.file)) {
                return copy;
            }
            return null;
        }

        /** Mesh inside {@code model} carrying the same GUID as {@code target}. */
        private static FakeArtMesh copyMeshByGuid(final FakeModel model, final FakeArtMesh target) {
            if (target == null) {
                return null;
            }
            for (FakeArtMesh mesh : model.artMeshes) {
                if (mesh.guid.equals(target.guid)) {
                    return mesh;
                }
            }
            return null;
        }

        @Override
        public Object appController() {
            return this;
        }

        @Override
        public Object currentDocument() {
            return activeDoc;
        }

        @Override
        public Object currentProject() {
            return this;
        }

        @Override
        public boolean projectContains(final Object document) {
            return project.contains(document);
        }

        @Override
        public List<?> projectDocuments() {
            return List.copyOf(project);
        }

        @Override
        public Object mainFrame() {
            return new Object();
        }

        @Override
        public void openFile(final File file) {
            if (bindDelayMillis > 0L && !file.equals(original.file)) {
                try {
                    Thread.sleep(bindDelayMillis);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
            if (file.equals(original.file)) {
                if (restoreOriginal) {
                    // Reopening a document that left the project re-adds it —
                    // the native open path does the same.
                    project.add(original);
                    activeDoc = original;
                }
                return;
            }
            final FakeDoc bound = docFor(file);
            if (bound != null) {
                activeDoc = bound;
                return;
            }
            if (bindingDiesOnBind && bindingLiveFlag != null) {
                // Plugin unload lands while the copy binds.
                bindingLiveFlag.set(false);
            }
            if (generationBumpsOnBind && hostGenerationFlag != null) {
                hostGenerationFlag.incrementAndGet();
            }
            if (originalRemovedOnBind) {
                project.remove(original);
            }
            if (!bindCopyDoc) {
                return;
            }
            final FakeDoc fresh = new FakeDoc();
            fresh.file = file;
            for (FakeDeformer deformer : original.model.deformers) {
                // Knobs are assigned after fixture construction; the copy is
                // where evaluation/bake actually consume the constant.
                final float constant = "g-unbound".equals(deformer.guid) ? unboundDeformerConstant : deformer.constant;
                fresh.model.deformers.add(
                        new FakeDeformer(deformer.guid, deformer.targetGuid, deformer.bindings, constant));
            }
            for (FakeArtMesh mesh : original.model.artMeshes) {
                final FakeArtMesh copyMesh = new FakeArtMesh(mesh.guid, mesh.name, mesh.drawableId, mesh.bindings);
                copyMesh.basePositions = mesh.basePositions.clone();
                fresh.model.artMeshes.add(copyMesh);
            }
            for (FakeParameter parameter : original.model.parameters) {
                fresh.model.parameters.add(new FakeParameter(
                        parameter.id, parameter.min, parameter.max, parameter.defaultValue, parameter.repeat));
            }
            if (copyParameterStartsOffDefault) {
                // A document saved with non-default parameter values: sampling
                // must restore these, not leave the last sample behind.
                fresh.model.parameters.forEach(parameter -> parameter.currentValue = parameter.max);
            }
            for (FakePart part : original.model.parts) {
                fresh.model.parts.add(new FakePart(part.guid, part.id, part.name, new ArrayList<>(part.childGuids)));
            }
            for (FakeGlue glue : original.model.glues) {
                // Serialization re-resolves glue references against the copy's
                // own meshes — identity and targets survive byte-identical.
                fresh.model.glues.add(new FakeGlue(
                        glue.guid,
                        glue.id,
                        glue.name,
                        copyMeshByGuid(fresh.model, glue.targetA),
                        copyMeshByGuid(fresh.model, glue.targetB)));
            }
            for (FakePassThrough member : original.model.passThrough) {
                // Serialized pass-through members keep identity, parent edge,
                // references and flags byte-identical.
                final FakePassThrough cloned =
                        new FakePassThrough(member.family, member.guid, member.id, member.name, member.referenceGuids);
                cloned.targetDeformerGuid = member.targetDeformerGuid;
                cloned.flags.addAll(member.flags);
                fresh.model.passThrough.add(cloned);
            }
            fresh.model.unpinnableObjects.addAll(original.model.unpinnableObjects);
            for (Object setting : original.model.physicsSettings) {
                if (setting instanceof FakePhysicsSettings physics) {
                    fresh.model.physicsSettings.add(physics.copy());
                } else {
                    fresh.model.physicsSettings.add(setting);
                }
            }
            for (Object setting : original.model.motionSyncSettings) {
                if (setting instanceof FakeMotionSyncSettings sync) {
                    fresh.model.motionSyncSettings.add(sync.copy());
                } else {
                    fresh.model.motionSyncSettings.add(setting);
                }
            }
            fresh.model.physicsSet = original.model.physicsSet == null ? null : original.model.physicsSet.copy();
            copy = fresh;
            project.add(fresh);
            activeDoc = fresh;
            if (copyFileMarkedReadOnlyOnOpen) {
                // The editor marks saved documents read-only.
                file.setWritable(false);
            }
        }

        @Override
        public void closeFileContent(final Object fileContent) {
            if (copy != null && copy.content == fileContent) {
                project.remove(copy);
            }
        }

        @Override
        public boolean isModelingDocument(final Object document) {
            return document instanceof FakeDoc;
        }

        @Override
        public Object documentModelSource(final Object document) {
            return ((FakeDoc) document).model;
        }

        @Override
        public Object documentFileContent(final Object document) {
            return ((FakeDoc) document).content;
        }

        @Override
        public Object documentSelector(final Object document) {
            return ((FakeDoc) document).selector;
        }

        @Override
        public Object documentCurrentEditMode(final Object document) {
            return ((FakeDoc) document).editMode;
        }

        @Override
        public Object documentMainEditMode(final Object document) {
            return ((FakeDoc) document).editMode;
        }

        @Override
        public File documentFile(final Object document) {
            return ((FakeDoc) document).file;
        }

        @Override
        public Object documentUndoManager(final Object document) {
            return ((FakeDoc) document).undo;
        }

        @Override
        public void markDocumentSaved(final Object document) {
            ((FakeDoc) document).modified = false;
        }

        @Override
        public File fileContentFile(final Object fileContent) {
            for (FakeDoc doc : project) {
                if (doc.content == fileContent) {
                    return doc.file;
                }
            }
            return null;
        }

        @Override
        public boolean fileContentModified(final Object fileContent) {
            for (FakeDoc doc : project) {
                if (doc.content == fileContent) {
                    return doc.modified;
                }
            }
            return false;
        }

        @Override
        public int undoPosition(final Object undoManager) {
            return 0;
        }

        @Override
        public int undoEditCount(final Object undoManager) {
            return 0;
        }

        @Override
        public boolean undoCanUndo(final Object undoManager) {
            return false;
        }

        @Override
        public boolean isMainSelector(final Object selector) {
            return selector instanceof FakeSelector;
        }

        @Override
        public boolean isMainEditMode(final Object editMode) {
            return editMode != null;
        }

        @Override
        public void clearSelection(final Object selector) {
            ((FakeSelector) selector).selected.clear();
        }

        @Override
        public int selectedCount(final Object selector) {
            return ((FakeSelector) selector).selected.size();
        }

        @Override
        public void selectSource(final Object selector, final Object source) {
            ((FakeSelector) selector).selected.add(source);
            if (selectsExtraDeformer) {
                // A foreign deformer coexists in the selection — the apply must
                // refuse rather than consume an unplanned target.
                ((FakeSelector) selector).selected.add(new FakeDeformer("g-foreign", null));
            }
            if (switchActiveDocOnSelect) {
                // Selection callbacks reenter host code: the active document
                // flips back to the original while the selection itself still
                // holds the planned deformer.
                activeDoc = original;
            }
        }

        @Override
        public List<?> selectedDeformers(final Object selector) {
            return List.copyOf(((FakeSelector) selector).selected);
        }

        @Override
        public void applyDeformerToParameters(final Object mainEditMode) {
            applyCalls.incrementAndGet();
            final FakeDoc doc = (FakeDoc) activeDoc;
            if (applyRemovesDeformer) {
                if (mutatePartNameMidRun && doc == copy) {
                    // A mutation outside the orchestrator's plan touches part
                    // identity — the post-mutation census must catch it.
                    doc.model.parts.forEach(part -> part.name = "part-mutated");
                }
                if (mutateGlueMidRun && doc == copy) {
                    // A mutation outside the orchestrator's plan touches glue
                    // identity — the pass-through census must catch it.
                    doc.model.glues.forEach(glue -> glue.name = "glue-mutated");
                }
                if (mutatePassThroughMidRun && doc == copy) {
                    doc.model.passThrough.forEach(member -> member.name = "member-mutated");
                }
                if (mutateSettingsMidRun && doc == copy) {
                    doc.model.physicsSettings.forEach(setting -> {
                        if (setting instanceof FakePhysicsSettings physics) {
                            physics.enable = !physics.enable;
                        }
                    });
                }
                final List<FakeDeformer> removed = doc.model.deformers.stream()
                        .filter(d -> doc.selector.selected.contains(d))
                        .toList();
                doc.model.deformers.removeAll(removed);
                // A removed deformer's bindings move onto every ArtMesh it
                // deformed (the fake treats every deformer as deforming every
                // mesh). Appending — rather than unioning key positions — keeps
                // the fake's evaluation exactly invariant under the move, as
                // the real keyform-transfer apply does. The parameter key
                // union the census records is preserved either way.
                for (FakeDeformer deformer : removed) {
                    for (FakeArtMesh mesh : doc.model.artMeshes) {
                        for (FakeBinding binding : deformer.bindings) {
                            mesh.bindings.add(new FakeBinding(binding.parameterId, new ArrayList<>(binding.keys)));
                        }
                        if (!deformer.bindings.isEmpty()) {
                            // Bound deformers preserve their constant
                            // contribution through the bound keyforms; an
                            // unbound deformer's constant is silently dropped —
                            // the real-host gap the behavior oracle must catch.
                            for (int i = 0; i < mesh.basePositions.length; i++) {
                                mesh.basePositions[i] += deformer.constant;
                            }
                        }
                    }
                    for (FakePart part : doc.model.parts) {
                        part.childGuids.remove(deformer.guid);
                    }
                    doc.model.rootPart.childGuids.remove(deformer.guid);
                }
            }
            doc.modified = true;
            doc.selector.selected.clear();
        }

        @Override
        public boolean serializeModelSource(final Object modelSource, final File target) {
            serializeCalls.incrementAndGet();
            serializedFrom = modelSource;
            if (serializeFails) {
                return false;
            }
            // The fake's "serializer": the file content is derived from the live
            // model state — mesh names and parameter ranges — so a staged file
            // provably reflects in-memory edits, not the disk bytes.
            try {
                final FakeModel model = (FakeModel) modelSource;
                final StringBuilder payload = new StringBuilder("serialized:");
                for (FakeArtMesh mesh : model.artMeshes) {
                    payload.append(mesh.guid).append('=').append(mesh.name).append(';');
                }
                for (FakeParameter parameter : model.parameters) {
                    payload.append(parameter.id)
                            .append('=')
                            .append(parameter.min)
                            .append('-')
                            .append(parameter.max)
                            .append(';');
                }
                serializedPayload = payload.toString();
                Files.writeString(target.toPath(), serializedPayload);
                return true;
            } catch (IOException failure) {
                return false;
            }
        }

        @Override
        public Object modelSourceDocument(final Object modelSource) {
            return ((FakeModel) modelSource).document;
        }

        @Override
        public Object modelSourceCurrentInstance(final Object modelSource) {
            return modelSource;
        }

        @Override
        public List<?> allDeformers(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            if (duplicateDeformerGuids && model == original.model) {
                return List.of(new FakeDeformer("g-dup", null), new FakeDeformer("g-dup", null));
            }
            if (copy != null && model == copy.model && vanishDeformerAfterBindCensus) {
                // First copy census is the binding-confirmation plan; later reads
                // (flatten's re-resolve) no longer see the leaf deformer.
                copyCensusCalls++;
                if (copyCensusCalls >= 2) {
                    return model.deformers.stream()
                            .filter(d -> !"g-leaf".equals(d.guid))
                            .toList();
                }
            }
            return List.copyOf(model.deformers);
        }

        @Override
        public List<?> allObjects(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            final List<Object> all = new ArrayList<>();
            all.addAll(model.deformers);
            all.addAll(model.artMeshes);
            // Parameter sources are not parameter-controllable on the real host:
            // getAllObjects never returns them.
            all.add(model.rootPart);
            all.addAll(model.parts);
            all.addAll(model.glues);
            all.addAll(model.passThrough);
            all.addAll(model.unpinnableObjects);
            return all;
        }

        @Override
        public List<?> allArtMeshes(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            if (copy != null
                    && vanishArtMeshAfterCensus
                    && model == copy.model
                    && model.artMeshes.size() > 1
                    && ++artMeshCensusCalls > 1) {
                // The census planned over both meshes; later reads (per-target
                // re-resolution, the post-pass census) no longer see the first.
                return List.copyOf(model.artMeshes.subList(1, model.artMeshes.size()));
            }
            return List.copyOf(model.artMeshes);
        }

        @Override
        public List<?> allParts(final Object modelSource) {
            final FakeModel model = (FakeModel) modelSource;
            final List<Object> all = new ArrayList<>(model.parts.size() + 1);
            all.add(model.rootPart);
            all.addAll(model.parts);
            return List.copyOf(all);
        }

        @Override
        public Object rootPart(final Object modelSource) {
            return ((FakeModel) modelSource).rootPart;
        }

        @Override
        public List<?> allParameters(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parameters);
        }

        @Override
        public List<?> allPhysicsSettings(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).physicsSettings);
        }

        @Override
        public List<?> allMotionSyncSettings(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).motionSyncSettings);
        }

        @Override
        public String modelSourceGuid(final Object modelSource) {
            return "model-guid";
        }

        @Override
        public List<?> liveParameters(final Object modelSource) {
            return List.copyOf(((FakeModel) modelSource).parameters);
        }

        @Override
        public String parameterInstanceId(final Object parameter) {
            return parameter instanceof FakeParameter p ? p.id : null;
        }

        @Override
        public float parameterInstanceValue(final Object parameter) {
            return parameter instanceof FakeParameter p ? p.currentValue : Float.NaN;
        }

        @Override
        public void setParameterInstanceValue(final Object parameterInstance, final float value) {
            ((FakeParameter) parameterInstance).currentValue = value;
        }

        @Override
        public void evaluateModelInstance(final Object modelInstance) {
            // The fake's "update model": every ArtMesh's evaluated positions are
            // recomputed from the current parameter values — the same function
            // the fake staged model evaluates on the Core side.
            final FakeModel model = (FakeModel) modelInstance;
            final Map<String, Float> values = new HashMap<>();
            for (FakeParameter parameter : model.parameters) {
                values.put(parameter.id, parameter.currentValue);
            }
            for (FakeArtMesh mesh : model.artMeshes) {
                // Deformer bindings deform every mesh they contain; the fake
                // models the worst case (all meshes) so an apply that appends
                // those bindings onto the mesh keeps evaluation identical.
                final List<FakeBinding> effective = new ArrayList<>(mesh.bindings);
                for (FakeDeformer deformer : model.deformers) {
                    effective.addAll(deformer.bindings);
                }
                mesh.evaluatedPositions = evalPositions(effective, values);
                for (int i = 0; i < mesh.evaluatedPositions.length; i++) {
                    mesh.evaluatedPositions[i] += mesh.basePositions[i];
                    for (FakeDeformer deformer : model.deformers) {
                        mesh.evaluatedPositions[i] += deformer.constant;
                    }
                }
                if (flattenCorruptsGeometry && model.deformers.isEmpty()) {
                    // A flatten that damaged geometry: evaluated positions
                    // diverge from the pre-flatten snapshot even though the
                    // exporter would faithfully reproduce them.
                    for (int i = 0; i < mesh.evaluatedPositions.length; i++) {
                        mesh.evaluatedPositions[i] *= 1.5f;
                    }
                }
            }
        }

        @Override
        public List<?> modelInstanceArtMeshes(final Object modelInstance) {
            return ((FakeModel) modelInstance)
                    .artMeshes.stream().map(FakeInstanceMesh::new).toList();
        }

        @Override
        public Object artMeshInstanceSource(final Object artMeshInstance) {
            return artMeshInstance instanceof FakeInstanceMesh mesh ? mesh.source : null;
        }

        @Override
        public float[] evaluatedArtMeshPositions(final Object artMeshInstance) {
            if (!(artMeshInstance instanceof FakeInstanceMesh mesh)) {
                return null;
            }
            return mesh.source.evaluatedPositions == null ? null : mesh.source.evaluatedPositions.clone();
        }

        @Override
        public List<?> deformerChildren(final Object deformerSource) {
            // The fake models the worst case: every deformer contains every
            // mesh, so an unbound deformer's constant reaches all of them.
            // Flatten operates on the bound copy, which is the active doc.
            return List.copyOf(((FakeDoc) activeDoc).model.artMeshes);
        }

        @Override
        public String objectGuid(final Object source) {
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeDeformer deformer) {
                return deformer.guid;
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.guid;
            }
            if (source instanceof FakePart part) {
                return part.guid;
            }
            if (source instanceof FakeGlue glue) {
                return glue.guid;
            }
            if (source instanceof FakePassThrough member) {
                return member.guid;
            }
            return "object-guid";
        }

        @Override
        public String objectIdString(final Object source) {
            // Mirror the real host: the controllable accessor rejects
            // parameter sources with IllegalArgumentException.
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.drawableId;
            }
            if (source instanceof FakePart part) {
                return part.id;
            }
            if (source instanceof FakeGlue glue) {
                return glue.id;
            }
            if (source instanceof FakePassThrough member) {
                return member.id;
            }
            return "object-id-" + System.identityHashCode(source);
        }

        @Override
        public List<?> keyformBindings(final Object controllableSource) {
            if (controllableSource instanceof FakeDeformer deformer) {
                return deformer.bindings;
            }
            if (controllableSource instanceof FakeArtMesh mesh) {
                return mesh.bindings;
            }
            return List.of();
        }

        @Override
        public String keyformBindingParameterId(final Object binding) {
            return ((FakeBinding) binding).parameterId;
        }

        @Override
        public List<Float> keyformBindingKeys(final Object binding) {
            return ((FakeBinding) binding).keys;
        }

        @Override
        public boolean isPartSource(final Object object) {
            return object instanceof FakePart;
        }

        @Override
        public boolean isGlueSource(final Object object) {
            return object instanceof FakeGlue;
        }

        @Override
        public boolean isControllableSource(final Object object) {
            return object instanceof FakeDeformer
                    || object instanceof FakeArtMesh
                    || object instanceof FakePart
                    || object instanceof FakeGlue
                    || object instanceof FakePassThrough;
        }

        @Override
        public String sourceTargetDeformerGuid(final Object source) {
            if (source instanceof FakePassThrough member) {
                return member.targetDeformerGuid;
            }
            if (source instanceof FakeDeformer deformer) {
                return deformer.targetGuid;
            }
            return null;
        }

        /**
         * Family-defined reference pins: Glue yields its two mesh targets,
         * a FakePassThrough yields its recorded references, everything else
         * contributes nothing.
         */
        @Override
        public List<String> passThroughReferenceGuids(final Object source) {
            if (source instanceof FakeGlue) {
                return glueTargetGuids(source);
            }
            if (source instanceof FakePassThrough member) {
                return java.util.Collections.unmodifiableList(new ArrayList<>(member.referenceGuids));
            }
            return List.of();
        }

        @Override
        public List<String> passThroughFlagSignature(final Object source) {
            if (source instanceof FakePassThrough member) {
                return java.util.Collections.unmodifiableList(new ArrayList<>(member.flags));
            }
            return List.of();
        }

        @Override
        public List<String> glueTargetGuids(final Object glueSource) {
            if (!(glueSource instanceof FakeGlue glue)) {
                return List.of();
            }
            final List<String> targets = new ArrayList<>(2);
            targets.add(glue.targetA == null ? null : glue.targetA.guid);
            targets.add(glue.targetB == null ? null : glue.targetB.guid);
            return java.util.Collections.unmodifiableList(targets);
        }

        @Override
        public List<String> partChildGuids(final Object partSource) {
            return List.copyOf(((FakePart) partSource).childGuids);
        }

        @Override
        public String objectLocalName(final Object source) {
            if (source instanceof FakeParameter) {
                throw new IllegalArgumentException("not a controllable source");
            }
            if (source instanceof FakeArtMesh mesh) {
                return mesh.name;
            }
            if (source instanceof FakePart part) {
                return part.name;
            }
            if (source instanceof FakeGlue glue) {
                return glue.name;
            }
            if (source instanceof FakePassThrough member) {
                return member.name;
            }
            return "object-name-" + System.identityHashCode(source);
        }

        @Override
        public String parameterSourceIdString(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.id : null;
        }

        @Override
        public String parameterSourceName(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? "param-name-" + parameter.id : null;
        }

        @Override
        public Float parameterSourceMinValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.min : null;
        }

        @Override
        public Float parameterSourceMaxValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.max : null;
        }

        @Override
        public Float parameterSourceDefaultValue(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.defaultValue : null;
        }

        @Override
        public Boolean parameterSourceRepeat(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.repeat : null;
        }

        @Override
        public void setObjectLocalName(final Object source, final String name) {
            ((FakeArtMesh) source).name = name;
        }

        @Override
        public String drawableIdString(final Object drawableSource) {
            return drawableSource instanceof FakeArtMesh mesh ? mesh.drawableId : null;
        }

        @Override
        public void setDrawableId(final Object drawableSource, final String idString) {
            ((FakeArtMesh) drawableSource).drawableId = idString;
        }

        @Override
        public String censusFamily(final Object object) {
            if (object instanceof FakeGlue) {
                return "glue";
            }
            if (object instanceof FakePassThrough member) {
                return member.family;
            }
            return object instanceof FakeUnsupported unsupported ? unsupported.family : "unknown";
        }

        @Override
        public boolean isPhysicsSettingsSource(final Object object) {
            return object instanceof FakePhysicsSettings;
        }

        @Override
        public boolean isMotionSyncSettingSource(final Object object) {
            return object instanceof FakeMotionSyncSettings;
        }

        @Override
        public String settingsGuid(final Object settingsSource) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                return physics.guid;
            }
            if (settingsSource instanceof FakeMotionSyncSettings sync) {
                return sync.guid;
            }
            return null;
        }

        @Override
        public String settingsIdString(final Object settingsSource) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                return physics.id;
            }
            if (settingsSource instanceof FakeMotionSyncSettings sync) {
                return sync.id;
            }
            return null;
        }

        @Override
        public String settingsName(final Object settingsSource) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                return physics.name;
            }
            if (settingsSource instanceof FakeMotionSyncSettings sync) {
                return sync.name;
            }
            return null;
        }

        /**
         * Behavior-content signature built from the fake's structured members —
         * the same token grammar the verified host operations produce, so the
         * staged physics3 comparison exercises the real field mapping.
         */
        @Override
        public List<String> settingsSignature(final Object modelSource, final Object settingsSource) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                final List<String> tokens = new ArrayList<>();
                tokens.add("enable=" + physics.enable);
                tokens.add("normalization.position=" + physics.normPosition[0] + "|" + physics.normPosition[1] + "|"
                        + physics.normPosition[2]);
                tokens.add("normalization.angle=" + physics.normAngle[0] + "|" + physics.normAngle[1] + "|"
                        + physics.normAngle[2]);
                tokens.add("totalAngle=" + physics.totalAngle);
                for (int i = 0; i < physics.inputs.size(); i++) {
                    final FakePhysicsInput input = physics.inputs.get(i);
                    final String parameterId = fakeParameterId((FakeModel) modelSource, input.paramGuid);
                    if (parameterId == null) {
                        return null;
                    }
                    tokens.add("input." + i + "=" + parameterId
                            + "|" + input.weight + "|" + input.type + "|"
                            + input.reflect + "|" + input.angleScale
                            + "|" + input.tx + "," + input.ty);
                }
                for (int i = 0; i < physics.outputs.size(); i++) {
                    final FakePhysicsOutput output = physics.outputs.get(i);
                    final String parameterId = fakeParameterId((FakeModel) modelSource, output.paramGuid);
                    if (parameterId == null) {
                        return null;
                    }
                    tokens.add("output." + i + "=" + parameterId
                            + "|" + output.vertexIndex + "|" + output.weight
                            + "|" + output.type + "|" + output.reflect
                            + "|" + output.angleScale
                            + "|" + output.tx + "," + output.ty
                            + "|" + output.belowMin + "|" + output.exceededMax);
                }
                for (int i = 0; i < physics.vertices.size(); i++) {
                    final FakePhysicsVertex vertex = physics.vertices.get(i);
                    tokens.add("vertex." + i + "=" + vertex.x + "," + vertex.y
                            + "|" + vertex.mobility + "|" + vertex.delay
                            + "|" + vertex.acceleration + "|" + vertex.radius);
                }
                return List.copyOf(tokens);
            }
            if (settingsSource instanceof FakeMotionSyncSettings sync) {
                return List.of(
                        "version=" + sync.version,
                        "mapping.checksum=" + sync.mappingChecksum,
                        "postproc.checksum=" + sync.postProcChecksum);
            }
            return null;
        }

        private String fakeParameterId(final FakeModel model, final String paramGuid) {
            for (FakeParameter parameter : model.parameters) {
                if (parameter.guid.equals(paramGuid)) {
                    return parameter.id;
                }
            }
            return null;
        }

        @Override
        public void setSettingsName(final Object settingsSource, final String name) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                physics.name = name;
            } else if (settingsSource instanceof FakeMotionSyncSettings sync) {
                sync.name = name;
            }
        }

        @Override
        public void setSettingsId(final Object settingsSource, final String idString) {
            if (settingsSource instanceof FakePhysicsSettings physics) {
                physics.id = idString;
            } else if (settingsSource instanceof FakeMotionSyncSettings sync) {
                sync.id = idString;
            }
        }

        @Override
        public String parameterSourceGuid(final Object parameterSource) {
            return parameterSource instanceof FakeParameter parameter ? parameter.guid : null;
        }

        @Override
        public List<String> physicsSettingsSetSignature(final Object modelSource) {
            final FakePhysicsSet set = ((FakeModel) modelSource).physicsSet;
            if (set == null) {
                return ((FakeModel) modelSource).physicsSettings.isEmpty() ? List.of() : null;
            }
            return List.of(
                    "gravity=" + set.gravityX + "," + set.gravityY,
                    "wind=" + set.windX + "," + set.windY,
                    "fps=" + set.fps,
                    "selected=" + set.selectedGuid);
        }

        @Override
        public boolean isDeformerSource(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isWarpDeformer(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isRotationDeformer(final Object object) {
            return object instanceof FakeDeformer;
        }

        @Override
        public boolean isArtMeshSource(final Object object) {
            return object instanceof FakeArtMesh;
        }

        @Override
        public String deformerGuid(final Object deformerSource) {
            return ((FakeDeformer) deformerSource).guid;
        }

        @Override
        public String deformerTargetGuid(final Object deformerSource) {
            return ((FakeDeformer) deformerSource).targetGuid;
        }

        @Override
        public boolean usesExtendedInterpolation(final Object modelSource) {
            return extendedInterpolation;
        }

        @Override
        public List<String> modelFeatureFlags(final Object modelSource) {
            // Mirrors the host contain* gates: any populated flag is pass-through
            // content pinned by the census — multiply colour lives on ArtMesh
            // colour composition, not the object list.
            return multiplyColor ? List.of("multiply-color") : List.of();
        }

        @Override
        public List<String> embeddedContentFamilies(final Object controllableSource) {
            final List<String> detected = new ArrayList<>();
            if (embeddedMorphTargets
                    && controllableSource instanceof FakeArtMesh mesh
                    && "m-a-guid".equals(mesh.guid)) {
                detected.add("keyform-morph-target-set");
            }
            if (attachedExtension && controllableSource instanceof FakeDeformer) {
                detected.add("extension:com.live2d.cubism.doc.model.extension.controller.CControllerExtension");
            }
            return detected;
        }

        @Override
        public boolean isExportDialog(final Object owner) {
            return owner != null;
        }

        @Override
        public Object dialogModelSource(final Object dialogOwner) {
            return original.model;
        }

        @Override
        public Object exportDriver() {
            return this;
        }

        @Override
        public void invokeNativeExport(
                final Object driver, final Object modelSource, final Object frame, final Object completionCallback) {
            if (!exportShowsDialog) {
                return; // native pre-check veto: no dialog, no chooser
            }
            final ProtectedExportOrchestrator orchestrator = orchestratorRef.get();
            final Object innerDialog = new Object();
            orchestrator.suppressDialogOptions(innerDialog);
            if (cancelInnerDialog) {
                orchestrator.dialogCancelled(innerDialog);
                return;
            }
            if (cancelChooser) {
                orchestrator.redirectPickedFile(null);
                return;
            }
            final Object stagedPick = orchestrator.redirectPickedFile(realPick);
            if (!(stagedPick instanceof File staged)) {
                return;
            }
            exportedModel = (FakeModel) modelSource;
            final List<String> paths = new ArrayList<>();
            if (exportWritesFiles) {
                try {
                    final Path parent = staged.toPath().getParent();
                    Files.write(staged.toPath(), new byte[] {1, 2, 3});
                    paths.add(staged.toPath().toAbsolutePath().toString());
                    final Path modelJson = parent.resolve(staged.getName().replace(".moc3", "") + ".model3.json");
                    final StringBuilder modelRefs =
                            new StringBuilder("{\"FileReferences\":{\"Moc\":\"" + staged.getName() + "\"");
                    final Path physicsJson = parent.resolve(staged.getName().replace(".moc3", "") + ".physics3.json");
                    if (exportWritesPhysics && !exportedModel.physicsSettings.isEmpty()) {
                        // The user's physics output checkbox is on: the native
                        // flow emits physics3.json — the writer canonicalizes
                        // every setting ID to PhysicsSetting<i+1> in list order
                        // (exactly like the real writer) while the dictionary
                        // Name carries the obfuscated token.
                        Files.writeString(physicsJson, fakePhysics3Json(exportedModel));
                        paths.add(physicsJson.toAbsolutePath().toString());
                        modelRefs
                                .append(",\"Physics\":\"")
                                .append(physicsJson.getFileName())
                                .append("\"");
                    }
                    Files.writeString(modelJson, modelRefs.append("}}").toString());
                    paths.add(modelJson.toAbsolutePath().toString());
                } catch (IOException ignored) {
                }
            } else {
                // Reported but never written: the validator must refuse to publish.
                paths.add(staged.toPath().toAbsolutePath().toString());
            }
            if (exportCompletes && completion != null) {
                completion.accept(staged, paths);
            }
            if (revokeBindingOnNativeExport && bindingLiveFlag != null) {
                // Plugin unload lands while the native modal flow runs.
                bindingLiveFlag.set(false);
            }
            if (bumpGenerationOnNativeExport && hostGenerationFlag != null) {
                hostGenerationFlag.incrementAndGet();
            }
            if (removeOriginalOnNativeExport) {
                project.remove(original);
            }
            if (removeCopyOnNativeExport && copy != null) {
                project.remove(copy);
            }
        }

        /**
         * Renders the fake model's physics settings as a {@code physics3.json}
         * matching the real writer's schema and rounding: IDs canonicalized to
         * {@code PhysicsSetting<i+1>} in list order, floats through a
         * three-decimal {@code DecimalFormat}, {@code Scale} chosen by the
         * output type. {@code exportMutatesPhysicsContent} corrupts one
         * serialized value — staging must catch it.
         */
        private String fakePhysics3Json(final FakeModel model) {
            final java.text.DecimalFormat fmt = new java.text.DecimalFormat();
            fmt.setGroupingUsed(false);
            fmt.setMaximumFractionDigits(3);
            final List<FakePhysicsSettings> emitted = new ArrayList<>();
            boolean dropped = false;
            for (Object setting : model.physicsSettings) {
                if (exportDropsPhysicsSetting && !dropped) {
                    dropped = true; // a setting silently dropped by export
                    continue;
                }
                if (setting instanceof FakePhysicsSettings physics) {
                    emitted.add(physics);
                }
            }
            final StringBuilder out = new StringBuilder("{\"Version\":3,\"Meta\":{");
            int totalInputs = 0, totalOutputs = 0, totalVertices = 0;
            for (FakePhysicsSettings physics : emitted) {
                totalInputs += physics.inputs.size();
                totalOutputs += physics.outputs.size();
                totalVertices += physics.vertices.size();
            }
            out.append("\"PhysicsSettingCount\":")
                    .append(emitted.size())
                    .append(",\"TotalInputCount\":")
                    .append(totalInputs)
                    .append(",\"TotalOutputCount\":")
                    .append(totalOutputs)
                    .append(",\"VertexCount\":")
                    .append(totalVertices);
            final FakePhysicsSet set = model.physicsSet;
            if (set != null) {
                if (set.fps != null) {
                    out.append(",\"Fps\":").append(set.fps);
                }
                out.append(",\"EffectiveForces\":{\"Gravity\":{\"X\":")
                        .append(fmt.format(set.gravityX))
                        .append(",\"Y\":")
                        .append(fmt.format(set.gravityY))
                        .append("},\"Wind\":{\"X\":")
                        .append(fmt.format(set.windX))
                        .append(",\"Y\":")
                        .append(fmt.format(set.windY))
                        .append("}}");
            }
            out.append(",\"PhysicsDictionary\":[");
            for (int i = 0; i < emitted.size(); i++) {
                if (i > 0) {
                    out.append(',');
                }
                out.append("{\"Id\":\"PhysicsSetting")
                        .append(i + 1)
                        .append("\",\"Name\":\"")
                        .append(emitted.get(i).name)
                        .append("\"}");
            }
            out.append("]},\"PhysicsSettings\":[");
            for (int i = 0; i < emitted.size(); i++) {
                final FakePhysicsSettings physics = emitted.get(i);
                if (i > 0) {
                    out.append(',');
                }
                out.append("{\"Id\":\"PhysicsSetting").append(i + 1).append("\",\"Input\":[");
                for (int j = 0; j < physics.inputs.size(); j++) {
                    final FakePhysicsInput input = physics.inputs.get(j);
                    if (j > 0) {
                        out.append(',');
                    }
                    out.append("{\"Source\":{\"Target\":\"Parameter\",\"Id\":\"")
                            .append(fakeParameterId(model, input.paramGuid))
                            .append("\"},\"Weight\":")
                            .append(fmt.format(input.weight))
                            .append(",\"Type\":\"")
                            .append(fakeJsonType(input.type))
                            .append("\",\"Reflect\":")
                            .append(input.reflect)
                            .append('}');
                }
                out.append("],\"Output\":[");
                for (int j = 0; j < physics.outputs.size(); j++) {
                    final FakePhysicsOutput output = physics.outputs.get(j);
                    if (j > 0) {
                        out.append(',');
                    }
                    final float weight = exportMutatesPhysicsContent ? output.weight * 2f : output.weight;
                    out.append("{\"Destination\":{\"Target\":\"Parameter\",\"Id\":\"")
                            .append(fakeParameterId(model, output.paramGuid))
                            .append("\"},\"VertexIndex\":")
                            .append(output.vertexIndex)
                            .append(",\"Scale\":")
                            .append(fmt.format(
                                    switch (output.type) {
                                        case "SRC_TO_X" -> output.tx;
                                        case "SRC_TO_Y" -> output.ty;
                                        default -> output.angleScale;
                                    }))
                            .append(",\"Weight\":")
                            .append(fmt.format(weight))
                            .append(",\"Type\":\"")
                            .append(fakeJsonType(output.type))
                            .append("\",\"Reflect\":")
                            .append(output.reflect)
                            .append('}');
                }
                out.append("],\"Vertices\":[");
                for (int j = 0; j < physics.vertices.size(); j++) {
                    final FakePhysicsVertex vertex = physics.vertices.get(j);
                    if (j > 0) {
                        out.append(',');
                    }
                    out.append("{\"Position\":{\"X\":")
                            .append(fmt.format(vertex.x))
                            .append(",\"Y\":")
                            .append(fmt.format(vertex.y))
                            .append("},\"Mobility\":")
                            .append(fmt.format(vertex.mobility))
                            .append(",\"Delay\":")
                            .append(fmt.format(vertex.delay))
                            .append(",\"Acceleration\":")
                            .append(fmt.format(vertex.acceleration))
                            .append(",\"Radius\":")
                            .append(fmt.format(vertex.radius))
                            .append('}');
                }
                out.append("],\"Normalization\":{\"Position\":{\"Minimum\":")
                        .append(fmt.format(physics.normPosition[0]))
                        .append(",\"Default\":")
                        .append(fmt.format(physics.normPosition[1]))
                        .append(",\"Maximum\":")
                        .append(fmt.format(physics.normPosition[2]))
                        .append("},\"Angle\":{\"Minimum\":")
                        .append(fmt.format(physics.normAngle[0]))
                        .append(",\"Default\":")
                        .append(fmt.format(physics.normAngle[1]))
                        .append(",\"Maximum\":")
                        .append(fmt.format(physics.normAngle[2]))
                        .append("}}}");
            }
            return out.append("]}").toString();
        }

        private static String fakeJsonType(final String type) {
            return switch (type) {
                case "SRC_TO_X" -> "X";
                case "SRC_TO_Y" -> "Y";
                case "SRC_TO_G_ANGLE" -> "Angle";
                default -> type;
            };
        }

        @Override
        public Object newExportCompletionProxy(final BiConsumer<File, List<String>> callback) {
            completion = callback;
            return callback;
        }

        @Override
        public boolean releaseFileHandleFor(final File file) {
            return releaseCopyHandle;
        }

        @Override
        public List<String> fileHandleDiagnostics(final File file) {
            return List.of();
        }
    }
}
