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
        if (plan.controls().isEmpty()) return;
        var parameters = new LinkedHashMap<String, ParameterId>();
        var definitions = new ArrayList<ParameterDefinition>();
        for (int i = 0; i < plan.controls().size(); i++) {
            var source = plan.controls().get(i);
            var id = new ParameterId(prefix + i);
            if (model.parameters().all().stream().anyMatch(p -> p.id().equals(id)))
                throw new IllegalArgumentException("source control parameter conflict");
            parameters.put(source.id(), id);
            definitions.add(new ParameterDefinition(
                    id, source.name(), source.minimum(), 0, source.maximum(), ParameterType.BLEND_SHAPE, false));
        }
        model.parameters().createMany(definitions, parameterGroup);
        var opacityParents = new HashMap<DeformerId, List<ViewerScene.OpacityControl>>();
        var endpoints = new HashMap<DeformerId, DeformerId>();
        for (var meshId : created.meshIds()) {
            var mesh = model.drawables().find(meshId);
            var source = scene.meshes().get(created.meshToSourceIndex().get(meshId));
            if (!source.visible() || source.opacityControls().isEmpty()) continue;
            var original = mesh.parentDeformerId().orElseThrow();
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
                                        model.parts().find(created.rootPartId()),
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
                    model.warpDeformers()
                            .find(warpId)
                            .createOpacityMorphKeyform(
                                    Objects.requireNonNull(parameters.get(factor.controlId()), "opacity parameter"),
                                    1,
                                    factor.target());
                    parent = warpId;
                }
                endpoints.put(original, parent);
            }
            if (!original.equals(endpoints.get(original))) {
                var before = mesh.authoringSnapshot();
                mesh.setTargetDeformer(Optional.of(endpoints.get(original)));
                var after = mesh.authoringSnapshot();
                requireSameLayout(before, after);
                for (int i = 0; i < before.forms().size(); i++) {
                    var expected = before.forms().get(i).positions();
                    var actual = after.forms().get(i).positions();
                    if (expected.size() != actual.size())
                        throw new IllegalStateException("opacity parent changed XYZ slot count");
                    for (int j = 0; j < expected.size(); j++)
                        if (Math.hypot(
                                        actual.get(j).x() - expected.get(j).x(),
                                        actual.get(j).y() - expected.get(j).y())
                                > 1e-5)
                            throw new IllegalStateException(
                                    "identity opacity parent coordinate conversion exceeds tolerance");
                }
            }
        }
        int completedPatches = 0;
        for (var patch : plan.patches()) {
            var mesh = model.drawables().find(created.meshIds().get(patch.partition()));
            var before = mesh.authoringSnapshot();
            var parent = mesh.parentDeformerId().orElseThrow();
            var warp = model.deformers()
                    .createWarp(
                            mesh.name() + " 局部动作",
                            model.parts().find(created.rootPartId()),
                            -1,
                            patch.cells(),
                            patch.cells());
            var warpId = warp.id();
            warp.setTargetDeformer(Optional.of(parent));
            warp = model.warpDeformers().find(warpId);
            var base = mapControls(NativeControlWarpPlan.identity(patch.cells()).controlPoints(), patch);
            warp.replaceGrid(new WarpGrid(patch.cells(), patch.cells(), true, base));
            mesh.setTargetDeformer(Optional.of(warp.id()));
            // Cubism's reparent handler eagerly converts every stored position.
            // Retain the layout, then use its fresh snapshot as the write precondition.
            var reparented = mesh.authoringSnapshot();
            requireSameLayout(before, reparented);
            var edits = new ArrayList<ArtMeshKeyformPositions>();
            for (var cell : before.cells()) {
                // The native reparent already converts the protected default form.
                // Ordinary batch edits must exclude that form; verify it below instead.
                boolean defaultCell = cell.coordinates().stream()
                        .allMatch(c -> before.axes().stream()
                                .filter(axis -> axis.parameterGuid().equals(c.parameterGuid()))
                                .allMatch(axis -> axis.defaultValue() == c.value()));
                if (defaultCell) continue;
                var form = before.forms().stream()
                        .filter(f -> f.formGuid().equals(cell.formGuid()))
                        .findFirst()
                        .orElseThrow();
                var local = form.positions().stream()
                        .map(v -> new Point2(
                                (v.x() - patch.origin().x()) / patch.span().x(),
                                (v.y() - patch.origin().y()) / patch.span().y()))
                        .toList();
                edits.add(new ArtMeshKeyformPositions(cell.coordinates(), cell.formGuid(), local));
            }
            mesh.setOrdinaryKeyformPositions(reparented, edits);
            var after = mesh.authoringSnapshot();
            if (!after.sourceGeometry().equals(before.sourceGeometry())
                    || !after.axes().equals(before.axes())
                    || !after.cells().equals(before.cells())
                    || !after.morphKeys().equals(before.morphKeys()))
                throw new IllegalStateException("local control rebase changed topology or bindings");
            for (int i = 0; i < before.forms().size(); i++) {
                var expected = before.forms().get(i);
                var actual = after.forms().get(i);
                if (!expected.formGuid().equals(actual.formGuid()) || expected.drawOrder() != actual.drawOrder())
                    throw new IllegalStateException("local control rebase changed form membership");
                var restored = mapControls(actual.positions(), patch);
                for (int j = 0; j < restored.size(); j++)
                    if (Math.hypot(
                                    restored.get(j).x()
                                            - expected.positions().get(j).x(),
                                    restored.get(j).y()
                                            - expected.positions().get(j).y())
                            > 1e-5)
                        throw new IllegalStateException("local control coordinate rebase exceeds tolerance");
            }
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
