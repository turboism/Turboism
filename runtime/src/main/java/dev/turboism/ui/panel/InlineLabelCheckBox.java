package dev.turboism.ui.panel;

import dev.turboism.sdk.ui.UiInlineLabel;
import dev.turboism.sdk.ui.resource.UiIconRef;

import javax.swing.Icon;
import javax.swing.JCheckBox;
import javax.swing.SwingConstants;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiFunction;

/**
 * Runtime-only JCheckBox that paints a typed inline label without creating input-stealing child
 * components. The checkbox UI continues to paint the standard selection marker; the icon runs are
 * only label content and are never installed as the checkbox marker.
 */
final class InlineLabelCheckBox extends JCheckBox {
    static final int LOGICAL_ICON_SIZE = 16;
    private static final int ICON_GAP = 5;
    private static final int MAX_NATURAL_WIDTH = 640;

    private final UiInlineLabel label;
    private final List<ResolvedRun> runs;
    private final boolean grayed;

    InlineLabelCheckBox(
        final String id,
        final UiInlineLabel label,
        final boolean selected,
        final boolean grayed,
        final BiFunction<UiIconRef, Boolean, Optional<Icon>> iconResolver
    ) {
        super("", selected);
        this.label = Objects.requireNonNull(label, "label");
        this.grayed = grayed;
        this.runs = resolveRuns(label, Objects.requireNonNull(iconResolver, "iconResolver"), grayed);
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

    /** Package-visible observation hook for renderer tests; no SDK/API exposure. */
    String accessibleLabel() {
        return label.accessibleText();
    }

    /** Package-visible observation hook for renderer tests; no SDK/API exposure. */
    String renderedFallbackText() {
        return runs.stream().map(ResolvedRun::fallbackText).reduce("", String::concat);
    }

    @Override
    public Dimension getPreferredSize() {
        final Dimension marker = markerPreferredSize();
        final FontMetrics metrics = getFontMetrics(getFont());
        final int naturalWidth = Math.min(
            MAX_NATURAL_WIDTH,
            Math.max(1, layout(runs, metrics, Integer.MAX_VALUE / 4).width())
        );
        final boolean assignedWidth = getWidth() > 0;
        final int contentWidth = assignedWidth
            ? Math.max(1, getWidth() - labelStart(marker) - getInsets().right)
            : naturalWidth;
        final LabelLayout wrapped = layout(runs, metrics, contentWidth);
        final Insets insets = getInsets();
        final int height = Math.max(
            marker.height,
            insets.top + wrapped.lineCount() * wrapped.lineHeight() + insets.bottom
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
        // but no plugin-provided text. Inline runs are painted below as literal graphics text.
        super.paintComponent(graphics);

        final Graphics2D copy = (Graphics2D) graphics.create();
        try {
            final FontMetrics metrics = copy.getFontMetrics(getFont());
            final Dimension marker = markerPreferredSize();
            final int start = labelStart(marker);
            final int availableWidth = Math.max(1, getWidth() - start - getInsets().right);
            final LabelLayout wrapped = layout(runs, metrics, availableWidth);
            final int lineHeight = wrapped.lineHeight();
            final Insets insets = getInsets();
            final Color foreground = grayed || !isEnabled() ? disabledForeground() : getForeground();
            copy.setColor(foreground == null ? Color.GRAY : foreground);
            copy.setFont(getFont());

            int y = insets.top;
            for (List<VisualAtom> line : wrapped.lines()) {
                final int baseline = y + Math.max(0, (lineHeight - metrics.getHeight()) / 2)
                    + metrics.getAscent();
                int x = start;
                int previousRun = -1;
                for (VisualAtom atom : line) {
                    if (previousRun >= 0 && previousRun != atom.runIndex()
                        && (runs.get(previousRun).iconSlot() || runs.get(atom.runIndex()).iconSlot())) {
                        x += ICON_GAP;
                    }
                    if (atom.icon() != null) {
                        paintScaledIcon(atom.icon(), this, copy, x, y + (lineHeight - LOGICAL_ICON_SIZE) / 2);
                        x += LOGICAL_ICON_SIZE;
                    } else {
                        copy.drawString(atom.text(), x, baseline);
                        x += metrics.stringWidth(atom.text());
                    }
                    previousRun = atom.runIndex();
                }
                y += lineHeight;
            }
        } finally {
            copy.dispose();
        }
    }

    private Dimension markerPreferredSize() {
        return super.getPreferredSize();
    }

    private int labelStart(final Dimension marker) {
        return Math.max(1, marker.width) + ICON_GAP;
    }

    private static List<ResolvedRun> resolveRuns(
        final UiInlineLabel label,
        final BiFunction<UiIconRef, Boolean, Optional<Icon>> iconResolver,
        final boolean disabled
    ) {
        final List<ResolvedRun> resolved = new ArrayList<>(label.runs().size());
        int index = 0;
        for (UiInlineLabel.Run run : label.runs()) {
            if (run instanceof UiInlineLabel.TextRun text) {
                resolved.add(new ResolvedRun(text.text(), null, false, index));
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
                resolved.add(new ResolvedRun(iconRun.fallbackText(), icon, true, index));
            } else {
                throw new IllegalArgumentException("unsupported inline label run: " + run.getClass());
            }
            index++;
        }
        return List.copyOf(resolved);
    }

    private static void paintScaledIcon(
        final Icon icon,
        final Component component,
        final Graphics2D graphics,
        final int x,
        final int y
    ) {
        final int width = icon.getIconWidth();
        final int height = icon.getIconHeight();
        if (width <= 0 || height <= 0) return;
        final Graphics2D iconGraphics = (Graphics2D) graphics.create();
        try {
            iconGraphics.translate(x, y);
            iconGraphics.scale(
                (double) LOGICAL_ICON_SIZE / width,
                (double) LOGICAL_ICON_SIZE / height
            );
            icon.paintIcon(component, iconGraphics, 0, 0);
        } finally {
            iconGraphics.dispose();
        }
    }

    private static LabelLayout layout(
        final List<ResolvedRun> runs,
        final FontMetrics metrics,
        final int maximumWidth
    ) {
        final int width = Math.max(1, maximumWidth);
        final List<List<VisualAtom>> lines = new ArrayList<>();
        List<VisualAtom> current = new ArrayList<>();
        int currentWidth = 0;
        int maximumLineWidth = 0;
        int lineHeight = Math.max(metrics.getHeight(), LOGICAL_ICON_SIZE);
        boolean lineBreakPending = false;

        for (ResolvedRun run : runs) {
            if (run.icon() != null) {
                int gap = gap(current, run, runs);
                if (!current.isEmpty() && currentWidth + gap + LOGICAL_ICON_SIZE > width) {
                    maximumLineWidth = Math.max(maximumLineWidth, currentWidth);
                    lines.add(current);
                    current = new ArrayList<>();
                    currentWidth = 0;
                    gap = 0;
                }
                current.add(new VisualAtom(null, run.icon(), run.runIndex()));
                currentWidth += gap + LOGICAL_ICON_SIZE;
                lineBreakPending = false;
                continue;
            }

            final String text = run.fallbackText();
            for (int offset = 0; offset < text.length();) {
                final int codePoint = text.codePointAt(offset);
                final int count = Character.charCount(codePoint);
                if (codePoint == '\r' || codePoint == '\n') {
                    maximumLineWidth = Math.max(maximumLineWidth, currentWidth);
                    lines.add(current);
                    current = new ArrayList<>();
                    currentWidth = 0;
                    offset += count;
                    if (codePoint == '\r' && offset < text.length() && text.charAt(offset) == '\n') {
                        offset++;
                    }
                    lineBreakPending = true;
                    continue;
                }

                final String value = text.substring(offset, offset + count);
                final int itemWidth = Math.max(0, metrics.stringWidth(value));
                int gap = gap(current, run, runs);
                if (!current.isEmpty() && currentWidth + gap + itemWidth > width) {
                    maximumLineWidth = Math.max(maximumLineWidth, currentWidth);
                    lines.add(current);
                    current = new ArrayList<>();
                    currentWidth = 0;
                    gap = 0;
                }
                current.add(new VisualAtom(value, null, run.runIndex()));
                currentWidth += gap + itemWidth;
                lineBreakPending = false;
                offset += count;
            }
        }

        maximumLineWidth = Math.max(maximumLineWidth, currentWidth);
        if (!current.isEmpty() || lines.isEmpty() || lineBreakPending) {
            lines.add(current);
        }
        return new LabelLayout(List.copyOf(lines.stream().map(List::copyOf).toList()), maximumLineWidth, lineHeight);
    }

    private static int gap(
        final List<VisualAtom> current,
        final ResolvedRun run,
        final List<ResolvedRun> runs
    ) {
        if (current.isEmpty()) return 0;
        final int previous = current.get(current.size() - 1).runIndex();
        return previous != run.runIndex()
            && (runs.get(previous).iconSlot() || run.iconSlot())
            ? ICON_GAP
            : 0;
    }

    private static Color disabledForeground() {
        final Color color = javax.swing.UIManager.getColor("Label.disabledForeground");
        return color == null ? new Color(0x999999) : color;
    }

    private record ResolvedRun(String fallbackText, Icon icon, boolean iconSlot, int runIndex) { }

    private record VisualAtom(String text, Icon icon, int runIndex) { }

    private record LabelLayout(List<List<VisualAtom>> lines, int width, int lineHeight) {
        private int lineCount() {
            return Math.max(1, lines.size());
        }
    }
}
