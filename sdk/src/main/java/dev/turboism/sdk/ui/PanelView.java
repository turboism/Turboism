package dev.turboism.sdk.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import javax.imageio.ImageIO;

/** Immutable, toolkit-neutral panel content rendered and owned by the runtime. */
public sealed interface PanelView
        permits PanelView.Column,
                PanelView.Row,
                PanelView.Text,
                PanelView.Image,
                PanelView.ScaledImage,
                PanelView.Button,
                PanelView.TextInput,
                PanelView.Select,
                PanelView.Toggle,
                PanelView.Chart,
                PanelView.CollapsibleSection,
                PanelView.Separator,
                PanelView.Scroll,
                PanelView.Card,
                PanelView.Grid,
                PanelView.Centered,
                PanelView.Link,
                PanelView.SpaceBetween,
                PanelView.LeftRow,
                PanelView.IconButton,
                PanelView.ColorIconButton,
                PanelView.Padded {

    /** Creates a vertical stack of the given children. */
    static Column column(final PanelView... children) {
        return new Column(List.of(children));
    }

    /** Creates a horizontal row of the given children. */
    static Row row(final PanelView... children) {
        return new Row(List.of(children));
    }

    /** Creates a plain text node. */
    static Text text(final String value) {
        return new Text(value, false, false);
    }

    /** Text rendered in a grayed (disabled-looking) style, e.g. redo entries. */
    static Text text(final String value, final boolean grayed) {
        return new Text(value, grayed, false);
    }

    /** Text rendered at a scaled font size (1.0 is the host default). */
    static Text text(final String value, final boolean grayed, final float fontScale) {
        return new Text(value, grayed, false, fontScale);
    }

    /** Text rendered centered in its region, e.g. a panel header statistic. */
    static Text textCentered(final String value) {
        return new Text(value, false, true);
    }

    /** Text rendered centered and optionally grayed, e.g. a centered subtitle. */
    static Text textCentered(final String value, final boolean grayed) {
        return new Text(value, grayed, true);
    }

    /** Text rendered centered at a scaled font size. */
    static Text textCentered(final String value, final boolean grayed, final float fontScale) {
        return new Text(value, grayed, true, fontScale);
    }

    /** Creates an image node from PNG bytes with accessibility alt text. */
    static Image image(final byte[] pngBytes, final String altText) {
        return new Image(pngBytes, altText);
    }

    /**
     * Creates a high-resolution image node displayed inside the given logical bounds.
     * The runtime preserves the image aspect ratio, scales it down for 1x displays and
     * keeps the original pixels available as a device-resolution variant for HiDPI
     * displays.
     */
    static ScaledImage scaledImage(
            final byte[] pngBytes, final String altText, final int maxWidth, final int maxHeight) {
        return new ScaledImage(pngBytes, altText, maxWidth, maxHeight);
    }

    /** Creates a push button that invokes {@code actionId} when pressed. */
    static Button button(final String id, final String label, final String actionId) {
        return new Button(id, label, actionId);
    }

    /** Creates an icon-only button with tooltip text and no visible label. */
    static IconButton iconButton(final String id, final byte[] pngBytes, final String tooltip, final String actionId) {
        return new IconButton(id, pngBytes, tooltip, actionId);
    }

    /**
     * Creates an icon-only button that keeps its own colours.
     *
     * <p>{@link IconButton} tints its mask with the theme foreground, so it cannot signal meaning by
     * colour. A control that must do so supplies a coloured glyph here. A disabled control stays
     * visible, rejects input and paints its glyph muted.</p>
     */
    static ColorIconButton colorIconButton(
            final String id,
            final byte[] pngBytes,
            final String tooltip,
            final String actionId,
            final boolean enabled) {
        return new ColorIconButton(id, pngBytes, tooltip, actionId, enabled);
    }

    /** Wraps a child in an empty inset; useful for standalone header alignment. */
    static Padded padded(final PanelView child, final int top, final int left, final int bottom, final int right) {
        return new Padded(child, top, left, bottom, right);
    }

    /** Creates a labeled text field whose edits dispatch {@code actionId}. */
    static TextInput textInput(final String id, final String label, final String value, final String actionId) {
        return new TextInput(id, label, value, actionId);
    }

    /** Creates one selectable option for a {@link Select} control. */
    static Option option(final String value, final String label) {
        return new Option(value, label);
    }

    /** Creates a labeled single-select control whose changes dispatch {@code actionId}. */
    static Select select(
            final String id,
            final String label,
            final List<Option> options,
            final String selectedValue,
            final String actionId) {
        return new Select(id, label, options, selectedValue, actionId);
    }

    /** Creates a labeled toggle whose changes dispatch {@code actionId}. */
    static Toggle toggle(final String id, final String label, final boolean selected, final String actionId) {
        return new Toggle(id, label, selected, false, actionId);
    }

    /** Creates a labeled toggle with an explicit grayed (disabled-looking) style. */
    static Toggle toggle(
            final String id, final String label, final boolean selected, final boolean grayed, final String actionId) {
        return new Toggle(id, label, selected, grayed, actionId);
    }

    /**
     * Creates a selectable row whose label may place typed icons between literal text runs.
     * Missing icons are rendered with the fallback text embedded in {@code label}.
     */
    static Toggle toggle(final String id, final UiInlineLabel label, final boolean selected, final String actionId) {
        return new Toggle(id, label, selected, false, actionId);
    }

    /** Creates an icon-capable selectable row with an independent gray presentation flag. */
    static Toggle toggle(
            final String id,
            final UiInlineLabel label,
            final boolean selected,
            final boolean grayed,
            final String actionId) {
        return new Toggle(id, label, selected, grayed, actionId);
    }

    /** Creates a horizontal separator line. */
    static Separator separator() {
        return new Separator();
    }

    /** Wraps a single child in a scrollable viewport. */
    static Scroll scroll(final PanelView child) {
        return new Scroll(child);
    }

    /**
     * Declarative real-time line chart. Series display configuration (name,
     * window size, unit, format) is declared here; the numeric values are
     * injected by the runtime, which resolves live data by chart id.
     */
    static Chart chart(final String id, final String title, final SeriesSpec... series) {
        return new Chart(id, title, List.of(series));
    }

    /** Creates the display specification of one chart series. */
    static SeriesSpec series(final String name, final int maxPoints, final String unit, final String format) {
        return new SeriesSpec(name, maxPoints, unit, format);
    }

    /** Creates a titled collapsible section containing the given children. */
    static CollapsibleSection collapsibleSection(
            final String title, final boolean expandedByDefault, final PanelView... children) {
        return new CollapsibleSection(title, expandedByDefault, List.of(children));
    }

    /**
     * Creates a clickable card around {@code content}. Double-clicking the card
     * dispatches {@code actionId}; the card id is sent as the action event source.
     */
    static Card card(final String id, final String actionId, final PanelView content) {
        return new Card(id, actionId, content);
    }

    /** Creates a borderless clickable row, e.g. a details-list entry. */
    static Card flatCard(final String id, final String actionId, final PanelView content) {
        return new Card(id, actionId, content, false);
    }

    /** Creates a fixed-column grid of child nodes. */
    static Grid grid(final int columns, final PanelView... children) {
        return new Grid(columns, List.of(children));
    }

    /** Centers one child block horizontally and vertically in the available space. */
    static Centered centered(final PanelView child) {
        return new Centered(child, 0, 0);
    }

    /** Centers one child block inside a fixed preferred-size box. */
    static Centered centered(final PanelView child, final int preferredWidth, final int preferredHeight) {
        return new Centered(child, preferredWidth, preferredHeight);
    }

    /** Creates a blue underlined link that dispatches {@code actionId} on left click. */
    static Link link(final String id, final String label, final String actionId) {
        return new Link(id, label, actionId);
    }

    /** Creates a row with {@code left} aligned to the start and {@code right} to the end. */
    static SpaceBetween spaceBetween(final PanelView left, final PanelView right) {
        return new SpaceBetween(left, right);
    }

    /** Creates a row with {@code left} at its preferred size and {@code right} filling the rest. */
    static LeftRow leftRow(final PanelView left, final PanelView right) {
        return new LeftRow(left, right);
    }

    /** Vertical stack of child nodes. */
    record Column(List<PanelView> children) implements PanelView {
        public Column {
            children = immutableChildren(children);
        }
    }

    /** Horizontal row of child nodes. */
    record Row(List<PanelView> children) implements PanelView {
        public Row {
            children = immutableChildren(children);
        }
    }

    /** Plain text node; {@code grayed} renders disabled-looking, {@code centered} centers it. */
    record Text(String value, boolean grayed, boolean centered, float fontScale) implements PanelView {
        public Text {
            value = Objects.requireNonNull(value, "value");
            if (Float.isNaN(fontScale) || fontScale < 0.5f || fontScale > 3.0f) {
                throw new IllegalArgumentException("fontScale must be between 0.5 and 3.0");
            }
        }

        public Text(final String value, final boolean grayed, final boolean centered) {
            this(value, grayed, centered, 1.0f);
        }

        public Text(final String value, final boolean grayed) {
            this(value, grayed, false, 1.0f);
        }

        public Text(final String value) {
            this(value, false, false, 1.0f);
        }
    }

    /**
     * PNG image node (for example a recent-file preview thumbnail) with accessibility
     * alt text. The runtime renders it as an image label sized to the decoded pixels.
     *
     * <p>Equality compares the PNG bytes by content, so a repaint that rebuilds the same
     * image keeps the rendered component instead of decoding the PNG again.</p>
     */
    record Image(byte[] pngBytes, String altText) implements PanelView {
        public Image {
            pngBytes = checkedPngBytes(pngBytes);
            altText = Objects.requireNonNull(altText, "altText");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof Image image
                    && altText.equals(image.altText)
                    && Arrays.equals(pngBytes, image.pngBytes);
        }

        @Override
        public int hashCode() {
            return 31 * altText.hashCode() + Arrays.hashCode(pngBytes);
        }

        @Override
        public String toString() {
            return "Image[pngBytes=" + pngBytes.length + " bytes, altText=" + altText + "]";
        }
    }

    /**
     * High-resolution PNG image node with explicit logical display bounds. The runtime
     * fits the image into the bounds while preserving aspect ratio, and supplies the
     * original pixels to HiDPI painting without increasing the panel's logical size.
     *
     * <p>Equality compares the PNG bytes by content, so a repaint that rebuilds the same
     * image keeps the rendered component instead of decoding and rescaling the PNG again.</p>
     */
    record ScaledImage(byte[] pngBytes, String altText, int maxWidth, int maxHeight) implements PanelView {
        private static final int MAX_DISPLAY_DIMENSION = 4096;

        public ScaledImage {
            pngBytes = checkedPngBytes(pngBytes);
            altText = Objects.requireNonNull(altText, "altText");
            requireDisplayDimension(maxWidth, "maxWidth");
            requireDisplayDimension(maxHeight, "maxHeight");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }

        @Override
        public boolean equals(final Object other) {
            return other instanceof ScaledImage image
                    && maxWidth == image.maxWidth
                    && maxHeight == image.maxHeight
                    && altText.equals(image.altText)
                    && Arrays.equals(pngBytes, image.pngBytes);
        }

        @Override
        public int hashCode() {
            return 31 * (31 * (31 * altText.hashCode() + Arrays.hashCode(pngBytes)) + maxWidth) + maxHeight;
        }

        @Override
        public String toString() {
            return "ScaledImage[pngBytes=" + pngBytes.length + " bytes, altText=" + altText + ", maxWidth=" + maxWidth
                    + ", maxHeight=" + maxHeight + "]";
        }

        private static void requireDisplayDimension(final int value, final String name) {
            if (value < 1 || value > MAX_DISPLAY_DIMENSION) {
                throw new IllegalArgumentException(name + " must be between 1 and " + MAX_DISPLAY_DIMENSION);
            }
        }
    }

    /** Push button that invokes {@code actionId} when pressed. */
    record Button(String id, String label, String actionId) implements PanelView {
        public Button {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
        }
    }

    /** Empty inset around a child. */
    record Padded(PanelView child, int top, int left, int bottom, int right) implements PanelView {
        public Padded {
            child = Objects.requireNonNull(child, "child");
            requireInset(top, "top");
            requireInset(left, "left");
            requireInset(bottom, "bottom");
            requireInset(right, "right");
        }

        private static void requireInset(final int value, final String name) {
            if (value < 0) {
                throw new IllegalArgumentException(name + " must be non-negative");
            }
        }
    }

    /** Icon-only button; {@code pngBytes} is an alpha mask tinted by the runtime theme. */
    record IconButton(String id, byte[] pngBytes, String tooltip, String actionId) implements PanelView {
        public IconButton {
            id = requireText(id, "id");
            pngBytes = checkedPngBytes(pngBytes);
            tooltip = requireText(tooltip, "tooltip");
            actionId = requireText(actionId, "actionId");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    /**
     * Icon-only button that keeps the PNG colours; a disabled control rejects input.
     *
     * @param id component name used for routing and observation
     * @param pngBytes coloured glyph, drawn at the runtime icon size
     * @param tooltip localized description of the action
     * @param actionId action dispatched on click
     * @param enabled whether the control accepts input
     */
    record ColorIconButton(String id, byte[] pngBytes, String tooltip, String actionId, boolean enabled)
            implements PanelView {
        public ColorIconButton {
            id = requireText(id, "id");
            pngBytes = checkedPngBytes(pngBytes);
            tooltip = requireText(tooltip, "tooltip");
            actionId = requireText(actionId, "actionId");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }
    }

    /** Labeled text field whose edits dispatch {@code actionId}. */
    record TextInput(String id, String label, String value, String actionId) implements PanelView {
        public TextInput {
            id = requireText(id, "id");
            label = requireText(label, "label");
            value = Objects.requireNonNull(value, "value");
            actionId = requireText(actionId, "actionId");
        }
    }

    /** One selectable option of a {@link Select} control. */
    record Option(String value, String label) {
        public Option {
            value = requireText(value, "value");
            label = requireText(label, "label");
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** Labeled single-select dropdown; {@code selectedValue} must identify one option. */
    record Select(String id, String label, List<Option> options, String selectedValue, String actionId)
            implements PanelView {
        public Select {
            id = requireText(id, "id");
            label = requireText(label, "label");
            options = List.copyOf(Objects.requireNonNull(options, "options"));
            if (options.isEmpty()) {
                throw new IllegalArgumentException("options must not be empty");
            }
            final HashSet<String> values = new HashSet<>();
            for (Option option : options) {
                Objects.requireNonNull(option, "option");
                if (!values.add(option.value())) {
                    throw new IllegalArgumentException("option values must be unique");
                }
            }
            selectedValue = requireText(selectedValue, "selectedValue");
            if (!values.contains(selectedValue)) {
                throw new IllegalArgumentException("selectedValue must identify an option");
            }
            actionId = requireText(actionId, "actionId");
        }
    }

    /** Checkbox node; changes dispatch {@code actionId}. */
    record Toggle(String id, String label, boolean selected, boolean grayed, String actionId, UiInlineLabel inlineLabel)
            implements PanelView {
        public Toggle {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
            if (inlineLabel != null && !label.equals(inlineLabel.fallbackText())) {
                throw new IllegalArgumentException("label must equal inlineLabel.fallbackText()");
            }
        }

        /** Backwards-compatible construction for callers without a grayed flag. */
        public Toggle(final String id, final String label, final boolean selected, final String actionId) {
            this(id, label, selected, false, actionId, null);
        }

        /** Backwards-compatible construction for callers with a grayed flag. */
        public Toggle(
                final String id,
                final String label,
                final boolean selected,
                final boolean grayed,
                final String actionId) {
            this(id, label, selected, grayed, actionId, null);
        }

        /** Constructs an icon-capable toggle without requiring a duplicate fallback string. */
        public Toggle(final String id, final UiInlineLabel inlineLabel, final boolean selected, final String actionId) {
            this(id, requireInlineLabel(inlineLabel).fallbackText(), selected, false, actionId, inlineLabel);
        }

        /** Constructs an icon-capable toggle with an independent gray presentation flag. */
        public Toggle(
                final String id,
                final UiInlineLabel inlineLabel,
                final boolean selected,
                final boolean grayed,
                final String actionId) {
            this(id, requireInlineLabel(inlineLabel).fallbackText(), selected, grayed, actionId, inlineLabel);
        }

        private static UiInlineLabel requireInlineLabel(final UiInlineLabel value) {
            return Objects.requireNonNull(value, "inlineLabel");
        }
    }

    /** Titled collapsible section containing child nodes. */
    record CollapsibleSection(String title, boolean expandedByDefault, List<PanelView> children) implements PanelView {
        public CollapsibleSection {
            title = requireText(title, "title");
            children = immutableChildren(children);
        }
    }

    /** Horizontal separator line. */
    record Separator() implements PanelView {}

    /** Scrollable viewport around one child node. */
    record Scroll(PanelView child) implements PanelView {
        public Scroll {
            child = Objects.requireNonNull(child, "child");
        }
    }

    /**
     * Double-clickable card around arbitrary panel content.
     *
     * <p>The rendered card is a bordered container; a left-button double-click
     * dispatches {@code actionId} with a {@link dev.turboism.sdk.action.UiActionEvent}
     * whose source id is {@code id}. Single clicks are presentation-only.</p>
     */
    record Card(String id, String actionId, PanelView content, boolean bordered) implements PanelView {
        public Card {
            id = requireText(id, "id");
            actionId = requireText(actionId, "actionId");
            content = Objects.requireNonNull(content, "content");
        }

        /** Creates a bordered card; the details-view equivalent uses {@link #flatCard}. */
        public Card(final String id, final String actionId, final PanelView content) {
            this(id, actionId, content, true);
        }
    }

    /** Fixed-column grid of child nodes; columns must be between 1 and 12. */
    record Grid(int columns, List<PanelView> children) implements PanelView {
        public Grid {
            if (columns < 1 || columns > 12) {
                throw new IllegalArgumentException("columns must be between 1 and 12");
            }
            children = immutableChildren(children);
        }
    }

    /** Centers one child block in the available width and height. */
    /**
     * Centers one child block inside the available space.
     *
     * <p>A zero dimension keeps the child's natural size in that direction, so a caller can pin the
     * width of a text block and let its height follow the content.</p>
     */
    record Centered(PanelView child, int preferredWidth, int preferredHeight) implements PanelView {
        public Centered {
            child = Objects.requireNonNull(child, "child");
            if (preferredWidth < 0 || preferredHeight < 0) {
                throw new IllegalArgumentException("preferred dimensions must be non-negative");
            }
        }

        /** Centers the child at its natural preferred size. */
        public Centered(final PanelView child) {
            this(child, 0, 0);
        }
    }

    /** Blue underlined link that dispatches {@code actionId} on a left click. */
    record Link(String id, String label, String actionId) implements PanelView {
        public Link {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
        }
    }

    /** Left/right row with the two blocks pinned to the start and end of the available width. */
    record SpaceBetween(PanelView left, PanelView right) implements PanelView {
        public SpaceBetween {
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }
    }

    /** Row with a fixed-leading block and a trailing block that fills the remaining width. */
    record LeftRow(PanelView left, PanelView right) implements PanelView {
        public LeftRow {
            left = Objects.requireNonNull(left, "left");
            right = Objects.requireNonNull(right, "right");
        }
    }

    /** Real-time line chart whose live data is resolved by the runtime through {@code id}. */
    record Chart(String id, String title, List<SeriesSpec> series) implements PanelView {
        public Chart {
            id = requireText(id, "id");
            title = requireText(title, "title");
            series = List.copyOf(Objects.requireNonNull(series, "series"));
            if (series.isEmpty()) {
                throw new IllegalArgumentException("series must not be empty");
            }
            final HashSet<String> names = new HashSet<>();
            for (SeriesSpec spec : series) {
                Objects.requireNonNull(spec, "series entry");
                if (!names.add(spec.name())) {
                    throw new IllegalArgumentException("series names must be unique");
                }
            }
        }
    }

    /** Display specification of one chart series: name, window size, unit, and value format. */
    record SeriesSpec(String name, int maxPoints, String unit, String format) {
        public SeriesSpec {
            name = requireText(name, "name");
            if (maxPoints < 2) {
                throw new IllegalArgumentException("maxPoints must be at least 2");
            }
            unit = Objects.requireNonNull(unit, "unit");
            format = Objects.requireNonNull(format, "format");
        }
    }

    private static List<PanelView> immutableChildren(final List<PanelView> children) {
        final List<PanelView> snapshot = List.copyOf(Objects.requireNonNull(children, "children"));
        if (snapshot.isEmpty()) {
            throw new IllegalArgumentException("children must not be empty");
        }
        return snapshot;
    }

    private static byte[] checkedPngBytes(final byte[] value) {
        final byte[] pngBytes = Objects.requireNonNull(value, "pngBytes").clone();
        if (pngBytes.length == 0 || pngBytes.length > 1024 * 1024) {
            throw new IllegalArgumentException("pngBytes must contain between 1 and 1048576 bytes");
        }
        final byte[] signature = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};
        if (pngBytes.length < signature.length) {
            throw new IllegalArgumentException("pngBytes must start with the PNG signature");
        }
        for (int index = 0; index < signature.length; index++) {
            if (pngBytes[index] != signature[index]) {
                throw new IllegalArgumentException("pngBytes must start with the PNG signature");
            }
        }
        try {
            final var decoded = ImageIO.read(new ByteArrayInputStream(pngBytes));
            if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                throw new IllegalArgumentException("pngBytes must be a readable PNG");
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("pngBytes must be a readable PNG", failure);
        }
        return pngBytes;
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
