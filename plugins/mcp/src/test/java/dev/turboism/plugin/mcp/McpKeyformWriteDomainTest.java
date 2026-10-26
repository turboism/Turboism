package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.*;
import dev.turboism.sdk.cubism.history.*;
import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import org.junit.jupiter.api.Test;

final class McpKeyformWriteDomainTest {
    private static final List<Point2> POINTS = List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1));
    private static final List<Point2> CONTROLS =
            List.of(new Point2(0, 0), new Point2(1, 0), new Point2(0, 1), new Point2(1, 1));
    private static final RotationDeformerForm NEUTRAL = new RotationDeformerForm(0, 0, 0, 1, false, false);

    @Test
    void orderOnlyAndCombinedSelectExactSdkWriterAndRetainOneTransaction() {
        for (boolean combined : List.of(false, true)) {
            var f = new Fixture();
            var before = f.mesh;
            var request = f.request("order", "mesh");
            meshEdit(request, combined, 700);
            var output = f.write(request);
            assertEquals("APPLIED", output.get("outcome"), output.toString());
            assertEquals(combined ? "setOrdinaryKeyforms" : "setOrdinaryKeyformDrawOrders", f.lastMeshMethod);
            assertEquals(700, f.mesh.forms().get(1).drawOrder());
            assertEquals(
                    combined ? new Point2(.25F, 0) : POINTS.get(0),
                    f.mesh.forms().get(1).positions().get(0));
            assertEquals(before.sourceGeometry(), f.mesh.sourceGeometry());
            assertEquals(before.forms().get(0), f.mesh.forms().get(0));
            assertEquals(1, f.transactions);
            assertEquals(1, f.writes);
            assertEquals(output, f.write(request));
            assertEquals(1, f.writes);
            var after = f.mesh;
            f.undo();
            assertEquals(before, f.mesh);
            f.redo();
            assertEquals(after, f.mesh);
        }
    }

    @Test
    void orderChangesRollbackWithOtherObjectFailureAndRejectUnrelatedReadbackChanges() {
        var f = new Fixture();
        var before = f.mesh;
        var request = f.request("rollback-order", "mesh", "warp");
        meshEdit(request, true, 800);
        f.failWrite = 2;
        assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
        assertEquals(before, f.mesh);
        var g = new Fixture();
        request = g.request("readback-order", "mesh");
        meshEdit(request, false, 800);
        g.afterCommit = () -> {
            var b = g.mesh;
            g.mesh = new ArtMeshAuthoringSnapshot(
                    b.artMeshId(),
                    b.sourceGuid(),
                    b.sourceGeometry(),
                    b.axes(),
                    b.cells(),
                    List.of(
                            new ArtMeshAuthoringSnapshot.Form("neutral", POINTS, 999),
                            b.forms().get(1)),
                    b.morphKeys());
        };
        assertEquals("APPLIED_WITH_READBACK_WARNING", g.write(request).get("outcome"));
    }

    @Test
    void equalOrderIsNoChangeAndInvalidOrderIsRejectedBeforeMutation() {
        var f = new Fixture();
        var request = f.request("same-order", "mesh");
        meshEdit(request, false, 101);
        assertEquals("NO_CHANGE", f.write(request).get("outcome"));
        assertEquals(0, f.writes);
        for (Object bad : List.of(-1, 1001, .25, "500")) {
            var g = new Fixture();
            var invalid = g.request("bad-order", "mesh");
            meshEdit(invalid, false, bad);
            assertThrows(IllegalArgumentException.class, () -> g.write(invalid));
            assertEquals(0, g.writes);
            assertEquals(0, g.transactions);
        }
    }

    @SuppressWarnings("unchecked")
    private static void meshEdit(Map<String, Object> request, boolean keepPositions, Object order) {
        var target = (Map<String, Object>) ((List<?>) request.get("targets")).get(0);
        var old = (Map<String, Object>) ((List<?>) target.get("edits")).get(0);
        var edit = new LinkedHashMap<>(old);
        if (!keepPositions) edit.remove("positions");
        edit.put("drawOrder", order);
        target.put("edits", List.of(edit));
    }

    @Test
    void writesThreeTypedObjectsInOneTransactionWithFullReadbackAndStableResult() {
        var f = new Fixture();
        var before = List.of(f.mesh, f.warp, f.rotation);
        var request = f.request("operation-1", "mesh", "warp", "rotation");
        var output = f.write(request);
        assertEquals("APPLIED", output.get("outcome"), output.toString());
        assertEquals(1, f.transactions);
        assertEquals(3, f.writes);
        assertNotEquals(before, List.of(f.mesh, f.warp, f.rotation));
        assertEquals(new Point2(.25F, 0), f.mesh.forms().get(1).positions().get(0));
        assertEquals(new Point2(.5F, 0), f.warp.forms().get(1).controlPoints().get(0));
        assertEquals(12, f.rotation.forms().get(1).value().angle());
        assertEquals(20, f.rotation.baseAngle());
        assertEquals(POINTS, f.mesh.sourceGeometry().positions());
        assertEquals(POINTS, f.mesh.forms().get(0).positions());
        assertEquals(101, f.mesh.forms().get(1).drawOrder());
        assertEquals(3, ((List<?>) output.get("targets")).size());
        assertTrue(((List<?>) output.get("targets"))
                .stream().allMatch(v -> Boolean.TRUE.equals(((Map<?, ?>) v).get("readbackVerified"))));
        assertNotNull(output.get("receipt"));
        assertEquals(output, f.result("operation-1"));
        assertEquals(
                output,
                f.write(request),
                "Retry returns committed receipt even though original snapshots were invalidated");
        assertEquals(1, f.transactions);
        assertEquals(3, f.writes);
        var committed = List.of(f.mesh, f.warp, f.rotation);
        f.undo();
        assertEquals(before, List.of(f.mesh, f.warp, f.rotation));
        f.redo();
        assertEquals(committed, List.of(f.mesh, f.warp, f.rotation));
        assertEquals(output, f.result("operation-1"), "Receipt remains historical after Undo/Redo");
    }

    @Test
    void rejectsChangedGeometryEvenWithUnchangedHistoryAndChecksAllObjectsBeforeFirstSetter() {
        var f = new Fixture();
        var request = f.request("stale", "mesh", "warp");
        var w = f.warp;
        f.warp = new WarpAuthoringSnapshot(
                w.deformerId(), "new-source", w.rows(), w.columns(), w.quadTransform(), w.axes(), w.cells(), w.forms());
        assertCode(f.write(request), "STALE_STATE");
        assertEquals(0, f.transactions);
        assertEquals(0, f.writes);
    }

    @Test
    void opacityAndGeometryUseOneTypedCallAndReplayDoesNotRewrite() {
        var f = new Fixture();
        f.mesh = opacity(f.mesh, 1);
        var request = f.request("opacity-and-positions", "mesh");
        addOpacity(request, .25);
        var result = f.write(request);
        assertEquals("APPLIED", result.get("outcome"), result.toString());
        assertEquals("setOrdinaryKeyformValues", f.lastMeshMethod);
        assertEquals(Optional.of(.25F), f.mesh.forms().get(1).opacity());
        assertEquals(Optional.of(1F), f.mesh.forms().get(0).opacity());
        assertEquals(1, f.writes);
        assertEquals(result, f.write(request));
        assertEquals(1, f.writes);
    }

    @Test
    void opacityUnknownRejectsBeforeTransactionAndMultiObjectFailureRestoresIt() {
        var unknown = new Fixture();
        var request = unknown.request("unknown-opacity", "mesh");
        addOpacity(request, .5);
        assertCode(unknown.write(request), "UNAVAILABLE");
        assertEquals(0, unknown.transactions);
        assertEquals(0, unknown.writes);
        var f = new Fixture();
        f.mesh = opacity(f.mesh, .75F);
        var before = f.mesh;
        var combined = f.request("opacity-rollback", "mesh", "warp");
        addOpacity(combined, .25);
        f.failWrite = 2;
        assertEquals("ROLLED_BACK", f.write(combined).get("outcome"));
        assertEquals(before, f.mesh);
    }

    @SuppressWarnings("unchecked")
    private static void addOpacity(Map<String, Object> request, Object value) {
        var target = (Map<String, Object>) ((List<?>) request.get("targets")).get(0);
        var edits = new ArrayList<Map<String, Object>>();
        for (Object raw : (List<?>) target.get("edits")) {
            var edit = new LinkedHashMap<>((Map<String, Object>) raw);
            edit.put("opacity", value);
            edits.add(edit);
        }
        target.put("edits", edits);
    }

    @Test
    void opacityOnlyChangeInvalidatesFullCasWithoutAHistoryRevisionChange() {
        for (boolean known : List.of(false, true)) {
            var f = new Fixture();
            if (known) f.mesh = opacity(f.mesh, .25F);
            var request = f.request("opacity-stale", "mesh", "warp");
            f.mesh = opacity(f.mesh, .75F);
            assertCode(f.write(request), "STALE_STATE");
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    private static ArtMeshAuthoringSnapshot opacity(ArtMeshAuthoringSnapshot b, float opacity) {
        return new ArtMeshAuthoringSnapshot(
                b.artMeshId(),
                b.sourceGuid(),
                b.sourceGeometry(),
                b.axes(),
                b.cells(),
                b.forms().stream()
                        .map(form -> new ArtMeshAuthoringSnapshot.Form(
                                form.formGuid(), form.positions(), form.drawOrder(), Optional.of(opacity)))
                        .toList(),
                b.morphKeys());
    }

    @Test
    void checksHistorySessionScopeParentAndObjectIdentity() {
        for (String change : List.of("history", "parent", "document", "model", "missing")) {
            var f = new Fixture();
            var request = f.request("stale", "mesh");
            switch (change) {
                case "history" -> f.revision++;
                case "parent" -> f.parent = "new-parent";
                case "document" -> f.documentId = "other-document";
                case "model" -> f.modelId = "other-model";
                default -> f.present = false;
            }
            assertEquals("NOT_APPLIED", f.write(request).get("outcome"), change);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
        var f = new Fixture();
        var request = McpRequestRegistry.inSession("a", () -> f.request("session-op", "mesh"));
        assertCode(McpRequestRegistry.inSession("b", () -> f.write(request)), "NOT_FOUND");
        assertEquals(
                "APPLIED",
                McpRequestRegistry.inSession("a", () -> f.write(request)).get("outcome"));
        assertCode(McpRequestRegistry.inSession("b", () -> f.result("session-op")), "NOT_FOUND");
    }

    @Test
    void staleStateAtTransactionEntryRejectsBeforeFirstSetter() {
        var f = new Fixture();
        var request = f.request("entry-race", "mesh", "warp");
        f.beforeWork = () -> f.parent = "changed-parent";
        assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
        assertEquals(1, f.transactions);
        assertEquals(0, f.writes);
    }

    @Test
    void secondObjectFailureRollsBackWholeGroupAndCachesTheRollback() {
        var f = new Fixture();
        var before = List.of(f.mesh, f.warp, f.rotation);
        var request = f.request("rollback", "mesh", "warp", "rotation");
        f.failWrite = 2;
        var output = f.write(request);
        assertEquals("ROLLED_BACK", output.get("outcome"), output.toString());
        assertEquals("simulated.rollback", output.get("diagnosticId"));
        assertEquals(before, List.of(f.mesh, f.warp, f.rotation));
        assertEquals(2, f.writes);
        assertEquals(1, f.transactions);
        assertEquals(output, f.write(request));
        assertEquals(2, f.writes);
    }

    @Test
    void unintendedChangesAndReadbackFailureInsideScopeForceRollback() {
        for (String mode : List.of("corrupt", "read-fails")) {
            var f = new Fixture();
            var before = f.mesh;
            var request = f.request("verify", "mesh");
            f.afterSetter = () -> {
                if (mode.equals("read-fails")) f.readFailure = true;
                else
                    f.mesh = new ArtMeshAuthoringSnapshot(
                            f.mesh.artMeshId(),
                            "corrupted-source",
                            f.mesh.sourceGeometry(),
                            f.mesh.axes(),
                            f.mesh.cells(),
                            f.mesh.forms(),
                            f.mesh.morphKeys());
            };
            assertEquals("ROLLED_BACK", f.write(request).get("outcome"));
            assertEquals(before, f.mesh);
        }
    }

    @Test
    void lostTransactionResponseIsUnknownAndIsNeverAutomaticallyReplayed() {
        var f = new Fixture();
        var request = f.request("lost", "mesh");
        f.loseResult = true;
        var output = f.write(request);
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals(new Point2(.25F, 0), f.mesh.forms().get(1).positions().get(0));
        assertEquals(output, f.result("lost"));
        assertEquals(output, f.write(request));
        assertEquals(1, f.transactions);
        assertEquals(1, f.writes);
    }

    @Test
    void unavailableAndPermissionFailuresDoNotFallBackToStandaloneSetters() {
        var f = new Fixture();
        var request = f.request("unavailable", "mesh");
        f.unavailable = true;
        assertEquals("NOT_APPLIED", f.write(request).get("outcome"));
        assertEquals(0, f.writes);
        assertEquals(
                "cubism.authoring.transactions.unavailable",
                f.result("unavailable").get("diagnosticId"));
        var denied = new Fixture();
        var deniedRequest = denied.request("denied", "mesh");
        denied.readDenied = true;
        assertCode(denied.write(deniedRequest), "PERMISSION_DENIED");
        assertEquals(0, denied.transactions);
        assertEquals(0, denied.writes);
    }

    @Test
    void postCommitReadFailureIsWarningAndResultQueryDoesNotReadHost() {
        var f = new Fixture();
        var request = f.request("warning", "mesh");
        f.afterCommit = () -> f.readFailure = true;
        var output = f.write(request);
        assertEquals("APPLIED_WITH_READBACK_WARNING", output.get("outcome"));
        assertEquals(true, output.get("ok"));
        assertNotNull(output.get("receipt"));
        assertEquals(output, f.result("warning"));
        assertEquals(output, f.write(request));
        assertEquals(1, f.writes);
    }

    @Test
    void resultQuerySeesRunningWhileHostWorkIsStillActive() {
        var f = new Fixture();
        var request = f.request("running", "mesh");
        f.afterSetter = () -> assertEquals("RUNNING", f.result("running").get("outcome"));
        assertEquals("APPLIED", f.write(request).get("outcome"));
        assertEquals("OUTCOME_UNKNOWN", f.result("missing").get("outcome"));
    }

    @Test
    void operationIdentityConflictCannotWriteAgainAndMapOrderingDoesNotConflict() {
        var f = new Fixture();
        var request = f.request("unique", "mesh");
        var output = f.write(request);
        var reordered = new LinkedHashMap<String, Object>();
        request.keySet().stream().sorted().forEach(k -> reordered.put(k, request.get(k)));
        assertEquals(output, f.write(reordered));
        var changed = new HashMap<>(request);
        changed.put("documentId", "different");
        assertCode(f.write(changed), "OPERATION_ID_CONFLICT");
        assertEquals(1, f.writes);
    }

    @Test
    void rejectsInvalidLastTargetDuplicateObjectAndCrossTypeFieldsBeforeTransaction() {
        var f = new Fixture();
        var request = f.request("invalid", "mesh", "warp");
        var targets = new ArrayList<Object>((List<?>) request.get("targets"));
        var last = new HashMap<>((Map<String, Object>) targets.get(1));
        last.put("edits", List.of(Map.of("bad", true)));
        targets.set(1, last);
        var bad = new HashMap<>(request);
        bad.put("targets", targets);
        assertThrows(IllegalArgumentException.class, () -> f.write(bad));
        assertEquals(0, f.transactions);
        assertEquals(0, f.writes);
        bad.put("targets", List.of(targets.get(0), targets.get(0)));
        assertCode(f.write(bad), "INVALID_ARGUMENT");
        assertEquals(0, f.transactions);
        assertFalse(f.tools.registration(McpKeyformWriteDomain.WRITE).transactionEligible());
    }

    @Test
    void noChangeProducesNoUndoGroupAndRetainedResultDoesNotEvictWhenFull() {
        var f = new Fixture();
        f.noChange = true;
        for (int i = 0; i < McpKeyformWriteDomain.MAX_OPERATIONS; i++) {
            var request = f.request("retained-" + i, "mesh");
            assertEquals("NO_CHANGE", f.write(request).get("outcome"));
        }
        assertEquals("NO_CHANGE", f.result("retained-0").get("outcome"));
        var full = f.write(f.request("overflow", "mesh"));
        assertCode(full, "RESULT_STORE_FULL");
        assertEquals("NOT_APPLIED", full.get("outcome"));
        assertEquals(McpKeyformWriteDomain.MAX_OPERATIONS, f.transactions);
        assertNull(((Map<?, ?>) f.result("retained-0").get("receipt")).get("historyEntryId"));
    }

    @Test
    void historyChangingDuringPreflightReadsCannotProduceAConsistentWrite() {
        var f = new Fixture();
        var request = f.request("read-race", "mesh", "warp");
        f.onRead = () -> f.revision++;
        assertCode(f.write(request), "STALE_STATE");
        assertEquals(0, f.transactions);
        assertEquals(0, f.writes);
    }

    @Test
    void recoveryFailureIsUnknownAndRetainsItsNativeReceiptAndDiagnostic() {
        var f = new Fixture();
        var request = f.request("failed-recovery", "mesh", "warp");
        f.failWrite = 2;
        f.recoveryFailed = true;
        var output = f.write(request);
        assertEquals("OUTCOME_UNKNOWN", output.get("outcome"));
        assertEquals("RECOVERY_FAILED", output.get("transactionOutcome"));
        assertEquals("simulated.recovery-failed", output.get("diagnosticId"));
        assertNotNull(output.get("receipt"));
        assertEquals(output, f.write(request));
        assertEquals(1, f.transactions);
    }

    @Test
    void cancelBeforeAdmissionDoesNotWriteAndCancelAfterAdmissionDoesNotPretendToRollback() {
        var registry = new McpRequestRegistry();
        var before = new Fixture();
        try (var scope = registry.enter("cancel-before", 1)) {
            var request = before.request("before", "mesh");
            assertTrue(registry.cancel("cancel-before", 1));
            assertThrows(java.util.concurrent.CancellationException.class, () -> before.write(request));
            assertEquals(0, before.transactions);
            assertEquals(0, before.writes);
        }
        var after = new Fixture();
        try (var scope = registry.enter("cancel-after", 2)) {
            var request = after.request("after", "mesh", "warp");
            after.afterSetter = () -> registry.cancel("cancel-after", 2);
            assertEquals("APPLIED", after.write(request).get("outcome"));
            assertEquals(1, after.transactions);
            assertEquals(2, after.writes);
        }
        assertEquals(
                "APPLIED",
                McpRequestRegistry.inSession("cancel-after", () -> after.result("after"))
                        .get("outcome"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewThreeObjectsReportsExactDiffWithoutResolvingTransactionsAndCanThenWrite() {
        var f = new Fixture();
        var request = f.request("preview-all", "mesh", "warp", "rotation");
        var original = List.of(f.mesh, f.warp, f.rotation);
        var preview = f.call(McpKeyformWriteDomain.PREVIEW, request);
        assertEquals(true, preview.get("ok"), preview.toString());
        assertEquals(false, preview.get("applied"));
        assertEquals("NOT_CHECKED", preview.get("writeAdmission"));
        assertEquals(false, preview.get("reservedByPreview"));
        assertEquals(3, ((Number) preview.get("requestedForms")).intValue());
        assertEquals(7, ((Number) preview.get("budgetedPoints")).intValue());
        var targets = (List<Map<String, Object>>) preview.get("targets");
        assertEquals(.25, ((Number) targets.get(0).get("maxPointDisplacement")).doubleValue());
        assertEquals(.5, ((Number) targets.get(1).get("maxPointDisplacement")).doubleValue());
        assertEquals(12., ((Number) targets.get(2).get("maxAngleDelta")).doubleValue());
        assertEquals(Math.sqrt(13), ((Number) targets.get(2).get("maxOriginDisplacement")).doubleValue());
        assertEquals(.25, ((Number) targets.get(2).get("maxScaleDelta")).doubleValue());
        assertEquals(1, ((Number) targets.get(2).get("changedReflectionFlags")).intValue());
        assertEquals(
                List.of(Map.of(
                        "formGuid", "target", "changedFields", List.of("angle", "origin", "scale", "reflectedX"))),
                targets.get(2).get("forms"));
        assertEquals(preview, f.call(McpKeyformWriteDomain.PREVIEW, request));
        assertCode(f.result("preview-all"), "NOT_FOUND");
        assertEquals(original, List.of(f.mesh, f.warp, f.rotation));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactionLookups);
        var written = f.write(request);
        assertEquals("APPLIED", written.get("outcome"), written.toString());
        var actual = (List<Map<String, Object>>) written.get("targets");
        for (int i = 0; i < 3; i++) {
            assertEquals(targets.get(i).get("beforeHash"), actual.get(i).get("beforeHash"));
            assertEquals(targets.get(i).get("expectedAfterHash"), actual.get(i).get("expectedAfterHash"));
        }
        assertEquals(
                "OPERATION_ID_ALREADY_ADMITTED",
                f.call(McpKeyformWriteDomain.PREVIEW, request).get("error"));
        assertEquals(written, f.write(request));
        assertEquals(3, f.writes);
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewNoOpOrderAndOpacityReportRetainedPointBudgets() {
        for (String kind : List.of("noop", "order", "opacity")) {
            var f = new Fixture();
            f.noChange = true;
            f.mesh = opacity(f.mesh, 1);
            var request = f.request("preview-" + kind, "mesh");
            if (kind.equals("order")) meshEdit(request, false, 102);
            if (kind.equals("opacity")) {
                meshEdit(request, false, 101);
                addOpacity(request, .5);
            }
            var result = f.call(McpKeyformWriteDomain.PREVIEW, request);
            assertEquals(true, result.get("ok"), result.toString());
            var row = ((List<Map<String, Object>>) result.get("targets")).get(0);
            assertEquals(kind.equals("noop") ? 0 : 1, ((Number) row.get("changedForms")).intValue());
            assertEquals(0, ((Number) row.get("changedPoints")).intValue());
            assertEquals(kind.equals("order") ? 0 : 3, ((Number) row.get("budgetedPoints")).intValue());
            assertEquals(kind.equals("order") ? 1 : 0, ((Number) row.get("changedDrawOrders")).intValue());
            assertEquals(kind.equals("opacity") ? 1 : 0, ((Number) row.get("changedOpacities")).intValue());
            assertEquals(0, f.transactionLookups);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void previewRejectsStaleScopeParentNativeStateAndMutationDuringSecondVerification() {
        for (String change : List.of("history", "parent", "document", "model", "missing", "native", "secondRead")) {
            var f = new Fixture();
            var request = f.request("preview-stale", "mesh");
            switch (change) {
                case "history" -> f.revision++;
                case "parent" -> f.parent = "other";
                case "document" -> f.documentId = "other";
                case "model" -> f.modelId = "other";
                case "missing" -> f.present = false;
                case "native" -> f.mesh = opacity(f.mesh, .5F);
                default -> {
                    int[] reads = {0};
                    f.onRead = () -> {
                        if (++reads[0] == 2) f.revision++;
                    };
                }
            }
            assertEquals(false, f.call(McpKeyformWriteDomain.PREVIEW, request).get("ok"), change);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactionLookups);
        }
        var f = new Fixture();
        var request = McpRequestRegistry.inSession("a", () -> f.request("session-preview", "mesh"));
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("b", () -> f.call(McpKeyformWriteDomain.PREVIEW, request))
                        .get("error"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void previewReusesClosedTypedOrdinaryPreflightAndRejectsUnknownOpacity() {
        for (String invalid : List.of("default", "tuple", "crossType", "duplicate", "unknownOpacity")) {
            var f = new Fixture();
            var request = f.request("preview-invalid", "mesh");
            var targets = (List<Map<String, Object>>) request.get("targets");
            var edit = ((List<Map<String, Object>>) targets.get(0).get("edits")).get(0);
            switch (invalid) {
                case "default" -> {
                    edit.put("formGuid", "neutral");
                    edit.put("coordinates", List.of(Map.of("parameterGuid", "axis-guid", "value", 0)));
                }
                case "tuple" -> edit.put("coordinates", List.of());
                case "crossType" -> edit.put("controlPoints", edit.get("positions"));
                case "duplicate" -> request.put("targets", List.of(targets.get(0), targets.get(0)));
                default -> addOpacity(request, .5);
            }
            if (invalid.equals("tuple") || invalid.equals("crossType"))
                assertThrows(IllegalArgumentException.class, () -> f.call(McpKeyformWriteDomain.PREVIEW, request));
            else
                assertEquals(
                        false, f.call(McpKeyformWriteDomain.PREVIEW, request).get("ok"), invalid);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactionLookups);
        }
    }

    @Test
    void previewRejectsSharedMorphAndExpiredSnapshotsWithoutTransactionLookup() {
        for (String kind : List.of("shared", "morph", "expired")) {
            var f = new Fixture();
            var b = f.mesh;
            if (kind.equals("shared"))
                f.mesh = new ArtMeshAuthoringSnapshot(
                        b.artMeshId(),
                        b.sourceGuid(),
                        b.sourceGeometry(),
                        List.of(new ArtMeshAuthoringSnapshot.Axis(
                                "axis-guid", new ParameterId("display"), 0, 0, 2, List.of(0F, 1F, 2F))),
                        List.of(
                                b.cells().get(0),
                                b.cells().get(1),
                                new ArtMeshAuthoringSnapshot.Cell(
                                        "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 2)))),
                        b.forms(),
                        List.of());
            if (kind.equals("morph"))
                f.mesh = new ArtMeshAuthoringSnapshot(
                        b.artMeshId(),
                        b.sourceGuid(),
                        b.sourceGeometry(),
                        b.axes(),
                        b.cells(),
                        b.forms(),
                        List.of(new ArtMeshAuthoringSnapshot.MorphKey("morph", "target", Optional.empty())));
            var request = f.request("preview-guard", "mesh");
            if (kind.equals("expired")) f.snapshotClock = McpAuthoringSnapshotStore.TTL_NANOS;
            var output = f.call(McpKeyformWriteDomain.PREVIEW, request);
            assertEquals(false, output.get("ok"), kind);
            assertEquals(kind.equals("expired") ? "NOT_FOUND" : "INVALID_ARGUMENT", output.get("error"));
            assertEquals(0, f.writes);
            assertEquals(0, f.transactionLookups);
        }
    }

    private static void assertCode(Map<String, Object> output, String code) {
        assertEquals(code, output.get("code"), output.toString());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (o, m, a) -> body.apply(m.getName(), a));
    }

    private static final class Fixture {
        ArtMeshAuthoringSnapshot mesh = mesh();
        WarpAuthoringSnapshot warp = warp();
        RotationAuthoringSnapshot rotation = rotation();
        String documentId = "document-1", modelId = "model-1", parent;
        boolean present = true, readFailure, readDenied, unavailable, loseResult, noChange, recoveryFailed;
        int writes, transactions, failWrite, transactionLookups;
        long revision;
        Runnable beforeWork = () -> {}, afterSetter = () -> {}, afterCommit = () -> {}, onRead = () -> {};
        List<Object> undoBefore, undoAfter;
        long snapshotClock;
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> snapshotClock);
        final Drawable drawable = proxy(Drawable.class, (m, a) -> object(m, a, "mesh"));
        final WarpDeformer warpHandle = proxy(WarpDeformer.class, (m, a) -> object(m, a, "warp"));
        final RotationDeformer rotationHandle = proxy(RotationDeformer.class, (m, a) -> object(m, a, "rotation"));
        final CubismModel model = proxy(CubismModel.class, (m, a) -> switch (m) {
            case "id" -> new ModelId(modelId);
            case "drawables" -> proxy(Drawables.class, (n, b) -> drawable);
            case "deformers" ->
                proxy(
                        Deformers.class,
                        (n, b) -> ((DeformerId) b[0]).value().equals("warp") ? warpHandle : rotationHandle);
            default -> throw new UnsupportedOperationException(m);
        });
        final AuthoringTransactionService transactionService = new AuthoringTransactionService() {
            @Override
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                transactions++;
                if (unavailable)
                    return AuthoringTransactionResult.unavailable("cubism.authoring.transactions.unavailable");
                var before = history();
                var original = List.of(mesh, warp, rotation);
                T value;
                try {
                    beforeWork.run();
                    value = work.run();
                } catch (Exception failure) {
                    if (recoveryFailed)
                        return AuthoringTransactionResult.recoveryFailed(
                                new AuthoringTransactionReceipt(
                                        "tx-" + transactions, options.label(), before, history(), Optional.empty()),
                                "simulated.recovery-failed");
                    restore(original);
                    readFailure = false;
                    return AuthoringTransactionResult.rolledBack(
                            new AuthoringTransactionReceipt(
                                    "tx-" + transactions, options.label(), before, history(), Optional.empty()),
                            "simulated.rollback");
                }
                var changed = !original.equals(List.of(mesh, warp, rotation));
                if (changed) {
                    revision++;
                    undoBefore = new ArrayList<>(original);
                    undoAfter = List.of(mesh, warp, rotation);
                }
                var receipt = new AuthoringTransactionReceipt(
                        "tx-" + transactions,
                        options.label(),
                        before,
                        history(),
                        changed ? Optional.of("entry-" + transactions) : Optional.empty());
                afterCommit.run();
                if (loseResult) throw new IllegalStateException("Simulated response loss after commit");
                return changed
                        ? AuthoringTransactionResult.committed(value, receipt)
                        : AuthoringTransactionResult.noChange(value, receipt);
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (m, a) -> switch (m) {
            case "activeModelDocument" ->
                present
                        ? Optional.of(new DocumentSnapshot(
                                documentId, "Model", "model.cmo3", Optional.empty(), Optional.of(modelSnapshot())))
                        : Optional.empty();
            case "activeModel" -> present ? Optional.of(modelSnapshot()) : Optional.empty();
            case "model" -> (CubismModelAccess) () -> model;
            case "history" -> proxy(CubismHistory.class, (n, b) -> history());
            case "authoringTransactions" -> {
                transactionLookups++;
                yield transactionService;
            }
            default -> throw new UnsupportedOperationException(m);
        });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable r) {
                r.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable r, Duration d) {
                throw new UnsupportedOperationException();
            }
        });
        final McpStoredAuthoringDomain reads = new McpStoredAuthoringDomain(facade, execution, store);
        final McpToolCatalog tools = McpToolCatalog.combine(
                reads.tools(), new McpKeyformWriteDomain(facade, execution, store, reads::context).tools());

        String lastMeshMethod;

        private Object object(String method, Object[] args, String kind) {
            if (method.equals("authoringSnapshot")) {
                if (readDenied) throw new SecurityException();
                if (readFailure) throw new IllegalStateException("Simulated read failure");
                onRead.run();
                return switch (kind) {
                    case "mesh" -> mesh;
                    case "warp" -> warp;
                    default -> rotation;
                };
            }
            if (method.equals("parentPartId")) return Optional.empty();
            if (method.equals("parentDeformerId"))
                return Optional.ofNullable(parent).map(DeformerId::new);
            if (method.equals("setOrdinaryKeyformPositions")
                    || method.equals("setOrdinaryKeyformForms")
                    || method.equals("setOrdinaryKeyformDrawOrders")
                    || method.equals("setOrdinaryKeyforms")
                    || method.equals("setOrdinaryKeyformValues")) {
                writes++;
                if (writes == failWrite) throw new IllegalStateException("Injected setter failure");
                if (kind.equals("mesh")) {
                    assertEquals(mesh, args[0]);
                    lastMeshMethod = method;
                    var positions = new HashMap<String, List<Point2>>();
                    var orders = new HashMap<String, Integer>();
                    var opacities = new HashMap<String, Float>();
                    if (method.equals("setOrdinaryKeyformValues")) {
                        for (var edit : (List<ArtMeshKeyformValues>) args[1]) {
                            edit.positions().ifPresent(value -> positions.put(edit.formGuid(), value));
                            edit.drawOrder().ifPresent(value -> orders.put(edit.formGuid(), value));
                            edit.opacity().ifPresent(value -> opacities.put(edit.formGuid(), value));
                        }
                    } else if (!method.equals("setOrdinaryKeyformDrawOrders"))
                        for (var edit : (List<ArtMeshKeyformPositions>) args[1])
                            positions.put(edit.formGuid(), edit.positions());
                    if (!method.equals("setOrdinaryKeyformPositions") && !method.equals("setOrdinaryKeyformValues"))
                        for (var edit :
                                (List<ArtMeshKeyformDrawOrder>) args[method.equals("setOrdinaryKeyforms") ? 2 : 1])
                            orders.put(edit.formGuid(), edit.drawOrder());
                    mesh = new ArtMeshAuthoringSnapshot(
                            mesh.artMeshId(),
                            mesh.sourceGuid(),
                            mesh.sourceGeometry(),
                            mesh.axes(),
                            mesh.cells(),
                            mesh.forms().stream()
                                    .map(f -> new ArtMeshAuthoringSnapshot.Form(
                                            f.formGuid(),
                                            positions.getOrDefault(f.formGuid(), f.positions()),
                                            orders.getOrDefault(f.formGuid(), f.drawOrder()),
                                            opacities.containsKey(f.formGuid())
                                                    ? Optional.of(opacities.get(f.formGuid()))
                                                    : f.opacity()))
                                    .toList(),
                            mesh.morphKeys());
                } else if (kind.equals("warp")) {
                    assertEquals(warp, args[0]);
                    var replacement = ((List<WarpKeyformPositions>) args[1]).get(0);
                    warp = new WarpAuthoringSnapshot(
                            warp.deformerId(),
                            warp.sourceGuid(),
                            warp.rows(),
                            warp.columns(),
                            warp.quadTransform(),
                            warp.axes(),
                            warp.cells(),
                            warp.forms().stream()
                                    .map(f -> f.formGuid().equals(replacement.formGuid())
                                            ? new WarpAuthoringSnapshot.Form(f.formGuid(), replacement.controlPoints())
                                            : f)
                                    .toList());
                } else {
                    assertEquals(rotation, args[0]);
                    var replacement = ((List<RotationKeyformForm>) args[1]).get(0);
                    rotation = new RotationAuthoringSnapshot(
                            rotation.deformerId(),
                            rotation.sourceGuid(),
                            rotation.baseAngle(),
                            rotation.axes(),
                            rotation.cells(),
                            rotation.forms().stream()
                                    .map(f -> f.formGuid().equals(replacement.formGuid())
                                            ? new RotationAuthoringSnapshot.Form(f.formGuid(), replacement.value())
                                            : f)
                                    .toList());
                }
                afterSetter.run();
                return null;
            }
            throw new UnsupportedOperationException(method);
        }

        private ModelSnapshot modelSnapshot() {
            return new ModelSnapshot(modelId, "Model", List.of(), List.of(), List.of(), List.of());
        }

        private HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    1,
                    revision,
                    0,
                    List.of(),
                    false,
                    false,
                    "document-binding",
                    "manager-binding");
        }

        private void restore(List<?> values) {
            mesh = (ArtMeshAuthoringSnapshot) values.get(0);
            warp = (WarpAuthoringSnapshot) values.get(1);
            rotation = (RotationAuthoringSnapshot) values.get(2);
        }

        void undo() {
            restore(undoBefore);
            revision++;
        }

        void redo() {
            restore(undoAfter);
            revision++;
        }

        Map<String, Object> request(String operationId, String... objects) {
            var targets = new ArrayList<Map<String, Object>>();
            for (String object : objects) {
                String tool =
                        switch (object) {
                            case "mesh" -> McpStoredAuthoringDomain.MESHES_READ;
                            case "warp" -> McpStoredAuthoringDomain.WARPS_READ;
                            default -> McpStoredAuthoringDomain.ROTATIONS_READ;
                        };
                var read = call(tool, Map.of("documentId", documentId, "modelId", modelId, "objectId", object));
                assertEquals(true, read.get("ok"), read.toString());
                var edit = new LinkedHashMap<String, Object>();
                edit.put("formGuid", "target");
                edit.put("coordinates", List.of(Map.of("parameterGuid", "axis-guid", "value", 1)));
                if (object.equals("mesh"))
                    edit.put(
                            "positions",
                            List.of(
                                    Map.of("x", noChange ? 0 : .25, "y", 0),
                                    Map.of("x", 1, "y", 0),
                                    Map.of("x", 0, "y", 1)));
                else if (object.equals("warp"))
                    edit.put(
                            "controlPoints",
                            List.of(
                                    Map.of("x", .5, "y", 0),
                                    Map.of("x", 1, "y", 0),
                                    Map.of("x", 0, "y", 1),
                                    Map.of("x", 1, "y", 1)));
                else
                    edit.put(
                            "form",
                            Map.of(
                                    "angle",
                                    12,
                                    "originX",
                                    2,
                                    "originY",
                                    3,
                                    "scale",
                                    .75,
                                    "reflectedX",
                                    true,
                                    "reflectedY",
                                    false));
                targets.add(Map.of(
                        "kind",
                        read.get("kind"),
                        "objectId",
                        object,
                        "snapshotId",
                        read.get("snapshotId"),
                        "edits",
                        List.of(edit)));
            }
            // Round-trip exactly as HTTP JSON does; requests must not depend on Java-only number types.
            return new LinkedHashMap<>(Json.parseObject(Json.stringify(Map.of(
                    "documentId", documentId, "modelId", modelId, "operationId", operationId, "targets", targets))));
        }

        Map<String, Object> write(Map<String, Object> args) {
            return call(McpKeyformWriteDomain.WRITE, args);
        }

        Map<String, Object> result(String id) {
            return call(McpKeyformWriteDomain.RESULT, Map.of("operationId", id));
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> call(String name, Map<String, Object> args) {
            var response = tools.call(name, args);
            assertInstanceOf(Map.class, response.get("structuredContent"), response.toString());
            return (Map<String, Object>) response.get("structuredContent");
        }
    }

    private static ArtMeshAuthoringSnapshot mesh() {
        return new ArtMeshAuthoringSnapshot(
                new ArtMeshId("mesh"),
                "source-mesh",
                new ArtMeshGeometry(POINTS, POINTS, List.of(0, 1, 2)),
                List.of(new ArtMeshAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Cell(
                                "neutral", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new ArtMeshAuthoringSnapshot.Cell(
                                "target", List.of(new ArtMeshAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new ArtMeshAuthoringSnapshot.Form("neutral", POINTS, 100),
                        new ArtMeshAuthoringSnapshot.Form("target", POINTS, 101)),
                List.of());
    }

    private static WarpAuthoringSnapshot warp() {
        return new WarpAuthoringSnapshot(
                new DeformerId("warp"),
                "source-warp",
                1,
                1,
                true,
                List.of(new WarpAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new WarpAuthoringSnapshot.Cell(
                                "neutral", List.of(new WarpAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new WarpAuthoringSnapshot.Cell(
                                "target", List.of(new WarpAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new WarpAuthoringSnapshot.Form("neutral", CONTROLS),
                        new WarpAuthoringSnapshot.Form("target", CONTROLS)));
    }

    private static RotationAuthoringSnapshot rotation() {
        return new RotationAuthoringSnapshot(
                new DeformerId("rotation"),
                "source-rotation",
                20,
                List.of(new RotationAuthoringSnapshot.Axis(
                        "axis-guid", new ParameterId("display"), 0, 0, 1, List.of(0F, 1F))),
                List.of(
                        new RotationAuthoringSnapshot.Cell(
                                "neutral", List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", 0))),
                        new RotationAuthoringSnapshot.Cell(
                                "target", List.of(new RotationAuthoringSnapshot.Coordinate("axis-guid", 1)))),
                List.of(
                        new RotationAuthoringSnapshot.Form("neutral", NEUTRAL),
                        new RotationAuthoringSnapshot.Form("target", NEUTRAL)));
    }
}
