package dev.turboism.plugin.mcp;

import static dev.turboism.plugin.mcp.McpWriteResults.*;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Session-owned immutable source metadata pages; never opens PSDs or accesses image bytes. */
final class McpAssetSourceDomain {
    static final String SOURCES = "turboism.assets.sources.read", RELATIONS = "turboism.assets.relations.read";
    static final int MAX_ENTRIES = 16, MAX_TOTAL_BYTES = 16 * 1024 * 1024, MAX_PAGE_BYTES = 240 * 1024;
    private final McpExecutionBridge execution;
    private final Supplier<McpStoredAuthoringDomain.Context> context;
    private final LongSupplier clock;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private int retainedBytes;

    McpAssetSourceDomain(McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context) {
        this(execution, context, System::nanoTime);
    }

    McpAssetSourceDomain(
            McpExecutionBridge execution, Supplier<McpStoredAuthoringDomain.Context> context, LongSupplier clock) {
        this.execution = Objects.requireNonNull(execution);
        this.context = Objects.requireNonNull(context);
        this.clock = Objects.requireNonNull(clock);
    }

    McpToolCatalog tools() {
        var versions = ReviewedVersions.since("5.0.00").stream().sorted().toList();
        var tools = new ArrayList<McpRegisteredTool>();
        for (String name : List.of(SOURCES, RELATIONS)) {
            tools.add(McpRegisteredTool.typed(
                    McpAssetSourceSchemas.definition(name),
                    McpOperationEffect.READ,
                    McpExecutionAffinity.UI_THREAD,
                    false,
                    McpVersionSupport.exact(
                            "cubism.editor-model.texture.read",
                            versions,
                            List.of(
                                    McpVersionSupport.OperationSupport.available(
                                            name.equals(SOURCES) ? "scoped_sources" : "texture_relations",
                                            McpOperationEffect.READ,
                                            false,
                                            McpVersionSupport.UndoVerification.NOT_APPLICABLE,
                                            versions,
                                            "Typed exact-host source metadata only; current source and candidates remain distinct. Runtime permissions/selectors/generation remain authoritative. No pixels, file grants, semantic inference or real-host acceptance."))),
                    execution,
                    args -> read(name, args)));
        }
        return McpToolCatalog.of(tools);
    }

    private Map<String, Object> read(String name, Map<String, Object> args) {
        try {
            var allowed = new HashSet<>(Set.of("documentId", "modelId", "snapshotId", "offset", "limit"));
            if (name.equals(SOURCES)) allowed.add("query");
            if (!allowed.containsAll(args.keySet()) || !args.keySet().containsAll(Set.of("documentId", "modelId")))
                throw new IllegalArgumentException();
            var query = name.equals(SOURCES) ? query(args.get("query")) : new TextureSourceQuery(Set.of(), Set.of());
            String queryHash = digest(Map.of(
                    "artMeshIds",
                    query.artMeshes().stream().map(ArtMeshId::value).sorted().toList(),
                    "modelImageIds",
                    query.modelImages().stream()
                            .map(ModelImageId::value)
                            .sorted()
                            .toList()));
            int offset = integer(args.getOrDefault("offset", 0), 0, Integer.MAX_VALUE),
                    limit = integer(args.getOrDefault("limit", 128), 1, 256);
            String session = McpRequestRegistry.currentSessionId();
            var current = context.get();
            if (!text(args.get("documentId"), 4096).equals(current.binding().get("documentId"))
                    || !text(args.get("modelId"), 4096).equals(current.binding().get("modelId"))) throw new Stale();
            if (McpAssetSourceProjection.bytes(current.binding()) > 32 * 1024)
                throw new McpAssetSourceProjection.Budget();
            Entry entry;
            boolean issued = !args.containsKey("snapshotId");
            if (issued) {
                if (offset != 0) throw new IllegalArgumentException();
                var first = observe(name, current.model(), query);
                if (!current.binding().equals(context.get().binding())) throw new Stale();
                var second = observe(name, current.model(), query);
                if (!first.sameData(second)
                        || second.revision() < first.revision()
                        || !current.binding().equals(context.get().binding())) throw new Stale();
                String id = UUID.randomUUID().toString();
                long created = clock.getAsLong();
                int encoded = McpAssetSourceProjection.bytes(Map.of(
                        "id",
                        id,
                        "session",
                        session,
                        "name",
                        name,
                        "queryHash",
                        queryHash,
                        "state",
                        current.binding(),
                        "created",
                        created,
                        "observation",
                        Map.of(
                                "binding",
                                second.binding(),
                                "generation",
                                second.generation(),
                                "revision",
                                second.revision(),
                                "rows",
                                second.rows(),
                                "counts",
                                second.counts())));
                if (encoded > McpAssetSourceProjection.MAX_BYTES) throw new McpAssetSourceProjection.Budget();
                entry = new Entry(
                        id, session, name, queryHash, Map.copyOf(current.binding()), second, created, encoded);
            } else {
                synchronized (entries) {
                    prune();
                    entry = entries.get(text(args.get("snapshotId"), 128));
                    if (entry == null || !entry.session.equals(session)) throw new NoSuchElementException();
                }
                if (!entry.name.equals(name)
                        || !entry.queryHash.equals(queryHash)
                        || !entry.state.equals(current.binding())) throw new Stale();
            }
            var output = page(entry, offset, limit);
            if (issued)
                synchronized (entries) {
                    prune();
                    while (entries.size() >= MAX_ENTRIES || (long) retainedBytes + entry.encodedBytes > MAX_TOTAL_BYTES)
                        removeOldest();
                    entries.put(entry.id, entry);
                    retainedBytes += entry.encodedBytes;
                }
            return envelope(output);
        } catch (RuntimeException failure) {
            return envelope(Map.of("ok", false, "error", code(failure)));
        }
    }

    private static McpAssetSourceProjection.Observation observe(
            String name, CubismModel model, TextureSourceQuery query) {
        return name.equals(SOURCES)
                ? McpAssetSourceProjection.sources(model.textures().sources(query), query)
                : McpAssetSourceProjection.relations(model.textures().relations());
    }

    private static Map<String, Object> page(Entry entry, int offset, int limit) {
        var o = entry.observation;
        if (offset > o.rows().size()) throw new IllegalArgumentException();
        int end = offset + Math.min(limit, o.rows().size() - offset);
        while (true) {
            var output = new LinkedHashMap<String, Object>();
            output.put("ok", true);
            output.put("snapshotId", entry.id);
            output.put("projection", entry.name.equals(SOURCES) ? "SCOPED_SOURCES" : "RELATIONS");
            output.put("queryFingerprint", entry.queryHash);
            output.put("state", entry.state);
            output.put(
                    "observation",
                    Map.of(
                            "binding",
                            o.binding(),
                            "generation",
                            o.generation(),
                            "revision",
                            o.revision(),
                            "revisionKind",
                            "OBSERVATION_SEQUENCE",
                            "historical",
                            true));
            output.put("counts", o.counts());
            output.put("rows", o.rows().subList(offset, end));
            output.put("offset", offset);
            output.put("totalRows", o.rows().size());
            output.put("nextOffset", end < o.rows().size() ? end : null);
            if (McpAssetSourceProjection.bytes(output) <= MAX_PAGE_BYTES) return Collections.unmodifiableMap(output);
            if (end - offset <= 1) throw new McpAssetSourceProjection.Budget();
            end = offset + (end - offset) / 2;
        }
    }

    private static TextureSourceQuery query(Object value) {
        if (!(value instanceof Map<?, ?> map) || !map.keySet().equals(Set.of("artMeshIds", "modelImageIds")))
            throw new IllegalArgumentException();
        return new TextureSourceQuery(
                ids(map.get("artMeshIds")).stream().map(ArtMeshId::new).collect(java.util.stream.Collectors.toSet()),
                ids(map.get("modelImageIds")).stream()
                        .map(ModelImageId::new)
                        .collect(java.util.stream.Collectors.toSet()));
    }

    private static List<String> ids(Object value) {
        if (!(value instanceof List<?> list) || list.size() > 64) throw new IllegalArgumentException();
        var ids = list.stream().map(v -> text(v, 4096)).toList();
        if (new HashSet<>(ids).size() != ids.size()) throw new IllegalArgumentException();
        return ids;
    }

    private void prune() {
        for (var it = entries.values().iterator(); it.hasNext(); ) {
            var entry = it.next();
            if (clock.getAsLong() - entry.created >= McpAuthoringSnapshotStore.TTL_NANOS) {
                retainedBytes -= entry.encodedBytes;
                it.remove();
            }
        }
    }

    private void removeOldest() {
        var it = entries.values().iterator();
        var first = it.next();
        retainedBytes -= first.encodedBytes;
        it.remove();
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Number n)
                || !Double.isFinite(n.doubleValue())
                || n.doubleValue() != n.longValue()
                || n.longValue() < min
                || n.longValue() > max) throw new IllegalArgumentException();
        return n.intValue();
    }

    private static String text(Object value, int max) {
        if (!(value instanceof String s)
                || s.isBlank()
                || s.length() > max
                || s.chars().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
        try {
            return McpAssetSourceProjection.id(s);
        } catch (McpAssetSourceProjection.Invalid invalid) {
            throw new IllegalArgumentException();
        }
    }

    private static String code(RuntimeException e) {
        if (e instanceof Stale || e instanceof McpStoredAuthoringDomain.Stale) return "STALE_STATE";
        if (e instanceof McpStoredAuthoringDomain.NoModel) return "NO_ACTIVE_MODEL";
        if (e instanceof NoSuchElementException) return "NOT_FOUND";
        if (e instanceof McpAssetSourceProjection.Budget) return "BUDGET_EXCEEDED";
        if (e instanceof McpAssetSourceProjection.Invalid) return "INVALID_SOURCE_DATA";
        if (e instanceof SecurityException || e instanceof dev.turboism.sdk.permission.CubismPermissionException)
            return "PERMISSION_DENIED";
        if (e instanceof UnsupportedOperationException
                || e instanceof dev.turboism.sdk.cubism.CubismEditorApiUnavailableException) return "UNAVAILABLE";
        if (e instanceof IllegalArgumentException) return "INVALID_ARGUMENT";
        return "READ_FAILED";
    }

    private record Entry(
            String id,
            String session,
            String name,
            String queryHash,
            Map<String, Object> state,
            McpAssetSourceProjection.Observation observation,
            long created,
            int encodedBytes) {}

    private static final class Stale extends RuntimeException {}
}
