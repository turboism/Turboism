package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.id.ArtMeshId;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import dev.turboism.sdk.json.Json;
import dev.turboism.sdk.plugin.PluginContext;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/** Test-only typed setter fault injection. Never included in production MCP or a release plugin. */
public final class McpGeometryRollbackProbe {
    public static final String REQUEST = "mcp-geometry-rollback-request.json";
    public static final String RESULT = "mcp-geometry-rollback-result.json";

    private McpGeometryRollbackProbe() {}

    public record Target(String kind, String objectId, String sourceGuid, String formGuid) {}

    record Request(String runId, String documentId, String modelId, List<Target> targets) {}

    record Work(String kind, Object before, Object expected, Supplier<Object> read, Runnable write) {}

    private static final class InjectedFailure extends RuntimeException {}

    static Request parse(final Object value, final String runId) {
        final var root = fields(value, "runId", "documentId", "modelId", "targets");
        require(text(root.get("runId")).equals(runId) && runId.matches("[a-zA-Z0-9-]{8,96}"), "Foreign run");
        require(root.get("targets") instanceof List<?>, "Targets missing");
        final var rows = (List<?>) root.get("targets");
        require(rows.size() == 3, "Exactly three native target kinds required");
        final var targets = new ArrayList<Target>();
        final var kinds = List.of("art_mesh", "warp_deformer", "rotation_deformer");
        for (int i = 0; i < rows.size(); i++) {
            final var row = fields(rows.get(i), "kind", "objectId", "sourceGuid", "formGuid");
            require(text(row.get("kind")).equals(kinds.get(i)), "Unexpected target kind/order");
            targets.add(new Target(
                    kinds.get(i), text(row.get("objectId")), text(row.get("sourceGuid")), text(row.get("formGuid"))));
        }
        return new Request(runId, text(root.get("documentId")), text(root.get("modelId")), List.copyOf(targets));
    }

    public static boolean runIfRequested(final PluginContext context, final Path stateRoot) {
        final Path path = stateRoot.resolve(REQUEST);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) return false;
        final var report = new LinkedHashMap<String, Object>();
        final String runId = System.getProperty("turboism.validation.runId", "");
        report.put("runId", runId);
        report.put("status", "FAIL");
        try {
            require(Files.size(path) <= 16384, "Oversize rollback request");
            final var request = parse(Json.parseObject(Files.readString(path)), runId);
            requireTaskIdentity(
                    stateRoot.getParent(), Path.of(System.getProperty("turboism.validation.fixture", "")), runId);
            final var task = new FutureTask<Map<String, Object>>(() -> exercise(context.cubism(), request));
            SwingUtilities.invokeLater(task);
            // An uncertain host timeout is terminal: never retry or perform unguarded cleanup writes.
            report.putAll(task.get(120, TimeUnit.SECONDS));
            report.put("status", "PASS");
        } catch (Exception failure) {
            report.put("exceptionClass", failure.getClass().getSimpleName());
            context.logger().error("Geometry fault-injection validation failed", failure);
        }
        try {
            final var pending = stateRoot.resolve(RESULT + ".pending");
            Files.writeString(pending, Json.stringify(report));
            Files.move(
                    pending,
                    stateRoot.resolve(RESULT),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            context.logger().error("Geometry rollback evidence publication failed", failure);
        }
        return true;
    }

    static void requireTaskIdentity(final Path home, final Path fixture, final String runId) throws Exception {
        require(
                runId.matches("[a-zA-Z0-9-]{8,96}")
                        && fixture.isAbsolute()
                        && home.isAbsolute()
                        && !Files.isSymbolicLink(home)
                        && !Files.isSymbolicLink(fixture)
                        && fixture.getFileName().toString().equals(runId + ".cmo3")
                        && fixture.getParent().getFileName().toString().equals(runId)
                        && home.toRealPath()
                                .getParent()
                                .equals(fixture.toRealPath().getParent()),
                "Not an owned task fixture");
    }

    static Map<String, Object> exercise(final CubismFacade cubism, final Request request) throws Exception {
        final var baseline = prepare(cubism, request);
        final var original = baseline.stream().map(Work::before).toList();
        final var attempts = new ArrayList<Map<String, Object>>();
        for (int failureAfter = 1; failureAfter <= 3; failureAfter++) {
            scope(cubism, request);
            final var work = prepare(cubism, request);
            require(
                    work.stream().map(Work::before).toList().equals(original),
                    "Previous attempt did not restore snapshots");
            final var historyBefore = cubism.history().snapshot();
            final int boundary = failureAfter;
            final int[] verified = {0};
            final boolean[] injected = {false};
            final var changedHashes = new ArrayList<String>();
            final var result = cubism.authoringTransactions()
                    .execute(
                            AuthoringTransactionOptions.of("Validation ordinary geometry injected failure " + boundary),
                            () -> {
                                scope(cubism, request);
                                require(
                                        cubism.history().snapshot().equals(historyBefore),
                                        "History changed before callback");
                                for (int i = 0; i < boundary; i++) {
                                    final var item = work.get(i);
                                    require(
                                            item.read().get().equals(item.before()),
                                            "Pre-write native snapshot changed");
                                    item.write().run();
                                    require(
                                            item.read().get().equals(item.expected()),
                                            "Setter did not reach expected complete native state");
                                    changedHashes.add(hash(item.expected()));
                                    verified[0]++;
                                }
                                injected[0] = true;
                                throw new InjectedFailure();
                            });
            require(injected[0] && verified[0] == boundary, "Injected boundary was not reached");
            require(
                    result.outcome() == AuthoringTransactionOutcome.ROLLED_BACK,
                    "Host did not authoritatively roll back");
            final var receipt = result.receipt().orElseThrow();
            require(receipt.historyEntryId().isEmpty(), "Failed transaction committed a history entry");
            scope(cubism, request);
            final var after =
                    prepare(cubism, request).stream().map(Work::before).toList();
            require(after.equals(original), "Rollback changed source/topology/grid/forms or bindings");
            final var historyAfter = cubism.history().snapshot();
            // The public service tracks Editor UI lifecycle generation; the transaction host tracks
            // native model binding generation. Both also own separate revision counters/binding IDs.
            // Prove each complete baseline independently, with explicit document/model checks above.
            require(receipt.historyBefore().equals(receipt.historyAfter()), "Transaction history was not restored");
            require(historyBefore.equals(historyAfter), "Public history was not restored");
            require(
                    receipt.historyBefore().position() == historyBefore.position()
                            && receipt.historyBefore().entries().size()
                                    == historyBefore.entries().size(),
                    "Receipt and public history disagree on native cursor/count");
            require(
                    historyAfter.generation() == historyBefore.generation()
                            && historyAfter.position() == historyBefore.position()
                            && historyAfter.entries().equals(historyBefore.entries())
                            && historyAfter.documentBindingId().equals(historyBefore.documentBindingId())
                            && historyAfter.managerBindingId().equals(historyBefore.managerBindingId()),
                    "Rollback changed history position or entries");
            final var attempt = new LinkedHashMap<String, Object>();
            attempt.put("failureAfter", boundary);
            attempt.put("verifiedSetterCount", verified[0]);
            attempt.put("injected", true);
            attempt.put("outcome", result.outcome().name());
            attempt.put("transactionId", receipt.transactionId());
            attempt.put("diagnosticId", result.diagnosticId().orElseThrow());
            attempt.put("historyPositionBefore", historyBefore.position());
            attempt.put("historyPositionAfter", historyAfter.position());
            attempt.put("publicHistoryBefore", historyEvidence(historyBefore));
            attempt.put("publicHistoryAfter", historyEvidence(historyAfter));
            attempt.put("transactionHistoryBefore", historyEvidence(receipt.historyBefore()));
            attempt.put("transactionHistoryAfter", historyEvidence(receipt.historyAfter()));
            attempt.put("changedSnapshotHashes", changedHashes);
            attempt.put("restoredSnapshotHashes", hashes(after));
            attempts.add(Collections.unmodifiableMap(attempt));
        }
        return Map.of(
                "attempts",
                attempts,
                "snapshotHashEncoding",
                "SDK_RECORD_UTF8_SHA256",
                "beforeSnapshotHashes",
                hashes(original),
                "allSnapshotsRestored",
                true);
    }

    private static List<Work> prepare(final CubismFacade cubism, final Request request) {
        scope(cubism, request);
        final var model = cubism.model().active();
        final var work = new ArrayList<Work>();
        for (final var target : request.targets()) {
            switch (target.kind()) {
                case "art_mesh" -> {
                    final var object = model.drawables().find(new ArtMeshId(target.objectId()));
                    final var before = object.authoringSnapshot();
                    require(before.sourceGuid().equals(target.sourceGuid()), "Mesh source changed");
                    final var cell = before.cells().stream()
                            .filter(c -> c.formGuid().equals(target.formGuid()))
                            .toList();
                    require(cell.size() == 1, "Non-independent Mesh cell");
                    final var form = before.forms().stream()
                            .filter(f -> f.formGuid().equals(target.formGuid()))
                            .findFirst()
                            .orElseThrow();
                    final var points = shifted(form.positions());
                    final var expected = new ArtMeshAuthoringSnapshot(
                            before.artMeshId(),
                            before.sourceGuid(),
                            before.sourceGeometry(),
                            before.axes(),
                            before.cells(),
                            before.forms().stream()
                                    .map(f -> f.formGuid().equals(form.formGuid())
                                            ? new ArtMeshAuthoringSnapshot.Form(
                                                    f.formGuid(), points, f.drawOrder(), f.opacity())
                                            : f)
                                    .toList(),
                            before.morphKeys());
                    work.add(new Work(
                            target.kind(),
                            before,
                            expected,
                            object::authoringSnapshot,
                            () -> object.setOrdinaryKeyformPositions(
                                    before,
                                    List.of(new ArtMeshKeyformPositions(
                                            cell.get(0).coordinates(), form.formGuid(), points)))));
                }
                case "warp_deformer" -> {
                    final var object = (WarpDeformer) model.deformers().find(new DeformerId(target.objectId()));
                    final var before = object.authoringSnapshot();
                    require(before.sourceGuid().equals(target.sourceGuid()), "Warp source changed");
                    final var cell = before.cells().stream()
                            .filter(c -> c.formGuid().equals(target.formGuid()))
                            .toList();
                    require(cell.size() == 1, "Non-independent Warp cell");
                    final var form = before.forms().stream()
                            .filter(f -> f.formGuid().equals(target.formGuid()))
                            .findFirst()
                            .orElseThrow();
                    final var points = shifted(form.controlPoints());
                    final var expected = new WarpAuthoringSnapshot(
                            before.deformerId(),
                            before.sourceGuid(),
                            before.rows(),
                            before.columns(),
                            before.quadTransform(),
                            before.axes(),
                            before.cells(),
                            before.forms().stream()
                                    .map(f -> f.formGuid().equals(form.formGuid())
                                            ? new WarpAuthoringSnapshot.Form(f.formGuid(), points)
                                            : f)
                                    .toList());
                    work.add(new Work(
                            target.kind(),
                            before,
                            expected,
                            object::authoringSnapshot,
                            () -> object.setOrdinaryKeyformPositions(
                                    before,
                                    List.of(new WarpKeyformPositions(
                                            cell.get(0).coordinates(), form.formGuid(), points)))));
                }
                case "rotation_deformer" -> {
                    final var object = (RotationDeformer) model.deformers().find(new DeformerId(target.objectId()));
                    final var before = object.authoringSnapshot();
                    require(before.sourceGuid().equals(target.sourceGuid()), "Rotation source changed");
                    final var cell = before.cells().stream()
                            .filter(c -> c.formGuid().equals(target.formGuid()))
                            .toList();
                    require(cell.size() == 1, "Non-independent Rotation cell");
                    final var form = before.forms().stream()
                            .filter(f -> f.formGuid().equals(target.formGuid()))
                            .findFirst()
                            .orElseThrow();
                    final var old = form.value();
                    final var replacement = new RotationDeformerForm(
                            old.angle()
                                    + ((Boolean.getBoolean("turboism.validation.headEditing")
                                                    || Boolean.getBoolean("turboism.validation.headPoseQA"))
                                            ? 0.5f
                                            : 1f),
                            old.originX(),
                            old.originY(),
                            old.scale(),
                            old.reflectedX(),
                            old.reflectedY());
                    require(!replacement.equals(old), "Unrepresentable Rotation delta");
                    final var expected = new RotationAuthoringSnapshot(
                            before.deformerId(),
                            before.sourceGuid(),
                            before.baseAngle(),
                            before.axes(),
                            before.cells(),
                            before.forms().stream()
                                    .map(f -> f.formGuid().equals(form.formGuid())
                                            ? new RotationAuthoringSnapshot.Form(f.formGuid(), replacement)
                                            : f)
                                    .toList());
                    work.add(new Work(
                            target.kind(),
                            before,
                            expected,
                            object::authoringSnapshot,
                            () -> object.setOrdinaryKeyformForms(
                                    before,
                                    List.of(new RotationKeyformForm(
                                            cell.get(0).coordinates(), form.formGuid(), replacement)))));
                }
                default -> throw new IllegalArgumentException("Unknown target kind");
            }
        }
        return List.copyOf(work);
    }

    private static List<Point2> shifted(final List<Point2> input) {
        if ((Boolean.getBoolean("turboism.validation.headEditing")
                || Boolean.getBoolean("turboism.validation.headPoseQA"))) return scaledShift(input);
        require(!input.isEmpty() && input.size() <= 4096, "Invalid point budget");
        final var points = new ArrayList<>(input);
        final var old = points.get(0);
        final var replacement = new Point2(old.x() + 1f, old.y());
        require(!replacement.equals(old), "Unrepresentable point delta");
        points.set(0, replacement);
        return List.copyOf(points);
    }

    static List<Point2> scaledShift(final List<Point2> input) {
        require(input.size() > 1 && input.size() <= 4096, "Invalid scaled point budget");
        final double minimum = input.stream().mapToDouble(Point2::x).min().orElseThrow();
        final double maximum = input.stream().mapToDouble(Point2::x).max().orElseThrow();
        final double span = maximum - minimum;
        require(Double.isFinite(span) && span > 0, "Invalid local x extent");
        final var result = new ArrayList<Point2>();
        for (Point2 point : input) {
            final float x = (float) (point.x() + span / 256);
            final double delta = (double) x - point.x();
            require(Float.isFinite(x) && delta > 0 && delta <= span / 128, "Unrepresentable bounded local delta");
            result.add(new Point2(x, point.y()));
        }
        return List.copyOf(result);
    }

    private static void scope(final CubismFacade cubism, final Request request) {
        final var document = cubism.activeDocument().orElseThrow();
        require(
                document.documentId().equals(request.documentId())
                        && document.model().orElseThrow().modelId().equals(request.modelId()),
                "Active document/model changed");
    }

    private static Map<String, Object> historyEvidence(final dev.turboism.sdk.cubism.history.HistorySnapshot history) {
        return Map.of(
                "generation",
                history.generation(),
                "revision",
                history.revision(),
                "position",
                history.position(),
                "entriesSha256",
                hash(history.entries()),
                "entryCount",
                history.entries().size(),
                "documentBindingId",
                history.documentBindingId(),
                "managerBindingId",
                history.managerBindingId(),
                "canUndo",
                history.canUndo(),
                "canRedo",
                history.canRedo());
    }

    private static List<String> hashes(final List<?> values) {
        return values.stream().map(McpGeometryRollbackProbe::hash).toList();
    }

    private static String hash(final Object value) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256")
                            .digest(value.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static Map<?, ?> fields(final Object value, final String... keys) {
        require(value instanceof Map<?, ?>, "Expected object");
        final var map = (Map<?, ?>) value;
        require(map.keySet().equals(Set.of(keys)), "Closed request fields");
        return map;
    }

    private static String text(final Object value) {
        require(
                value instanceof String
                        && !((String) value).isBlank()
                        && ((String) value).length() <= 256
                        && ((String) value).chars().noneMatch(Character::isISOControl),
                "Invalid identity");
        return (String) value;
    }

    private static void require(final boolean condition, final String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
