package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查看器窗口的只读数据状态：从 SDK 服务收集记录并建立索引/反向索引/重复桶。
 *
 * <p>昂贵的服务读取和索引计算先生成不可变 {@link Snapshot}，窗口只在 EDT 上原子应用
 * 已完成的快照，避免大模型的分析工作阻塞 Swing。</p>
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
     * @return 上一次刷新收集到的全部 clip-mask 记录，按服务返回的顺序；
     *     未刷新或已 {@link #clear} 时为空列表，绝不为 {@code null}
     */
    public List<ClipMaskRecord> records() {
        return records;
    }

    /** @return 以 GUID 为键的记录索引。 */
    public Map<String, ClipMaskRecord> byGuid() {
        return byGuid;
    }

    /** @return 蒙版 GUID 到使用者记录的反向索引。 */
    public Map<String, List<ClipMaskRecord>> maskUsers() {
        return maskUsers;
    }

    /** @return 按无序蒙版集合分组的疑似重复桶。 */
    public Map<String, List<ClipMaskRecord>> dupeBuckets() {
        return dupeBuckets;
    }

    /** 服务不可用时的空状态（窗口在失败结果后调用）。 */
    public void clear() {
        apply(Snapshot.empty());
    }

    /** 重读 SDK 服务并重建全部索引；主要保留给纯逻辑测试和非 Swing 消费者。 */
    public void refreshData(final CubismClipMaskService service) {
        apply(load(service));
    }

    /**
     * 从 SDK 服务读取并完整分析一个不可变快照。该方法不持有或修改 Swing 组件，适合在
     * 插件任务线程执行；服务实现负责其宿主读取边界。
     */
    public static Snapshot load(final CubismClipMaskService service) {
        final List<ClipMaskRecord> collected = service.collectClipMaskRecords();
        return analyze(collected);
    }

    /** 分析已经与宿主分离的不可变记录，安全地在插件任务线程运行。 */
    public static Snapshot analyze(final List<ClipMaskRecord> collected) {
        final List<ClipMaskRecord> records = collected == null ? List.of() : List.copyOf(collected);
        final Map<String, ClipMaskRecord> byGuid = ClipMaskAnalyzer.indexByGuid(records);
        final Map<String, List<ClipMaskRecord>> maskUsers = ClipMaskAnalyzer.buildMaskUsers(records);
        final Map<String, List<ClipMaskRecord>> dupeBuckets =
            ClipMaskAnalyzer.groupByUnorderedMaskSet(records);
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
            countOrderConflicts(dupeBuckets)
        );
    }

    /** 原子替换窗口消费的所有派生数据；必须在 EDT 上由窗口调用。 */
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

    /** 只保留与蒙版关系相关的节点（作为蒙版或有蒙版）。 */
    public List<ClipMaskRecord> filterRelated() {
        return relatedRecords;
    }

    /** 持有蒙版（有 clip-mask 引用）的 ArtMesh 数。 */
    public int countWithMasks() {
        return countWithMasks;
    }

    /** 唯一的蒙版 ArtMesh 数。 */
    public int countUniqueMasks() {
        return countUniqueMasks;
    }

    /** 蒙版集合一致但顺序不一致的疑似重复处数。 */
    public int countOrderConflicts() {
        return countOrderConflicts;
    }

    private static int countOrderConflicts(
        final Map<String, List<ClipMaskRecord>> duplicateBuckets
    ) {
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

    /** 完整、不可变且可跨线程交付的查看器数据。 */
    public record Snapshot(
        List<ClipMaskRecord> records,
        Map<String, ClipMaskRecord> byGuid,
        Map<String, List<ClipMaskRecord>> maskUsers,
        Map<String, List<ClipMaskRecord>> dupeBuckets,
        List<ClipMaskRecord> relatedRecords,
        int countWithMasks,
        int countUniqueMasks,
        int countOrderConflicts
    ) {
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
            final Map<String, List<ClipMaskRecord>> source
        ) {
            final Map<String, List<ClipMaskRecord>> copy = new LinkedHashMap<>();
            source.forEach((key, value) -> copy.put(key, List.copyOf(value)));
            return Collections.unmodifiableMap(copy);
        }
    }
}
