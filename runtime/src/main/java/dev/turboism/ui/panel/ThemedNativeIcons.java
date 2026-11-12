package dev.turboism.ui.panel;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import javax.swing.AbstractButton;
import javax.swing.Icon;
import javax.swing.JComponent;
import javax.swing.UIManager;

/**
 * Removes the baked light plate of a native host icon and paints the glyph in the theme
 * foreground.
 *
 * <p>The editor rasterises its toolbar icons onto a pale square, so an injected tool-details
 * container that follows a Turboism theme still shows one light box per host button. This owner
 * walks the host buttons of a themed container, replaces a plated icon with a processed copy that
 * keeps the declared size, and restores the shipped icon when the theme leaves.</p>
 *
 * <p>Only a uniformly light plate is processed: a multi-colour, a partly transparent or an already
 * transparent bitmap keeps its shipped pixels, so a host glyph is never mangled. The owner that
 * creates this gate reports an active theme while the runtime publishes its overrides, which it
 * writes on apply and deletes on restore. Call the mutators on the Swing event dispatch thread,
 * and call {@link #refresh()} when the look and feel changes so a live theme switch recolours the
 * same buttons.</p>
 */
final class ThemedNativeIcons {

    private static final String FOREGROUND_KEY = "CubismCommon.foreground";
    private static final String LABEL_FOREGROUND_KEY = "Label.foreground";
    private static final Color FALLBACK_FOREGROUND = new Color(0xF0F0F0);

    /** Upper bound on retained processed bitmaps; the whole cache drops at the bound. */
    private static final int MAX_CACHED_ICONS = 64;

    private static final int CHANNEL_MASK = 0xFF;
    private static final int RGB_MASK = 0xFFFFFF;
    private static final int GREEN_SHIFT = 8;
    private static final int RED_SHIFT = 16;
    private static final int ALPHA_SHIFT = 24;
    private static final int FULLY_OPAQUE = 0xFF;
    private static final int TRANSPARENT = 0;
    private static final int PERCENT = 100;

    /** Largest channel distance from the plate colour that still counts as plate, not glyph. */
    private static final int PLATE_TOLERANCE = 16;

    /** Lowest per-channel value of the plate colour that counts as a light plate. */
    private static final int MINIMUM_PLATE_CHANNEL = 192;

    /** Share of the icon border that must carry the plate colour before it counts as a plate. */
    private static final int MINIMUM_PLATE_BORDER_PERCENT = 50;

    /** Channel distance from the plate colour at which a glyph pixel becomes fully opaque. */
    private static final int INK_FULL_ALPHA_DISTANCE = 96;

    private static final int INK_ALPHA_SPAN = INK_FULL_ALPHA_DISTANCE - PLATE_TOLERANCE;
    private static final int NO_PLATE = -1;
    private static final int FIRST_OWNER = 1;

    private final BooleanSupplier themeActive;
    private final Map<JComponent, List<AbstractButton>> buttons = new IdentityHashMap<>();
    private final Map<AbstractButton, Integer> owners = new IdentityHashMap<>();
    private final Map<Icon, Processed> cache = new IdentityHashMap<>();

    /**
     * Creates an owner with an explicit theme verdict, so a test can pin the gate.
     *
     * @param themeActive the active-theme verdict; not {@code null}
     * @throws NullPointerException if {@code themeActive} is {@code null}
     */
    ThemedNativeIcons(final BooleanSupplier themeActive) {
        this.themeActive = Objects.requireNonNull(themeActive, "themeActive");
    }

    /**
     * Records the host buttons inside one container and processes their icons while a theme is
     * active.
     *
     * <p>Repeated calls for the same container keep one record per button, so a container that
     * several controls share is processed once per icon identity.</p>
     *
     * @param host the host container to walk; not {@code null}
     * @throws NullPointerException if {@code host} is {@code null}
     */
    void decorate(final JComponent host) {
        Objects.requireNonNull(host, "host");
        final List<AbstractButton> owned = buttons.computeIfAbsent(host, key -> new ArrayList<>());
        track(host, owned);
        if (!themeActive.getAsBoolean()) return;
        apply(owned, inkColor());
    }

    /**
     * Reapplies the current theme verdict and foreground to every recorded host button.
     *
     * <p>Call this after the look and feel changed: an active theme recolours the glyphs, and a
     * removed theme puts the shipped icons back.</p>
     */
    void refresh() {
        if (!themeActive.getAsBoolean()) {
            for (List<AbstractButton> owned : buttons.values()) {
                for (AbstractButton button : owned) revert(button);
            }
            return;
        }
        final Color ink = inkColor();
        for (List<AbstractButton> owned : buttons.values()) apply(owned, ink);
    }

    /**
     * Drops one ownership of a container and restores the shipped icons of the last owner.
     *
     * <p>A container this owner does not record is ignored, and a button that another container
     * still covers keeps the processed icon.</p>
     *
     * @param host the container to release; {@code null} is ignored
     */
    void restore(final JComponent host) {
        if (host == null) return;
        final List<AbstractButton> owned = buttons.remove(host);
        if (owned == null) return;
        for (AbstractButton button : owned) {
            final Integer remaining = owners.get(button);
            if (remaining == null) continue;
            final int left = remaining - FIRST_OWNER;
            if (left > 0) {
                owners.put(button, left);
                continue;
            }
            owners.remove(button);
            revert(button);
        }
    }

    /**
     * Replaces one plated bitmap with the theme foreground glyph at the declared size.
     *
     * <p>The returned icon is the given icon when its border is not a uniform light plate, when it
     * renders no glyph, or when it cannot render without its component.</p>
     *
     * @param source the shipped host icon; not {@code null}
     * @param ink the glyph colour the theme foreground provides; not {@code null}
     * @return the processed icon, or {@code source} when it must stay untouched
     * @throws NullPointerException if either argument is {@code null}
     */
    static Icon recolored(final Icon source, final Color ink) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(ink, "ink");
        final int width = source.getIconWidth();
        final int height = source.getIconHeight();
        if (width <= 0 || height <= 0) return source;
        final BufferedImage raster = render(source, width, height);
        if (raster == null) return source;
        final int plate = plateColor(raster);
        if (plate == NO_PLATE) return source;
        final BufferedImage glyph = tinted(raster, plate, ink);
        if (glyph == null) return source;
        return new ProcessedGlyphIcon(source, glyph, width, height);
    }

    private void track(final Container container, final List<AbstractButton> owned) {
        for (Component child : container.getComponents()) {
            if (child instanceof AbstractButton button && !owned.contains(button)) {
                owned.add(button);
                owners.merge(button, FIRST_OWNER, Integer::sum);
            }
            if (child instanceof Container nested) track(nested, owned);
        }
    }

    private void apply(final List<AbstractButton> owned, final Color ink) {
        for (AbstractButton button : owned) {
            process(button, ink);
        }
    }

    /**
     * Writes the processed icon into every state slot from the shipped icon, so a repeated pass
     * sees the same source identity and the cache answers instead of reprocessing.
     */
    private void process(final AbstractButton button, final Color ink) {
        for (Slot slot : Slot.values()) {
            final Icon current = slot.read(button);
            if (current == null) continue;
            slot.write(button, resolved(sourceOf(current), ink));
        }
    }

    private Icon resolved(final Icon source, final Color ink) {
        final Processed cached = cache.get(source);
        if (cached != null && cached.ink().equals(ink)) return cached.icon();
        final Icon icon = recolored(source, ink);
        if (cache.size() >= MAX_CACHED_ICONS) cache.clear();
        cache.put(source, new Processed(ink, icon));
        return icon;
    }

    private static void revert(final AbstractButton button) {
        for (Slot slot : Slot.values()) {
            final Icon current = slot.read(button);
            if (current == null) continue;
            slot.write(button, sourceOf(current));
        }
    }

    private static Icon sourceOf(final Icon icon) {
        return icon instanceof ProcessedGlyphIcon processed ? processed.source : icon;
    }

    private static Color inkColor() {
        final Color themed = UIManager.getColor(FOREGROUND_KEY);
        if (themed != null) return themed;
        final Color label = UIManager.getColor(LABEL_FOREGROUND_KEY);
        return label == null ? FALLBACK_FOREGROUND : label;
    }

    private static BufferedImage render(final Icon source, final int width, final int height) {
        final BufferedImage raster = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final Graphics2D graphics = raster.createGraphics();
        try {
            source.paintIcon(null, graphics, 0, 0);
            return raster;
        } catch (RuntimeException failure) {
            // An icon that needs its component keeps the pixels the editor shipped.
            return null;
        } finally {
            graphics.dispose();
        }
    }

    /**
     * Detects the baked plate of one bitmap.
     *
     * <p>The verdict reads the icon border only: the dominant opaque border colour is the plate
     * candidate, and it counts as a plate only while it covers most of the border and stays light.
     * Any other border means the icon carries its own transparency or its own colours, and it must
     * stay untouched.</p>
     *
     * @return the packed plate colour, or {@link #NO_PLATE}
     */
    private static int plateColor(final BufferedImage raster) {
        final int width = raster.getWidth();
        final int height = raster.getHeight();
        final Map<Integer, Integer> counts = new HashMap<>();
        int sampled = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                if (isInteriorPixel(x, y, width, height)) continue;
                sampled++;
                final int argb = raster.getRGB(x, y);
                if (channel(argb, ALPHA_SHIFT) != FULLY_OPAQUE) continue;
                counts.merge(argb & RGB_MASK, 1, Integer::sum);
            }
        }
        int plate = NO_PLATE;
        int best = 0;
        for (Map.Entry<Integer, Integer> entry : counts.entrySet()) {
            if (entry.getValue() <= best) continue;
            plate = entry.getKey();
            best = entry.getValue();
        }
        if (plate == NO_PLATE) return NO_PLATE;
        if (best * PERCENT < sampled * MINIMUM_PLATE_BORDER_PERCENT) return NO_PLATE;
        return isLight(plate) ? plate : NO_PLATE;
    }

    private static boolean isInteriorPixel(final int x, final int y, final int width, final int height) {
        return x > 0 && y > 0 && x < width - 1 && y < height - 1;
    }

    /**
     * Builds the transparent-background glyph image.
     *
     * @return the glyph image, or {@code null} when the plate holds no glyph pixel
     */
    private static BufferedImage tinted(final BufferedImage raster, final int plate, final Color ink) {
        final int width = raster.getWidth();
        final int height = raster.getHeight();
        final BufferedImage glyph = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        final int inkRgb = ink.getRGB() & RGB_MASK;
        int inked = 0;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int pixel = raster.getRGB(x, y);
                final int alpha = channel(pixel, ALPHA_SHIFT);
                if (alpha == TRANSPARENT) continue;
                final int distance = distance(plate, pixel);
                if (distance <= PLATE_TOLERANCE) continue;
                final int ramp = distance >= INK_FULL_ALPHA_DISTANCE
                        ? FULLY_OPAQUE
                        : (distance - PLATE_TOLERANCE) * FULLY_OPAQUE / INK_ALPHA_SPAN;
                final int inkAlpha = ramp * alpha / FULLY_OPAQUE;
                if (inkAlpha == TRANSPARENT) continue;
                glyph.setRGB(x, y, (inkAlpha << ALPHA_SHIFT) | inkRgb);
                inked++;
            }
        }
        return inked == 0 ? null : glyph;
    }

    private static boolean isLight(final int rgb) {
        return channel(rgb, RED_SHIFT) >= MINIMUM_PLATE_CHANNEL
                && channel(rgb, GREEN_SHIFT) >= MINIMUM_PLATE_CHANNEL
                && (rgb & CHANNEL_MASK) >= MINIMUM_PLATE_CHANNEL;
    }

    /** Largest per-channel difference between two packed colours. */
    private static int distance(final int first, final int second) {
        final int red = Math.abs(channel(first, RED_SHIFT) - channel(second, RED_SHIFT));
        final int green = Math.abs(channel(first, GREEN_SHIFT) - channel(second, GREEN_SHIFT));
        final int blue = Math.abs((first & CHANNEL_MASK) - (second & CHANNEL_MASK));
        return Math.max(red, Math.max(green, blue));
    }

    private static int channel(final int argb, final int shift) {
        return (argb >> shift) & CHANNEL_MASK;
    }

    /** One processed bitmap and the glyph colour it was built for. */
    private record Processed(Color ink, Icon icon) {}

    /** Icon slot a host button paints; the six state slots mirror the native icon-button set. */
    private enum Slot {
        ICON(AbstractButton::getIcon, AbstractButton::setIcon),
        PRESSED(AbstractButton::getPressedIcon, AbstractButton::setPressedIcon),
        ROLLOVER(AbstractButton::getRolloverIcon, AbstractButton::setRolloverIcon),
        ROLLOVER_SELECTED(AbstractButton::getRolloverSelectedIcon, AbstractButton::setRolloverSelectedIcon),
        SELECTED(AbstractButton::getSelectedIcon, AbstractButton::setSelectedIcon),
        DISABLED(AbstractButton::getDisabledIcon, AbstractButton::setDisabledIcon),
        DISABLED_SELECTED(AbstractButton::getDisabledSelectedIcon, AbstractButton::setDisabledSelectedIcon);

        private final Function<AbstractButton, Icon> reader;
        private final BiConsumer<AbstractButton, Icon> writer;

        private Slot(final Function<AbstractButton, Icon> reader, final BiConsumer<AbstractButton, Icon> writer) {
            this.reader = reader;
            this.writer = writer;
        }

        private Icon read(final AbstractButton button) {
            return reader.apply(button);
        }

        private void write(final AbstractButton button, final Icon icon) {
            writer.accept(button, icon);
        }
    }

    /** Processed bitmap that keeps the declared size of the shipped icon and its source identity. */
    private static final class ProcessedGlyphIcon implements Icon {
        private final Icon source;
        private final BufferedImage glyph;
        private final int width;
        private final int height;

        private ProcessedGlyphIcon(final Icon source, final BufferedImage glyph, final int width, final int height) {
            this.source = source;
            this.glyph = glyph;
            this.width = width;
            this.height = height;
        }

        @Override
        public int getIconWidth() {
            return width;
        }

        @Override
        public int getIconHeight() {
            return height;
        }

        @Override
        public void paintIcon(final Component component, final Graphics graphics, final int x, final int y) {
            graphics.drawImage(glyph, x, y, width, height, null);
        }
    }
}
