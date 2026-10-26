package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.CubismFacade;
import dev.turboism.sdk.cubism.core.*;
import dev.turboism.sdk.cubism.id.TextureAtlasId;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.ui.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import org.junit.jupiter.api.Test;

class McpMocExportDomainTest {
    @Test
    void nativeExportInspectionAndAuthorizedAtomicPackageHaveExactEvidenceAndNoHostReplay() throws Exception {
        var f = new Fixture();
        var request = f.plan("export");
        assertEquals(0, f.exports);
        assertEquals(0, f.inspections);
        assertEquals(0, f.writes);
        assertEquals(0, f.handle.closes);
        assertEquals("QUEUED", f.start(request).get("phase"));
        f.worker.runNext();
        assertEquals("WRITING", f.result("export").get("phase"));
        assertEquals(0, f.handle.closes);
        assertEquals(1, f.exports);
        assertEquals(1, f.inspections);
        assertEquals(1, f.writes);
        var zip = McpMocPackageTest.unzip(f.written);
        assertTrue(zip.containsKey("model.model3.json"));
        f.complete(true);
        var result = f.result("export");
        assertEquals("WRITTEN", result.get("outcome"), result.toString());
        assertEquals(true, result.get("fileWriteConfirmed"));
        assertEquals(false, result.get("fileReadbackVerified"));
        assertEquals(false, result.get("fullCharacterDelivery"));
        assertEquals(McpMocPackage.hash(f.written), result.get("archiveSha256"));
        assertEquals(f.written.length, result.get("archiveBytes"));
        assertEquals(1, f.handle.closes);
        f.forbidHost = true;
        assertEquals(result, f.result("export"));
        assertEquals(result, f.start(request));
        assertEquals(1, f.exports);
        var conflict = new LinkedHashMap<>(request);
        conflict.put("grantId", "different");
        assertEquals("OPERATION_ID_CONFLICT", f.start(conflict).get("error"));
        assertFalse(result.toString().contains("secret"));
        assertFalse(result.containsKey("bytes"));
    }

    @Test
    void sourceHistoryIdentityAtlasMetadataAndTtlAreCheckedBeforeExport() {
        for (String kind :
                List.of("revision", "generation", "document", "model", "manager", "atlas", "ttl", "session")) {
            var f = new Fixture();
            var request = f.plan(kind);
            switch (kind) {
                case "revision" -> f.base.revision++;
                case "generation" -> f.base.generation++;
                case "document" -> f.base.document = "other";
                case "model" -> f.base.modelId = "other";
                case "manager" -> f.base.manager = "other";
                case "atlas" -> f.atlasName = "changed";
                case "ttl" -> f.now = McpAtlasDomain.TTL;
                case "session" -> f.session = "foreign";
            }
            f.start(request);
            f.worker.runNext();
            assertEquals("NOT_WRITTEN", f.result(kind).get("outcome"), kind);
            assertEquals(0, f.exports);
            assertEquals(0, f.writes);
            assertEquals(0, f.handle.closes);
        }
    }

    @Test
    void emptyAtlasUnavailableCoreAndSourcePixelBudgetRejectPurePlan() {
        for (String kind : List.of("empty", "core", "inspection", "pixels")) {
            var f = new Fixture();
            if (kind.equals("empty")) f.empty = true;
            if (kind.equals("core")) f.coreAvailable = false;
            if (kind.equals("inspection")) f.inspectAvailable = false;
            var args = f.args("plan", f.grant("WRITE", List.of("zip")));
            if (kind.equals("pixels"))
                args.put("budgets", Map.of("maxMocBytes", 1024, "maxArchiveBytes", 4096, "maxTotalPixels", 1));
            assertEquals(false, f.call(McpMocExportDomain.PLAN, args).get("ok"), kind);
            assertEquals(0, f.exports);
            assertEquals(0, f.inspections);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void invalidNativeSettingsTextureIndicesMocAndPostExportStateNeverWrite() {
        for (String kind : List.of(
                "settings",
                "dimensions",
                "textureCount",
                "mocBudget",
                "coreVersion",
                "inconsistent",
                "unknown",
                "state",
                "throw",
                "coreThrow")) {
            var f = new Fixture();
            var request = f.plan(kind);
            switch (kind) {
                case "settings" ->
                    f.export = new CubismMocExport(
                            McpMocPackageTest.moc(),
                            f.export.textures(),
                            new CubismMocExportSettings(1, 0, 100, false, false, false, false, true));
                case "dimensions" ->
                    f.export = new CubismMocExport(
                            McpMocPackageTest.moc(),
                            List.of(
                                    new CubismExportTexture(2, 2, new int[4]),
                                    f.export.textures().get(1)),
                            McpMocPackageTest.SETTINGS);
                case "textureCount" ->
                    f.export = new CubismMocExport(
                            McpMocPackageTest.moc(), List.of(f.export.textures().get(0)), McpMocPackageTest.SETTINGS);
                case "mocBudget" -> {
                    var bytes = new byte[1025];
                    System.arraycopy(McpMocPackageTest.moc(), 0, bytes, 0, 64);
                    f.export = new CubismMocExport(bytes, f.export.textures(), McpMocPackageTest.SETTINGS);
                }
                case "coreVersion" -> f.info = new MocInfo(MocVersion.V5_0, MocConsistency.CONSISTENT);
                case "inconsistent" -> f.info = new MocInfo(MocVersion.V5_3, MocConsistency.INCONSISTENT);
                case "unknown" -> f.info = new MocInfo(MocVersion.UNKNOWN, MocConsistency.UNKNOWN);
                case "state" -> f.afterExport = () -> f.base.revision++;
                case "throw" ->
                    f.afterExport = () -> {
                        throw new IllegalStateException("secret");
                    };
                case "coreThrow" -> f.throwInspect = true;
            }
            f.start(request);
            f.worker.runNext();
            var result = f.result(kind);
            assertEquals("NOT_WRITTEN", result.get("outcome"), kind + result);
            assertEquals(0, f.writes);
            assertEquals(0, f.handle.closes);
            assertFalse(result.toString().contains("secret"));
        }
    }

    @Test
    void deniedWrongModeForeignExpiredRevokedOrWrongExtensionGrantsCannotWrite() {
        for (String kind : List.of("missing", "read", "extensions", "expired", "revoked")) {
            var f = new Fixture();
            var grant = kind.equals("missing")
                    ? "missing"
                    : f.grant(
                            kind.equals("read") ? "READ" : "WRITE",
                            kind.equals("extensions") ? List.of("zip", "png") : List.of("zip"));
            var planned = f.call(McpMocExportDomain.PLAN, f.args(kind, grant));
            var request = new LinkedHashMap<>(map(planned.get("executeRequest")));
            if (kind.equals("expired")) f.grants.now = f.grants.now.plus(McpFileGrantDomain.TTL);
            if (kind.equals("revoked")) f.handle.revoke();
            f.start(request);
            f.worker.runNext();
            assertEquals("NOT_WRITTEN", f.result(kind).get("outcome"), kind);
            assertEquals(0, f.writes);
        }
    }

    @Test
    void cancellationBeforeExportDuringExportAndAtFinalWriteBoundaryPreventsFileWrite() {
        for (String kind : List.of("queued", "export", "boundary")) {
            var f = new Fixture();
            var request = f.plan(kind);
            f.start(request);
            if (kind.equals("queued")) f.cancel(kind);
            if (kind.equals("export")) f.afterExport = () -> f.cancel(kind);
            if (kind.equals("boundary"))
                f.onSource = () -> {
                    if (f.exports > 0) f.cancel(kind);
                };
            f.worker.runNext();
            assertEquals("CANCELLED", f.result(kind).get("outcome"), kind + f.result(kind));
            assertEquals(0, f.writes);
            assertEquals(0, f.handle.closes);
        }
    }

    @Test
    void pendingWriteKeepsLeaseAndGlobalSlotAndRecordsActualCompletionAfterCancelOrClose() {
        for (String kind : List.of("cancel", "session", "close")) {
            var f = new Fixture();
            var request = f.plan(kind);
            f.start(request);
            f.worker.runNext();
            var stage = f.write;
            if (kind.equals("cancel")) f.cancel(kind);
            else if (kind.equals("session")) f.domain.dropSession("session");
            else f.domain.close();
            assertFalse(stage.isCancelled());
            assertEquals(0, f.handle.closes);
            if (kind.equals("cancel")) {
                var other = new LinkedHashMap<>(request);
                other.put("operationId", "other");
                assertEquals("EXPORT_BUSY", f.start(other).get("error"));
            }
            f.complete(true);
            assertEquals(1, f.handle.closes);
            assertEquals(1, f.writes);
            if (kind.equals("cancel")) {
                assertEquals("WRITTEN", f.result(kind).get("outcome"));
                assertEquals(true, f.result(kind).get("cancelRequested"));
                assertEquals(false, f.result(kind).get("hostCancellationConfirmed"));
            } else
                assertEquals(
                        kind.equals("close") ? "UNAVAILABLE" : "NOT_FOUND",
                        f.result(kind).get("error"));
        }
    }

    @Test
    void olderSdkMissingBoundedWriteFailsClosedAndClosesLeaseWithoutRetry() {
        var f = new Fixture();
        var request = f.plan("old-sdk");
        f.missingApi = true;
        f.start(request);
        f.worker.runNext();
        var result = f.result("old-sdk");
        assertEquals("NOT_WRITTEN", result.get("outcome"));
        assertEquals("BOUNDED_WRITE_API_UNAVAILABLE", result.get("code"));
        assertEquals(1, f.handle.closes);
        f.forbidHost = true;
        assertEquals(result, f.start(request));
    }

    @Test
    void writeFalseIsKnownUnchangedWhileExceptionalNullAndSubmissionLossAreUnknown() {
        for (String kind : List.of("false", "exception", "null", "throw", "nullStage")) {
            var f = new Fixture();
            var request = f.plan(kind);
            f.throwWrite = kind.equals("throw");
            f.nullStage = kind.equals("nullStage");
            f.start(request);
            f.worker.runNext();
            if (kind.equals("false")) f.complete(false);
            if (kind.equals("exception")) f.write.completeExceptionally(new IllegalStateException("secret"));
            if (kind.equals("null")) f.write.complete(null);
            var result = f.result(kind);
            assertEquals(
                    kind.equals("false") ? "NOT_WRITTEN" : "OUTCOME_UNKNOWN", result.get("outcome"), result.toString());
            assertEquals(1, f.handle.closes);
            f.forbidHost = true;
            assertEquals(result, f.start(request));
            assertEquals(1, f.writes);
            assertFalse(result.toString().contains("secret"));
        }
    }

    @Test
    void shutdownBeforeQueuedWorkAndWorkerRejectionDoNotExportOrConsumeGrant() {
        for (boolean rejected : List.of(false, true)) {
            var f = new Fixture();
            var request = f.plan("stop");
            f.worker.reject = rejected;
            f.start(request);
            if (!rejected) {
                f.domain.close();
                f.worker.runNext();
            }
            assertEquals(0, f.exports);
            assertEquals(0, f.writes);
            assertEquals(0, f.handle.closes);
        }
    }

    @Test
    void snapshotRequestCannotChangeGrantPolicyAndResultRetentionDoesNotEvict() {
        var f = new Fixture();
        var request = f.plan("mismatch");
        request.put("grantId", "different");
        f.start(request);
        f.worker.runNext();
        assertEquals("INVALID_ARGUMENT", f.result("mismatch").get("code"));
        assertEquals(0, f.exports);
        f.worker.reject = true;
        for (int i = 0; i < 127; i++) {
            var args = new LinkedHashMap<>(request);
            args.put("operationId", "reject" + i);
            assertEquals("NOT_WRITTEN", f.start(args).get("outcome"));
        }
        request.put("operationId", "full");
        assertEquals("RESULT_STORE_FULL", f.start(request).get("error"));
        assertEquals("NOT_WRITTEN", f.result("mismatch").get("outcome"));
    }

    @Test
    void encodingBudgetAndFinalWriteHistoryExpiryCancellationAreCheckedBeforeGrantConsumption() {
        for (String kind : List.of("budget", "revision", "ttl", "cancel", "session", "close")) {
            var f = new Fixture();
            var args = f.args(kind, f.grant("WRITE", List.of("zip")));
            if (kind.equals("budget"))
                args.put("budgets", Map.of("maxMocBytes", 1024, "maxArchiveBytes", 100, "maxTotalPixels", 4));
            var planned = f.call(McpMocExportDomain.PLAN, args);
            assertEquals(true, planned.get("ok"));
            var request = new LinkedHashMap<>(map(planned.get("executeRequest")));
            if (kind.equals("ttl")) f.afterExport = () -> f.now = McpAtlasDomain.TTL;
            var observations = new java.util.concurrent.atomic.AtomicInteger();
            f.onSource = () -> {
                if (f.exports > 0 && observations.incrementAndGet() == 2)
                    switch (kind) {
                        case "revision" -> f.base.revision++;
                        case "cancel" -> f.cancel(kind);
                        case "session" -> f.domain.dropSession("session");
                        case "close" -> f.domain.close();
                    }
            };
            f.start(request);
            f.worker.runNext();
            assertEquals(1, f.exports, kind);
            assertEquals(0, f.writes, kind);
            assertEquals(0, f.handle.closes, kind);
            if (!Set.of("session", "close").contains(kind))
                assertEquals(
                        kind.equals("cancel") ? "CANCELLED" : "NOT_WRITTEN",
                        f.result(kind).get("outcome"),
                        kind + f.result(kind));
        }
    }

    @Test
    void retainedPlansExpireOrEvictWithoutExportAndAreBoundToMcpSession() {
        var f = new Fixture();
        var request = f.plan("old");
        for (int i = 0; i < 16; i++) f.plan("fresh" + i);
        f.start(request);
        f.worker.runNext();
        assertEquals("NOT_FOUND", f.result("old").get("code"));
        assertEquals(0, f.exports);
        f.session = "";
        assertEquals("SESSION_REQUIRED", f.start(request).get("error"));
    }

    @Test
    void schemasRejectPathsInlineBytesBadSettingsBudgetsAndOverwritePolicy() {
        var f = new Fixture();
        var request = f.plan("schema");
        for (String key : List.of("path", "base64", "settings", "expectedState")) {
            var bad = new LinkedHashMap<>(request);
            bad.put(key, "forbidden");
            assertThrows(IllegalArgumentException.class, () -> f.start(bad));
        }
        var policy = f.args("invalid", "unused");
        policy.put("writePolicy", "REJECT_EXISTING");
        assertThrows(IllegalArgumentException.class, () -> f.call(McpMocExportDomain.PLAN, policy));
        var args = f.args("invalid", "unused");
        args.put("budgets", Map.of("maxMocBytes", 1024, "maxArchiveBytes", 134217729, "maxTotalPixels", 4));
        var bad = args;
        assertThrows(IllegalArgumentException.class, () -> f.call(McpMocExportDomain.PLAN, bad));
        assertEquals(0, f.exports);
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (p, m, a) -> body.apply(m.getName(), a));
    }

    static class Fixture {
        final McpImageBindingDomainTest.Fixture base = new McpImageBindingDomainTest.Fixture();
        final McpFileGrantDomainTest.Fixture grants = new McpFileGrantDomainTest.Fixture();
        final McpFileInspectDomainTest.Worker worker = new McpFileInspectDomainTest.Worker();
        String session = "session", atlasName = "atlas";
        long now;
        int exports, inspections, writes, grantCount;
        boolean forbidHost,
                empty,
                coreAvailable = true,
                inspectAvailable = true,
                throwInspect,
                throwWrite,
                nullStage,
                missingApi;
        Runnable afterExport = () -> {}, onSource = () -> {};
        CubismMocExport export = McpMocPackageTest.export();
        MocInfo info = new MocInfo(MocVersion.V5_3, MocConsistency.CONSISTENT);
        byte[] written;
        CompletableFuture<UserFileWriteResult> write;
        McpFileGrantDomainTest.Handle handle;
        final ModelTextures textures = proxy(ModelTextures.class, (name, args) -> {
            check();
            assertEquals("textureAtlases", name);
            onSource.run();
            if (empty) return List.of();
            return java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> new AtlasTexture() {
                        public TextureAtlasId id() {
                            return new TextureAtlasId("atlas" + index);
                        }

                        public String name() {
                            return atlasName + index;
                        }

                        public int width() {
                            return index == 0 ? 2 : 1;
                        }

                        public int height() {
                            return index == 0 ? 1 : 2;
                        }

                        public int atlasVersion() {
                            return 1;
                        }

                        public int modelImageCount() {
                            return 1;
                        }
                    })
                    .toList();
        });
        final CubismModel model = proxy(CubismModel.class, (name, args) -> {
            check();
            if (name.equals("textures")) return textures;
            if (name.equals("exportMoc")) {
                exports++;
                afterExport.run();
                return export;
            }
            throw new AssertionError(name);
        });
        final CoreRuntimeInfo core = new CoreRuntimeInfo() {
            public boolean isAvailable() {
                check();
                return coreAvailable;
            }

            public CoreVersion version() {
                throw new AssertionError();
            }

            public CoreCapabilities capabilities() {
                check();
                return new CoreCapabilities(false, false, inspectAvailable);
            }

            public MocInspector mocInspector() {
                return new MocInspector() {
                    public MocVersion latestVersion() {
                        throw new AssertionError();
                    }

                    public MocInfo inspect(MocData data) {
                        check();
                        inspections++;
                        assertArrayEquals(export.moc3(), data.toByteArray());
                        if (throwInspect) throw new IllegalStateException("secret");
                        return info;
                    }
                };
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (name, args) -> {
            check();
            assertEquals("coreRuntime", name);
            return core;
        });
        final UserFileAccessService files = proxy(UserFileAccessService.class, (name, args) -> {
            assertEquals("writeBytesAtomicBounded", name);
            if (missingApi) throw new NoSuchMethodError("older-sdk");
            writes++;
            assertSame(handle, args[0]);
            written = ((byte[]) args[1]).clone();
            assertTrue(written.length <= ((Number) args[2]).intValue());
            if (throwWrite) throw new IllegalStateException("secret");
            if (nullStage) return null;
            write = new CompletableFuture<>();
            return write;
        });
        final McpMocExportDomain domain = new McpMocExportDomain(
                facade,
                files,
                grants.domain,
                base.execution,
                () -> {
                    check();
                    return new McpStoredAuthoringDomain.Context(model, base.binding());
                },
                () -> now,
                worker);
        final McpToolCatalog tools = domain.tools();

        void check() {
            if (forbidHost) throw new AssertionError("No host access during replay/result");
        }

        String grant(String mode, List<String> extensions) {
            String id = "grant" + grantCount++;
            var args = new LinkedHashMap<>(grants.args(id));
            args.put("mode", mode);
            args.put("allowedExtensions", extensions);
            grants.call(McpFileGrantDomain.REQUEST, args);
            handle = new McpFileGrantDomainTest.Handle();
            handle.mode = UserFileMode.valueOf(mode);
            grants.grant(handle);
            return (String) grants.status(id).get("grantId");
        }

        Map<String, Object> args(String op, String grant) {
            return new LinkedHashMap<>(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "operationId",
                    op,
                    "grantId",
                    grant,
                    "writePolicy",
                    "REPLACE_AUTHORIZED_FILE",
                    "settings",
                    McpMocPackage.project(McpMocPackageTest.SETTINGS),
                    "budgets",
                    Map.of("maxMocBytes", 1024, "maxArchiveBytes", 4096, "maxTotalPixels", 4)));
        }

        Map<String, Object> plan(String op) {
            var result = call(McpMocExportDomain.PLAN, args(op, grant("WRITE", List.of("zip"))));
            assertEquals(true, result.get("ok"), result.toString());
            return new LinkedHashMap<>(map(result.get("executeRequest")));
        }

        Map<String, Object> start(Map<String, Object> request) {
            return call(McpMocExportDomain.EXECUTE, request);
        }

        Map<String, Object> result(String op) {
            return call(McpMocExportDomain.RESULT, Map.of("operationId", op));
        }

        Map<String, Object> cancel(String op) {
            return call(McpMocExportDomain.CANCEL, Map.of("operationId", op));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(McpRequestRegistry.inSession(session, () -> tools.call(name, args))
                    .get("structuredContent"));
        }

        void complete(boolean success) {
            write.complete(new UserFileWriteResult(
                    success,
                    success
                            ? Optional.empty()
                            : Optional.of(new UserFileError(UserFileErrorCode.IO_FAILURE, "secret"))));
        }
    }
}
