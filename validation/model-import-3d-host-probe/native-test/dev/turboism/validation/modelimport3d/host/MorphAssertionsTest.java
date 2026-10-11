package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Axis;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Cell;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.Form;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.MorphKey;
import dev.turboism.sdk.cubism.model.Point2;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Frozen N4a constants and pure snapshot helpers; never instantiates a host plugin. */
public final class MorphAssertionsTest {
    private static int checks;

    public static void run() {
        check(MorphProbe.B.equals(List.of(new Point2(16, 24), new Point2(272, 24), new Point2(272, 152), new Point2(16, 152))), "frozen base");
        check(MorphProbe.U.equals(List.of(new Point2(48, 40), new Point2(304, 40), new Point2(304, 168), new Point2(48, 168))), "frozen first target");
        check(MorphProbe.H.equals(List.of(new Point2(32, 32), new Point2(288, 32), new Point2(288, 160), new Point2(32, 160))), "frozen midpoint");
        check(MorphProbe.U2.equals(List.of(new Point2(80, 56), new Point2(336, 56), new Point2(336, 184), new Point2(80, 184))), "frozen second target");
        check(MorphProbe.V.equals(List.of(new Point2(8, 64), new Point2(264, 64), new Point2(264, 192), new Point2(8, 192))), "frozen control target");
        check(MorphProbe.W.equals(List.of(new Point2(96, 8), new Point2(352, 8), new Point2(352, 136), new Point2(96, 136))), "frozen second control target");
        check(MorphProbe.SEED25.equals(List.of(new Point2(24, 28), new Point2(280, 28), new Point2(280, 156), new Point2(24, 156))), "frozen .25 seed");
        check(MorphProbe.SEED75.equals(List.of(new Point2(56, 44), new Point2(312, 44), new Point2(312, 172), new Point2(56, 172))), "frozen .75 seed");
        // The frozen constants must satisfy the independent morph interpolation identities.
        check(MorphProbe.H.equals(midpoint(MorphProbe.B, MorphProbe.U)), "H is the single-key midpoint");
        check(MorphProbe.SEED25.equals(midpoint(MorphProbe.B, MorphProbe.H)), ".25 seed interpolates base to .5 key");
        check(MorphProbe.SEED75.equals(midpoint(MorphProbe.H, MorphProbe.U2)), ".75 seed interpolates .5 to 1 keys");
        var s = fixture();
        check(MorphProbe.morphKey(s, "gm", 1f).formGuid().equals("m1") && MorphProbe.morphKey(s, "gn", 1f).formGuid().equals("n1"),
            "morph triples resolve by parameter and key");
        reject(() -> MorphProbe.morphKey(s, "gm", .5f));
        reject(() -> MorphProbe.morphKey(s, "absent", 1f));
        var duplicate = new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(), s.axes(), s.cells(),
            s.forms(), List.of(new MorphKey("gm", "m1", Optional.of(1f)), new MorphKey("gm", "n1", Optional.of(1f))));
        reject(() -> MorphProbe.morphKey(duplicate, "gm", 1f));
        var zero = new ArtMeshAuthoringSnapshot(s.artMeshId(), s.sourceGuid(), s.sourceGeometry(), s.axes(), s.cells(),
            s.forms(), List.of(new MorphKey("gm", "m1", Optional.of(0f))));
        reject(() -> MorphProbe.morphKey(zero, "gm", 1f));  // A zero record never aliases a positive key.
        check(MorphProbe.form(s, "m1").positions().equals(MorphProbe.U), "stored form lookup");
        reject(() -> MorphProbe.form(s, "absent"));
        var unrun = new NativeReport("synthetic-only");
        check(unrun.morph.get("status").equals("NOT_RUN") && unrun.morph.get("checks").equals(List.of())
            && unrun.morph.get("samples").equals(List.of()) && unrun.morph.get("schemaVersion").equals("mi3d-native-n4a-v1"),
            "unobserved morph is NOT_RUN");
        System.out.println("N4A OFFLINE_FIXTURE checks=" + checks + " PASS; hostExecuted=false");
    }

    private static List<Point2> midpoint(List<Point2> a, List<Point2> b) {
        var result = new ArrayList<Point2>();
        for (int i = 0; i < a.size(); i++) result.add(new Point2((a.get(i).x() + b.get(i).x()) / 2, (a.get(i).y() + b.get(i).y()) / 2));
        return List.copyOf(result);
    }

    private static ArtMeshAuthoringSnapshot fixture() {
        var cells = new ArrayList<Cell>(); var forms = new ArrayList<Form>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            String guid = x + ":" + y;
            cells.add(new Cell(guid, List.of(new Coordinate("gy", y), new Coordinate("gx", x))));
            forms.add(new Form(guid, MorphProbe.B, 500));
        }
        forms.add(new Form("m1", MorphProbe.U, 500)); forms.add(new Form("n1", MorphProbe.B, 500));
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("morph-mesh"), "morph-source", NativeAssertions.GEOMETRY,
            List.of(new Axis("gx", new ParameterId("ParamMI3DN2X"), -1, 0, 1, List.of(-1f, 0f, 1f)),
                new Axis("gy", new ParameterId("ParamMI3DN2Y"), -1, 0, 1, List.of(-1f, 0f, 1f))),
            cells, forms, List.of(new MorphKey("gm", "m1", Optional.of(1f)), new MorphKey("gn", "n1", Optional.of(1f))));
    }

    private static void check(boolean value, String message) { NativeAssertions.require(value, message); checks++; }
    private static void reject(Runnable action) {
        try { action.run(); } catch (IllegalStateException | IllegalArgumentException expected) { checks++; return; }
        throw new AssertionError("invalid morph fixture accepted");
    }
}
