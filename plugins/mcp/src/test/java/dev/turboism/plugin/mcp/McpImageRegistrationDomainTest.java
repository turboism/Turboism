package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

class McpImageRegistrationDomainTest {
    @Test
    void authorizedPngCreatesImageGroupRawImageAndBindingWithExactEvidenceAndRetainedResult() throws Exception {
        var f = new Fixture();
        var original = f.model.mesh;
        var bytes = png();
        var args = f.args("create", f.grant("READ", List.of("png")));
        assertEquals("QUEUED", f.start(args).get("phase"));
        assertEquals(0, f.reads);
        f.worker.runNext();
        assertEquals("READING", f.result("create").get("phase"));
        assertEquals(0, f.handle.closes);
        f.complete(bytes, false);
        assertEquals(1, f.handle.closes);
        assertEquals(0, f.model.writes);
        f.worker.runNext();
        var result = f.result("create");
        assertEquals("APPLIED", result.get("outcome"), result.toString());
        assertEquals(true, result.get("hostAdmitted"));
        assertEquals(false, result.get("hostCancellationConfirmed"));
        var evidence = map(result.get("fileEvidence"));
        assertEquals(sha(bytes), evidence.get("sha256"));
        assertEquals(2, ((Number) evidence.get("width")).intValue());
        assertEquals(2, ((Number) evidence.get("height")).intValue());
        assertEquals("UNPROVEN", evidence.get("fileStability"));
        var write = map(result.get("writeResult"));
        assertEquals("raw-registered", write.get("rawImageId"));
        assertEquals("registered", map(write.get("textureInput")).get("modelImageId"));
        assertEquals(true, write.get("readbackVerified"));
        assertNotNull(write.get("receipt"));
        assertNotNull(write.get("beforeHash"));
        assertNotNull(write.get("expectedAfterHash"));
        assertEquals(original, f.model.mesh);
        assertEquals(1, f.model.rawCatalog.size());
        assertEquals(1, f.model.addedImages.size());
        assertEquals(1, f.model.writes);
        assertEquals(1, f.model.transactions);
        f.model.forbidHost = true;
        assertEquals(result, f.start(args));
        assertEquals(result, f.result("create"));
        var conflict = new LinkedHashMap<>(args);
        conflict.put("name", "different");
        assertEquals("OPERATION_ID_CONFLICT", f.start(conflict).get("error"));
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.containsKey("bytes"));
    }

    @Test
    void preflightRejectsBoundLockedDeformedOrExpiredTargetsBeforeGrantConsumption() {
        for (String kind : List.of("locked", "parent", "bound", "expired")) {
            var f = new Fixture();
            if (kind.equals("locked")) f.model.locked = true;
            if (kind.equals("parent")) f.model.parent = "parent";
            var args = f.args("preflight", f.grant("READ", List.of("png")));
            if (kind.equals("bound"))
                f.model.input = Optional.of(new dev.turboism.sdk.cubism.model.ArtMeshTextureInput(
                        new dev.turboism.sdk.cubism.id.ArtMeshId("mesh"),
                        new dev.turboism.sdk.cubism.id.ModelId("model"),
                        42,
                        dev.turboism.sdk.cubism.model.ArtMeshTextureInput.Kind.MODEL_IMAGE,
                        Optional.of(new dev.turboism.sdk.cubism.id.ModelImageId("image-a")),
                        Optional.empty()));
            if (kind.equals("expired")) f.model.now = McpAuthoringSnapshotStore.TTL_NANOS;
            f.start(args);
            f.worker.runNext();
            assertEquals("NOT_APPLIED", f.result("preflight").get("outcome"), kind);
            assertEquals(0, f.reads);
            assertEquals(0, f.handle.closes);
            assertEquals(0, f.model.writes);
        }
    }

    @Test
    void staleHistoryGeometrySnapshotExpiryOrForeignSessionAfterReadNeverRegister() throws Exception {
        for (String kind : List.of("history", "source", "expired", "document", "model")) {
            var f = new Fixture();
            var args = f.args(kind, f.grant("READ", List.of("png")));
            f.start(args);
            f.worker.runNext();
            switch (kind) {
                case "history" -> f.model.revision++;
                case "expired" -> f.model.now = McpAuthoringSnapshotStore.TTL_NANOS;
                case "document" -> f.model.document = "other";
                case "model" -> f.model.modelId = "other";
                default -> {
                    var b = f.model.mesh;
                    f.model.mesh = new dev.turboism.sdk.cubism.model.ArtMeshAuthoringSnapshot(
                            b.artMeshId(), "new", b.sourceGeometry(), b.axes(), b.cells(), b.forms(), b.morphKeys());
                }
            }
            f.complete(png(), false);
            f.worker.runNext();
            assertEquals("NOT_APPLIED", f.result(kind).get("outcome"), kind);
            assertEquals(0, f.model.writes);
            assertEquals(0, f.model.transactions);
            assertEquals(1, f.handle.closes);
        }
    }

    @Test
    void truncatedMalformedAndOverBudgetContentOrWrongHashCannotReachNative() throws Exception {
        for (String kind : List.of("truncated", "empty", "signature", "dimensions", "hugeDimension", "bytes", "hash")) {
            var f = new Fixture();
            var args = f.args(kind, f.grant("READ", List.of("png")));
            if (kind.equals("hash")) args.put("expectedSha256", "0".repeat(64));
            if (kind.equals("bytes")) args.put("maxPngBytes", 1);
            if (kind.equals("hugeDimension")) args.put("maxPixels", 16777216);
            f.start(args);
            f.worker.runNext();
            var data = png();
            switch (kind) {
                case "empty" -> data = new byte[0];
                case "signature" -> data[0] = 0;
                case "dimensions" -> ByteBuffer.wrap(data, 16, 4).putInt(100);
                case "hugeDimension" -> {
                    ByteBuffer.wrap(data, 16, 4).putInt(8193);
                    ByteBuffer.wrap(data, 20, 4).putInt(1);
                }
                default -> {}
            }
            f.complete(data, kind.equals("truncated"));
            f.worker.runNext();
            var output = f.result(kind);
            assertEquals("NOT_APPLIED", output.get("outcome"), kind + output);
            assertEquals(false, output.get("hostAdmitted"));
            assertEquals(0, f.model.writes);
            assertEquals(1, f.handle.closes);
        }
        var f = new Fixture();
        var bytes = png();
        var args = f.args("pinned", f.grant("READ", List.of("png")));
        args.put("expectedSha256", sha(bytes));
        f.start(args);
        f.worker.runNext();
        f.complete(bytes, false);
        f.worker.runNext();
        assertEquals("APPLIED", f.result("pinned").get("outcome"));
    }

    @Test
    void onlySameSessionUnexpiredReadPngGrantCanBeConsumed() {
        for (String kind : List.of("write", "extension", "foreign", "expired", "revoked", "missing")) {
            var f = new Fixture();
            String grant = f.grant(
                    kind.equals("write") ? "WRITE" : "READ",
                    kind.equals("extension") ? List.of("png", "psd") : List.of("png"));
            if (kind.equals("foreign")) f.session = "other";
            var args = f.args(kind, kind.equals("missing") ? "missing" : grant);
            if (kind.equals("expired")) f.grants.now = f.grants.now.plus(McpFileGrantDomain.TTL);
            if (kind.equals("revoked")) f.grants.revoke("grant0");
            f.start(args);
            f.worker.runNext();
            assertEquals("NOT_APPLIED", f.result(kind).get("outcome"), kind);
            assertEquals(0, f.reads);
            assertEquals(0, f.model.writes);
        }
    }

    @Test
    void cancelBeforeWorkerAndDuringReadOrValidationWaitsForActualWorkAndReleasesLease() throws Exception {
        for (String where : List.of("queued", "read", "validation")) {
            var f = new Fixture();
            var args = f.args(where, f.grant("READ", List.of("png")));
            f.start(args);
            if (!where.equals("queued")) f.worker.runNext();
            if (where.equals("validation")) f.complete(png(), false);
            assertEquals("RUNNING", f.cancel(where).get("outcome"));
            assertEquals(
                    "REGISTRATION_BUSY",
                    f.start(f.args("other", "not-consumed")).get("error"));
            if (where.equals("read")) {
                assertEquals(0, f.handle.closes);
                assertFalse(f.read.isCancelled());
                f.complete(png(), false);
            }
            if (!f.worker.tasks.isEmpty()) f.worker.runNext();
            var output = f.result(where);
            assertEquals("CANCELLED", output.get("outcome"), where + output);
            assertEquals(false, output.get("hostAdmitted"));
            assertEquals(false, output.get("hostCancellationConfirmed"));
            assertEquals(where.equals("queued") ? 0 : 1, f.handle.closes);
            assertEquals(0, f.model.writes);
        }
    }

    @Test
    void cancellationAfterHostAdmissionDoesNotRelabelCommittedWriteOrClaimHostCancellation() throws Exception {
        var f = new Fixture();
        var args = f.args("admitted", f.grant("READ", List.of("png")));
        f.start(args);
        f.worker.runNext();
        f.complete(png(), false);
        f.model.afterWrite = () -> {
            assertEquals("HOST_RUNNING", f.result("admitted").get("phase"));
            assertEquals(true, f.result("admitted").get("hostAdmitted"));
            f.cancel("admitted");
        };
        f.worker.runNext();
        var output = f.result("admitted");
        assertEquals("APPLIED", output.get("outcome"));
        assertEquals(true, output.get("cancelRequested"));
        assertEquals(false, output.get("hostCancellationConfirmed"));
        assertEquals(1, f.model.writes);
    }

    @Test
    void partialNativeFailureWrongHandleOrUnexpectedCatalogChangeRollsBackRegistrationAndBinding() throws Exception {
        for (String kind :
                List.of("throw", "handle", "model", "mesh", "generation", "input", "catalog", "raw", "recovery")) {
            var f = new Fixture();
            var args = f.args(kind, f.grant("READ", List.of("png")));
            f.start(args);
            f.worker.runNext();
            switch (kind) {
                case "handle" -> f.model.wrongHandle = true;
                case "model" -> f.model.wrongModel = true;
                case "mesh" -> f.model.wrongMesh = true;
                case "generation" -> f.model.wrongGeneration = true;
                case "input" -> f.model.wrongInput = true;
                case "catalog" -> f.model.afterWrite = () -> f.model.catalog = List.of();
                case "raw" -> f.model.afterWrite = () -> f.model.rawCatalog = List.of();
                default -> {
                    f.model.throwBind = true;
                    f.model.recoveryFailed = kind.equals("recovery");
                }
            }
            f.complete(png(), false);
            f.worker.runNext();
            var output = f.result(kind);
            assertEquals(
                    kind.equals("recovery") ? "OUTCOME_UNKNOWN" : "ROLLED_BACK", output.get("outcome"), kind + output);
            assertNotNull(map(output.get("writeResult")).get("receipt"));
            assertEquals(1, f.model.writes);
            if (!kind.equals("recovery")) {
                assertTrue(f.model.input.isEmpty());
                assertTrue(f.model.addedImages.isEmpty());
                assertTrue(f.model.rawCatalog.isEmpty());
            }
            f.model.forbidHost = true;
            assertEquals(output, f.start(args));
        }
    }

    @Test
    void postCommitFailureOrResponseLossIsRetainedAndNeverAutomaticallyReexecutes() throws Exception {
        for (boolean lost : List.of(false, true)) {
            var f = new Fixture();
            var args = f.args("late", f.grant("READ", List.of("png")));
            f.start(args);
            f.worker.runNext();
            if (lost) f.model.loseResult = true;
            else f.model.afterCommit = () -> f.model.readFailure = new SecurityException("secret-path");
            f.complete(png(), false);
            f.worker.runNext();
            var output = f.result("late");
            assertEquals(
                    lost ? "OUTCOME_UNKNOWN" : "APPLIED_WITH_READBACK_WARNING",
                    output.get("outcome"),
                    output.toString());
            assertFalse(output.toString().contains("secret-path"));
            assertEquals(1, f.model.writes);
            assertTrue(f.model.input.isPresent());
            f.model.forbidHost = true;
            assertEquals(output, f.result("late"));
            assertEquals(output, f.start(args));
        }
    }

    @Test
    void sessionDropAndShutdownBeforeNativeAdmissionDiscardLateContentWithoutCancellingIo() throws Exception {
        for (boolean shutdown : List.of(false, true)) {
            var f = new Fixture();
            var args = f.args("late", f.grant("READ", List.of("png")));
            f.start(args);
            f.worker.runNext();
            if (shutdown) f.domain.close();
            else f.domain.dropSession("session");
            assertEquals(0, f.handle.closes);
            assertFalse(f.read.isCancelled());
            f.complete(png(), false);
            assertEquals(1, f.handle.closes);
            assertEquals(0, f.model.writes);
            assertTrue(f.worker.tasks.isEmpty());
            assertEquals(
                    shutdown ? "UNAVAILABLE" : "NOT_FOUND", f.result("late").get("error"));
        }
        var f = new Fixture();
        var args = f.args("queued", f.grant("READ", List.of("png")));
        f.start(args);
        f.domain.close();
        f.worker.runNext();
        assertEquals(0, f.reads);
    }

    @Test
    void workerAndReadAdmissionFailuresAreSanitizedAndDoNotLeakHandles() throws Exception {
        var rejected = new Fixture();
        var args = rejected.args("reject", rejected.grant("READ", List.of("png")));
        rejected.worker.reject = true;
        assertEquals("NOT_APPLIED", rejected.start(args).get("outcome"));
        assertEquals(0, rejected.reads);
        for (String kind : List.of("throw", "null", "exception", "sdkError", "worker")) {
            var f = new Fixture();
            var request = f.args(kind, f.grant("READ", List.of("png")));
            f.throwRead = kind.equals("throw");
            f.nullStage = kind.equals("null");
            f.start(request);
            f.worker.runNext();
            if (kind.equals("exception")) f.read.completeExceptionally(new IllegalStateException("secret-path"));
            else if (kind.equals("sdkError"))
                f.read.complete(new UserFileReadResult<>(
                        Optional.empty(),
                        Optional.of(new UserFileError(UserFileErrorCode.PERMISSION_DENIED, "secret-path")),
                        false));
            else if (kind.equals("worker")) {
                f.worker.reject = true;
                f.complete(png(), false);
            }
            if (!f.worker.tasks.isEmpty()) f.worker.runNext();
            var output = f.result(kind);
            assertEquals("NOT_APPLIED", output.get("outcome"), output.toString());
            assertFalse(output.toString().contains("secret-path"));
            assertEquals(1, f.handle.closes);
            assertEquals(0, f.model.writes);
        }
    }

    @Test
    void busyReplaySessionOwnershipAndRetentionDoNotPermitDuplicateReadsOrWrites() {
        var f = new Fixture();
        var args = f.args("active", f.grant("READ", List.of("png")));
        f.start(args);
        f.worker.runNext();
        assertEquals("READING", f.start(args).get("phase"));
        assertEquals("REGISTRATION_BUSY", f.start(f.args("other", "missing")).get("error"));
        f.session = "foreign";
        assertEquals("NOT_FOUND", f.result("active").get("error"));
        f.session = "session";
        assertEquals(1, f.reads);
        var g = new Fixture();
        for (int i = 0; i < 128; i++) {
            g.start(g.args("bad-" + i, "missing"));
            g.worker.runNext();
        }
        assertEquals("RESULT_STORE_FULL", g.start(g.args("full", "missing")).get("error"));
        assertEquals("NOT_APPLIED", g.result("bad-0").get("outcome"));
        assertEquals(0, g.reads);
    }

    @Test
    void lastPreAdmissionCancellationPreventsNativeAndSessionCloseAfterAdmissionDoesNotUndoCommit() throws Exception {
        var f = new Fixture();
        var args = f.args("last-boundary", f.grant("READ", List.of("png")));
        f.start(args);
        f.worker.runNext();
        f.complete(png(), false);
        f.model.onObserve = () -> f.cancel("last-boundary");
        f.worker.runNext();
        assertEquals("CANCELLED", f.result("last-boundary").get("outcome"));
        assertEquals(0, f.model.transactions);
        assertEquals(0, f.model.writes);
        for (boolean shutdown : List.of(false, true)) {
            var g = new Fixture();
            var request = g.args("admitted-close", g.grant("READ", List.of("png")));
            g.start(request);
            g.worker.runNext();
            g.complete(png(), false);
            g.model.afterWrite = () -> {
                if (shutdown) g.domain.close();
                else g.domain.dropSession("session");
            };
            g.worker.runNext();
            assertTrue(g.model.input.isPresent());
            assertEquals(1, g.model.writes);
            assertEquals(1, g.model.transactions);
            assertEquals(
                    shutdown ? "UNAVAILABLE" : "NOT_FOUND",
                    g.result("admitted-close").get("error"));
        }
    }

    @Test
    void inputSchemaRejectsPathsBase64BudgetsInvalidHashAndBlankNamesBeforeAnyWork() {
        var f = new Fixture();
        var args = f.args("schema", "unused");
        for (String field : List.of("path", "pngBase64", "overwrite", "width", "height")) {
            var invalid = new LinkedHashMap<>(args);
            invalid.put(field, "forbidden");
            assertThrows(IllegalArgumentException.class, () -> f.start(invalid));
        }
        for (String field : List.of("maxPngBytes", "maxPixels")) {
            var invalid = new LinkedHashMap<>(args);
            invalid.put(field, 16777217);
            assertThrows(IllegalArgumentException.class, () -> f.start(invalid));
        }
        var invalid = new LinkedHashMap<>(args);
        invalid.put("expectedSha256", "wrong");
        assertThrows(IllegalArgumentException.class, () -> f.start(invalid));
        var blank = new LinkedHashMap<>(args);
        blank.put("name", " ");
        assertEquals("INVALID_ARGUMENT", f.start(blank).get("error"));
        f.session = "";
        assertEquals("SESSION_REQUIRED", f.start(args).get("error"));
        assertEquals(0, f.reads);
    }

    private static byte[] png() throws Exception {
        var out = new ByteArrayOutputStream();
        var image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, 0xff123456);
        assertTrue(ImageIO.write(image, "png", out));
        return out.toByteArray();
    }

    private static String sha(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static final class Fixture {
        String session = "session";
        int grantsIssued, reads;
        boolean throwRead, nullStage;
        final McpFileGrantDomainTest.Fixture grants = new McpFileGrantDomainTest.Fixture();
        final McpImageBindingDomainTest.Fixture model = new McpImageBindingDomainTest.Fixture();
        final McpFileInspectDomainTest.Worker worker = new McpFileInspectDomainTest.Worker();
        McpFileGrantDomainTest.Handle handle;
        CompletableFuture<UserFileReadResult<byte[]>> read;
        final UserFileAccessService files = (UserFileAccessService) Proxy.newProxyInstance(
                UserFileAccessService.class.getClassLoader(),
                new Class<?>[] {UserFileAccessService.class},
                (p, m, a) -> {
                    assertEquals("readBytesBounded", m.getName());
                    reads++;
                    if (throwRead) throw new IllegalStateException("secret-path");
                    if (nullStage) return null;
                    read = new CompletableFuture<>();
                    return read;
                });
        final McpImageRegistrationDomain domain =
                new McpImageRegistrationDomain(files, grants.domain, model.domain, model.execution, worker);
        final McpToolCatalog tools = domain.tools();

        String grant(String mode, List<String> extensions) {
            String op = "grant" + grantsIssued++;
            var args = new LinkedHashMap<>(grants.args(op));
            args.put("mode", mode);
            args.put("allowedExtensions", extensions);
            grants.call(McpFileGrantDomain.REQUEST, args);
            handle = new McpFileGrantDomainTest.Handle();
            handle.mode = UserFileMode.valueOf(mode);
            grants.grant(handle);
            return (String) grants.status(op).get("grantId");
        }

        Map<String, Object> args(String op, String grant) {
            var binding = McpRequestRegistry.inSession(session, model::read);
            assertEquals(true, binding.get("ok"), binding.toString());
            return new LinkedHashMap<>(Map.of(
                    "operationId",
                    op,
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "objectId",
                    "mesh",
                    "snapshotId",
                    binding.get("snapshotId"),
                    "grantId",
                    grant,
                    "name",
                    "Eye PNG",
                    "maxPngBytes",
                    4096,
                    "maxPixels",
                    16));
        }

        Map<String, Object> start(Map<String, Object> args) {
            return call(McpImageRegistrationDomain.START, args);
        }

        Map<String, Object> result(String op) {
            return call(McpImageRegistrationDomain.RESULT, Map.of("operationId", op));
        }

        Map<String, Object> cancel(String op) {
            return call(McpImageRegistrationDomain.CANCEL, Map.of("operationId", op));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent"));
        }

        void complete(byte[] bytes, boolean truncated) {
            read.complete(new UserFileReadResult<>(Optional.of(bytes), Optional.empty(), truncated));
        }
    }
}
