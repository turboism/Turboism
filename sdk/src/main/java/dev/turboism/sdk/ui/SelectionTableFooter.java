package dev.turboism.sdk.ui;

import java.util.List;
import java.util.Objects;

/**
 * Optional footer control for a selection table, drawn left of the accept and cancel buttons.
 *
 * <p>The control rebuilds the preview when its choice changes. A batch preview whose proposed
 * names depend on one setting then shows the settled list in one dialog, instead of asking for the
 * setting first and presenting a list that can no longer change.</p>
 */
public interface SelectionTableFooter {

    /** @return stable control id, used to attribute diagnostics */
    String id();

    /** @return localized label drawn before the choices */
    String label();

    /** @return ordered choices, never empty */
    List<Choice> choices();

    /** @return the choice selected when the dialog opens; must name one of {@link #choices()} */
    String selectedChoiceId();

    /**
     * Rebuilds the preview rows for one choice.
     *
     * <p>Runs on the host event dispatch thread while the dialog is open, so an implementation must
     * read only state it already owns. An empty list keeps the dialog open with an empty table.</p>
     *
     * @param choiceId the choice the operator picked
     * @return the replacement rows
     */
    List<SelectionTableDialogRequest.Row> rows(String choiceId);

    /** Validates the footer contract before a host renders it. */
    static void requireValid(final SelectionTableFooter footer) {
        Objects.requireNonNull(footer, "footer");
        Objects.requireNonNull(footer.id(), "footer.id");
        Objects.requireNonNull(footer.label(), "footer.label");
        final List<Choice> choices = Objects.requireNonNull(footer.choices(), "footer.choices");
        if (choices.isEmpty()) {
            throw new IllegalArgumentException("a footer needs at least one choice");
        }
        final String selected = footer.selectedChoiceId();
        if (choices.stream().noneMatch(choice -> choice.id().equals(selected))) {
            throw new IllegalArgumentException("the selected footer choice must be one of its choices");
        }
    }

    /** One labeled choice of the footer control. */
    record Choice(String id, String label) {
        public Choice {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(label, "label");
            if (id.isBlank() || label.isBlank()) {
                throw new IllegalArgumentException("a footer choice needs a non-blank id and label");
            }
        }
    }
}
