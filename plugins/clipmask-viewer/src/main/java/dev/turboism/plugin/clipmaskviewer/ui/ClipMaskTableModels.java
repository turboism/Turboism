package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.domain.ClipMaskRecordAdapter;
import dev.turboism.plugin.clipmaskviewer.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.table.AbstractTableModel;

/**
 * Models of the two table views of the viewer, as pure models with no component.
 *
 * <p>The mask-first table shows the masking ArtMesh name, the mask ID, and the list of ArtMeshes
 * that use the mask. The user-first table shows the user ArtMesh name, the user ID, and its mask
 * list in the original order, with a duplicate bucket on top.</p>
 */
public final class ClipMaskTableModels {

    private ClipMaskTableModels() {}

    /** Mask-first model: one row per distinct ArtMesh that another ArtMesh uses as a mask. */
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

        /**
         * Re-reads the mask row set from the state and notifies the table to refresh. The row order
         * follows the key order of {@code maskUsers()}, and an unloaded state clears to zero rows.
         * The call must run on the EDT.
         */
        public void fireRefresh() {
            maskGuids.clear();
            if (state.maskUsers() != null) {
                maskGuids.addAll(state.maskUsers().keySet());
            }
            fireTableDataChanged();
        }

        /**
         * @param row the view row index
         * @return the clip-mask record of the mask itself. The value is {@code null} when the row
         *     index is out of range, or when the mask holds no mask of its own and has no record
         *     {@code null}——此时仍可用 {@link #getMaskGuidAt(int)} 取到 GUID
         */
        public ClipMaskRecord getMaskAt(final int row) {
            if (row < 0 || row >= maskGuids.size()) {
                return null;
            }
            return state.byGuid().get(maskGuids.get(row));
        }

        /** Mask GUID of the row. The mask may hold no clip-mask record of its own and still copies or highlights. */
        public String getMaskGuidAt(final int row) {
            if (row < 0 || row >= maskGuids.size()) {
                return null;
            }
            return maskGuids.get(row);
        }

        /**
         * Replaces the whole highlight GUID set and refreshes the table. The set is copied
         * defensively, so a later caller change cannot affect this model, and {@code null} clears
         * the highlight. The call must run on the EDT.
         *
         * @param guids the GUID set to highlight, which may be {@code null}
         */
        public void setHighlightedGuids(final Set<String> guids) {
            this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
            fireTableDataChanged();
        }

        /**
         * @param row the view row index
         * @return whether the mask GUID of the row is in the current highlight set. An out-of-range
         *     row index returns {@code false}
         */
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

    /** User-first model: one row per ArtMesh that holds a mask, with a suspected duplicate bucket on top. */
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

        /**
         * Re-reads the user row set from the state and notifies the table to refresh. Only records
         * that hold a mask stay, a record inside a suspected duplicate bucket comes first, and the
         * rest sort by display name in dictionary order. The call must run on the EDT.
         */
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

        /**
         * @param row the view row index
         * @return the user record of the row, or {@code null} when the row index is out of range
         */
        public ClipMaskRecord getUserAt(final int row) {
            if (row < 0 || row >= users.size()) {
                return null;
            }
            return users.get(row);
        }

        /**
         * Replaces the whole highlight GUID set and refreshes the table. The set is copied
         * defensively, so a later caller change cannot affect this model, and {@code null} clears
         * the highlight. The call must run on the EDT.
         *
         * @param guids the GUID set to highlight, which may be {@code null}
         */
        public void setHighlightedGuids(final Set<String> guids) {
            this.highlightedGuids = guids == null ? Set.of() : Set.copyOf(guids);
            fireTableDataChanged();
        }

        /**
         * @param row the view row index
         * @return whether the user GUID of the row is in the current highlight set. An out-of-range
         *     row index returns {@code false}
         */
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
                sb.append(mask != null ? mask.displayName() : ClipMaskRecordAdapter.shortGuid(maskGuid));
            }
            sb.append(" ")
                    .append(localization.format(
                            "table.count", user.orderedMaskGuids().size()));
            return sb.toString();
        }
    }
}
