package dev.turboism.validation.modelimport3d.host;

import dev.turboism.plugin.modelimport3d.viewer.ViewerScene;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Source control authoring uses native Warp blend shapes; no runtime pose evaluator is needed after save. */
final class NativeControlAuthoring {
    static void apply(
            CubismModel model,
            NativeCharacterAuthoring.Created created,
            ViewerScene scene,
            NativeControlWarpPlan.Plan plan) {
        apply(model, created, scene, plan, stage -> {});
    }

    static void apply(
            CubismModel model,
            NativeCharacterAuthoring.Created created,
            ViewerScene scene,
            NativeControlWarpPlan.Plan plan,
            java.util.function.Consumer<String> progress) {
        apply(
                model,
                created,
                scene,
                plan,
                progress,
                Optional.empty(),
                created.rootPartId().value() + "_Control_");
    }

    static void apply(
            CubismModel model,
            NativeCharacterAuthoring.Created created,
            ViewerScene scene,
            NativeControlWarpPlan.Plan plan,
            java.util.function.Consumer<String> progress,
            Optional<ParameterGroupId> parameterGroup,
            String prefix) {
        prepare(model, created, scene, plan, progress, parameterGroup, prefix).run();
    }

    /** Build every parent before materializing dense morph forms; finish inside the same root. */
    static Runnable prepare(
            CubismModel model,
            NativeCharacterAuthoring.Created created,
            ViewerScene scene,
            NativeControlWarpPlan.Plan plan,
            java.util.function.Consumer<String> progress,
            Optional<ParameterGroupId> parameterGroup,
            String prefix) {
        var hierarchy = new NativeCharacterAuthoring.Hierarchy(
                created.rootPartId(),
                created.meshIds().stream().map(created.meshToSourceIndex()::get).toList(),
                created.meshIds().stream()
                        .map(id -> model.drawables().find(id).parentDeformerId())
                        .toList());
        var prepared = prepareHierarchy(model, hierarchy, scene, plan, progress, parameterGroup, prefix);
        var patches = new HashMap<Integer, NativeControlWarpPlan.Patch>();
        for (var patch : plan.patches()) patches.put(patch.partition(), patch);
        for (int i = 0; i < created.meshIds().size(); i++) {
            var mesh = model.drawables().find(created.meshIds().get(i));
            if (mesh.parentDeformerId().equals(prepared.parents().get(i))) continue;
            var before = mesh.authoringSnapshot();
            mesh.setTargetDeformer(prepared.parents().get(i));
            var after = mesh.authoringSnapshot();
            requireSameLayout(before, after);
            var patch = patches.get(i);
            var edits = new ArrayList<ArtMeshKeyformPositions>();
            for (var cell : before.cells()) {
                boolean defaultCell = cell.coordinates().stream()
                        .allMatch(c -> before.axes().stream()
                                .filter(axis -> axis.parameterGuid().equals(c.parameterGuid()))
                                .allMatch(axis -> axis.defaultValue() == c.value()));
                if (defaultCell) continue;
                var form = before.forms().stream()
                        .filter(f -> f.formGuid().equals(cell.formGuid()))
                        .findFirst()
                        .orElseThrow();
                var points = patch == null ? form.positions() : local(form.positions(), patch);
                edits.add(new ArtMeshKeyformPositions(cell.coordinates(), cell.formGuid(), points));
            }
            if (!edits.isEmpty()) mesh.setOrdinaryKeyformPositions(after, edits);
            after = mesh.authoringSnapshot();
            requireSameLayout(before, after);
            for (int f = 0; f < before.forms().size(); f++) {
                var expected = before.forms().get(f);
                var actual = after.forms().get(f);
                if (!expected.formGuid().equals(actual.formGuid()) || expected.drawOrder() != actual.drawOrder())
                    throw new IllegalStateException("local control rebase changed form membership");
                var restored = patch == null ? actual.positions() : mapControls(actual.positions(), patch);
                requirePositions(expected.positions(), restored);
            }
        }
        return prepared.finish();
    }

    record PreparedHierarchy(List<Optional<DeformerId>> parents, Runnable finish) {
        PreparedHierarchy {
            parents = List.copyOf(parents);
            Objects.requireNonNull(finish);
        }
    }

    static PreparedHierarchy prepareHierarchy(
            CubismModel model,
            NativeCharacterAuthoring.Hierarchy hierarchy,
            ViewerScene scene,
            NativeControlWarpPlan.Plan plan,
            java.util.function.Consumer<String> progress,
            Optional<ParameterGroupId> parameterGroup,
            String prefix) {
        if (plan.controls().isEmpty()) return new PreparedHierarchy(hierarchy.parents(), () -> {});
        var parameters = new LinkedHashMap<String, ParameterId>();
        var definitions = new ArrayList<ParameterDefinition>();
        var existingIds = new HashSet<ParameterId>();
        for (var parameter : model.parameters().all()) existingIds.add(parameter.id());
        for (int i = 0; i < plan.controls().size(); i++) {
            var source = plan.controls().get(i);
            var id = new ParameterId(prefix + i);
            if (!existingIds.add(id)) throw new IllegalArgumentException("source control parameter conflict");
            parameters.put(source.id(), id);
            definitions.add(new ParameterDefinition(
                    id, source.name(), source.minimum(), 0, source.maximum(), ParameterType.BLEND_SHAPE, false));
        }
        model.parameters().createMany(definitions, parameterGroup);
        var opacityWrites = new ArrayList<Runnable>();
        var patchWarps = new LinkedHashMap<Integer, DeformerId>();
        var opacityParents = new HashMap<DeformerId, List<ViewerScene.OpacityControl>>();
        var endpoints = new HashMap<DeformerId, DeformerId>();
        var parents = new ArrayList<>(hierarchy.parents());
        for (int partition = 0; partition < hierarchy.sourceIndices().size(); partition++) {
            var source = scene.meshes().get(hierarchy.sourceIndices().get(partition));
            if (!source.visible() || source.opacityControls().isEmpty()) continue;
            var original = parents.get(partition).orElseThrow();
            var prior = opacityParents.putIfAbsent(original, source.opacityControls());
            if (prior != null && !prior.equals(source.opacityControls()))
                throw new IllegalArgumentException("shared outfit parent has conflicting opacity factors");
            if (prior == null) {
                DeformerId parent = original;
                for (int i = 0; i < source.opacityControls().size(); i++) {
                    var factor = source.opacityControls().get(i);
                    DeformerId warpId = parent;
                    if (i != 0) {
                        var warp = model.deformers()
                                .createWarp(
                                        source.appearanceGroup() + " / " + factor.controlId(),
                                        model.parts().find(hierarchy.rootPartId()),
                                        -1,
                                        2,
                                        2);
                        warpId = warp.id();
                        warp.setTargetDeformer(Optional.of(parent));
                        // Native parent conversion can rebuild the evaluated instance.
                        // Keep the stable ID and reacquire through the public model collection.
                        model.warpDeformers().find(warpId).replaceGrid(NativeControlWarpPlan.identity(2));
                    }
                    model.warpDeformers().find(warpId).setOpacity(factor.base());
                    DeformerId opacityId = warpId;
                    var opacityParameter =
                            Objects.requireNonNull(parameters.get(factor.controlId()), "opacity parameter");
                    opacityWrites.add(() -> model.warpDeformers()
                            .find(opacityId)
                            .createOpacityMorphKeyform(opacityParameter, 1, factor.target()));
                    parent = warpId;
                }
                endpoints.put(original, parent);
            }
            parents.set(partition, Optional.of(endpoints.get(original)));
        }
        int completedParents = 0;
        for (var patch : plan.patches()) {
            var parent = parents.get(patch.partition()).orElseThrow();
            var warp = model.deformers()
                    .createWarp(
                            "分区 " + patch.partition() + " 局部动作",
                            model.parts().find(hierarchy.rootPartId()),
                            -1,
                            patch.cells(),
                            patch.cells());
            var warpId = warp.id();
            warp.setTargetDeformer(Optional.of(parent));
            warp = model.warpDeformers().find(warpId);
            var base = mapControls(NativeControlWarpPlan.identity(patch.cells()).controlPoints(), patch);
            warp.replaceGrid(new WarpGrid(patch.cells(), patch.cells(), true, base));
            parents.set(patch.partition(), Optional.of(warpId));
            patchWarps.put(patch.partition(), warpId);
            if (++completedParents % 32 == 0)
                progress.accept("source-control-parents " + completedParents + "/"
                        + plan.patches().size());
        }
        progress.accept("source-control-parents " + completedParents + "/"
                + plan.patches().size());
        return new PreparedHierarchy(parents, () -> {
            for (var write : opacityWrites) write.run();
            int completedPatches = 0;
            for (var patch : plan.patches()) {
                var warp = model.warpDeformers().find(patchWarps.get(patch.partition()));
                for (var key : patch.keys())
                    warp.createMorphKeyform(
                            Objects.requireNonNull(parameters.get(key.parameter()), "shape parameter"),
                            key.value(),
                            mapControls(key.controls(), patch));
                var readback = warp.morphSnapshot();
                if (readback.keys().size() != patch.keys().size())
                    throw new IllegalStateException("source shape key membership differs");
                for (int i = 0; i < patch.keys().size(); i++) {
                    var expected = patch.keys().get(i);
                    var actual = readback.keys().get(i);
                    if (actual.keyValue() != expected.value()
                            || !actual.form().controlPoints().equals(mapControls(expected.controls(), patch)))
                        throw new IllegalStateException("source shape control readback differs");
                }
                if (++completedPatches % 32 == 0)
                    progress.accept("source-controls " + completedPatches + "/"
                            + plan.patches().size());
            }
            progress.accept(
                    "source-controls " + completedPatches + "/" + plan.patches().size());
            for (var definition : definitions) {
                var parameter = model.parameters().find(definition.id());
                if (parameter.type() != ParameterType.BLEND_SHAPE || parameter.getValue() != 0)
                    throw new IllegalStateException("source blend parameter default differs");
            }
        });
    }

    /** Convert body-parent coordinates into the already-created affine local Warp. */
    static java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationLocalizer(
            NativeControlWarpPlan.Plan plan) {
        var patches = new HashMap<Integer, NativeControlWarpPlan.Patch>();
        for (var patch : plan.patches()) {
            if (patches.putIfAbsent(patch.partition(), patch) != null)
                throw new IllegalArgumentException("duplicate local control partition");
        }
        return (partition, points) -> {
            var patch = patches.get(partition);
            if (patch == null) return points;
            return points.stream()
                    .map(v -> new Point2(
                            (v.x() - patch.origin().x()) / patch.span().x(),
                            (v.y() - patch.origin().y()) / patch.span().y()))
                    .toList();
        };
    }

    /** Restore affine local-control coordinates to body-parent units for canvas error checks. */
    static java.util.function.BiFunction<Integer, List<Point2>, List<Point2>> rotationRestorer(
            NativeControlWarpPlan.Plan plan) {
        var patches = new HashMap<Integer, NativeControlWarpPlan.Patch>();
        for (var patch : plan.patches()) {
            if (patches.putIfAbsent(patch.partition(), patch) != null)
                throw new IllegalArgumentException("duplicate local control partition");
        }
        return (partition, points) -> {
            var patch = patches.get(partition);
            return patch == null ? points : mapControls(points, patch);
        };
    }

    private static List<Point2> local(List<Point2> points, NativeControlWarpPlan.Patch patch) {
        return points.stream()
                .map(v -> new Point2(
                        (v.x() - patch.origin().x()) / patch.span().x(),
                        (v.y() - patch.origin().y()) / patch.span().y()))
                .toList();
    }

    private static void requirePositions(List<Point2> expected, List<Point2> actual) {
        if (expected.size() != actual.size()) throw new IllegalStateException("parent changed XYZ slot count");
        for (int j = 0; j < expected.size(); j++)
            if (Math.hypot(
                            actual.get(j).x() - expected.get(j).x(),
                            actual.get(j).y() - expected.get(j).y())
                    > 1e-5) throw new IllegalStateException("local control coordinate rebase exceeds tolerance");
    }

    private static void requireSameLayout(ArtMeshAuthoringSnapshot before, ArtMeshAuthoringSnapshot after) {
        if (!before.artMeshId().equals(after.artMeshId())
                || !before.sourceGuid().equals(after.sourceGuid())
                || !before.sourceGeometry().equals(after.sourceGeometry())
                || !before.axes().equals(after.axes())
                || !before.cells().equals(after.cells())
                || !before.morphKeys().equals(after.morphKeys())
                || before.forms().size() != after.forms().size())
            throw new IllegalStateException("local control parent changed topology or bindings");
        for (int i = 0; i < before.forms().size(); i++) {
            var expected = before.forms().get(i);
            var actual = after.forms().get(i);
            if (!expected.formGuid().equals(actual.formGuid()) || expected.drawOrder() != actual.drawOrder())
                throw new IllegalStateException("local control parent changed form membership or draw order");
        }
    }

    private static List<Point2> mapControls(List<Point2> points, NativeControlWarpPlan.Patch patch) {
        return points.stream()
                .map(v -> new Point2(
                        patch.origin().x() + v.x() * patch.span().x(),
                        patch.origin().y() + v.y() * patch.span().y()))
                .toList();
    }

    private NativeControlAuthoring() {}
}
