package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.SelectionTableDialogRequest;
import dev.turboism.sdk.ui.resource.CubismIcon;
import dev.turboism.sdk.ui.resource.UiIconRef;
import java.util.List;

/** Detached preview description; the runtime renders native icons and owns the dialog. */
final class RenameDialog {
    private RenameDialog() {}

    static SelectionTableDialogRequest request(PluginLocalization text, List<RenamePlanner.Row> rows) {
        return new SelectionTableDialogRequest(
                "deformer.rename.preview",
                text.text("action.rename"),
                "",
                List.of(
                        text.text("column.type"),
                        text.text("column.original"),
                        text.text("column.target"),
                        text.text("column.apply")),
                rows.stream()
                        .map(row -> new SelectionTableDialogRequest.Row(
                                row.id().value(),
                                new UiIconRef(row.rotation() ? CubismIcon.ROTATION_DEFORMER : CubismIcon.WARP_DEFORMER),
                                text.text(row.rotation() ? "type.rotation" : "type.warp"),
                                row.original(),
                                row.target(),
                                row.changed(),
                                row.changed()))
                        .toList(),
                text.text("button.confirm"),
                text.text("button.cancel"));
    }
}
