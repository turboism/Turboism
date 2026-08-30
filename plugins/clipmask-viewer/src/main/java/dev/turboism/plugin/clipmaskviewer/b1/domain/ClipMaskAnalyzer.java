package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 剪贴蒙版查重/分组的纯逻辑层（无 Swing 依赖）。
 *
 * <p>只消费 SDK 的 {@link ClipMaskRecord} 快照并计算统计/索引/重复桶；</p>
 */
public final class ClipMaskAnalyzer {

    private ClipMaskAnalyzer() {
    }

    /** 以 GUID 为键的快速索引（保留首次出现顺序）。 */
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

    /** 统计不同的“蒙版 ArtMesh”数量（作为他人蒙版的唯一 GUID 数）。 */
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

    /** 反向索引：mask guid -> 使用该蒙版的 ArtMesh 列表（按原 records 顺序）。 */
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
     * 计算“无序蒙版组合”的重复桶：蒙版集合（忽略顺序）相同但原顺序不同的 ArtMesh 会被归到同一桶。
     *
     * @return key = 排序后的 guid 列表用 ";" 连接 + 倒置标记；value = 命中该 key 的用户列表
     *         （size >= 2 才算“疑似重复”）
     */
    public static Map<String, List<ClipMaskRecord>> groupByUnorderedMaskSet(
        final List<ClipMaskRecord> records
    ) {
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

    /** 统计“蒙版集合一致但顺序不一致”的用户数，用于窗口顶部提示。 */
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
