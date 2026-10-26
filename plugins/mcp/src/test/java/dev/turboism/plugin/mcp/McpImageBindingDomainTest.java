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

class McpImageBindingDomainTest {
    @Test
    void discoversExistingImagesAndBindsWithExactReceiptAndRetainedReadback() {
        var f = new Fixture();
        var original = f.mesh;
        var read = f.read();
        assertEquals(true, read.get("ok"), read.toString());
        var binding = map(read.get("binding"));
        assertNull(binding.get("textureInput"));
        assertEquals(false, binding.get("locked"));
        assertEquals(2, ((List<?>) binding.get("images")).size());
        assertFalse(read.toString().contains("base64"));
        assertEquals(0, f.writes);
        assertEquals(0, f.transactions);
        var args = f.request("bind", read, "image-a");
        var output = f.bind(args);
        assertEquals("APPLIED", output.get("outcome"), output.toString());
        assertEquals(true, output.get("readbackVerified"));
        assertEquals("image-a", map(output.get("textureInput")).get("modelImageId"));
        assertEquals(42L, ((Number) map(output.get("textureInput")).get("modelGeneration")).longValue());
        assertNotEquals(output.get("beforeHash"), output.get("expectedAfterHash"));
        assertNotNull(output.get("receipt"));
        assertEquals(1, f.writes);
        assertEquals(1, f.transactions);
        assertEquals(original, f.mesh);
        assertThrows(
                NoSuchElementException.class,
                () -> f.store.get((String) read.get("snapshotId"), McpRequestRegistry.currentSessionId()));
        f.forbidHost = true;
        assertEquals(output, f.bind(args));
        assertEquals(output, f.result("bind"));
        var conflict = new LinkedHashMap<>(args);
        conflict.put("modelImageId", "image-b");
        assertEquals("OPERATION_ID_CONFLICT", f.bind(conflict).get("code"));
    }

    @Test
    void sameBindingIsNoChangeWithNoNativeBindAndIndependentNewSnapshots() {
        var f = new Fixture();
        f.input = Optional.of(bound("image-a"));
        var read = f.read();
        assertEquals(
                "MODEL_IMAGE", map(map(read.get("binding")).get("textureInput")).get("kind"));
        var output = f.bind(f.request("noop", read, "image-a"));
        assertEquals("NO_CHANGE", output.get("outcome"), output.toString());
        assertEquals(output.get("beforeHash"), output.get("expectedAfterHash"));
        assertEquals(true, output.get("readbackVerified"));
        assertEquals(0, f.writes);
        assertEquals(1, f.transactions);
        assertEquals(true, f.read().get("ok"));
    }

    @Test
    void existingOtherImageAtlasLockedAndParentTargetsCannotBeOverwrittenOrUnlocked() {
        for (String kind : List.of("other", "atlas", "locked", "parent")) {
            var f = new Fixture();
            switch (kind) {
                case "other" -> f.input = Optional.of(bound("image-b"));
                case "atlas" ->
                    f.input = Optional.of(new ArtMeshTextureInput(
                            new ArtMeshId("mesh"),
                            new ModelId("model"),
                            42,
                            ArtMeshTextureInput.Kind.TEXTURE_ATLAS_REGION,
                            Optional.empty(),
                            Optional.of(new TextureAtlasId("atlas"))));
                case "locked" -> f.locked = true;
                default -> f.parent = "deformer";
            }
            var read = f.read();
            assertEquals(true, read.get("ok"), read.toString());
            var output = f.bind(f.request("reject", read, "image-a"));
            assertEquals("UNAVAILABLE", output.get("code"), kind + output);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void fullCasRejectsNativeIdentityGeometryParentLocksCatalogInputAndHistoryChanges() {
        for (String kind : List.of(
                "source",
                "geometry",
                "part",
                "parent",
                "locked",
                "input",
                "inputGeneration",
                "images",
                "dimensions",
                "history",
                "generation",
                "document",
                "model",
                "manager")) {
            var f = new Fixture();
            if (kind.equals("inputGeneration")) f.input = Optional.of(bound("image-a"));
            var args = f.request("stale", f.read(), "image-a");
            switch (kind) {
                case "source" -> {
                    var b = f.mesh;
                    f.mesh = new ArtMeshAuthoringSnapshot(
                            b.artMeshId(),
                            "recreated",
                            b.sourceGeometry(),
                            b.axes(),
                            b.cells(),
                            b.forms(),
                            b.morphKeys());
                }
                case "geometry" ->
                    f.mesh = McpGeometryQualityTest.mesh(
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.TRI,
                            McpGeometryQualityTest.UNIT,
                            McpGeometryQualityTest.points(0, 0, 2, 0, 0, 1));
                case "part" -> f.part = "part";
                case "parent" -> f.parent = "deformer";
                case "locked" -> f.locked = true;
                case "input" -> f.input = Optional.of(bound("image-b"));
                case "inputGeneration" ->
                    f.input = Optional.of(new ArtMeshTextureInput(
                            new ArtMeshId("mesh"),
                            new ModelId("model"),
                            43,
                            ArtMeshTextureInput.Kind.MODEL_IMAGE,
                            Optional.of(new ModelImageId("image-a")),
                            Optional.empty()));
                case "images" -> f.catalog = List.of(image("image-a", 2), image("image-new", 2));
                case "dimensions" -> f.catalog = List.of(image("image-a", 3), image("image-b", 2));
                case "history" -> f.revision++;
                case "generation" -> f.generation++;
                case "document" -> f.document = "other";
                case "model" -> f.modelId = "other";
                default -> f.manager = "other";
            }
            var output = f.bind(args);
            assertEquals("STALE_STATE", output.get("code"), kind + output);
            assertEquals(0, f.writes);
            assertEquals(0, f.transactions);
        }
    }

    @Test
    void transactionEntryRecheckPreventsFirstSetterAndPartialFailureRestoresBinding() {
        var race = new Fixture();
        var args = race.request("race", race.read(), "image-a");
        race.before = () -> race.locked = true;
        assertEquals("ROLLED_BACK", race.bind(args).get("outcome"));
        assertEquals(0, race.writes);
        for (String kind : List.of(
                "throw",
                "wrongHandle",
                "wrongModel",
                "wrongMesh",
                "wrongGeneration",
                "wrongInput",
                "geometry",
                "catalog",
                "recovery")) {
            var f = new Fixture();
            var original = f.mesh;
            var request = f.request(kind, f.read(), "image-a");
            switch (kind) {
                case "throw" -> f.throwBind = true;
                case "wrongHandle" -> f.wrongHandle = true;
                case "wrongModel" -> f.wrongModel = true;
                case "wrongMesh" -> f.wrongMesh = true;
                case "wrongGeneration" -> f.wrongGeneration = true;
                case "wrongInput" -> f.wrongInput = true;
                case "geometry" ->
                    f.afterWrite = () -> {
                        var b = f.mesh;
                        f.mesh = new ArtMeshAuthoringSnapshot(
                                b.artMeshId(),
                                "unexpected",
                                b.sourceGeometry(),
                                b.axes(),
                                b.cells(),
                                b.forms(),
                                b.morphKeys());
                    };
                case "catalog" -> f.afterWrite = () -> f.catalog = List.of(image("image-a", 4));
                default -> {
                    f.throwBind = true;
                    f.recoveryFailed = true;
                }
            }
            var output = f.bind(request);
            assertEquals(
                    kind.equals("recovery") ? "OUTCOME_UNKNOWN" : "ROLLED_BACK", output.get("outcome"), kind + output);
            assertNotNull(output.get("receipt"));
            assertNotNull(output.get("diagnosticId"));
            assertEquals(false, output.get("readbackVerified"));
            assertNull(output.get("textureInput"));
            if (!kind.equals("recovery")) {
                assertTrue(f.input.isEmpty());
                assertEquals(original, f.mesh);
            }
            assertEquals(output, f.bind(request));
            assertEquals(1, f.writes);
        }
    }

    @Test
    void postCommitReadFailureAndLostResponseAreRetainedWithoutRetryingHost() {
        for (boolean lost : List.of(false, true)) {
            var f = new Fixture();
            var args = f.request("lost", f.read(), "image-a");
            if (lost) f.loseResult = true;
            else f.afterCommit = () -> f.readFailure = new SecurityException("sensitive-path");
            var output = f.bind(args);
            assertEquals(
                    lost ? "OUTCOME_UNKNOWN" : "APPLIED_WITH_READBACK_WARNING",
                    output.get("outcome"),
                    output.toString());
            assertEquals(false, output.get("readbackVerified"));
            assertNull(output.get("textureInput"));
            assertTrue(f.input.isPresent());
            assertFalse(output.toString().contains("sensitive-path"));
            f.forbidHost = true;
            assertEquals(output, f.bind(args));
            assertEquals(output, f.result("lost"));
            assertEquals(1, f.writes);
        }
    }

    @Test
    void unavailableTransactionAndNativePermissionAreClosedWithoutStandaloneFallback() {
        var f = new Fixture();
        var args = f.request("unavailable", f.read(), "image-a");
        f.unavailable = true;
        assertEquals("NOT_APPLIED", f.bind(args).get("outcome"));
        assertEquals(0, f.writes);
        for (RuntimeException failure :
                List.of(new SecurityException("secret"), new UnsupportedOperationException("secret"))) {
            var g = new Fixture();
            var request = g.request("failure", g.read(), "image-a");
            g.nativeFailure = failure;
            var output = g.bind(request);
            assertEquals("ROLLED_BACK", output.get("outcome"));
            assertFalse(output.toString().contains("secret"));
            assertTrue(g.input.isEmpty());
            assertEquals(1, g.writes);
        }
    }

    @Test
    void readingRacesForeignObservationsAndUnavailableReadsDoNotIssueUsableSnapshot() {
        var f = new Fixture();
        var args = f.readArgs();
        f.onObserve = () -> f.revision++;
        assertEquals("STALE_STATE", f.call(McpImageBindingDomain.READ, args).get("error"));
        var g = new Fixture();
        var readArgs = g.readArgs();
        g.input = Optional.of(new ArtMeshTextureInput(
                new ArtMeshId("foreign"),
                new ModelId("model"),
                42,
                ArtMeshTextureInput.Kind.MODEL_IMAGE,
                Optional.of(new ModelImageId("image-a")),
                Optional.empty()));
        assertEquals("STALE_STATE", g.call(McpImageBindingDomain.READ, readArgs).get("error"));
        for (RuntimeException failure : List.of(
                new SecurityException("private"),
                new UnsupportedOperationException("private"),
                new IllegalStateException("private"))) {
            var h = new Fixture();
            var read = h.readArgs();
            h.readFailure = failure;
            var output = h.call(McpImageBindingDomain.READ, read);
            assertEquals(false, output.get("ok"));
            assertFalse(output.toString().contains("private"));
        }
        var h = new Fixture();
        var raw = h.readArgs();
        int[] observeCount = {0};
        h.onObserve = () -> {
            if (++observeCount[0] != 2) return;
            var b = h.mesh;
            h.mesh = new ArtMeshAuthoringSnapshot(
                    b.artMeshId(), "race", b.sourceGeometry(), b.axes(), b.cells(), b.forms(), b.morphKeys());
        };
        assertEquals("STALE_STATE", h.call(McpImageBindingDomain.READ, raw).get("error"));
    }

    @Test
    void crossSessionExpiredAndWrongKindTokensAreRejectedAndResultsNeverReadHost() {
        var f = new Fixture();
        var args = McpRequestRegistry.inSession("a", () -> f.request("session", f.read(), "image-a"));
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("b", () -> f.bind(args)).get("code"));
        var g = new Fixture();
        var expired = g.request("expired", g.read(), "image-a");
        g.now = McpAuthoringSnapshotStore.TTL_NANOS;
        assertEquals("NOT_FOUND", g.bind(expired).get("code"));
        var h = new Fixture();
        var raw = h.readArgs();
        var invalid = h.request("wrong", h.read(), "image-a");
        invalid.put("snapshotId", raw.get("authoringSnapshotId"));
        assertEquals("STALE_STATE", h.bind(invalid).get("code"));
        f.forbidHost = true;
        assertEquals("NOT_FOUND", f.result("session").get("code"));
        assertEquals(0, f.writes);
        assertEquals(0, g.writes);
        assertEquals(0, h.writes);
    }

    @Test
    void candidatesBudgetsAndClosedSchemaRejectGuessedPathsBytesAndUnlistedImages() {
        var f = new Fixture();
        var args = f.request("missing", f.read(), "guessed-image");
        assertEquals("INVALID_ARGUMENT", f.bind(args).get("code"));
        assertEquals(0, f.transactions);
        for (String field : List.of("path", "pngBase64", "grantId", "overwrite", "unlock")) {
            var invalid = new LinkedHashMap<>(args);
            invalid.put(field, "forbidden");
            assertThrows(IllegalArgumentException.class, () -> f.bind(invalid));
        }
        var g = new Fixture();
        g.catalog = Collections.nCopies(1025, image("image-a", 2));
        assertEquals("BUDGET_EXCEEDED", g.read().get("error"));
        var h = new Fixture();
        h.catalog = List.of(image("duplicate", 2), image("duplicate", 2));
        assertEquals("INVALID_ARGUMENT", h.read().get("error"));
        var j = new Fixture();
        var large = new ArrayList<ModelImageEntry>();
        for (int i = 0; i < 80; i++) large.add(image("id-" + i, "x".repeat(4096), 2));
        j.catalog = large;
        assertEquals("BUDGET_EXCEEDED", j.read().get("error"));
        assertEquals(0, j.writes);
    }

    @Test
    void resultRetentionFullDoesNotEvictOrResubmitNoOps() {
        var f = new Fixture();
        f.input = Optional.of(bound("image-a"));
        for (int i = 0; i < 256; i++)
            assertEquals(
                    "NO_CHANGE",
                    f.bind(f.request("op-" + i, f.read(), "image-a")).get("outcome"));
        assertEquals(
                "RESULT_STORE_FULL",
                f.bind(f.request("overflow", f.read(), "image-a")).get("code"));
        assertEquals("NO_CHANGE", f.result("op-0").get("outcome"));
        assertEquals(256, f.transactions);
        assertEquals(0, f.writes);
    }

    @Test
    void runningResultAndCancellationDoNotPretendToCancelCommittedHostMutation() {
        var registry = new McpRequestRegistry();
        var f = new Fixture();
        try (var ignored = registry.enter("before", 1)) {
            var args = f.request("before", f.read(), "image-a");
            registry.cancel("before", 1);
            assertThrows(java.util.concurrent.CancellationException.class, () -> f.bind(args));
            assertEquals(0, f.writes);
        }
        var g = new Fixture();
        try (var ignored = registry.enter("after", 2)) {
            var args = g.request("after", g.read(), "image-a");
            g.afterWrite = () -> {
                assertEquals("RUNNING", g.result("after").get("outcome"));
                registry.cancel("after", 2);
            };
            assertEquals("APPLIED", g.bind(args).get("outcome"));
            assertEquals(1, g.writes);
        }
    }

    private static ArtMeshTextureInput bound(String image) {
        return new ArtMeshTextureInput(
                new ArtMeshId("mesh"),
                new ModelId("model"),
                42,
                ArtMeshTextureInput.Kind.MODEL_IMAGE,
                Optional.of(new ModelImageId(image)),
                Optional.empty());
    }

    private static ModelImageEntry image(String id, int width) {
        return image(id, id, width);
    }

    private static ModelImageEntry image(String id, String title, int width) {
        return new ModelImageEntry() {
            public ModelImageId id() {
                return new ModelImageId(id);
            }

            public String name() {
                return title;
            }

            public int width() {
                return width;
            }

            public int height() {
                return 2;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (o, m, a) -> body.apply(m.getName(), a));
    }

    private static ModelImageGroup group(List<ModelImageEntry> entries) {
        return new ModelImageGroup() {
            public String groupName() {
                return "group";
            }

            public String memo() {
                return "";
            }

            public List<ModelImageEntry> modelImages() {
                return entries;
            }
        };
    }

    static final class Fixture {
        long now, revision, generation = 2;
        String document = "doc", modelId = "model", manager = "manager", part, parent;
        int writes, transactions;
        boolean locked,
                throwBind,
                wrongHandle,
                wrongModel,
                wrongMesh,
                wrongGeneration,
                wrongInput,
                unavailable,
                recoveryFailed,
                loseResult,
                forbidHost;
        RuntimeException readFailure, nativeFailure;
        Runnable before = () -> {}, afterWrite = () -> {}, afterCommit = () -> {}, onObserve = () -> {};
        ArtMeshAuthoringSnapshot mesh = McpGeometryQualityTest.mesh(
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.TRI,
                McpGeometryQualityTest.UNIT,
                McpGeometryQualityTest.UNIT);
        Optional<ArtMeshTextureInput> input = Optional.empty();
        List<ModelImageEntry> addedImages = List.of();
        List<RawTexture> rawCatalog = List.of();
        List<ModelImageEntry> catalog = List.of(image("image-a", 2), image("image-b", 2));
        final McpAuthoringSnapshotStore store = new McpAuthoringSnapshotStore(() -> now);
        final Drawable drawable = proxy(Drawable.class, (name, args) -> {
            checkHost();
            return switch (name) {
                case "authoringSnapshot" -> mesh;
                case "parentPartId" -> Optional.ofNullable(part).map(PartId::new);
                case "parentDeformerId" -> Optional.ofNullable(parent).map(DeformerId::new);
                case "locked" -> locked;
                default -> throw new AssertionError(name);
            };
        });
        final ModelTextures textures = proxy(ModelTextures.class, (name, args) -> {
            checkHost();
            if (readFailure != null) throw readFailure;
            return switch (name) {
                case "modelImageGroups" ->
                    addedImages.isEmpty() ? List.of(group(catalog)) : List.of(group(catalog), group(addedImages));
                case "rawImages" -> rawCatalog;
                case "registerImageAndBind" -> {
                    writes++;
                    var content = (AuthorizedImageContent) args[0];
                    content.claim();
                    assertEquals(new ArtMeshId("mesh"), args[1]);
                    if (nativeFailure != null) throw nativeFailure;
                    addedImages = List.of(new ModelImageEntry() {
                        public ModelImageId id() {
                            return new ModelImageId("registered");
                        }

                        public String name() {
                            return content.sourceDescription();
                        }

                        public int width() {
                            return content.width();
                        }

                        public int height() {
                            return content.height();
                        }
                    });
                    var raws = new ArrayList<>(rawCatalog);
                    raws.add(new RawTexture() {
                        public RawImageId id() {
                            return new RawImageId("raw-registered");
                        }

                        public String name() {
                            return content.sourceDescription();
                        }

                        public int width() {
                            return content.width();
                        }

                        public int height() {
                            return content.height();
                        }
                    });
                    rawCatalog = List.copyOf(raws);
                    input = Optional.of(bound(wrongInput ? "image-b" : "registered"));
                    afterWrite.run();
                    if (throwBind) throw new IllegalStateException("partial registration");
                    yield new ModelImageBinding(
                            "test",
                            new ModelId(wrongModel ? "wrong" : "model"),
                            wrongGeneration ? 43 : 42,
                            new RawImageId("raw-registered"),
                            new ModelImageId(wrongHandle ? "image-b" : "registered"),
                            Optional.of(new ArtMeshId(wrongMesh ? "wrong" : "mesh")));
                }
                case "textureInput" -> {
                    onObserve.run();
                    yield input;
                }
                case "bindModelImage" -> {
                    writes++;
                    assertEquals(new ArtMeshId("mesh"), args[1]);
                    assertEquals(new ModelImageId("image-a"), args[0]);
                    if (nativeFailure != null) throw nativeFailure;
                    input = Optional.of(bound(wrongInput ? "image-b" : "image-a"));
                    afterWrite.run();
                    if (throwBind) throw new IllegalStateException("partial");
                    yield new ModelImageBinding(
                            "test",
                            new ModelId(wrongModel ? "other-model" : "model"),
                            wrongGeneration ? 43 : 42,
                            new RawImageId("raw"),
                            new ModelImageId(wrongHandle ? "image-b" : "image-a"),
                            Optional.of(new ArtMeshId(wrongMesh ? "other-mesh" : "mesh")));
                }
                default -> throw new AssertionError(name);
            };
        });
        final CubismModel model = proxy(CubismModel.class, (name, args) -> {
            checkHost();
            return switch (name) {
                case "textures" -> textures;
                case "drawables" ->
                    proxy(Drawables.class, (n, a) -> {
                        assertEquals(new ArtMeshId("mesh"), a[0]);
                        return drawable;
                    });
                default -> throw new AssertionError(name);
            };
        });
        final AuthoringTransactionService service = new AuthoringTransactionService() {
            public <T> AuthoringTransactionResult<T> execute(
                    AuthoringTransactionOptions options, AuthoringTransactionWork<T> work) {
                checkHost();
                transactions++;
                if (unavailable) return AuthoringTransactionResult.unavailable("test.unavailable");
                var start = history();
                var original = input;
                var originalMesh = mesh;
                var originalCatalog = catalog;
                var originalAdded = addedImages;
                var originalRaw = rawCatalog;
                String originalParent = parent, originalPart = part;
                boolean originalLock = locked;
                T value;
                try {
                    before.run();
                    value = work.run();
                } catch (Exception failure) {
                    var receipt =
                            new AuthoringTransactionReceipt("tx", options.label(), start, history(), Optional.empty());
                    if (recoveryFailed) return AuthoringTransactionResult.recoveryFailed(receipt, "test.recovery");
                    input = original;
                    mesh = originalMesh;
                    catalog = originalCatalog;
                    addedImages = originalAdded;
                    rawCatalog = originalRaw;
                    parent = originalParent;
                    part = originalPart;
                    locked = originalLock;
                    return AuthoringTransactionResult.rolledBack(receipt, "test.rollback");
                }
                boolean changed = !input.equals(original);
                if (changed) revision++;
                var receipt = new AuthoringTransactionReceipt(
                        "tx", options.label(), start, history(), changed ? Optional.of("entry") : Optional.empty());
                afterCommit.run();
                if (loseResult) throw new IllegalStateException("response loss");
                return changed
                        ? AuthoringTransactionResult.committed(value, receipt)
                        : AuthoringTransactionResult.noChange(value, receipt);
            }
        };
        final CubismFacade facade = proxy(CubismFacade.class, (name, args) -> {
            checkHost();
            if (name.equals("authoringTransactions")) return service;
            throw new AssertionError(name);
        });
        final McpExecutionBridge execution = new McpExecutionBridge(new UiScheduler() {
            public Registration runOnUiThread(Runnable task) {
                task.run();
                return () -> {};
            }

            public Registration runOnUiThreadLater(Runnable task, Duration delay) {
                throw new AssertionError();
            }
        });
        final McpImageBindingDomain domain = new McpImageBindingDomain(facade, execution, store, () -> {
            checkHost();
            return new McpStoredAuthoringDomain.Context(model, binding());
        });
        final McpToolCatalog tools = domain.tools();

        void checkHost() {
            if (forbidHost) throw new AssertionError("Result/replay must not query host");
        }

        Map<String, Object> binding() {
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    modelId,
                    "historyGeneration",
                    generation,
                    "historyRevision",
                    revision,
                    "historyDocumentBinding",
                    "document-binding",
                    "historyManagerBinding",
                    manager);
        }

        HistorySnapshot history() {
            return new HistorySnapshot(
                    HistorySnapshot.Availability.AVAILABLE,
                    generation,
                    revision,
                    0,
                    List.of(),
                    false,
                    false,
                    "document-binding",
                    manager);
        }

        Map<String, Object> readArgs() {
            var parentRow = new LinkedHashMap<String, Object>();
            parentRow.put("kind", "parent_space");
            parentRow.put("parentPartId", part);
            parentRow.put("parentDeformerId", parent);
            var issued = store.put(
                    McpRequestRegistry.currentSessionId(),
                    binding(),
                    "art_mesh",
                    "mesh",
                    mesh.sourceGuid(),
                    mesh,
                    List.of(parentRow),
                    1024);
            return Map.of(
                    "documentId",
                    document,
                    "modelId",
                    modelId,
                    "objectId",
                    "mesh",
                    "authoringSnapshotId",
                    issued.snapshotId());
        }

        Map<String, Object> read() {
            return call(McpImageBindingDomain.READ, readArgs());
        }

        Map<String, Object> request(String id, Map<String, Object> read, String image) {
            assertEquals(true, read.get("ok"), read.toString());
            return new LinkedHashMap<>(Json.parseObject(Json.stringify(Map.of(
                    "documentId",
                    document,
                    "modelId",
                    modelId,
                    "objectId",
                    "mesh",
                    "snapshotId",
                    read.get("snapshotId"),
                    "operationId",
                    id,
                    "modelImageId",
                    image))));
        }

        Map<String, Object> bind(Map<String, Object> args) {
            return call(McpImageBindingDomain.BIND, args);
        }

        Map<String, Object> result(String id) {
            return call(McpImageBindingDomain.RESULT, Map.of("operationId", id));
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(tools.call(name, args).get("structuredContent"));
        }
    }
}
