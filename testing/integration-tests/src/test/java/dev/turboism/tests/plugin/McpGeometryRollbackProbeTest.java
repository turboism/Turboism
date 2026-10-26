package dev.turboism.tests.plugin;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class McpGeometryRollbackProbeTest {
    @TempDir
    Path temporary;

    private static final String RUN = "queue-1234567890abcdef";

    @Test
    void rejectsForeignAndSymlinkedFixtureBeforeHostActions() throws Exception {
        final var task = Files.createDirectory(temporary.resolve(RUN));
        final var home = Files.createDirectory(task.resolve("turboism-home"));
        final var fixture = Files.writeString(task.resolve(RUN + ".cmo3"), "fixture");
        McpGeometryRollbackProbe.requireTaskIdentity(home, fixture, RUN);
        assertThrows(
                IllegalStateException.class,
                () -> McpGeometryRollbackProbe.requireTaskIdentity(home, fixture, "queue-foreign"));
        final var alias = task.resolve("alias");
        Files.createSymbolicLink(alias, home);
        assertThrows(
                IllegalStateException.class, () -> McpGeometryRollbackProbe.requireTaskIdentity(alias, fixture, RUN));
    }

    @Test
    void closedRequestRejectsForeignRunsWrongKindsAndArbitraryEdits() {
        final var targets = List.of("art_mesh", "warp_deformer", "rotation_deformer").stream()
                .map(kind -> Map.of("kind", kind, "objectId", kind, "sourceGuid", kind, "formGuid", "edit"))
                .toList();
        final var root = new HashMap<String, Object>(
                Map.of("runId", RUN, "documentId", "doc", "modelId", "model", "targets", targets));
        assertEquals(3, McpGeometryRollbackProbe.parse(root, RUN).targets().size());
        assertThrows(IllegalStateException.class, () -> McpGeometryRollbackProbe.parse(root, "queue-foreign"));
        root.put("edits", List.of());
        assertThrows(IllegalStateException.class, () -> McpGeometryRollbackProbe.parse(root, RUN));
        root.remove("edits");
        final var reversed = new ArrayList<>(targets);
        Collections.reverse(reversed);
        root.put("targets", reversed);
        assertThrows(IllegalStateException.class, () -> McpGeometryRollbackProbe.parse(root, RUN));
    }

    @Test
    void eachInjectedBoundaryRequiresRealWritesAndFullRestoration() throws Exception {
        final var fixture = new Fixture("success");
        final var report = McpGeometryRollbackProbe.exercise(fixture.facade, fixture.request);
        assertEquals(true, report.get("allSnapshotsRestored"));
        assertEquals(6, fixture.writes);
        assertEquals(3, ((List<?>) report.get("attempts")).size());
    }

    @Test
    void typedRollbackLabelAloneDoesNotProveSetterOrRestoration() {
        for (final String mode :
                List.of("no_callback", "no_setter", "corrupt_untouched", "recovery_failed", "wrong_scope")) {
            final var fixture = new Fixture(mode);
            assertThrows(
                    IllegalStateException.class,
                    () -> McpGeometryRollbackProbe.exercise(fixture.facade, fixture.request),
                    mode);
        }
    }

    @org.junit.jupiter.api.Test
    void scaledShiftUsesLocalExtentAndPreservesY() {
        for (float scale : new float[] {0.01f, 1f, 1000f}) {
            var input = List.of(new Point2(0, 1), new Point2(scale, 2));
            var output = McpGeometryRollbackProbe.scaledShift(input);
            org.junit.jupiter.api.Assertions.assertEquals(input.size(), output.size());
            for (int i = 0; i < input.size(); i++) {
                org.junit.jupiter.api.Assertions.assertEquals(
                        input.get(i).y(), output.get(i).y());
                double delta = output.get(i).x() - input.get(i).x();
                org.junit.jupiter.api.Assertions.assertTrue(delta > 0 && delta <= scale / 128);
            }
        }
        org.junit.jupiter.api.Assertions.assertThrows(
                IllegalStateException.class,
                () -> McpGeometryRollbackProbe.scaledShift(List.of(new Point2(0, 0), new Point2(0, 1))));
    }

    private static final class Fixture {
        Object mesh;
        Object warp;
        Object rotation;
        int writes;
        final CubismFacade facade;
        final McpGeometryRollbackProbe.Request request;

        Fixture(final String mode) {
            final var meshId = new ArtMeshId("mesh");
            final var warpId = new DeformerId("warp");
            final var rotationId = new DeformerId("rotation");
            final var parameterId = new ParameterId("angle");
            final var positions = List.of(new Point2(0, 0));
            mesh = new ArtMeshAuthoringSnapshot(
                    meshId,
                    "mesh-source",
                    new ArtMeshGeometry(positions, positions, List.of()),
                    List.of(new ArtMeshAuthoringSnapshot.Axis("axis", parameterId, 0, 0, 1, List.of(0f, 1f))),
                    List.of(new ArtMeshAuthoringSnapshot.Cell(
                            "edit", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis", 1)))),
                    List.of(new ArtMeshAuthoringSnapshot.Form("edit", positions, 10)),
                    List.of());
            final var controls = List.of(new Point2(0, 0), new Point2(0, 1), new Point2(1, 0), new Point2(1, 1));
            warp = new WarpAuthoringSnapshot(
                    warpId,
                    "warp-source",
                    1,
                    1,
                    false,
                    List.of(new WarpAuthoringSnapshot.Axis("axis", parameterId, 0, 0, 1, List.of(0f, 1f))),
                    List.of(new WarpAuthoringSnapshot.Cell(
                            "edit", List.of(new WarpAuthoringSnapshot.Coordinate("axis", 1)))),
                    List.of(new WarpAuthoringSnapshot.Form("edit", controls)));
            rotation = new RotationAuthoringSnapshot(
                    rotationId,
                    "rotation-source",
                    0,
                    List.of(new RotationAuthoringSnapshot.Axis("axis", parameterId, 0, 0, 1, List.of(0f, 1f))),
                    List.of(new RotationAuthoringSnapshot.Cell(
                            "edit", List.of(new RotationAuthoringSnapshot.Coordinate("axis", 1)))),
                    List.of(new RotationAuthoringSnapshot.Form(
                            "edit", new RotationDeformerForm(0, 0, 0, 1, false, false))));
            final var drawable = proxy(Drawable.class, (name, args) -> {
                if (name.equals("authoringSnapshot")) return mesh;
                if (name.equals("setOrdinaryKeyformPositions")) {
                    writes++;
                    if (mode.equals("no_setter")) return null;
                    final var old = (ArtMeshAuthoringSnapshot) mesh;
                    final var edit = (ArtMeshKeyformPositions) ((List<?>) args[1]).get(0);
                    mesh = new ArtMeshAuthoringSnapshot(
                            old.artMeshId(),
                            old.sourceGuid(),
                            old.sourceGeometry(),
                            old.axes(),
                            old.cells(),
                            List.of(new ArtMeshAuthoringSnapshot.Form("edit", edit.positions(), 10)),
                            List.of());
                    return null;
                }
                throw new AssertionError(name);
            });
            final var warpObject = proxy(WarpDeformer.class, (name, args) -> {
                if (name.equals("authoringSnapshot")) return warp;
                if (name.equals("setOrdinaryKeyformPositions")) {
                    writes++;
                    final var old = (WarpAuthoringSnapshot) warp;
                    final var edit = (WarpKeyformPositions) ((List<?>) args[1]).get(0);
                    warp = new WarpAuthoringSnapshot(
                            old.deformerId(),
                            old.sourceGuid(),
                            old.rows(),
                            old.columns(),
                            old.quadTransform(),
                            old.axes(),
                            old.cells(),
                            List.of(new WarpAuthoringSnapshot.Form("edit", edit.controlPoints())));
                    return null;
                }
                throw new AssertionError(name);
            });
            final var rotationObject = proxy(RotationDeformer.class, (name, args) -> {
                if (name.equals("authoringSnapshot")) return rotation;
                if (name.equals("setOrdinaryKeyformForms")) {
                    writes++;
                    final var old = (RotationAuthoringSnapshot) rotation;
                    final var edit = (RotationKeyformForm) ((List<?>) args[1]).get(0);
                    rotation = new RotationAuthoringSnapshot(
                            old.deformerId(),
                            old.sourceGuid(),
                            old.baseAngle(),
                            old.axes(),
                            old.cells(),
                            List.of(new RotationAuthoringSnapshot.Form("edit", edit.value())));
                    return null;
                }
                throw new AssertionError(name);
            });
            final var drawables = proxy(Drawables.class, (name, args) -> {
                if (name.equals("find")) return drawable;
                throw new AssertionError(name);
            });
            final var deformers = proxy(Deformers.class, (name, args) -> {
                if (name.equals("find")) return args[0].equals(warpId) ? warpObject : rotationObject;
                throw new AssertionError(name);
            });
            final var model = proxy(CubismModel.class, (name, args) -> switch (name) {
                case "drawables" -> drawables;
                case "deformers" -> deformers;
                default -> throw new AssertionError(name);
            });
            final var access = proxy(CubismModelAccess.class, (name, args) -> {
                if (name.equals("active")) return model;
                throw new AssertionError(name);
            });
            final var history =
                    new HistorySnapshot(HistorySnapshot.Availability.AVAILABLE, 1, 1, 0, List.of(), false, false);
            final var historyService = proxy(CubismHistory.class, (name, args) -> {
                if (name.equals("snapshot")) return history;
                throw new AssertionError(name);
            });
            final var transactions = proxy(AuthoringTransactionService.class, (name, args) -> {
                if (!name.equals("execute")) throw new AssertionError(name);
                final var originalMesh = mesh;
                final var originalWarp = warp;
                final var originalRotation = rotation;
                if (!mode.equals("no_callback")) {
                    try {
                        ((AuthoringTransactionWork<?>) args[1]).run();
                        fail("Expected injected callback failure");
                    } catch (Exception expected) {
                        /* Transaction backend mock recovers state; production probe must prove it. */
                    }
                }
                mesh = originalMesh;
                warp = originalWarp;
                rotation = originalRotation;
                if (mode.equals("corrupt_untouched")) {
                    final var old = (RotationAuthoringSnapshot) rotation;
                    rotation = new RotationAuthoringSnapshot(
                            old.deformerId(), old.sourceGuid(), 10, old.axes(), old.cells(), old.forms());
                }
                final var transactionHistory = new HistorySnapshot(
                        HistorySnapshot.Availability.AVAILABLE,
                        41,
                        73,
                        0,
                        List.of(),
                        false,
                        false,
                        "transaction-document",
                        "transaction-manager");
                final var receipt = new AuthoringTransactionReceipt(
                        "tx", "probe", transactionHistory, transactionHistory, Optional.empty());
                return mode.equals("recovery_failed")
                        ? AuthoringTransactionResult.recoveryFailed(receipt, "diagnostic")
                        : AuthoringTransactionResult.rolledBack(receipt, "diagnostic");
            });
            final var document = new DocumentSnapshot(
                    mode.equals("wrong_scope") ? "foreign" : "doc",
                    "fixture",
                    "fixture.cmo3",
                    Optional.empty(),
                    Optional.of(new ModelSnapshot("model", "model", List.of(), List.of(), List.of(), List.of())));
            facade = proxy(CubismFacade.class, (name, args) -> switch (name) {
                case "activeDocument" -> Optional.of(document);
                case "model" -> access;
                case "history" -> historyService;
                case "authoringTransactions" -> transactions;
                default -> throw new AssertionError(name);
            });
            request = new McpGeometryRollbackProbe.Request(
                    RUN,
                    "doc",
                    "model",
                    List.of(
                            new McpGeometryRollbackProbe.Target("art_mesh", "mesh", "mesh-source", "edit"),
                            new McpGeometryRollbackProbe.Target("warp_deformer", "warp", "warp-source", "edit"),
                            new McpGeometryRollbackProbe.Target(
                                    "rotation_deformer", "rotation", "rotation-source", "edit")));
        }
    }

    private static <T> T proxy(final Class<T> type, final BiFunction<String, Object[], Object> handler) {
        return type.cast(Proxy.newProxyInstance(
                type.getClassLoader(),
                new Class<?>[] {type},
                (object, method, args) -> handler.apply(method.getName(), args)));
    }
}
