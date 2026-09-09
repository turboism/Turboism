package dev.turboism.plugin.protectedexport;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Drawables;
import dev.turboism.sdk.cubism.model.Glue;
import dev.turboism.sdk.cubism.model.Glues;
import dev.turboism.sdk.cubism.model.InstanceRenderType;
import dev.turboism.sdk.cubism.model.ModelInstance;
import dev.turboism.sdk.cubism.model.MorphTarget;
import dev.turboism.sdk.cubism.model.MorphTargets;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingFamily;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Parts;
import dev.turboism.sdk.cubism.model.Deformers;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtectedExportPlannerTest {
    @Test
    void snapshotsReadOnlyEvidenceAndCarriesUnresolvedCondition() {
        final Fixture fixture = validFixture();

        final ProtectedExportPlan plan = new ProtectedExportPlanner().plan(fixture.model);

        assertEquals(
            List.of("leaf", "child", "root"),
            plan.deformerOrder().stream().map(DeformerId::value).toList()
        );
        assertEquals(
            List.of("mesh-a", "mesh-b"),
            plan.artMeshOrder().stream().map(ArtMeshId::value).toList()
        );
        assertEquals(List.of("root", "child"), plan.partIds().stream().map(PartId::value).toList());
        assertEquals(List.of("ParamAngle"), plan.parameterIds().stream().map(ParameterId::value).toList());
        assertEquals(
            List.of(
                new ProtectedExportPlan.PartSnapshot(
                    new PartId("root"), "Root", Optional.empty(), List.of(new PartId("child")), 0.75f
                ),
                new ProtectedExportPlan.PartSnapshot(
                    new PartId("child"), "Child", Optional.of(new PartId("root")), List.of(), 0.5f
                )
            ),
            plan.partSnapshots()
        );
        assertEquals(
            Set.of(ProtectedExportPlan.UnresolvedCondition.EXTENDED_INTERPOLATION_UNVERIFIED),
            plan.unresolvedConditions()
        );
        assertTrue(plan.artMeshTargets().values().stream().allMatch(target ->
            target.name().matches("ArtMesh_[0-9a-f]{16,62}")
                && target.idToken().matches("[@_a-zA-Z][0-9a-zA-Z_@]*")
                && target.idToken().length() < 64
        ));
        assertTrue(plan.artMeshTargets().values().stream()
            .allMatch(target -> target.idToken().getClass() == String.class));
        assertThrows(UnsupportedOperationException.class, () -> plan.partSnapshots().add(null));
        assertThrows(
            UnsupportedOperationException.class,
            () -> plan.artMeshTargets().put(new ArtMeshId("other"),
                new ProtectedExportPlan.ArtMeshTarget("ArtMesh_other", "@other"))
        );
        assertEquals(0, fixture.mutations.mutatorCalls.get(), "preflight must only read snapshots");
        assertEquals(0, fixture.model.updateCalls.get(), "preflight must never update the model");
    }

    @Test
    void inputIterationOrderDoesNotChangePlanOrGuidMapping() {
        final Fixture first = validFixture();
        final Fixture second = validFixture();
        second.model.parts = List.of(second.childPart.value(), second.root.value());
        second.model.parameters = List.of(second.parameter.value());
        second.model.deformers = List.of(
            second.leaf.value(), second.rootDeformer.value(), second.childDeformer.value()
        );
        second.model.drawables = List.of(second.secondaryDrawable.value(), second.primaryDrawable.value());

        final ProtectedExportPlan firstPlan = new ProtectedExportPlanner().plan(first.model);
        final ProtectedExportPlan secondPlan = new ProtectedExportPlanner().plan(second.model);

        assertEquals(firstPlan, secondPlan);
    }

    @Test
    void hashesOnlyTrueDrawableGuids() {
        final Fixture fixture = validFixture();
        final List<String> hashedInputs = new ArrayList<>();

        new ProtectedExportPlanner(guid -> {
            hashedInputs.add(guid);
            return guid.equals("guid-a") ? fullHash('a') : fullHash('b');
        }).plan(fixture.model);

        assertEquals(List.of("guid-a", "guid-b"), hashedInputs);
    }

    @Test
    void acceptsAConsistentNormalBindingWithoutWriting() {
        final Fixture fixture = validFixture();
        final ParameterBinding binding = new ParameterBinding(
            ParameterBindingTarget.warpDeformer(new DeformerId("root")),
            new ParameterId("ParamAngle"),
            ParameterBindingFamily.KEYFORM_GRID,
            List.of()
        );
        fixture.rootDeformer.answers().put("getParameterBindings", List.of(binding));
        fixture.parameter.answers().put("getParameterBindings", List.of(binding));

        final ProtectedExportPlan plan = new ProtectedExportPlanner().plan(fixture.model);

        assertEquals(List.of(new DeformerId("leaf"), new DeformerId("child"), new DeformerId("root")),
            plan.deformerOrder());
        assertEquals(0, fixture.mutations.mutatorCalls.get());
    }

    @Test
    void rejectsPartAndDrawableMorphTargetsIncludingReadFailure() {
        final Fixture partTargets = validFixture();
        partTargets.root.answers().put("morphTargets", morphTargets(List.of(proxy(
            MorphTarget.class, Map.of(), partTargets.mutations
        ).value())));
        assertRejected(partTargets.model);

        final Fixture drawableTargets = validFixture();
        drawableTargets.primaryDrawable.answers().put("morphTargets", morphTargets(List.of(proxy(
            MorphTarget.class, Map.of(), drawableTargets.mutations
        ).value())));
        assertRejected(drawableTargets.model);

        final Fixture readFailure = validFixture();
        readFailure.primaryDrawable.answers().put(
            "morphTargets", morphTargetsFailure(new IllegalStateException("morph target read failed"))
        );
        assertRejected(readFailure.model);
    }

    @Test
    void rejectsBlankExceptionAndDuplicateGuids() {
        final Fixture blank = validFixture();
        blank.primaryDrawable.answers().put("guid", " ");
        assertRejected(blank.model);

        final Fixture exception = validFixture();
        exception.primaryDrawable.answers().put("guid", new IllegalStateException("guid unavailable"));
        assertRejected(exception.model);

        final Fixture duplicate = validFixture();
        duplicate.secondaryDrawable.answers().put("guid", "guid-b");
        assertRejected(duplicate.model);
    }

    @Test
    void extendsShortHashPrefixesAndFailsClosedOnCompleteCollision() {
        final Fixture distinct = validFixture();
        final String common = "0123456789abcdef";
        final ProtectedExportPlan plan = new ProtectedExportPlanner(guid ->
            guid.equals("guid-a")
                ? common + "0" + "0".repeat(47)
                : common + "1" + "0".repeat(47)
        ).plan(distinct.model);

        final List<ProtectedExportPlan.ArtMeshTarget> targets = new ArrayList<>(
            plan.artMeshTargets().values()
        );
        assertEquals("ArtMesh_0123456789abcdef", targets.get(0).name());
        assertEquals("ArtMesh_0123456789abcdef1", targets.get(1).name());
        assertEquals("@0123456789abcdef", targets.get(0).idToken());
        assertEquals("@0123456789abcdef1", targets.get(1).idToken());

        final Fixture completeCollision = validFixture();
        assertRejectedWith(
            completeCollision.model,
            new ProtectedExportPlanner(guid -> common + "0".repeat(48))
        );
    }

    @Test
    void rejectsTargetIdSourceConflictAndMalformedHashes() {
        final Fixture sourceConflict = validFixture();
        final PartId reserved = new PartId("@0123456789abcdef");
        sourceConflict.root.answers().put("id", reserved);
        sourceConflict.childPart.answers().put("parentId", Optional.of(reserved));
        sourceConflict.secondaryDrawable.answers().put("parentPartId", Optional.of(reserved));
        final ProtectedExportPlan plan = new ProtectedExportPlanner(guid ->
            guid.equals("guid-a")
                ? "0123456789abcdef" + "0".repeat(48)
                : fullHash('b')
        ).plan(sourceConflict.model);
        assertEquals("@0123456789abcdef0", plan.artMeshTargets().get(new ArtMeshId("mesh-a")).idToken());

        final Fixture malformed = validFixture();
        assertRejectedWith(malformed.model, new ProtectedExportPlanner(guid -> "not-a-hash"));
        assertThrows(
            IllegalArgumentException.class,
            () -> new ProtectedExportPlan.ArtMeshTarget("name", "1starts-with-digit")
        );
    }

    @Test
    void rejectsGlueNonNormalInstancesUnknownParametersAndUnsupportedFamilies() {
        final Fixture glue = validFixture();
        glue.model.glues = List.of(proxy(Glue.class, Map.of(), glue.mutations).value());
        assertRejected(glue.model);

        final Fixture instance = validFixture();
        instance.model.instances = List.of(() -> InstanceRenderType.ART_PATH);
        assertRejected(instance.model);

        final Fixture unknownParameter = validFixture();
        unknownParameter.parameter.answers().put("type", ParameterType.UNKNOWN);
        assertRejected(unknownParameter.model);

        final Fixture combined = validFixture();
        combined.parameter.answers().put("combined", Optional.of(true));
        assertRejected(combined.model);

        final Fixture unsupportedDeformer = validFixture();
        unsupportedDeformer.model.deformers = List.of(proxy(
            Deformer.class,
            answers(
                "id", new DeformerId("plain"),
                "parentPartId", Optional.empty(),
                "parentDeformerId", Optional.empty(),
                "getParameterBindings", List.of()
            ),
            unsupportedDeformer.mutations
        ).value());
        assertRejected(unsupportedDeformer.model);

        final Fixture unsupportedBinding = validFixture();
        final ParameterBinding blendShape = new ParameterBinding(
            ParameterBindingTarget.warpDeformer(new DeformerId("root")),
            new ParameterId("ParamAngle"),
            ParameterBindingFamily.BLEND_SHAPE,
            List.of()
        );
        unsupportedBinding.rootDeformer.answers().put("getParameterBindings", List.of(blendShape));
        unsupportedBinding.parameter.answers().put("getParameterBindings", List.of(blendShape));
        assertRejected(unsupportedBinding.model);
    }

    @Test
    void rejectsBadGraphsDuplicateIdsAndInconsistentRelations() {
        final Fixture missingPartParent = validFixture();
        missingPartParent.childPart.answers().put(
            "parentId", Optional.of(new PartId("missing"))
        );
        assertRejected(missingPartParent.model);

        final Fixture missingDeformerParent = validFixture();
        missingDeformerParent.childDeformer.answers().put(
            "parentDeformerId", Optional.of(new DeformerId("missing"))
        );
        assertRejected(missingDeformerParent.model);

        final Fixture deformerCycle = validFixture();
        deformerCycle.rootDeformer.answers().put(
            "parentDeformerId", Optional.of(new DeformerId("leaf"))
        );
        assertRejected(deformerCycle.model);

        final Fixture partCycle = validFixture();
        partCycle.root.answers().put("parentId", Optional.of(new PartId("child")));
        partCycle.childPart.answers().put("childIds", List.of(new PartId("root")));
        assertRejected(partCycle.model);

        final Fixture asymmetric = validFixture();
        asymmetric.root.answers().put("childIds", List.of());
        assertRejected(asymmetric.model);

        final Fixture duplicatePart = validFixture();
        duplicatePart.model.parts = List.of(duplicatePart.root.value(), duplicatePart.root.value());
        assertRejected(duplicatePart.model);

        final Fixture duplicateParameter = validFixture();
        duplicateParameter.model.parameters = List.of(
            duplicateParameter.parameter.value(), duplicateParameter.parameter.value()
        );
        assertRejected(duplicateParameter.model);

        final Fixture duplicateDrawable = validFixture();
        duplicateDrawable.model.drawables = List.of(
            duplicateDrawable.primaryDrawable.value(), duplicateDrawable.primaryDrawable.value()
        );
        assertRejected(duplicateDrawable.model);
    }

    @Test
    void rejectsUnknownOrInconsistentBindingSnapshots() {
        final Fixture missingParameter = validFixture();
        final ParameterBinding missing = new ParameterBinding(
            ParameterBindingTarget.warpDeformer(new DeformerId("root")),
            new ParameterId("missing"),
            ParameterBindingFamily.KEYFORM_GRID,
            List.of()
        );
        missingParameter.rootDeformer.answers().put("getParameterBindings", List.of(missing));
        missingParameter.parameter.answers().put("getParameterBindings", List.of(missing));
        assertRejected(missingParameter.model);

        final Fixture wrongOwner = validFixture();
        final ParameterBinding wrongTarget = new ParameterBinding(
            ParameterBindingTarget.rotationDeformer(new DeformerId("child")),
            new ParameterId("ParamAngle"),
            ParameterBindingFamily.KEYFORM_GRID,
            List.of()
        );
        wrongOwner.rootDeformer.answers().put("getParameterBindings", List.of(wrongTarget));
        wrongOwner.parameter.answers().put("getParameterBindings", List.of(wrongTarget));
        assertRejected(wrongOwner.model);

        final Fixture inconsistent = validFixture();
        final ParameterBinding onlyOnOwner = new ParameterBinding(
            ParameterBindingTarget.warpDeformer(new DeformerId("root")),
            new ParameterId("ParamAngle"),
            ParameterBindingFamily.KEYFORM_GRID,
            List.of()
        );
        inconsistent.rootDeformer.answers().put("getParameterBindings", List.of(onlyOnOwner));
        assertRejected(inconsistent.model);
    }

    @Test
    void planUsesPrivateStringTargetTokensAndNeverHostArtMeshIds() {
        final ProtectedExportPlan plan = new ProtectedExportPlanner().plan(validFixture().model);

        for (ProtectedExportPlan.ArtMeshTarget target : plan.artMeshTargets().values()) {
            assertEquals(String.class, target.idToken().getClass());
            assertTrue(target.idToken().startsWith("@"));
            assertTrue(target.idToken().length() < 64);
        }
    }

    private static void assertRejected(final CubismModel model) {
        assertThrows(IllegalArgumentException.class, () -> new ProtectedExportPlanner().plan(model));
    }

    private static void assertRejectedWith(
        final CubismModel model,
        final ProtectedExportPlanner planner
    ) {
        assertThrows(IllegalArgumentException.class, () -> planner.plan(model));
    }

    private static String fullHash(final char first) {
        return first + "0".repeat(63);
    }

    private static MorphTargets morphTargets(final List<MorphTarget> values) {
        return morphTargetsFailure(values);
    }

    private static MorphTargets morphTargetsFailure(final Object answer) {
        return (MorphTargets) Proxy.newProxyInstance(
            MorphTargets.class.getClassLoader(),
            new Class<?>[]{MorphTargets.class},
            (proxy, method, arguments) -> {
                if (method.getName().equals("all")) {
                    if (answer instanceof Throwable failure) {
                        throw failure;
                    }
                    return List.copyOf((List<?>) answer);
                }
                if (method.getName().equals("toString")) return "MorphTargets";
                throw new UnsupportedOperationException(method.getName());
            }
        );
    }

    private static Fixture validFixture() {
        final Mutations mutations = new Mutations();
        final ProxyValue<Part> root = proxy(Part.class, answers(
            "id", new PartId("root"),
            "name", "Root",
            "parentId", Optional.empty(),
            "childIds", List.of(new PartId("child")),
            "getOpacity", 0.75f,
            "morphTargets", morphTargets(List.of())
        ), mutations);
        final ProxyValue<Part> childPart = proxy(Part.class, answers(
            "id", new PartId("child"),
            "name", "Child",
            "parentId", Optional.of(new PartId("root")),
            "childIds", List.of(),
            "getOpacity", 0.5f,
            "morphTargets", morphTargets(List.of())
        ), mutations);
        final ProxyValue<Parameter> parameter = proxy(Parameter.class, answers(
            "id", new ParameterId("ParamAngle"),
            "type", ParameterType.NORMAL,
            "combined", Optional.of(false),
            "combinedWith", Optional.empty(),
            "getParameterBindings", List.of()
        ), mutations);
        final ProxyValue<WarpDeformer> rootDeformer = proxy(WarpDeformer.class, answers(
            "id", new DeformerId("root"),
            "parentPartId", Optional.empty(),
            "parentDeformerId", Optional.empty(),
            "getParameterBindings", List.of()
        ), mutations);
        final ProxyValue<RotationDeformer> childDeformer = proxy(RotationDeformer.class, answers(
            "id", new DeformerId("child"),
            "parentPartId", Optional.empty(),
            "parentDeformerId", Optional.of(new DeformerId("root")),
            "getParameterBindings", List.of()
        ), mutations);
        final ProxyValue<WarpDeformer> leaf = proxy(WarpDeformer.class, answers(
            "id", new DeformerId("leaf"),
            "parentPartId", Optional.empty(),
            "parentDeformerId", Optional.of(new DeformerId("child")),
            "getParameterBindings", List.of()
        ), mutations);
        final ProxyValue<Drawable> primaryDrawable = proxy(Drawable.class, answers(
            "id", new ArtMeshId("mesh-b"),
            "guid", "guid-b",
            "parentPartId", Optional.empty(),
            "parentDeformerId", Optional.of(new DeformerId("leaf")),
            "getParameterBindings", List.of(),
            "morphTargets", morphTargets(List.of())
        ), mutations);
        final ProxyValue<Drawable> secondaryDrawable = proxy(Drawable.class, answers(
            "id", new ArtMeshId("mesh-a"),
            "guid", "guid-a",
            "parentPartId", Optional.of(new PartId("root")),
            "parentDeformerId", Optional.empty(),
            "getParameterBindings", List.of(),
            "morphTargets", morphTargets(List.of())
        ), mutations);
        final FakeModel model = new FakeModel();
        model.parameters = List.of(parameter.value());
        model.parts = List.of(root.value(), childPart.value());
        model.deformers = List.of(rootDeformer.value(), childDeformer.value(), leaf.value());
        model.drawables = List.of(primaryDrawable.value(), secondaryDrawable.value());
        model.instances = List.of(() -> InstanceRenderType.NORMAL);
        return new Fixture(
            model, mutations, root, childPart, parameter, rootDeformer, childDeformer, leaf,
            primaryDrawable, secondaryDrawable
        );
    }

    private static Map<String, Object> answers(final Object... values) {
        final Map<String, Object> result = new HashMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.put((String) values[index], values[index + 1]);
        }
        return result;
    }

    private static <T> ProxyValue<T> proxy(
        final Class<T> type,
        final Map<String, Object> answers,
        final Mutations mutations
    ) {
        final InvocationHandler handler = (proxy, method, arguments) -> invoke(
            proxy, method, arguments, answers, mutations
        );
        return new ProxyValue<>(type.cast(Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type}, handler
        )), answers);
    }

    private static Object invoke(
        final Object proxy,
        final Method method,
        final Object[] arguments,
        final Map<String, Object> answers,
        final Mutations mutations
    ) throws Throwable {
        final String name = method.getName();
        if (name.equals("toString")) return "read-only test proxy";
        if (name.equals("hashCode")) return System.identityHashCode(proxy);
        if (name.equals("equals")) return proxy == arguments(arguments)[0];
        if (name.startsWith("set") || name.startsWith("replace") || name.equals("update")
            || name.equals("remove") || name.equals("create") || name.equals("add")
            || name.equals("combine") || name.equals("uncombine")) {
            mutations.mutatorCalls.incrementAndGet();
            throw new AssertionError("planner invoked mutator " + name);
        }
        if (!answers.containsKey(name)) {
            throw new UnsupportedOperationException("unavailable read " + name);
        }
        final Object value = answers.get(name);
        if (value instanceof Throwable failure) {
            throw failure;
        }
        return value;
    }

    private static Object[] arguments(final Object[] values) {
        return values == null ? new Object[0] : values;
    }

    private static <T> T collection(final Class<T> type, final Supplier<List<?>> values) {
        return type.cast(Proxy.newProxyInstance(
            type.getClassLoader(), new Class<?>[]{type}, (proxy, method, arguments) -> {
                if (method.getName().equals("all")) return List.copyOf(values.get());
                if (method.getName().equals("toString")) return type.getSimpleName();
                throw new UnsupportedOperationException(method.getName());
            }
        ));
    }

    private static final class FakeModel implements CubismModel {
        private List<Parameter> parameters = List.of();
        private List<Part> parts = List.of();
        private List<Deformer> deformers = List.of();
        private List<Drawable> drawables = List.of();
        private List<Glue> glues = List.of();
        private List<ModelInstance> instances = List.of();
        private final AtomicInteger updateCalls = new AtomicInteger();

        @Override public ModelId id() { return new ModelId("model"); }
        @Override public Parameters parameters() {
            return collection(Parameters.class, () -> parameters);
        }
        @Override public Parts parts() {
            return collection(Parts.class, () -> parts);
        }
        @Override public Deformers deformers() {
            return collection(Deformers.class, () -> deformers);
        }
        @Override public Drawables drawables() {
            return collection(Drawables.class, () -> drawables);
        }
        @Override public Glues glues() {
            return collection(Glues.class, () -> glues);
        }
        @Override public List<ModelInstance> modelInstances() { return List.copyOf(instances); }
        @Override public void update() {
            updateCalls.incrementAndGet();
            throw new AssertionError("planner invoked model update");
        }
    }

    private static final class Mutations {
        private final AtomicInteger mutatorCalls = new AtomicInteger();
    }

    private record ProxyValue<T>(T value, Map<String, Object> answers) {
    }

    private record Fixture(
        FakeModel model,
        Mutations mutations,
        ProxyValue<Part> root,
        ProxyValue<Part> childPart,
        ProxyValue<Parameter> parameter,
        ProxyValue<WarpDeformer> rootDeformer,
        ProxyValue<RotationDeformer> childDeformer,
        ProxyValue<WarpDeformer> leaf,
        ProxyValue<Drawable> primaryDrawable,
        ProxyValue<Drawable> secondaryDrawable
    ) {
    }
}
