package dev.turboism.plugin.clipmaskviewer.ui;

import dev.turboism.plugin.clipmaskviewer.b1.domain.ClipMaskViewerState;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService;
import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;
import dev.turboism.sdk.i18n.PluginLocalization;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClipMaskTableModelsTest {

    @Test
    void maskPrimaryModelRowsFollowMaskUsersOrderAndRenderCells() {
        final StateFixture fixture = new StateFixture(
            record("user-1", "A", false, "mask-1", "mask-2"),
            record("user-2", "B", false, "mask-1")
        );
        final ClipMaskTableModels.MaskPrimaryTableModel model =
            new ClipMaskTableModels.MaskPrimaryTableModel(fixture.state, fixture.localization);

        assertEquals(2, model.getRowCount());
        assertEquals("mask-1", model.getMaskGuidAt(0));
        assertEquals("mask-2", model.getMaskGuidAt(1));
        assertEquals("table.mask.column", model.getColumnName(0));
        assertEquals("table.masks.column", model.getColumnName(1));
        assertTrue(String.valueOf(model.getValueAt(0, 1)).contains("user-1"));
        assertTrue(String.valueOf(model.getValueAt(0, 1)).contains("user-2"));
    }

    @Test
    void userPrimaryModelPutsDupeBucketMembersFirst() {
        final StateFixture fixture = new StateFixture(
            record("plain-1", "Plain One", false, "mask-9"),
            record("dupe-1", "Dupe One", false, "mask-1", "mask-2"),
            record("dupe-2", "Dupe Two", false, "mask-2", "mask-1"),
            record("plain-2", "Plain Two", false, "mask-9")
        );
        final ClipMaskTableModels.UserPrimaryTableModel model =
            new ClipMaskTableModels.UserPrimaryTableModel(fixture.state, fixture.localization);

        assertEquals(4, model.getRowCount());
        assertEquals("dupe-1", model.getUserAt(0).guid());
        assertEquals("dupe-2", model.getUserAt(1).guid());
        assertEquals("plain-1", model.getUserAt(2).guid());
        assertEquals("plain-2", model.getUserAt(3).guid());
        assertTrue(String.valueOf(model.getValueAt(0, 1)).contains("mask-1"));
        assertTrue(String.valueOf(model.getValueAt(0, 1)).contains("mask-2"));
    }

    @Test
    void userPrimaryModelOmitsRecordsWithoutMasks() {
        final StateFixture fixture = new StateFixture(
            record("user-1", "A", false, "mask-1"),
            record("empty-1", "E", false)
        );
        final ClipMaskTableModels.UserPrimaryTableModel model =
            new ClipMaskTableModels.UserPrimaryTableModel(fixture.state, fixture.localization);

        assertEquals(1, model.getRowCount());
        assertEquals("user-1", model.getUserAt(0).guid());
    }

    @Test
    void highlightedGuidsMarkMatchingRows() {
        final StateFixture fixture = new StateFixture(
            record("user-1", "A", false, "mask-1"),
            record("user-2", "B", false, "mask-2")
        );
        final ClipMaskTableModels.MaskPrimaryTableModel maskModel =
            new ClipMaskTableModels.MaskPrimaryTableModel(fixture.state, fixture.localization);
        final ClipMaskTableModels.UserPrimaryTableModel userModel =
            new ClipMaskTableModels.UserPrimaryTableModel(fixture.state, fixture.localization);

        maskModel.setHighlightedGuids(Set.of("mask-1"));
        userModel.setHighlightedGuids(Set.of("user-2"));

        assertTrue(maskModel.isRowHighlighted(0));
        assertFalse(maskModel.isRowHighlighted(1));
        assertFalse(userModel.isRowHighlighted(0));
        assertTrue(userModel.isRowHighlighted(1));
    }

    private static final class StateFixture {
        private final ClipMaskViewerState state = new ClipMaskViewerState();
        private final PluginLocalization localization = new PluginLocalization() {
            @Override
            public Locale locale() {
                return Locale.ENGLISH;
            }

            @Override
            public String text(final String key) {
                return key;
            }

            @Override
            public String format(final String key, final Object... arguments) {
                return key;
            }

            @Override
            public boolean contains(final String key) {
                return true;
            }
        };

        private StateFixture(final ClipMaskRecord... records) {
            state.refreshData(service(records));
        }
    }

    private static CubismClipMaskService service(final ClipMaskRecord... records) {
        return () -> List.of(records);
    }

    private static ClipMaskRecord record(
        final String guid,
        final String id,
        final boolean inverted,
        final String... masks
    ) {
        return new ClipMaskRecord(guid, id, guid, inverted, List.of(masks));
    }
}
