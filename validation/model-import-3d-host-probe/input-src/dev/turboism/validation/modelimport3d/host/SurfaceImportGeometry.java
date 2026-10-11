package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.AuthorizedImageContent;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;

/** SDK consumer for decoded patches. Caller owns document recovery and the bake transaction.
 * This stage does not set materials, visibility/order, or claim whole-import atomicity. */
final class SurfaceImportGeometry {
    private SurfaceImportGeometry() { }

    /** Original GLB UVs remain in BoundPatch.source. Generated flat-color tiles
     * use an invertible interior transform to avoid transparent texture-border
     * filtering. This mode is not a texture-image resampling policy. */
    enum UvMode {
        SOURCE, IMAGE_PIXELS, EDGE_PADDED_IMAGE_PIXELS, FLAT_COLOR_INTERIOR;
        List<Point2> map(List<Point2> source) {
            if(this==EDGE_PADDED_IMAGE_PIXELS) return source.stream()
                .map(p->new Point2((1+256*p.x())/512,(1+128*p.y())/256)).toList();
            return this!=FLAT_COLOR_INTERIOR ? List.copyOf(source)
                : source.stream().map(p->new Point2(.25f+.5f*p.x(),.25f+.5f*p.y())).toList();
        }
    }

    record BoundPatch(SurfacePatchPlan.Patch source, ArtMeshId meshId, SurfaceKeyformBake.Plan bake) { }
    record Created(List<BoundPatch> patches) {
        Created { patches = List.copyOf(patches); }

        /** Run in the caller's authoring transaction; one bulk contribution per mesh. */
        void bake(CubismModel model) {
            for (var patch : patches) patch.bake().write(model.drawables().find(patch.meshId()));
            for (var patch : patches) patch.bake().verify(model.drawables().find(patch.meshId()));
        }
    }

    static Created create(CubismModel model, SurfaceImportPlan.Plan plan, ParameterId yaw, ParameterId pitch,
            Part parent, String name) {
        return create(model,plan,yaw,pitch,parent,name,UvMode.SOURCE);
    }

    static Created create(CubismModel model, SurfaceImportPlan.Plan plan, ParameterId yaw, ParameterId pitch,
            Part parent, String name, UvMode uvMode) {
        java.util.Objects.requireNonNull(uvMode,"uvMode");
        return create(model,plan,yaw,pitch,parent,name,java.util.Collections.nCopies(plan.patches().size(),uvMode));
    }

    static Created create(CubismModel model, SurfaceImportPlan.Plan plan, ParameterId yaw, ParameterId pitch,
            Part parent, String name, List<UvMode> uvModes) {
        uvModes=List.copyOf(uvModes);
        if(uvModes.size()!=plan.patches().size())throw new IllegalArgumentException("UV mode/patch count differs");
        if (name == null || name.isBlank() || yaw.equals(pitch) || plan.patches().isEmpty()) {
            throw new IllegalArgumentException("invalid import geometry identity/axes");
        }
        var ys = SurfaceKeyformBake.bindingKeys(plan.grid().yawKeys());
        var ps = SurfaceKeyformBake.bindingKeys(plan.grid().pitchKeys());
        validateAxis(model.parameters().find(yaw), ys);
        validateAxis(model.parameters().find(pitch), ps);
        // Generated model images use an identity image-to-canvas transform. Their source
        // mesh therefore lives in image pixels, even when the visible neutral face is edge-on.
        // Geometry-only probes retain their original source domain; no image is bound there.
        var neutral = plan.patches().stream().map(p -> SurfaceProjection.project(
            p.source().positions(), plan.camera(), new SurfaceProjection.Pose(0,0)).positions()).toList();
        var geometries = new ArrayList<ArtMeshGeometry>();
        for (int i=0; i<plan.patches().size(); i++) {
            var patch=plan.patches().get(i).source();
            var uvMode=uvModes.get(i);
            var uvs=uvMode.map(patch.textureUvs());
            var source=uvMode==UvMode.SOURCE ? neutral.get(i) : uvs.stream().map(p -> new Point2(
                p.x()*(uvMode==UvMode.EDGE_PADDED_IMAGE_PIXELS ? 512 : AuthorizedImageContent.REQUIRED_WIDTH),
                p.y()*(uvMode==UvMode.EDGE_PADDED_IMAGE_PIXELS ? 256 : AuthorizedImageContent.REQUIRED_HEIGHT))).toList();
            geometries.add(new ArtMeshGeometry(source,uvs,patch.indices()));
        }
        var yawPoints = bindingPoints(ys, "YAW");
        var pitchPoints = bindingPoints(ps, "PITCH");
        var result = new ArrayList<BoundPatch>();
        for (int i=0; i<plan.patches().size(); i++) {
            var mesh = uvModes.get(i)==UvMode.SOURCE
                ? model.drawables().create(name + "_" + (i+1), parent, -1, geometries.get(i))
                : model.drawables().createWithSourceGeometry(name + "_" + (i+1), parent, -1,
                    geometries.get(i), neutral.get(i));
            model.parameterBindings(yaw).bind(ParameterBindingTarget.artMesh(mesh.id()), yawPoints);
            model.parameterBindings(pitch).bind(ParameterBindingTarget.artMesh(mesh.id()), pitchPoints);
            var patch = plan.patches().get(i).source();
            var bake = SurfaceKeyformBake.compile(mesh.authoringSnapshot(), yaw, pitch,
                patch.positions(), plan.camera(), plan.grid());
            result.add(new BoundPatch(patch, mesh.id(), bake));
        }
        return new Created(result);
    }

    private static List<ParameterBindingPoint> bindingPoints(List<Float> keys, String axis) {
        var result = new ArrayList<ParameterBindingPoint>();
        for (int i=0; i<keys.size(); i++) {
            result.add(new ParameterBindingPoint(new ParameterBindingPointId("MI3D_" + axis + "_" + i), keys.get(i)));
        }
        return List.copyOf(result);
    }

    private static void validateAxis(Parameter parameter, List<Float> keys) {
        if (keys.size() < 3 || parameter.type() != ParameterType.NORMAL
                || !parameter.repeat().equals(java.util.Optional.of(false))
                || parameter.getDefaultValue() != 0 || parameter.getValue() != 0
                || parameter.getMinimumValue() != keys.get(0) || parameter.getMaximumValue() != keys.get(keys.size()-1)) {
            throw new IllegalArgumentException("import requires neutral, existing axes with exact planned domains");
        }
    }
}
