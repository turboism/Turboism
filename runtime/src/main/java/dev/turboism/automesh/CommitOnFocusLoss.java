package dev.turboism.automesh;

import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.util.Objects;
import java.util.function.Consumer;
import javax.swing.JTextField;

/**
 * Commits a text field's content when the field loses focus.
 *
 * <p>A text parameter has no confirm gesture of its own, so a value typed and then abandoned by
 * clicking elsewhere would otherwise never reach the model. Focus loss is the last moment the field
 * still owns the input, which makes it the one place to commit.</p>
 */
final class CommitOnFocusLoss extends FocusAdapter {

    private final JTextField field;
    private final Consumer<String> commit;

    /**
     * Creates the listener of one field.
     *
     * @param field the field whose content is committed
     * @param commit receives the stripped content on every focus loss
     */
    CommitOnFocusLoss(final JTextField field, final Consumer<String> commit) {
        this.field = Objects.requireNonNull(field, "field");
        this.commit = Objects.requireNonNull(commit, "commit");
    }

    @Override
    public void focusLost(final FocusEvent event) {
        commit.accept(field.getText().strip());
    }
}
