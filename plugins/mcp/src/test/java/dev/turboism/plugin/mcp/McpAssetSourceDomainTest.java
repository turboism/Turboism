package dev.turboism.plugin.mcp;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.UiScheduler;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.*;
import java.util.function.BiFunction;
import java.util.function.IntConsumer;
import org.junit.jupiter.api.Test;

class McpAssetSourceDomainTest {
    @Test
    void fullGraphKeepsRawLayerIdentityCurrentSourceCandidatesGroupsAndUsageDistinct() {
        var f = new Fixture();
        var output = f.relations(Map.of("limit", 256));
        assertEquals(true, output.get("ok"), output.toString());
        assertEquals("RELATIONS", output.get("projection"));
        var rows = rows(output);
        assertEquals("PSD", kind(rows, "raw_image_details").get("sourceKind"));
        assertEquals(
                1,
                rows.stream().filter(r -> "UNKNOWN".equals(r.get("sourceKind"))).count());
        var layers =
                rows.stream().filter(r -> "raw_layer".equals(r.get("kind"))).toList();
        assertEquals(3, layers.size());
        assertEquals("group", layers.get(1).get("parentRawLayerId"));
        assertEquals("7", layers.get(1).get("psdLayerId"));
        assertEquals(layers.get(1).get("rawLayerId"), layers.get(2).get("rawLayerId"));
        assertNotEquals(layers.get(1).get("rawImageId"), layers.get(2).get("rawImageId"));
        var current = rows.stream()
                .filter(r -> "model_image".equals(r.get("kind")) && "image-a".equals(r.get("modelImageId")))
                .findFirst()
                .orElseThrow();
        assertEquals("raw-a", current.get("currentRawImageId"));
        assertEquals(List.of("raw-b", "raw-a"), current.get("linkedRawImageIds"));
        assertEquals(true, current.get("currentSourceResolved"));
        var absent = rows.stream()
                .filter(r -> "model_image".equals(r.get("kind")) && "image-b".equals(r.get("modelImageId")))
                .findFirst()
                .orElseThrow();
        assertNull(absent.get("currentRawImageId"));
        assertEquals(false, absent.get("currentSourceResolved"));
        assertEquals(List.of("raw-b"), absent.get("linkedRawImageIds"));
        var input = kind(rows, "layer_input");
        assertEquals("UNKNOWN", input.get("transformAvailability"));
        assertEquals("AVAILABLE", input.get("clippingAvailability"));
        assertEquals(true, input.get("rawLayerResolved"));
        assertFalse(input.containsKey("transform"));
        assertFalse(input.containsKey("clipping"));
        var group = kind(rows, "model_image_group");
        assertEquals(0, group.get("groupIndex"));
        assertFalse(group.containsKey("guid"));
        assertEquals(2, f.relationReads);
        assertEquals(0, f.sourceReads);
        assertEquals("OBSERVATION_SEQUENCE", map(output.get("observation")).get("revisionKind"));
        assertEquals(true, map(output.get("observation")).get("historical"));
        assertFalse(output.toString().contains("relativePath"));
        assertFalse(output.toString().contains("base64"));
    }

    @Test
    void scopedSourcesNeverLoadRelationsOrSubstituteCandidatesAndMissingAnchorsRemainExplicit() {
        var f = new Fixture();
        var output =
                f.sources(List.of("mesh-a", "missing-mesh"), List.of("image-b", "missing-image"), Map.of("limit", 256));
        assertEquals(true, output.get("ok"), output.toString());
        assertEquals(2, f.sourceReads);
        assertEquals(0, f.relationReads);
        assertEquals(1, ((Number) map(output.get("counts")).get("rawImages")).intValue());
        assertTrue(rows(output).stream()
                .noneMatch(r -> "raw_layer".equals(r.get("kind")) || "model_image_group".equals(r.get("kind"))));
        assertEquals(
                2,
                rows(output).stream()
                        .filter(r -> "unresolved_anchor".equals(r.get("kind")))
                        .count());
        var missingCurrent = rows(output).stream()
                .filter(r -> "model_image_source".equals(r.get("kind")) && "image-b".equals(r.get("modelImageId")))
                .findFirst()
                .orElseThrow();
        assertNull(missingCurrent.get("currentRawImageId"));
        assertFalse(rows(output).stream().anyMatch(r -> "raw-b".equals(r.get("rawImageId"))));
    }

    @Test
    void absentCurrentRawMetadataAndUnknownTextureInputsAreNotResolvedByGuessing() {
        var f = new Fixture();
        f.raws = List.of(raw("raw-b", "candidate", List.of()));
        var output = f.sources(List.of("mesh-a", "mesh-unknown"), List.of(), Map.of("limit", 256));
        assertEquals(true, output.get("ok"), output.toString());
        var current = kind(rows(output), "model_image_source");
        assertEquals("raw-a", current.get("currentRawImageId"));
        assertEquals(false, current.get("currentSourceResolved"));
        var mesh = rows(output).stream()
                .filter(r -> "art_mesh".equals(r.get("kind")) && "mesh-unknown".equals(r.get("objectId")))
                .findFirst()
                .orElseThrow();
        assertNull(mesh.get("currentInputIndex"));
        var unknown = rows(output).stream()
                .filter(r -> "art_mesh_input".equals(r.get("kind")) && "mesh-unknown".equals(r.get("objectId")))
                .toList();
        assertEquals(
                List.of("UNKNOWN", "UNAVAILABLE"),
                unknown.stream().map(r -> r.get("resolutionState")).toList());
        assertTrue(unknown.stream().noneMatch(r -> Boolean.TRUE.equals(r.get("current"))));
        assertTrue(rows(output).stream()
                .anyMatch(r -> "ATLAS".equals(r.get("inputKind")) && Boolean.TRUE.equals(r.get("current"))));
    }

    @Test
    void emptyScopedQueryAndAvailableEmptyGraphDifferFromUnavailable() {
        var f = new Fixture();
        var empty = f.sources(List.of(), List.of(), Map.of());
        assertEquals(true, empty.get("ok"));
        assertEquals(List.of(), rows(empty));
        assertEquals(0, f.relationReads);
        f.raws = List.of();
        f.images = List.of();
        f.meshes = List.of();
        f.groups = List.of();
        var graph = f.relations(Map.of());
        assertEquals(true, graph.get("ok"));
        assertEquals(List.of(), rows(graph));
        f.relationsUnavailable = true;
        assertEquals("UNAVAILABLE", f.relations(Map.of()).get("error"));
        var g = new Fixture();
        g.sourcesUnavailable = true;
        assertEquals(
                "UNAVAILABLE", g.sources(List.of("mesh-a"), List.of(), Map.of()).get("error"));
        assertEquals(0, g.relationReads);
    }

    @Test
    void allPagesUseOneImmutableObservationAndNeverRereadTheGraph() {
        var f = new Fixture();
        var first = f.relations(Map.of("limit", 2));
        assertEquals(true, first.get("ok"));
        f.raws = List.of(raw("changed", "changed", List.of()));
        f.forbidTextures = true;
        var all = new ArrayList<>(rows(first));
        var page = first;
        while (page.get("nextOffset") != null) {
            page = f.relations(
                    Map.of("snapshotId", first.get("snapshotId"), "offset", page.get("nextOffset"), "limit", 2));
            assertEquals(true, page.get("ok"), page.toString());
            all.addAll(rows(page));
            assertEquals(first.get("observation"), page.get("observation"));
            assertEquals(first.get("counts"), page.get("counts"));
        }
        assertEquals(((Number) first.get("totalRows")).intValue(), all.size());
        assertTrue(all.stream().noneMatch(r -> "changed".equals(r.get("rawImageId"))));
        assertEquals(2, f.relationReads);
        f.revision++;
        assertEquals(
                "STALE_STATE",
                f.relations(Map.of("snapshotId", first.get("snapshotId"))).get("error"));
    }

    @Test
    void changingProjectedDataEpochOrHistoryBetweenCapturesRejectsRead() {
        for (String fault : List.of("data", "binding", "generation", "history", "reverseRevision")) {
            var f = new Fixture();
            f.onRead = n -> {
                if (n != 2) return;
                switch (fault) {
                    case "data" -> f.raws = List.of(raw("different", "changed", List.of()));
                    case "binding" -> f.nativeBinding = "different";
                    case "generation" -> f.nativeGeneration++;
                    case "history" -> f.revision++;
                    case "reverseRevision" -> f.nativeRevision = -1;
                }
            };
            var output = f.relations(Map.of());
            assertEquals("STALE_STATE", output.get("error"), fault + output);
            assertFalse(output.containsKey("snapshotId"));
        }
    }

    @Test
    void foreignSessionWrongViewQueryAndExpiredTokensCannotBeReused() {
        var f = new Fixture();
        var first =
                McpRequestRegistry.inSession("a", () -> f.sources(List.of("mesh-a"), List.of(), Map.of("limit", 1)));
        var request = Map.<String, Object>of("snapshotId", first.get("snapshotId"), "limit", 1);
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("b", () -> f.sources(List.of("mesh-a"), List.of(), request))
                        .get("error"));
        assertEquals(
                "STALE_STATE",
                McpRequestRegistry.inSession("a", () -> f.sources(List.of(), List.of(), request))
                        .get("error"));
        assertEquals(
                "STALE_STATE",
                McpRequestRegistry.inSession("a", () -> f.relations(request)).get("error"));
        f.now = McpAuthoringSnapshotStore.TTL_NANOS - 1;
        assertEquals(
                true,
                McpRequestRegistry.inSession("a", () -> f.sources(List.of("mesh-a"), List.of(), request))
                        .get("ok"));
        f.now++;
        assertEquals(
                "NOT_FOUND",
                McpRequestRegistry.inSession("a", () -> f.sources(List.of("mesh-a"), List.of(), request))
                        .get("error"));
        assertEquals(2, f.sourceReads);
    }

    @Test
    void duplicateLayerAndResourceIdentitiesOrInconsistentMetadataFailClosed() {
        for (String fault :
                List.of("raw", "image", "mesh", "layer", "owner", "dimensions", "metadataId", "binding", "unicode")) {
            var f = new Fixture();
            switch (fault) {
                case "raw" -> f.raws = List.of(f.raws.get(0), f.raws.get(0));
                case "image" -> f.images = List.of(f.images.get(0), f.images.get(0));
                case "mesh" -> f.meshes = List.of(f.meshes.get(0), f.meshes.get(0));
                case "layer" ->
                    f.raws = List.of(raw(
                            "raw-a",
                            "raw",
                            List.of(layer("raw-a", "same", List.of()), layer("raw-a", "same", List.of()))));
                case "owner" -> f.raws = List.of(raw("raw-a", "raw", List.of(layer("raw-b", "layer", List.of()))));
                case "dimensions" ->
                    f.raws = List.of(new RawImageDetails(
                            rawTexture("raw-a", "raw", 0),
                            RawImageDetails.SourceKind.UNKNOWN,
                            List.of(),
                            false,
                            Optional.empty(),
                            Optional.empty(),
                            Optional.empty()));
                case "metadataId" -> {
                    var old = f.images.get(0);
                    f.images = List.of(new ModelImageRelation(
                            old.id(),
                            image("other"),
                            old.linkedRawImageIds(),
                            old.currentRawImageId(),
                            old.inputsByRawImage(),
                            old.usingArtMeshIds()));
                }
                case "binding" -> f.nativeBinding = "";
                case "unicode" -> f.nativeBinding = "invalid-" + (char) 0xd800;
            }
            assertEquals("INVALID_SOURCE_DATA", f.relations(Map.of()).get("error"), fault);
        }
    }

    @Test
    void scopedBackendCannotReturnAnUnrequestedGlobalDump() {
        for (String kind : List.of("mesh", "image", "raw")) {
            var f = new Fixture();
            f.extraScoped = kind;
            assertEquals(
                    "INVALID_SOURCE_DATA",
                    f.sources(List.of(), List.of(), Map.of()).get("error"),
                    kind);
            assertEquals(0, f.relationReads);
        }
    }

    @Test
    void missingLayerReferencesStayUnresolvedWithoutUsingNumericPsdIdsAsRawLayerIds() {
        var f = new Fixture();
        var old = f.images.get(0);
        f.images = List.of(new ModelImageRelation(
                old.id(),
                old.modelImage(),
                old.linkedRawImageIds(),
                old.currentRawImageId(),
                Map.of(
                        new RawImageId("raw-a"),
                        List.of(new RawLayerBinding(
                                new RawImageId("raw-a"),
                                new RawLayerId("7"),
                                2,
                                RawLayerBinding.DetailAvailability.UNKNOWN,
                                RawLayerBinding.DetailAvailability.UNAVAILABLE))),
                old.usingArtMeshIds()));
        var output = f.relations(Map.of("limit", 256));
        var binding = kind(rows(output), "layer_input");
        assertEquals(true, binding.get("rawImageResolved"));
        assertEquals(false, binding.get("rawLayerResolved"));
        assertEquals("7", binding.get("rawLayerId"));
    }

    @Test
    void overBudgetTreesCountsLinksAndSingleRowsAreRejectedBeforeIssuingSnapshot() {
        for (String fault : List.of("rawCount", "depth", "layerCount", "links", "row", "bytes")) {
            var f = new Fixture();
            switch (fault) {
                case "rawCount" -> {
                    var values = new ArrayList<RawImageDetails>();
                    for (int i = 0; i < 1025; i++) values.add(raw("r" + i, "r", List.of()));
                    f.raws = values;
                }
                case "depth" -> {
                    var tree = layer("raw-a", "leaf", List.of());
                    for (int i = 0; i < 64; i++) tree = layer("raw-a", "parent" + i, List.of(tree));
                    f.raws = List.of(raw("raw-a", "r", List.of(tree)));
                }
                case "layerCount" -> {
                    var layers = new ArrayList<RawLayerDetails>();
                    for (int i = 0; i < 8193; i++) layers.add(layer("raw-a", "l" + i, List.of()));
                    f.raws = List.of(raw("raw-a", "r", layers));
                }
                case "links" ->
                    f.groups = List.of(group(java.util.stream.IntStream.range(0, 16385)
                            .mapToObj(i -> new ModelImageId("image" + i))
                            .toList()));
                case "row" ->
                    f.groups = List.of(group(java.util.stream.IntStream.range(0, 64)
                            .mapToObj(i -> new ModelImageId("g".repeat(4000) + i))
                            .toList()));
                case "bytes" -> {
                    var values = new ArrayList<RawImageDetails>();
                    for (int i = 0; i < 1024; i++) values.add(raw("r" + i, "x".repeat(4096), List.of()));
                    f.raws = values;
                }
            }
            var output = f.relations(Map.of());
            assertEquals("BUDGET_EXCEEDED", output.get("error"), fault + output);
            assertFalse(output.containsKey("snapshotId"));
        }
    }

    @Test
    void byteLimitedPagesShrinkWithoutSilentlyDroppingRowsAndCacheEvictionIsBounded() {
        var f = new Fixture();
        var values = new ArrayList<RawImageDetails>();
        for (int i = 0; i < 80; i++) values.add(raw("r" + i, "x".repeat(4000), List.of()));
        f.raws = values;
        var first = f.relations(Map.of("limit", 256));
        assertEquals(true, first.get("ok"), first.toString());
        assertNotNull(first.get("nextOffset"));
        assertTrue(McpAssetSourceProjection.bytes(first) <= 240 * 1024);
        int count = rows(first).size();
        var page = first;
        while (page.get("nextOffset") != null) {
            page = f.relations(
                    Map.of("snapshotId", first.get("snapshotId"), "offset", page.get("nextOffset"), "limit", 256));
            count += rows(page).size();
        }
        assertEquals(((Number) first.get("totalRows")).intValue(), count);
        var g = new Fixture();
        var old = g.relations(Map.of());
        for (int i = 0; i < 16; i++) g.relations(Map.of());
        assertEquals(
                "NOT_FOUND",
                g.relations(Map.of("snapshotId", old.get("snapshotId"))).get("error"));
    }

    @Test
    void totalEncodedCacheBudgetEvictsBeforeEntryCountLimit() {
        var f = new Fixture();
        var values = new ArrayList<RawImageDetails>();
        String name = "x".repeat(3500);
        for (int i = 0; i < 800; i++) values.add(raw("r" + i, name, List.of()));
        f.raws = values;
        var first = f.relations(Map.of("limit", 1));
        assertEquals(true, first.get("ok"), first.toString());
        Map<String, Object> latest = first;
        for (int i = 0; i < 6; i++) latest = f.relations(Map.of("limit", 1));
        assertEquals(true, latest.get("ok"));
        assertEquals(
                "NOT_FOUND",
                f.relations(Map.of("snapshotId", first.get("snapshotId"))).get("error"));
        f.forbidTextures = true;
        assertEquals(
                true,
                f.relations(Map.of("snapshotId", latest.get("snapshotId"), "limit", 1))
                        .get("ok"));
    }

    @Test
    void invalidRequestsPermissionRevocationAndUnavailableReadsDoNotLeakNativeFailures() {
        var f = new Fixture();
        var bad = new LinkedHashMap<String, Object>(
                Map.of("documentId", "doc", "modelId", "model", "path", "/private/secret.psd"));
        assertThrows(IllegalArgumentException.class, () -> f.call(McpAssetSourceDomain.RELATIONS, bad));
        assertEquals(0, f.relationReads);
        for (RuntimeException failure : List.of(
                new SecurityException("secret"),
                new UnsupportedOperationException("secret"),
                new IllegalStateException("secret"))) {
            var g = new Fixture();
            g.failure = failure;
            var output = g.relations(Map.of());
            assertEquals(false, output.get("ok"));
            assertFalse(output.toString().contains("secret"));
        }
        var g = new Fixture();
        var first = g.relations(Map.of());
        g.contextFailure = new SecurityException("private");
        assertEquals(
                "PERMISSION_DENIED",
                g.relations(Map.of("snapshotId", first.get("snapshotId"))).get("error"));
        var h = new Fixture();
        var wrong = Map.<String, Object>of("documentId", "other", "modelId", "model");
        assertEquals(
                "STALE_STATE", h.call(McpAssetSourceDomain.RELATIONS, wrong).get("error"));
        assertEquals(0, h.relationReads);
    }

    static final class Fixture {
        long now, revision, nativeGeneration = 3, nativeRevision;
        String nativeBinding = "native-model", extraScoped = "";
        int sourceReads, relationReads;
        boolean sourcesUnavailable, relationsUnavailable, forbidTextures;
        RuntimeException failure, contextFailure;
        IntConsumer onRead = n -> {};
        List<RawImageDetails> raws = List.of(
                raw("raw-a", "layers.psd", List.of(layer("raw-a", "group", List.of(layer("raw-a", "eye", List.of()))))),
                raw("raw-b", "reopened.psd", List.of(layer("raw-b", "eye", List.of()))));
        List<ModelImageRelation> images = List.of(
                new ModelImageRelation(
                        new ModelImageId("image-a"),
                        image("image-a"),
                        List.of(new RawImageId("raw-b"), new RawImageId("raw-a")),
                        Optional.of(new RawImageId("raw-a")),
                        Map.of(
                                new RawImageId("raw-a"),
                                List.of(new RawLayerBinding(
                                        new RawImageId("raw-a"),
                                        new RawLayerId("eye"),
                                        0,
                                        RawLayerBinding.DetailAvailability.UNKNOWN,
                                        RawLayerBinding.DetailAvailability.AVAILABLE))),
                        List.of(new ArtMeshId("mesh-a"))),
                new ModelImageRelation(
                        new ModelImageId("image-b"),
                        image("image-b"),
                        List.of(new RawImageId("raw-b")),
                        Optional.empty(),
                        Map.of(),
                        List.of(new ArtMeshId("mesh-a"))));
        List<ModelImageGroupRelation> groups =
                List.of(group(List.of(new ModelImageId("image-a"), new ModelImageId("image-b"))));
        List<ArtMeshTextureInputs> meshes = List.of(
                new ArtMeshTextureInputs(
                        new ArtMeshId("mesh-a"),
                        List.of(
                                TextureInputBinding.modelImage(new ModelImageId("image-a")),
                                TextureInputBinding.modelImage(new ModelImageId("image-b")),
                                TextureInputBinding.atlas(new TextureAtlasId("atlas"))),
                        OptionalInt.of(2)),
                new ArtMeshTextureInputs(
                        new ArtMeshId("mesh-unknown"),
                        List.of(TextureInputBinding.unknown(), TextureInputBinding.unavailable()),
                        OptionalInt.empty()));
        final McpToolCatalog tools;

        Fixture() {
            var textures = proxy(ModelTextures.class, (n, a) -> {
                if (forbidTextures) throw new AssertionError("Cached page may not read textures");
                if (failure != null) throw failure;
                if (n.equals("relations")) {
                    relationReads++;
                    onRead.accept(sourceReads + relationReads);
                    if (relationsUnavailable) return TextureRelationsSnapshot.unavailable();
                    return new TextureRelationsSnapshot(
                            TextureRelationsSnapshot.Availability.AVAILABLE,
                            nativeBinding,
                            nativeGeneration,
                            ++nativeRevision,
                            raws,
                            images,
                            groups,
                            meshes);
                }
                if (n.equals("sources")) {
                    sourceReads++;
                    onRead.accept(sourceReads + relationReads);
                    if (sourcesUnavailable) return TextureSourcesSnapshot.unavailable();
                    var query = (TextureSourceQuery) a[0];
                    var selected = new ArrayList<>(meshes.stream()
                            .filter(m -> query.artMeshes().contains(m.id()))
                            .toList());
                    var mids = new HashSet<>(query.modelImages());
                    for (var mesh : selected)
                        if (mesh.currentInputIndex().isPresent()) {
                            var current =
                                    mesh.inputs().get(mesh.currentInputIndex().getAsInt());
                            if (current.kind() == TextureInputBinding.Kind.MODEL_IMAGE)
                                current.modelImageId().ifPresent(mids::add);
                            else if (current.kind() == TextureInputBinding.Kind.ATLAS)
                                mesh.inputs()
                                        .forEach(input -> input.modelImageId().ifPresent(mids::add));
                        }
                    var modelSources = new ArrayList<>(images.stream()
                            .filter(i -> mids.contains(i.id()))
                            .map(i -> new TextureSourcesSnapshot.ModelImageSource(i.id(), i.currentRawImageId()))
                            .toList());
                    var rids = new HashSet<RawImageId>();
                    modelSources.forEach(i -> i.currentRawImageId().ifPresent(rids::add));
                    var selectedRaws = new ArrayList<>(raws.stream()
                            .filter(r -> rids.contains(r.id()))
                            .map(RawImageDetails::rawImage)
                            .toList());
                    if (extraScoped.equals("mesh")) selected.add(meshes.get(0));
                    if (extraScoped.equals("image"))
                        modelSources.add(new TextureSourcesSnapshot.ModelImageSource(
                                new ModelImageId("foreign"), Optional.empty()));
                    if (extraScoped.equals("raw")) selectedRaws.add(raws.get(0).rawImage());
                    return new TextureSourcesSnapshot(
                            TextureSourcesSnapshot.Availability.AVAILABLE,
                            nativeBinding,
                            nativeGeneration,
                            ++nativeRevision,
                            selectedRaws,
                            modelSources,
                            selected);
                }
                throw new AssertionError("No write/PSD/pixel/full graph fallback: " + n);
            });
            var model = proxy(CubismModel.class, (n, a) -> {
                assertEquals("textures", n);
                return textures;
            });
            var execution = new McpExecutionBridge(new UiScheduler() {
                public Registration runOnUiThread(Runnable work) {
                    work.run();
                    return () -> {};
                }

                public Registration runOnUiThreadLater(Runnable work, Duration delay) {
                    throw new AssertionError();
                }
            });
            tools = new McpAssetSourceDomain(
                            execution,
                            () -> {
                                if (contextFailure != null) throw contextFailure;
                                return new McpStoredAuthoringDomain.Context(
                                        model,
                                        Map.of(
                                                "documentId",
                                                "doc",
                                                "modelId",
                                                "model",
                                                "historyGeneration",
                                                1L,
                                                "historyRevision",
                                                revision,
                                                "historyDocumentBinding",
                                                "doc-binding",
                                                "historyManagerBinding",
                                                "manager"));
                            },
                            () -> now)
                    .tools();
        }

        Map<String, Object> relations(Map<String, Object> extra) {
            var args = new LinkedHashMap<String, Object>(Map.of("documentId", "doc", "modelId", "model"));
            args.putAll(extra);
            return call(McpAssetSourceDomain.RELATIONS, args);
        }

        Map<String, Object> sources(List<String> meshes, List<String> images, Map<String, Object> extra) {
            var args = new LinkedHashMap<String, Object>(Map.of(
                    "documentId",
                    "doc",
                    "modelId",
                    "model",
                    "query",
                    Map.of("artMeshIds", meshes, "modelImageIds", images)));
            args.putAll(extra);
            return call(McpAssetSourceDomain.SOURCES, args);
        }

        Map<String, Object> call(String name, Map<String, Object> args) {
            return map(tools.call(name, args).get("structuredContent"));
        }
    }

    static RawImageDetails raw(String id, String name, List<RawLayerDetails> layers) {
        return new RawImageDetails(
                rawTexture(id, name, 100),
                id.equals("raw-a") ? RawImageDetails.SourceKind.PSD : RawImageDetails.SourceKind.UNKNOWN,
                layers,
                false,
                Optional.of("observed-import-time"),
                Optional.empty(),
                Optional.empty());
    }

    static RawTexture rawTexture(String id, String name, int width) {
        return proxy(RawTexture.class, (n, a) -> switch (n) {
            case "id" -> new RawImageId(id);
            case "name" -> name;
            case "width" -> width;
            case "height" -> 100;
            default -> throw new AssertionError(n);
        });
    }

    static RawLayerDetails layer(String owner, String id, List<RawLayerDetails> children) {
        return new RawLayerDetails(
                new RawLayerId(id),
                new RawImageId(owner),
                children.isEmpty() ? RawLayerDetails.EntryKind.PIXEL : RawLayerDetails.EntryKind.GROUP,
                id,
                Optional.of("7"),
                children);
    }

    static ModelImageEntry image(String id) {
        return proxy(ModelImageEntry.class, (n, a) -> switch (n) {
            case "id" -> new ModelImageId(id);
            case "name" -> "image";
            case "width", "height" -> 100;
            default -> throw new AssertionError(n);
        });
    }

    static ModelImageGroupRelation group(List<ModelImageId> ids) {
        return new ModelImageGroupRelation(
                proxy(ModelImageGroup.class, (n, a) -> switch (n) {
                    case "groupName" -> "group";
                    case "memo" -> "memo";
                    default -> throw new AssertionError(n);
                }),
                ids,
                List.of(new RawImageId("raw-a")),
                Optional.empty());
    }

    static Map<String, Object> kind(List<Map<String, Object>> rows, String kind) {
        return rows.stream().filter(r -> kind.equals(r.get("kind"))).findFirst().orElseThrow();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object v) {
        return (Map<String, Object>) v;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> rows(Map<String, Object> value) {
        return (List<Map<String, Object>>) value.get("rows");
    }

    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> body) {
        return (T) Proxy.newProxyInstance(
                type.getClassLoader(), new Class<?>[] {type}, (o, m, a) -> body.apply(m.getName(), a));
    }
}
