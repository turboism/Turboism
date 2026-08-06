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

    public List<ClipMaskRecord> records() {
        return records;
    }

    public Map<String, ClipMaskRecord> byGuid() {
        return byGuid;
    }

    public Map<String, List<ClipMaskRecord>> maskUsers() {
        return maskUsers;
    }

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
