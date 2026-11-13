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
    static final String USER_CARD_FIRST = "acp.user-card-first";
    static final String USER_CARD_LAST = "acp.user-card-last";
    private static final int USER_CARD_MARGIN_TOP = 8;
    private static final int USER_CARD_MARGIN_BOTTOM = 6;
    private static final int ACCENT_STRIPE_WIDTH = 2;

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
            final var attrs = getElement().getAttributes();
            final Object background = attrs.getAttribute(USER_BACKGROUND);
            final Object accent = attrs.getAttribute(USER_ACCENT);
            if (background instanceof Color fill && accent instanceof Color edge) {
                final Rectangle bounds = allocation.getBounds();
                final int topInset =
                        Boolean.TRUE.equals(attrs.getAttribute(USER_CARD_FIRST)) ? USER_CARD_MARGIN_TOP : 0;
                final int bottomInset =
                        Boolean.TRUE.equals(attrs.getAttribute(USER_CARD_LAST)) ? USER_CARD_MARGIN_BOTTOM : 0;
                final int y = bounds.y + topInset;
                final int h = Math.max(0, bounds.height - topInset - bottomInset);
                if (h > 0) {
                    final Color previous = graphics.getColor();
                    graphics.setColor(fill);
                    graphics.fillRect(bounds.x, y, bounds.width, h);
                    graphics.setColor(edge);
                    graphics.fillRect(bounds.x, y, ACCENT_STRIPE_WIDTH, h);
                    graphics.setColor(previous);
                }
            }
            super.paint(graphics, allocation);
        }
    }
}
