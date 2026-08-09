package dev.turboism.plugin.parameterbatchtransfer.service;

import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferOutcome;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferRow;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BatchTransferStatus;
import dev.turboism.plugin.parameterbatchtransfer.b1.domain.BoundParameterSnapshot;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.FloatSequence;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.IntSequence;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingBatchOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Parts;

import org.junit.jupiter.api.Test;

import dev.turboism.sdk.cubism.model.BlendMode;
import dev.turboism.sdk.cubism.model.Color;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParameterBatchTransferServiceTest {

    private final ParameterBatchTransferService service = new ParameterBatchTransferService();

    private static ParameterBinding binding(final ParameterId parameterId, final boolean morph) {
        return new ParameterBinding(
            ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1")),
            parameterId,
            morph ? ParameterBindingFamily.BLEND_SHAPE : ParameterBindingFamily.KEYFORM_GRID,
            List.of(new ParameterBindingPoint(new ParameterBindingPointId("p"), 0.5f))
        );
    }

    private static ParameterBinding keyformBinding(final String parameterId, final float... values) {
        final ArrayList<ParameterBindingPoint> points = new ArrayList<>();
        for (int index = 0; index < values.length; index++) {
            points.add(new ParameterBindingPoint(
                new ParameterBindingPointId(parameterId + ":" + index),
                values[index]
            ));
        }
        return new ParameterBinding(
            ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1")),
            new ParameterId(parameterId),
            ParameterBindingFamily.KEYFORM_GRID,
            points
        );
    }

    private static BoundParameterSnapshot bound(final String id, final String label, final String markers) {
        return new BoundParameterSnapshot(
            new ParameterId(id), label, label, markers,
            markers.contains("M"), markers.contains("C"),
            markers.contains("M")
                ? ParameterBindingFamily.BLEND_SHAPE
                : ParameterBindingFamily.KEYFORM_GRID,
            binding(new ParameterId(id), markers.contains("M"))
        );
    }

    private static BoundParameterSnapshot candidate(
        final String id, final String label, final boolean morph, final boolean combined
    ) {
        return new BoundParameterSnapshot(
            new ParameterId(id), label, label,
            (morph ? "M" : "") + (combined ? "C" : ""), morph, combined, null, null
        );
    }

    @Test
    void sessionForBuildsBoundSnapshotsWithMarkersAndLabels() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "Param One", ParameterType.BLEND_SHAPE, false));
        model.parameters.add(new FakeParameter("p2", null, ParameterType.NORMAL, true));
        model.parameters.add(new FakeParameter("p3", "Param Three", ParameterType.NORMAL, false));
        model.drawable.bindings.add(binding(new ParameterId("p1"), true));
        model.drawable.bindings.add(binding(new ParameterId("p2"), false));

        final ParameterBatchTransferService.Session session = service.sessionFor(
            model, ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"))
        );

        assertEquals(List.of("p1", "p2"), session.bound().stream()
            .map(snapshot -> snapshot.parameterId().value()).toList());
        final BoundParameterSnapshot morph = session.bound().get(0);
        assertEquals("Param One(p1)", morph.label());
        assertEquals("M", morph.markers());
        assertTrue(morph.morph());
        assertFalse(morph.combined());
        assertEquals(binding(new ParameterId("p1"), true), morph.binding());
        final BoundParameterSnapshot combined = session.bound().get(1);
        assertEquals("p2", combined.label()); // name absent -> id
        assertEquals("C", combined.markers());
        assertTrue(combined.combined());
        assertEquals(3, session.candidates().size());
    }

    @Test
    void sessionForDeformerResolvesBindingsByIdAcrossFamilies() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        final FakeDeformer deformer = new FakeDeformer("warp-1");
        deformer.bindings.add(binding(new ParameterId("p1"), false));
        model.deformers.add(deformer);

        final ParameterBatchTransferService.Session warpSession = service.sessionFor(
            model, ParameterBindingTarget.warpDeformer(new DeformerId("warp-1"))
        );
        assertEquals(1, warpSession.bound().size());
        assertEquals("p1", warpSession.bound().get(0).parameterId().value());
    }

    @Test
    void sessionForUnknownOwnerYieldsNoBoundParameters() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));

        final ParameterBatchTransferService.Session session = service.sessionFor(
            model, ParameterBindingTarget.artMesh(new ArtMeshId("missing-mesh"))
        );

        assertTrue(session.bound().isEmpty());
        assertEquals(1, session.candidates().size());
    }

    @Test
    void targetCandidatesForMorphSourceOfferOnlyMorphTargets() {
        final ParameterBatchTransferService.Session session = new ParameterBatchTransferService.Session(
            List.of(
                bound("src", "Source", "M"),
                bound("bound-other", "Bound Other", "M")
            ),
            List.of(
                candidate("z-param", "Zulu", true, false),
                candidate("bound-other", "Bound Other", true, false),
                candidate("src", "Source", true, false),
                candidate("a-param", "alpha", true, false),
                candidate("normal-param", "Normal", false, false),
                candidate("combined-param", "Combined", true, true)
            )
        );
        final BoundParameterSnapshot source = session.bound().get(0);

        final List<BoundParameterSnapshot> candidates = service.targetCandidates(session, source);

        // morph source: only morph targets (combined or not), bound-other excluded,
        // source kept, sorted by lowercase label
        assertTrue(candidates.stream().allMatch(BoundParameterSnapshot::morph));
        assertEquals(List.of("alpha", "Combined", "Source", "Zulu"), candidates.stream()
            .map(BoundParameterSnapshot::label).toList());
    }

    @Test
    void targetCandidatesForNonMorphSourceExcludeMorphTargets() {
        final ParameterBatchTransferService.Session session = new ParameterBatchTransferService.Session(
            List.of(bound("src", "Source", "")),
            List.of(
                candidate("src", "Source", false, false),
                candidate("normal-param", "Normal", false, false),
                candidate("combined-param", "Combined", false, true),
                candidate("morph-param", "Morph", true, false)
            )
        );
        final BoundParameterSnapshot source = session.bound().get(0);

        final List<BoundParameterSnapshot> candidates = service.targetCandidates(session, source);

        // non-morph source (normal or combined): every non-morph target, morph excluded
        assertTrue(candidates.stream().noneMatch(BoundParameterSnapshot::morph));
        assertEquals(List.of("Combined", "Normal", "Source"), candidates.stream()
            .map(BoundParameterSnapshot::label).toList());
    }

    @Test
    void applySkipsSameParameterRowsAndCountsFailures() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p2", "Two", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false));
        model.batch.failingSources.add(new ParameterId("p3"));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));
        final List<BatchTransferRow> rows = List.of(
            BatchTransferRow.keep(bound("p1", "One", "")),                                  // skipped: same target
            new BatchTransferRow(bound("p2", "Two", ""), new ParameterId("p1"), true),      // applied + inverted
            new BatchTransferRow(bound("p3", "Three", ""), new ParameterId("p2"), false)    // fails
        );

        final BatchTransferOutcome outcome = service.apply(model, owner, rows);

        assertEquals(1, outcome.applied());
        assertEquals(1, outcome.failed());
        assertEquals(BatchTransferStatus.PARTIAL, outcome.status());
        assertEquals(1, model.batch.clampedPlans.size());
        assertTrue(model.batch.clampedPlans.get(0).invertAfterTransfer(),
            "inversion is delegated to atomic clamped transfer");
        assertEquals(new ParameterId("p2"), model.batch.clampedPlans.get(0).sourceParameterId());
        assertEquals(new ParameterId("p1"), model.batch.clampedPlans.get(0).targetParameterId());
        assertEquals(List.of(owner), model.batch.clampedPlans.get(0).targets());
    }

    @Test
    void failedRowsPermanentlyLogTransferContextAndThrowable() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p2", "Two", ParameterType.NORMAL, false));
        model.batch.failingSources.add(new ParameterId("p1"));
        final RecordingLogger logger = new RecordingLogger();
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = new ParameterBatchTransferService(logger).apply(
            model,
            owner,
            List.of(new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p2"), true))
        );

        assertEquals(0, outcome.applied());
        assertEquals(1, outcome.failed());
        assertEquals(
            "PBT_APPLY_FAILED from=p1 to=p2 family=KEYFORM_GRID inverted=true",
            logger.errorMessage
        );
        assertTrue(logger.errorThrowable instanceof IllegalStateException);
    }

    @Test
    void applyAllSkippedRowsYieldsNoChanges() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            BatchTransferRow.keep(bound("p1", "One", ""))
        ));

        assertEquals(BatchTransferStatus.NO_CHANGES, outcome.status());
        assertEquals(0, outcome.applied());
        assertEquals(0, outcome.failed());
        assertTrue(model.batch.clampedPlans.isEmpty());
    }

    @Test
    void applyAllSucceedingRowsYieldsApplied() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p2", "Two", ParameterType.NORMAL, false));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p2"), false)
        ));

        assertEquals(BatchTransferStatus.APPLIED, outcome.status());
        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
    }

    @Test
    void sessionForBoundRowsCarryTheBindingFamily() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "Morph One", ParameterType.BLEND_SHAPE, false));
        model.parameters.add(new FakeParameter("p2", "Normal One", ParameterType.NORMAL, false));
        model.drawable.bindings.add(binding(new ParameterId("p1"), true));
        model.drawable.bindings.add(binding(new ParameterId("p2"), false));

        final ParameterBatchTransferService.Session session = service.sessionFor(
            model, ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"))
        );

        assertEquals(
            List.of(ParameterBindingFamily.BLEND_SHAPE, ParameterBindingFamily.KEYFORM_GRID),
            session.bound().stream().map(BoundParameterSnapshot::family).toList()
        );
        assertEquals(ParameterBindingFamily.BLEND_SHAPE, session.bound().get(0).binding().family());
        assertTrue(session.bound().get(0).morph());
        assertFalse(session.bound().get(1).morph());
        assertTrue(session.candidates().stream().allMatch(candidate -> candidate.family() == null));
    }

    @Test
    void applyMorphRowDispatchesTheWholeBindingOperation() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.BLEND_SHAPE, false));
        model.parameters.add(new FakeParameter("p2", "Two", ParameterType.BLEND_SHAPE, false));
        model.drawable.morphTargets.add(new FakeMorphTarget("p1"));
        model.drawable.morphTargets.add(new FakeMorphTarget("p1"));
        model.drawable.morphTargets.add(new FakeMorphTarget("p1"));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", "M"), new ParameterId("p2"), true)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertEquals(BatchTransferStatus.APPLIED, outcome.status());
        assertEquals(1, model.batch.morphPlans.size());
        assertEquals(new ParameterId("p1"), model.batch.morphPlans.get(0).sourceParameterId());
        assertEquals(new ParameterId("p2"), model.batch.morphPlans.get(0).targetParameterId());
        assertTrue(model.batch.morphPlans.get(0).invertAfterTransfer());
        assertEquals(List.of(owner), model.batch.morphPlans.get(0).targets());
        assertTrue(model.batch.clampedPlans.isEmpty(), "morph rows must not use keyform transfer");
    }

    @Test
    void duplicateNonNoOpDestinationsFailClosedBeforeAnyTransfer() {
        final FakeModel model = new FakeModel();
        model.parameters.add(new FakeParameter("p1", "One", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p2", "Two", ParameterType.NORMAL, false));
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), false),
            new BatchTransferRow(bound("p2", "Two", ""), new ParameterId("p3"), true)
        ));

        assertEquals(0, outcome.applied());
        assertEquals(2, outcome.failed());
        assertEquals(BatchTransferStatus.PARTIAL, outcome.status());
        assertTrue(model.batch.clampedPlans.isEmpty());
        assertTrue(model.batch.morphPlans.isEmpty());
    }

    @Test
    void applyClampsOutOfRangePointsToTheTargetRangeBeforeTransfer() {
        final FakeModel model = new FakeModel();
        final FakeParameter source = new FakeParameter("p1", "One", ParameterType.NORMAL, false, -30f, 30f);
        source.bindings.add(keyformBinding("p1", -30f, 0f, 30f));
        model.parameters.add(source);
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false, -10f, 50f));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), false)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertEquals(1, model.batch.clampedPlans.size());
        assertEquals(new ParameterId("p1"), model.batch.clampedPlans.get(0).sourceParameterId());
        assertEquals(new ParameterId("p3"), model.batch.clampedPlans.get(0).targetParameterId());
    }

    @Test
    void applyKeepsPointsUnchangedWhenTheTargetRangeIsWideEnough() {
        final FakeModel model = new FakeModel();
        final FakeParameter source = new FakeParameter("p1", "One", ParameterType.NORMAL, false, -30f, 30f);
        source.bindings.add(keyformBinding("p1", -30f, 0f, 30f));
        model.parameters.add(source);
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false, -70f, 50f));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), false)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertEquals(1, model.batch.clampedPlans.size());
    }

    @Test
    void applyInvertedRowNegatesPointsBeforeTransfer() {
        final FakeModel model = new FakeModel();
        final FakeParameter source = new FakeParameter("p1", "One", ParameterType.NORMAL, false, -30f, 30f);
        source.bindings.add(keyformBinding("p1", -30f, 0f, 30f));
        model.parameters.add(source);
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false, -70f, 50f));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), true)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertTrue(model.batch.clampedPlans.get(0).invertAfterTransfer());
        assertEquals(new ParameterId("p1"), model.batch.clampedPlans.get(0).sourceParameterId());
        assertEquals(new ParameterId("p3"), model.batch.clampedPlans.get(0).targetParameterId());
    }

    @Test
    void applyInvertedRowNegatesThenClampsOutOfRangePoints() {
        final FakeModel model = new FakeModel();
        final FakeParameter source = new FakeParameter("p1", "One", ParameterType.NORMAL, false, -70f, 70f);
        source.bindings.add(keyformBinding("p1", 50f));
        model.parameters.add(source);
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false, -10f, 50f));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), true)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertTrue(model.batch.clampedPlans.get(0).invertAfterTransfer());
        assertEquals(1, model.batch.clampedPlans.size());
    }

    @Test
    void applyClampsBothSidesToTheTargetBoundaries() {
        final FakeModel model = new FakeModel();
        final FakeParameter source = new FakeParameter("p1", "One", ParameterType.NORMAL, false, -40f, 60f);
        source.bindings.add(keyformBinding("p1", -40f, 0f, 60f));
        model.parameters.add(source);
        model.parameters.add(new FakeParameter("p3", "Three", ParameterType.NORMAL, false, -10f, 50f));
        final ParameterBindingTarget owner = ParameterBindingTarget.artMesh(new ArtMeshId("mesh-1"));

        final BatchTransferOutcome outcome = service.apply(model, owner, List.of(
            new BatchTransferRow(bound("p1", "One", ""), new ParameterId("p3"), false)
        ));

        assertEquals(1, outcome.applied());
        assertEquals(0, outcome.failed());
        assertEquals(1, model.batch.clampedPlans.size());
    }

    private static final class FakeModel implements CubismModel {
        final List<Parameter> parameters = new ArrayList<>();
        final List<Drawable> drawables = new ArrayList<>();
        final List<Deformer> deformers = new ArrayList<>();
        final FakeDrawable drawable = new FakeDrawable("mesh-1");
        final FakeBatch batch = new FakeBatch();

        FakeModel() {
            drawables.add(drawable);
        }

        @Override public ModelId id() { return new ModelId("model-1"); }
        @Override public Parameters parameters() {
            return new Parameters() {
                @Override public List<Parameter> all() { return parameters; }
                @Override public Parameter find(final ParameterId id) {
                    return parameters.stream().filter(value -> value.id().equals(id)).findFirst()
                        .orElseThrow();
                }
            };
        }
        @Override public Drawables drawables() {
            return new Drawables() {
                @Override public List<Drawable> all() { return drawables; }
                @Override public Drawable find(final ArtMeshId id) {
                    return drawables.stream().filter(value -> value.id().equals(id)).findFirst()
                        .orElseThrow();
                }
            };
        }
        @Override public Deformers deformers() {
            return new Deformers() {
                @Override public List<Deformer> all() { return deformers; }
                @Override public Deformer find(final DeformerId id) {
                    return deformers.stream().filter(value -> value.id().equals(id)).findFirst()
                        .orElseThrow();
                }
            };
        }
        @Override public Parts parts() { throw new UnsupportedOperationException(); }
        @Override public Glues glues() { throw new UnsupportedOperationException(); }
        @Override public void update() { }
        @Override public ParameterBindingBatchOperations parameterBindingBatch() { return batch; }
    }


    private static final class FakeDrawable implements Drawable {
        final List<ParameterBinding> bindings = new ArrayList<>();
        final List<MorphTarget> morphTargets = new ArrayList<>();
        private final ArtMeshId id;

        FakeDrawable(final String id) { this.id = new ArtMeshId(id); }

        @Override public ArtMeshId id() { return id; }
        @Override public byte constantFlag() { return 0; }
        @Override public byte dynamicFlag() { return 0; }
        @Override public BlendMode blendMode() { return BlendMode.NORMAL; }
        @Override public int textureIndex() { return 0; }
        @Override public int drawOrder() { return 0; }
        @Override public int renderOrder() { return 0; }
        @Override public float getOpacity() { return 1f; }
        @Override public IntSequence masks() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexPositions() { throw new UnsupportedOperationException(); }
        @Override public FloatSequence vertexUvs() { throw new UnsupportedOperationException(); }
        @Override public IntSequence indices() { throw new UnsupportedOperationException(); }
        @Override public Color multiplyColor() { throw new UnsupportedOperationException(); }
        @Override public Color screenColor() { throw new UnsupportedOperationException(); }
        @Override public int parentPartIndex() { return -1; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { throw new UnsupportedOperationException(); }
        @Override public List<ParameterBinding> getParameterBindings() { return bindings; }

        @Override public MorphTargets morphTargets() {
            return new MorphTargets() {
                @Override public List<MorphTarget> all() { return morphTargets; }
                @Override public MorphTarget find(final ParameterId id) {
                    return morphTargets.stream()
                        .filter(target -> target.parameterId().equals(id)).findFirst()
                        .orElseThrow();
                }
            };
        }
    }

    private static final class FakeMorphTarget implements MorphTarget {
        final List<ParameterId> setParameterCalls = new ArrayList<>();
        final List<ParameterId> setParameterAndKeyValueCalls = new ArrayList<>();
        final List<Float> setParameterAndKeyValueValues = new ArrayList<>();
        private ParameterId id;
        private float keyValue = 0.5f;

        FakeMorphTarget(final String id) { this.id = new ParameterId(id); }

        @Override public ParameterId parameterId() { return id; }
        @Override public float keyValue() { return keyValue; }
        @Override public void setParameter(final ParameterId id) {
            setParameterCalls.add(id);
            this.id = id;
        }

        @Override public void setParameterAndKeyValue(final ParameterId id, final float value) {
            setParameterAndKeyValueCalls.add(id);
            setParameterAndKeyValueValues.add(value);
            this.id = id;
            this.keyValue = value;
        }
    }

    private static final class FakeDeformer implements Deformer {
        final List<ParameterBinding> bindings = new ArrayList<>();
        private final DeformerId id;

        FakeDeformer(final String id) { this.id = new DeformerId(id); }

        @Override public DeformerId id() { return id; }
        @Override public int parentDeformerIndex() { return -1; }
        @Override public IntSequence parameters() { throw new UnsupportedOperationException(); }
        @Override public List<ParameterBinding> getParameterBindings() { return bindings; }
    }

    private static final class FakeParameter implements Parameter {
        final List<ParameterBinding> bindings = new ArrayList<>();
        private final ParameterId id;
        private final String name;
        private final ParameterType type;
        private final boolean combined;
        private final float minimum;
        private final float maximum;

        FakeParameter(final String id, final String name, final ParameterType type, final boolean combined) {
            this(id, name, type, combined, -1f, 1f);
        }

        FakeParameter(
            final String id,
            final String name,
            final ParameterType type,
            final boolean combined,
            final float minimum,
            final float maximum
        ) {
            this.id = new ParameterId(id);
            this.name = name;
            this.type = type;
            this.combined = combined;
            this.minimum = minimum;
            this.maximum = maximum;
        }

        @Override public ParameterId id() { return id; }
        @Override public Optional<String> name() { return Optional.ofNullable(name); }
        @Override public ParameterType type() { return type; }
        @Override public Optional<Boolean> combined() { return Optional.of(combined); }
        @Override public List<ParameterBinding> getParameterBindings() { return bindings; }
        @Override public float getValue() { return 0f; }
        @Override public float getMinimumValue() { return minimum; }
        @Override public float getMaximumValue() { return maximum; }
        @Override public float getDefaultValue() { return 0f; }
        @Override public void setValue(final float value) { }
    }

    private static final class RecordingLogger implements PluginLogger {
        String errorMessage;
        Throwable errorThrowable;

        @Override public void debug(final String message) { }
        @Override public void info(final String message) { }
        @Override public void warn(final String message) { }
        @Override public void error(final String message) { }
        @Override public void error(final String message, final Throwable throwable) {
            errorMessage = message;
            errorThrowable = throwable;
        }
    }

    private static final class FakeBatch implements ParameterBindingBatchOperations {
        final List<ParameterBindingTransferPlan> clampedPlans = new ArrayList<>();
        final List<ParameterBindingTransferPlan> morphPlans = new ArrayList<>();
        final Set<ParameterId> failingSources = new java.util.HashSet<>();

        @Override public void invert(final List<ParameterBindingTarget> targets) { }

        @Override public void transfer(final ParameterBindingTransferPlan plan) {
            throw new AssertionError("ordinary transfer must not be used");
        }

        @Override public void transferClamped(final ParameterBindingTransferPlan plan) {
            record(plan);
        }

        @Override public void transferMorphClamped(final ParameterBindingTransferPlan plan) {
            if (failingSources.contains(plan.sourceParameterId())) {
                throw new IllegalStateException("transfer failed for " + plan.sourceParameterId());
            }
            morphPlans.add(plan);
        }

        private void record(final ParameterBindingTransferPlan plan) {
            if (failingSources.contains(plan.sourceParameterId())) {
                throw new IllegalStateException("transfer failed for " + plan.sourceParameterId());
            }
            clampedPlans.add(plan);
        }
    }
}
