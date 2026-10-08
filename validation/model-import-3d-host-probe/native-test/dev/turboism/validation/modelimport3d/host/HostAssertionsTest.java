package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import dev.turboism.sdk.cubism.DocumentSnapshot;
import dev.turboism.sdk.cubism.ModelSnapshot;

/** Synthetic oracle checks only. Does not instantiate HostProbe or produce an EXACT_HOST report. */
public final class HostAssertionsTest {
    private static int count;
    public static void main(String[] args) throws Exception {
        // Exercise the actual enable-time admission, not just the later dispatch branch.
        HostProbe.requireMode("cube-atlas");
        HostProbe.requireMode("cube-export");
        HostProbe.requireMode("canonical-warp-layered-textured");
        reject(() -> HostProbe.requireMode("canonical-warp-layered-save"));
        reject(() -> HostProbe.requireMode("cube-atlas-unknown"));
        var form = new Form("shared", NativeAssertions.GEOMETRY.positions(), 500);
        var axes = List.of(axis("gx", "x"), axis("gy", "y"));
        var cells = new ArrayList<Cell>();
        for (float x : NativeAssertions.KEYS) for (float y : NativeAssertions.KEYS) {
            cells.add(new Cell("shared", List.of(new Coordinate("gy", y), new Coordinate("gx", x))));
        }
        var valid = snapshot(axes, cells, List.of(form), List.of());
        NativeAssertions.grid(valid, "x", "y"); count++;
        NativeAssertions.require(valid.cells().stream().map(Cell::formGuid).distinct().count() == 1, "alias was lost"); count++;
        var created = snapshot(List.of(), List.of(new Cell("shared", List.of())), List.of(form), List.of());
        NativeAssertions.created(created); count++;
        reject(() -> NativeAssertions.grid(snapshot(axes, cells.subList(1, 9), List.of(form), List.of()), "x", "y"));
        var duplicate = new ArrayList<>(cells); duplicate.set(0, duplicate.get(1));
        reject(() -> NativeAssertions.grid(snapshot(axes, duplicate, List.of(form), List.of()), "x", "y"));
        reject(() -> NativeAssertions.grid(valid, "wrong", "y"));
        reject(() -> NativeAssertions.created(valid));
        reject(() -> NativeAssertions.grid(new ArtMeshAuthoringSnapshot(valid.artMeshId(), "source",
            NativeAssertions.GEOMETRY.withVertexPosition(0, 999, 99), axes, cells,
            List.of(form), List.of()), "x", "y"));
        reject(() -> NativeAssertions.grid(snapshot(axes, cells, List.of(new Form("shared",
            NativeAssertions.GEOMETRY.withVertexPosition(0, 99, 9).positions(), 500)), List.of()), "x", "y"));
        var nullMorph = snapshot(axes, cells, List.of(form), List.of(new MorphKey("gm", "shared", Optional.empty())));
        NativeAssertions.require(NativeReport.json(NativeAssertions.snapshot(nullMorph)).contains("\"keyValue\":null"), "null identity lost"); count++;
        reject(() -> NativeAssertions.grid(nullMorph, "x", "y"));
        var zeroMorph = snapshot(axes, cells, List.of(form), List.of(new MorphKey("gm", "shared", Optional.of(0f))));
        NativeAssertions.require(NativeReport.json(NativeAssertions.snapshot(zeroMorph)).contains("\"basicIdentity\":\"UNKNOWN\""), "zero classified"); count++;
        NativeAssertions.require(NativeReport.json("\"\\\n").equals("\"\\\"\\\\\\u000a\""), "JSON escaping"); count++;
        reject(() -> NativeReport.json(Float.NaN));
        var doc = document("document-session-1", "model-session-2");
        NativeAssertions.require(HostProbe.stableDocumentIdentity(doc, "native-guid-abc", doc, "native-guid-abc", null),
            "stable document and live model use distinct ID namespaces"); count++;
        check(!HostProbe.stableDocumentIdentity(doc, "native-a", doc, "native-b", null), "live model switch rejected");
        check(!HostProbe.stableDocumentIdentity(doc, "native-a", document("changed", "model-session-2"), "native-a", null), "document switch rejected");
        check(!HostProbe.stableDocumentIdentity(doc, "native-a", document("document-session-1", "changed"), "native-a", null), "snapshot model switch rejected");
        check(!HostProbe.stableDocumentIdentity(doc, "native-a", doc, "native-a", doc.documentId()), "seed reused rejected");
        check(!HostProbe.stableDocumentIdentity(null, "native-a", doc, "native-a", null), "missing before document rejected");
        check(!HostProbe.stableDocumentIdentity(doc, "native-a", null, "native-a", null), "missing after document rejected");
        check(!HostProbe.stableDocumentIdentity(doc, "", doc, "", null), "empty live ID rejected");
        var calls = new ArrayList<String>();
        var observed = HostProbe.observeDocument(() -> { calls.add("document"); return doc; },
            () -> { calls.add("model"); return model("native-a"); }, null);
        check(observed.ready() && observed.editorModelId().equals("native-a")
            && calls.equals(List.of("document", "model", "document", "model")), "real observation brackets each namespace");
        check(observed.diagnostic().get("snapshotModelId").equals("model-session-2")
            && observed.diagnostic().get("editorModelId").equals("native-a"), "diagnostic preserves distinct IDs");
        var missing = HostProbe.observeDocument(() -> null, () -> { throw new AssertionError("model read without document"); }, null);
        check(!missing.ready() && missing.reason().equals("NO_MODEL_DOCUMENT")
            && missing.diagnostic().get("documentId") == null, "absent document diagnostic is honest");
        var seed = HostProbe.observeDocument(() -> doc, () -> { throw new AssertionError("seed model read in NEW wait"); }, doc.documentId());
        check(!seed.ready() && seed.reason().equals("WAITING_NEW_DOCUMENT"), "NEW wait never accepts seed");
        var documents = new java.util.ArrayDeque<>(List.of(doc, document("switched", "other")));
        var switched = HostProbe.observeDocument(documents::remove, () -> model("native-a"), null);
        check(!switched.ready() && switched.model() == null && switched.reason().equals("IDENTITY_CHANGED"), "unstable observation yields no model handle");
        ordinaryChecks(valid);
        var unrun = new NativeReport("offline-test-only");
        check(unrun.ordinaryWrite.get("status").equals("NOT_RUN")
            && unrun.ordinaryWrite.get("evaluationStatus").equals("NOT_RUN"), "unobserved extension is not PASS");
        EvaluationAssertionsTest.run();
        OrderAssertionsTest.run();
        MorphAssertionsTest.run();
        MorphOrderAssertionsTest.run();
        ImageBindingAssertionsTest.run();
        PixelSamplingAssertionsTest.run();
        PixelSamplingProducerIntegrationTest.run();
        ShapeImportAssertionsTest.run();
        SurfaceProjectionTest.run();
        SurfaceKeyformBakeTest.run();
        SurfaceAtlasLayoutTest.main(new String[0]);
        JointPoseProducerIntegrationTest.run();
        System.out.println("OFFLINE_FIXTURE checks=" + count + " PASS; hostExecuted=false");
    }
    private static Axis axis(String guid, String id) { return new Axis(guid, new ParameterId(id), -1, 0, 1, NativeAssertions.KEYS); }
    private static ArtMeshAuthoringSnapshot snapshot(List<Axis> axes, List<Cell> cells, List<Form> forms, List<MorphKey> morph) {
        return new ArtMeshAuthoringSnapshot(new ArtMeshId("mesh"), "source", NativeAssertions.GEOMETRY, axes, cells, forms, morph);
    }
    private static void ordinaryChecks(ArtMeshAuthoringSnapshot aliased) {
        reject(() -> OrdinaryWriteProbe.formGuid(aliased, List.of(new Coordinate("gx", 1), new Coordinate("gy", 0))));
        var cells = new ArrayList<Cell>(); var forms = new ArrayList<Form>();
        for (int x = -1; x <= 1; x++) for (int y = -1; y <= 1; y++) {
            String guid = x + ":" + y;
            cells.add(new Cell(guid, List.of(new Coordinate("gx", x), new Coordinate("gy", y))));
            forms.add(new Form(guid, NativeAssertions.GEOMETRY.positions(), 500));
        }
        var s = snapshot(List.of(axis("gx", "x"), axis("gy", "y")), cells, forms, List.of());
        var c = OrdinaryWriteProbe.coordinates(s, "x", "y", 1);
        check(c.equals(List.of(new Coordinate("gy", 0), new Coordinate("gx", 1))), "complete Y-X coordinates");
        check(OrdinaryWriteProbe.formGuid(s, c).equals("1:0"), "tuple resolves independently of axis order");
        reject(() -> OrdinaryWriteProbe.formGuid(s, c.subList(0, 1)));
        reject(() -> OrdinaryWriteProbe.coordinates(s, "missing", "y", 1));
        var morphAlias = snapshot(s.axes(), s.cells(), s.forms(), List.of(new MorphKey("m", "1:0", Optional.empty())));
        reject(() -> OrdinaryWriteProbe.formGuid(morphAlias, c));
        var expectedA = List.of(new dev.turboism.sdk.cubism.model.Point2(48, 40),
            new dev.turboism.sdk.cubism.model.Point2(304, 40), new dev.turboism.sdk.cubism.model.Point2(304, 168),
            new dev.turboism.sdk.cubism.model.Point2(48, 168));
        var expectedB = List.of(new dev.turboism.sdk.cubism.model.Point2(-4, 64),
            new dev.turboism.sdk.cubism.model.Point2(252, 64), new dev.turboism.sdk.cubism.model.Point2(252, 192),
            new dev.turboism.sdk.cubism.model.Point2(-4, 192));
        check(OrdinaryWriteProbe.A.equals(expectedA) && OrdinaryWriteProbe.B.equals(expectedB), "frozen positions");
        var changed = OrdinaryWriteProbe.changed(s, "1:0", OrdinaryWriteProbe.A, "-1:0", OrdinaryWriteProbe.B);
        for (var form : changed.forms()) {
            check(form.positions().equals(form.formGuid().equals("1:0") ? expectedA
                : form.formGuid().equals("-1:0") ? expectedB : NativeAssertions.GEOMETRY.positions())
                && form.drawOrder() == 500, "independent per-form expected content");
        }
        check(s.forms().equals(forms) && changed.sourceGeometry().equals(s.sourceGeometry())
            && changed.axes().equals(s.axes()) && changed.cells().equals(s.cells()), "source/layout/original unchanged");
        reject(() -> OrdinaryWriteProbe.changed(s, "1:0", expectedA, "1:0", expectedB));
        reject(() -> OrdinaryWriteProbe.changed(s, "absent", expectedA, "-1:0", expectedB));
    }
    private static DocumentSnapshot document(String documentId, String snapshotModelId) {
        return new DocumentSnapshot(documentId, "fixture", "fixture.cmo3", Optional.empty(),
            Optional.of(new ModelSnapshot(snapshotModelId, "fixture", List.of(), List.of(), List.of(), List.of())));
    }
    private static dev.turboism.sdk.cubism.model.CubismModel model(String id) {
        // SDK interface stub only; no reflective Cubism/runtime access and no plugin activation.
        return (dev.turboism.sdk.cubism.model.CubismModel) java.lang.reflect.Proxy.newProxyInstance(
            HostAssertionsTest.class.getClassLoader(), new Class<?>[] {dev.turboism.sdk.cubism.model.CubismModel.class},
            (proxy, method, args) -> {
                if (method.getName().equals("id")) return new dev.turboism.sdk.cubism.id.ModelId(id);
                throw new AssertionError("unexpected SDK call: " + method.getName());
            });
    }
    private static void check(boolean value, String message) { NativeAssertions.require(value, message); count++; }
    private static void reject(Runnable operation) {
        try { operation.run(); } catch (IllegalStateException | IllegalArgumentException expected) { count++; return; }
        throw new AssertionError("bad fixture was accepted");
    }
}
