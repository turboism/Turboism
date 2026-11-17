package dev.turboism.plugin.clipmaskviewer.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only data state of the viewer window. It collects the records from the SDK service and
 * builds the index, the reverse index, and the duplicate buckets.
 *
 * <p>The expensive service read and index computation produce an immutable {@link Snapshot}
 * first, and the window applies a finished snapshot atomically on the EDT only, so the analysis
 * of a large model never blocks Swing.</p>
 */
public final class ClipMaskViewerState {

    private List<ClipMaskRecord> records = Collections.emptyList();
    private Map<String, ClipMaskRecord> byGuid = Collections.emptyMap();
    private Map<String, List<ClipMaskRecord>> maskUsers = Collections.emptyMap();
    private Map<String, List<ClipMaskRecord>> dupeBuckets = Collections.emptyMap();
    private List<ClipMaskRecord> relatedRecords = Collections.emptyList();
    private int countWithMasks;
    private int countUniqueMasks;
    private int countOrderConflicts;

    /**
     * @return every clip-mask record collected by the last refresh, in the order the service
     *     returned them. The list is empty before a refresh or after {@link #clear} and is never
     *     {@code null}
     */
    public List<ClipMaskRecord> records() {
        return records;
    }

    /** @return the record index keyed by GUID. */
    public Map<String, ClipMaskRecord> byGuid() {
        return byGuid;
    }

    /** @return the reverse index from a mask GUID to the records that use it. */
    public Map<String, List<ClipMaskRecord>> maskUsers() {
        return maskUsers;
    }

    /** @return the suspected duplicate buckets grouped by unordered mask set. */
    public Map<String, List<ClipMaskRecord>> dupeBuckets() {
        return dupeBuckets;
    }

    /** Empty state for an unavailable service, which the window applies after a failed result. */
    public void clear() {
        apply(Snapshot.empty());
    }

    /** Re-reads the SDK service and rebuilds every index. It is kept mainly for pure logic tests and non-Swing consumers. */
    public void refreshData(final CubismClipMaskService service) {
        apply(load(service));
    }

    /**
     * Reads the SDK service and fully analyzes one immutable snapshot. The method holds and
     * modifies no Swing component, so it runs on the plugin task thread, and the service
     * implementation owns its host read boundary.
     */
    public static Snapshot load(final CubismClipMaskService service) {
        final List<ClipMaskRecord> collected = service.collectClipMaskRecords();
        return analyze(collected);
    }

    /** Analyzes immutable records already detached from the host, so it runs safely on the plugin task thread. */
    public static Snapshot analyze(final List<ClipMaskRecord> collected) {
        final List<ClipMaskRecord> records = collected == null ? List.of() : List.copyOf(collected);
        final Map<String, ClipMaskRecord> byGuid = ClipMaskAnalyzer.indexByGuid(records);
        final Map<String, List<ClipMaskRecord>> maskUsers = ClipMaskAnalyzer.buildMaskUsers(records);
        final Map<String, List<ClipMaskRecord>> dupeBuckets = ClipMaskAnalyzer.groupByUnorderedMaskSet(records);
        final List<ClipMaskRecord> related = new ArrayList<>();
        int withMasks = 0;
        for (ClipMaskRecord record : records) {
            if (record == null) {
                continue;
            }
            if (record.hasMasks()) {
                withMasks++;
            }
            if (record.hasMasks() || maskUsers.containsKey(record.guid())) {
                related.add(record);
            }
        }
        return new Snapshot(
                records,
                byGuid,
                maskUsers,
                dupeBuckets,
                related,
                withMasks,
                ClipMaskAnalyzer.countUniqueMasks(records),
                countOrderConflicts(dupeBuckets));
    }

    /** Atomically replaces every derived datum the window consumes. The window must call it on the EDT. */
    public void apply(final Snapshot snapshot) {
        final Snapshot value = java.util.Objects.requireNonNull(snapshot, "snapshot");
        records = value.records();
        byGuid = value.byGuid();
        maskUsers = value.maskUsers();
        dupeBuckets = value.dupeBuckets();
        relatedRecords = value.relatedRecords();
        countWithMasks = value.countWithMasks();
        countUniqueMasks = value.countUniqueMasks();
        countOrderConflicts = value.countOrderConflicts();
    }

    /** Keeps only the nodes that take part in a mask relation, either as a mask or as a masked node. */
    public List<ClipMaskRecord> filterRelated() {
        return relatedRecords;
    }

    /** Number of ArtMeshes that hold a clip-mask reference. */
    public int countWithMasks() {
        return countWithMasks;
    }

    /** Number of distinct masking ArtMeshes. */
    public int countUniqueMasks() {
        return countUniqueMasks;
    }

    /** Number of suspected duplicates whose mask set agrees but whose order differs. */
    public int countOrderConflicts() {
        return countOrderConflicts;
    }

    private static int countOrderConflicts(final Map<String, List<ClipMaskRecord>> duplicateBuckets) {
        int count = 0;
        for (List<ClipMaskRecord> bucket : duplicateBuckets.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            final List<String> first = bucket.get(0).orderedMaskGuids();
            for (int index = 1; index < bucket.size(); index++) {
                if (!bucket.get(index).orderedMaskGuids().equals(first)) {
                    count++;
                }
            }
        }
        return count;
    }

    /** Complete immutable viewer data that crosses a thread boundary safely. */
    public record Snapshot(
            List<ClipMaskRecord> records,
            Map<String, ClipMaskRecord> byGuid,
            Map<String, List<ClipMaskRecord>> maskUsers,
            Map<String, List<ClipMaskRecord>> dupeBuckets,
            List<ClipMaskRecord> relatedRecords,
            int countWithMasks,
            int countUniqueMasks,
            int countOrderConflicts) {
        public Snapshot {
            records = List.copyOf(records);
            byGuid = Collections.unmodifiableMap(new LinkedHashMap<>(byGuid));
            maskUsers = immutableLists(maskUsers);
            dupeBuckets = immutableLists(dupeBuckets);
            relatedRecords = List.copyOf(relatedRecords);
            if (countWithMasks < 0 || countUniqueMasks < 0 || countOrderConflicts < 0) {
                throw new IllegalArgumentException("clip-mask counts must not be negative");
            }
        }

        /** @return an immutable snapshot with no clip-mask records or derived relationships */
        public static Snapshot empty() {
            return new Snapshot(List.of(), Map.of(), Map.of(), Map.of(), List.of(), 0, 0, 0);
        }

        private static Map<String, List<ClipMaskRecord>> immutableLists(
                final Map<String, List<ClipMaskRecord>> source) {
            final Map<String, List<ClipMaskRecord>> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(copy);
        }
    }
}
