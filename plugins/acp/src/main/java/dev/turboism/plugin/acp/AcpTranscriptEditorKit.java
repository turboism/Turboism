package dev.turboism.plugin.acp;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Rectangle;
import java.awt.Shape;
import javax.swing.text.AbstractDocument;
import javax.swing.text.Element;
import javax.swing.text.ParagraphView;
import javax.swing.text.StyledEditorKit;
import javax.swing.text.ViewFactory;

/** Keeps messages selectable native text while painting user-message panels across the viewport. */
final class AcpTranscriptEditorKit extends StyledEditorKit {
    static final String USER_BACKGROUND = "acp.user-background";
    static final String USER_ACCENT = "acp.user-accent";

    @Override
    public ViewFactory getViewFactory() {
        final ViewFactory fallback = super.getViewFactory();
        return element -> AbstractDocument.ParagraphElementName.equals(element.getName())
                ? new MessageParagraphView(element)
                : fallback.create(element);
    }

    private static final class MessageParagraphView extends ParagraphView {
        private MessageParagraphView(final Element element) {
            super(element);
        }

        @Override
        public void paint(final Graphics graphics, final Shape allocation) {
            final Object background = getElement().getAttributes().getAttribute(USER_BACKGROUND);
            final Object accent = getElement().getAttributes().getAttribute(USER_ACCENT);
            if (background instanceof Color fill && accent instanceof Color edge) {
                final Rectangle bounds = allocation.getBounds();
                final Color previous = graphics.getColor();
                graphics.setColor(fill);
                graphics.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
                graphics.setColor(edge);
                graphics.fillRect(bounds.x, bounds.y, 2, bounds.height);
                graphics.setColor(previous);
            }
            super.paint(graphics, allocation);
        }
    }
}
