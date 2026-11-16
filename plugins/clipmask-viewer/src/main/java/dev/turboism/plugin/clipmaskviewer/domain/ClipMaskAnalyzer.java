package dev.turboism.plugin.clipmaskviewer.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Pure logic layer for clip-mask duplicate detection and grouping, with no Swing dependency.
 *
 * <p>It consumes only the SDK {@link ClipMaskRecord} snapshot and computes the statistics, the
 * indexes, and the duplicate buckets.</p>
 */
public final class ClipMaskAnalyzer {

    private ClipMaskAnalyzer() {}

    /** Fast index keyed by GUID, which keeps the first-seen order. */
    public static Map<String, ClipMaskRecord> indexByGuid(final List<ClipMaskRecord> records) {
        final Map<String, ClipMaskRecord> map = new LinkedHashMap<>();
        if (records == null) {
            return map;
        }
        for (ClipMaskRecord record : records) {
            if (record != null) {
                map.put(record.guid(), record);
            }
        }
        return map;
    }

    /** Counts the distinct masking ArtMeshes, which is the number of unique GUIDs used as a mask. */
    public static int countUniqueMasks(final List<ClipMaskRecord> records) {
        if (records == null || records.isEmpty()) {
            return 0;
        }
        final Set<String> set = new HashSet<>();
        for (ClipMaskRecord record : records) {
            if (record != null) {
                set.addAll(record.orderedMaskGuids());
            }
        }
        return set.size();
    }

    /** Reverse index from a mask GUID to the ArtMeshes that use it, in the original record order. */
    public static Map<String, List<ClipMaskRecord>> buildMaskUsers(final List<ClipMaskRecord> records) {
        final Map<String, List<ClipMaskRecord>> out = new LinkedHashMap<>();
        if (records == null) {
            return out;
        }
        for (ClipMaskRecord user : records) {
            if (user == null) {
                continue;
            }
            for (String maskGuid : user.orderedMaskGuids()) {
                out.computeIfAbsent(maskGuid, key -> new ArrayList<>()).add(user);
            }
        }
        return out;
    }

    /**
     * Computes the duplicate buckets of unordered mask combinations, so ArtMeshes with the same
     * mask set in a different original order land in one bucket.
     *
     * @return key = the sorted GUID list joined with ";" plus the inverted flag; value = the users
     *     that hit that key, where a size of 2 or more counts as a suspected duplicate
     */
    public static Map<String, List<ClipMaskRecord>> groupByUnorderedMaskSet(final List<ClipMaskRecord> records) {
        final Map<String, List<ClipMaskRecord>> groups = new LinkedHashMap<>();
        if (records == null) {
            return groups;
        }
        for (ClipMaskRecord record : records) {
            if (record == null || record.orderedMaskGuids().isEmpty()) {
                continue;
            }
            final List<String> sorted = new ArrayList<>(record.orderedMaskGuids());
            Collections.sort(sorted);
            final String key = (record.inverted() ? "INV|" : "NOR|") + String.join(";", sorted);
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(record);
        }
        final Map<String, List<ClipMaskRecord>> dupes = new LinkedHashMap<>();
        for (Map.Entry<String, List<ClipMaskRecord>> entry : groups.entrySet()) {
            if (entry.getValue().size() >= 2) {
                dupes.put(entry.getKey(), entry.getValue());
            }
        }
        return dupes;
    }

    /** Counts the users whose mask set agrees but whose order differs, for the window header hint. */
    public static int countOrderConflicts(final List<ClipMaskRecord> records) {
        final Map<String, List<ClipMaskRecord>> dupes = groupByUnorderedMaskSet(records);
        int count = 0;
        for (List<ClipMaskRecord> bucket : dupes.values()) {
            if (bucket.size() < 2) {
                continue;
            }
            final List<String> first = bucket.get(0).orderedMaskGuids();
            for (int i = 1; i < bucket.size(); i++) {
                if (!bucket.get(i).orderedMaskGuids().equals(first)) {
                    count++;
                }
            }
        }
        return count;
    }
}
