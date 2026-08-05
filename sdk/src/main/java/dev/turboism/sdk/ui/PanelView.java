package dev.turboism.sdk.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/** Immutable, toolkit-neutral panel content rendered and owned by the runtime. */
public sealed interface PanelView permits
    PanelView.Column,
    PanelView.Row,
    PanelView.Text,
    PanelView.Button,
    PanelView.TextInput,
    PanelView.Select,
    PanelView.Toggle,
    PanelView.CollapsibleSection,
    PanelView.Separator {

    static Column column(final PanelView... children) {
        return new Column(List.of(children));
    }

    static Row row(final PanelView... children) {
        return new Row(List.of(children));
    }

    static Text text(final String value) {
        return new Text(value);
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
        return new Toggle(id, label, selected, actionId);
    }

    static Separator separator() {
        return new Separator();
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

    record Text(String value) implements PanelView {
        public Text {
            value = Objects.requireNonNull(value, "value");
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

    record Toggle(String id, String label, boolean selected, String actionId) implements PanelView {
        public Toggle {
            id = requireText(id, "id");
            label = requireText(label, "label");
            actionId = requireText(actionId, "actionId");
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
