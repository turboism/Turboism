package dev.turboism.automesh;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.sdk.cubism.mesh.automesh.AlgorithmParameterDescriptor;
import dev.turboism.ui.host.EdtDispatch;
import java.awt.Component;
import java.awt.Window;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * The "Algorithm / Preset / Parameters…" row one native Auto Mesh Generate container gains.
 *
 * <p>The row is built from the host's own widgets ({@code CLabel}, {@code CComboBox},
 * {@code CButton}, {@code CHBox}, {@code CSpacer}) through the container's own class loader, so
 * theme, DPI and font match the surrounding native controls. Every reflection step resolves an
 * exact signature through {@link MethodHandleCache}, and any failure leaves the container exactly
 * as the host built it.</p>
 *
 * <p>Each host member is named by its full parameter types, because a host build that keeps a
 * member name but changes a parameter is the mismatch that costs a debugging session. A refused
 * lookup therefore reports the signature it wanted and the signatures the build declares.</p>
 *
 * <p>A container whose centre is the native Auto Mesh layout gains three groups inside that
 * centre: an Algorithm group above the native Preset group, a plugin Preset group beside it, and a
 * Parameters group below the native one. The plugin groups copy the native titles and the native
 * button text, and they hide whenever the Editor's generator runs, so the native groups stay
 * authoritative.</p>
 *
 * <p>Every other centre keeps the single appended row: the row becomes the last child of the
 * container's centre widget, and the container keeps its own children in their own order. A
 * container whose centre is missing or is not a host container is left untouched.</p>
 *
 * <p>No row is added at all while only the Editor's generator is registered. A user without an
 * algorithm plugin therefore sees the native popup with zero visual change.</p>
 *
 * <p>One instance serves one container. The session-level owner creates a fresh instance per
 * construction, so a container the host discards takes its row with it.</p>
 */
public final class AutoMeshSelectorRow {

    private static final String LABEL_TYPE = "com.live2d.ui.control.CLabel";
    private static final String COMBO_TYPE = "com.live2d.ui.control.CComboBox";
    private static final String BUTTON_TYPE = "com.live2d.ui.control.CButton";
    private static final String ROW_TYPE = "com.live2d.ui.container.CHBox";
    private static final String SPACER_TYPE = "com.live2d.ui.container.CSpacer";
    private static final String CONTAINER_TYPE = "com.live2d.ui.container.CContainer";
    private static final String WIDGET_TYPE = "com.live2d.ui.CWidget";
    private static final String FUNCTION1_TYPE = "kotlin.jvm.functions.Function1";
    private static final String UNIT_TYPE = "kotlin.Unit";
    private static final String UNIT_INSTANCE_FIELD = "INSTANCE";
    private static final String CONSTRUCTOR_NAME = "<init>";
    private static final String INVOKE_METHOD = "invoke";
    private static final String CENTER_GETTER = "getCenter";
    private static final String COMPONENT_GETTER = "getJComponent";
    private static final String UNARY_PLUS = "unaryPlus";
    private static final String REVALIDATE = "revalidate";
    private static final String REPAINT = "repaint";
    private static final String SET_ON_SELECTED = "setOnSelected";
    private static final String ADD_ON_ACTION = "addOnAction";
    private static final String SET_TEXT = "setText";
    private static final String SET_TOOLTIP = "setToolTipText";
    private static final String GET_SELECTED_INDEX = "getSelectedIndex";
    private static final String SET_SELECTED_INDEX = "setSelectedIndex";
    private static final String SET_ENABLED = "setEnabled";
    private static final String GET_MODEL = "getModel";
    private static final String SET_MODEL = "setModel";
    private static final String UPDATE_POPUP_WIDTH = "updatePopupWidth";
    private static final String MARGIN_METHOD = "setMarginInt";
    private static final int ROW_MARGIN = 6;
    private static final int SPACER_WIDTH = 4;
    private static final int SPACER_HEIGHT = 0;
    private static final int MAX_DECLARED_HINTS = 4;
    private static final String NO_DECLARED_MEMBERS = "none";
    private static final String CAUSE_SEPARATOR = " caused by ";
    private static final String DECLARED_SEPARATOR = "; declared: ";
    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];
    private static final Class<?>[] TEXT_PARAMETER = {String.class};
    private static final Class<?>[] COUNT_PARAMETER = {int.class};
    private static final Class<?>[] FLAG_PARAMETER = {boolean.class};
    private static final Class<?>[] FLOAT_PARAMETER = {float.class};
    private static final Class<?>[] BOXED_BOOLEAN_PARAMETER = {Boolean.class};
    private static final Class<?>[] SIZE_PARAMETER = {int.class, int.class};
    private static final Class<?>[] INSETS_PARAMETER = {int.class, int.class, int.class, int.class};
    private static final Class<?>[] ITEM_PARAMETER = {List.class};
    private static final String ALGORITHM_LABEL_KEY = "selector.algorithm.label";
    private static final String ALGORITHM_TOOLTIP_KEY = "selector.algorithm.tooltip";
    private static final String PRESET_LABEL_KEY = "selector.preset.label";
    private static final String PRESET_TOOLTIP_KEY = "selector.preset.tooltip";
    private static final String PARAMETERS_BUTTON_KEY = "selector.parameters.button";
    private static final String PARAMETERS_TOOLTIP_KEY = "selector.parameters.tooltip";

    private static final String VBOX_TYPE = "com.live2d.ui.container.CVBox";
    private static final String TITLED_BORDER_TYPE = "com.live2d.ui.border.CTitledBorder";
    private static final String BORDER_TYPE = "com.live2d.ui.border.CBorder";
    private static final String DLG_BUTTON_TYPE = "com.live2d.ui.control.CDlgButton";
    private static final String BUTTON_MENU_TYPE = "com.live2d.ui.menu.CButtonMenu";
    private static final String MENU_ITEM_TYPE = "com.live2d.ui.menu.CMenuItem";
    private static final String POPUP_MENU_TYPE = "com.live2d.ui.menu.k";
    private static final String SLIDABLE_INT_TYPE = "com.live2d.ui.control.CSlidableInt";
    private static final String SLIDABLE_FLOAT_TYPE = "com.live2d.ui.control.CSlidableFloat";
    private static final String CHECK_BOX_TYPE = "com.live2d.ui.control.CCheckBox";
    private static final String TEXT_FIELD_TYPE = "com.live2d.ui.control.CTextField";
    private static final String INSETS_TYPE = "com.live2d.type.CInsets";
    private static final String GROW_TYPE = "com.live2d.ui.container.a.c";
    private static final String ALIGNMENT_TYPE = "com.live2d.ui.a.a";
    private static final String DEFAULT_MARKER_TYPE = "kotlin.jvm.internal.DefaultConstructorMarker";

    /** Field of {@link #GROW_TYPE} that always expands its widget. */
    private static final String GROW_WEIGHT_FIELD = "a";

    /** Field of {@link #ALIGNMENT_TYPE} that aligns text to the trailing edge, which is right in LTR. */
    private static final String TRAILING_ALIGNMENT_FIELD = "f";

    /**
     * The popup item-list getter of {@link #POPUP_MENU_TYPE}.
     *
     * <p>The host publishes no stable name for the items of a button menu, so the reviewed builds
     * pin this obfuscated one. The read is optional: a refused lookup falls back to the bundle text.
     */
    private static final String POPUP_ITEMS_METHOD = "d";

    private static final String HOST_CHILDREN = "getChildren";
    private static final String REMOVE_ALL = "removeAll";
    private static final String ADD_METHOD = "add";
    private static final String SET_BORDER = "setBorder";
    private static final String GET_BORDER = "getBorder";
    private static final String GET_TITLE = "getTitle";
    private static final String GET_TEXT = "getText";
    private static final String SET_VISIBLE = "setVisible";
    private static final String SET_HGROW = "setHgrow";
    private static final String SET_MARGIN = "setMargin";
    private static final String SET_PREF_WIDTH = "setPrefWidth";
    private static final String SET_HORIZONTAL_ALIGNMENT = "setHorizontalAlignment";
    private static final String LABEL_WIDTH_GETTER = "getEnoughWidth";
    private static final String GET_POPUP_MENU = "getPopupMenu";
    private static final String SET_VALUE = "setValue";
    private static final String GET_VALUE = "getValue";
    private static final String SET_MIN = "setMin";
    private static final String SET_MAX = "setMax";
    private static final String SET_KETA = "setKeta";
    private static final String SET_SLIDE_WEIGHT = "setSlideWeight";
    private static final String SET_ON_CHANGED = "setOnChanged";
    private static final String SET_CHECKED = "setChecked";
    private static final String IS_CHECKED = "isChecked";
    private static final String SET_ON_ACTION = "setOnAction";

    /** The centre child count of a container that carries the native Auto Mesh groups. */
    private static final int NATIVE_LAYOUT_MINIMUM_CHILDREN = 2;

    /** Centre child index of the native Preset group. */
    private static final int NATIVE_PRESET_INDEX = 0;

    /** Centre child index of the native Parameters group. */
    private static final int NATIVE_SETTINGS_INDEX = 1;

    private static final int ALGORITHM_GROUP_INDEX = 0;
    private static final int PRESET_GROUP_INDEX = 2;
    private static final int SETTINGS_GROUP_INDEX = 4;
    private static final int GROUP_SPACER_WIDTH = 0;
    private static final int GROUP_SPACER_HEIGHT = 5;

    /** The Kotlin default-argument mask that leaves a titled border without an inner border. */
    private static final int TITLE_BORDER_NULL_BORDER_MASK = 2;

    private static final int PARAMETER_ROW_TOP_MARGIN = 2;
    private static final int PARAMETER_ROW_BOTTOM_MARGIN = 1;
    private static final int PARAMETER_ROW_PREF_WIDTH = 200;
    private static final int PARAMETER_LABEL_TOP_MARGIN = 3;
    private static final int PARAMETER_LABEL_RIGHT_MARGIN = 5;
    private static final int PARAMETER_LABEL_MIN_WIDTH = 90;
    private static final int PARAMETER_LABEL_MAX_WIDTH = 150;
    private static final int PARAMETER_SLIDER_DECIMALS = 2;
    private static final float PARAMETER_SLIDE_WEIGHT = 5.0f;

    /**
     * Delay before one inline edit stream is written to the preset store.
     *
     * <p>A slider reports every step of a drag and each store write forces the preset file. The
     * selection state follows every step, so only the durable copy waits for this delay.</p>
     */
    private static final int PARAMETER_COMMIT_DELAY_MILLIS = 400;

    private static final int MAX_WIDGET_SCAN_DEPTH = 8;
    private static final int NO_PRESET_SELECTION = 0;
    private static final int SAVE_AS_BUTTON_INDEX = 0;
    private static final int DELETE_BUTTON_INDEX = 1;
    private static final int FIRST_MENU_ITEM_INDEX = 0;
    private static final String MENU_BUTTON_TEXT = ">";

    /** Suffix a host dialog button appends to its own text. */
    private static final String DIALOG_BUTTON_ELLIPSIS = "...";

    private final AutoMeshSelectorController controller;
    private final Locale locale;
    private final Consumer<String> diagnostics;

    /**
     * Creates the row renderer of one container.
     *
     * @param controller the headless decisions the row renders
     * @param locale the host-effective locale for the framework's own text
     * @param diagnostics sink for every refused attach; it must not throw
     */
    public AutoMeshSelectorRow(
            final AutoMeshSelectorController controller, final Locale locale, final Consumer<String> diagnostics) {
        this.controller = Objects.requireNonNull(controller, "controller");
        this.locale = locale;
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    /**
     * Reports whether {@link #attach} would add a row to a container right now.
     *
     * <p>The session host asks this before it records any bookkeeping, so a session that lists no
     * plugin algorithm leaves no trace: no widget, no entry, no diagnostic.</p>
     *
     * @return {@code true} when at least one plugin algorithm is registered
     */
    public boolean wouldAttach() {
        return controller.rowVisible();
    }

    /**
     * Appends the row to one freshly constructed native container.
     *
     * <p>Runs on the event dispatch thread. Every failure is swallowed into a diagnostic, because a
     * container that cannot be decorated must still show the native popup unchanged.</p>
     *
     * @param container the container widget the host just constructed
     */
    public void attach(final Object container) {
        if (container == null) {
            diagnostic("auto mesh selector: container is missing, the row is not added");
            return;
        }
        if (!wouldAttach()) {
            // The native-only case is the default: no widget is created at all.
            return;
        }
        EdtDispatch.post("auto mesh selector row", () -> decorate(container));
    }

    private void decorate(final Object container) {
        try {
            final Object mount = mountTarget(container);
            if (mount == null) {
                diagnostic("auto mesh selector: " + container.getClass().getName()
                        + " exposes no centre container, the row is not added");
                return;
            }
            if (carriesNativeLayout(mount)) {
                new Inline(container, mount).attach();
            } else {
                final Object row = build(container);
                invoke(mount, UNARY_PLUS, parameterTypes(container, WIDGET_TYPE), row);
            }
            tryInvoke(mount, REVALIDATE, NO_PARAMETERS);
            tryInvoke(mount, REPAINT, NO_PARAMETERS);
            diagnostic("auto mesh selector: row attached to "
                    + container.getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Fail closed: the native popup keeps its own layout and its own controls.
            diagnostic("auto mesh selector: attach failed with " + describe(failure) + ", the row is not added");
        }
    }

    /**
     * Resolves the container the row joins.
     *
     * <p>The centre widget is the host's own content slot, so appending there keeps the container's
     * border layout, its scrolling body and its button row untouched. A container without a centre
     * is refused instead of being guessed at.</p>
     */
    private static Object mountTarget(final Object container) throws ReflectiveOperationException {
        final Object center = invoke(container, CENTER_GETTER, NO_PARAMETERS);
        if (center == null) {
            return null;
        }
        final Class<?> containerType =
                Class.forName(CONTAINER_TYPE, false, container.getClass().getClassLoader());
        return containerType.isInstance(center) ? center : null;
    }

    /**
     * Reports whether the centre already carries the native Preset and Parameters groups.
     *
     * <p>Only that layout takes the plugin groups: any other centre belongs to a container the
     * framework does not know, and it keeps the single appended row.</p>
     */
    private static boolean carriesNativeLayout(final Object mount) throws ReflectiveOperationException {
        final Class<?> vbox = Class.forName(VBOX_TYPE, false, mount.getClass().getClassLoader());
        return vbox.isInstance(mount) && childrenOf(mount).size() >= NATIVE_LAYOUT_MINIMUM_CHILDREN;
    }

    private static List<?> childrenOf(final Object container) throws ReflectiveOperationException {
        final Object children = invoke(container, HOST_CHILDREN, NO_PARAMETERS);
        return children instanceof List<?> list ? list : List.of();
    }

    /**
     * The plugin groups one native Auto Mesh container gains.
     *
     * <p>Every group is a host {@code CVBox} with a host titled border, so the plugin content
     * inherits the container's own border, spacing, font and scaling. The groups insert themselves
     * in the native order and toggle visibility with the algorithm choice: while the Editor's
     * generator runs, the native Preset and Parameters groups alone are visible.</p>
     *
     * <p>Preset chrome reuses this framework's bundle keys of the parameter dialog through
     * {@link AutoMeshParameterDialog}, because one text must describe one action wherever it is
     * offered.</p>
     */
    private final class Inline {

        private final Object container;
        private final Object mount;
        private final ClassLoader loader;
        private final Object unit;
        private final Class<?> function1;
        private final Class<?> widgetType;
        private final Class<?>[] widgetParameter;
        private final Class<?>[] callbackParameter;
        private final Object nativePresetBox;
        private final Object nativeSettingsBox;
        private final Object algorithmCombo;
        private final Object algorithmGroup;
        private final Object presetCombo;
        private final Object saveButton;
        private final Object addButton;
        private final Object deleteButton;
        private final PresetMenu presetMenu;
        private final Object presetGroup;
        private final Object settingsGroup;
        private final List<InlineRow> parameterRows = new ArrayList<>();
        private final Timer commitTimer = new Timer(PARAMETER_COMMIT_DELAY_MILLIS, event -> commitParameters());
        private AutoMeshParameterModel model;
        private boolean writing;

        private Inline(final Object container, final Object mount) throws ReflectiveOperationException {
            this.container = container;
            this.mount = mount;
            this.loader = container.getClass().getClassLoader();
            this.unit = type(UNIT_TYPE).getField(UNIT_INSTANCE_FIELD).get(null);
            this.function1 = type(FUNCTION1_TYPE);
            this.widgetType = type(WIDGET_TYPE);
            this.widgetParameter = new Class<?>[] {widgetType};
            this.callbackParameter = new Class<?>[] {function1};
            final List<?> children = childrenOf(mount);
            this.nativePresetBox = children.get(NATIVE_PRESET_INDEX);
            this.nativeSettingsBox = children.get(NATIVE_SETTINGS_INDEX);
            this.algorithmCombo =
                    buildCombo(labels(controller.algorithmEntries()), controller.selectedAlgorithmIndex());
            tryInvoke(algorithmCombo, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, ALGORITHM_TOOLTIP_KEY));
            this.algorithmGroup = buildAlgorithmGroup();
            this.presetCombo = buildCombo(controller.presetEntries(), controller.selectedPresetIndex());
            tryInvoke(presetCombo, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, PRESET_TOOLTIP_KEY));
            this.saveButton = buildSaveButton();
            this.addButton = buildAddButton();
            this.deleteButton = buildDeleteButton();
            this.presetMenu = buildPresetMenu();
            this.presetGroup = buildPresetGroup();
            this.settingsGroup = buildSettingsGroup();
            this.commitTimer.setRepeats(false);
        }

        /**
         * Inserts the three groups and gives every control its behaviour.
         *
         * <p>The insert indices are the native ones: the Algorithm group leads the centre, the
         * plugin Preset group follows the native Preset group, and the plugin Parameters group
         * follows the native Parameters group. Insertions run before the callbacks are installed,
         * so filling a combo can never report a selection the user did not make.</p>
         */
        private void attach() throws ReflectiveOperationException {
            insert(algorithmGroup, ALGORITHM_GROUP_INDEX);
            insert(presetGroup, PRESET_GROUP_INDEX);
            insert(settingsGroup, SETTINGS_GROUP_INDEX);
            installCallbacks();
            refresh();
        }

        private void insert(final Object group, final int index) throws ReflectiveOperationException {
            invoke(mount, ADD_METHOD, new Class<?>[] {widgetType, int.class}, group, index);
        }

        private Object buildCombo(final List<String> items, final int selectedIndex)
                throws ReflectiveOperationException {
            final Object combo = instantiate(type(COMBO_TYPE), ITEM_PARAMETER, items);
            tryInvoke(combo, SET_SELECTED_INDEX, COUNT_PARAMETER, selectedIndex);
            return combo;
        }

        private Object buildAlgorithmGroup() throws ReflectiveOperationException {
            final Object group = titledGroup(AutoMeshText.text(locale, ALGORITHM_LABEL_KEY));
            final Object row = instantiate(type(ROW_TYPE), NO_PARAMETERS);
            growToFill(row, algorithmCombo);
            invoke(row, UNARY_PLUS, widgetParameter, algorithmCombo);
            invoke(group, UNARY_PLUS, widgetParameter, row);
            appendGroupSpacer(group);
            return group;
        }

        private Object buildPresetGroup() throws ReflectiveOperationException {
            final Object group = titledGroup(titleOr(nativePresetBox, PRESET_LABEL_KEY));
            final Object row = instantiate(type(ROW_TYPE), NO_PARAMETERS);
            growToFill(row, presetCombo);
            invoke(row, UNARY_PLUS, widgetParameter, presetCombo);
            invoke(group, UNARY_PLUS, widgetParameter, row);
            appendGroupSpacer(group);
            final Object actions = instantiate(type(ROW_TYPE), NO_PARAMETERS);
            final Object filler = instantiate(type(SPACER_TYPE), NO_PARAMETERS);
            growToFill(actions, filler);
            invoke(actions, UNARY_PLUS, widgetParameter, filler);
            invoke(actions, UNARY_PLUS, widgetParameter, saveButton);
            invoke(actions, UNARY_PLUS, widgetParameter, addButton);
            invoke(actions, UNARY_PLUS, widgetParameter, deleteButton);
            if (presetMenu.menu() != null) {
                invoke(actions, UNARY_PLUS, widgetParameter, presetMenu.menu());
            }
            invoke(group, UNARY_PLUS, widgetParameter, actions);
            appendGroupSpacer(group);
            return group;
        }

        private Object buildSettingsGroup() throws ReflectiveOperationException {
            return titledGroup(titleOr(nativeSettingsBox, AutoMeshParameterDialog.TITLE_KEY));
        }

        private Object buildSaveButton() throws ReflectiveOperationException {
            return buildButton(DLG_BUTTON_TYPE, AutoMeshParameterDialog.PRESET_SAVE_KEY, null);
        }

        private Object buildAddButton() throws ReflectiveOperationException {
            return buildButton(BUTTON_TYPE, AutoMeshParameterDialog.PRESET_ADD_KEY, SAVE_AS_BUTTON_INDEX);
        }

        private Object buildDeleteButton() throws ReflectiveOperationException {
            return buildButton(BUTTON_TYPE, AutoMeshParameterDialog.PRESET_DELETE_KEY, DELETE_BUTTON_INDEX);
        }

        /**
         * Builds one preset action with the native wording.
         *
         * <p>The native group is the only place the host localizes these actions, so its own text is
         * copied when it exists. The bundle text is the fallback, which keeps a host build the row
         * cannot read from showing an empty button.</p>
         *
         * <p>A host dialog button appends its own ellipsis in {@code setText}. The copied text therefore
         * loses one such ellipsis first, so the copy shows the same wording as the native action instead
         * of two ellipses.</p>
         *
         * @param typeName the host button class of the action
         * @param key the bundle key of the action
         * @param ordinal the position among the native plain buttons, or {@code null} for the dialog
         *     button
         */
        private Object buildButton(final String typeName, final String key, final Integer ordinal)
                throws ReflectiveOperationException {
            final String copied = ordinal == null
                    ? dialogButtonText(textOf(firstWidget(nativePresetBox, DLG_BUTTON_TYPE, null)))
                    : textOf(widgetAt(nativePresetBox, BUTTON_TYPE, DLG_BUTTON_TYPE, ordinal));
            final Object button = instantiate(type(typeName), NO_PARAMETERS);
            invoke(button, SET_TEXT, TEXT_PARAMETER, textOr(copied, key));
            return button;
        }

        /** Removes the ellipsis a host dialog button appends, so a copy does not grow a second one. */
        private String dialogButtonText(final String copied) {
            if (copied == null || !copied.endsWith(DIALOG_BUTTON_ELLIPSIS)) {
                return copied;
            }
            return copied.substring(0, copied.length() - DIALOG_BUTTON_ELLIPSIS.length());
        }

        private Object buildMenuItem(final String text) throws ReflectiveOperationException {
            final Object item = instantiate(type(MENU_ITEM_TYPE), NO_PARAMETERS);
            invoke(item, SET_TEXT, TEXT_PARAMETER, text);
            return item;
        }

        /**
         * Builds the preset menu with its two less frequent actions.
         *
         * <p>The host popup widget owns a look-and-feel class that a host build can lack, so a
         * refused menu must not cost the three direct actions. The menu is therefore optional: when
         * the host refuses it, the group keeps Save, Save as and Delete.</p>
         */
        private PresetMenu buildPresetMenu() {
            try {
                final Object menu = instantiate(type(BUTTON_MENU_TYPE), TEXT_PARAMETER, MENU_BUTTON_TEXT);
                final Object rename =
                        buildMenuItem(AutoMeshText.text(locale, AutoMeshParameterDialog.PRESET_RENAME_KEY));
                final Object restore =
                        buildMenuItem(textOr(nativeMenuItemText(), AutoMeshParameterDialog.PRESET_RESTORE_KEY));
                invoke(menu, ADD_METHOD, new Class<?>[] {type(MENU_ITEM_TYPE)}, rename);
                invoke(menu, ADD_METHOD, new Class<?>[] {type(MENU_ITEM_TYPE)}, restore);
                return new PresetMenu(menu, rename, restore);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError refused) {
                FatalErrors.rethrowIfFatal(refused);
                diagnostic("auto mesh selector: the host button menu is unavailable, the preset menu is omitted: "
                        + describe(refused));
                return new PresetMenu(null, null, null);
            }
        }

        private void installCallbacks() throws ReflectiveOperationException {
            invoke(
                    algorithmCombo,
                    SET_ON_SELECTED,
                    callbackParameter,
                    callback(() -> selectAlgorithm(selectedIndex(algorithmCombo))));
            invoke(
                    presetCombo,
                    SET_ON_SELECTED,
                    callbackParameter,
                    callback(() -> selectPreset(selectedIndex(presetCombo))));
            invoke(saveButton, ADD_ON_ACTION, callbackParameter, callback(this::savePreset));
            invoke(addButton, ADD_ON_ACTION, callbackParameter, callback(this::savePresetAs));
            invoke(deleteButton, ADD_ON_ACTION, callbackParameter, callback(this::deletePreset));
            if (presetMenu.menu() != null) {
                invoke(presetMenu.renameItem(), ADD_ON_ACTION, callbackParameter, callback(this::renamePreset));
                invoke(presetMenu.restoreItem(), ADD_ON_ACTION, callbackParameter, callback(this::restorePresets));
            }
        }

        private void selectAlgorithm(final int index) {
            controller.selectAlgorithm(index);
            refresh();
        }

        /**
         * Applies one preset choice and republishes both plugin groups.
         *
         * <p>The custom placeholder is not a preset, so choosing it changes no value. The combo then
         * returns to the entry the selection still names, which keeps the shown preset honest.</p>
         */
        private void selectPreset(final int index) {
            if (controller.selectPreset(index)) {
                refresh();
                return;
            }
            syncPresetState();
        }

        private void savePreset() {
            final Optional<String> active = controller.selectedPresetName();
            if (active.isEmpty()) {
                diagnostic("auto mesh selector: no preset is selected, the values are not saved");
                return;
            }
            if (model == null) {
                return;
            }
            try {
                controller.savePreset(active.get(), model.values());
            } catch (IllegalArgumentException rejected) {
                diagnostic("auto mesh selector: preset " + active.get() + " was refused: "
                        + singleLine(String.valueOf(rejected.getMessage())));
                return;
            }
            refresh();
        }

        private void savePresetAs() {
            if (model == null) {
                return;
            }
            final String name = prompt(AutoMeshParameterDialog.PRESET_PROMPT_KEY, "");
            if (name == null || name.isBlank()) {
                return;
            }
            try {
                controller.savePreset(name.trim(), model.values());
            } catch (IllegalArgumentException rejected) {
                diagnostic(
                        "auto mesh selector: preset was refused: " + singleLine(String.valueOf(rejected.getMessage())));
                return;
            }
            refresh();
        }

        private void renamePreset() {
            final Optional<String> active = controller.selectedPresetName();
            if (active.isEmpty()) {
                return;
            }
            final String name = prompt(AutoMeshParameterDialog.PRESET_RENAME_PROMPT_KEY, active.get());
            if (name == null || name.isBlank()) {
                return;
            }
            try {
                controller.renamePreset(active.get(), name.trim());
            } catch (IllegalArgumentException rejected) {
                diagnostic("auto mesh selector: preset " + active.get() + " was not renamed: "
                        + singleLine(String.valueOf(rejected.getMessage())));
                return;
            }
            refresh();
        }

        private void deletePreset() {
            final Optional<String> active = controller.selectedPresetName();
            if (active.isEmpty()) {
                return;
            }
            if (controller.deletePreset(active.get())) {
                refresh();
            }
        }

        private void restorePresets() {
            controller.restoreDefaultPresets();
            refresh();
        }

        private String prompt(final String key, final String initialValue) {
            return JOptionPane.showInputDialog(ownerOf(container), AutoMeshText.text(locale, key), initialValue);
        }

        /**
         * Republishes every plugin control from the selection state.
         *
         * <p>One entry point keeps the preset list, the preset actions, the parameter rows and the
         * visible group pair in step after any change, including a preset the user just deleted.</p>
         */
        private void refresh() {
            try {
                commitParameters();
                fillCombo(presetCombo, controller.presetEntries());
                syncPresetState();
                rebuildParameterRows();
                refreshVisibility();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                FatalErrors.rethrowIfFatal(failure);
                diagnostic("auto mesh selector: inline group refresh failed with " + describe(failure));
            }
        }

        private void syncPresetState() {
            final int index = controller.selectedPresetIndex();
            tryInvoke(presetCombo, SET_SELECTED_INDEX, COUNT_PARAMETER, index);
            tryInvoke(deleteButton, SET_ENABLED, FLAG_PARAMETER, index > NO_PRESET_SELECTION);
        }

        private void refreshVisibility() {
            final boolean nativeSelected = controller.selectedAlgorithm().isEmpty();
            tryInvoke(nativePresetBox, SET_VISIBLE, FLAG_PARAMETER, nativeSelected);
            tryInvoke(nativeSettingsBox, SET_VISIBLE, FLAG_PARAMETER, nativeSelected);
            tryInvoke(presetGroup, SET_VISIBLE, FLAG_PARAMETER, !nativeSelected);
            tryInvoke(settingsGroup, SET_VISIBLE, FLAG_PARAMETER, !nativeSelected);
        }

        /**
         * Rebuilds the inline parameter rows of the selected algorithm.
         *
         * <p>The rows are host widgets of the same family the native property panel uses, so the
         * slider, checkbox and field of a Cubism parameter appear in place, with the dialog's own
         * validation behind them. Labels share one width, because ragged label columns make a
         * parameter list unreadable.</p>
         */
        private void rebuildParameterRows() throws ReflectiveOperationException {
            parameterRows.clear();
            tryInvoke(settingsGroup, REMOVE_ALL, NO_PARAMETERS);
            model = controller.parameterModel().orElse(null);
            if (model == null) {
                return;
            }
            final List<AutoMeshParameterModel.Row> rows = model.rows();
            final List<Object> labels = new ArrayList<>(rows.size());
            int labelWidth = PARAMETER_LABEL_MIN_WIDTH;
            for (final AutoMeshParameterModel.Row row : rows) {
                final Object label = buildParameterLabel(row);
                labels.add(label);
                final Object measured = tryInvoke(label, LABEL_WIDTH_GETTER, NO_PARAMETERS);
                if (measured instanceof Number number) {
                    labelWidth = Math.max(labelWidth, number.intValue());
                }
            }
            final int width = Math.min(PARAMETER_LABEL_MAX_WIDTH, labelWidth);
            for (int index = 0; index < rows.size(); index++) {
                appendParameterRow(rows.get(index), labels.get(index), width);
            }
        }

        /**
         * Builds the label column of one parameter row.
         *
         * <p>The label carries the name the plugin declared, aligned to the trailing edge, and the
         * declared description as its tooltip. The plugin localizes both, so the runtime never invents
         * wording for a parameter it does not own.</p>
         */
        private Object buildParameterLabel(final AutoMeshParameterModel.Row row) throws ReflectiveOperationException {
            final Object label = instantiate(type(LABEL_TYPE), TEXT_PARAMETER, row.label());
            final String description = row.descriptor().description();
            if (!description.isEmpty()) {
                tryInvoke(label, SET_TOOLTIP, TEXT_PARAMETER, description);
            }
            invoke(
                    label,
                    SET_MARGIN,
                    new Class<?>[] {type(INSETS_TYPE)},
                    insets(PARAMETER_LABEL_TOP_MARGIN, 0, 0, PARAMETER_LABEL_RIGHT_MARGIN));
            invoke(
                    label,
                    SET_HORIZONTAL_ALIGNMENT,
                    new Class<?>[] {type(ALIGNMENT_TYPE)},
                    type(ALIGNMENT_TYPE).getField(TRAILING_ALIGNMENT_FIELD).get(null));
            return label;
        }

        private void appendParameterRow(final AutoMeshParameterModel.Row row, final Object label, final int labelWidth)
                throws ReflectiveOperationException {
            invoke(label, SET_PREF_WIDTH, COUNT_PARAMETER, labelWidth);
            final Object line = instantiate(type(ROW_TYPE), NO_PARAMETERS);
            invoke(
                    line,
                    SET_MARGIN,
                    new Class<?>[] {type(INSETS_TYPE)},
                    insets(PARAMETER_ROW_TOP_MARGIN, 0, PARAMETER_ROW_BOTTOM_MARGIN, 0));
            invoke(line, SET_PREF_WIDTH, COUNT_PARAMETER, PARAMETER_ROW_PREF_WIDTH);
            invoke(line, UNARY_PLUS, widgetParameter, label);
            final Object control = parameterControl(row);
            growToFill(line, control);
            invoke(line, UNARY_PLUS, widgetParameter, control);
            invoke(settingsGroup, UNARY_PLUS, widgetParameter, line);
        }

        private Object parameterControl(final AutoMeshParameterModel.Row row) throws ReflectiveOperationException {
            final AlgorithmParameterDescriptor descriptor = row.descriptor();
            if (descriptor instanceof AlgorithmParameterDescriptor.IntParameter integer) {
                return integerControl(row, integer);
            }
            if (descriptor instanceof AlgorithmParameterDescriptor.DoubleParameter decimal) {
                return decimalControl(row, decimal);
            }
            if (descriptor instanceof AlgorithmParameterDescriptor.BoolParameter) {
                return checkBoxControl(row);
            }
            if (descriptor instanceof AlgorithmParameterDescriptor.EnumParameter enumeration) {
                return enumControl(row, enumeration);
            }
            return textControl(row);
        }

        private Object integerControl(
                final AutoMeshParameterModel.Row row, final AlgorithmParameterDescriptor.IntParameter descriptor)
                throws ReflectiveOperationException {
            final Object control = instantiate(type(SLIDABLE_INT_TYPE), NO_PARAMETERS);
            invoke(control, SET_MIN, COUNT_PARAMETER, descriptor.min());
            invoke(control, SET_MAX, COUNT_PARAMETER, descriptor.max());
            invoke(control, SET_SLIDE_WEIGHT, FLOAT_PARAMETER, PARAMETER_SLIDE_WEIGHT);
            invoke(control, SET_VALUE, COUNT_PARAMETER, Integer.parseInt(row.value()));
            final String parameterId = row.parameterId();
            invoke(control, SET_ON_CHANGED, callbackParameter, callback(() -> {
                final Object value = tryInvoke(control, GET_VALUE, NO_PARAMETERS);
                if (value instanceof Number number) {
                    applyParameter(parameterId, Integer.toString(number.intValue()));
                }
            }));
            parameterRows.add(new InlineRow(
                    parameterId, value -> invoke(control, SET_VALUE, COUNT_PARAMETER, Integer.parseInt(value))));
            return control;
        }

        private Object decimalControl(
                final AutoMeshParameterModel.Row row, final AlgorithmParameterDescriptor.DoubleParameter descriptor)
                throws ReflectiveOperationException {
            final Object control = instantiate(type(SLIDABLE_FLOAT_TYPE), NO_PARAMETERS);
            invoke(control, SET_MIN, FLOAT_PARAMETER, (float) descriptor.min());
            invoke(control, SET_MAX, FLOAT_PARAMETER, (float) descriptor.max());
            invoke(control, SET_KETA, COUNT_PARAMETER, PARAMETER_SLIDER_DECIMALS);
            invoke(control, SET_SLIDE_WEIGHT, FLOAT_PARAMETER, PARAMETER_SLIDE_WEIGHT);
            invoke(control, SET_VALUE, FLOAT_PARAMETER, Float.parseFloat(row.value()));
            final String parameterId = row.parameterId();
            invoke(control, SET_ON_CHANGED, callbackParameter, callback(() -> {
                final Object value = tryInvoke(control, GET_VALUE, NO_PARAMETERS);
                if (value instanceof Number number) {
                    applyParameter(
                            parameterId,
                            AutoMeshValueCodec.canonicalDecimal(number.doubleValue(), descriptor.decimals()));
                }
            }));
            parameterRows.add(new InlineRow(
                    parameterId, value -> invoke(control, SET_VALUE, FLOAT_PARAMETER, Float.parseFloat(value))));
            return control;
        }

        private Object checkBoxControl(final AutoMeshParameterModel.Row row) throws ReflectiveOperationException {
            final Object control = instantiate(type(CHECK_BOX_TYPE), NO_PARAMETERS);
            invoke(control, SET_CHECKED, BOXED_BOOLEAN_PARAMETER, Boolean.valueOf(row.value()));
            final String parameterId = row.parameterId();
            invoke(control, ADD_ON_ACTION, callbackParameter, callback(() -> {
                final Object checked = tryInvoke(control, IS_CHECKED, NO_PARAMETERS);
                applyParameter(parameterId, Boolean.toString(Boolean.TRUE.equals(checked)));
            }));
            parameterRows.add(new InlineRow(
                    parameterId,
                    value -> invoke(control, SET_CHECKED, BOXED_BOOLEAN_PARAMETER, Boolean.valueOf(value))));
            return control;
        }

        private Object enumControl(
                final AutoMeshParameterModel.Row row, final AlgorithmParameterDescriptor.EnumParameter descriptor)
                throws ReflectiveOperationException {
            final List<String> labels = new ArrayList<>(descriptor.options().size());
            final List<String> values = new ArrayList<>(descriptor.options().size());
            for (final AlgorithmParameterDescriptor.EnumParameter.Option option : descriptor.options()) {
                values.add(option.value());
                labels.add(option.label());
            }
            final Object control = instantiate(type(COMBO_TYPE), ITEM_PARAMETER, labels);
            invoke(control, SET_SELECTED_INDEX, COUNT_PARAMETER, Math.max(0, values.indexOf(row.value())));
            final String parameterId = row.parameterId();
            invoke(control, SET_ON_SELECTED, callbackParameter, callback(() -> {
                final int index = selectedIndex(control);
                if (index >= 0 && index < values.size()) {
                    applyParameter(parameterId, values.get(index));
                }
            }));
            parameterRows.add(new InlineRow(
                    parameterId,
                    value -> invoke(control, SET_SELECTED_INDEX, COUNT_PARAMETER, Math.max(0, values.indexOf(value)))));
            return control;
        }

        private Object textControl(final AutoMeshParameterModel.Row row) throws ReflectiveOperationException {
            final Object control = instantiate(type(TEXT_FIELD_TYPE), TEXT_PARAMETER, row.value());
            final String parameterId = row.parameterId();
            invoke(control, SET_ON_ACTION, callbackParameter, callback(() -> {
                final Object text = tryInvoke(control, GET_TEXT, NO_PARAMETERS);
                applyParameter(parameterId, text instanceof String value ? value : "");
            }));
            parameterRows.add(new InlineRow(parameterId, value -> invoke(control, SET_TEXT, TEXT_PARAMETER, value)));
            return control;
        }

        /**
         * Stores one inline edit and republishes the preset bar.
         *
         * <p>A rejected value keeps the previous one, because the algorithm would refuse it at run
         * time. The control is then rewritten from the model, so what the user sees and what a run
         * would use can never differ.</p>
         *
         * <p>The accepted set reaches the selection state at once and the preset file after the edit
         * stream ends, so a drag neither stutters on forced writes nor leaves a run on a stale value.</p>
         */
        private void applyParameter(final String parameterId, final String value) {
            if (writing || model == null) {
                return;
            }
            if (!model.set(parameterId, value)) {
                diagnostic("auto mesh selector: parameter " + parameterId + " refused " + singleLine(value)
                        + ", the previous value is kept");
                restoreParameter(parameterId);
                return;
            }
            controller.storeParametersTransient(model.algorithm(), model.values());
            syncPresetState();
            commitTimer.restart();
        }

        /** Writes the pending edit stream to the preset store. */
        private void commitParameters() {
            commitTimer.stop();
            if (model == null) {
                return;
            }
            controller.storeParameters(model.algorithm(), model.values());
        }

        private void restoreParameter(final String parameterId) {
            if (model == null) {
                return;
            }
            final String value = model.values().get(parameterId);
            if (value == null) {
                return;
            }
            for (final InlineRow row : parameterRows) {
                if (!row.parameterId().equals(parameterId)) {
                    continue;
                }
                writing = true;
                try {
                    row.writer().write(value);
                } catch (ReflectiveOperationException | RuntimeException refused) {
                    diagnostic(
                            "auto mesh selector: parameter " + parameterId + " was not restored: " + describe(refused));
                } finally {
                    writing = false;
                }
                return;
            }
        }

        private Object callback(final Runnable body) {
            return proxy(function1, unit, (invocation, method, arguments) -> {
                if (INVOKE_METHOD.equals(method.getName())) {
                    guard(body);
                }
                return unit;
            });
        }

        private void growToFill(final Object row, final Object child) throws ReflectiveOperationException {
            final Class<?> growType = type(GROW_TYPE);
            invoke(
                    row,
                    SET_HGROW,
                    new Class<?>[] {widgetType, growType},
                    child,
                    growType.getField(GROW_WEIGHT_FIELD).get(null));
        }

        private void appendGroupSpacer(final Object group) throws ReflectiveOperationException {
            invoke(
                    group,
                    UNARY_PLUS,
                    widgetParameter,
                    instantiate(type(SPACER_TYPE), SIZE_PARAMETER, GROUP_SPACER_WIDTH, GROUP_SPACER_HEIGHT));
        }

        private Object titledGroup(final String title) throws ReflectiveOperationException {
            final Object group = instantiate(type(VBOX_TYPE), NO_PARAMETERS);
            final Class<?> borderType = type(BORDER_TYPE);
            final Object border = instantiate(
                    type(TITLED_BORDER_TYPE),
                    new Class<?>[] {String.class, borderType, int.class, type(DEFAULT_MARKER_TYPE)},
                    title,
                    null,
                    TITLE_BORDER_NULL_BORDER_MASK,
                    null);
            invoke(group, SET_BORDER, new Class<?>[] {borderType}, border);
            return group;
        }

        private Object insets(final int top, final int right, final int bottom, final int left)
                throws ReflectiveOperationException {
            return instantiate(type(INSETS_TYPE), INSETS_PARAMETER, top, right, bottom, left);
        }

        private String titleOr(final Object box, final String key) {
            final Object border = tryInvoke(box, GET_BORDER, NO_PARAMETERS);
            final Object title = border == null ? null : tryInvoke(border, GET_TITLE, NO_PARAMETERS);
            return title instanceof String text && !text.isBlank() ? text : AutoMeshText.text(locale, key);
        }

        private String textOr(final String copied, final String key) {
            return copied == null ? AutoMeshText.text(locale, key) : copied;
        }

        private String nativeMenuItemText() {
            final Object menu = firstWidget(nativePresetBox, BUTTON_MENU_TYPE, null);
            final Object popup = tryInvoke(menu, GET_POPUP_MENU, NO_PARAMETERS);
            final Object items = tryInvoke(popup, POPUP_ITEMS_METHOD, NO_PARAMETERS);
            if (!(items instanceof List<?> list) || list.size() <= FIRST_MENU_ITEM_INDEX) {
                return null;
            }
            return textOf(list.get(FIRST_MENU_ITEM_INDEX));
        }

        private Class<?> type(final String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }
    }

    /** One built parameter row: the declared parameter it edits and the writer that shows a value. */
    private record InlineRow(String parameterId, ParameterWriter writer) {}

    /** The preset menu of one group; every member is {@code null} when the host refuses a menu. */
    private record PresetMenu(Object menu, Object renameItem, Object restoreItem) {}

    /** Shows one canonical value in an inline control without reporting it as an edit. */
    @FunctionalInterface
    private interface ParameterWriter {
        void write(String value) throws ReflectiveOperationException;
    }

    /**
     * Returns the first widget of one host type below {@code root}.
     *
     * @param root the widget to search, itself included
     * @param typeName the host class of the wanted widget
     * @param excludedTypeName a host class to skip, or {@code null}
     */
    private static Object firstWidget(final Object root, final String typeName, final String excludedTypeName) {
        final List<Object> found = widgetsOfType(root, typeName, excludedTypeName);
        return found.isEmpty() ? null : found.get(0);
    }

    private static Object widgetAt(
            final Object root, final String typeName, final String excludedTypeName, final int index) {
        final List<Object> found = widgetsOfType(root, typeName, excludedTypeName);
        return index < found.size() ? found.get(index) : null;
    }

    /**
     * Collects the widgets of one host type below {@code root}, in tree order.
     *
     * <p>A refused class lookup answers no widget instead of failing: a host build without that
     * widget class must still show the plugin group with the bundle wording.</p>
     */
    private static List<Object> widgetsOfType(final Object root, final String typeName, final String excludedTypeName) {
        final List<Object> found = new ArrayList<>(1);
        if (root == null) {
            return found;
        }
        final ClassLoader loader = root.getClass().getClassLoader();
        try {
            final Class<?> type = Class.forName(typeName, false, loader);
            final Class<?> excluded = excludedTypeName == null ? null : Class.forName(excludedTypeName, false, loader);
            collectWidgets(root, type, excluded, found, 0);
        } catch (ClassNotFoundException | LinkageError absent) {
            return List.of();
        }
        return found;
    }

    private static void collectWidgets(
            final Object widget,
            final Class<?> type,
            final Class<?> excluded,
            final List<Object> found,
            final int depth) {
        if (widget == null || depth > MAX_WIDGET_SCAN_DEPTH) {
            return;
        }
        if (type.isInstance(widget) && (excluded == null || !excluded.isInstance(widget))) {
            found.add(widget);
        }
        final Object children = tryInvoke(widget, HOST_CHILDREN, NO_PARAMETERS);
        if (children instanceof List<?> list) {
            for (final Object child : list) {
                collectWidgets(child, type, excluded, found, depth + 1);
            }
        }
    }

    private static String textOf(final Object widget) {
        final Object text = widget == null ? null : tryInvoke(widget, GET_TEXT, NO_PARAMETERS);
        return text instanceof String value && !value.isBlank() ? value : null;
    }

    /**
     * Builds the row from the host's own widgets.
     *
     * <p>The preset combo is refilled whenever the algorithm changes, because the preset list
     * belongs to the selected algorithm. Refilling replaces the combo's model instead of the widget,
     * so the row keeps its layout and its focus owner.</p>
     */
    private Object build(final Object container) throws ReflectiveOperationException {
        final ClassLoader loader = container.getClass().getClassLoader();
        final Object unit = Class.forName(UNIT_TYPE, false, loader)
                .getField(UNIT_INSTANCE_FIELD)
                .get(null);
        final Class<?> function1 = Class.forName(FUNCTION1_TYPE, false, loader);
        final Class<?> widgetType = Class.forName(WIDGET_TYPE, false, loader);
        final Class<?>[] widgetParameter = {widgetType};
        final Class<?>[] callbackParameter = {function1};

        final Object row = instantiate(loader, ROW_TYPE, NO_PARAMETERS);
        tryInvoke(row, MARGIN_METHOD, COUNT_PARAMETER, ROW_MARGIN);

        final Object algorithmLabel =
                instantiate(loader, LABEL_TYPE, TEXT_PARAMETER, AutoMeshText.text(locale, ALGORITHM_LABEL_KEY));
        tryInvoke(algorithmLabel, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, ALGORITHM_TOOLTIP_KEY));
        invoke(row, UNARY_PLUS, widgetParameter, algorithmLabel);

        final Object algorithmCombo =
                instantiate(loader, COMBO_TYPE, ITEM_PARAMETER, labels(controller.algorithmEntries()));
        tryInvoke(algorithmCombo, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, ALGORITHM_TOOLTIP_KEY));
        tryInvoke(algorithmCombo, SET_SELECTED_INDEX, COUNT_PARAMETER, controller.selectedAlgorithmIndex());
        invoke(row, UNARY_PLUS, widgetParameter, algorithmCombo);

        invoke(
                row,
                UNARY_PLUS,
                widgetParameter,
                instantiate(loader, SPACER_TYPE, SIZE_PARAMETER, SPACER_WIDTH, SPACER_HEIGHT));

        final Object presetLabel =
                instantiate(loader, LABEL_TYPE, TEXT_PARAMETER, AutoMeshText.text(locale, PRESET_LABEL_KEY));
        tryInvoke(presetLabel, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, PRESET_TOOLTIP_KEY));
        invoke(row, UNARY_PLUS, widgetParameter, presetLabel);

        final Object presetCombo = instantiate(loader, COMBO_TYPE, ITEM_PARAMETER, controller.presetEntries());
        tryInvoke(presetCombo, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, PRESET_TOOLTIP_KEY));
        tryInvoke(presetCombo, SET_SELECTED_INDEX, COUNT_PARAMETER, controller.selectedPresetIndex());
        invoke(row, UNARY_PLUS, widgetParameter, presetCombo);

        invoke(
                row,
                UNARY_PLUS,
                widgetParameter,
                instantiate(loader, SPACER_TYPE, SIZE_PARAMETER, SPACER_WIDTH, SPACER_HEIGHT));

        final Object parametersButton = instantiate(loader, BUTTON_TYPE, NO_PARAMETERS);
        tryInvoke(parametersButton, SET_TEXT, TEXT_PARAMETER, AutoMeshText.text(locale, PARAMETERS_BUTTON_KEY));
        tryInvoke(parametersButton, SET_TOOLTIP, TEXT_PARAMETER, AutoMeshText.text(locale, PARAMETERS_TOOLTIP_KEY));
        tryInvoke(parametersButton, SET_ENABLED, FLAG_PARAMETER, controller.parametersAvailable());
        invoke(row, UNARY_PLUS, widgetParameter, parametersButton);

        final Runnable syncPresets = () -> {
            try {
                fillCombo(presetCombo, controller.presetEntries());
                tryInvoke(presetCombo, SET_SELECTED_INDEX, COUNT_PARAMETER, controller.selectedPresetIndex());
                tryInvoke(parametersButton, SET_ENABLED, FLAG_PARAMETER, controller.parametersAvailable());
            } catch (ReflectiveOperationException failure) {
                diagnostic("auto mesh selector: preset list refresh failed with " + describe(failure));
            }
        };

        invoke(
                algorithmCombo,
                SET_ON_SELECTED,
                callbackParameter,
                proxy(function1, unit, (proxy, method, arguments) -> {
                    if (INVOKE_METHOD.equals(method.getName())) {
                        guard(() -> controller.selectAlgorithm(selectedIndex(algorithmCombo)));
                        guard(syncPresets);
                    }
                    return unit;
                }));
        invoke(presetCombo, SET_ON_SELECTED, callbackParameter, proxy(function1, unit, (proxy, method, arguments) -> {
            if (INVOKE_METHOD.equals(method.getName())) {
                guard(() -> controller.selectPreset(selectedIndex(presetCombo)));
            }
            return unit;
        }));
        invoke(
                parametersButton,
                ADD_ON_ACTION,
                callbackParameter,
                proxy(function1, unit, (proxy, method, arguments) -> {
                    if (INVOKE_METHOD.equals(method.getName())) {
                        guard(() -> openParameters(container, syncPresets));
                    }
                    return unit;
                }));
        return row;
    }

    /**
     * Opens the parameter dialog and republishes the preset list afterwards.
     *
     * <p>The dialog can add, rename, delete or restore presets, so the native popup's own preset
     * combo is stale the moment the dialog closes. Republishing keeps both views on one list.</p>
     */
    private void openParameters(final Object container, final Runnable syncPresets) {
        AutoMeshParameterDialog.show(ownerOf(container), controller, locale);
        guard(syncPresets);
    }

    /**
     * Resolves the window the dialog centres on.
     *
     * <p>An Editor container is a host widget, not an AWT component, so the window is reached through
     * the widget's own Swing component. A container without one leaves the dialog screen-centred.</p>
     */
    private static Window ownerOf(final Object container) {
        final Component component = awtComponentOf(container);
        return component == null ? null : SwingUtilities.getWindowAncestor(component);
    }

    private static Component awtComponentOf(final Object container) {
        if (container instanceof Component component) {
            return component;
        }
        final Object widget = tryInvoke(container, COMPONENT_GETTER, NO_PARAMETERS);
        return widget instanceof Component component ? component : null;
    }

    /**
     * Runs one row callback.
     *
     * <p>Every callback runs on the event dispatch thread, and every exception becomes a
     * diagnostic: a broken plugin algorithm must never reach the host's own event pump.</p>
     */
    private void guard(final Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            diagnostic("auto mesh selector: callback failed with " + describe(failure));
        }
    }

    private static List<String> labels(final List<AutoMeshSelectorController.AlgorithmEntry> entries) {
        final List<String> labels = new ArrayList<>(entries.size());
        for (final AutoMeshSelectorController.AlgorithmEntry entry : entries) {
            labels.add(entry.label());
        }
        return labels;
    }

    /**
     * Replaces the items of one host combo box.
     *
     * <p>The host combo box keeps its items in its own list model, and that model class is the only
     * type {@code setModel} accepts. The model class is therefore read from the live combo and
     * instantiated through its {@code (List)} constructor, so no obfuscated host type is named.</p>
     *
     * <p>The read must not be softened: the host's {@code getModel} casts its Swing model to that
     * own type, so a combo built without items answers a cast failure instead of a model.</p>
     */
    private static void fillCombo(final Object combo, final List<String> items) throws ReflectiveOperationException {
        final Object model = invoke(combo, GET_MODEL, NO_PARAMETERS);
        if (model == null) {
            throw new NoSuchMethodException(combo.getClass().getName() + "#" + GET_MODEL + "() answered no model");
        }
        final Class<?> modelType = model.getClass();
        final Object replacement = instantiate(modelType, ITEM_PARAMETER, List.copyOf(items));
        invoke(combo, SET_MODEL, new Class<?>[] {modelType}, replacement);
        tryInvoke(combo, UPDATE_POPUP_WIDTH, NO_PARAMETERS);
    }

    private static int selectedIndex(final Object combo) {
        final Object value = tryInvoke(combo, GET_SELECTED_INDEX, NO_PARAMETERS);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void diagnostic(final String message) {
        try {
            diagnostics.accept(singleLine(message));
        } catch (RuntimeException | Error failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change whether the row is added.
        }
    }

    /**
     * Creates one host widget through its exact public constructor.
     *
     * <p>The constructor is named by its parameter types rather than selected by argument count,
     * because a widget family declares overloads whose bodies differ. A host build that keeps the
     * arity but changes a parameter is refused here with the signature it was asked for.</p>
     */
    private static Object instantiate(final Class<?> type, final Class<?>[] parameterTypes, final Object... arguments)
            throws ReflectiveOperationException {
        final Constructor<?> constructor;
        try {
            constructor = type.getConstructor(parameterTypes);
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodException(type.getName() + "#" + CONSTRUCTOR_NAME + render(parameterTypes)
                    + DECLARED_SEPARATOR + declaredConstructors(type));
        }
        return constructor.newInstance(arguments);
    }

    private static Object instantiate(
            final ClassLoader loader,
            final String className,
            final Class<?>[] parameterTypes,
            final Object... arguments)
            throws ReflectiveOperationException {
        return instantiate(Class.forName(className, false, loader), parameterTypes, arguments);
    }

    /**
     * Resolves one host widget type through the container's own loader.
     *
     * <p>A parameter type has to come from the loader that defines the widget, because the harness,
     * a plugin loader and the Editor loader each define their own copy of every host type.</p>
     */
    private static Class<?>[] parameterTypes(final Object container, final String... typeNames)
            throws ReflectiveOperationException {
        final ClassLoader loader = container.getClass().getClassLoader();
        final Class<?>[] types = new Class<?>[typeNames.length];
        for (int index = 0; index < typeNames.length; index++) {
            types[index] = Class.forName(typeNames[index], false, loader);
        }
        return types;
    }

    private static Object proxy(final Class<?> function1, final Object unit, final InvocationHandler body) {
        return Proxy.newProxyInstance(
                function1.getClassLoader(), new Class<?>[] {function1}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "toString":
                            return "TurboismAutoMeshSelectorCallback";
                        case "hashCode":
                            return System.identityHashCode(proxy);
                        case "equals":
                            return proxy == (arguments == null || arguments.length == 0 ? null : arguments[0]);
                        default:
                            return body.invoke(proxy, method, arguments);
                    }
                });
    }

    private static Object tryInvoke(
            final Object target, final String name, final Class<?>[] parameterTypes, final Object... arguments) {
        try {
            return invoke(target, name, parameterTypes, arguments);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    /**
     * Invokes one host member by its exact signature.
     *
     * <p>A refused lookup reports the wanted signature and the signatures the build declares, which
     * is the whole diagnosis a future mismatch needs from the log.</p>
     */
    private static Object invoke(
            final Object target, final String name, final Class<?>[] parameterTypes, final Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(name + render(parameterTypes));
        }
        final Method method;
        try {
            method = MethodHandleCache.method(target.getClass(), name, parameterTypes);
        } catch (NoSuchMethodException missing) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name + render(parameterTypes)
                    + DECLARED_SEPARATOR + declaredMembers(target.getClass(), name));
        }
        return method.invoke(target, arguments);
    }

    /**
     * Renders one failure as a single log line.
     *
     * <p>The wrapper exception an invocation always adds carries no information, so it is skipped.
     * Everything below it names the host member and the host cause that refused the attach.</p>
     */
    private static String describe(final Throwable failure) {
        final StringBuilder text = new StringBuilder();
        Throwable current = failure;
        while (current != null && current != current.getCause()) {
            if (current instanceof InvocationTargetException && current.getCause() != null) {
                current = current.getCause();
                continue;
            }
            if (text.length() > 0) {
                text.append(CAUSE_SEPARATOR);
            }
            text.append(current.getClass().getName());
            if (current.getMessage() != null) {
                text.append(": ").append(current.getMessage());
            }
            current = current.getCause();
        }
        return singleLine(text.toString());
    }

    private static String singleLine(final String message) {
        return message.replace('\n', ' ').replace('\r', ' ');
    }

    private static String render(final Class<?>[] parameterTypes) {
        final StringBuilder text = new StringBuilder("(");
        for (int index = 0; index < parameterTypes.length; index++) {
            if (index > 0) {
                text.append(", ");
            }
            text.append(parameterTypes[index].getTypeName());
        }
        return text.append(')').toString();
    }

    private static String declaredMembers(final Class<?> type, final String name) {
        final List<String> rendered = new ArrayList<>();
        for (final Method method : type.getMethods()) {
            if (name.equals(method.getName()) && rendered.size() < MAX_DECLARED_HINTS) {
                rendered.add(name + render(method.getParameterTypes()));
            }
        }
        return rendered.isEmpty() ? NO_DECLARED_MEMBERS : String.join(", ", rendered);
    }

    private static String declaredConstructors(final Class<?> type) {
        final List<String> rendered = new ArrayList<>();
        for (final Constructor<?> constructor : type.getConstructors()) {
            if (rendered.size() < MAX_DECLARED_HINTS) {
                rendered.add(CONSTRUCTOR_NAME + render(constructor.getParameterTypes()));
            }
        }
        return rendered.isEmpty() ? NO_DECLARED_MEMBERS : String.join(", ", rendered);
    }
}
