package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Manual-test-only SDK plugin packaged into the isolated Windows validation drop. */
public final class WindowsParameterValidationProbe implements CubismPlugin {

    enum SearchMode {
        CONTAINS,
        EXACT_ID,
        EXACT_NAME
    }

    enum TypeFilter {
        ANY,
        NORMAL,
        BLEND_SHAPE,
        UNKNOWN
    }

    enum BooleanFilter {
        ANY,
        YES,
        NO,
        UNKNOWN
    }

    record QuerySpec(
        String text,
        SearchMode searchMode,
        TypeFilter type,
        BooleanFilter repeat,
        BooleanFilter combined
    ) {
        QuerySpec {
            text = Objects.requireNonNull(text, "text");
            searchMode = Objects.requireNonNull(searchMode, "searchMode");
            type = Objects.requireNonNull(type, "type");
            repeat = Objects.requireNonNull(repeat, "repeat");
            combined = Objects.requireNonNull(combined, "combined");
        }
    }

    record ParameterRow(
        ParameterId id,
        Optional<String> name,
        ParameterType type,
        Optional<Boolean> repeat,
        Optional<Boolean> combined,
        float value,
        float minimum,
        float maximum,
        float defaultValue
    ) {
        ParameterRow {
            id = Objects.requireNonNull(id, "id");
            name = Objects.requireNonNull(name, "name");
            type = Objects.requireNonNull(type, "type");
            repeat = Objects.requireNonNull(repeat, "repeat");
            combined = Objects.requireNonNull(combined, "combined");
        }

        boolean blendShape() {
            return type == ParameterType.BLEND_SHAPE;
        }

        String displayName() {
            return name.orElse(id.value());
        }

        @Override
        public String toString() {
            return name.filter(value -> !value.equals(id.value()))
                .map(value -> id.value() + "  —  " + value)
                .orElse(id.value());
        }
    }

    static List<ParameterRow> queryRows(final Parameters parameters, final QuerySpec spec) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(spec, "spec");
        final String text = spec.text().strip();
        final List<Parameter> textMatches = text.isEmpty()
            ? parameters.all()
            : switch (spec.searchMode()) {
                case CONTAINS -> parameters.search(text);
                case EXACT_ID -> parameters.findById(text).stream().toList();
                case EXACT_NAME -> parameters.findByName(text);
            };
        return textMatches.stream()
            .filter(parameter -> matchesType(parameter.type(), spec.type()))
            .filter(parameter -> matchesBoolean(parameter.repeat(), spec.repeat()))
            .filter(parameter -> matchesBoolean(parameter.combined(), spec.combined()))
            .map(WindowsParameterValidationProbe::row)
            .toList();
    }

    static Optional<ParameterId> preferredSelection(
        final List<ParameterRow> rows,
        final Optional<ParameterId> current,
        final Optional<ParameterId> cubismSelection,
        final boolean followCubismSelection
    ) {
        Objects.requireNonNull(rows, "rows");
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(cubismSelection, "cubismSelection");
        final java.util.function.Predicate<ParameterId> visible = id ->
            rows.stream().anyMatch(row -> row.id().equals(id));
        final Optional<ParameterId> visibleCurrent = current.filter(visible);
        final Optional<ParameterId> preferred = followCubismSelection
            ? cubismSelection.filter(visible).or(() -> visibleCurrent)
            : visibleCurrent;
        return preferred.or(() -> rows.stream().findFirst().map(ParameterRow::id));
    }

    private static ParameterRow row(final Parameter parameter) {
        return new ParameterRow(
            parameter.id(),
            parameter.name(),
            parameter.type(),
            parameter.repeat(),
            parameter.combined(),
            parameter.getValue(),
            parameter.getMinimumValue(),
            parameter.getMaximumValue(),
            parameter.getDefaultValue()
        );
    }

    private static boolean matchesType(final ParameterType type, final TypeFilter filter) {
        return switch (filter) {
            case ANY -> true;
            case NORMAL -> type == ParameterType.NORMAL;
            case BLEND_SHAPE -> type == ParameterType.BLEND_SHAPE;
            case UNKNOWN -> type == ParameterType.UNKNOWN;
        };
    }

    private static boolean matchesBoolean(
        final Optional<Boolean> value,
        final BooleanFilter filter
    ) {
        return switch (filter) {
            case ANY -> true;
            case YES -> value.filter(Boolean::booleanValue).isPresent();
            case NO -> value.filter(item -> !item).isPresent();
            case UNKNOWN -> value.isEmpty();
        };
    }

    private final AtomicInteger beforeCount = new AtomicInteger();
    private final AtomicInteger changedCount = new AtomicInteger();
    private final AtomicInteger afterCount = new AtomicInteger();
    private volatile String lastLifecycle = "none";
    private volatile String lastActionStatus = "Ready";
    private PluginContext context;
    private JFrame frame;
    private JTextField searchField;
    private JComboBox<SearchMode> searchModeBox;
    private JComboBox<TypeFilter> typeFilterBox;
    private JComboBox<BooleanFilter> repeatFilterBox;
    private JComboBox<BooleanFilter> combinedFilterBox;
    private JCheckBox followSelectionBox;
    private DefaultListModel<ParameterRow> resultModel;
    private JList<ParameterRow> resultList;
    private JLabel resultCountLabel;
    private JLabel idValueLabel;
    private JLabel nameValueLabel;
    private JLabel typeValueLabel;
    private JLabel blendShapeValueLabel;
    private JLabel combinedValueLabel;
    private JLabel repeatValueLabel;
    private JLabel currentValueLabel;
    private JLabel rangeValueLabel;
    private JLabel defaultValueLabel;
    private JTextField setterField;
    private JTextField definitionIdField;
    private JTextField definitionNameField;
    private JTextField definitionMinimumField;
    private JTextField definitionDefaultField;
    private JTextField definitionMaximumField;
    private JComboBox<ParameterType> definitionTypeBox;
    private JCheckBox definitionRepeatBox;
    private JLabel definitionCombinedLabel;
    private JComboBox<String> combinedPartnerBox;
    private JLabel combinedPartnerValueLabel;
    private JComboBox<String> parameterGroupBox;
    private JTextField labelRedField;
    private JTextField labelGreenField;
    private JTextField labelBlueField;
    private JTextField labelAlphaField;
    private JLabel currentLabelColorLabel;
    private JLabel defaultKeyformLockLabel;
    private JLabel countsLabel;
    private JLabel lifecycleLabel;
    private JLabel statusLabel;
    private Optional<ParameterId> selectedParameterId = Optional.empty();
    private boolean applyingSelection;
    private Thread partValidationThread;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.disposableScope().register(this::disposeWindow);
        context.logger().info("Windows parameter validation probe initialized");
    }

    @Override
    public void enable() {
        partValidationThread = new Thread(() -> {
            runPartOpacityValidation();
            SwingUtilities.invokeLater(this::showWindow);
        }, "turboism-part-opacity-validation");
        partValidationThread.setDaemon(true);
        partValidationThread.start();
    }

    @Override
    public void disable() {
        if (partValidationThread != null) partValidationThread.interrupt();
        disposeWindow();
    }

    @Override
    public void shutdown() {
        disposeWindow();
    }

    @Override
    public float beforeSetParameterValue(final Parameter parameter, final float value) {
        beforeCount.incrementAndGet();
        lastLifecycle = "before " + parameter.id().value() + " requested=" + value;
        refreshLater();
        return value;
    }

    @Override
    public void onParameterValueChanged(
        final Parameter parameter,
        final float oldValue,
        final float newValue
    ) {
        changedCount.incrementAndGet();
        lastLifecycle = "on " + parameter.id().value() + " " + oldValue + " -> " + newValue;
        refreshLater();
    }

    @Override
    public void afterSetParameterValue(final Parameter parameter, final float value) {
        afterCount.incrementAndGet();
        lastLifecycle = "after " + parameter.id().value() + " final=" + value;
        refreshLater();
    }

    private void showWindow() {
        if (frame != null) {
            frame.setVisible(true);
            frame.toFront();
            refresh();
            return;
        }
        frame = new JFrame("Turboism Parameter Validation");
        frame.setDefaultCloseOperation(WindowConstants.HIDE_ON_CLOSE);
        frame.setLayout(new BorderLayout(10, 10));
        frame.getRootPane().setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        frame.add(createQueryPanel(), BorderLayout.NORTH);

        final JSplitPane content = new JSplitPane(
            JSplitPane.HORIZONTAL_SPLIT,
            createResultPanel(),
            createDetailsPanel()
        );
        content.setResizeWeight(0.42);
        content.setContinuousLayout(true);
        frame.add(content, BorderLayout.CENTER);
        frame.add(createStatusPanel(), BorderLayout.SOUTH);

        frame.setSize(1180, 780);
        frame.setMinimumSize(new java.awt.Dimension(980, 680));
        frame.setLocationByPlatform(true);
        frame.setVisible(true);
        content.setDividerLocation(0.42);
        refresh();
    }

    private JPanel createQueryPanel() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder("Parameter query"));
        searchField = new JTextField(24);
        searchModeBox = new JComboBox<>(SearchMode.values());
        typeFilterBox = new JComboBox<>(TypeFilter.values());
        repeatFilterBox = new JComboBox<>(BooleanFilter.values());
        combinedFilterBox = new JComboBox<>(BooleanFilter.values());
        followSelectionBox = new JCheckBox(
            "Follow Cubism selection (current adapter: unavailable)",
            false
        );
        followSelectionBox.setEnabled(false);
        followSelectionBox.setToolTipText(
            "The production 5.3.02 adapter does not yet expose parameter-palette selection."
        );

        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        addQueryField(panel, constraints, "Search", searchField, 0, 0.35);
        addQueryField(panel, constraints, "Mode", searchModeBox, 2, 0.0);
        addQueryField(panel, constraints, "Type", typeFilterBox, 4, 0.0);
        addQueryField(panel, constraints, "Repeat", repeatFilterBox, 6, 0.0);
        addQueryField(panel, constraints, "Combined", combinedFilterBox, 8, 0.0);

        constraints.gridx = 0;
        constraints.gridy = 1;
        constraints.gridwidth = 3;
        constraints.weightx = 0.0;
        panel.add(followSelectionBox, constraints);
        constraints.gridx = 8;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.EAST;
        panel.add(button("Refresh", this::refresh), constraints);

        final DocumentListener listener = new DocumentListener() {
            @Override public void insertUpdate(final DocumentEvent event) { refresh(); }
            @Override public void removeUpdate(final DocumentEvent event) { refresh(); }
            @Override public void changedUpdate(final DocumentEvent event) { refresh(); }
        };
        searchField.getDocument().addDocumentListener(listener);
        searchModeBox.addActionListener(ignored -> refresh());
        typeFilterBox.addActionListener(ignored -> refresh());
        repeatFilterBox.addActionListener(ignored -> refresh());
        combinedFilterBox.addActionListener(ignored -> refresh());
        followSelectionBox.addActionListener(ignored -> refresh());
        return panel;
    }

    private void addQueryField(
        final JPanel panel,
        final GridBagConstraints constraints,
        final String label,
        final java.awt.Component component,
        final int column,
        final double weight
    ) {
        constraints.gridx = column;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        constraints.fill = GridBagConstraints.NONE;
        panel.add(new JLabel(label + ':'), constraints);
        constraints.gridx = column + 1;
        constraints.weightx = weight;
        constraints.fill = weight > 0.0
            ? GridBagConstraints.HORIZONTAL
            : GridBagConstraints.NONE;
        panel.add(component, constraints);
    }

    private JPanel createResultPanel() {
        final JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("Filtered parameters"));
        resultModel = new DefaultListModel<>();
        resultList = new JList<>(resultModel);
        resultList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        resultList.addListSelectionListener(event -> {
            if (event.getValueIsAdjusting() || applyingSelection) {
                return;
            }
            selectedParameterId = Optional.ofNullable(resultList.getSelectedValue())
                .map(ParameterRow::id);
            updateDetailsFromSelection();
        });
        resultCountLabel = new JLabel("0 parameter(s)");
        panel.add(new JScrollPane(resultList), BorderLayout.CENTER);
        panel.add(resultCountLabel, BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createDetailsPanel() {
        final JPanel panel = new JPanel(new BorderLayout(8, 8));
        panel.setBorder(BorderFactory.createTitledBorder("Test-window selection"));
        final JPanel metadata = new JPanel(new GridLayout(0, 2, 8, 7));
        idValueLabel = addDetail(metadata, "ID");
        nameValueLabel = addDetail(metadata, "Name");
        typeValueLabel = addDetail(metadata, "Type");
        blendShapeValueLabel = addDetail(metadata, "Blend shape");
        combinedValueLabel = addDetail(metadata, "Combined");
        repeatValueLabel = addDetail(metadata, "Repeat");
        currentValueLabel = addDetail(metadata, "Current value");
        rangeValueLabel = addDetail(metadata, "Range");
        defaultValueLabel = addDetail(metadata, "Default value");
        final JPanel north = new JPanel(new BorderLayout(6, 6));
        north.add(metadata, BorderLayout.NORTH);
        north.add(createModelAuthoringPanel(), BorderLayout.SOUTH);
        panel.add(north, BorderLayout.NORTH);

        final JPanel setter = new JPanel(new BorderLayout(6, 6));
        setter.setBorder(BorderFactory.createTitledBorder("Runtime value setter"));
        final JPanel input = new JPanel(new BorderLayout(6, 0));
        setterField = new JTextField();
        input.add(new JLabel("New finite float:"), BorderLayout.WEST);
        input.add(setterField, BorderLayout.CENTER);
        input.add(button("Set value", this::writeInput), BorderLayout.EAST);
        setter.add(input, BorderLayout.NORTH);
        final JPanel quick = new JPanel(new GridLayout(1, 0, 6, 0));
        quick.add(button("Set minimum", () -> writeSelectedBound(Bound.MINIMUM)));
        quick.add(button("Set default", () -> writeSelectedBound(Bound.DEFAULT)));
        quick.add(button("Set maximum", () -> writeSelectedBound(Bound.MAXIMUM)));
        quick.add(button("Set same", this::writeCurrent));
        setter.add(quick, BorderLayout.SOUTH);
        panel.add(setter, BorderLayout.CENTER);
        panel.add(createDefinitionEditor(), BorderLayout.SOUTH);
        return panel;
    }

    private JPanel createModelAuthoringPanel() {
        final JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
            "Model and parameter-folder authoring"
        ));
        parameterGroupBox = new JComboBox<>();
        labelRedField = new JTextField("0.25", 5);
        labelGreenField = new JTextField("0.50", 5);
        labelBlueField = new JTextField("0.75", 5);
        labelAlphaField = new JTextField("1.00", 5);
        currentLabelColorLabel = new JLabel("Current color: —");
        defaultKeyformLockLabel = new JLabel("Default keyform locked: —");

        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.gridy = 0;
        constraints.anchor = GridBagConstraints.WEST;
        addAuthoringField(panel, constraints, "Group", parameterGroupBox, 0);
        addAuthoringField(panel, constraints, "R", labelRedField, 2);
        addAuthoringField(panel, constraints, "G", labelGreenField, 4);
        addAuthoringField(panel, constraints, "B", labelBlueField, 6);
        addAuthoringField(panel, constraints, "A", labelAlphaField, 8);
        constraints.gridx = 10;
        panel.add(button("Set label color", this::writeLabelColor), constraints);

        constraints.gridy = 1;
        constraints.gridx = 0;
        constraints.gridwidth = 5;
        panel.add(currentLabelColorLabel, constraints);
        constraints.gridx = 5;
        constraints.gridwidth = 3;
        panel.add(defaultKeyformLockLabel, constraints);
        constraints.gridx = 8;
        constraints.gridwidth = 1;
        panel.add(button("Lock default", () -> writeDefaultKeyformLock(true)), constraints);
        constraints.gridx = 9;
        constraints.gridwidth = 2;
        panel.add(button("Unlock default", () -> writeDefaultKeyformLock(false)), constraints);
        return panel;
    }

    private void addAuthoringField(
        final JPanel panel,
        final GridBagConstraints constraints,
        final String label,
        final java.awt.Component component,
        final int column
    ) {
        constraints.gridx = column;
        constraints.gridwidth = 1;
        panel.add(new JLabel(label + ':'), constraints);
        constraints.gridx = column + 1;
        panel.add(component, constraints);
    }

    private JPanel createDefinitionEditor() {
        final JPanel editor = new JPanel(new GridBagLayout());
        editor.setBorder(BorderFactory.createTitledBorder(
            "Authoring definition editor (one native Undo transaction)"
        ));
        definitionIdField = new JTextField(18);
        definitionNameField = new JTextField(18);
        definitionMinimumField = new JTextField(8);
        definitionDefaultField = new JTextField(8);
        definitionMaximumField = new JTextField(8);
        definitionTypeBox = new JComboBox<>(new ParameterType[] {
            ParameterType.NORMAL,
            ParameterType.BLEND_SHAPE
        });
        definitionRepeatBox = new JCheckBox("Repeat");
        definitionCombinedLabel = new JLabel("Combined marker: —");
        definitionCombinedLabel.setToolTipText(
            "The first parameter of a four-corner pair carries the Editor Combined marker."
        );

        final GridBagConstraints constraints = new GridBagConstraints();
        constraints.insets = new Insets(3, 4, 3, 4);
        constraints.anchor = GridBagConstraints.WEST;
        constraints.fill = GridBagConstraints.HORIZONTAL;
        constraints.weightx = 0.0;
        addEditorField(editor, constraints, "ID", definitionIdField, 0, 0);
        addEditorField(editor, constraints, "Name", definitionNameField, 2, 0);
        addEditorField(editor, constraints, "Minimum", definitionMinimumField, 0, 1);
        addEditorField(editor, constraints, "Default", definitionDefaultField, 2, 1);
        addEditorField(editor, constraints, "Maximum", definitionMaximumField, 4, 1);
        addEditorField(editor, constraints, "Type", definitionTypeBox, 0, 2);
        constraints.gridx = 2;
        constraints.gridy = 2;
        constraints.gridwidth = 1;
        editor.add(definitionRepeatBox, constraints);
        constraints.gridx = 3;
        constraints.gridwidth = 2;
        editor.add(definitionCombinedLabel, constraints);
        constraints.gridx = 5;
        constraints.gridwidth = 1;
        constraints.anchor = GridBagConstraints.EAST;
        editor.add(button("Apply definition", this::writeDefinition), constraints);

        combinedPartnerBox = new JComboBox<>();
        combinedPartnerBox.setEditable(true);
        combinedPartnerBox.setPrototypeDisplayValue("ParamExampleLongIdentifier");
        combinedPartnerValueLabel = new JLabel("Current partner: —");
        addEditorField(editor, constraints, "Partner (choose or type ID)", combinedPartnerBox, 0, 3);
        constraints.gridx = 2;
        constraints.gridy = 3;
        constraints.gridwidth = 2;
        constraints.anchor = GridBagConstraints.WEST;
        editor.add(combinedPartnerValueLabel, constraints);
        constraints.gridx = 4;
        constraints.gridwidth = 1;
        editor.add(button("Combine", this::writeCombined), constraints);
        constraints.gridx = 5;
        editor.add(button("Uncombine", this::writeUncombined), constraints);
        return editor;
    }

    private void addEditorField(
        final JPanel panel,
        final GridBagConstraints constraints,
        final String label,
        final java.awt.Component component,
        final int column,
        final int row
    ) {
        constraints.gridx = column;
        constraints.gridy = row;
        constraints.gridwidth = 1;
        constraints.weightx = 0.0;
        panel.add(new JLabel(label + ':'), constraints);
        constraints.gridx = column + 1;
        constraints.weightx = 1.0;
        panel.add(component, constraints);
    }

    private JLabel addDetail(final JPanel panel, final String label) {
        final JLabel heading = new JLabel(label + ':');
        heading.setFont(heading.getFont().deriveFont(Font.BOLD));
        final JLabel value = new JLabel("—");
        panel.add(heading);
        panel.add(value);
        return value;
    }

    private JPanel createStatusPanel() {
        final JPanel panel = new JPanel(new GridLayout(0, 1, 2, 2));
        panel.setBorder(BorderFactory.createTitledBorder("Lifecycle and status"));
        countsLabel = new JLabel();
        lifecycleLabel = new JLabel();
        statusLabel = new JLabel("Ready");
        panel.add(countsLabel);
        panel.add(lifecycleLabel);
        panel.add(statusLabel);
        return panel;
    }

    private JButton button(final String label, final Runnable action) {
        final JButton button = new JButton(label);
        button.addActionListener(ignored -> runAction(action));
        return button;
    }

    private void runAction(final Runnable action) {
        try {
            action.run();
        } catch (RuntimeException failure) {
            final String description = failureDescription(failure);
            lastActionStatus = "ERROR " + description;
            context.logger().error(
                "Parameter validation action failed safely: " + description,
                failure
            );
            refresh();
        }
    }

    private void writeInput() {
        write(parseFiniteValue(setterField.getText()));
    }

    private void writeLabelColor() {
        final String selectedGroupId = selected(parameterGroupBox, null);
        if (selectedGroupId == null) {
            throw new IllegalStateException("Select one parameter group first.");
        }
        final ParameterGroupId groupId = new ParameterGroupId(selectedGroupId);
        final Color requested = parseColor(
            labelRedField.getText(),
            labelGreenField.getText(),
            labelBlueField.getText(),
            labelAlphaField.getText()
        );
        final Color authoritative = setParameterGroupLabelColor(
            activeModel().parameterGroups(),
            groupId,
            requested
        );
        lastActionStatus = "Parameter group " + groupId.value()
            + " label color=" + colorText(authoritative)
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    private void writeDefaultKeyformLock(final boolean locked) {
        final boolean authoritative = setDefaultKeyformLock(activeModel(), locked);
        lastActionStatus = "Default keyform locked=" + authoritative
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    static Color parseColor(
        final String red,
        final String green,
        final String blue,
        final String alpha
    ) {
        return new Color(
            parseFiniteValue(red),
            parseFiniteValue(green),
            parseFiniteValue(blue),
            parseFiniteValue(alpha)
        );
    }

    static Color setParameterGroupLabelColor(
        final ParameterGroups groups,
        final ParameterGroupId id,
        final Color color
    ) {
        Objects.requireNonNull(groups, "groups");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(color, "color");
        groups.find(id).setLabelColor(color);
        return groups.find(id).labelColor();
    }

    static boolean setDefaultKeyformLock(
        final CubismModel model,
        final boolean locked
    ) {
        Objects.requireNonNull(model, "model");
        model.setDefaultKeyformLocked(locked);
        return model.defaultKeyformLocked();
    }

    static float parseFiniteValue(final String text) {
        final String normalized = Objects.requireNonNull(text, "text").strip();
        final float value;
        try {
            value = Float.parseFloat(normalized);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                "Value must be a finite float: " + normalized,
                failure
            );
        }
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Value must be finite: " + normalized);
        }
        return value;
    }

    private void writeDefinition() {
        final ParameterId currentId = selectedParameterId.orElseThrow(() ->
            new IllegalStateException("Select one filtered parameter first."));
        final ParameterDefinition requested = parseDefinition(
            definitionIdField.getText(),
            definitionNameField.getText(),
            definitionMinimumField.getText(),
            definitionDefaultField.getText(),
            definitionMaximumField.getText(),
            selected(definitionTypeBox, ParameterType.NORMAL),
            definitionRepeatBox.isSelected()
        );
        final ParameterDefinition authoritative = updateParameterDefinition(
            activeParameters(),
            currentId,
            requested
        );
        selectedParameterId = Optional.of(authoritative.id());
        lastActionStatus = "Definition updated " + currentId.value() + " -> "
            + authoritative.id().value() + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    private void writeCombined() {
        final ParameterId currentId = selectedParameterId.orElseThrow(() ->
            new IllegalStateException("Select one filtered parameter first."));
        final List<ParameterId> candidates = partnerCandidates(activeParameters(), currentId);
        final Object selectedPartner = combinedPartnerBox.getEditor().getItem();
        final ParameterId partnerId = resolvePartnerId(
            selectedPartner == null ? "" : selectedPartner.toString(),
            candidates
        );
        final Optional<ParameterId> authoritative = combineParameters(
            activeParameters(),
            currentId,
            partnerId
        );
        lastActionStatus = "Combined " + currentId.value() + " with "
            + authoritative.orElseThrow().value()
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    private void writeUncombined() {
        final ParameterId currentId = selectedParameterId.orElseThrow(() ->
            new IllegalStateException("Select one filtered parameter first."));
        final Optional<ParameterId> authoritative = uncombineParameter(
            activeParameters(),
            currentId
        );
        if (authoritative.isPresent()) {
            throw new IllegalStateException(
                "Editor still reports Combined partner " + authoritative.orElseThrow().value()
            );
        }
        lastActionStatus = "Uncombined " + currentId.value()
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    private void writeSelectedBound(final Bound bound) {
        final Parameter parameter = selectedParameter();
        final float value = switch (bound) {
            case MINIMUM -> parameter.getMinimumValue();
            case DEFAULT -> parameter.getDefaultValue();
            case MAXIMUM -> parameter.getMaximumValue();
        };
        write(value);
    }

    private void write(final float value) {
        final ParameterId id = selectedParameterId.orElseThrow(() ->
            new IllegalStateException("Select one filtered parameter first."));
        final float authoritative = setParameterValue(activeParameters(), id, value);
        lastActionStatus = "Write " + id.value() + ": requested=" + value
            + ", authoritative=" + authoritative;
        refresh();
    }

    static ParameterDefinition parseDefinition(
        final String id,
        final String name,
        final String minimum,
        final String defaultValue,
        final String maximum,
        final ParameterType type,
        final boolean repeat
    ) {
        return new ParameterDefinition(
            new ParameterId(Objects.requireNonNull(id, "id").strip()),
            Objects.requireNonNull(name, "name"),
            parseFiniteValue(minimum),
            parseFiniteValue(defaultValue),
            parseFiniteValue(maximum),
            Objects.requireNonNull(type, "type"),
            repeat
        );
    }

    static ParameterDefinition updateParameterDefinition(
        final Parameters parameters,
        final ParameterId currentId,
        final ParameterDefinition definition
    ) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(currentId, "currentId");
        Objects.requireNonNull(definition, "definition");
        parameters.find(currentId).updateDefinition(definition);
        final Parameter authoritative = parameters.find(definition.id());
        return new ParameterDefinition(
            authoritative.id(),
            authoritative.name().orElseThrow(() ->
                new IllegalStateException("Updated parameter name is unavailable.")),
            authoritative.getMinimumValue(),
            authoritative.getDefaultValue(),
            authoritative.getMaximumValue(),
            authoritative.type(),
            authoritative.repeat().orElseThrow(() ->
                new IllegalStateException("Updated parameter repeat flag is unavailable."))
        );
    }

    static List<ParameterId> partnerCandidates(
        final Parameters parameters,
        final ParameterId currentId
    ) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(currentId, "currentId");
        return parameters.all().stream()
            .map(Parameter::id)
            .filter(id -> !currentId.equals(id))
            .toList();
    }

    static ParameterId resolvePartnerId(
        final String text,
        final List<ParameterId> candidates
    ) {
        final String normalized = Objects.requireNonNull(text, "text").strip();
        final List<ParameterId> available = List.copyOf(
            Objects.requireNonNull(candidates, "candidates")
        );
        if (!normalized.isEmpty()) {
            return new ParameterId(normalized);
        }
        if (available.isEmpty()) {
            throw new IllegalArgumentException(
                "No other parameter is available as a Combined partner."
            );
        }
        return available.get(0);
    }

    static Optional<ParameterId> combineParameters(
        final Parameters parameters,
        final ParameterId id,
        final ParameterId partnerId
    ) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(partnerId, "partnerId");
        if (id.equals(partnerId)) {
            throw new IllegalArgumentException("A parameter cannot be Combined with itself.");
        }
        parameters.find(id).combineWith(partnerId);
        return parameters.find(id).combinedWith();
    }

    static Optional<ParameterId> uncombineParameter(
        final Parameters parameters,
        final ParameterId id
    ) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(id, "id");
        parameters.find(id).uncombine();
        return parameters.find(id).combinedWith();
    }

    static float setParameterValue(
        final Parameters parameters,
        final ParameterId id,
        final float value
    ) {
        Objects.requireNonNull(parameters, "parameters");
        Objects.requireNonNull(id, "id");
        if (!Float.isFinite(value)) {
            throw new IllegalArgumentException("Value must be finite: " + value);
        }
        final Parameter parameter = parameters.find(id);
        parameter.setValue(value);
        return parameter.getValue();
    }

    private void writeCurrent() {
        final Parameter parameter = selectedParameter();
        write(parameter.getValue());
    }

    private Parameter selectedParameter() {
        final ParameterId id = selectedParameterId.orElseThrow(() ->
            new IllegalStateException("Select one filtered parameter first."));
        return activeParameters().find(id);
    }

    private CubismModel activeModel() {
        return context.cubism().model().active();
    }

    private void runPartOpacityValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "part-opacity-validation.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "status=RUNNING phase=await-model\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Part part = null;
            Exception unavailable = null;
            for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
                try {
                    part = onHostThread(() -> activeModel().parts().all().stream()
                        .filter(value -> !"__RootPart__".equals(value.id().value()))
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No non-root Part is available.")));
                    break;
                } catch (Exception exception) {
                    unavailable = exception;
                    Files.writeString(
                        artifact,
                        "status=RUNNING phase=await-model attempt=" + attempt + " error="
                            + exception.getClass().getName() + ": " + exception.getMessage() + "\n",
                        StandardOpenOption.TRUNCATE_EXISTING
                    );
                    Thread.sleep(1000L);
                }
            }
            if (part == null) {
                throw unavailable == null
                    ? new IllegalStateException("Part validation was interrupted.")
                    : unavailable;
            }
            final Part selectedPart = part;
            Files.writeString(artifact, "status=RUNNING phase=read-before\n", StandardOpenOption.TRUNCATE_EXISTING);
            final float before = onHostThread(selectedPart::getOpacity);
            final String partName = onHostThread(selectedPart::name);
            final String writtenName = partName + " Turboism";
            onHostThread(() -> { selectedPart.setName(writtenName); return null; });
            final String afterNameWrite = onHostThread(selectedPart::name);
            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
            Thread.sleep(800L);
            final String afterNameUndo = onHostThread(selectedPart::name);
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
            Thread.sleep(800L);
            final String afterNameRedo = onHostThread(selectedPart::name);
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
            Thread.sleep(800L);
            final String restoredName = onHostThread(selectedPart::name);
            final float written = Float.compare(before, 0.625F) == 0 ? 0.75F : 0.625F;
            Float afterWrite = null;
            Float afterUndo = null;
            Float afterRedo = null;
            String opacityWriteDisposition = "supported";
            boolean opacityPassed;
            try {
                onHostThread(() -> { selectedPart.setOpacity(written); return null; });
                Files.writeString(artifact, "status=RUNNING phase=after-write\n", StandardOpenOption.TRUNCATE_EXISTING);
                afterWrite = onHostThread(selectedPart::getOpacity);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                Thread.sleep(800L);
                afterUndo = onHostThread(selectedPart::getOpacity);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
                Thread.sleep(800L);
                afterRedo = onHostThread(selectedPart::getOpacity);
                opacityPassed = Float.compare(afterWrite, written) == 0
                    && Float.compare(afterUndo, before) == 0
                    && Float.compare(afterRedo, written) == 0;
            } catch (UnsupportedOperationException unsupported) {
                opacityWriteDisposition = "unsupported-fail-closed";
                afterWrite = onHostThread(selectedPart::getOpacity);
                opacityPassed = Float.compare(afterWrite, before) == 0;
            }
            final boolean passed = writtenName.equals(afterNameWrite)
                && partName.equals(afterNameUndo)
                && writtenName.equals(afterNameRedo)
                && partName.equals(restoredName)
                && opacityPassed;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + System.lineSeparator()
                    + "partId=" + part.id().value() + System.lineSeparator()
                    + "partName=" + partName + System.lineSeparator()
                    + "writtenName=" + writtenName + System.lineSeparator()
                    + "afterNameWrite=" + afterNameWrite + System.lineSeparator()
                    + "afterNameUndo=" + afterNameUndo + System.lineSeparator()
                    + "afterNameRedo=" + afterNameRedo + System.lineSeparator()
                    + "restoredName=" + restoredName + System.lineSeparator()
                    + "before=" + before + System.lineSeparator()
                    + "opacityWriteDisposition=" + opacityWriteDisposition + System.lineSeparator()
                    + "written=" + written + System.lineSeparator()
                    + "afterWrite=" + afterWrite + System.lineSeparator()
                    + "afterUndo=" + afterUndo + System.lineSeparator()
                    + "afterRedo=" + afterRedo + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            try {
                Files.writeString(
                    artifact,
                    "status=FAIL" + System.lineSeparator()
                        + "error=" + exception.getClass().getName() + ": "
                        + exception.getMessage() + System.lineSeparator(),
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                context.logger().error("Part opacity validation artifact could not be written", exception);
            }
        }
    }

    private static <T> T onHostThread(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            }
        });
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static void pressShortcut(final java.awt.Robot robot, final int key) {
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
    }

    private Parameters activeParameters() {
        return activeModel().parameters();
    }

    private Optional<ParameterId> cubismSelection() {
        try {
            return context.cubism().runtime().selection().activeParameterId()
                .map(ParameterId::new);
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    private QuerySpec currentQuery() {
        return new QuerySpec(
            searchField.getText(),
            selected(searchModeBox, SearchMode.CONTAINS),
            selected(typeFilterBox, TypeFilter.ANY),
            selected(repeatFilterBox, BooleanFilter.ANY),
            selected(combinedFilterBox, BooleanFilter.ANY)
        );
    }

    private static <T> T selected(final JComboBox<T> box, final T fallback) {
        final Object value = box.getSelectedItem();
        if (value == null) {
            return fallback;
        }
        return box.getItemAt(box.getSelectedIndex());
    }

    private void refreshLater() {
        SwingUtilities.invokeLater(this::refresh);
    }

    private void refresh() {
        if (frame == null || resultModel == null) {
            return;
        }
        try {
            final List<ParameterRow> rows = queryRows(activeParameters(), currentQuery());
            final boolean followCubismSelection = followSelectionBox.isSelected();
            final Optional<ParameterId> nextSelection = preferredSelection(
                rows,
                selectedParameterId,
                followCubismSelection ? cubismSelection() : Optional.empty(),
                followCubismSelection
            );
            applyRows(rows, nextSelection);
            refreshModelAuthoring(activeModel());
            statusLabel.setText(
                lastActionStatus + " — " + rows.size()
                    + " filtered parameter(s); definition editing enabled when verified"
            );
        } catch (RuntimeException unavailable) {
            applyRows(List.of(), Optional.empty());
            statusLabel.setText("Model unavailable: " + safeMessage(unavailable));
        }
        updateLifecycleLabels();
    }

    private void refreshModelAuthoring(final CubismModel model) {
        if (parameterGroupBox == null) {
            return;
        }
        final String prior = selected(parameterGroupBox, null);
        final List<ParameterGroup> groups = model.parameterGroups().all();
        parameterGroupBox.removeAllItems();
        groups.stream().map(group -> group.id().value()).forEach(parameterGroupBox::addItem);
        final String chosen = groups.stream().map(group -> group.id().value())
            .filter(id -> id.equals(prior))
            .findFirst()
            .orElseGet(() -> groups.isEmpty() ? null : groups.get(0).id().value());
        if (chosen != null) {
            parameterGroupBox.setSelectedItem(chosen);
        }
        refreshSelectedGroupColor(model);
        defaultKeyformLockLabel.setText(
            "Default keyform locked: " + model.defaultKeyformLocked()
        );
    }

    private void refreshSelectedGroupColor(final CubismModel model) {
        final String chosen = selected(parameterGroupBox, null);
        if (chosen == null) {
            currentLabelColorLabel.setText("Current color: unavailable");
            return;
        }
        final Color color = model.parameterGroups()
            .find(new ParameterGroupId(chosen))
            .labelColor();
        currentLabelColorLabel.setText("Current color: " + colorText(color));
        if (!labelRedField.hasFocus()) labelRedField.setText(Float.toString(color.red()));
        if (!labelGreenField.hasFocus()) labelGreenField.setText(Float.toString(color.green()));
        if (!labelBlueField.hasFocus()) labelBlueField.setText(Float.toString(color.blue()));
        if (!labelAlphaField.hasFocus()) labelAlphaField.setText(Float.toString(color.alpha()));
    }

    private void applyRows(
        final List<ParameterRow> rows,
        final Optional<ParameterId> nextSelection
    ) {
        applyingSelection = true;
        try {
            resultModel.clear();
            rows.forEach(resultModel::addElement);
            final int index = nextSelection
                .map(id -> indexOf(rows, id))
                .orElse(-1);
            if (index >= 0) {
                resultList.setSelectedIndex(index);
                resultList.ensureIndexIsVisible(index);
            } else {
                resultList.clearSelection();
            }
            selectedParameterId = nextSelection;
            resultCountLabel.setText(rows.size() + " parameter(s)");
        } finally {
            applyingSelection = false;
        }
        updateDetailsFromSelection();
    }

    private static int indexOf(final List<ParameterRow> rows, final ParameterId id) {
        for (int index = 0; index < rows.size(); index++) {
            if (rows.get(index).id().equals(id)) {
                return index;
            }
        }
        return -1;
    }

    private void updateDetailsFromSelection() {
        final ParameterRow row = resultList == null ? null : resultList.getSelectedValue();
        if (row == null) {
            selectedParameterId = Optional.empty();
            showNoSelection();
            return;
        }
        selectedParameterId = Optional.of(row.id());
        idValueLabel.setText(row.id().value());
        nameValueLabel.setText(row.name().orElse("unknown"));
        typeValueLabel.setText(row.type().name());
        blendShapeValueLabel.setText(Boolean.toString(row.blendShape()));
        combinedValueLabel.setText(booleanText(row.combined()));
        repeatValueLabel.setText(booleanText(row.repeat()));
        currentValueLabel.setText(number(row.value()));
        rangeValueLabel.setText('[' + number(row.minimum()) + ", " + number(row.maximum()) + ']');
        defaultValueLabel.setText(number(row.defaultValue()));
        if (!setterField.hasFocus()) {
            setterField.setText(Float.toString(row.value()));
        }
        if (definitionIdField != null && !definitionIdField.hasFocus()) {
            definitionIdField.setText(row.id().value());
        }
        if (definitionNameField != null && !definitionNameField.hasFocus()) {
            definitionNameField.setText(row.name().orElse(""));
        }
        if (definitionMinimumField != null && !definitionMinimumField.hasFocus()) {
            definitionMinimumField.setText(Float.toString(row.minimum()));
        }
        if (definitionDefaultField != null && !definitionDefaultField.hasFocus()) {
            definitionDefaultField.setText(Float.toString(row.defaultValue()));
        }
        if (definitionMaximumField != null && !definitionMaximumField.hasFocus()) {
            definitionMaximumField.setText(Float.toString(row.maximum()));
        }
        if (definitionTypeBox != null && row.type() != ParameterType.UNKNOWN) {
            definitionTypeBox.setSelectedItem(row.type());
        }
        if (definitionRepeatBox != null) {
            definitionRepeatBox.setEnabled(row.repeat().isPresent());
            definitionRepeatBox.setSelected(row.repeat().orElse(false));
        }
        if (definitionCombinedLabel != null) {
            definitionCombinedLabel.setText(
                "Combined marker: " + booleanText(row.combined())
            );
        }
        if (combinedPartnerValueLabel != null) {
            try {
                final Parameters parameters = activeParameters();
                final Optional<ParameterId> partner = parameters.find(row.id()).combinedWith();
                combinedPartnerValueLabel.setText(
                    "Current partner: " + partner.map(ParameterId::value).orElse("none")
                );
                updatePartnerChoices(parameters, row.id(), partner);
            } catch (RuntimeException unavailable) {
                combinedPartnerValueLabel.setText("Current partner: unavailable");
            }
        }
    }

    private void showNoSelection() {
        for (final JLabel label : List.of(
            idValueLabel,
            nameValueLabel,
            typeValueLabel,
            blendShapeValueLabel,
            combinedValueLabel,
            repeatValueLabel,
            currentValueLabel,
            rangeValueLabel,
            defaultValueLabel
        )) {
            if (label != null) {
                label.setText("—");
            }
        }
        if (setterField != null) {
            setterField.setText("");
        }
        for (final JTextField field : List.of(
            definitionIdField,
            definitionNameField,
            definitionMinimumField,
            definitionDefaultField,
            definitionMaximumField
        )) {
            if (field != null) {
                field.setText("");
            }
        }
        if (definitionRepeatBox != null) {
            definitionRepeatBox.setSelected(false);
            definitionRepeatBox.setEnabled(false);
        }
        if (definitionCombinedLabel != null) {
            definitionCombinedLabel.setText("Combined marker: —");
        }
        if (combinedPartnerBox != null) {
            combinedPartnerBox.removeAllItems();
        }
        if (combinedPartnerValueLabel != null) {
            combinedPartnerValueLabel.setText("Current partner: —");
        }
    }

    private void updateLifecycleLabels() {
        countsLabel.setText(
            "before=" + beforeCount.get()
                + "  changed(on)=" + changedCount.get()
                + "  after=" + afterCount.get()
        );
        lifecycleLabel.setText("last: " + lastLifecycle);
    }

    private static String booleanText(final Optional<Boolean> value) {
        return value.map(String::valueOf).orElse("unknown");
    }

    private static String number(final float value) {
        return String.format(Locale.ROOT, "%.3f", value);
    }

    private static String colorText(final Color color) {
        return "rgba(" + number(color.red()) + ", " + number(color.green()) + ", "
            + number(color.blue()) + ", " + number(color.alpha()) + ')';
    }

    private enum Bound {
        MINIMUM,
        DEFAULT,
        MAXIMUM
    }

    private void disposeWindow() {
        final Runnable dispose = () -> {
            final JFrame current = frame;
            frame = null;
            searchField = null;
            searchModeBox = null;
            typeFilterBox = null;
            repeatFilterBox = null;
            combinedFilterBox = null;
            followSelectionBox = null;
            resultModel = null;
            resultList = null;
            resultCountLabel = null;
            idValueLabel = null;
            nameValueLabel = null;
            typeValueLabel = null;
            blendShapeValueLabel = null;
            combinedValueLabel = null;
            repeatValueLabel = null;
            currentValueLabel = null;
            rangeValueLabel = null;
            defaultValueLabel = null;
            setterField = null;
            definitionIdField = null;
            definitionNameField = null;
            definitionMinimumField = null;
            definitionDefaultField = null;
            definitionMaximumField = null;
            definitionTypeBox = null;
            definitionRepeatBox = null;
            definitionCombinedLabel = null;
            combinedPartnerBox = null;
            combinedPartnerValueLabel = null;
            parameterGroupBox = null;
            labelRedField = null;
            labelGreenField = null;
            labelBlueField = null;
            labelAlphaField = null;
            currentLabelColorLabel = null;
            defaultKeyformLockLabel = null;
            countsLabel = null;
            lifecycleLabel = null;
            statusLabel = null;
            selectedParameterId = Optional.empty();
            if (current != null) {
                current.dispose();
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            dispose.run();
            return;
        }
        try {
            SwingUtilities.invokeAndWait(dispose);
        } catch (Exception failure) {
            throw new IllegalStateException("Validation probe window cleanup failed", failure);
        }
    }

    private void updatePartnerChoices(
        final Parameters parameters,
        final ParameterId currentId,
        final Optional<ParameterId> currentPartner
    ) {
        if (combinedPartnerBox == null) {
            return;
        }
        final Object editorItem = combinedPartnerBox.getEditor().getItem();
        final String typed = editorItem == null ? "" : editorItem.toString().strip();
        final List<ParameterId> candidates = partnerCandidates(parameters, currentId);
        combinedPartnerBox.removeAllItems();
        candidates.stream().map(ParameterId::value).forEach(combinedPartnerBox::addItem);
        final Optional<String> typedCandidate = candidates.stream()
            .map(ParameterId::value)
            .filter(typed::equals)
            .findFirst();
        final String preferred = currentPartner.map(ParameterId::value)
            .or(() -> typedCandidate)
            .orElseGet(() -> candidates.isEmpty() ? "" : candidates.get(0).value());
        combinedPartnerBox.getEditor().setItem(preferred);
        combinedPartnerBox.setToolTipText(
            candidates.isEmpty()
                ? "No other model parameter is available."
                : "Choose another parameter, or type its exact ID. Runtime validates group and pair eligibility."
        );
    }

    static String failureDescription(final Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        final StringBuilder description = new StringBuilder();
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth < 4) {
            if (depth > 0) {
                description.append(" <- ");
            }
            description.append(current.getClass().getSimpleName())
                .append(": ")
                .append(safeMessage(current));
            current = current.getCause();
            depth++;
        }
        return description.toString();
    }

    private static String safeMessage(final Throwable failure) {
        final String message = failure.getMessage();
        return message == null || message.isBlank() ? failure.getClass().getSimpleName() : message;
    }
}
