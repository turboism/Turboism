package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.UiIconRef;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.SwingConstants;
import javax.swing.UIManager;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Stroke;
import java.awt.font.FontRenderContext;
import java.awt.font.GraphicAttribute;
import java.awt.font.LineBreakMeasurer;
import java.awt.font.TextAttribute;
import java.awt.font.TextLayout;
import java.awt.geom.Rectangle2D;
import java.text.AttributedCharacterIterator;
import java.text.AttributedString;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Runtime-only JCheckBox that paints a typed inline label without creating input-stealing child
 * components. The checkbox UI continues to paint the standard selection marker; the icon runs are
 * only label content and are never installed as the checkbox marker.
 *
 * <p>Text is shaped by {@link TextLayout}; it is never split into individual code points. This
 * keeps combining marks, emoji ZWJ sequences, bidirectional text and mixed-script runs together
 * for the platform font engine while retaining literal text semantics.</p>
 */
final class InlineLabelCheckBox extends JCheckBox {
    static final int LOGICAL_ICON_SIZE = 16;
    private static final int ICON_GAP = 5;
    private static final int MAX_NATURAL_WIDTH = 640;
    private static final char ICON_PLACEHOLDER = '\uFFFC';

    private final UiInlineLabel label;
    private final BiFunction<UiIconRef, Boolean, Optional<Icon>> iconResolver;
    private final boolean grayed;
    private List<ResolvedRun> runs;

    InlineLabelCheckBox(
        final String id,
        final UiInlineLabel label,
        final boolean selected,
        final boolean grayed,
        final BiFunction<UiIconRef, Boolean, Optional<Icon>> iconResolver
    ) {
        super("", selected);
        this.label = Objects.requireNonNull(label, "label");
        this.iconResolver = Objects.requireNonNull(iconResolver, "iconResolver");
        this.grayed = grayed;
        this.runs = resolveRuns(label, this.iconResolver, grayed);
        setName(Objects.requireNonNull(id, "id"));
        setHorizontalAlignment(SwingConstants.LEFT);
        setFocusable(true);
        // Gray is presentation only. A redo row remains enabled and keeps the standard keyboard
        // action and full-row mouse target.
        if (grayed) {
            setForeground(disabledForeground());
        }
        final var accessible = getAccessibleContext();
        accessible.setAccessibleName(label.accessibleText());
        accessible.setAccessibleDescription(label.accessibleText());
    }

    /** Re-resolves bounded icon presentation and drops every previously retained icon handle. */
    void refreshPresentation() {
        runs = resolveRuns(label, iconResolver, grayed);
        if (grayed) {
            setForeground(disabledForeground());
        }
        revalidate();
        repaint();
    }

    /** Package-visible observation hook for renderer tests; no SDK/API exposure. */
    String accessibleLabel() {
        return label.accessibleText();
    }

    /** Package-visible observation hook for renderer tests; no SDK/API exposure. */
    String renderedFallbackText() {
        return runs.stream().map(ResolvedRun::fallbackText).reduce("", String::concat);
    }

    /** Package-visible observation hook for renderer tests; no SDK/API exposure. */
    int resolvedIconCount() {
        return (int) runs.stream().filter(run -> run.icon() != null).count();
    }

    /** Package-visible focus-paint hook used to verify the explicit row focus treatment. */
    void paintFocusIndicatorForTest(final Graphics2D graphics) {
        paintFocusIndicator(graphics);
    }

    @Override
    public Dimension getPreferredSize() {
        final Dimension marker = markerPreferredSize();
        final Font font = labelFont();
        final FontMetrics metrics = getFontMetrics(font);
        final FontRenderContext fontRenderContext = metrics.getFontRenderContext();
        final LabelLayout natural = layout(runs, font, fontRenderContext, Integer.MAX_VALUE / 4, this);
        final int naturalWidth = Math.min(
            MAX_NATURAL_WIDTH,
            Math.max(1, natural.width())
        );
        final boolean assignedWidth = getWidth() > 0;
        final int contentWidth = assignedWidth
            ? Math.max(1, getWidth() - labelStart(marker) - getInsets().right)
            : naturalWidth;
        final LabelLayout wrapped = layout(runs, font, fontRenderContext, contentWidth, this);
        final Insets insets = getInsets();
        final int height = Math.max(
            marker.height,
            insets.top + wrapped.height() + insets.bottom
        );
        final int preferredWidth = assignedWidth ? getWidth() : labelStart(marker) + naturalWidth;
        return new Dimension(preferredWidth, height);
    }

    @Override
    public Dimension getMaximumSize() {
        return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
    }

    @Override
    protected void paintComponent(final Graphics graphics) {
        // With an empty Swing text value this paints the platform checkbox marker and focus state,
        // but no plugin-provided text. Inline runs are painted below as literal shaped text.
        super.paintComponent(graphics);

        final Graphics2D copy = (Graphics2D) graphics.create();
        try {
            final Font font = labelFont();
            final Dimension marker = markerPreferredSize();
            final int start = labelStart(marker);
            final int availableWidth = Math.max(1, getWidth() - start - getInsets().right);
            final LabelLayout wrapped = layout(runs, font, copy.getFontRenderContext(), availableWidth, this);
            final Insets insets = getInsets();
            final Color foreground = grayed || !isEnabled() ? disabledForeground() : getForeground();
            copy.setColor(foreground == null ? Color.GRAY : foreground);
            copy.setFont(font);

            float y = insets.top;
            for (VisualLine line : wrapped.lines()) {
                if (line.layout() != null) {
                    line.layout().draw(copy, start, y + line.layout().getAscent());
                }
                y += line.height();
            }
            if (isFocusOwner() && isFocusPainted()) {
                paintFocusIndicator(copy);
            }
        } finally {
            copy.dispose();
        }
    }

    private Font labelFont() {
        final Font font = getFont();
        return font == null ? new Font(Font.DIALOG, Font.PLAIN, 12) : font;
    }

    private Dimension markerPreferredSize() {
        return super.getPreferredSize();
    }

    private int labelStart(final Dimension marker) {
        return Math.max(1, marker.width) + ICON_GAP;
    }

    private void paintFocusIndicator(final Graphics2D graphics) {
        if (getWidth() < 4 || getHeight() < 4) return;
        final Color oldColor = graphics.getColor();
        final Stroke oldStroke = graphics.getStroke();
        final Color focus = UIManager.getColor("Component.focusColor");
        graphics.setColor(focus == null ? new Color(0x4A90E2) : focus);
        graphics.setStroke(new BasicStroke(
            1f,
            BasicStroke.CAP_BUTT,
            BasicStroke.JOIN_MITER,
            10f,
            new float[]{2f, 2f},
            0f
        ));
        graphics.drawRect(1, 1, getWidth() - 3, getHeight() - 3);
        graphics.setStroke(oldStroke);
        graphics.setColor(oldColor);
    }

    private static List<ResolvedRun> resolveRuns(
        final UiInlineLabel label,
        final BiFunction<UiIconRef, Boolean, Optional<Icon>> iconResolver,
        final boolean disabled
    ) {
        final List<ResolvedRun> resolved = new ArrayList<>(label.runs().size());
        for (UiInlineLabel.Run run : label.runs()) {
            if (run instanceof UiInlineLabel.TextRun text) {
                resolved.add(new ResolvedRun(text.text(), null));
            } else if (run instanceof UiInlineLabel.IconRun iconRun) {
                Icon icon = null;
                try {
                    final Optional<Icon> candidate = iconResolver.apply(iconRun.icon(), disabled);
                    if (candidate != null && candidate.isPresent()) {
                        final Icon value = candidate.orElse(null);
                        if (value != null && value.getIconWidth() > 0 && value.getIconHeight() > 0) {
                            icon = value;
                        }
                    }
                } catch (RuntimeException ignored) {
                    // A display resolver is optional. A failed/missing icon must degrade to the
                    // run's localized text rather than disabling or breaking the row.
                }
                resolved.add(new ResolvedRun(iconRun.fallbackText(), icon));
            } else {
                throw new IllegalArgumentException("unsupported inline label run: " + run.getClass());
            }
        }
        return List.copyOf(resolved);
    }

    private static LabelLayout layout(
        final List<ResolvedRun> runs,
        final Font font,
        final FontRenderContext fontRenderContext,
        final int maximumWidth,
        final Component component
    ) {
        final int width = Math.max(1, maximumWidth);
        final ParagraphsBuilder paragraphs = new ParagraphsBuilder(component);
        for (ResolvedRun run : runs) {
            if (run.icon() != null) {
                paragraphs.appendIcon(run.icon());
            } else {
                paragraphs.appendText(run.fallbackText());
            }
        }
        paragraphs.finish();

        final List<VisualLine> lines = new ArrayList<>();
        int maximumLineWidth = 0;
        int totalHeight = 0;
        for (ParagraphBuilder paragraph : paragraphs.paragraphs()) {
            if (paragraph.isEmpty()) {
                final int lineHeight = emptyLineHeight(font, fontRenderContext);
                lines.add(new VisualLine(null, 0, lineHeight));
                totalHeight += lineHeight;
                continue;
            }

            final AttributedString attributed = paragraph.toAttributedString(font);
            final AttributedCharacterIterator iterator = attributed.getIterator();
            final LineBreakMeasurer measurer = new LineBreakMeasurer(iterator, fontRenderContext);
            final int end = iterator.getEndIndex();
            boolean emitted = false;
            while (measurer.getPosition() < end) {
                final TextLayout textLayout = measurer.nextLayout(width);
                if (textLayout == null) {
                    break;
                }
                final int lineWidth = Math.max(0, (int) Math.ceil(textLayout.getAdvance()));
                final int lineHeight = Math.max(
                    1,
                    (int) Math.ceil(
                        textLayout.getAscent() + textLayout.getDescent() + textLayout.getLeading()
                    )
                );
                lines.add(new VisualLine(textLayout, lineWidth, lineHeight));
                maximumLineWidth = Math.max(maximumLineWidth, lineWidth);
                totalHeight += lineHeight;
                emitted = true;
            }
            if (!emitted) {
                final int lineHeight = emptyLineHeight(font, fontRenderContext);
                lines.add(new VisualLine(null, 0, lineHeight));
                totalHeight += lineHeight;
            }
        }
        return new LabelLayout(List.copyOf(lines), maximumLineWidth, totalHeight);
    }

    private static int emptyLineHeight(final Font font, final FontRenderContext fontRenderContext) {
        return Math.max(1, (int) Math.ceil(font.getLineMetrics("", fontRenderContext).getHeight()));
    }

    private static final class ParagraphsBuilder {
        private final Component component;
        private final List<ParagraphBuilder> paragraphs = new ArrayList<>();
        private ParagraphBuilder current;
        private boolean suppressLeadingLf;

        private ParagraphsBuilder(final Component component) {
            this.component = Objects.requireNonNull(component, "component");
            this.current = new ParagraphBuilder(component);
        }

        void appendText(final String value) {
            Objects.requireNonNull(value, "value");
            for (int index = 0; index < value.length(); index++) {
                final char character = value.charAt(index);
                if (suppressLeadingLf) {
                    suppressLeadingLf = false;
                    if (character == '\n') {
                        continue;
                    }
                }
                if (character == '\r' || character == '\n') {
                    paragraphs.add(current);
                    current = new ParagraphBuilder(component);
                    suppressLeadingLf = character == '\r';
                } else {
                    final int start = index;
                    index++;
                    while (index < value.length()) {
                        final char next = value.charAt(index);
                        if (next == '\r' || next == '\n') {
                            break;
                        }
                        index++;
                    }
                    current.appendText(value.substring(start, index));
                    index--;
                }
            }
        }

        void appendIcon(final Icon icon) {
            suppressLeadingLf = false;
            current.appendIcon(icon);
        }

        void finish() {
            paragraphs.add(current);
        }

        List<ParagraphBuilder> paragraphs() {
            return paragraphs;
        }
    }

    private static final class ParagraphBuilder {
        private final Component component;
        private final StringBuilder text = new StringBuilder();
        private final List<IconPlacement> icons = new ArrayList<>();

        private ParagraphBuilder(final Component component) {
            this.component = Objects.requireNonNull(component, "component");
        }

        void appendText(final String value) {
            text.append(value);
        }

        void appendIcon(final Icon icon) {
            final int start = text.length();
            text.append(ICON_PLACEHOLDER);
            icons.add(new IconPlacement(
                start,
                new IconGraphicAttribute(icon, component)
            ));
        }

        boolean isEmpty() {
            return text.length() == 0;
        }

        AttributedString toAttributedString(final Font font) {
            final AttributedString attributed = new AttributedString(text.toString());
            attributed.addAttribute(TextAttribute.FONT, font);
            for (IconPlacement placement : icons) {
                attributed.addAttribute(
                    TextAttribute.CHAR_REPLACEMENT,
                    placement.attribute(),
                    placement.start(),
                    placement.start() + 1
                );
            }
            return attributed;
        }
    }


    private static final class IconGraphicAttribute extends GraphicAttribute {
        private final Icon icon;
        private final Component component;

        private IconGraphicAttribute(final Icon icon, final Component component) {
            super(TOP_ALIGNMENT);
            this.icon = Objects.requireNonNull(icon, "icon");
            this.component = Objects.requireNonNull(component, "component");
        }

        @Override
        public float getAscent() {
            return LOGICAL_ICON_SIZE;
        }

        @Override
        public float getDescent() {
            return 0;
        }

        @Override
        public float getAdvance() {
            return LOGICAL_ICON_SIZE + (2f * ICON_GAP);
        }

        @Override
        public Rectangle2D getBounds() {
            return new Rectangle2D.Float(
                ICON_GAP,
                -LOGICAL_ICON_SIZE,
                LOGICAL_ICON_SIZE,
                LOGICAL_ICON_SIZE
            );
        }

        @Override
        public void draw(final Graphics2D graphics, final float x, final float y) {
            final int width = icon.getIconWidth();
            final int height = icon.getIconHeight();
            if (width <= 0 || height <= 0) return;
            final Graphics2D iconGraphics = (Graphics2D) graphics.create();
            try {
                iconGraphics.translate(x + ICON_GAP, y - LOGICAL_ICON_SIZE);
                iconGraphics.scale(
                    (double) LOGICAL_ICON_SIZE / width,
                    (double) LOGICAL_ICON_SIZE / height
                );
                icon.paintIcon(component, iconGraphics, 0, 0);
            } finally {
                iconGraphics.dispose();
            }
        }
    }

    private record ResolvedRun(String fallbackText, Icon icon) { }

    private record IconPlacement(int start, IconGraphicAttribute attribute) { }

    private record VisualLine(TextLayout layout, int width, int height) { }

    private record LabelLayout(List<VisualLine> lines, int width, int height) { }

    private static Color disabledForeground() {
        final Color color = UIManager.getColor("Label.disabledForeground");
        return color == null ? new Color(0x999999) : color;
    }
}
