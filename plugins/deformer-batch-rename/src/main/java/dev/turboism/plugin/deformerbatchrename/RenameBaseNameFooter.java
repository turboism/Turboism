package dev.turboism.plugin.deformerbatchrename;

import dev.turboism.sdk.i18n.PluginLocalization;
import dev.turboism.sdk.ui.SelectionTableDialogRequest;
import dev.turboism.sdk.ui.SelectionTableFooter;
import java.util.List;
import java.util.Objects;

/**
 * Naming-base control shown in the preview footer.
 *
 * <p>Every proposed name depends on this choice, so the control rebuilds the preview in place.
 * Both previews are derived from one index before the dialog opens, so switching the choice
 * re-derives names from memory instead of reading the model again. The chosen mode and the rows it
 * produced stay together: the accept path confirms exactly the rows the operator last saw.</p>
 */
final class RenameBaseNameFooter implements SelectionTableFooter {

    static final String ID = "deformer.rename.base";
    private static final String CHOICE_MINIMAL = "minimal-common";
    private static final String CHOICE_PART = "part-name";

    private final PluginLocalization i18n;
    private final RenamePlanner.Preview preview;
    private final List<Choice> choices;
    private volatile RenamePlanner.BaseNameMode mode;

    RenameBaseNameFooter(
            final PluginLocalization i18n,
            final RenamePlanner.Preview preview,
            final RenamePlanner.BaseNameMode initial) {
        this.i18n = Objects.requireNonNull(i18n, "i18n");
        this.preview = Objects.requireNonNull(preview, "preview");
        this.mode = Objects.requireNonNull(initial, "initial");
        this.choices = List.of(
                new Choice(CHOICE_MINIMAL, i18n.text("base.minimal")), new Choice(CHOICE_PART, i18n.text("base.part")));
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String label() {
        return i18n.text("base.label");
    }

    @Override
    public List<Choice> choices() {
        return choices;
    }

    @Override
    public String selectedChoiceId() {
        return mode == RenamePlanner.BaseNameMode.PART_NAME ? CHOICE_PART : CHOICE_MINIMAL;
    }

    @Override
    public List<SelectionTableDialogRequest.Row> rows(final String choiceId) {
        final RenamePlanner.BaseNameMode next = CHOICE_PART.equals(choiceId)
                ? RenamePlanner.BaseNameMode.PART_NAME
                : RenamePlanner.BaseNameMode.MINIMAL_COMMON;
        mode = next;
        return RenameDialog.rows(i18n, preview.rows(next));
    }

    /** @return the rows currently shown, in planner order */
    List<RenamePlanner.Row> rows() {
        return preview.rows(mode);
    }

    /** @return the mode the currently shown rows were built with */
    RenamePlanner.BaseNameMode mode() {
        return mode;
    }

    /** @return the index both modes were derived from */
    dev.turboism.sdk.cubism.model.ModelNamingIndex index() {
        return preview.index();
    }
}
