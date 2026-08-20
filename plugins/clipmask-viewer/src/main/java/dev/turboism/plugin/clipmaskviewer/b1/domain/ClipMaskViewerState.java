package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 查看器窗口的只读数据状态：从 SDK 服务收集记录并建立索引/反向索引/重复桶。
 *
 * <p>纯逻辑，不持有 Swing 组件；{@link #refreshData} 在 SDK 服务不可用时抛异常，
 * 由调用方（窗口）决定如何呈现空状态。</p>
 */
public final class ClipMaskViewerState {

    private List<ClipMaskRecord> records = Collections.emptyList();
    private Map<String, ClipMaskRecord> byGuid = Collections.emptyMap();
    private Map<String, List<ClipMaskRecord>> maskUsers = Collections.emptyMap();
    private Map<String, List<ClipMaskRecord>> dupeBuckets = Collections.emptyMap();

    /**
     * @return 上一次 {@link #refreshData} 收集到的全部 clip-mask 记录，按服务返回的顺序；
     *     未刷新或已 {@link #clear} 时为空列表，绝不为 {@code null}
     */
    public List<ClipMaskRecord> records() {
        return records;
    }

    /**
     * @return 以 GUID 为键的记录索引，用于把蒙版引用解析回它所指向的 ArtMesh；
     *     未刷新或已 {@link #clear} 时为空映射，绝不为 {@code null}
     */
    public Map<String, ClipMaskRecord> byGuid() {
        return byGuid;
    }

    /**
     * @return 反向索引：蒙版 ArtMesh 的 GUID 映射到所有把它当作蒙版使用的记录；
     *     键集合即“被用作蒙版”的节点集合。未刷新或已 {@link #clear} 时为空映射，绝不为 {@code null}
     */
    public Map<String, List<ClipMaskRecord>> maskUsers() {
        return maskUsers;
    }

    /**
     * @return 按“无序蒙版集合”分组的桶，用于发现蒙版集合相同（可能仅顺序不同）的疑似重复节点；
     *     未刷新或已 {@link #clear} 时为空映射，绝不为 {@code null}
     */
    public Map<String, List<ClipMaskRecord>> dupeBuckets() {
        return dupeBuckets;
    }

    /** 服务不可用时的空状态（窗口在 catch 后调用）。 */
    public void clear() {
        records = Collections.emptyList();
        byGuid = Collections.emptyMap();
        maskUsers = Collections.emptyMap();
        dupeBuckets = Collections.emptyMap();
    }

    /** 重读 SDK 服务并重建全部索引；服务失败时抛 {@link RuntimeException}。 */
    public void refreshData(final CubismClipMaskService service) {
        final List<ClipMaskRecord> collected = service.collectClipMaskRecords();
        records = collected == null ? Collections.emptyList() : collected;
        byGuid = ClipMaskAnalyzer.indexByGuid(records);
        maskUsers = ClipMaskAnalyzer.buildMaskUsers(records);
        dupeBuckets = ClipMaskAnalyzer.groupByUnorderedMaskSet(records);
    }

    /** 只保留与蒙版关系相关的节点（作为蒙版或有蒙版）。 */
    public List<ClipMaskRecord> filterRelated() {
        final List<ClipMaskRecord> out = new ArrayList<>();
        final Set<String> usedAsMask = maskUsers.keySet();
        for (ClipMaskRecord record : records) {
            if (record != null && (record.hasMasks() || usedAsMask.contains(record.guid()))) {
                out.add(record);
            }
        }
        return out;
    }

    /** 持有蒙版（有 clip-mask 引用）的 ArtMesh 数。 */
    public int countWithMasks() {
        int count = 0;
        for (ClipMaskRecord record : records) {
            if (record != null && record.hasMasks()) {
                count++;
            }
        }
        return count;
    }

    /** 唯一的蒙版 ArtMesh 数。 */
    public int countUniqueMasks() {
        return ClipMaskAnalyzer.countUniqueMasks(records);
    }

    /** 蒙版集合一致但顺序不一致的疑似重复处数。 */
    public int countOrderConflicts() {
        return ClipMaskAnalyzer.countOrderConflicts(records);
    }
}
