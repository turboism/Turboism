package dev.turboism.sdk.ui;

import javax.imageio.ImageIO;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, toolkit-neutral panel content rendered and owned by the runtime. */
public sealed interface PanelView permits
    PanelView.Column,
    PanelView.Row,
    PanelView.Text,
    PanelView.Image,
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

    /** Creates a push button that invokes {@code actionId} when pressed. */
    static Button button(final String id, final String label, final String actionId) {
        return new Button(id, label, actionId);
    }

    /** Creates a labeled text field whose edits dispatch {@code actionId}. */
    static TextInput textInput(
        final String id,
        final String label,
        final String value,
        final String actionId
    ) {
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
        final String actionId
    ) {
        return new Select(id, label, options, selectedValue, actionId);
    }

    /** Creates a labeled toggle whose changes dispatch {@code actionId}. */
    static Toggle toggle(
        final String id,
        final String label,
        final boolean selected,
        final String actionId
    ) {
        return new Toggle(id, label, selected, false, actionId);
    }

    /** Creates a labeled toggle with an explicit grayed (disabled-looking) style. */
    static Toggle toggle(
        final String id,
        final String label,
        final boolean selected,
        final boolean grayed,
        final String actionId
    ) {
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
        final String title,
        final boolean expandedByDefault,
        final PanelView... children
    ) {
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
        private static final int MAX_PNG_BYTES = 1024 * 1024;
        private static final byte[] PNG_SIGNATURE = {(byte) 137, 80, 78, 71, 13, 10, 26, 10};

        public Image {
            pngBytes = Objects.requireNonNull(pngBytes, "pngBytes").clone();
            if (pngBytes.length == 0 || pngBytes.length > MAX_PNG_BYTES) {
                throw new IllegalArgumentException("pngBytes must contain between 1 and 1048576 bytes");
            }
            if (!startsWithPngSignature(pngBytes)) {
                throw new IllegalArgumentException("pngBytes must start with the PNG signature");
            }
            try {
                final var decoded = ImageIO.read(new ByteArrayInputStream(pngBytes));
                if (decoded == null || decoded.getWidth() < 1 || decoded.getHeight() < 1) {
                    throw new IllegalArgumentException("pngBytes must be a readable PNG");
                }
            } catch (IOException failure) {
                throw new IllegalArgumentException("pngBytes must be a readable PNG", failure);
            }
            altText = Objects.requireNonNull(altText, "altText");
        }

        @Override
        public byte[] pngBytes() {
            return pngBytes.clone();
        }

        private static boolean startsWithPngSignature(final byte[] value) {
            if (value.length < PNG_SIGNATURE.length) return false;
            for (int index = 0; index < PNG_SIGNATURE.length; index++) {
                if (value[index] != PNG_SIGNATURE[index]) return false;
            }
            return true;
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
    record Select(
        String id,
        String label,
        List<Option> options,
        String selectedValue,
        String actionId
    ) implements PanelView {
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
    record Toggle(
        String id,
        String label,
        boolean selected,
        boolean grayed,
        String actionId
    ) implements PanelView {
        public Toggle {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
        }

        /** Backwards-compatible construction for callers without a grayed flag. */
        public Toggle(final String id, final String label, final boolean selected, final String actionId) {
            this(id, label, selected, false, actionId);
        }
    }

    /** Titled collapsible section containing child nodes. */
    record CollapsibleSection(
        String title,
        boolean expandedByDefault,
        List<PanelView> children
    ) implements PanelView {
        public CollapsibleSection {
            title = requireText(title, "title");
            children = immutableChildren(children);
        }
    }

    /** Horizontal separator line. */
    record Separator() implements PanelView { }

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

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
