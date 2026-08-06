package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskRecordAdapter;
import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;

import javax.swing.table.AbstractTableModel;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 查看器两种表视角的模型（纯模型，无组件）。
 *
 * <p>表-蒙版为主：左列=蒙版 ArtMesh 名称，中列=蒙版 ID，右列=使用该蒙版的 ArtMesh 列表；
 * 表-使用者为主：左列=使用者 ArtMesh 名称，中列=使用者 ID，右列=它的蒙版列表（保留原顺序，dupe 桶置顶）。</p>
 */
public final class ClipMaskTableModels {

    private ClipMaskTableModels() {
    }

    /** 蒙版为主模型：行 = 被他人作为蒙版的唯一 ArtMesh。 */
    public static final class MaskPrimaryTableModel extends AbstractTableModel {
        private final ClipMaskViewerState state;
        private final PluginLocalization localization;
        private final List<String> maskGuids = new ArrayList<>();
        private Set<String> highlightedGuids = Set.of();

        public MaskPrimaryTableModel(final ClipMaskViewerState state, final PluginLocalization localization) {
            this.state = state;
            this.localization = localization;
            fireRefresh();
        }

        public void fireRefresh() {
            maskGuids.clear();
            if (state.maskUsers() != null) {
                maskGuids.addAll(state.maskUsers().keySet());
            }
            fireTableDataChanged();
        }

        public ClipMaskRecord getMaskAt(final int row) {
            if (row < 0 || row >= maskGuids.size()) {
                return null;
            }
            return state.byGuid().get(maskGuids.get(row));
        }

        /** 行对应的蒙版 GUID（蒙版自身可能没有 clip-mask 记录，仍可复制/高亮）。 */
        public String getMaskGuidAt(final int row) {
            if (row < 0 || row >= maskGuids.size()) {
                return null;
            }
            return maskGuids.get(row);
        }

        public void setHighlightedGuids(final Set<String> guids) {
            this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
            fireTableDataChanged();
        }

        public boolean isRowHighlighted(final int row) {
            final String guid = getMaskGuidAt(row);
            return guid != null && highlightedGuids.contains(guid);
        }

        @Override
        public int getRowCount() {
            return maskGuids.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> localization.text("table.mask.column");
                case 1 -> localization.text("table.id");
                default -> localization.text("table.masks.column");
            };
        }

        @Override
        public Object getValueAt(final int rowIndex, final int columnIndex) {
            final String maskGuid = maskGuids.get(rowIndex);
            final ClipMaskRecord mask = state.byGuid().get(maskGuid);
            if (columnIndex == 0) {
                return mask == null ? localization.text("value.none") : mask.displayName();
            }
            if (columnIndex == 1) {
                final String id = mask == null ? null : mask.id();
                return id == null || id.isBlank() ? localization.text("value.none") : id;
            }
            final List<ClipMaskRecord> users = state.maskUsers().get(maskGuid);
            if (users == null || users.isEmpty()) {
                return localization.text("value.none");
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < users.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                final ClipMaskRecord user = users.get(i);
                sb.append(user.displayName());
                if (user.inverted()) {
                    sb.append(localization.text("node.inverted"));
                }
            }
            sb.append(" ").append(localization.format("table.count", users.size()));
            return sb.toString();
        }
    }

    /** 使用者为主模型：行 = 持有蒙版的 ArtMesh；疑似重复桶（同集合不同顺序）优先置顶。 */
    public static final class UserPrimaryTableModel extends AbstractTableModel {
        private final ClipMaskViewerState state;
        private final PluginLocalization localization;
        private final List<ClipMaskRecord> users = new ArrayList<>();
        private Set<String> highlightedGuids = Set.of();

        public UserPrimaryTableModel(final ClipMaskViewerState state, final PluginLocalization localization) {
            this.state = state;
            this.localization = localization;
            fireRefresh();
        }

        public void fireRefresh() {
            users.clear();
            if (state.records() != null) {
                for (ClipMaskRecord record : state.records()) {
                    if (record != null && record.hasMasks()) {
                        users.add(record);
                    }
                }
            }
            final Set<String> dupeGuids = new HashSet<>();
            for (List<ClipMaskRecord> bucket : state.dupeBuckets().values()) {
                for (ClipMaskRecord record : bucket) {
                    dupeGuids.add(record.guid());
                }
            }
            users.sort((a, b) -> {
                final int as = dupeGuids.contains(a.guid()) ? 0 : 1;
                final int bs = dupeGuids.contains(b.guid()) ? 0 : 1;
                if (as != bs) {
                    return as - bs;
                }
                return a.displayName().compareTo(b.displayName());
            });
            fireTableDataChanged();
        }

        public ClipMaskRecord getUserAt(final int row) {
            if (row < 0 || row >= users.size()) {
                return null;
            }
            return users.get(row);
        }

        public void setHighlightedGuids(final Set<String> guids) {
            this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
            fireTableDataChanged();
        }

        public boolean isRowHighlighted(final int row) {
            final ClipMaskRecord record = getUserAt(row);
            return record != null && highlightedGuids.contains(record.guid());
        }

        @Override
        public int getRowCount() {
            return users.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(final int column) {
            return switch (column) {
                case 0 -> localization.text("table.user.column");
                case 1 -> localization.text("table.id");
                default -> localization.text("table.users.column");
            };
        }

        @Override
        public Object getValueAt(final int rowIndex, final int columnIndex) {
            final ClipMaskRecord user = users.get(rowIndex);
            if (columnIndex == 0) {
                final String name = user.displayName();
                return user.inverted() ? name + "  " + localization.text("node.inverted") : name;
            }
            if (columnIndex == 1) {
                final String id = user.id();
                return id == null || id.isBlank() ? localization.text("value.none") : id;
            }
            if (user.orderedMaskGuids().isEmpty()) {
                return localization.text("value.none");
            }
            final StringBuilder sb = new StringBuilder();
            for (int i = 0; i < user.orderedMaskGuids().size(); i++) {
                if (i > 0) {
                    sb.append(" -> ");
                }
                final String maskGuid = user.orderedMaskGuids().get(i);
                final ClipMaskRecord mask = state.byGuid().get(maskGuid);
                sb.append(mask != null
                    ? mask.displayName()
                    : ClipMaskRecordAdapter.shortGuid(maskGuid));
            }
            sb.append(" ").append(localization.format("table.count", user.orderedMaskGuids().size()));
            return sb.toString();
        }
    }
}
