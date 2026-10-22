package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

/** SDK-only chart import assembly in one transaction. Prepared source charts/order
 * still require visibility admission; this class is not a concave-model menu gate. */
final class SurfaceWarpAuthoring {
    private SurfaceWarpAuthoring() {}

    enum Stage {
        IDENTITY,
        MATERIAL,
        ATTACHED,
        NEUTRAL,
        BOUND,
        BAKED
    }

    @FunctionalInterface
    interface Observer {
        void completed(Stage stage);

        default void parentRoundTrip(Created created) {}
    }

    static final class Prepared {
        final SurfaceImport.Request request;
        final SurfaceWarpChartPlan.Plan geometry;
        final SurfaceWarpImagePlan.Plan images;
        final SurfaceKeyPlan.Grid grid;
        final List<Float> yawKeys, pitchKeys;
        final double idealErrorBoundPixels, maximumErrorPixels;
        private final float[] xy;

        private Prepared(
                SurfaceImport.Request request,
                SurfaceWarpChartPlan.Plan geometry,
                SurfaceWarpImagePlan.Plan images,
                SurfaceKeyPlan.Grid grid,
                List<Float> yawKeys,
                List<Float> pitchKeys,
                double error,
                double maximumErrorPixels,
                float[] xy) {
            this.request = request;
            this.geometry = geometry;
            this.images = images;
            this.grid = grid;
            this.yawKeys = yawKeys;
            this.pitchKeys = pitchKeys;
            idealErrorBoundPixels = error;
            this.maximumErrorPixels = maximumErrorPixels;
            this.xy = xy;
        }

        float rollOriginX() {
            return request.roll().isPresent()
                    ? (float) (request.camera().centerX()
                            + request.camera().scale()
                                    * request.camera().pivot().x())
                    : 0;
        }

        float rollOriginY() {
            return request.roll().isPresent()
                    ? (float) (request.camera().centerY()
                            - request.camera().scale()
                                    * request.camera().pivot().y())
                    : 0;
        }

        List<Point2> controls(int yawIndex, int pitchIndex) {
            if (yawIndex < 0 || yawIndex >= yawKeys.size() || pitchIndex < 0 || pitchIndex >= pitchKeys.size())
                throw new IndexOutOfBoundsException();
            int size = geometry.sourceControls().size(), offset = (yawIndex * pitchKeys.size() + pitchIndex) * size * 2;
            return new java.util.AbstractList<>() {
                @Override
                public int size() {
                    return size;
                }

                @Override
                public Point2 get(int i) {
                    if (i < 0 || i >= size) throw new IndexOutOfBoundsException(i);
                    return new Point2(xy[offset + i * 2] - rollOriginX(), xy[offset + i * 2 + 1] - rollOriginY());
                }
            };
        }
    }

    record Created(
            PartId partId,
            DeformerId warpId,
            List<ArtMeshId> meshIds,
            List<ArtMeshAuthoringSnapshot> sourceSnapshots,
            List<ModelImageBinding> imageBindings,
            double maximumParentLocalDrift,
            double parentRoundTripErrorBoundPixels) {
        Created(
                PartId partId,
                DeformerId warpId,
                List<ArtMeshId> meshIds,
                List<ArtMeshAuthoringSnapshot> snapshots,
                List<ModelImageBinding> bindings) {
            this(partId, warpId, meshIds, snapshots, bindings, 0, 0);
        }

        Created {
            meshIds = List.copyOf(meshIds);
            sourceSnapshots = List.copyOf(sourceSnapshots);
            imageBindings = List.copyOf(imageBindings);
        }
    }

    static Prepared prepare(
            SurfaceWarpChartPlan.Plan geometry,
            SurfaceImport.Request request,
            SurfaceKeyPlan.Grid requestedGrid,
            double maximumIdealErrorPixels)
            throws IOException {
        Objects.requireNonNull(geometry);
        Objects.requireNonNull(request);
        Objects.requireNonNull(requestedGrid);
        var ys = SurfaceKeyformBake.bindingKeys(requestedGrid.yawKeys());
        var ps = SurfaceKeyformBake.bindingKeys(requestedGrid.pitchKeys());
        if (ys.size() < 3
                || ps.size() < 3
                || ys.get(0) != (float) -request.yawLimit()
                || ys.get(ys.size() - 1) != (float) request.yawLimit()
                || ps.get(0) != (float) -request.pitchLimit()
                || ps.get(ps.size() - 1) != (float) request.pitchLimit()
                || !Double.isFinite(maximumIdealErrorPixels)
                || maximumIdealErrorPixels <= 0)
            throw new IllegalArgumentException("joint key grid differs from requested rotation domain");
        int points = geometry.sourceControls().size(), forms = Math.multiplyExact(ys.size(), ps.size());
        long samples = (long) points * forms;
        if (geometry.columns() < 1
                || geometry.columns() > 512
                || geometry.rows() < 1
                || geometry.rows() > 512
                || points != (long) (geometry.columns() + 1) * (geometry.rows() + 1)
                || forms > 4096
                || samples > request.maxProjectedVertices()
                || samples != geometry.controlSamples()
                || !Double.isFinite(geometry.maximumSourceResidual())
                || geometry.maximumSourceResidual() < 0
                || samples > WarpAuthoringSnapshot.MAX_CONTROL_SAMPLES
                || samples > SurfaceImportPlan.MAX_PROJECTED_VERTICES
                || geometry.meshes().isEmpty()
                || geometry.sources().stream().anyMatch(GlbSurfaceReader.Surface::doubleSided))
            throw new IllegalArgumentException("source Warp preparation exceeds supported geometry/key domain");
        double radius = 0;
        var pivot = request.camera().pivot();
        for (var p : geometry.sourceControls())
            radius = Math.max(radius, Math.hypot(Math.hypot(p.x() - pivot.x(), p.y() - pivot.y()), p.z() - pivot.z()));
        double curvature = radius * request.camera().scale(),
                interpolation = curvature * (stepSquared(ys) + stepSquared(ps)) / 8;
        double bound = Math.max(geometry.maximumSourceResidual(), maximumPackedResidual(geometry))
                        * request.camera().scale()
                + interpolation;
        if (!Double.isFinite(bound) || bound > maximumIdealErrorPixels)
            throw new IllegalArgumentException("source fit/joint interpolation exceeds requested ideal accuracy");
        var images = SurfaceWarpImagePlan.compile(geometry);
        var xy = new float[Math.toIntExact(samples * 2)];
        int offset = 0;
        for (float yaw : ys)
            for (float pitch : ps)
                for (var p : geometry.projectControls(request.camera(), new SurfaceProjection.Pose(yaw, pitch))) {
                    xy[offset++] = p.x();
                    xy[offset++] = p.y();
                }
        return new Prepared(
                request,
                geometry,
                images,
                new SurfaceKeyPlan.Grid(requestedGrid.yawKeys(), requestedGrid.pitchKeys(), interpolation),
                ys,
                ps,
                bound,
                maximumIdealErrorPixels,
                xy);
    }

    static Prepared derivedImages(Prepared prepared) throws IOException {
        var images = SurfaceWarpImagePlan.compileDerived(prepared.geometry);
        return new Prepared(
                prepared.request,
                prepared.geometry,
                images,
                prepared.grid,
                prepared.yawKeys,
                prepared.pitchKeys,
                prepared.idealErrorBoundPixels,
                prepared.maximumErrorPixels,
                prepared.xy);
    }

    static Prepared faceColors(Prepared prepared) throws IOException {
        return new Prepared(
                prepared.request,
                prepared.geometry,
                SurfaceWarpImagePlan.faceColors(prepared.images),
                prepared.grid,
                prepared.yawKeys,
                prepared.pitchKeys,
                prepared.idealErrorBoundPixels,
                prepared.maximumErrorPixels,
                prepared.xy);
    }

    private static double maximumPackedResidual(SurfaceWarpChartPlan.Plan geometry) {
        double residual = 0;
        for (var mesh : geometry.meshes())
            for (int i = 0; i < mesh.warpLocal().size(); i++) {
                var local = mesh.warpLocal().get(i);
                if (local.x() < 0 || local.x() > 1 || local.y() < 0 || local.y() > 1)
                    throw new IllegalArgumentException("packed source vertex outside control atlas");
                double u = (double) local.x() * geometry.columns(), v = (double) local.y() * geometry.rows();
                int x = Math.min((int) Math.floor(u), geometry.columns() - 1),
                        y = Math.min((int) Math.floor(v), geometry.rows() - 1),
                        id = y * (geometry.columns() + 1) + x;
                double a = u - x, b = v - y;
                var corners = List.of(
                        geometry.sourceControls().get(id),
                        geometry.sourceControls().get(id + 1),
                        geometry.sourceControls().get(id + geometry.columns() + 1),
                        geometry.sourceControls().get(id + geometry.columns() + 2));
                double[] weights = {(1 - a) * (1 - b), a * (1 - b), (1 - a) * b, a * b};
                double px = 0, py = 0, pz = 0;
                for (int c = 0; c < 4; c++) {
                    var p = corners.get(c);
                    px += weights[c] * p.x();
                    py += weights[c] * p.y();
                    pz += weights[c] * p.z();
                }
                var original = mesh.sourcePositions().get(i);
                residual = Math.max(
                        residual, Math.hypot(Math.hypot(px - original.x(), py - original.y()), pz - original.z()));
            }
        return residual;
    }

    private static double stepSquared(List<Float> keys) {
        double maximum = 0;
        for (int i = 1; i < keys.size(); i++)
            maximum = Math.max(maximum, Math.toRadians((double) keys.get(i) - keys.get(i - 1)));
        return maximum * maximum;
    }

    static AuthoringTransactionResult<Created> apply(
            Prepared prepared, Supplier<CubismModel> current, AuthoringTransactionService transactions) {
        return apply(prepared, current, transactions, stage -> {});
    }

    static AuthoringTransactionResult<Created> apply(
            Prepared prepared,
            Supplier<CubismModel> current,
            AuthoringTransactionService transactions,
            Observer observer) {
        Objects.requireNonNull(prepared);
        Objects.requireNonNull(current);
        Objects.requireNonNull(transactions);
        Objects.requireNonNull(observer);
        return transactions.execute(
                AuthoringTransactionOptions.of("Import 3D " + prepared.request.name()),
                () -> assemble(current.get(), prepared, observer));
    }

    static Created assemble(CubismModel model, Prepared p, Observer observer) {
        Objects.requireNonNull(model);
        Objects.requireNonNull(observer);
        if (model.parameters().all().stream()
                .anyMatch(v -> v.id().equals(p.request.yaw())
                        || v.id().equals(p.request.pitch())
                        || p.request.roll().filter(v.id()::equals).isPresent()))
            throw new IllegalArgumentException("import parameter ID already exists");
        if (p.images.derived)
            for (var input : p.images.inputs) {
                var preview = model.drawables().previewSourceTriangulation(input.imageGeometry());
                if (!preview.positions().equals(input.imageGeometry().positions())
                        || !orientedFaces(preview.triangleIndices())
                                .equals(orientedFaces(input.imageGeometry().triangleIndices())))
                    throw new IllegalArgumentException("原生编辑网格预览无法保留模型全部顶点和面。");
            }
        var part = model.parts().create(p.request.name(), null, -1);
        var warp = model.deformers()
                .createWarp(
                        p.request.name() + " " + p.request.label("object.warp", "曲面变形"),
                        part,
                        -1,
                        p.geometry.rows(),
                        p.geometry.columns());
        var identity = new ArrayList<Point2>();
        for (int y = 0; y <= p.geometry.rows(); y++)
            for (int x = 0; x <= p.geometry.columns(); x++)
                identity.add(new Point2((float) x / p.geometry.columns(), (float) y / p.geometry.rows()));
        warp.replaceGrid(new WarpGrid(p.geometry.rows(), p.geometry.columns(), true, identity));
        Deformer projectionRoot = warp;
        RotationDeformer roll = null;
        if (p.request.roll().isPresent()) {
            roll = SurfaceRotationAuthoring.create(model, part, p.request);
            projectionRoot = roll;
        }
        observer.completed(Stage.IDENTITY);
        var ids = new ArrayList<ArtMeshId>();
        var snapshots = new ArrayList<ArtMeshAuthoringSnapshot>();
        for (int i = 0; i < p.images.inputs.size(); i++) {
            var input = p.images.inputs.get(i);
            var mesh = model.drawables()
                    .createWithSourceGeometry(
                            p.request.name() + " " + i, part, -1, input.imageGeometry(), input.defaultLocal());
            mesh.setCulling(true);
            ids.add(mesh.id());
            snapshots.add(mesh.authoringSnapshot());
        }
        var initial = new Created(part.id(), warp.id(), ids, snapshots, List.of());
        for (int i = 0; i < snapshots.size(); i++)
            if (snapshots.get(i).forms().size() != 1
                    || !snapshots
                            .get(i)
                            .forms()
                            .get(0)
                            .positions()
                            .equals(p.images.inputs.get(i).defaultLocal()))
                throw new IllegalStateException("native creation changed prepared default positions");
        verifyInputs(model, initial, p.images.inputs, Optional.empty());
        var bindings = bindImages(model, initial, p.images);
        var created = new Created(part.id(), warp.id(), ids, snapshots, bindings);
        observer.completed(Stage.MATERIAL);
        for (var id : ids) model.drawables().find(id).setTargetDeformer(Optional.of(warp.id()));
        created = acceptParentRoundTrip(model, created, p);
        observer.parentRoundTrip(created);
        verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
        verifyImageInputs(model, created);
        observer.completed(Stage.ATTACHED);
        if (roll != null) {
            // Attach normalized chart vertices to the root identity Warp first.
            // Inverting through a translated Roll origin (typically 512px) loses
            // float precision in [0,1] chart coordinates and amplifies edge drift.
            // Reparent only the Warp; child chart coordinates must remain frozen.
            warp.setTargetDeformer(Optional.of(roll.id()));
            var localIdentity = identity.stream()
                    .map(v -> new Point2(v.x() - p.rollOriginX(), v.y() - p.rollOriginY()))
                    .toList();
            warp.replaceGrid(new WarpGrid(p.geometry.rows(), p.geometry.columns(), true, localIdentity));
            verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
            verifyImageInputs(model, created);
        }
        warp.replaceGrid(new WarpGrid(
                p.geometry.rows(),
                p.geometry.columns(),
                true,
                p.controls(p.yawKeys.indexOf(0f), p.pitchKeys.indexOf(0f))));
        verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
        verifyImageInputs(model, created);
        observer.completed(Stage.NEUTRAL);
        model.parameters()
                .createMany(List.of(
                        new ParameterDefinition(
                                p.request.yaw(),
                                p.request.rotationName("Y"),
                                p.yawKeys.get(0),
                                0,
                                p.yawKeys.get(p.yawKeys.size() - 1),
                                ParameterType.NORMAL,
                                false),
                        new ParameterDefinition(
                                p.request.pitch(),
                                p.request.rotationName("X"),
                                p.pitchKeys.get(0),
                                0,
                                p.pitchKeys.get(p.pitchKeys.size() - 1),
                                ParameterType.NORMAL,
                                false)));
        var axes = List.of(p.request.yaw(), p.request.pitch());
        var keys = List.of(p.yawKeys, p.pitchKeys);
        for (int axis = 0; axis < 2; axis++) {
            var points = new ArrayList<ParameterBindingPoint>();
            for (int k = 0; k < keys.get(axis).size(); k++)
                points.add(new ParameterBindingPoint(
                        new ParameterBindingPointId("WARP_" + axes.get(axis).value() + "_" + k),
                        keys.get(axis).get(k)));
            model.parameterBindings(axes.get(axis)).bind(ParameterBindingTarget.warpDeformer(warp.id()), points);
        }
        verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
        verifyImageInputs(model, created);
        observer.completed(Stage.BOUND);
        var controls = new ArrayList<SurfaceWarpKeyformBake.Controls>();
        for (int y = 0; y < p.yawKeys.size(); y++)
            for (int pitch = 0; pitch < p.pitchKeys.size(); pitch++)
                controls.add(new SurfaceWarpKeyformBake.Controls(
                        new SurfaceWarpKeyformBake.Pose(p.yawKeys.get(y), p.pitchKeys.get(pitch)),
                        p.controls(y, pitch)));
        var bake = SurfaceWarpKeyformBake.compile(
                warp.authoringSnapshot(), p.request.yaw(), p.request.pitch(), p.grid, controls);
        bake.write(warp);
        bake.verify(warp);
        verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
        verifyImageInputs(model, created);
        SurfacePlacementAuthoring.wrap(model, part, p.request, projectionRoot);
        verifyInputs(model, created, p.images.inputs, Optional.of(warp.id()));
        verifyImageInputs(model, created);
        observer.completed(Stage.BAKED);
        return created;
    }
    // Native inverse mapping of an identity Warp has ordinary float round-off.
    // Accept it once at reparenting, then freeze the actual before image exactly.
    // A source/form/grid/UV/order change never qualifies as numeric round-off.
    static Created acceptParentRoundTrip(CubismModel model, Created created, Prepared p) {
        var snapshots = new ArrayList<ArtMeshAuthoringSnapshot>();
        double maximum = 0, dx = 0, dy = 0;
        for (int i = 0; i < created.meshIds().size(); i++) {
            var actual = model.drawables().find(created.meshIds().get(i)).authoringSnapshot();
            var before = created.sourceSnapshots().get(i);
            if (actual.forms().size() != 1
                    || actual.forms().get(0).positions().size()
                            != before.forms().get(0).positions().size())
                throw new IllegalStateException("parent conversion changed default form domain");
            var form = actual.forms().get(0);
            var withoutRoundOff = new ArtMeshAuthoringSnapshot(
                    actual.artMeshId(),
                    actual.sourceGuid(),
                    actual.sourceGeometry(),
                    actual.axes(),
                    actual.cells(),
                    List.of(new ArtMeshAuthoringSnapshot.Form(
                            form.formGuid(), before.forms().get(0).positions(), form.drawOrder())),
                    actual.morphKeys());
            if (!withoutRoundOff.equals(before))
                throw new IllegalStateException("parent conversion changed source/form/grid identity");
            for (int slot = 0; slot < form.positions().size(); slot++) {
                var a = form.positions().get(slot);
                var b = before.forms().get(0).positions().get(slot);
                double x = Math.abs((double) a.x() - b.x()), y = Math.abs((double) a.y() - b.y());
                dx = Math.max(dx, x);
                dy = Math.max(dy, y);
                maximum = Math.max(maximum, Math.hypot(x, y));
            }
            snapshots.add(actual);
        }
        // Translating through a 512px Rotation origin incurs float cancellation.
        // Two ULPs per coordinate bound that arithmetic; the independent world-pixel
        // budget below still rejects amplified drift or an incorrect parent transform.
        double localLimit = p.request.roll().isPresent() ? 2 * Math.sqrt(2) * Math.ulp(512f) : 1e-6;
        if (maximum > localLimit)
            throw new IllegalStateException(
                    String.format(java.util.Locale.ROOT, "parent local delta=%.4g exceeds %.4g", maximum, localLimit));
        double bound = 0;
        if (maximum > 0) {
            // Bilinear weights and parameter interpolation are convex inside each cell.
            // Bound both local derivatives across every stored control pose, including
            // extrapolation by the tiny measured round-off at the outer rectangle.
            double horizontal = 0, vertical = 0;
            int columns = p.geometry.columns(),
                    rows = p.geometry.rows(),
                    size = p.geometry.sourceControls().size();
            for (int offset = 0; offset < p.xy.length; offset += size * 2)
                for (int y = 0; y <= rows; y++)
                    for (int x = 0; x <= columns; x++) {
                        int at = offset + 2 * (y * (columns + 1) + x);
                        if (x < columns)
                            horizontal = Math.max(
                                    horizontal,
                                    Math.hypot((double) p.xy[at + 2] - p.xy[at], (double) p.xy[at + 3] - p.xy[at + 1])
                                            * columns);
                        if (y < rows) {
                            int next = at + 2 * (columns + 1);
                            vertical = Math.max(
                                    vertical,
                                    Math.hypot((double) p.xy[next] - p.xy[at], (double) p.xy[next + 1] - p.xy[at + 1])
                                            * rows);
                        }
                    }
            bound = dx * horizontal * (1 + 2 * dy * rows) + dy * vertical * (1 + 2 * dx * columns);
        }
        if (!Double.isFinite(bound) || p.idealErrorBoundPixels + bound > p.maximumErrorPixels)
            throw new IllegalStateException(String.format(
                    java.util.Locale.ROOT, "parent rounding pixel bound=%.4g exceeds remaining budget", bound));
        return new Created(
                created.partId(),
                created.warpId(),
                created.meshIds(),
                snapshots,
                created.imageBindings(),
                maximum,
                bound);
    }

    static void verifyInputs(
            CubismModel model,
            Created created,
            List<SurfaceWarpImagePlan.MeshInput> inputs,
            Optional<DeformerId> parent) {
        if (created.meshIds().size() != inputs.size()
                || created.sourceSnapshots().size() != inputs.size())
            throw new IllegalStateException("source mesh inventory changed");
        for (int i = 0; i < inputs.size(); i++) {
            var id = created.meshIds().get(i);
            var mesh = model.drawables().find(id);
            var actual = mesh.authoringSnapshot();
            var input = inputs.get(i);
            var original = created.sourceSnapshots().get(i);
            String difference = null;
            if (!actual.artMeshId().equals(id) || !actual.sourceGuid().equals(original.sourceGuid()))
                difference = "source identity";
            else if (!mesh.parentDeformerId().equals(parent)) difference = "parent ownership";
            else if (!mesh.culling() || !mesh.visible()) difference = "culling/visibility";
            else if (!actual.sourceGeometry().equals(input.imageGeometry()))
                difference = "source image geometry/UV/indices";
            else if (actual.forms().size() != original.forms().size()
                    || !actual.cells().equals(original.cells())
                    || !actual.axes().equals(original.axes())
                    || !actual.morphKeys().equals(original.morphKeys())) difference = "stored form/grid layout";
            else if (!actual.forms()
                    .get(0)
                    .positions()
                    .equals(original.forms().get(0).positions())) {
                var positions = actual.forms().get(0).positions();
                double maximum = 0;
                if (positions.size() == original.forms().get(0).positions().size())
                    for (int slot = 0; slot < positions.size(); slot++) {
                        var v = positions.get(slot);
                        var expected = original.forms().get(0).positions().get(slot);
                        maximum = Math.max(
                                maximum, Math.hypot((double) v.x() - expected.x(), (double) v.y() - expected.y()));
                    }
                difference =
                        String.format(java.util.Locale.ROOT, "local delta=%.4g count=%d", maximum, positions.size());
            } else if (!actual.equals(original)) difference = "stored form identity/order";
            if (difference != null) throw new IllegalStateException("native mesh " + i + " changed " + difference);
        }
    }

    private static Map<List<Integer>, Integer> orientedFaces(List<Integer> indices) {
        if (indices.size() % 3 != 0) throw new IllegalArgumentException("complete original faces required");
        var faces = new HashMap<List<Integer>, Integer>();
        for (int i = 0; i < indices.size(); i += 3) {
            int a = indices.get(i), b = indices.get(i + 1), c = indices.get(i + 2);
            int[] corners = {a, b, c};
            int first = 0;
            for (int j = 1; j < 3; j++)
                for (int k = 0; k < 3; k++) {
                    int difference = Integer.compare(corners[(j + k) % 3], corners[(first + k) % 3]);
                    if (difference < 0) first = j;
                    if (difference != 0) break;
                }
            faces.merge(List.of(corners[first], corners[(first + 1) % 3], corners[(first + 2) % 3]), 1, Integer::sum);
        }
        return faces;
    }

    private static List<ModelImageBinding> bindImages(
            CubismModel model, Created created, SurfaceWarpImagePlan.Plan images) {
        verifyInputs(model, created, images.inputs, Optional.empty());
        for (var id : created.meshIds())
            if (model.textures().textureInput(id).isPresent())
                throw new IllegalArgumentException("new mesh already has an image");
        var bindings = new ArrayList<ModelImageBinding>();
        var registered = new java.util.HashMap<Integer, ModelImageBinding>();
        for (int i = 0; i < created.meshIds().size(); i++) {
            var id = created.meshIds().get(i);
            int image = images.meshImages.get(i);
            var first = registered.get(image);
            var bound = first == null
                    ? model.textures().registerImageAndBind(images.newGrant(image), id)
                    : model.textures().bindModelImage(first.modelImageId(), id);
            if (bound == null
                    || !bound.artMeshId().equals(Optional.of(id))
                    || (first != null
                            && (!bound.modelImageId().equals(first.modelImageId())
                                    || !bound.rawImageId().equals(first.rawImageId())
                                    || !bound.modelId().equals(first.modelId())
                                    || bound.modelGeneration() != first.modelGeneration())))
                throw new IllegalStateException("shared source image ownership changed");
            if (first == null) registered.put(image, bound);
            bindings.add(bound);
            verifyInputs(model, created, images.inputs, Optional.empty());
        }
        verifyImageInputs(
                model,
                new Created(
                        created.partId(), created.warpId(), created.meshIds(), created.sourceSnapshots(), bindings));
        return List.copyOf(bindings);
    }

    static void verifyImageInputs(CubismModel model, Created created) {
        if (created.meshIds().size() != created.imageBindings().size())
            throw new IllegalStateException("source image binding inventory");
        for (int i = 0; i < created.meshIds().size(); i++) {
            var id = created.meshIds().get(i);
            var expected = created.imageBindings().get(i);
            var actual = model.textures().textureInput(id).orElseThrow();
            if (!actual.artMeshId().equals(id)
                    || actual.kind() != ArtMeshTextureInput.Kind.MODEL_IMAGE
                    || !actual.modelImageId().equals(Optional.of(expected.modelImageId()))
                    || !actual.modelId().equals(expected.modelId())
                    || actual.modelGeneration() != expected.modelGeneration())
                throw new IllegalStateException("source image readback changed");
        }
    }
}
