package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.ArtMeshKeyform;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Point2;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;

/** Exercises the SDK consumer and complete-tuple writes, without simulating native evaluation. */
public final class SurfaceKeyformBakeTest {
    private static final ParameterId YAW = new ParameterId("Yaw"), PITCH = new ParameterId("Pitch");
    private static final List<SurfaceProjection.Vertex> SOURCE = List.of(new SurfaceProjection.Vertex(.3, .2, .1),
        new SurfaceProjection.Vertex(-.3, .2, .1), new SurfaceProjection.Vertex(0, -.2, -.1));
    private static final SurfaceProjection.Camera CAMERA = new SurfaceProjection.Camera(512, 512, 200,
        new SurfaceProjection.Vertex(0, 0, 0));
    private static int checks;

    public static void main(String[] args) { run(); }

    static void run() {
        checks = 0;
        var grid = SurfaceKeyPlan.linear(SOURCE, CAMERA, 30, 30, .05, 4096);
        var before = fixture(grid);
        var plan = SurfaceKeyformBake.compile(before, YAW, PITCH, SOURCE, CAMERA, grid);
        check(plan.targets().size() > 9, "dense joint grid");
        var backend = new Backend(before);
        plan.write(backend.mesh());
        plan.verify(backend.mesh());
        check(backend.writes == plan.targets().size() - 1, "exactly every non-default tuple written");
        check(backend.batches == 1, "one SDK batch per mesh");
        check(backend.snapshot.sourceGeometry().equals(before.sourceGeometry()), "UV topology/source preserved");
        var unfoldedGeometry = new ArtMeshGeometry(before.sourceGeometry().uvs().stream()
            .map(p -> new Point2(p.x()*256,p.y()*128)).toList(),
            before.sourceGeometry().uvs(),before.sourceGeometry().triangleIndices());
        var unfolded = new ArtMeshAuthoringSnapshot(before.artMeshId(), before.sourceGuid(), unfoldedGeometry,
            before.axes(), before.cells(), before.forms(), before.morphKeys());
        var unfoldedPlan=SurfaceKeyformBake.compile(unfolded,YAW,PITCH,SOURCE,CAMERA,grid);
        var unfoldedBackend=new Backend(unfolded);
        unfoldedPlan.write(unfoldedBackend.mesh());unfoldedPlan.verify(unfoldedBackend.mesh());
        check(unfoldedBackend.snapshot.sourceGeometry().equals(unfoldedGeometry),"bake preserves unfolded image-space source");
        check(unfoldedBackend.snapshot.forms().equals(backend.snapshot.forms()),"source mapping does not alter any projected form");
        check(backend.snapshot.forms().stream().allMatch(f -> f.drawOrder() == 500), "draw order preserved");
        var corner = backend.snapshot.forms().get(backend.snapshot.forms().size()-1).positions().get(0);
        double r = Math.sqrt(3);
        // (x,y,z)=(.3,.2,.1), pitch=yaw=30, independent closed form.
        check(Math.hypot(corner.x()-(512+200*(.05+.175*r)), corner.y()-(512-200*(.1*r-.05))) < .0001,
            "joint corner came from source rotation, not summed 2D offsets");
        reject(() -> plan.write(backend.mesh()));
        check(backend.writes == plan.targets().size() - 1, "stale plan rejected before writes");
        reject(() -> SurfaceKeyformBake.bindingKeys(List.of(-1d, 0d, 0.00000001d, 1d)));
        reject(() -> SurfaceKeyformBake.bindingKeys(List.of(-1d, 1d)));
        reject(() -> SurfaceKeyformBake.bindingKeys(List.of(1d, 0d, -1d)));
        reject(() -> SurfaceKeyformBake.bindingKeys(List.of(-1d, 0d, Double.POSITIVE_INFINITY)));
        reject(() -> SurfaceKeyformBake.compile(before, YAW, YAW, SOURCE, CAMERA, grid));
        reject(() -> SurfaceKeyformBake.compile(before, new ParameterId("Wrong"), PITCH, SOURCE, CAMERA, grid));
        var missing = new ArtMeshAuthoringSnapshot(before.artMeshId(), before.sourceGuid(), before.sourceGeometry(),
            before.axes(), before.cells().subList(1, before.cells().size()), before.forms(), List.of());
        reject(() -> SurfaceKeyformBake.compile(missing, YAW, PITCH, SOURCE, CAMERA, grid));
        var cells = new ArrayList<>(before.cells());
        cells.set(1, new ArtMeshAuthoringSnapshot.Cell(cells.get(0).formGuid(), cells.get(1).coordinates()));
        var aliased = new ArtMeshAuthoringSnapshot(before.artMeshId(), before.sourceGuid(), before.sourceGeometry(),
            before.axes(), cells, before.forms(), List.of());
        reject(() -> SurfaceKeyformBake.compile(aliased, YAW, PITCH, SOURCE, CAMERA, grid));
        var corrupt = new Backend(before);
        corrupt.dropWrites = true;
        plan.write(corrupt.mesh());
        reject(() -> plan.verify(corrupt.mesh()));
        System.out.println("SurfaceKeyformBake checks=" + checks + " PASS; targets=" + plan.targets().size()
            + "; hostExecuted=false");
    }

    private static ArtMeshAuthoringSnapshot fixture(SurfaceKeyPlan.Grid grid) {
        var positions = SurfaceProjection.project(SOURCE, CAMERA, new SurfaceProjection.Pose(0,0)).positions();
        var geometry = new ArtMeshGeometry(positions, List.of(new Point2(0,0), new Point2(1,0), new Point2(0,1)),
            List.of(0,1,2));
        var ys = SurfaceKeyformBake.bindingKeys(grid.yawKeys());
        var ps = SurfaceKeyformBake.bindingKeys(grid.pitchKeys());
        // Reverse axis/GUID order deliberately; tuple meaning must not depend on either.
        var axes = List.of(new ArtMeshAuthoringSnapshot.Axis("a-pitch", PITCH, -30, 0, 30, ps),
            new ArtMeshAuthoringSnapshot.Axis("z-yaw", YAW, -30, 0, 30, ys));
        var cells = new ArrayList<ArtMeshAuthoringSnapshot.Cell>();
        var forms = new ArrayList<ArtMeshAuthoringSnapshot.Form>();
        for (float y : ys) for (float p : ps) {
            String guid = "form-" + forms.size();
            cells.add(new ArtMeshAuthoringSnapshot.Cell(guid, List.of(new ArtMeshAuthoringSnapshot.Coordinate("z-yaw",y),
                new ArtMeshAuthoringSnapshot.Coordinate("a-pitch",p))));
            forms.add(new ArtMeshAuthoringSnapshot.Form(guid, positions, 500));
        }
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("mesh"), "source", geometry, axes, cells, forms, List.of());
    }

    private static final class Backend {
        ArtMeshAuthoringSnapshot snapshot;
        int writes, batches;
        boolean dropWrites;
        Backend(ArtMeshAuthoringSnapshot snapshot) { this.snapshot = snapshot; }
        Drawable mesh() {
            return (Drawable) Proxy.newProxyInstance(Drawable.class.getClassLoader(), new Class<?>[] {Drawable.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "authoringSnapshot" -> snapshot;
                    case "setOrdinaryKeyformPositions" -> {
                        if (!snapshot.equals(args[0])) throw new IllegalArgumentException("stale batch");
                        batches++;
                        for (var value : (List<?>) args[1]) {
                            var edit = (dev.turboism.sdk.cubism.model.ArtMeshKeyformPositions) value;
                            var target = mesh().ordinaryKeyform(edit.coordinates());
                            if (!target.snapshot().formGuid().equals(edit.formGuid())) throw new IllegalArgumentException("wrong form");
                            target.setPositions(edit.positions());
                        }
                        yield null;
                    }
                    case "ordinaryKeyform" -> {
                        var coords = (List<?>) args[0];
                        var matches = snapshot.cells().stream().filter(c -> c.coordinates().size() == coords.size()
                            && c.coordinates().containsAll(coords)).toList();
                        if (matches.size() != 1) throw new IllegalArgumentException("incomplete/ambiguous tuple");
                        var cell = matches.get(0);
                        boolean isDefault = cell.coordinates().stream().allMatch(c -> c.value() == 0);
                        yield Proxy.newProxyInstance(ArtMeshKeyform.class.getClassLoader(), new Class<?>[] {ArtMeshKeyform.class},
                            (ignored, km, ka) -> switch (km.getName()) {
                                case "defaultKeyform" -> isDefault;
                                case "snapshot" -> snapshot.forms().stream().filter(f -> f.formGuid().equals(cell.formGuid()))
                                    .findFirst().orElseThrow();
                                case "setPositions" -> {
                                    if (isDefault) throw new AssertionError("default write attempted");
                                    writes++;
                                    if (!dropWrites) snapshot = new ArtMeshAuthoringSnapshot(snapshot.artMeshId(), snapshot.sourceGuid(),
                                        snapshot.sourceGeometry(), snapshot.axes(), snapshot.cells(), snapshot.forms().stream()
                                            .map(f -> f.formGuid().equals(cell.formGuid()) ? new ArtMeshAuthoringSnapshot.Form(
                                                f.formGuid(), ((List<?>) ka[0]).stream().map(Point2.class::cast).toList(), f.drawOrder()) : f).toList(),
                                        snapshot.morphKeys());
                                    yield null;
                                }
                                default -> throw new AssertionError(km);
                            });
                    }
                    default -> throw new AssertionError(method);
                });
        }
    }

    private static void reject(Runnable task) {
        try { task.run(); } catch (IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid bake accepted");
    }
    private static void check(boolean valid, String message) { if (!valid) throw new AssertionError(message); checks++; }
}
