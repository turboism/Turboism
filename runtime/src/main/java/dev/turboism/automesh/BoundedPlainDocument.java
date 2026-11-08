package dev.turboism.automesh;

import javax.swing.text.AttributeSet;
import javax.swing.text.BadLocationException;
import javax.swing.text.PlainDocument;

/**
 * Plain-text document that refuses any edit beyond a declared length.
 *
 * <p>A text parameter declares {@code maxLength}, and the dialog must not let the user type past
 * it: a field that accepts input the codec later rejects would report a failure the user cannot
 * see the cause of. The limit lives in the document so paste, drag-and-drop and programmatic
 * insert all obey it.</p>
 */
final class BoundedPlainDocument extends PlainDocument {

    private static final long serialVersionUID = 1L;

    private final int maxLength;

    /**
     * Creates a document bounded to {@code maxLength} characters.
     *
     * @param maxLength the longest accepted content; it must be positive
     */
    BoundedPlainDocument(final int maxLength) {
        if (maxLength < 1) {
            throw new IllegalArgumentException("maxLength must be positive");
        }
        this.maxLength = maxLength;
    }

    @Override
    public void insertString(final int offset, final String text, final AttributeSet attributes)
            throws BadLocationException {
        if (text == null) {
            return;
        }
        final int room = maxLength - getLength();
        if (room <= 0) {
            return;
        }
        super.insertString(offset, text.length() <= room ? text : text.substring(0, room), attributes);
    }
}
