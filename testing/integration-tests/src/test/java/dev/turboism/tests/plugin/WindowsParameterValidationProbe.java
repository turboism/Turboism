package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.id.ParameterGroupId;
import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.id.ParameterBindingPointId;
import dev.turboism.sdk.cubism.model.ArtMeshGeometry;
import dev.turboism.sdk.cubism.model.Color;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ModelEditLevel;
import dev.turboism.sdk.cubism.model.ModelStatistics;
import dev.turboism.sdk.cubism.model.Deformer;
import dev.turboism.sdk.cubism.model.Drawable;
import dev.turboism.sdk.cubism.model.Parameter;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterGroup;
import dev.turboism.sdk.cubism.model.ParameterGroups;
import dev.turboism.sdk.cubism.model.ParameterBinding;
import dev.turboism.sdk.cubism.model.ParameterBindingOperations;
import dev.turboism.sdk.cubism.model.ParameterBindingPoint;
import dev.turboism.sdk.cubism.model.ParameterBindingTarget;
import dev.turboism.sdk.cubism.model.ParameterBindingTransferPlan;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.Parameters;
import dev.turboism.sdk.cubism.model.Part;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.model.Point2;
import dev.turboism.sdk.cubism.model.RotationDeformer;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.WarpDeformer;
import dev.turboism.sdk.cubism.model.WarpGrid;
import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.ui.appearance.NativeLabelColor;
import dev.turboism.sdk.ui.appearance.NativeLabelColorState;
import dev.turboism.sdk.ui.appearance.PresetColor;
import dev.turboism.sdk.ui.appearance.UiColor;

import javax.swing.BorderFactory;
import javax.swing.DefaultListModel;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
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
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;
import java.nio.file.Files;
import java.nio.file.attribute.FileTime;
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
    private final ConcurrentHashMap<String, AtomicInteger> editorObjectLifecycleCounts = new ConcurrentHashMap<>();
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
            final String mode = System.getProperty("turboism.editorObjectValidation.mode", "matrix");
            final long startedNanos = System.nanoTime();
            try {
                if ("fixed-api".equals(mode)) {
                    runFixedApiValidation(false);
                } else if ("fixed-api-document-close".equals(mode)) {
                    runFixedApiValidation(true);
                } else if ("statistics-read".equals(mode)) {
                    runModelStatisticsValidation();
                } else if ("binding-read".equals(mode)) {
                    runParameterBindingDiscoveryRead();
                } else if ("binding-matrix".equals(mode)) {
                    runParameterBindingValidation();
                } else if ("parameter-menu-smoke".equals(mode)) {
                    runParameterMenuSmoke();
                } else if ("persist-write".equals(mode)) {
                    runEditorObjectPersistenceWrite();
                } else if ("persist-read".equals(mode)) {
                    runEditorObjectPersistenceRead();
                } else if ("plugin-scope-close".equals(mode)) {
                    runEditorObjectPluginScopeClose();
                } else if ("document-close".equals(mode)) {
                    runEditorObjectDocumentClose();
                } else if ("model-edit-level".equals(mode)) {
                    runModelEditLevelValidation();
                } else if ("wave1".equals(mode)) {
                    runModelEditLevelValidation();
                    runParameterMenuSmoke();
                } else if ("native-control-background".equals(mode)) {
                    runNativeLabelColorValidation();
                } else if ("native-control-background-document-close".equals(mode)) {
                    runNativeLabelColorDocumentClose();
                } else if ("native-control-background-persist-write".equals(mode)) {
                    runNativeLabelColorPersistWrite();
                } else if ("native-control-background-persist-reopen".equals(mode)) {
                    runNativeLabelColorPersistReopen();
                } else if ("native-control-background-persist-final".equals(mode)) {
                    runNativeLabelColorPersistFinal();
                } else {
                    runEditorObjectValidation();
                    runPartOpacityValidation();
                }
            } finally {
                if (Boolean.getBoolean("turboism.validation.exitOnComplete")) {
                    finishAutomatedValidation(mode, startedNanos);
                    return;
                }
                if (showsValidationWindow(mode)) {
                    SwingUtilities.invokeLater(this::showWindow);
                }
            }
        }, "turboism-editor-object-validation");
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

    @Override
    public float beforeSetDrawableOpacity(final Drawable drawable, final float opacity) {
        recordEditorObjectLifecycle("meshOpacity", "before");
        return opacity;
    }

    @Override
    public void onDrawableOpacityChanged(final Drawable drawable, final float oldOpacity, final float newOpacity) {
        recordEditorObjectLifecycle("meshOpacity", "on");
    }

    @Override
    public void afterSetDrawableOpacity(final Drawable drawable, final float opacity) {
        recordEditorObjectLifecycle("meshOpacity", "after");
    }

    @Override
    public boolean beforeSetDrawableVisible(final Drawable drawable, final boolean visible) {
        recordEditorObjectLifecycle("meshVisible", "before");
        return visible;
    }

    @Override
    public void onDrawableVisibilityChanged(final Drawable drawable, final boolean oldVisible, final boolean newVisible) {
        recordEditorObjectLifecycle("meshVisible", "on");
    }

    @Override
    public void afterSetDrawableVisible(final Drawable drawable, final boolean visible) {
        recordEditorObjectLifecycle("meshVisible", "after");
    }

    @Override
    public boolean beforeSetDrawableLocked(final Drawable drawable, final boolean locked) {
        recordEditorObjectLifecycle("meshLocked", "before");
        return locked;
    }

    @Override
    public void onDrawableLockChanged(final Drawable drawable, final boolean oldLocked, final boolean newLocked) {
        recordEditorObjectLifecycle("meshLocked", "on");
    }

    @Override
    public void afterSetDrawableLocked(final Drawable drawable, final boolean locked) {
        recordEditorObjectLifecycle("meshLocked", "after");
    }

    @Override
    public ArtMeshGeometry beforeReplaceDrawableGeometry(final Drawable drawable, final ArtMeshGeometry geometry) {
        recordEditorObjectLifecycle("meshGeometry", "before");
        return geometry;
    }

    @Override
    public void onDrawableGeometryChanged(
        final Drawable drawable,
        final ArtMeshGeometry oldGeometry,
        final ArtMeshGeometry newGeometry
    ) {
        recordEditorObjectLifecycle("meshGeometry", "on");
    }

    @Override
    public void afterReplaceDrawableGeometry(final Drawable drawable, final ArtMeshGeometry geometry) {
        recordEditorObjectLifecycle("meshGeometry", "after");
    }

    @Override
    public float beforeSetDeformerOpacity(final Deformer deformer, final float opacity) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Opacity"), "before");
        return opacity;
    }

    @Override
    public void onDeformerOpacityChanged(final Deformer deformer, final float oldOpacity, final float newOpacity) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Opacity"), "on");
    }

    @Override
    public void afterSetDeformerOpacity(final Deformer deformer, final float opacity) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Opacity"), "after");
    }

    @Override
    public boolean beforeSetDeformerVisible(final Deformer deformer, final boolean visible) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Visible"), "before");
        return visible;
    }

    @Override
    public void onDeformerVisibilityChanged(final Deformer deformer, final boolean oldVisible, final boolean newVisible) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Visible"), "on");
    }

    @Override
    public void afterSetDeformerVisible(final Deformer deformer, final boolean visible) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Visible"), "after");
    }

    @Override
    public boolean beforeSetDeformerLocked(final Deformer deformer, final boolean locked) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Locked"), "before");
        return locked;
    }

    @Override
    public void onDeformerLockChanged(final Deformer deformer, final boolean oldLocked, final boolean newLocked) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Locked"), "on");
    }

    @Override
    public void afterSetDeformerLocked(final Deformer deformer, final boolean locked) {
        recordEditorObjectLifecycle(deformerLifecycleLabel(deformer, "Locked"), "after");
    }

    @Override
    public WarpGrid beforeReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) {
        recordEditorObjectLifecycle("warpGrid", "before");
        return grid;
    }

    @Override
    public void onWarpDeformerGridChanged(
        final WarpDeformer deformer,
        final WarpGrid oldGrid,
        final WarpGrid newGrid
    ) {
        recordEditorObjectLifecycle("warpGrid", "on");
    }

    @Override
    public void afterReplaceWarpDeformerGrid(final WarpDeformer deformer, final WarpGrid grid) {
        recordEditorObjectLifecycle("warpGrid", "after");
    }

    @Override
    public float beforeSetRotationDeformerBaseAngle(final RotationDeformer deformer, final float angle) {
        recordEditorObjectLifecycle("rotationBaseAngle", "before");
        return angle;
    }

    @Override
    public void onRotationDeformerBaseAngleChanged(
        final RotationDeformer deformer,
        final float oldAngle,
        final float newAngle
    ) {
        recordEditorObjectLifecycle("rotationBaseAngle", "on");
    }

    @Override
    public void afterSetRotationDeformerBaseAngle(final RotationDeformer deformer, final float angle) {
        recordEditorObjectLifecycle("rotationBaseAngle", "after");
    }

    @Override
    public RotationDeformerForm beforeReplaceRotationDeformerForm(
        final RotationDeformer deformer,
        final RotationDeformerForm form
    ) {
        recordEditorObjectLifecycle("rotationForm", "before");
        return form;
    }

    @Override
    public void onRotationDeformerFormChanged(
        final RotationDeformer deformer,
        final RotationDeformerForm oldForm,
        final RotationDeformerForm newForm
    ) {
        recordEditorObjectLifecycle("rotationForm", "on");
    }

    @Override
    public void afterReplaceRotationDeformerForm(
        final RotationDeformer deformer,
        final RotationDeformerForm form
    ) {
        recordEditorObjectLifecycle("rotationForm", "after");
    }

    private void recordEditorObjectLifecycle(final String operation, final String phase) {
        editorObjectLifecycleCounts.computeIfAbsent(operation + "." + phase, ignored -> new AtomicInteger())
            .incrementAndGet();
    }

    private static String deformerLifecycleLabel(final Deformer deformer, final String suffix) {
        if (deformer instanceof WarpDeformer) return "warp" + suffix;
        if (deformer instanceof RotationDeformer) return "rotation" + suffix;
        throw new IllegalArgumentException("Unsupported deformer hook target: " + deformer.getClass().getName());
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
        panel.add(button("Set label background", this::writeLabelColor), constraints);

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
        final UiColor requested = parseColor(
            labelRedField.getText(),
            labelGreenField.getText(),
            labelBlueField.getText(),
            labelAlphaField.getText()
        );
        final ParameterGroup group = activeModel().parameterGroups().find(groupId);
        final NativeLabelColorState authoritative = setParameterFolderLabelColor(group, requested);
        lastActionStatus = "Parameter folder " + groupId.value()
            + " labelColor=" + backgroundText(authoritative.labelColor())
            + " actual=" + effectiveText(authoritative.actualColor())
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    private void writeDefaultKeyformLock(final boolean locked) {
        final boolean authoritative = setDefaultKeyformLock(activeModel(), locked);
        lastActionStatus = "Default keyform locked=" + authoritative
            + "; use Cubism Undo/Redo and save/reopen to validate";
        refresh();
    }

    static UiColor parseColor(
        final String red,
        final String green,
        final String blue,
        final String alpha
    ) {
        return new UiColor(
            parseFiniteValue(red),
            parseFiniteValue(green),
            parseFiniteValue(blue),
            parseFiniteValue(alpha)
        );
    }

    static NativeLabelColorState setParameterFolderLabelColor(
        final ParameterGroup group,
        final UiColor color
    ) {
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(color, "color");
        final var ui = group.ui();
        ui.setNativeLabelColor(new NativeLabelColor.Custom(color));
        return ui.nativeLabelColor().orElseThrow(() ->
            new IllegalStateException(
                "No native label color is available for parameter folder " + group.id().value()
            ));
    }

    static boolean setDefaultKeyformLock(
        final CubismModel model,
        final boolean locked
    ) {
        Objects.requireNonNull(model, "model");
        model.setDefaultKeyformLocked(locked);
        return model.defaultKeyformLocked();
    }

    /**
     * Auto evidence modes that run to completion (especially those that close the plugin scope
     * or the document) must not rebuild the validation window afterwards.
     */
    static boolean showsValidationWindow(final String mode) {
        final String value = Objects.requireNonNull(mode, "mode");
        return !value.startsWith("native-control-background")
            && !value.startsWith("fixed-api");
    }

    enum HostCloseDecision {
        CLEAN_CLOSE,
        DISCARD,
        UNSUPPORTED_CONFIRMATION
    }

    enum HostCloseRoute {
        SYNTHETIC_WINDOW_CLOSING,
        ROBOT_ALT_F4
    }

    static HostCloseRoute hostCloseRoute(final String hostVersion) {
        if (hostVersion == null) {
            throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302"
            );
        }
        return switch (hostVersion) {
            case "5203" -> HostCloseRoute.SYNTHETIC_WINDOW_CLOSING;
            case "5302" -> HostCloseRoute.ROBOT_ALT_F4;
            default -> throw new IllegalArgumentException(
                "turboism.validation.hostVersion must be 5203 or 5302: " + hostVersion
            );
        };
    }

    /** Chooses the non-saving branch without depending on localized button text. */
    static HostCloseDecision hostCloseDecision(
        final boolean confirmationVisible,
        final int optionType,
        final int enabledButtonCount
    ) {
        if (!confirmationVisible) {
            return HostCloseDecision.CLEAN_CLOSE;
        }
        if (optionType == JOptionPane.YES_NO_OPTION
            || optionType == JOptionPane.YES_NO_CANCEL_OPTION) {
            return HostCloseDecision.DISCARD;
        }
        return optionType == JOptionPane.DEFAULT_OPTION
                && (enabledButtonCount == 2 || enabledButtonCount == 3)
            ? HostCloseDecision.DISCARD
            : HostCloseDecision.UNSUPPORTED_CONFIRMATION;
    }

    /**
     * Response budget for the primary's post-scope-close peer terminal-evidence wait (60 s max).
     * Deliberately distinct from the peer plugin's pre-marker startup budget (240 s, which begins
     * before Cubism host/model readiness).
     */
    static final int PEER_RESPONSE_MAX_ATTEMPTS = 600;
    static final long PEER_RESPONSE_POLL_MILLIS = 100L;

    /** RUNNING progress phase written before the bounded peer wait so a stuck run reports correctly. */
    static String scopeCloseRunningPhase(final String modelId, final String hostThread) {
        return "status=RUNNING\nphase=plugin-scope-close\n"
            + "modelId=" + modelId + "\n"
            + "hostThread=" + hostThread + "\n";
    }

    /** Writes the scope-close progress phase from the pre-close captured values only. */
    static void writeRunningScopeClosePhase(
        final Path artifact,
        final String modelId,
        final String hostThread
    ) throws Exception {
        Files.createDirectories(artifact.getParent());
        Files.writeString(
            artifact,
            scopeCloseRunningPhase(modelId, hostThread),
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING
        );
    }

    /**
     * Bounded wait for terminal peer evidence (status=PASS|FAIL). Returns the last observed
     * content; an empty result after the bounded attempts means the peer is absent or failed.
     */
    static String awaitPeerEvidence(
        final Path peerArtifact,
        final int maxAttempts,
        final long pollMillis
    ) throws Exception {
        String evidence = "";
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            evidence = Files.exists(peerArtifact) ? Files.readString(peerArtifact) : "";
            if (evidence.contains("status=PASS") || evidence.contains("status=FAIL")) {
                return evidence;
            }
            Thread.sleep(pollMillis);
        }
        return "";
    }

    /** Task-scoped copied model required by the persistence stages (Windows JVM-readable path). */
    static Path fixturePath() {
        final String raw = System.getProperty("turboism.validation.fixture");
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(
                "turboism.validation.fixture must point to the task-scoped copied model"
            );
        }
        return Path.of(raw);
    }

    /**
     * Bounded save confirmation: waits for the fixture file mtime/size to change after Ctrl+S
     * and to remain stable across consecutive samples. Timeout reports {@code confirmed=false};
     * a PASS is never written without confirmation. JDK Files/FileTime only.
     */
    static SaveConfirmation awaitSaveConfirmation(
        final Path fixture,
        final FileTime beforeMtime,
        final long beforeSize
    ) throws Exception {
        return awaitSaveConfirmation(
            fixture,
            beforeMtime,
            beforeSize,
            DEFAULT_SAVE_DEADLINE_MILLIS,
            DEFAULT_SAVE_POLL_MILLIS
        );
    }

    static SaveConfirmation awaitSaveConfirmation(
        final Path fixture,
        final FileTime beforeMtime,
        final long beforeSize,
        final long deadlineMillis,
        final long pollMillis
    ) throws Exception {
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(beforeMtime, "beforeMtime");
        if (!Files.isRegularFile(fixture)) {
            throw new IllegalArgumentException(
                "turboism.validation.fixture is missing or unreadable: " + fixture
            );
        }
        final long deadlineNanos = System.nanoTime() + deadlineMillis * 1_000_000L;
        FileTime changedMtime = null;
        long changedSize = -1L;
        int stableSamples = 0;
        while (System.nanoTime() < deadlineNanos) {
            final FileTime mtime = Files.getLastModifiedTime(fixture);
            final long size = Files.size(fixture);
            if (!mtime.equals(beforeMtime) || size != beforeSize) {
                if (changedMtime == null || !changedMtime.equals(mtime) || changedSize != size) {
                    changedMtime = mtime;
                    changedSize = size;
                    stableSamples = 0;
                }
                stableSamples++;
                if (stableSamples >= SAVE_STABLE_SAMPLES) {
                    return new SaveConfirmation(
                        true, beforeMtime.toMillis(), beforeSize, mtime.toMillis(), size
                    );
                }
            }
            Thread.sleep(pollMillis);
        }
        final FileTime lastMtime = Files.getLastModifiedTime(fixture);
        return new SaveConfirmation(
            false, beforeMtime.toMillis(), beforeSize, lastMtime.toMillis(), Files.size(fixture)
        );
    }

    static final long DEFAULT_SAVE_DEADLINE_MILLIS = 30_000L;
    static final long DEFAULT_SAVE_POLL_MILLIS = 100L;
    static final int SAVE_STABLE_SAMPLES = 3;

    /** Machine-readable save evidence; {@code confirmed=false} is a FAIL, never a PASS. */
    record SaveConfirmation(
        boolean confirmed,
        long beforeMtimeMillis,
        long beforeSize,
        long afterMtimeMillis,
        long afterSize
    ) {
        String report(final String prefix) {
            return prefix + "saveConfirmed=" + confirmed + System.lineSeparator()
                + prefix + "save.beforeMtimeMillis=" + beforeMtimeMillis + System.lineSeparator()
                + prefix + "save.beforeSize=" + beforeSize + System.lineSeparator()
                + prefix + "save.afterMtimeMillis=" + afterMtimeMillis + System.lineSeparator()
                + prefix + "save.afterSize=" + afterSize + System.lineSeparator();
        }
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
    private void runModelStatisticsValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "model-statistics-validation.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitEditorObjectModel(artifact);
            final ModelStatistics statistics = onHostThread(model::statistics);
            final int parameterCount = onHostThread(() -> model.parameters().all().size());
            final int partCount = onHostThread(() -> model.parts().all().size());
            final int drawableCount = onHostThread(() -> model.drawables().all().size());
            final int deformerCount = onHostThread(() -> model.deformers().all().size());
            if (statistics.parameterCount() != parameterCount
                || statistics.partCount() != partCount
                || statistics.drawableCount() != drawableCount
                || statistics.artMeshCount() != drawableCount
                || statistics.deformerCount() != deformerCount) {
                throw new IllegalStateException("Model statistics counts disagree with SDK collections.");
            }
            final String report = new StringBuilder("status=PASS\n")
                .append("modelId=").append(onHostThread(() -> model.id().value())).append('\n')
                .append("parameterCount=").append(statistics.parameterCount()).append('\n')
                .append("partCount=").append(statistics.partCount()).append('\n')
                .append("drawableCount=").append(statistics.drawableCount()).append('\n')
                .append("artMeshCount=").append(statistics.artMeshCount()).append('\n')
                .append("deformerCount=").append(statistics.deformerCount()).append('\n')
                .append("vertexCount=").append(statistics.vertexCount()).append('\n')
                .append("triangleCount=").append(statistics.triangleCount()).append('\n')
                .append("textureCount=").append(statistics.textureCount()).append('\n')
                .append("maskedDrawableCount=").append(statistics.maskedDrawableCount()).append('\n')
                .append("maskGroupCount=").append(statistics.maskGroupCount()).append('\n')
                .append("offscreenRenderingCount=")
                .append(statistics.offscreenRenderingCount().isPresent()
                    ? statistics.offscreenRenderingCount().getAsInt() : "unavailable")
                .append('\n')
                .append("maxOffscreenDepth=")
                .append(statistics.maxOffscreenDepth().isPresent()
                    ? statistics.maxOffscreenDepth().getAsInt() : "unavailable")
                .append('\n')
                .toString();
            Files.writeString(
                artifact,
                report,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Model statistics validation failed");
        }
    }

    private void runModelEditLevelValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "model-edit-level-validation.txt"
        );
        try {
            final String callerThread = Thread.currentThread().getName();
            final boolean callerEdt = SwingUtilities.isEventDispatchThread();
            if (callerEdt) {
                throw new IllegalStateException("Model edit-level validation must run off the AWT EDT.");
            }
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitEditorObjectModel(artifact);
            final String modelId = onHostThread(() -> model.id().value());
            final ModelEditLevel before = model.editLevel();
            final ModelEditLevel written = before == ModelEditLevel.LEVEL_3
                ? ModelEditLevel.LEVEL_2
                : ModelEditLevel.LEVEL_3;
            model.setEditLevel(written);
            final ModelEditLevel afterWrite = model.editLevel();
            model.setEditLevel(before);
            final ModelEditLevel restored = model.editLevel();
            final boolean passed = afterWrite == written && restored == before;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + System.lineSeparator()
                    + "modelId=" + modelId + System.lineSeparator()
                    + "callerThread=" + callerThread + System.lineSeparator()
                    + "callerEdt=" + callerEdt + System.lineSeparator()
                    + "before=" + before + System.lineSeparator()
                    + "written=" + written + System.lineSeparator()
                    + "afterWrite=" + afterWrite + System.lineSeparator()
                    + "restored=" + restored + System.lineSeparator()
                    + "undoRedo=NOT_APPLICABLE_HOST_VIEW_STATE" + System.lineSeparator()
                    + "dirtyState=NOT_EXPECTED" + System.lineSeparator()
                    + "persistence=NOT_CLAIMED" + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Model edit-level validation failed");
        }
    }

    private void runFixedApiValidation(final boolean closeDocument) {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs",
            closeDocument ? "fixed-api-document-close.txt" : "fixed-api-validation.txt"
        );
        final AtomicReference<String> phase = new AtomicReference<>("await-model");
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitEditorObjectModel(artifact);
            phase.set("identity");
            final StringBuilder report = new StringBuilder("status=RUNNING\n")
                .append("phase=fixed-api-read\n")
                .append("hostThread=").append(onHostThread(() ->
                    Thread.currentThread().getName() + "|edt=" + SwingUtilities.isEventDispatchThread()
                )).append('\n')
                .append("modelId=").append(onHostThread(() -> model.id().value())).append('\n');

            appendRequiredFixedOutcome(report, "activeDocument", () -> onHostThread(() ->
                context.cubism().activeDocument()
                    .map(document -> document.documentId() + "|" + document.name())
                    .orElse("none")
            ));
            phase.set("parameters");

            final Parameters parameters = onHostThread(model::parameters);
            final List<Parameter> parameterValues = onHostThread(parameters::all);
            if (parameterValues.isEmpty()) {
                throw new IllegalStateException("No Parameter is available.");
            }
            final Parameter parameter = parameterValues.get(0);
            final int parameterIndex = onHostThread(parameter::index);
            if (parameterIndex < 0) {
                throw new IllegalStateException("Parameter index must be non-negative.");
            }
            report.append("parameters.count=").append(parameterValues.size()).append('\n')
                .append("parameter.id=").append(onHostThread(() -> parameter.id().value())).append('\n')
                .append("parameter.index=").append(parameterIndex).append('\n');
            appendFixedOutcome(report, "parameter.keyValues", () ->
                onHostThread(() -> parameter.keyValues().size()));
            phase.set("parameter-definitions");

            final dev.turboism.sdk.cubism.model.ParameterDefinitions definitions =
                onHostThread(model::parameterDefinitions);
            final List<ParameterDefinition> definitionValues = onHostThread(definitions::all);
            if (definitionValues.isEmpty()) {
                throw new IllegalStateException("No Parameter definition is available.");
            }
            final ParameterDefinition definition = definitionValues.get(0);
            final ParameterDefinition foundDefinition = onHostThread(() -> definitions.find(definition.id()));
            if (!definition.equals(foundDefinition)) {
                throw new IllegalStateException("Parameter definition lookup does not match stable order.");
            }
            report.append("parameterDefinitions.count=").append(definitionValues.size()).append('\n')
                .append("parameterDefinition.first=").append(fixedText(definition)).append('\n');
            phase.set("parts");

            final dev.turboism.sdk.cubism.model.Parts parts = onHostThread(model::parts);
            final List<Part> partValues = onHostThread(parts::all);
            if (partValues.isEmpty()) {
                throw new IllegalStateException("No Part is available.");
            }
            for (int index = 0; index < partValues.size(); index++) {
                final Part current = partValues.get(index);
                if (onHostThread(current::index) != index) {
                    throw new IllegalStateException("Part index does not match stable model order at " + index + '.');
                }
                final Optional<dev.turboism.sdk.cubism.model.PartId> parentId =
                    onHostThread(current::parentId);
                if (parentId.isPresent()) {
                    final Part parent = onHostThread(() -> parts.find(parentId.orElseThrow()));
                    if (!onHostThread(parent::childIds).contains(onHostThread(current::id))) {
                        throw new IllegalStateException("Part parent does not contain child " + current.id().value());
                    }
                }
                for (dev.turboism.sdk.cubism.model.PartId childId : onHostThread(current::childIds)) {
                    final Part child = onHostThread(() -> parts.find(childId));
                    if (!onHostThread(child::parentId).equals(Optional.of(onHostThread(current::id)))) {
                        throw new IllegalStateException("Part child does not point back to parent " + current.id().value());
                    }
                }
            }
            final Part part = partValues.get(0);
            report.append("parts.count=").append(partValues.size()).append('\n')
                .append("part.first.id=").append(onHostThread(() -> part.id().value())).append('\n')
                .append("part.first.index=").append(onHostThread(part::index)).append('\n')
                .append("part.first.parentId=").append(fixedText(onHostThread(part::parentId))).append('\n')
                .append("part.first.childIds=").append(fixedText(onHostThread(part::childIds))).append('\n')
                .append("fixedReads.status=PASS\n");
            phase.set("required-fixed-api");

            appendRequiredFixedOutcome(report, "coreRuntime.version", () ->
                onHostThread(() -> context.cubism().coreRuntime().version()));
            appendRequiredFixedOutcome(report, "coreRuntime.capabilities", () ->
                onHostThread(() -> context.cubism().coreRuntime().capabilities()));
            appendRequiredFixedOutcome(report, "coreRuntime.latestMocVersion", () ->
                onHostThread(() -> context.cubism().coreRuntime().mocInspector().latestVersion()));
            appendRequiredFixedOutcome(report, "model.name", () -> onHostThread(model::name));
            appendFixedOutcome(report, "model.mocInfo", () -> onHostThread(model::mocInfo));

            appendRequiredFixedOutcome(report, "part.shortName", () -> onHostThread(part::shortName));
            appendRequiredFixedOutcome(report, "part.visible", () -> onHostThread(part::visible));
            appendRequiredFixedOutcome(report, "part.visibleInHierarchy", () -> onHostThread(part::visibleInHierarchy));
            appendRequiredFixedOutcome(report, "part.locked", () -> onHostThread(part::locked));
            appendRequiredFixedOutcome(report, "part.lockedInHierarchy", () -> onHostThread(part::lockedInHierarchy));
            appendRequiredFixedOutcome(report, "part.editColor", () -> onHostThread(part::editColor));
            appendRequiredFixedOutcome(report, "part.sketch", () -> onHostThread(part::sketch));
            appendRequiredFixedOutcome(report, "part.defaultOrder", () -> onHostThread(part::defaultOrder));
            appendRequiredFixedOutcome(report, "part.parentIndex", () -> onHostThread(part::parentIndex));

            final var drawables = onHostThread(model::drawables);
            final List<Drawable> drawableValues = onHostThread(drawables::all);
            if (drawableValues.isEmpty()) {
                throw new IllegalStateException("No Drawable is available.");
            }
            for (int index = 0; index < drawableValues.size(); index++) {
                if (onHostThread(drawableValues.get(index)::index) != index) {
                    throw new IllegalStateException("Drawable index does not match stable model order at " + index + '.');
                }
            }
            final Drawable drawable = drawableValues.get(0);
            report.append("drawables.count=").append(drawableValues.size()).append('\n');
            appendRequiredFixedOutcome(report, "drawable.index", () -> onHostThread(drawable::index));
            appendRequiredFixedOutcome(report, "drawable.doubleSided", () -> onHostThread(drawable::doubleSided));
            appendFixedOutcome(report, "drawable.evaluationState", () -> onHostThread(drawable::evaluationState));
            appendRequiredFixedOutcome(report, "drawable.parentPartId", () -> onHostThread(drawable::parentPartId));
            appendRequiredFixedOutcome(report, "drawable.parentPartIndex", () -> onHostThread(drawable::parentPartIndex));
            appendRequiredFixedOutcome(report, "drawable.parentDeformerId", () -> onHostThread(drawable::parentDeformerId));
            appendRequiredFixedOutcome(report, "drawable.parentDeformerIndex", () -> onHostThread(drawable::parentDeformerIndex));
            appendRequiredFixedOutcome(report, "drawable.parameterIds", () -> onHostThread(drawable::parameterIds));
            appendRequiredFixedOutcome(report, "drawable.parameters", () ->
                onHostThread(() -> fixedInts(drawable.parameters())));
            appendRequiredFixedOutcome(report, "drawable.maskIds", () -> onHostThread(drawable::maskIds));
            appendRequiredFixedOutcome(report, "drawable.masks", () ->
                onHostThread(() -> fixedInts(drawable.masks())));

            final var deformers = onHostThread(model::deformers);
            final List<Deformer> deformerValues = onHostThread(deformers::all);
            if (deformerValues.isEmpty()) {
                throw new IllegalStateException("No Deformer is available.");
            }
            for (int index = 0; index < deformerValues.size(); index++) {
                if (onHostThread(deformerValues.get(index)::index) != index) {
                    throw new IllegalStateException("Deformer index does not match stable model order at " + index + '.');
                }
            }
            final Deformer deformer = deformerValues.get(0);
            report.append("deformers.count=").append(deformerValues.size()).append('\n');
            appendRequiredFixedOutcome(report, "deformer.index", () -> onHostThread(deformer::index));
            appendRequiredFixedOutcome(report, "deformer.parentPartId", () -> onHostThread(deformer::parentPartId));
            appendRequiredFixedOutcome(report, "deformer.parentPartIndex", () -> onHostThread(deformer::parentPartIndex));
            appendRequiredFixedOutcome(report, "deformer.parentDeformerId", () -> onHostThread(deformer::parentDeformerId));
            appendRequiredFixedOutcome(report, "deformer.parentDeformerIndex", () -> onHostThread(deformer::parentDeformerIndex));
            appendRequiredFixedOutcome(report, "deformer.parameterIds", () -> onHostThread(deformer::parameterIds));
            appendRequiredFixedOutcome(report, "deformer.parameters", () ->
                onHostThread(() -> fixedInts(deformer.parameters())));
            appendRequiredFixedOutcome(report, "deformer.name", () -> onHostThread(deformer::name));
            appendRequiredFixedOutcome(report, "deformer.visible", () -> onHostThread(deformer::visible));
            appendRequiredFixedOutcome(report, "deformer.visibleInHierarchy", () -> onHostThread(deformer::visibleInHierarchy));
            appendRequiredFixedOutcome(report, "deformer.locked", () -> onHostThread(deformer::locked));
            appendRequiredFixedOutcome(report, "deformer.lockedInHierarchy", () -> onHostThread(deformer::lockedInHierarchy));
            appendRequiredFixedOutcome(report, "deformer.opacity", () -> onHostThread(deformer::getOpacity));
            appendFixedOutcome(report, "deformer.multiplyColor", () -> onHostThread(deformer::multiplyColor));
            appendFixedOutcome(report, "deformer.screenColor", () -> onHostThread(deformer::screenColor));

            final var glues = onHostThread(model::glues);
            final List<dev.turboism.sdk.cubism.model.Glue> glueValues = onHostThread(glues::all);
            final dev.turboism.sdk.cubism.model.Glue glue =
                glueValues.isEmpty() ? null : glueValues.get(0);
            report.append("glues.count=").append(glueValues.size()).append('\n');
            if (glue == null) {
                report.append("glue.instance.status=NOT_EXERCISED\n");
            } else {
                for (int index = 0; index < glueValues.size(); index++) {
                    if (onHostThread(glueValues.get(index)::index) != index) {
                        throw new IllegalStateException("Glue index does not match stable model order at " + index + '.');
                    }
                }
                final dev.turboism.sdk.cubism.model.GlueId glueId = onHostThread(glue::id);
                appendRequiredFixedOutcome(report, "glue.id", () -> glueId);
                appendRequiredFixedOutcome(report, "glues.find", () ->
                    onHostThread(() -> glues.find(glueId).id()));
                appendRequiredFixedOutcome(report, "glue.index", () -> onHostThread(glue::index));
                appendRequiredFixedOutcome(report, "glue.drawableAId", () -> onHostThread(glue::drawableAId));
                appendRequiredFixedOutcome(report, "glue.drawableA", () -> onHostThread(glue::drawableA));
                appendRequiredFixedOutcome(report, "glue.drawableBId", () -> onHostThread(glue::drawableBId));
                appendRequiredFixedOutcome(report, "glue.drawableB", () -> onHostThread(glue::drawableB));
                appendRequiredFixedOutcome(report, "glue.parameterIds", () -> onHostThread(glue::parameterIds));
                appendRequiredFixedOutcome(report, "glue.parameters", () ->
                    onHostThread(() -> fixedInts(glue.parameters())));
            }
            phase.set("lifecycle");

            boolean modelStale;
            boolean definitionsStale;
            boolean parameterStale;
            boolean partsStale;
            boolean partStale;
            boolean drawablesStale;
            boolean drawableStale;
            boolean deformersStale;
            boolean deformerStale;
            boolean gluesStale;
            boolean glueStale;
            if (closeDocument) {
                final java.awt.Robot robot = new java.awt.Robot();
                pressShortcut(robot, java.awt.event.KeyEvent.VK_W);
                modelStale = definitionsStale = parameterStale = partsStale = partStale =
                    drawablesStale = drawableStale = deformersStale = deformerStale =
                        gluesStale = false;
                glueStale = glue == null;
                for (int attempt = 0; attempt < 60; attempt++) {
                    Thread.sleep(100L);
                    modelStale = failsStale(model::id);
                    definitionsStale = failsStale(definitions::all);
                    parameterStale = failsStale(parameter::index);
                    partsStale = failsStale(parts::all);
                    partStale = failsStale(part::parentId);
                    drawablesStale = failsStale(drawables::all);
                    drawableStale = failsStale(drawable::parameterIds);
                    deformersStale = failsStale(deformers::all);
                    deformerStale = failsStale(deformer::parameterIds);
                    gluesStale = failsStale(glues::all);
                    glueStale = glue == null || failsStale(glue::parameterIds);
                    if (modelStale && definitionsStale && parameterStale && partsStale && partStale
                        && drawablesStale && drawableStale && deformersStale && deformerStale
                        && gluesStale && glueStale) {
                        break;
                    }
                }
            } else {
                context.disposableScope().close();
                modelStale = failsStale(model::id);
                definitionsStale = failsStale(definitions::all);
                parameterStale = failsStale(parameter::index);
                partsStale = failsStale(parts::all);
                partStale = failsStale(part::parentId);
                drawablesStale = failsStale(drawables::all);
                drawableStale = failsStale(drawable::parameterIds);
                deformersStale = failsStale(deformers::all);
                deformerStale = failsStale(deformer::parameterIds);
                gluesStale = failsStale(glues::all);
                glueStale = glue == null || failsStale(glue::parameterIds);
            }
            final boolean passed = modelStale && definitionsStale && parameterStale
                && partsStale && partStale && drawablesStale && drawableStale
                && deformersStale && deformerStale && gluesStale && glueStale;
            report.append("lifecycle.phase=")
                .append(closeDocument ? "document-close" : "plugin-scope-close").append('\n')
                .append("modelStale=").append(modelStale).append('\n')
                .append("parameterDefinitionsStale=").append(definitionsStale).append('\n')
                .append("parameterStale=").append(parameterStale).append('\n')
                .append("partsStale=").append(partsStale).append('\n')
                .append("partStale=").append(partStale).append('\n')
                .append("drawablesStale=").append(drawablesStale).append('\n')
                .append("drawableStale=").append(drawableStale).append('\n')
                .append("deformersStale=").append(deformersStale).append('\n')
                .append("deformerStale=").append(deformerStale).append('\n')
                .append("gluesStale=").append(gluesStale).append('\n')
                .append("glueInstancePresent=").append(glue != null).append('\n')
                .append("glueStale=")
                .append(glue == null ? "NOT_EXERCISED" : Boolean.toString(glueStale)).append('\n');
            report.replace(0, "status=RUNNING".length(), "status=" + (passed ? "PASS" : "FAIL"));
            Files.writeString(
                artifact,
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            try {
                Files.writeString(
                    artifact,
                    "status=FAIL\nphase=" + phase.get() + "\nerror="
                        + failureDescription(exception) + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                context.logger().error("Fixed API validation artifact could not be written", exception);
            }
    }
    }

    private static void appendFixedOutcome(
        final StringBuilder report,
        final String key,
        final Callable<?> call
    ) {
        try {
            final Object value = call.call();
            report.append(key).append(".status=AVAILABLE\n")
                .append(key).append(".value=").append(fixedText(value)).append('\n');
        } catch (UnsupportedOperationException | IllegalStateException unavailable) {
            report.append(key).append(".status=UNAVAILABLE\n")
                .append(key).append(".reason=").append(fixedText(unavailable.getMessage())).append('\n');
        } catch (Exception failure) {
            report.append(key).append(".status=ERROR\n")
                .append(key).append(".reason=").append(fixedText(failureDescription(failure))).append('\n');
        }
    }

    private static void appendRequiredFixedOutcome(
        final StringBuilder report,
        final String key,
        final Callable<?> call
    ) throws Exception {
        try {
            final Object value = call.call();
            report.append(key).append(".status=AVAILABLE\n")
                .append(key).append(".value=").append(fixedText(value)).append('\n');
        } catch (Exception failure) {
            throw new IllegalStateException(
                key + " is unavailable: " + failureDescription(failure),
                failure
            );
        }
    }

    private static boolean failsStale(final Callable<?> call) {
        try {
            call.call();
            return false;
        } catch (Exception expected) {
            return expected instanceof IllegalStateException;
        }
    }

    private static String fixedText(final Object value) {
        return String.valueOf(value).replace('\r', ' ').replace('\n', ' ');
    }

    private static List<Integer> fixedInts(
        final dev.turboism.sdk.cubism.model.IntSequence values
    ) {
        return java.util.stream.IntStream.range(0, values.size())
            .map(values::get)
            .boxed()
            .toList();
    }

    private void runParameterBindingDiscoveryRead() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "binding-read-driver.txt"
        );
        try {
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Parameter parameter = onHostThread(() -> model.parameters().all().stream()
                .filter(candidate -> !candidate.getParameterBindings().isEmpty())
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "No parameter with Editor object bindings is available."
                )));
            final List<ParameterBinding> bindings = onHostThread(parameter::getParameterBindings);
            final StringBuilder report = new StringBuilder("status=PASS\n")
                .append("modelId=").append(onHostThread(() -> model.id().value())).append('\n')
                .append("parameterId=").append(onHostThread(() -> parameter.id().value())).append('\n')
                .append("bindingCount=").append(bindings.size()).append('\n');
            for (int index = 0; index < bindings.size(); index++) {
                final ParameterBinding binding = bindings.get(index);
                report.append("binding.").append(index).append(".targetType=")
                    .append(binding.target().type()).append('\n')
                    .append("binding.").append(index).append(".targetId=")
                    .append(binding.target().id()).append('\n')
                    .append("binding.").append(index).append(".family=")
                    .append(binding.family()).append('\n')
                    .append("binding.").append(index).append(".points=")
                    .append(binding.points().stream().map(point -> point.id().value() + ":" + point.value()).toList())
                    .append('\n');
            }
            Files.writeString(artifact, report.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Parameter binding discovery read failed");
        }
    }

    private void runParameterMenuSmoke() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "parameter-menu-smoke.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            java.util.Set<String> items = java.util.Set.of();
            Exception lastFailure = null;
            for (int attempt = 0; attempt < 30; attempt++) {
                try {
                    items = onHostThread(() -> {
                        final java.util.LinkedHashSet<String> found = new java.util.LinkedHashSet<>();
                        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
                            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
                            if (bar == null) continue;
                            for (int index = 0; index < bar.getMenuCount(); index++) {
                                final javax.swing.JMenu menu = bar.getMenu(index);
                                if (menu == null || !"Parameter Tools".equals(menu.getText())) continue;
                                for (java.awt.Component component : menu.getMenuComponents()) {
                                    if (component instanceof javax.swing.JMenuItem item) found.add(item.getText());
                                }
                            }
                        }
                        return java.util.Set.copyOf(found);
                    });
                    if (items.contains("Invert Bindings") && items.contains("Transfer Bindings")) break;
                } catch (Exception failure) {
                    lastFailure = failure;
                }
                Thread.sleep(1000L);
            }
            final boolean passed = items.contains("Invert Bindings") && items.contains("Transfer Bindings");
            if (!passed && lastFailure != null && items.isEmpty()) throw lastFailure;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + "\nitems=" + items + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception failure) {
            writeValidationFailure(artifact, failure, "Parameter menu smoke failed");
    }
    }
    private void runParameterBindingValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "parameter-binding-validation.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Parameter parameter = onHostThread(() -> model.parameters().all().stream()
                .filter(candidate -> candidate.getMaximumValue() > candidate.getMinimumValue())
                .findFirst().orElseThrow(() -> new IllegalStateException("No writable parameter is available.")));
            final float minimum = onHostThread(parameter::getMinimumValue);
            final float maximum = onHostThread(parameter::getMaximumValue);
            final float middle = minimum + (maximum - minimum) / 2.0F;
            final Drawable mesh = onHostThread(() -> model.drawables().all().get(0));
            final WarpDeformer warp = onHostThread(() -> model.warpDeformers().all().get(0));
            final RotationDeformer rotation = onHostThread(() -> model.rotationDeformers().all().get(0));
            final List<ParameterBindingTarget> targets = List.of(
                ParameterBindingTarget.artMesh(mesh.id()),
                ParameterBindingTarget.warpDeformer(warp.id()),
                ParameterBindingTarget.rotationDeformer(rotation.id())
            );
            final List<ParameterBindingPoint> points = List.of(
                new ParameterBindingPoint(new ParameterBindingPointId("probe:min"), minimum),
                new ParameterBindingPoint(new ParameterBindingPointId("probe:mid"), middle),
                new ParameterBindingPoint(new ParameterBindingPointId("probe:max"), maximum)
            );
            final java.awt.Robot robot = new java.awt.Robot();
            final StringBuilder report = new StringBuilder("status=RUNNING\n")
                .append("parameterId=").append(parameter.id().value()).append('\n')
                .append("meshId=").append(mesh.id().value()).append('\n')
                .append("warpId=").append(warp.id().value()).append('\n')
                .append("rotationId=").append(rotation.id().value()).append('\n')
                .append("hostThread=").append(onHostThread(() -> Thread.currentThread().getName())).append('\n');
            boolean passed = true;
            for (ParameterBindingTarget target : targets) {
                final ParameterBindingOperations operations = onHostThread(() -> model.parameterBindings(parameter.id()));
                final List<ParameterBindingPoint> originalPoints = onHostThread(() -> parameter.getParameterBindings().stream()
                    .filter(binding -> binding.target().equals(target)).findFirst()
                    .map(ParameterBinding::points).orElseGet(List::of));
                onHostThread(() -> { operations.unbind(target); return null; });
                onHostThread(() -> { operations.bind(target, points); return null; });
                final List<Float> written = bindingValues(parameter, target);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                final List<Float> undone = awaitBindingValues(parameter, target, List.of());
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
                final List<Float> redone = awaitBindingValues(parameter, target, List.of(minimum, middle, maximum));
                final ParameterBindingPointId middleId = onHostThread(() -> parameter.getParameterBindings().stream()
                    .filter(binding -> binding.target().equals(target)).findFirst().orElseThrow()
                    .points().get(1).id());
                final float movedValue = minimum + (maximum - minimum) * 0.6F;
                onHostThread(() -> { operations.movePoint(target, middleId, movedValue); return null; });
                final List<Float> moved = bindingValues(parameter, target);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                final List<Float> moveUndone = awaitBindingValues(parameter, target, List.of(minimum, middle, maximum));
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
                final List<Float> moveRedone = awaitBindingValues(parameter, target, List.of(minimum, movedValue, maximum));
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                awaitBindingValues(parameter, target, List.of(minimum, middle, maximum));
                final float createdValue = minimum + (maximum - minimum) * 0.8F;
                onHostThread(() -> {
                    operations.createPoint(target, new ParameterBindingPoint(
                        new ParameterBindingPointId("probe:create"), createdValue
                    ));
                    return null;
                });
                final List<Float> created = bindingValues(parameter, target);
                final ParameterBindingPointId createdId = onHostThread(() -> parameter.getParameterBindings().stream()
                    .filter(binding -> binding.target().equals(target)).findFirst().orElseThrow()
                    .points().stream().filter(point -> point.value() == createdValue).findFirst().orElseThrow().id());
                onHostThread(() -> { operations.deletePoint(target, createdId); return null; });
                final List<Float> deleted = bindingValues(parameter, target);
                onHostThread(() -> { operations.unbind(target); return null; });
                if (!originalPoints.isEmpty()) {
                    onHostThread(() -> { operations.bind(target, originalPoints); return null; });
                }
                final List<Float> restored = bindingValues(parameter, target);
                final boolean targetPassed = written.equals(List.of(minimum, middle, maximum))
                    && undone.isEmpty() && redone.equals(List.of(minimum, middle, maximum))
                    && moved.equals(List.of(minimum, movedValue, maximum))
                    && moveUndone.equals(List.of(minimum, middle, maximum))
                    && moveRedone.equals(List.of(minimum, movedValue, maximum))
                    && created.equals(List.of(minimum, middle, createdValue, maximum))
                    && deleted.equals(List.of(minimum, middle, maximum))
                    && restored.equals(originalPoints.stream().map(ParameterBindingPoint::value).toList());
                report.append("target.").append(target.type()).append(".written=").append(written).append('\n')
                    .append("target.").append(target.type()).append(".undo=").append(undone).append('\n')
                    .append("target.").append(target.type()).append(".redo=").append(redone).append('\n')
                    .append("target.").append(target.type()).append(".moved=").append(moved).append('\n')
                    .append("target.").append(target.type()).append(".moveUndo=").append(moveUndone).append('\n')
                    .append("target.").append(target.type()).append(".moveRedo=").append(moveRedone).append('\n')
                    .append("target.").append(target.type()).append(".created=").append(created).append('\n')
                    .append("target.").append(target.type()).append(".deleted=").append(deleted).append('\n')
                    .append("target.").append(target.type()).append(".restored=").append(restored).append('\n')
                    .append("target.").append(target.type()).append(".passed=").append(targetPassed).append('\n');
                passed &= targetPassed;
            }
            final Parameter transferTarget = onHostThread(() -> model.parameters().all().stream()
                .filter(candidate -> !candidate.id().equals(parameter.id()))
                .findFirst().orElseThrow(() -> new IllegalStateException(
                    "No destination parameter is available for binding transfer."
                )));
            final java.util.Map<ParameterBindingTarget, List<ParameterBindingPoint>> originalSource = new java.util.LinkedHashMap<>();
            final java.util.Map<ParameterBindingTarget, List<ParameterBindingPoint>> originalDestination = new java.util.LinkedHashMap<>();
            final ParameterBindingOperations sourceOperations = onHostThread(() -> model.parameterBindings(parameter.id()));
            final ParameterBindingOperations destinationOperations = onHostThread(() -> model.parameterBindings(transferTarget.id()));
            for (ParameterBindingTarget target : targets) {
                originalSource.put(target, bindingPoints(parameter, target));
                originalDestination.put(target, bindingPoints(transferTarget, target));
                onHostThread(() -> { sourceOperations.unbind(target); destinationOperations.unbind(target); return null; });
                onHostThread(() -> { sourceOperations.bind(target, points); return null; });
            }
            final var batch = onHostThread(model::parameterBindingBatch);
            final float originalValue = onHostThread(parameter::getValue);
            final java.util.Map<ParameterBindingTarget, Object> beforeMinimum = targetStates(
                model, parameter, minimum, targets, mesh, warp, rotation
            );
            final java.util.Map<ParameterBindingTarget, Object> beforeMaximum = targetStates(
                model, parameter, maximum, targets, mesh, warp, rotation
            );
            onHostThread(() -> { parameter.setValue(originalValue); return null; });
            onHostThread(() -> { batch.invert(targets); return null; });
            final java.util.Map<ParameterBindingTarget, Object> afterMinimum = targetStates(
                model, parameter, minimum, targets, mesh, warp, rotation
            );
            final java.util.Map<ParameterBindingTarget, Object> afterMaximum = targetStates(
                model, parameter, maximum, targets, mesh, warp, rotation
            );
            onHostThread(() -> { parameter.setValue(originalValue); return null; });
            final boolean batchInverted = targets.stream().allMatch(target ->
                beforeMinimum.get(target).equals(afterMaximum.get(target))
                    && beforeMaximum.get(target).equals(afterMinimum.get(target))
            );
            onHostThread(() -> { batch.invert(targets); return null; });
            final java.util.Map<ParameterBindingTarget, Object> restoredMinimum = targetStates(
                model, parameter, minimum, targets, mesh, warp, rotation
            );
            final java.util.Map<ParameterBindingTarget, Object> restoredMaximum = targetStates(
                model, parameter, maximum, targets, mesh, warp, rotation
            );
            onHostThread(() -> { parameter.setValue(originalValue); return null; });
            final boolean batchInvertUndone = targets.stream().allMatch(target ->
                beforeMinimum.get(target).equals(restoredMinimum.get(target))
                    && beforeMaximum.get(target).equals(restoredMaximum.get(target))
            );
            final boolean batchInvertRedone = batchInverted && batchInvertUndone;
            onHostThread(() -> {
                batch.transfer(new ParameterBindingTransferPlan(
                    parameter.id(), transferTarget.id(), targets, false
                ));
                return null;
            });
            final boolean batchTransferred = targets.stream().allMatch(target -> {
                try {
                    return bindingValues(parameter, target).isEmpty()
                        && bindingValues(transferTarget, target).equals(List.of(minimum, middle, maximum));
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            });
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
            final boolean batchTransferUndone = targets.stream().allMatch(target -> {
                try {
                    return awaitBindingValues(parameter, target, List.of(minimum, middle, maximum)).equals(List.of(minimum, middle, maximum))
                        && awaitBindingValues(transferTarget, target, List.of()).isEmpty();
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            });
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
            final boolean batchTransferRedone = targets.stream().allMatch(target -> {
                try {
                    return awaitBindingValues(parameter, target, List.of()).isEmpty()
                        && awaitBindingValues(transferTarget, target, List.of(minimum, middle, maximum)).equals(List.of(minimum, middle, maximum));
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            });
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
            for (ParameterBindingTarget target : targets) {
                awaitBindingValues(parameter, target, List.of(minimum, middle, maximum));
                onHostThread(() -> { sourceOperations.unbind(target); destinationOperations.unbind(target); return null; });
                if (!originalSource.get(target).isEmpty()) {
                    onHostThread(() -> { sourceOperations.bind(target, originalSource.get(target)); return null; });
                }
                if (!originalDestination.get(target).isEmpty()) {
                    onHostThread(() -> { destinationOperations.bind(target, originalDestination.get(target)); return null; });
                }
            }
            final boolean batchRestored = targets.stream().allMatch(target -> {
                try {
                    return bindingValues(parameter, target).equals(originalSource.get(target).stream().map(ParameterBindingPoint::value).toList())
                        && bindingValues(transferTarget, target).equals(originalDestination.get(target).stream().map(ParameterBindingPoint::value).toList());
                } catch (Exception exception) { throw new IllegalStateException(exception); }
            });
            final boolean batchPassed = batchInverted && batchInvertUndone && batchInvertRedone
                && batchTransferred && batchTransferUndone && batchTransferRedone && batchRestored;
            passed &= batchPassed;
            report.append("batch.destinationParameterId=").append(transferTarget.id().value()).append('\n')
                .append("batch.inverted=").append(batchInverted).append('\n')
                .append("batch.invertUndo=").append(batchInvertUndone).append('\n')
                .append("batch.invertRedo=").append(batchInvertRedone).append('\n')
                .append("batch.transferred=").append(batchTransferred).append('\n')
                .append("batch.transferUndo=").append(batchTransferUndone).append('\n')
                .append("batch.transferRedo=").append(batchTransferRedone).append('\n')
                .append("batch.restored=").append(batchRestored).append('\n')
                .append("batch.passed=").append(batchPassed).append('\n');
            report.replace(0, "status=RUNNING".length(), "status=" + (passed ? "PASS" : "FAIL"));
            Files.writeString(artifact, report.toString(), StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Parameter binding validation failed");
        }
    }

    private List<Float> bindingValues(final Parameter parameter, final ParameterBindingTarget target) throws Exception {
        return onHostThread(() -> parameter.getParameterBindings().stream()
            .filter(binding -> binding.target().equals(target))
            .findFirst().map(binding -> binding.points().stream().map(ParameterBindingPoint::value).toList())
            .orElseGet(List::of));
    }

    private List<ParameterBindingPoint> bindingPoints(
        final Parameter parameter,
        final ParameterBindingTarget target
    ) throws Exception {
        return onHostThread(() -> parameter.getParameterBindings().stream()
            .filter(binding -> binding.target().equals(target))
            .findFirst().map(ParameterBinding::points).orElseGet(List::of));
    }

    private java.util.Map<ParameterBindingTarget, Object> targetStates(
        final CubismModel model,
        final Parameter parameter,
        final float value,
        final List<ParameterBindingTarget> targets,
        final Drawable mesh,
        final WarpDeformer warp,
        final RotationDeformer rotation
    ) throws Exception {
        return onHostThread(() -> {
            parameter.setValue(value);
            final java.util.Map<ParameterBindingTarget, Object> states = new java.util.LinkedHashMap<>();
            for (ParameterBindingTarget target : targets) {
                states.put(target, switch (target.type()) {
                    case ART_MESH -> mesh.geometry();
                    case WARP_DEFORMER -> warp.grid();
                    case ROTATION_DEFORMER -> rotation.form();
                });
            }
            return java.util.Map.copyOf(states);
        });
    }

    private List<Float> awaitBindingValues(
        final Parameter parameter,
        final ParameterBindingTarget target,
        final List<Float> expected
    ) throws Exception {
        List<Float> actual = bindingValues(parameter, target);
        for (int attempt = 0; attempt < 40 && !actual.equals(expected); attempt++) {
            Thread.sleep(100L);
            actual = bindingValues(parameter, target);
        }
        return actual;
    }

    private void runEditorObjectValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "editor-object-validation.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "status=RUNNING phase=await-model\n", StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            CubismModel model = null;
            Exception unavailable = null;
            for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
                try {
                    model = onHostThread(this::activeModel);
                    final CubismModel candidate = model;
                    onHostThread(() -> {
                        if (candidate.drawables().all().isEmpty()) throw new IllegalStateException("No ArtMesh is available.");
                        if (candidate.warpDeformers().all().isEmpty()) throw new IllegalStateException("No Warp Deformer is available.");
                        if (candidate.rotationDeformers().all().isEmpty()) throw new IllegalStateException("No Rotation Deformer is available.");
                        return null;
                    });
                    break;
                } catch (Exception exception) {
                    model = null;
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
            if (model == null) throw unavailable == null
                ? new IllegalStateException("Editor object validation was interrupted.")
                : unavailable;

            final CubismModel selectedModel = model;
            final Drawable mesh = onHostThread(() -> selectedModel.drawables().all().get(0));
            final WarpDeformer warp = onHostThread(() -> selectedModel.warpDeformers().all().get(0));
            final RotationDeformer rotation = onHostThread(() -> selectedModel.rotationDeformers().all().get(0));
            final java.awt.Robot robot = new java.awt.Robot();
            editorObjectLifecycleCounts.clear();
            final StringBuilder report = new StringBuilder();
            report.append("status=RUNNING\n")
                .append("meshId=").append(mesh.id().value()).append('\n')
                .append("meshName=").append(onHostThread(mesh::name)).append('\n')
                .append("warpId=").append(warp.id().value()).append('\n')
                .append("warpName=").append(onHostThread(warp::name)).append('\n')
                .append("rotationId=").append(rotation.id().value()).append('\n')
                .append("rotationName=").append(onHostThread(rotation::name)).append('\n');

            final boolean meshOpacity = validateFloatEdit("meshOpacity", mesh::getOpacity, mesh::setOpacity, robot, report);
            final boolean meshVisible = validateBooleanEdit("meshVisible", mesh::visible, mesh::setVisible, robot, report);
            final boolean meshLocked = validateBooleanEdit("meshLocked", mesh::locked, mesh::setLocked, robot, report);
            final boolean meshGeometry = validateMeshGeometry(mesh, robot, report);

            final boolean warpOpacity = validateFloatEdit("warpOpacity", warp::getOpacity, warp::setOpacity, robot, report);
            final boolean warpVisible = validateBooleanEdit("warpVisible", warp::visible, warp::setVisible, robot, report);
            final boolean warpLocked = validateBooleanEdit("warpLocked", warp::locked, warp::setLocked, robot, report);
            final boolean warpGrid = validateWarpGrid(warp, robot, report);

            final boolean rotationOpacity = validateFloatEdit("rotationOpacity", rotation::getOpacity, rotation::setOpacity, robot, report);
            final boolean rotationVisible = validateBooleanEdit("rotationVisible", rotation::visible, rotation::setVisible, robot, report);
            final boolean rotationLocked = validateBooleanEdit("rotationLocked", rotation::locked, rotation::setLocked, robot, report);
            final boolean rotationBaseAngle = validateFloatEdit("rotationBaseAngle", rotation::baseAngle, rotation::setBaseAngle, robot, report);
            final boolean rotationForm = validateRotationForm(rotation, robot, report);

            final boolean lifecyclePassed = awaitEditorObjectLifecycle(report);
            final boolean passed = meshOpacity && meshVisible && meshLocked && meshGeometry
                && warpOpacity && warpVisible && warpLocked && warpGrid
                && rotationOpacity && rotationVisible && rotationLocked && rotationBaseAngle && rotationForm
                && lifecyclePassed;
            report.replace(0, "status=RUNNING".length(), "status=" + (passed ? "PASS" : "FAIL"));
            Files.writeString(artifact, report.toString(), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        } catch (Exception exception) {
            try {
                Files.writeString(
                    artifact,
                    "status=FAIL\nerror=" + exception.getClass().getName() + ": " + exception.getMessage() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
            } catch (Exception ignored) {
                context.logger().error("Editor object validation artifact could not be written", exception);
            }
        }
    }

    private boolean awaitEditorObjectLifecycle(final StringBuilder report) throws InterruptedException {
        final List<String> operations = List.of(
            "meshOpacity", "meshVisible", "meshLocked", "meshGeometry",
            "warpOpacity", "warpVisible", "warpLocked", "warpGrid",
            "rotationOpacity", "rotationVisible", "rotationLocked", "rotationBaseAngle", "rotationForm"
        );
        final long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(15L);
        while (System.nanoTime() < deadline) {
            if (operations.stream().allMatch(operation -> lifecycleCount(operation, "before") == 1
                && lifecycleCount(operation, "on") == 1
                && lifecycleCount(operation, "after") == 1)) {
                break;
            }
            Thread.sleep(50L);
        }
        boolean passed = true;
        for (String operation : operations) {
            final int before = lifecycleCount(operation, "before");
            final int on = lifecycleCount(operation, "on");
            final int after = lifecycleCount(operation, "after");
            final boolean operationPassed = before == 1 && on == 1 && after == 1;
            report.append(operation).append("Lifecycle.before=").append(before).append('\n')
                .append(operation).append("Lifecycle.on=").append(on).append('\n')
                .append(operation).append("Lifecycle.after=").append(after).append('\n')
                .append(operation).append("Lifecycle.passed=").append(operationPassed).append('\n');
            passed &= operationPassed;
        }
        report.append("editorObjectLifecycle.passed=").append(passed).append('\n');
        return passed;
    }

    private int lifecycleCount(final String operation, final String phase) {
        final AtomicInteger count = editorObjectLifecycleCounts.get(operation + "." + phase);
        return count == null ? 0 : count.get();
    }

    private CubismModel awaitEditorObjectModel(final Path artifact) throws Exception {
        CubismModel model = null;
        Exception unavailable = null;
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                model = onHostThread(this::activeModel);
                final CubismModel candidate = model;
                onHostThread(() -> {
                    if (candidate.drawables().all().isEmpty()) throw new IllegalStateException("No ArtMesh is available.");
                    if (candidate.warpDeformers().all().isEmpty()) throw new IllegalStateException("No Warp Deformer is available.");
                    if (candidate.rotationDeformers().all().isEmpty()) throw new IllegalStateException("No Rotation Deformer is available.");
                    return null;
                });
                return model;
            } catch (Exception exception) {
                model = null;
                unavailable = exception;
                Files.writeString(
                    artifact,
                    "status=RUNNING phase=await-model attempt=" + attempt + " error="
                        + exception.getClass().getName() + ": " + exception.getMessage() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
                Thread.sleep(1000L);
            }
        }
        throw unavailable == null
            ? new IllegalStateException("Editor object validation was interrupted.")
            : unavailable;
    }

    private void runEditorObjectPersistenceWrite() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path artifact = home.resolve("logs/editor-object-persistence-write.txt");
        final Path expectedFile = home.resolve("state/editor-object-persistence.properties");
        try {
            Files.createDirectories(artifact.getParent());
            Files.createDirectories(expectedFile.getParent());
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Drawable mesh = onHostThread(() -> model.drawables().all().get(0));
            final WarpDeformer warp = onHostThread(() -> model.warpDeformers().all().get(0));
            final RotationDeformer rotation = onHostThread(() -> model.rotationDeformers().all().get(0));

            final float meshOpacity = alternate(onHostThread(mesh::getOpacity));
            final boolean meshVisible = !onHostThread(mesh::visible);
            final boolean meshLocked = !onHostThread(mesh::locked);
            final ArtMeshGeometry meshBefore = onHostThread(mesh::geometry);
            final ArtMeshGeometry meshGeometry = changedMeshGeometry(meshBefore, 0.375F);

            final float warpOpacity = alternate(onHostThread(warp::getOpacity));
            final boolean warpVisible = !onHostThread(warp::visible);
            final boolean warpLocked = !onHostThread(warp::locked);
            final WarpGrid warpBefore = onHostThread(warp::grid);
            final WarpGrid warpGrid = changedWarpGrid(warpBefore, 0.375F);

            final float rotationOpacity = alternate(onHostThread(rotation::getOpacity));
            final boolean rotationVisible = !onHostThread(rotation::visible);
            final boolean rotationLocked = !onHostThread(rotation::locked);
            final float rotationBaseAngle = onHostThread(rotation::baseAngle) + 7.0F;
            final RotationDeformerForm rotationBefore = onHostThread(rotation::form);
            final RotationDeformerForm rotationForm = changedRotationForm(rotationBefore, 7.0F);

            onHostThread(() -> {
                mesh.setOpacity(meshOpacity); mesh.setVisible(meshVisible); mesh.setLocked(meshLocked); mesh.replaceGeometry(meshGeometry);
                warp.setOpacity(warpOpacity); warp.setVisible(warpVisible); warp.setLocked(warpLocked); warp.replaceGrid(warpGrid);
                rotation.setOpacity(rotationOpacity); rotation.setVisible(rotationVisible); rotation.setLocked(rotationLocked);
                rotation.setBaseAngle(rotationBaseAngle); rotation.replaceForm(rotationForm);
                return null;
            });

            final Properties expected = new Properties();
            expected.setProperty("mesh.id", mesh.id().value());
            expected.setProperty("mesh.opacity", Float.toString(meshOpacity));
            expected.setProperty("mesh.visible", Boolean.toString(meshVisible));
            expected.setProperty("mesh.locked", Boolean.toString(meshLocked));
            expected.setProperty("mesh.geometry", meshGeometry.toString());
            expected.setProperty("warp.id", warp.id().value());
            expected.setProperty("warp.opacity", Float.toString(warpOpacity));
            expected.setProperty("warp.visible", Boolean.toString(warpVisible));
            expected.setProperty("warp.locked", Boolean.toString(warpLocked));
            expected.setProperty("warp.grid", warpGrid.toString());
            expected.setProperty("rotation.id", rotation.id().value());
            expected.setProperty("rotation.opacity", Float.toString(rotationOpacity));
            expected.setProperty("rotation.visible", Boolean.toString(rotationVisible));
            expected.setProperty("rotation.locked", Boolean.toString(rotationLocked));
            expected.setProperty("rotation.baseAngle", Float.toString(rotationBaseAngle));
            expected.setProperty("rotation.form", rotationForm.toString());
            try (java.io.OutputStream output = Files.newOutputStream(expectedFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING)) {
                expected.store(output, "Turboism editor object persistence expectations");
            }

            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_S);
            Thread.sleep(2500L);
            Files.writeString(
                artifact,
                "status=PASS\nphase=saved\nmeshId=" + mesh.id().value()
                    + "\nwarpId=" + warp.id().value()
                    + "\nrotationId=" + rotation.id().value() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Editor object persistence write artifact could not be written");
        }
    }

    private void runEditorObjectPersistenceRead() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path artifact = home.resolve("logs/editor-object-persistence-read.txt");
        final Path expectedFile = home.resolve("state/editor-object-persistence.properties");
        try {
            final Properties expected = new Properties();
            try (java.io.InputStream input = Files.newInputStream(expectedFile)) { expected.load(input); }
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Drawable mesh = onHostThread(() -> model.drawables().find(new dev.turboism.sdk.cubism.id.ArtMeshId(expected.getProperty("mesh.id"))));
            final WarpDeformer warp = onHostThread(() -> model.warpDeformers().find(new dev.turboism.sdk.cubism.id.DeformerId(expected.getProperty("warp.id"))));
            final RotationDeformer rotation = onHostThread(() -> model.rotationDeformers().find(new dev.turboism.sdk.cubism.id.DeformerId(expected.getProperty("rotation.id"))));
            final ArtMeshGeometry meshGeometry = onHostThread(mesh::geometry);
            final WarpGrid warpGrid = onHostThread(warp::grid);
            final RotationDeformerForm rotationForm = onHostThread(rotation::form);
            final boolean passed =
                same(expected, "mesh.opacity", onHostThread(mesh::getOpacity))
                && same(expected, "mesh.visible", onHostThread(mesh::visible))
                && same(expected, "mesh.locked", onHostThread(mesh::locked))
                && expected.getProperty("mesh.geometry").equals(meshGeometry.toString())
                && same(expected, "warp.opacity", onHostThread(warp::getOpacity))
                && same(expected, "warp.visible", onHostThread(warp::visible))
                && same(expected, "warp.locked", onHostThread(warp::locked))
                && expected.getProperty("warp.grid").equals(warpGrid.toString())
                && same(expected, "rotation.opacity", onHostThread(rotation::getOpacity))
                && same(expected, "rotation.visible", onHostThread(rotation::visible))
                && same(expected, "rotation.locked", onHostThread(rotation::locked))
                && same(expected, "rotation.baseAngle", onHostThread(rotation::baseAngle))
                && expected.getProperty("rotation.form").equals(rotationForm.toString());
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + "\nphase=reopened\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Editor object persistence read artifact could not be written");
        }
    }

    private void runEditorObjectPluginScopeClose() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "editor-object-plugin-scope-close.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Drawable mesh = onHostThread(() -> model.drawables().all().get(0));
            final WarpDeformer warp = onHostThread(() -> model.warpDeformers().all().get(0));
            final RotationDeformer rotation = onHostThread(() -> model.rotationDeformers().all().get(0));
            context.disposableScope().close();
            final boolean modelStale = failsClosed(model::id);
            final boolean meshStale = failsClosed(mesh::geometry);
            final boolean warpStale = failsClosed(warp::grid);
            final boolean rotationStale = failsClosed(rotation::form);
            final Path peerRequest = Path.of(
                System.getProperty("turboism.home"), "state", "editor-object-peer-request.txt"
            );
            final Path peerArtifact = Path.of(
                System.getProperty("turboism.home"), "logs", "editor-object-peer-scope-close.txt"
            );
            Files.createDirectories(peerRequest.getParent());
            Files.deleteIfExists(peerArtifact);
            Files.writeString(
                peerRequest,
                "primaryScopeClosed=true\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            String peerEvidence = "";
            for (int attempt = 0; attempt < 3_000; attempt++) {
                peerEvidence = Files.exists(peerArtifact) ? Files.readString(peerArtifact) : "";
                if (peerEvidence.contains("status=PASS") || peerEvidence.contains("status=FAIL")) break;
                Thread.sleep(100L);
            }
            final boolean secondPluginUsable = peerEvidence.contains("status=PASS")
                && peerEvidence.contains("secondPluginUsable=true");
            final boolean passed = modelStale && meshStale && warpStale && rotationStale && secondPluginUsable;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL")
                    + "\nphase=plugin-scope-close"
                    + "\nmodelStale=" + modelStale
                    + "\nmeshStale=" + meshStale
                    + "\nwarpStale=" + warpStale
                    + "\nrotationStale=" + rotationStale
                    + "\nsharedHostActive=true"
                    + "\nsecondPluginUsable=" + secondPluginUsable + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Editor object plugin-scope lifecycle artifact could not be written");
        }
    }

    private void runEditorObjectDocumentClose() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "editor-object-document-close.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            final CubismModel model = awaitEditorObjectModel(artifact);
            final Drawable mesh = onHostThread(() -> model.drawables().all().get(0));
            final WarpDeformer warp = onHostThread(() -> model.warpDeformers().all().get(0));
            final RotationDeformer rotation = onHostThread(() -> model.rotationDeformers().all().get(0));
            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_W);
            boolean modelStale = false;
            boolean meshStale = false;
            boolean warpStale = false;
            boolean rotationStale = false;
            for (int attempt = 0; attempt < 60 && !(modelStale && meshStale && warpStale && rotationStale); attempt++) {
                Thread.sleep(100L);
                modelStale = failsClosed(model::id);
                meshStale = failsClosed(mesh::geometry);
                warpStale = failsClosed(warp::grid);
                rotationStale = failsClosed(rotation::form);
            }
            final boolean passed = modelStale && meshStale && warpStale && rotationStale;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL")
                    + "\nphase=document-close"
                    + "\nmodelStale=" + modelStale
                    + "\nmeshStale=" + meshStale
                    + "\nwarpStale=" + warpStale
                    + "\nrotationStale=" + rotationStale + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(artifact, exception, "Editor object document-close lifecycle artifact could not be written");
        }
    }

    private static boolean failsClosed(final Callable<?> call) {
        try {
            call.call();
            return false;
        } catch (Exception expected) {
            return expected instanceof IllegalStateException
                || expected instanceof UnsupportedOperationException;
        }
    }

    private static float alternate(final float before) {
        return Float.compare(before, 0.625F) == 0 ? 0.75F : 0.625F;
    }

    private static boolean same(final Properties expected, final String key, final float actual) {
        return Float.compare(Float.parseFloat(expected.getProperty(key)), actual) == 0;
    }

    private static boolean same(final Properties expected, final String key, final boolean actual) {
        return Boolean.parseBoolean(expected.getProperty(key)) == actual;
    }

    private void writeValidationFailure(final Path artifact, final Exception exception, final String logMessage) {
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=FAIL\nerror=" + exception.getClass().getName() + ": " + exception.getMessage() + "\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception ignored) {
            context.logger().error(logMessage, exception);
        }
    }

    private void finishAutomatedValidation(final String mode, final long startedNanos) {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path logs = home.resolve("logs");
        final Path result = home.resolve("state/host-validation-result.properties");
        boolean passed = false;
        try {
            Files.createDirectories(result.getParent());
            final java.util.List<Path> artifacts;
            try (var files = Files.list(logs)) {
                artifacts = files
                    .filter(Files::isRegularFile)
                    .filter(path -> {
                        final String name = path.getFileName().toString();
                        return name.endsWith(".txt")
                            && (name.contains("validation")
                                || name.contains("smoke")
                                || name.startsWith("native-control-background-"));
                    })
                    .sorted()
                    .toList();
            }

            final StringBuilder report = new StringBuilder()
                .append("schemaVersion=1\n")
                .append("runId=")
                .append(System.getProperty("turboism.validation.runId", "unknown"))
                .append('\n')
                .append("mode=").append(mode).append('\n')
                .append("durationMillis=")
                .append((System.nanoTime() - startedNanos) / 1_000_000L)
                .append('\n')
                .append("artifactCount=").append(artifacts.size()).append('\n');
            passed = !artifacts.isEmpty();
            for (int index = 0; index < artifacts.size(); index++) {
                final Path artifact = artifacts.get(index);
                final Properties properties = new Properties();
                try (var input = Files.newInputStream(artifact)) {
                    properties.load(input);
                }
                final String rawStatus = properties.getProperty("status", "MISSING");
                final String status = rawStatus.split("\\s+", 2)[0];
                report.append("artifact.").append(index).append(".path=")
                    .append(artifact.getFileName()).append('\n')
                    .append("artifact.").append(index).append(".status=")
                    .append(status).append('\n');
                passed &= "PASS".equals(status);
            }
            report.append("status=").append(passed ? "PASS" : "FAIL").append('\n');
            Files.writeString(
                result,
                report.toString(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            context.logger().info("HOST_VALIDATION_RESULT status=" + (passed ? "PASS" : "FAIL")
                + " mode=" + mode + " result=" + result);
        } catch (Exception exception) {
            writeValidationFailure(result, exception, "Host validation summary could not be written");
            context.logger().error("HOST_VALIDATION_RESULT status=FAIL mode=" + mode, exception);
        } finally {
            requestAutomatedHostClose();
        }
    }

    private enum CloseDialogHandling {
        DISCARDED,
        UNSUPPORTED
    }

    private record CloseDialogState(
        java.awt.Dialog dialog,
        JOptionPane optionPane,
        List<JButton> buttons
    ) {
        String description() {
            return "window=" + dialog.getClass().getName()
                + " optionType=" + (optionPane == null ? "none" : optionPane.getOptionType())
                + " buttonMetadata=" + buttons.stream()
                    .map(WindowsParameterValidationProbe::buttonMetadata)
                    .toList();
        }
    }

    private void requestAutomatedHostClose() {
        try {
            final java.awt.Window target = onHostThread(
                () -> selectHostWindow(java.awt.Window.getWindows())
            );
            final HostCloseRoute route = hostCloseRoute(
                System.getProperty("turboism.validation.hostVersion")
            );
            if (route == HostCloseRoute.ROBOT_ALT_F4) {
                pressAltF4(new java.awt.Robot());
                context.logger().info("Automated host close requested via Alt+F4");
            } else {
                SwingUtilities.invokeLater(() -> target.dispatchEvent(new java.awt.event.WindowEvent(
                    target, java.awt.event.WindowEvent.WINDOW_CLOSING
                )));
                context.logger().info("Automated host close requested via WINDOW_CLOSING");
            }
            final HostCloseDecision decision = awaitHostCloseConfirmation(target);
            if (decision == HostCloseDecision.CLEAN_CLOSE) {
                context.logger().info(
                    "Automated host close requested; no unsaved confirmation dialog observed"
                );
            } else if (decision == HostCloseDecision.DISCARD) {
                context.logger().info(
                    "Automated host close discarded unsaved document changes"
                );
            }
        } catch (Exception failure) {
            context.logger().error("Automated host close request or confirmation handling failed", failure);
        }
    }

    private static void pressAltF4(final java.awt.Robot robot) {
        robot.keyPress(java.awt.event.KeyEvent.VK_ALT);
        try {
            robot.keyPress(java.awt.event.KeyEvent.VK_F4);
        } finally {
            robot.keyRelease(java.awt.event.KeyEvent.VK_F4);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ALT);
        }
    }

    static java.awt.Window selectHostWindow(final java.awt.Window[] windows) {
        java.awt.Window target = null;
        long largestArea = -1L;
        for (final java.awt.Window window : windows) {
            if (window instanceof java.awt.Dialog
                || !window.isDisplayable()
                || !window.isVisible()) {
                continue;
            }
            final long area = (long) window.getWidth() * window.getHeight();
            if (area > largestArea) {
                target = window;
                largestArea = area;
            }
        }
        if (target == null) {
            throw new IllegalStateException(
                "No visible, displayable non-dialog host window found."
            );
        }
        return target;
    }

    private static boolean hostWindowClosed(final java.awt.Window window) throws Exception {
        return onHostThread(() -> !window.isDisplayable() || !window.isVisible());
    }

    private HostCloseDecision awaitHostCloseConfirmation(
        final java.awt.Window hostWindow
    ) throws Exception {
        final long deadlineNanos = System.nanoTime() + 3_000_000_000L;
        CloseDialogState observed = null;
        while (System.nanoTime() < deadlineNanos) {
            final CloseDialogState current = visibleCloseDialog();
            if (current == null) {
                Thread.sleep(100L);
                continue;
            }
            observed = current;
            final CloseDialogHandling handling = handleCloseDialog(current);
            if (handling == CloseDialogHandling.UNSUPPORTED) {
                throw new IllegalStateException(
                    "Unsaved confirmation could not be handled: " + current.description()
                );
            }
            boolean dialogClosed = false;
            while (System.nanoTime() < deadlineNanos) {
                dialogClosed = visibleCloseDialog() == null;
                if (dialogClosed && hostWindowClosed(hostWindow)) {
                    return HostCloseDecision.DISCARD;
                }
                Thread.sleep(100L);
            }
            throw new IllegalStateException(
                "Unsaved confirmation did not close the host window after discard: "
                    + observed.description() + " dialogClosed=" + dialogClosed
            );
        }
        if (!hostWindowClosed(hostWindow)) {
            throw new IllegalStateException(
                "Host window remained open without an unsaved confirmation."
            );
        }
        return HostCloseDecision.CLEAN_CLOSE;
    }

    private static CloseDialogHandling handleCloseDialog(
        final CloseDialogState state
    ) throws Exception {
        final JOptionPane optionPane = state.optionPane();
        final int optionType = optionPane == null
            ? JOptionPane.DEFAULT_OPTION : optionPane.getOptionType();
        final HostCloseDecision decision = hostCloseDecision(
            true, optionType, state.buttons().size()
        );
        if (decision == HostCloseDecision.UNSUPPORTED_CONFIRMATION) {
            return CloseDialogHandling.UNSUPPORTED;
        }
        final JButton discard = selectDiscardButton(state.buttons());
        if (discard == null) {
            return CloseDialogHandling.UNSUPPORTED;
        }
        SwingUtilities.invokeAndWait(discard::doClick);
        return CloseDialogHandling.DISCARDED;
    }

    private static CloseDialogState visibleCloseDialog() throws Exception {
        final AtomicReference<CloseDialogState> result = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            final java.awt.Window active =
                java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow();
            for (final java.awt.Window window : java.awt.Window.getWindows()) {
                if (!(window instanceof java.awt.Dialog dialog)
                    || !dialog.isVisible()
                    || (!dialog.isModal() && window != active)) {
                    continue;
                }
                final JOptionPane optionPane = findOptionPane(window);
                result.set(new CloseDialogState(dialog, optionPane, visibleButtons(window)));
                return;
            }
        });
        return result.get();
    }

    private static JOptionPane findOptionPane(final java.awt.Component component) {
        if (component instanceof JOptionPane optionPane) {
            return optionPane;
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                final JOptionPane found = findOptionPane(child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<JButton> visibleButtons(final java.awt.Component component) {
        final List<JButton> buttons = new java.util.ArrayList<>();
        collectButtons(component, buttons);
        return buttons.stream()
            .filter(button -> button.isVisible() && button.isEnabled())
            .toList();
    }

    /** Selects exactly one semantic discard action; ambiguity fails closed. */
    static JButton selectDiscardButton(final List<JButton> buttons) {
        final List<JButton> matches = buttons.stream()
            .filter(WindowsParameterValidationProbe::isDiscardAction)
            .toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static boolean isDiscardAction(final JButton button) {
        return matchesDiscardValue(button.getActionCommand())
            || matchesDiscardValue(button.getName())
            || matchesDiscardValue(button.getText())
            || matchesDiscardValue(accessibleName(button))
            || matchesNoButtonValue(button.getActionCommand())
            || matchesNoButtonValue(button.getName())
            || matchesNoButtonValue(button.getText())
            || matchesNoButtonValue(accessibleName(button));
    }

    private static String accessibleName(final JButton button) {
        final var context = button.getAccessibleContext();
        return context == null ? null : context.getAccessibleName();
    }

    private static boolean matchesDiscardValue(final String value) {
        if (value == null || value.isBlank()) {
            return false;
        }
        final String normalized = value.toLowerCase(Locale.ROOT)
            .replaceAll("[^\\p{L}\\p{N}]", "");
        return normalized.equals("no")
            || normalized.contains("discard")
            || normalized.contains("dontsave")
            || normalized.contains("donotsave")
            || normalized.contains("nosave")
            || normalized.contains("notsave")
            || normalized.contains("不保存")
            || normalized.contains("不要保存")
            || normalized.contains("不儲存")
            || normalized.contains("不要儲存")
            || normalized.contains("不存檔")
            || normalized.contains("不要存檔")
            || normalized.contains("放弃")
            || normalized.contains("放棄")
            || normalized.contains("舍弃")
            || normalized.contains("捨棄")
            || normalized.contains("保存しない")
            || normalized.contains("セーブしない");
    }

    private static boolean matchesNoButtonValue(final String value) {
        return value != null
            && value.strip().matches("(?i)no\\s*\\(\\s*[_&]?n\\s*\\)");
    }

    private static String buttonMetadata(final JButton button) {
        return "{class=" + button.getClass().getName()
            + ", text=" + button.getText()
            + ", action=" + button.getActionCommand()
            + ", name=" + button.getName()
            + ", accessible=" + accessibleName(button) + '}';
    }

    private static void collectButtons(
        final java.awt.Component component,
        final List<JButton> buttons
    ) {
        if (component instanceof JButton button) {
            buttons.add(button);
        }
        if (component instanceof java.awt.Container container) {
            for (final java.awt.Component child : container.getComponents()) {
                collectButtons(child, buttons);
            }
        }
    }

    @FunctionalInterface
    private interface FloatWriter { void set(float value); }

    @FunctionalInterface
    private interface BooleanWriter { void set(boolean value); }

    private boolean validateFloatEdit(
        final String label,
        final Callable<Float> reader,
        final FloatWriter writer,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final float before = onHostThread(reader);
        final float written = Float.compare(before, 0.625F) == 0 ? 0.75F : 0.625F;
        onHostThread(() -> { writer.set(written); return null; });
        final float afterWrite = onHostThread(reader);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final float afterUndo = awaitValue(reader, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final float afterRedo = awaitValue(reader, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final float restored = awaitValue(reader, before);
        final boolean passed = Float.compare(afterWrite, written) == 0
            && Float.compare(afterUndo, before) == 0
            && Float.compare(afterRedo, written) == 0
            && Float.compare(restored, before) == 0;
        appendMatrix(report, label, before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private boolean validateBooleanEdit(
        final String label,
        final Callable<Boolean> reader,
        final BooleanWriter writer,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final boolean before = onHostThread(reader);
        final boolean written = !before;
        onHostThread(() -> { writer.set(written); return null; });
        final boolean afterWrite = onHostThread(reader);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final boolean afterUndo = awaitValue(reader, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final boolean afterRedo = awaitValue(reader, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final boolean restored = awaitValue(reader, before);
        final boolean passed = afterWrite == written && afterUndo == before && afterRedo == written && restored == before;
        appendMatrix(report, label, before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private <T> boolean validateValueEdit(
        final String label,
        final Callable<T> reader,
        final java.util.function.Consumer<T> writer,
        final T written,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final T before = onHostThread(reader);
        onHostThread(() -> { writer.accept(written); return null; });
        final T afterWrite = onHostThread(reader);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final T afterUndo = awaitValue(reader, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final T afterRedo = awaitValue(reader, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final T restored = awaitValue(reader, before);
        final boolean passed = Objects.equals(written, afterWrite)
            && Objects.equals(before, afterUndo)
            && Objects.equals(written, afterRedo)
            && Objects.equals(before, restored);
        appendMatrix(report, label, before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private boolean validateMeshGeometry(
        final Drawable mesh,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final ArtMeshGeometry before = onHostThread(mesh::geometry);
        final ArtMeshGeometry written = changedMeshGeometry(before, 0.25F);
        onHostThread(() -> { mesh.replaceGeometry(written); return null; });
        final ArtMeshGeometry afterWrite = onHostThread(mesh::geometry);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final ArtMeshGeometry afterUndo = awaitValue(mesh::geometry, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final ArtMeshGeometry afterRedo = awaitValue(mesh::geometry, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final ArtMeshGeometry restored = awaitValue(mesh::geometry, before);
        final boolean passed = written.equals(afterWrite) && before.equals(afterUndo)
            && written.equals(afterRedo) && before.equals(restored);
        appendMatrix(report, "meshGeometry", before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private boolean validateWarpGrid(
        final WarpDeformer warp,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final WarpGrid before = onHostThread(warp::grid);
        final WarpGrid written = changedWarpGrid(before, 0.25F);
        onHostThread(() -> { warp.replaceGrid(written); return null; });
        final WarpGrid afterWrite = onHostThread(warp::grid);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final WarpGrid afterUndo = awaitValue(warp::grid, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final WarpGrid afterRedo = awaitValue(warp::grid, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final WarpGrid restored = awaitValue(warp::grid, before);
        final boolean passed = written.equals(afterWrite) && before.equals(afterUndo)
            && written.equals(afterRedo) && before.equals(restored);
        appendMatrix(report, "warpGrid", before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private boolean validateRotationForm(
        final RotationDeformer rotation,
        final java.awt.Robot robot,
        final StringBuilder report
    ) throws Exception {
        final RotationDeformerForm before = onHostThread(rotation::form);
        final RotationDeformerForm written = changedRotationForm(before, 5.0F);
        onHostThread(() -> { rotation.replaceForm(written); return null; });
        final RotationDeformerForm afterWrite = onHostThread(rotation::form);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final RotationDeformerForm afterUndo = awaitValue(rotation::form, before);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        final RotationDeformerForm afterRedo = awaitValue(rotation::form, written);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        final RotationDeformerForm restored = awaitValue(rotation::form, before);
        final boolean passed = written.equals(afterWrite) && before.equals(afterUndo)
            && written.equals(afterRedo) && before.equals(restored);
        appendMatrix(report, "rotationForm", before, written, afterWrite, afterUndo, afterRedo, restored, passed);
        return passed;
    }

    private static ArtMeshGeometry changedMeshGeometry(
        final ArtMeshGeometry before,
        final float delta
    ) {
        final java.util.ArrayList<Point2> positions = new java.util.ArrayList<>(before.positions());
        final Point2 firstPosition = positions.get(0);
        positions.set(0, new Point2(firstPosition.x() + delta, firstPosition.y() + delta));
        final java.util.ArrayList<Point2> uvs = new java.util.ArrayList<>(before.uvs());
        final Point2 firstUv = uvs.get(0);
        final float changedU = firstUv.x() <= 0.5F ? firstUv.x() + 0.125F : firstUv.x() - 0.125F;
        final float changedV = firstUv.y() <= 0.5F ? firstUv.y() + 0.125F : firstUv.y() - 0.125F;
        uvs.set(0, new Point2(changedU, changedV));
        final java.util.ArrayList<Integer> indices = new java.util.ArrayList<>(before.triangleIndices());
        if (indices.size() < 3 || Objects.equals(indices.get(0), indices.get(1))) {
            throw new IllegalStateException("ArtMesh fixture must contain a non-degenerate triangle.");
        }
        final Integer firstIndex = indices.get(0);
        indices.set(0, indices.get(1));
        indices.set(1, firstIndex);
        return new ArtMeshGeometry(positions, uvs, indices);
    }

    private static WarpGrid changedWarpGrid(final WarpGrid before, final float delta) {
        final int rows = before.rows() + 1;
        final int columns = before.columns() + 1;
        final java.util.ArrayList<Point2> points = new java.util.ArrayList<>((rows + 1) * (columns + 1));
        for (int row = 0; row <= rows; row++) {
            final float sourceRow = ((float) row / rows) * before.rows();
            for (int column = 0; column <= columns; column++) {
                final float sourceColumn = ((float) column / columns) * before.columns();
                final Point2 sampled = sampleWarpPoint(before, sourceRow, sourceColumn);
                final boolean first = row == 0 && column == 0;
                points.add(first
                    ? new Point2(sampled.x() + delta, sampled.y() + delta)
                    : sampled);
            }
        }
        return new WarpGrid(rows, columns, !before.quadTransform(), points);
    }

    private static Point2 sampleWarpPoint(
        final WarpGrid grid,
        final float sourceRow,
        final float sourceColumn
    ) {
        final int row0 = Math.min((int) Math.floor(sourceRow), grid.rows());
        final int column0 = Math.min((int) Math.floor(sourceColumn), grid.columns());
        final int row1 = Math.min(row0 + 1, grid.rows());
        final int column1 = Math.min(column0 + 1, grid.columns());
        final float rowWeight = sourceRow - row0;
        final float columnWeight = sourceColumn - column0;
        final Point2 topLeft = warpPoint(grid, row0, column0);
        final Point2 topRight = warpPoint(grid, row0, column1);
        final Point2 bottomLeft = warpPoint(grid, row1, column0);
        final Point2 bottomRight = warpPoint(grid, row1, column1);
        final float topX = topLeft.x() + (topRight.x() - topLeft.x()) * columnWeight;
        final float topY = topLeft.y() + (topRight.y() - topLeft.y()) * columnWeight;
        final float bottomX = bottomLeft.x() + (bottomRight.x() - bottomLeft.x()) * columnWeight;
        final float bottomY = bottomLeft.y() + (bottomRight.y() - bottomLeft.y()) * columnWeight;
        return new Point2(
            topX + (bottomX - topX) * rowWeight,
            topY + (bottomY - topY) * rowWeight
        );
    }

    private static Point2 warpPoint(final WarpGrid grid, final int row, final int column) {
        return grid.controlPoints().get(row * (grid.columns() + 1) + column);
    }

    private static RotationDeformerForm changedRotationForm(
        final RotationDeformerForm before,
        final float angleDelta
    ) {
        return new RotationDeformerForm(
            before.angle() + angleDelta,
            before.originX() + 0.25F,
            before.originY() - 0.25F,
            before.scale() * 1.125F,
            !before.reflectedX(),
            !before.reflectedY()
        );
    }

    private <T> T awaitValue(final Callable<T> reader, final T expected) throws Exception {
        T actual = onHostThread(reader);
        for (int attempt = 0; attempt < 40 && !Objects.equals(expected, actual); attempt++) {
            Thread.sleep(100L);
            actual = onHostThread(reader);
        }
        return actual;
    }

    private static void appendMatrix(
        final StringBuilder report,
        final String label,
        final Object before,
        final Object written,
        final Object afterWrite,
        final Object afterUndo,
        final Object afterRedo,
        final Object restored,
        final boolean passed
    ) {
        report.append(label).append(".status=").append(passed ? "PASS" : "FAIL").append('\n')
            .append(label).append(".before=").append(before).append('\n')
            .append(label).append(".written=").append(written).append('\n')
            .append(label).append(".afterWrite=").append(afterWrite).append('\n')
            .append(label).append(".afterUndo=").append(afterUndo).append('\n')
            .append(label).append(".afterRedo=").append(afterRedo).append('\n')
            .append(label).append(".restored=").append(restored).append('\n');
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
            final String afterNameUndo = awaitValue(selectedPart::name, partName);
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
            final String afterNameRedo = awaitValue(selectedPart::name, writtenName);
            pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
            final String restoredName = awaitValue(selectedPart::name, partName);

            final StringBuilder basicSettings = new StringBuilder();
            final Optional<String> currentShortName = onHostThread(selectedPart::shortName);
            final Optional<String> writtenShortName = Optional.of(
                currentShortName.equals(Optional.of("Turboism")) ? "Turboism 2" : "Turboism"
            );
            final boolean shortNamePassed = validateValueEdit(
                "shortName", selectedPart::shortName, selectedPart::setShortName,
                writtenShortName, robot, basicSettings
            );
            final boolean visiblePassed = validateBooleanEdit(
                "visible", selectedPart::visible, selectedPart::setVisible, robot, basicSettings
            );
            final boolean lockedPassed = validateBooleanEdit(
                "locked", selectedPart::locked, selectedPart::setLocked, robot, basicSettings
            );
            final Optional<Color> currentEditColor = onHostThread(selectedPart::editColor);
            final Color firstColor = new Color(32F / 255F, 64F / 255F, 128F / 255F, 1F);
            final Optional<Color> writtenEditColor = Optional.of(
                currentEditColor.equals(Optional.of(firstColor))
                    ? new Color(128F / 255F, 64F / 255F, 32F / 255F, 1F)
                    : firstColor
            );
            final boolean editColorPassed = validateValueEdit(
                "editColor", selectedPart::editColor, selectedPart::setEditColor,
                writtenEditColor, robot, basicSettings
            );
            final boolean sketchPassed = validateBooleanEdit(
                "sketch", selectedPart::sketch, selectedPart::setSketch, robot, basicSettings
            );
            final int currentDefaultOrder = onHostThread(selectedPart::defaultOrder);
            final int writtenDefaultOrder = currentDefaultOrder == 1 ? 2 : 1;
            final boolean defaultOrderPassed = validateValueEdit(
                "defaultOrder", selectedPart::defaultOrder, selectedPart::setDefaultOrder,
                writtenDefaultOrder, robot, basicSettings
            );

            final float written = Float.compare(before, 0.625F) == 0 ? 0.75F : 0.625F;
            Float afterWrite = null;
            Float afterUndo = null;
            Float afterRedo = null;
            Float restoredOpacity = null;
            String opacityWriteDisposition = "supported";
            boolean opacityPassed;
            try {
                onHostThread(() -> { selectedPart.setOpacity(written); return null; });
                Files.writeString(artifact, "status=RUNNING phase=after-write\n", StandardOpenOption.TRUNCATE_EXISTING);
                afterWrite = onHostThread(selectedPart::getOpacity);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                afterUndo = awaitValue(selectedPart::getOpacity, before);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
                afterRedo = awaitValue(selectedPart::getOpacity, written);
                pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
                restoredOpacity = awaitValue(selectedPart::getOpacity, before);
                opacityPassed = Float.compare(afterWrite, written) == 0
                    && Float.compare(afterUndo, before) == 0
                    && Float.compare(afterRedo, written) == 0
                    && Float.compare(restoredOpacity, before) == 0;
            } catch (UnsupportedOperationException unsupported) {
                opacityWriteDisposition = "unsupported-fail-closed";
                afterWrite = onHostThread(selectedPart::getOpacity);
                restoredOpacity = afterWrite;
                opacityPassed = Float.compare(afterWrite, before) == 0;
            }
            final boolean passed = writtenName.equals(afterNameWrite)
                && partName.equals(afterNameUndo)
                && writtenName.equals(afterNameRedo)
                && partName.equals(restoredName)
                && shortNamePassed && visiblePassed && lockedPassed && editColorPassed
                && sketchPassed && defaultOrderPassed && opacityPassed;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + System.lineSeparator()
                    + "partId=" + part.id().value() + System.lineSeparator()
                    + "partIndex=" + part.index() + System.lineSeparator()
                    + "partParentIndex=" + part.parentIndex() + System.lineSeparator()
                    + "partName=" + partName + System.lineSeparator()
                    + "writtenName=" + writtenName + System.lineSeparator()
                    + "afterNameWrite=" + afterNameWrite + System.lineSeparator()
                    + "afterNameUndo=" + afterNameUndo + System.lineSeparator()
                    + "afterNameRedo=" + afterNameRedo + System.lineSeparator()
                    + "restoredName=" + restoredName + System.lineSeparator()
                    + basicSettings
                    + "before=" + before + System.lineSeparator()
                    + "opacityWriteDisposition=" + opacityWriteDisposition + System.lineSeparator()
                    + "written=" + written + System.lineSeparator()
                    + "afterWrite=" + afterWrite + System.lineSeparator()
                    + "afterUndo=" + afterUndo + System.lineSeparator()
                    + "afterRedo=" + afterRedo + System.lineSeparator()
                    + "restoredOpacity=" + restoredOpacity + System.lineSeparator(),
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

    /**
     * Dedicated exact-mode validation of Editor-native control label colors through the
     * model object's {@code .ui()} facade. Writes the machine-readable matrix to
     * {@code <turboism.home>/logs/native-control-background-validation.txt}.
     */
    private void runNativeLabelColorValidation() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "native-control-background-validation.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitNativeLabelColorModel(artifact);
            final ParameterGroup folder = firstNonRootParameterGroup(model);
            final Part part = firstNonRootPart(model);
            final Deformer deformer = firstDeformer(model);
            final String modelId = onHostThread(() -> model.id().value());
            final String hostThread = onHostThread(() -> Thread.currentThread().getName());

            final NativeLabelColorState folderOriginal = onHostThread(() -> nativeLabelColor(folder));
            final NativeLabelColorState partOriginal = onHostThread(() -> nativeLabelColor(part));
            final NativeLabelColorState deformerOriginal = onHostThread(() -> nativeLabelColor(deformer));
            final StringBuilder report = new StringBuilder();
            final StringBuilder restoreReport = new StringBuilder();
            final StringBuilder scopeReport = new StringBuilder();
            final AtomicBoolean restoreFailed = new AtomicBoolean();
            boolean folderPassed = false;
            boolean partPassed = false;
            boolean deformerPassed = false;
            try {
                final NativeLabelColor customRequested = chooseCustomCandidate(folderOriginal);
                final MatrixValues folderMatrix = runBackgroundMatrix(
                    () -> nativeLabelColor(folder), color -> setNativeLabelColor(folder, color), customRequested
                );
                folderPassed = folderMatrix.passed();
                appendBackgroundReport(
                    report, "parameterFolder", folder.id().value(), modelId, hostThread, folderMatrix, folderPassed
                );

                final NativeLabelColor partRequested = presetDifferentFrom(partOriginal.labelColor());
                final MatrixValues partMatrix = runBackgroundMatrix(
                    () -> nativeLabelColor(part), color -> setNativeLabelColor(part, color), partRequested
                );
                partPassed = partMatrix.passed();
                appendBackgroundReport(
                    report, "part", part.id().value(), modelId, hostThread, partMatrix, partPassed
                );

                report.append("deformer.original.background=")
                    .append(backgroundText(deformerOriginal.labelColor())).append('\n')
                    .append("deformer.original.effective=")
                    .append(effectiveText(deformerOriginal.actualColor())).append('\n');
                NativeLabelColor matrixBefore = deformerOriginal.labelColor();
                if (matrixBefore instanceof NativeLabelColor.Default) {
                    final NativeLabelColor establishing = presetDifferentFrom(matrixBefore);
                    onHostThread(() -> {
                        setNativeLabelColor(deformer, establishing);
                        return null;
                    });
                    matrixBefore = onHostThread(() -> nativeLabelColor(deformer)).labelColor();
                }
                report.append("deformer.matrixBefore.background=")
                    .append(backgroundText(matrixBefore)).append('\n');
                final MatrixValues deformerMatrix = runBackgroundMatrix(
                    () -> nativeLabelColor(deformer), color -> setNativeLabelColor(deformer, color),
                    new NativeLabelColor.Default()
                );
                onHostThread(() -> {
                    setNativeLabelColor(deformer, deformerOriginal.labelColor());
                    return null;
                });
                final NativeLabelColorState finalRestored =
                    onHostThread(() -> nativeLabelColor(deformer));
                final boolean finalRestoreOk = finalRestored.equals(deformerOriginal);
                deformerPassed = deformerMatrix.passed() && finalRestoreOk;
                appendBackgroundReport(
                    report, "deformer", deformer.id().value(), modelId, hostThread,
                    deformerMatrix, deformerPassed
                );
                report.append("deformer.finalRestored.background=")
                    .append(backgroundText(finalRestored.labelColor())).append('\n')
                    .append("deformer.finalRestored.effective=")
                    .append(effectiveText(finalRestored.actualColor())).append('\n')
                    .append("deformer.finalRestore=")
                    .append(finalRestoreOk ? "PASS" : "FAIL").append('\n');
            } catch (Exception exception) {
                report.append("matrix.error=").append(exception.getClass().getName())
                    .append(": ").append(exception.getMessage()).append('\n');
            } finally {
                restoreReport.append(restoreStatus(
                    "parameterFolder", () -> nativeLabelColor(folder),
                    color -> setNativeLabelColor(folder, color), folderOriginal, restoreFailed
                ));
                restoreReport.append(restoreStatus(
                    "part", () -> nativeLabelColor(part),
                    color -> setNativeLabelColor(part, color), partOriginal, restoreFailed
                ));
                restoreReport.append(restoreStatus(
                    "deformer", () -> nativeLabelColor(deformer),
                    color -> setNativeLabelColor(deformer, color), deformerOriginal, restoreFailed
                ));
            }
            final boolean scopeClosePassed = verifyNativeControlScopeClose(
                model, folder, scopeReport, artifact, modelId, hostThread
            );
            final boolean overall = folderPassed && partPassed && deformerPassed
                && !restoreFailed.get() && scopeClosePassed;
            Files.writeString(
                artifact,
                "status=" + (overall ? "PASS" : "FAIL") + System.lineSeparator()
                    + "mode=native-control-background" + System.lineSeparator()
                    + "modelId=" + modelId + System.lineSeparator()
                    + "hostThread=" + hostThread + System.lineSeparator()
                    + "overall=" + (overall ? "PASS" : "FAIL") + System.lineSeparator()
                    + report
                    + restoreReport
                    + scopeReport,
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
                context.logger().error(
                    "Native control label-color validation artifact could not be written",
                    exception
                );
            }
        }
    }

    /**
     * Document-close staleness: hold a model object and its {@code .ui()} facade, close the
     * active document (Ctrl+W), and prove reads and writes fail closed. Only close-stale is
     * verified; reopening is a separate persistence stage.
     */
    private void runNativeLabelColorDocumentClose() {
        final Path artifact = Path.of(
            System.getProperty("turboism.home"), "logs", "native-control-background-document-close.txt"
        );
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitNativeLabelColorModel(artifact);
            final ParameterGroup folder = firstNonRootParameterGroup(model);
            final String modelId = onHostThread(() -> model.id().value());
            final String hostThread = onHostThread(() -> Thread.currentThread().getName());
            onHostThread(() -> nativeLabelColor(folder));
            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_W);
            boolean modelStale = false;
            boolean readStale = false;
            boolean writeStale = false;
            for (int attempt = 0;
                attempt < 60 && !(modelStale && readStale && writeStale);
                attempt++) {
                Thread.sleep(100L);
                modelStale = failsClosed(() -> onHostThread(model::id));
                readStale = failsClosed(() -> onHostThread(() -> nativeLabelColor(folder)));
                writeStale = failsClosed(() -> onHostThread(() -> {
                    setNativeLabelColor(folder, new NativeLabelColor.Default());
                    return null;
                }));
            }
            final boolean passed = modelStale && readStale && writeStale;
            Files.writeString(
                artifact,
                "status=" + (passed ? "PASS" : "FAIL") + System.lineSeparator()
                    + "phase=document-close" + System.lineSeparator()
                    + "modelId=" + modelId + System.lineSeparator()
                    + "hostThread=" + hostThread + System.lineSeparator()
                    + "modelStale=" + modelStale + System.lineSeparator()
                    + "readStale=" + readStale + System.lineSeparator()
                    + "writeStale=" + writeStale + System.lineSeparator(),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(
                artifact,
                exception,
                "Native control background document-close artifact could not be written"
            );
        }
    }

    /**
     * Persistence stage 1: write requested native label colors for a ParameterGroup, Part, and
     * Deformer through each model object's {@code .ui()} facade, then save the document. Originals
     * and requesteds are stored in the task-home properties file.
     */
    private void runNativeLabelColorPersistWrite() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path artifact = home.resolve("logs/native-control-background-persist-write.txt");
        final Path propertiesFile = home.resolve("state/native-control-background-persist.properties");
        try {
            Files.createDirectories(artifact.getParent());
            Files.createDirectories(propertiesFile.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitNativeLabelColorModel(artifact);
            final ParameterGroup folder = firstNonRootParameterGroup(model);
            final Part part = firstNonRootPart(model);
            final Deformer deformer = firstDeformer(model);
            final NativeLabelColorState folderOriginal = onHostThread(() -> nativeLabelColor(folder));
            final NativeLabelColorState partOriginal = onHostThread(() -> nativeLabelColor(part));
            final NativeLabelColorState deformerOriginal = onHostThread(() -> nativeLabelColor(deformer));
            final NativeLabelColor folderRequested = chooseCustomCandidate(folderOriginal);
            final NativeLabelColor partRequested = presetDifferentFrom(partOriginal.labelColor());
            final NativeLabelColor deformerRequested = presetDifferentFrom(deformerOriginal.labelColor());

            onHostThread(() -> {
                setNativeLabelColor(folder, folderRequested);
                return null;
            });
            awaitBackground(() -> nativeLabelColor(folder), folderRequested);
            onHostThread(() -> {
                setNativeLabelColor(part, partRequested);
                return null;
            });
            awaitBackground(() -> nativeLabelColor(part), partRequested);
            onHostThread(() -> {
                setNativeLabelColor(deformer, deformerRequested);
                return null;
            });
            awaitBackground(() -> nativeLabelColor(deformer), deformerRequested);

            final Properties stored = new Properties();
            stored.setProperty("folder.id", folder.id().value());
            stored.setProperty("part.id", part.id().value());
            stored.setProperty("deformer.id", deformer.id().value());
            storeStoredBackground(stored, "folder.original", folderOriginal.labelColor());
            storeStoredBackground(stored, "folder.requested", folderRequested);
            storeStoredBackground(stored, "part.original", partOriginal.labelColor());
            storeStoredBackground(stored, "part.requested", partRequested);
            storeStoredBackground(stored, "deformer.original", deformerOriginal.labelColor());
            storeStoredBackground(stored, "deformer.requested", deformerRequested);
            try (java.io.OutputStream output = Files.newOutputStream(
                propertiesFile, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING
            )) {
                stored.store(output, "Turboism native control background persistence expectations");
            }

            final String modelId = onHostThread(() -> model.id().value());
            final String hostThread = onHostThread(() -> Thread.currentThread().getName());
            final Path fixture = fixturePath();
            final FileTime beforeMtime = Files.getLastModifiedTime(fixture);
            final long beforeSize = Files.size(fixture);
            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_S);
            final SaveConfirmation save = awaitSaveConfirmation(fixture, beforeMtime, beforeSize);
            final boolean saved = save.confirmed();
            Files.writeString(
                artifact,
                "status=" + (saved ? "PASS" : "FAIL") + System.lineSeparator()
                    + "phase=saved" + System.lineSeparator()
                    + "modelId=" + modelId + System.lineSeparator()
                    + "hostThread=" + hostThread + System.lineSeparator()
                    + "folderId=" + folder.id().value() + System.lineSeparator()
                    + "partId=" + part.id().value() + System.lineSeparator()
                    + "deformerId=" + deformer.id().value() + System.lineSeparator()
                    + save.report("save."),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(
                artifact,
                exception,
                "Native control background persistence write artifact could not be written"
            );
        }
    }

    /**
     * Persistence stage 2: after the operator reopened the saved document, verify each requested
     * label color persisted through its model object's {@code .ui()} facade, restore every original,
     * and save again.
     */
    private void runNativeLabelColorPersistReopen() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path artifact = home.resolve("logs/native-control-background-persist-reopen.txt");
        final Path propertiesFile = home.resolve("state/native-control-background-persist.properties");
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitNativeLabelColorModel(artifact);
            final Properties stored = new Properties();
            try (java.io.InputStream input = Files.newInputStream(propertiesFile)) {
                stored.load(input);
            }
            final ParameterGroup folder = onHostThread(() -> model.parameterGroups().find(
                new ParameterGroupId(stored.getProperty("folder.id"))
            ));
            final Part part = onHostThread(() -> model.parts().find(
                new PartId(stored.getProperty("part.id"))
            ));
            final Deformer deformer = onHostThread(() -> model.deformers().find(
                new DeformerId(stored.getProperty("deformer.id"))
            ));
            final NativeLabelColor folderRequested = parseStoredBackground(stored, "folder.requested");
            final NativeLabelColor partRequested = parseStoredBackground(stored, "part.requested");
            final NativeLabelColor deformerRequested =
                parseStoredBackground(stored, "deformer.requested");
            final NativeLabelColor folderOriginal = parseStoredBackground(stored, "folder.original");
            final NativeLabelColor partOriginal = parseStoredBackground(stored, "part.original");
            final NativeLabelColor deformerOriginal = parseStoredBackground(stored, "deformer.original");

            awaitBackground(() -> nativeLabelColor(folder), folderRequested);
            awaitBackground(() -> nativeLabelColor(part), partRequested);
            awaitBackground(() -> nativeLabelColor(deformer), deformerRequested);

            onHostThread(() -> {
                setNativeLabelColor(folder, folderOriginal);
                setNativeLabelColor(part, partOriginal);
                setNativeLabelColor(deformer, deformerOriginal);
                return null;
            });
            awaitBackground(() -> nativeLabelColor(folder), folderOriginal);
            awaitBackground(() -> nativeLabelColor(part), partOriginal);
            awaitBackground(() -> nativeLabelColor(deformer), deformerOriginal);

            final String modelId = onHostThread(() -> model.id().value());
            final String hostThread = onHostThread(() -> Thread.currentThread().getName());
            final Path fixture = fixturePath();
            final FileTime beforeMtime = Files.getLastModifiedTime(fixture);
            final long beforeSize = Files.size(fixture);
            final java.awt.Robot robot = new java.awt.Robot();
            pressShortcut(robot, java.awt.event.KeyEvent.VK_S);
            final SaveConfirmation save = awaitSaveConfirmation(fixture, beforeMtime, beforeSize);
            final boolean saved = save.confirmed();
            Files.writeString(
                artifact,
                "status=" + (saved ? "PASS" : "FAIL") + System.lineSeparator()
                    + "phase=reopen-restored-saved" + System.lineSeparator()
                    + "modelId=" + modelId + System.lineSeparator()
                    + "hostThread=" + hostThread + System.lineSeparator()
                    + "verifiedRequested=parameterFolder,part,deformer" + System.lineSeparator()
                    + "restored=parameterFolder,part,deformer" + System.lineSeparator()
                    + save.report("save."),
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(
                artifact,
                exception,
                "Native control background persistence reopen artifact could not be written"
            );
        }
    }

    /**
     * Persistence stage 3: after the operator reopened the document a final time, verify every
     * restored original label color persisted through the model object's {@code .ui()} facade.
     */
    private void runNativeLabelColorPersistFinal() {
        final Path home = Path.of(System.getProperty("turboism.home"));
        final Path artifact = home.resolve("logs/native-control-background-persist-final.txt");
        final Path propertiesFile = home.resolve("state/native-control-background-persist.properties");
        try {
            Files.createDirectories(artifact.getParent());
            Files.writeString(
                artifact,
                "status=RUNNING phase=await-model\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            final CubismModel model = awaitNativeLabelColorModel(artifact);
            final Properties stored = new Properties();
            try (java.io.InputStream input = Files.newInputStream(propertiesFile)) {
                stored.load(input);
            }
            final ParameterGroup folder = onHostThread(() -> model.parameterGroups().find(
                new ParameterGroupId(stored.getProperty("folder.id"))
            ));
            final Part part = onHostThread(() -> model.parts().find(
                new PartId(stored.getProperty("part.id"))
            ));
            final Deformer deformer = onHostThread(() -> model.deformers().find(
                new DeformerId(stored.getProperty("deformer.id"))
            ));
            final NativeLabelColor folderOriginal = parseStoredBackground(stored, "folder.original");
            final NativeLabelColor partOriginal = parseStoredBackground(stored, "part.original");
            final NativeLabelColor deformerOriginal = parseStoredBackground(stored, "deformer.original");

            awaitBackground(() -> nativeLabelColor(folder), folderOriginal);
            awaitBackground(() -> nativeLabelColor(part), partOriginal);
            awaitBackground(() -> nativeLabelColor(deformer), deformerOriginal);

            final String modelId = onHostThread(() -> model.id().value());
            final String hostThread = onHostThread(() -> Thread.currentThread().getName());
            Files.writeString(
                artifact,
                "status=PASS\nphase=final-verify-restored\n"
                    + "modelId=" + modelId + System.lineSeparator()
                    + "hostThread=" + hostThread + System.lineSeparator()
                    + "verifiedRestored=parameterFolder,part,deformer\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
        } catch (Exception exception) {
            writeValidationFailure(
                artifact,
                exception,
                "Native control background persistence final artifact could not be written"
            );
        }
    }

    /**
     * Closes the owning plugin scope and proves the held model object and its {@code .ui()}
     * facade fail closed, while the existing peer-probe handshake proves the shared host remains
     * usable. No native color changes or overlays are left behind.
     */
    private boolean verifyNativeControlScopeClose(
        final CubismModel model,
        final ParameterGroup folder,
        final StringBuilder scopeReport,
        final Path artifact,
        final String modelId,
        final String hostThread
    ) {
        try {
            context.disposableScope().close();
            final boolean modelStale = failsClosed(() -> onHostThread(model::id));
            final boolean uiReadStale = failsClosed(() -> onHostThread(() -> nativeLabelColor(folder)));
            final boolean uiWriteStale = failsClosed(() -> onHostThread(() -> {
                setNativeLabelColor(folder, new NativeLabelColor.Default());
                return null;
            }));
            final Path peerRequest = Path.of(
                System.getProperty("turboism.home"), "state", "editor-object-peer-request.txt"
            );
            final Path peerArtifact = Path.of(
                System.getProperty("turboism.home"), "logs", "editor-object-peer-scope-close.txt"
            );
            Files.createDirectories(peerRequest.getParent());
            Files.deleteIfExists(peerArtifact);
            Files.writeString(
                peerRequest,
                "primaryScopeClosed=true\n",
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING
            );
            writeRunningScopeClosePhase(artifact, modelId, hostThread);
            final String peerEvidence = awaitPeerEvidence(
                peerArtifact, PEER_RESPONSE_MAX_ATTEMPTS, PEER_RESPONSE_POLL_MILLIS
            );
            final boolean peerTerminal = peerEvidence.contains("status=PASS")
                || peerEvidence.contains("status=FAIL");
            final boolean secondPluginUsable = peerEvidence.contains("status=PASS")
                && peerEvidence.contains("secondPluginUsable=true");
            final boolean passed = modelStale && uiReadStale && uiWriteStale
                && peerTerminal && secondPluginUsable;
            scopeReport.append("phase=plugin-scope-close\n")
                .append("modelStale=").append(modelStale).append('\n')
                .append("uiReadStale=").append(uiReadStale).append('\n')
                .append("uiWriteStale=").append(uiWriteStale).append('\n')
                .append("peerTerminal=").append(peerTerminal).append('\n')
                .append("secondPluginUsable=").append(secondPluginUsable).append('\n')
                .append("peerTimeout=").append(!peerTerminal).append('\n')
                .append("peerMissingEvidence=").append(!peerTerminal).append('\n')
                .append("scopeClose=").append(passed ? "PASS" : "FAIL").append('\n');
            return passed;
        } catch (Exception exception) {
            scopeReport.append("phase=plugin-scope-close\n")
                .append("scopeClose=FAIL\n")
                .append("scopeClose.error=").append(exception.getClass().getName())
                .append(": ").append(exception.getMessage()).append('\n');
            return false;
        }
    }

    private ParameterGroup firstNonRootParameterGroup(final CubismModel model) throws Exception {
        return onHostThread(() -> {
            final ParameterGroups groups = model.parameterGroups();
            final ParameterGroupId root = groups.root().id();
            return groups.all().stream()
                .filter(group -> !group.id().equals(root))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                    "No non-root parameter group is available."
                ));
        });
    }

    private Part firstNonRootPart(final CubismModel model) throws Exception {
        return onHostThread(() -> model.parts().all().stream()
            .filter(part -> !"__RootPart__".equals(part.id().value()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No non-root Part is available.")));
    }

    private Deformer firstDeformer(final CubismModel model) throws Exception {
        return onHostThread(() -> model.deformers().all().stream()
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("No Deformer is available.")));
    }

    /** Properties serialization of a native background for cross-mode persistence stages. */
    static void storeStoredBackground(
        final Properties properties,
        final String prefix,
        final NativeLabelColor background
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(prefix, "prefix");
        Objects.requireNonNull(background, "background");
        if (background instanceof NativeLabelColor.Default) {
            properties.setProperty(prefix + ".type", "default");
        } else if (background instanceof NativeLabelColor.Preset preset) {
            properties.setProperty(prefix + ".type", "preset");
            properties.setProperty(prefix + ".preset", preset.color().name());
        } else if (background instanceof NativeLabelColor.Custom custom) {
            properties.setProperty(prefix + ".type", "custom");
            properties.setProperty(prefix + ".red", Float.toString(custom.color().red()));
            properties.setProperty(prefix + ".green", Float.toString(custom.color().green()));
            properties.setProperty(prefix + ".blue", Float.toString(custom.color().blue()));
            properties.setProperty(prefix + ".alpha", Float.toString(custom.color().alpha()));
        } else {
            throw new IllegalArgumentException(
                "unsupported native control background: " + background.getClass().getName()
            );
        }
    }

    /** Inverse of {@link #storeStoredBackground}; fails closed on malformed properties. */
    static NativeLabelColor parseStoredBackground(
        final Properties properties,
        final String prefix
    ) {
        Objects.requireNonNull(properties, "properties");
        Objects.requireNonNull(prefix, "prefix");
        final String type = properties.getProperty(prefix + ".type");
        if ("default".equals(type)) {
            return new NativeLabelColor.Default();
        }
        if ("preset".equals(type)) {
            final String preset = properties.getProperty(prefix + ".preset");
            if (preset == null) {
                throw new IllegalArgumentException("Stored preset background is missing its preset name.");
            }
            return new NativeLabelColor.Preset(PresetColor.valueOf(preset));
        }
        if ("custom".equals(type)) {
            return new NativeLabelColor.Custom(new UiColor(
                parseFiniteValue(properties.getProperty(prefix + ".red")),
                parseFiniteValue(properties.getProperty(prefix + ".green")),
                parseFiniteValue(properties.getProperty(prefix + ".blue")),
                parseFiniteValue(properties.getProperty(prefix + ".alpha"))
            ));
        }
        throw new IllegalArgumentException("Unsupported stored background type: " + type);
    }

    private CubismModel awaitNativeLabelColorModel(final Path artifact) throws Exception {
        CubismModel model = null;
        Exception unavailable = null;
        for (int attempt = 0; attempt < 120 && !Thread.currentThread().isInterrupted(); attempt++) {
            try {
                model = onHostThread(this::activeModel);
                final CubismModel candidate = model;
                onHostThread(() -> {
                    final ParameterGroups groups = candidate.parameterGroups();
                    if (groups.all().stream().noneMatch(group ->
                        !group.id().equals(groups.root().id()))) {
                        throw new IllegalStateException("No non-root parameter group is available.");
                    }
                    if (candidate.parts().all().stream().noneMatch(part ->
                        !"__RootPart__".equals(part.id().value()))) {
                        throw new IllegalStateException("No non-root Part is available.");
                    }
                    if (candidate.deformers().all().isEmpty()) {
                        throw new IllegalStateException("No Deformer is available.");
                    }
                    return null;
                });
                return model;
            } catch (Exception exception) {
                model = null;
                unavailable = exception;
                Files.writeString(
                    artifact,
                    "status=RUNNING phase=await-model attempt=" + attempt + " error="
                        + exception.getClass().getName() + ": " + exception.getMessage() + "\n",
                    StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING
                );
                Thread.sleep(1000L);
            }
        }
        throw unavailable == null
            ? new IllegalStateException("Native control background validation was interrupted.")
            : unavailable;
    }

    private static NativeLabelColorState nativeLabelColor(final ParameterGroup group) {
        return group.ui().nativeLabelColor().orElseThrow(() ->
            new IllegalStateException("No native label color is available for ParameterGroup " + group.id())
        );
    }

    private static NativeLabelColorState nativeLabelColor(final Part part) {
        return part.ui().nativeLabelColor().orElseThrow(() ->
            new IllegalStateException("No native label color is available for Part " + part.id())
        );
    }

    private static NativeLabelColorState nativeLabelColor(final Deformer deformer) {
        return deformer.ui().nativeLabelColor().orElseThrow(() ->
            new IllegalStateException("No native label color is available for Deformer " + deformer.id())
        );
    }

    private static void setNativeLabelColor(
        final ParameterGroup group,
        final NativeLabelColor color
    ) {
        group.ui().setNativeLabelColor(color);
    }

    private static void setNativeLabelColor(final Part part, final NativeLabelColor color) {
        part.ui().setNativeLabelColor(color);
    }

    private static void setNativeLabelColor(final Deformer deformer, final NativeLabelColor color) {
        deformer.ui().setNativeLabelColor(color);
    }

    private MatrixValues runBackgroundMatrix(
        final java.util.function.Supplier<NativeLabelColorState> read,
        final java.util.function.Consumer<NativeLabelColor> write,
        final NativeLabelColor requested
    ) throws Exception {
        final NativeLabelColorState before = onHostThread(read::get);
        requireDistinctBackgroundRequest(requested, before.labelColor());
        onHostThread(() -> {
            write.accept(requested);
            return null;
        });
        awaitBackground(read, requested);
        final NativeLabelColorState afterWrite = onHostThread(read::get);
        onHostThread(() -> {
            write.accept(requested);
            return null;
        });
        final NativeLabelColorState sameValueSecondWrite = onHostThread(read::get);
        final java.awt.Robot robot = new java.awt.Robot();
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        awaitBackground(read, before);
        final NativeLabelColorState afterUndo = onHostThread(read::get);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Y);
        awaitBackground(read, afterWrite);
        final NativeLabelColorState afterRedo = onHostThread(read::get);
        pressShortcut(robot, java.awt.event.KeyEvent.VK_Z);
        awaitBackground(read, before);
        final NativeLabelColorState restored = onHostThread(read::get);
        return new MatrixValues(
            before, requested, afterWrite, sameValueSecondWrite, afterUndo, afterRedo, restored
        );
    }

    private static void appendBackgroundReport(
        final StringBuilder report,
        final String family,
        final String targetId,
        final String modelId,
        final String hostThread,
        final MatrixValues matrix,
        final boolean passed
    ) {
        final String prefix = family + ".";
        report.append(prefix).append("family=").append(family).append('\n')
            .append(prefix).append("target=").append(targetId).append('\n')
            .append(prefix).append("modelId=").append(modelId).append('\n')
            .append(prefix).append("hostThread=").append(hostThread).append('\n')
            .append(prefix).append("before.background=")
            .append(backgroundText(matrix.before().labelColor())).append('\n')
            .append(prefix).append("before.effective=")
            .append(effectiveText(matrix.before().actualColor())).append('\n')
            .append(prefix).append("requested=").append(backgroundText(matrix.requested())).append('\n')
            .append(prefix).append("afterWrite.background=")
            .append(backgroundText(matrix.afterWrite().labelColor())).append('\n')
            .append(prefix).append("afterWrite.effective=")
            .append(effectiveText(matrix.afterWrite().actualColor())).append('\n')
            .append(prefix).append("sameValueSecondWrite.background=")
            .append(backgroundText(matrix.sameValueSecondWrite().labelColor())).append('\n')
            .append(prefix).append("afterUndo.background=")
            .append(backgroundText(matrix.afterUndo().labelColor())).append('\n')
            .append(prefix).append("afterRedo.background=")
            .append(backgroundText(matrix.afterRedo().labelColor())).append('\n')
            .append(prefix).append("restored.background=")
            .append(backgroundText(matrix.restored().labelColor())).append('\n')
            .append(prefix).append("check.afterWrite=")
            .append(matrix.afterWrite().labelColor().equals(matrix.requested()) ? "PASS" : "FAIL").append('\n')
            .append(prefix).append("check.sameValueSecondWrite=")
            .append(matrix.sameValueSecondWrite().equals(matrix.afterWrite()) ? "PASS" : "FAIL").append('\n')
            .append(prefix).append("check.afterUndo=")
            .append(matrix.afterUndo().equals(matrix.before()) ? "PASS" : "FAIL").append('\n')
            .append(prefix).append("check.afterRedo=")
            .append(matrix.afterRedo().equals(matrix.afterWrite()) ? "PASS" : "FAIL").append('\n')
            .append(prefix).append("check.restored=")
            .append(matrix.restored().equals(matrix.before()) ? "PASS" : "FAIL").append('\n')
            .append(prefix).append("check.singleUndoGroup=")
            .append(matrix.afterUndo().equals(matrix.before()) ? "PASS" : "FAIL")
            .append(" // one Undo returned directly to before; the same-value second write added no Undo group")
            .append('\n')
            .append(prefix).append("status=").append(passed ? "PASS" : "FAIL").append('\n');
    }

    private static String restoreStatus(
        final String label,
        final java.util.function.Supplier<NativeLabelColorState> read,
        final java.util.function.Consumer<NativeLabelColor> write,
        final NativeLabelColorState original,
        final AtomicBoolean restoreFailed
    ) {
        try {
            onHostThread(() -> {
                write.accept(original.labelColor());
                return null;
            });
            final NativeLabelColorState confirmed = onHostThread(read::get);
            final boolean ok = confirmed.equals(original);
            if (!ok) {
                restoreFailed.set(true);
            }
            return "restore." + label + "=" + (ok ? "PASS" : "FAIL") + System.lineSeparator()
                + "restore." + label + ".confirmed.background="
                + backgroundText(confirmed.labelColor()) + System.lineSeparator()
                + "restore." + label + ".confirmed.effective="
                + effectiveText(confirmed.actualColor()) + System.lineSeparator();
        } catch (Exception exception) {
            restoreFailed.set(true);
            return "restore." + label + "=FAIL" + System.lineSeparator()
                + "restore." + label + ".error=" + exception.getClass().getName() + ": "
                + exception.getMessage() + System.lineSeparator();
        }
    }

    /** Fixed Custom request that differs from the semantic before-state, never the effective only. */
    static NativeLabelColor chooseCustomCandidate(final NativeLabelColorState before) {
        Objects.requireNonNull(before, "before");
        for (UiColor candidate : CUSTOM_CANDIDATES) {
            final NativeLabelColor custom = new NativeLabelColor.Custom(candidate);
            if (!custom.equals(before.labelColor())) {
                return custom;
            }
        }
        throw new IllegalStateException("No fixed custom color differs from the folder before-state.");
    }

    /** Hard fail-closed guard: a same-value request would create no Undo and must not be written. */
    static void requireDistinctBackgroundRequest(
        final NativeLabelColor requested,
        final NativeLabelColor before
    ) {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(before, "before");
        if (requested.equals(before)) {
            throw new IllegalStateException(
                "Requested background " + backgroundText(requested)
                    + " equals the before-state; a same-value write would create no Undo."
            );
        }
    }

    private NativeLabelColorState awaitBackground(
        final java.util.function.Supplier<NativeLabelColorState> read,
        final NativeLabelColor semantic
    ) throws Exception {
        return awaitBackground(read, appearance -> semantic.equals(appearance.labelColor()));
    }

    private NativeLabelColorState awaitBackground(
        final java.util.function.Supplier<NativeLabelColorState> read,
        final NativeLabelColorState expected
    ) throws Exception {
        return awaitBackground(read, expected::equals);
    }

    private NativeLabelColorState awaitBackground(
        final java.util.function.Supplier<NativeLabelColorState> read,
        final java.util.function.Predicate<NativeLabelColorState> predicate
    ) throws Exception {
        NativeLabelColorState actual = onHostThread(read::get);
        for (int attempt = 0; attempt < 40 && !predicate.test(actual); attempt++) {
            Thread.sleep(100L);
            actual = onHostThread(read::get);
        }
        if (!predicate.test(actual)) {
            throw new IllegalStateException(
                "Native label color did not converge within the bounded await window; last="
                    + backgroundText(actual.labelColor())
                    + " effective=" + effectiveText(actual.actualColor())
            );
        }
        return actual;
    }

    private static NativeLabelColor presetDifferentFrom(final NativeLabelColor current) {
        for (PresetColor preset : PresetColor.values()) {
            final NativeLabelColor candidate = new NativeLabelColor.Preset(preset);
            if (!candidate.equals(current)) {
                return candidate;
            }
        }
        throw new IllegalStateException("No preset differs from " + backgroundText(current));
    }

    private static String backgroundText(final NativeLabelColor background) {
        if (background instanceof NativeLabelColor.Default) {
            return "default";
        }
        if (background instanceof NativeLabelColor.Preset preset) {
            return "preset(" + preset.color().name() + ")";
        }
        if (background instanceof NativeLabelColor.Custom custom) {
            return "custom(" + colorText(custom.color()) + ")";
        }
        throw new IllegalArgumentException(
            "unsupported native control background: " + background.getClass().getName()
        );
    }

    private static final UiColor[] CUSTOM_CANDIDATES = {
        new UiColor(1.0F, 0.0F, 0.0F, 1.0F),
        new UiColor(0.0F, 1.0F, 0.0F, 1.0F),
        new UiColor(0.0F, 0.0F, 1.0F, 1.0F),
        new UiColor(0.25F, 0.5F, 0.75F, 1.0F),
        new UiColor(0.1F, 0.2F, 0.3F, 0.4F),
        new UiColor(0.8F, 0.6F, 0.4F, 0.2F)
    };

    private record MatrixValues(
        NativeLabelColorState before,
        NativeLabelColor requested,
        NativeLabelColorState afterWrite,
        NativeLabelColorState sameValueSecondWrite,
        NativeLabelColorState afterUndo,
        NativeLabelColorState afterRedo,
        NativeLabelColorState restored
    ) {
        boolean passed() {
            return afterWrite.labelColor().equals(requested)
                && sameValueSecondWrite.equals(afterWrite)
                && afterUndo.equals(before)
                && afterRedo.equals(afterWrite)
                && restored.equals(before);
        }
    }


    private static <T> T onHostThread(final Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(5L, java.util.concurrent.TimeUnit.SECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the probe within 5 seconds.");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private static void pressShortcut(final java.awt.Robot robot, final int key) throws Exception {
        // Prefer the matching enabled Swing menu accelerator directly (avoids Wine/window
        // focus); fall back to Robot only when no enabled accelerator exists. Callers use
        // Ctrl+Z/Y (Undo/Redo), Ctrl+W (document close), and Ctrl+S (save).
        if (invokeMenuShortcut(key)) {
            Thread.sleep(250L);
            return;
        }
        final AtomicReference<java.awt.Frame> hostFrame = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            java.awt.Frame fallback = null;
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!frame.isVisible()) continue;
                if (fallback == null) fallback = frame;
                final String title = frame.getTitle();
                if (title != null && title.contains(".cmo3")) {
                    hostFrame.set(frame);
                    break;
                }
                if (hostFrame.get() == null && title != null && title.contains("Cubism")) {
                    hostFrame.set(frame);
                }
            }
            if (hostFrame.get() == null) hostFrame.set(fallback);
            final java.awt.Frame frame = hostFrame.get();
            if (frame != null) {
                frame.setState(java.awt.Frame.NORMAL);
                frame.toFront();
                frame.requestFocus();
            }
        });
        final java.awt.Frame frame = hostFrame.get();
        if (frame != null) {
            final java.awt.Rectangle bounds = frame.getBounds();
            robot.mouseMove(bounds.x + Math.max(20, bounds.width / 2), bounds.y + 12);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        }
        Thread.sleep(400L);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        Thread.sleep(250L);
    }

    private static boolean invokeMenuShortcut(final int key) throws Exception {
        final AtomicReference<javax.swing.JMenuItem> match = new AtomicReference<>();
        final AtomicBoolean enabled = new AtomicBoolean();
        SwingUtilities.invokeAndWait(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
                final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
                if (bar == null) continue;
                for (int index = 0; index < bar.getMenuCount() && match.get() == null; index++) {
                    findMenuShortcut(bar.getMenu(index), key, match);
                }
            }
            final javax.swing.JMenuItem item = match.get();
            enabled.set(item != null && item.isEnabled());
            if (enabled.get()) item.doClick(0);
        });
        return enabled.get();
    }

    private static void findMenuShortcut(
        final javax.swing.JMenuItem item,
        final int key,
        final AtomicReference<javax.swing.JMenuItem> match
    ) {
        if (item == null || match.get() != null) return;
        final javax.swing.KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null && accelerator.getKeyCode() == key
            && (accelerator.getModifiers() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
            match.set(item);
            return;
        }
        if (item instanceof javax.swing.JMenu menu) {
            for (java.awt.Component component : menu.getMenuComponents()) {
                if (component instanceof javax.swing.JMenuItem child) findMenuShortcut(child, key, match);
            }
        }
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
        refreshSelectedGroupColor();
        defaultKeyformLockLabel.setText(
            "Default keyform locked: " + model.defaultKeyformLocked()
        );
    }

    private void refreshSelectedGroupColor() {
        final String chosen = selected(parameterGroupBox, null);
        if (chosen == null) {
            currentLabelColorLabel.setText("Current background: unavailable");
            return;
        }
        final NativeLabelColorState appearance;
        try {
            appearance = onHostThread(() -> nativeLabelColor(
                activeModel().parameterGroups().find(new ParameterGroupId(chosen))
            ));
        } catch (Exception unavailable) {
            currentLabelColorLabel.setText("Current background: unavailable");
            return;
        }
        currentLabelColorLabel.setText(
            "Background: " + backgroundText(appearance.labelColor())
                + " effective=" + effectiveText(appearance.actualColor())
        );
        appearance.actualColor().ifPresent(effective -> {
            if (!labelRedField.hasFocus()) labelRedField.setText(Float.toString(effective.red()));
            if (!labelGreenField.hasFocus()) labelGreenField.setText(Float.toString(effective.green()));
            if (!labelBlueField.hasFocus()) labelBlueField.setText(Float.toString(effective.blue()));
            if (!labelAlphaField.hasFocus()) labelAlphaField.setText(Float.toString(effective.alpha()));
        });
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

    private static String colorText(final UiColor color) {
        return "rgba(" + number(color.red()) + ", " + number(color.green()) + ", "
            + number(color.blue()) + ", " + number(color.alpha()) + ')';
    }

    private static String effectiveText(final Optional<UiColor> effective) {
        return effective.map(WindowsParameterValidationProbe::colorText).orElse("unavailable");
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
