package dev.turboism.sdk.ui;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
                PanelView.Scroll {

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

    /** Text rendered centered in its region, e.g. a panel header statistic. */
    static Text textCentered(final String value) {
        return new Text(value, false, true);
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
    record Text(String value, boolean grayed, boolean centered) implements PanelView {
        public Text {
            value = Objects.requireNonNull(value, "value");
        }

        public Text(final String value, final boolean grayed) {
            this(value, grayed, false);
        }

        public Text(final String value) {
            this(value, false, false);
        }
    }

    /**
     * PNG image node (for example a recent-file preview thumbnail) with accessibility
     * alt text. The runtime renders it as an image label sized to the decoded pixels.
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
    }

    /**
     * High-resolution PNG image node with explicit logical display bounds. The runtime
     * fits the image into the bounds while preserving aspect ratio, and supplies the
     * original pixels to HiDPI painting without increasing the panel's logical size.
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
