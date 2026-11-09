package dev.turboism.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.sdk.ui.SelectionTableDialogRequest;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeSelectionTableDialogTest {
    static SelectionTableDialogRequest request() {
        return new SelectionTableDialogRequest(
                "test",
                "Preview",
                "Review names",
                List.of("Type", "Original", "Target", "Apply"),
                List.of(
                        new SelectionTableDialogRequest.Row(
                                "a", new UiIconRef(CubismIcon.WARP_DEFORMER), "Warp", "old", "new", true, true),
                        new SelectionTableDialogRequest.Row(
                                "b",
                                new UiIconRef(CubismIcon.ROTATION_DEFORMER),
                                "Rotation",
                                "same",
                                "same",
                                false,
                                false)),
                "Confirm",
                "Cancel");
    }

    @Test
    void selectionSkipsDisabledRowsAndReturnsStableIdentities() {
        var rows = new RuntimeSelectionTableDialog.Rows(request());
        assertEquals(List.of("a"), rows.selected());
        assertTrue(rows.allSelected());
        rows.setValueAt(false, 0, 3);
        assertTrue(rows.selected().isEmpty());
        assertFalse(rows.allSelected());
        rows.selectAll(true);
        assertEquals(List.of("a"), rows.selected());
        rows.setValueAt(true, 1, 3);
        assertFalse(rows.isCellEditable(1, 3));
        assertEquals(List.of("a"), rows.selected());
        rows.selectAll(false);
        assertTrue(rows.selected().isEmpty());
    }

    /**
     * A footer choice rebuilds every proposed name, so the model must show the new rows and drop the
     * selection that belonged to the old ones. Keeping a stale selection would confirm an identity
     * the operator no longer sees.
     */
    @Test
    void rebuildingRowsReplacesContentAndDropsThePreviousSelection() {
        var rows = new RuntimeSelectionTableDialog.Rows(request());
        assertEquals(List.of("a"), rows.selected());

        rows.replaceRows(List.of(new SelectionTableDialogRequest.Row(
                "c", new UiIconRef(CubismIcon.WARP_DEFORMER), "Warp", "old", "renamed", true, false)));

        assertEquals(1, rows.getRowCount());
        assertEquals("c", rows.rowAt(0).id());
        assertTrue(rows.selected().isEmpty(), "the rebuild must not carry the previous selection");
        assertFalse(rows.allSelected(), "an unselected row is not an all-selected table");
    }

    @Test
    void footerContractRejectsAnEmptyOrUnmatchedChoiceSet() {
        var choice = new dev.turboism.sdk.ui.SelectionTableFooter.Choice("minimal", "Minimal");
        var footer = new dev.turboism.sdk.ui.SelectionTableFooter() {
            @Override
            public String id() {
                return "base";
            }

            @Override
            public String label() {
                return "Base";
            }

            @Override
            public List<dev.turboism.sdk.ui.SelectionTableFooter.Choice> choices() {
                return List.of(choice);
            }

            @Override
            public String selectedChoiceId() {
                return "minimal";
            }

            @Override
            public List<SelectionTableDialogRequest.Row> rows(final String choiceId) {
                return List.of();
            }
        };
        assertDoesNotThrow(() -> dev.turboism.sdk.ui.SelectionTableFooter.requireValid(footer));
        assertThrows(
                IllegalArgumentException.class,
                () -> new dev.turboism.sdk.ui.SelectionTableFooter.Choice(" ", "Minimal"));
    }

    @Test
    void validatesPreviewBoundsAndUniqueRowIdentities() {
        var original = request();
        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectionTableDialogRequest("test", "test", "", List.of("wrong"), List.of(), "yes", "no"));
        assertThrows(
                IllegalArgumentException.class,
                () -> new SelectionTableDialogRequest(
                        "test",
                        "test",
                        "",
                        original.columns(),
                        List.of(original.rows().get(0), original.rows().get(0)),
                        "yes",
                        "no"));
    }
}
