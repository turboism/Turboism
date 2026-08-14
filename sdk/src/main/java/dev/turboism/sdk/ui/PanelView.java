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

    static Column column(final PanelView... children) {
        return new Column(List.of(children));
    }

    static Row row(final PanelView... children) {
        return new Row(List.of(children));
    }

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

    static Image image(final byte[] pngBytes, final String altText) {
        return new Image(pngBytes, altText);
    }

    static Button button(final String id, final String label, final String actionId) {
        return new Button(id, label, actionId);
    }

    static TextInput textInput(
        final String id,
        final String label,
        final String value,
        final String actionId
    ) {
        return new TextInput(id, label, value, actionId);
    }

    static Option option(final String value, final String label) {
        return new Option(value, label);
    }

    static Select select(
        final String id,
        final String label,
        final List<Option> options,
        final String selectedValue,
        final String actionId
    ) {
        return new Select(id, label, options, selectedValue, actionId);
    }

    static Toggle toggle(
        final String id,
        final String label,
        final boolean selected,
        final String actionId
    ) {
        return new Toggle(id, label, selected, false, actionId);
    }

    static Toggle toggle(
        final String id,
        final String label,
        final boolean selected,
        final boolean grayed,
        final String actionId
    ) {
        return new Toggle(id, label, selected, grayed, actionId);
    }

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

    static SeriesSpec series(final String name, final int maxPoints, final String unit, final String format) {
        return new SeriesSpec(name, maxPoints, unit, format);
    }

    static CollapsibleSection collapsibleSection(
        final String title,
        final boolean expandedByDefault,
        final PanelView... children
    ) {
        return new CollapsibleSection(title, expandedByDefault, List.of(children));
    }

    record Column(List<PanelView> children) implements PanelView {
        public Column {
            children = immutableChildren(children);
        }
    }

    record Row(List<PanelView> children) implements PanelView {
        public Row {
            children = immutableChildren(children);
        }
    }

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

    record Button(String id, String label, String actionId) implements PanelView {
        public Button {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
        }
    }

    record TextInput(String id, String label, String value, String actionId) implements PanelView {
        public TextInput {
            id = requireText(id, "id");
            label = requireText(label, "label");
            value = Objects.requireNonNull(value, "value");
            actionId = requireText(actionId, "actionId");
        }
    }

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

    record Separator() implements PanelView { }

    record Scroll(PanelView child) implements PanelView {
        public Scroll {
            child = Objects.requireNonNull(child, "child");
        }
    }

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
