package dev.turboism.adapter.cubism.temporarydeform;

import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.runtime.log.RuntimeDiagnostics;
import dev.turboism.sdk.cubism.temporarydeform.TemporaryDeformOption;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Container;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.MutableComboBoxModel;
import javax.swing.SwingUtilities;

/**
 * Adapts Cubism's Tool Details panel ({@code CInspector_Object<TemporaryHandlerSetting>} +
 * {@code CComboBox2} + Swing options container) on the EDT.
 */
final class NativeTemporaryDeformToolDetailAdapter {

    static final String OPTIONS_PANEL_MARKER = "turboism.temporaryDeform.toolDetailOptions";

    private static final String INSPECTOR_FIELD = "a";
    private static final String ROOT_VBOX_FIELD = "d";
    private static final String UNBIND_BUTTON_FIELD = "f";
    private static final String WARP_SPLIT_BOX_FIELD = "m";

    private static final String ENUM_COMBO_FIELD = "comboBox";
    private static final String COMBO_BOX_CLASS = "com.live2d.ui.control.CComboBox2";
    private static final String ON_SELECTED_FIELD = "onSelectedFunc";
    private static final String SUPPRESS_SELECTION_FIELD = "suppressFiringSelectionEvent";
    private static final String FUNCTION1_CLASS = "kotlin.jvm.functions.Function1";
    private static final String KOTLIN_UNIT_CLASS = "kotlin.Unit";
    private static final String KOTLIN_UNIT_INSTANCE = "INSTANCE";
    private static final String LOG_COMPONENT = "temporary-deform";

    private static final int GRID_GAP = 4;
    private static final int TOP_INSET = 4;
    private static final int SIDE_INSET = 2;

    private final Map<Object, PanelState> panels = new WeakHashMap<>();

    /**
     * Wraps {@code CComboBox2}'s selection listener on the Tool Details panel so custom items
     * bypass {@code CInspector_Enum.comboSelected} cleanly.
     */
    void ensurePanelHooked(
            final Object panel, final Consumer<String> onCustomToolSelected, final Runnable onBeforeNativeSelected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("tool details hook requires EDT");
        }
        if (panel == null) {
            return;
        }
        final PanelState state = panels.computeIfAbsent(panel, ignored -> new PanelState());
        if (state.comboHooked) {
            return;
        }
        try {
            final Object inspectorObj = readField(panel, INSPECTOR_FIELD);
            final Object comboBox = findDeformMethodComboBox(inspectorObj);
            if (comboBox == null) {
                return;
            }
            final Field onSelectedField = findField(comboBox.getClass(), ON_SELECTED_FIELD);
            onSelectedField.setAccessible(true);
            final Object originalFunc = onSelectedField.get(comboBox);
            if (originalFunc == null) {
                return;
            }
            final ClassLoader loader = comboBox.getClass().getClassLoader();
            final Class<?> fn1Class = Class.forName(FUNCTION1_CLASS, false, loader);
            final Object unitInstance = readStaticField(KOTLIN_UNIT_CLASS, KOTLIN_UNIT_INSTANCE, loader);
            final Method invokeMethod = fn1Class.getMethod("invoke", Object.class);
            final Method setOnSelected = comboBox.getClass().getMethod("setOnSelected", fn1Class);

            final Object wrapper = Proxy.newProxyInstance(loader, new Class<?>[] {fn1Class}, (proxy, method, args) -> {
                if ("invoke".equals(method.getName()) && args != null && args.length == 1) {
                    if (state.reconciling) {
                        return unitInstance;
                    }
                    final Object selected = args[0];
                    if (selected instanceof CustomToolComboItem customItem) {
                        onCustomToolSelected.accept(customItem.qualifiedId());
                        return unitInstance;
                    }
                    onBeforeNativeSelected.run();
                    return invokeMethod.invoke(originalFunc, selected);
                }
                return handleObjectMethod(proxy, method, args);
            });
            setOnSelected.invoke(comboBox, wrapper);
            state.comboBox = comboBox;
            state.originalOnSelectedFunc = originalFunc;
            state.comboHooked = true;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Unable to bind Tool Details dropdown hook: "
                            + failure.getClass().getSimpleName());
        }
    }

    /** Reconciles custom dropdown items, active selection, warp-split visibility, and options UI on all known panels. */
    void reconcileAll(
            final List<ToolDetailEntry> tools,
            final String activeQualifiedId,
            final List<TemporaryDeformOption> activeOptions,
            final BiConsumer<String, String> onOptionChanged,
            final Consumer<String> onCustomToolSelected,
            final Runnable onBeforeNativeSelected) {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("tool details reconcile requires EDT");
        }
        for (final Object panel : List.copyOf(panels.keySet())) {
            reconcilePanel(
                    panel,
                    tools,
                    activeQualifiedId,
                    activeOptions,
                    onOptionChanged,
                    onCustomToolSelected,
                    onBeforeNativeSelected);
        }
    }

    /** Reconciles one Tool Details panel instance against the current custom temporary deform state. */
    void reconcilePanel(
            final Object panel,
            final List<ToolDetailEntry> tools,
            final String activeQualifiedId,
            final List<TemporaryDeformOption> activeOptions,
            final BiConsumer<String, String> onOptionChanged,
            final Consumer<String> onCustomToolSelected,
            final Runnable onBeforeNativeSelected) {
        if (panel == null) {
            return;
        }
        ensurePanelHooked(panel, onCustomToolSelected, onBeforeNativeSelected);
        final PanelState state = panels.get(panel);
        if (state == null) {
            return;
        }
        reconcileDropdown(state, tools, activeQualifiedId);
        reconcileWarpSplitVisibility(panel, activeQualifiedId != null);
        reconcileOptionsContainer(panel, state, activeQualifiedId, activeOptions, onOptionChanged);
    }

    /** Restores original listeners and removes all custom UI from tracked Tool Details panels. */
    void clearAll() {
        if (!SwingUtilities.isEventDispatchThread()) {
            return;
        }
        for (final Map.Entry<Object, PanelState> entry : List.copyOf(panels.entrySet())) {
            final Object panel = entry.getKey();
            final PanelState state = entry.getValue();
            try {
                if (state.comboBox != null) {
                    reconcileDropdown(state, List.of(), null);
                    if (state.originalOnSelectedFunc != null) {
                        final ClassLoader loader = state.comboBox.getClass().getClassLoader();
                        final Class<?> fn1Class = Class.forName(FUNCTION1_CLASS, false, loader);
                        state.comboBox
                                .getClass()
                                .getMethod("setOnSelected", fn1Class)
                                .invoke(state.comboBox, state.originalOnSelectedFunc);
                    }
                }
                removeOptionsPanel(panel, state);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                RuntimeDiagnostics.warn(
                        LOG_COMPONENT,
                        "Cleanup of Tool Details panel failed: "
                                + failure.getClass().getSimpleName());
            }
        }
        panels.clear();
    }

    @SuppressWarnings("unchecked")
    private static void reconcileDropdown(
            final PanelState state, final List<ToolDetailEntry> tools, final String activeQualifiedId) {
        final Object comboBox = state.comboBox;
        if (comboBox == null) {
            return;
        }
        final boolean prevReconciling = state.reconciling;
        state.reconciling = true;
        final Field suppressField = tryFindField(comboBox.getClass(), SUPPRESS_SELECTION_FIELD);
        boolean prevSuppress = false;
        boolean suppressApplied = false;
        try {
            // Mute CComboBox2 action firing while model elements are updated or removed.
            if (suppressField != null) {
                suppressField.setAccessible(true);
                prevSuppress = suppressField.getBoolean(comboBox);
                suppressField.setBoolean(comboBox, true);
                suppressApplied = true;
            }
            final Object rawModel = comboBox.getClass().getMethod("getModel").invoke(comboBox);
            if (!(rawModel instanceof MutableComboBoxModel<?> model)) {
                return;
            }
            final MutableComboBoxModel<Object> mutableModel = (MutableComboBoxModel<Object>) model;
            final List<ToolDetailEntry> sorted = tools.stream()
                    .sorted(Comparator.comparingInt(ToolDetailEntry::order).thenComparing(ToolDetailEntry::qualifiedId))
                    .toList();
            CustomToolComboItem selectedItem = null;
            if (customItemsMatch(mutableModel, sorted)) {
                selectedItem = findCustomItem(mutableModel, activeQualifiedId);
            } else {
                for (int i = mutableModel.getSize() - 1; i >= 0; i--) {
                    if (mutableModel.getElementAt(i) instanceof CustomToolComboItem) {
                        mutableModel.removeElementAt(i);
                    }
                }
                for (final ToolDetailEntry tool : sorted) {
                    final CustomToolComboItem item = new CustomToolComboItem(tool.qualifiedId(), tool.label());
                    mutableModel.addElement(item);
                    if (tool.qualifiedId().equals(activeQualifiedId)) {
                        selectedItem = item;
                    }
                }
            }
            if (selectedItem != null) {
                // Passing false keeps suppressFiringSelectionEvent true inside CComboBox2.setSelectedItem.
                comboBox.getClass()
                        .getMethod("setSelectedItem", Object.class, boolean.class)
                        .invoke(comboBox, selectedItem, false);
            }
            comboBox.getClass().getMethod("updatePopupWidth").invoke(comboBox);
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            RuntimeDiagnostics.warn(
                    LOG_COMPONENT,
                    "Dropdown reconciliation failed safely: "
                            + failure.getClass().getSimpleName());
        } finally {
            if (suppressApplied) {
                try {
                    suppressField.setBoolean(comboBox, prevSuppress);
                } catch (IllegalAccessException ignored) {
                }
            }
            state.reconciling = prevReconciling;
        }
    }

    private static boolean customItemsMatch(
            final MutableComboBoxModel<Object> model, final List<ToolDetailEntry> expected) {
        final List<CustomToolComboItem> existing = new java.util.ArrayList<>();
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i) instanceof CustomToolComboItem item) {
                existing.add(item);
            }
        }
        if (existing.size() != expected.size()) {
            return false;
        }
        for (int i = 0; i < existing.size(); i++) {
            final CustomToolComboItem item = existing.get(i);
            final ToolDetailEntry entry = expected.get(i);
            if (!item.qualifiedId().equals(entry.qualifiedId()) || !item.label().equals(entry.label())) {
                return false;
            }
        }
        return true;
    }

    private static CustomToolComboItem findCustomItem(
            final MutableComboBoxModel<Object> model, final String activeQualifiedId) {
        if (activeQualifiedId == null) {
            return null;
        }
        for (int i = 0; i < model.getSize(); i++) {
            if (model.getElementAt(i) instanceof CustomToolComboItem item
                    && item.qualifiedId().equals(activeQualifiedId)) {
                return item;
            }
        }
        return null;
    }

    private static void reconcileWarpSplitVisibility(final Object panel, final boolean customActive) {
        if (!customActive) {
            return;
        }
        try {
            final Object warpSplitBox = readField(panel, WARP_SPLIT_BOX_FIELD);
            if (warpSplitBox != null) {
                warpSplitBox.getClass().getMethod("setVisible", boolean.class).invoke(warpSplitBox, false);
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
    }

    private static void reconcileOptionsContainer(
            final Object panel,
            final PanelState state,
            final String activeQualifiedId,
            final List<TemporaryDeformOption> activeOptions,
            final BiConsumer<String, String> onOptionChanged) {
        final Container rootContainer = resolveRootContainer(panel);
        if (rootContainer == null) {
            return;
        }
        final boolean shouldShow = activeQualifiedId != null && activeOptions != null && !activeOptions.isEmpty();
        if (!shouldShow) {
            removeOptionsPanel(panel, state);
            return;
        }
        JPanel optionsPanel = state.optionsPanel;
        if (optionsPanel == null) {
            optionsPanel = new JPanel(new BorderLayout());
            optionsPanel.setOpaque(false);
            optionsPanel.putClientProperty(OPTIONS_PANEL_MARKER, Boolean.TRUE);
            state.optionsPanel = optionsPanel;
        }
        populateOptionsPanel(optionsPanel, activeOptions, onOptionChanged);
        if (optionsPanel.getParent() != rootContainer) {
            final int insertIndex = findInsertIndexBeforeUnbind(rootContainer, panel);
            if (insertIndex >= 0) {
                rootContainer.add(optionsPanel, insertIndex);
            } else {
                rootContainer.add(optionsPanel);
            }
        }
        optionsPanel.setVisible(true);
        rootContainer.revalidate();
        rootContainer.repaint();
    }

    private static void removeOptionsPanel(final Object panel, final PanelState state) {
        if (state.optionsPanel == null) {
            return;
        }
        final Container parent = state.optionsPanel.getParent();
        if (parent != null) {
            parent.remove(state.optionsPanel);
            parent.revalidate();
            parent.repaint();
        }
        state.optionsPanel.removeAll();
        state.optionsPanel = null;
    }

    private static void populateOptionsPanel(
            final JPanel container,
            final List<TemporaryDeformOption> options,
            final BiConsumer<String, String> onOptionChanged) {
        container.removeAll();
        final JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        final boolean[] building = {true};
        int row = 0;
        for (final TemporaryDeformOption option : options) {
            if (option instanceof TemporaryDeformOption.Choice choice) {
                final GridBagConstraints labelGbc = new GridBagConstraints();
                labelGbc.gridx = 0;
                labelGbc.gridy = row;
                labelGbc.anchor = GridBagConstraints.WEST;
                labelGbc.insets = new Insets(TOP_INSET, SIDE_INSET, 0, GRID_GAP);
                form.add(new JLabel(choice.label()), labelGbc);

                final ChoiceOptionItem[] items = choice.items().stream()
                        .map(item -> new ChoiceOptionItem(item.value(), item.label()))
                        .toArray(ChoiceOptionItem[]::new);
                final JComboBox<ChoiceOptionItem> combo = new JComboBox<>(items);
                for (final ChoiceOptionItem item : items) {
                    if (item.value().equals(choice.selectedValue())) {
                        combo.setSelectedItem(item);
                        break;
                    }
                }
                combo.addActionListener(ignored -> {
                    if (building[0]) {
                        return;
                    }
                    if (combo.getSelectedItem() instanceof ChoiceOptionItem selected) {
                        onOptionChanged.accept(choice.id(), selected.value());
                    }
                });

                final GridBagConstraints fieldGbc = new GridBagConstraints();
                fieldGbc.gridx = 1;
                fieldGbc.gridy = row;
                fieldGbc.weightx = 1.0;
                fieldGbc.fill = GridBagConstraints.HORIZONTAL;
                fieldGbc.insets = new Insets(TOP_INSET, 0, 0, SIDE_INSET);
                form.add(combo, fieldGbc);
                row++;
            } else if (option instanceof TemporaryDeformOption.Toggle toggle) {
                final JCheckBox checkBox = new JCheckBox(toggle.label(), toggle.selected());
                checkBox.setOpaque(false);
                checkBox.addActionListener(ignored -> {
                    if (building[0]) {
                        return;
                    }
                    onOptionChanged.accept(toggle.id(), Boolean.toString(checkBox.isSelected()));
                });
                final GridBagConstraints gbc = new GridBagConstraints();
                gbc.gridx = 0;
                gbc.gridy = row;
                gbc.gridwidth = 2;
                gbc.anchor = GridBagConstraints.WEST;
                gbc.insets = new Insets(TOP_INSET, SIDE_INSET, 0, SIDE_INSET);
                form.add(checkBox, gbc);
                row++;
            }
        }
        building[0] = false;
        container.add(form, BorderLayout.CENTER);
    }

    private static Container resolveRootContainer(final Object panel) {
        try {
            final Object vbox = readField(panel, ROOT_VBOX_FIELD);
            if (vbox == null) {
                return null;
            }
            final Object jc = vbox.getClass().getMethod("getJComponent").invoke(vbox);
            return jc instanceof Container container ? container : null;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return null;
        }
    }

    private static int findInsertIndexBeforeUnbind(final Container rootContainer, final Object panel) {
        try {
            final Object unbindBtn = readField(panel, UNBIND_BUTTON_FIELD);
            if (unbindBtn == null) {
                return -1;
            }
            final Object unbindSwing =
                    unbindBtn.getClass().getMethod("getJButton").invoke(unbindBtn);
            if (!(unbindSwing instanceof Component unbindComp)) {
                return -1;
            }
            final Component[] children = rootContainer.getComponents();
            for (int i = 0; i < children.length; i++) {
                if (children[i] == unbindComp
                        || (children[i] instanceof Container sub && isDescendant(sub, unbindComp))) {
                    return i;
                }
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
        }
        return -1;
    }

    private static boolean isDescendant(final Container parent, final Component target) {
        for (final Component child : parent.getComponents()) {
            if (child == target) {
                return true;
            }
            if (child instanceof Container nested && isDescendant(nested, target)) {
                return true;
            }
        }
        return false;
    }

    private static Object findDeformMethodComboBox(final Object inspectorObject) throws ReflectiveOperationException {
        if (inspectorObject == null) {
            return null;
        }
        final Object childrenObj = inspectorObject
                .getClass()
                .getMethod("getChildPropertyAndInspectors")
                .invoke(inspectorObject);
        if (!(childrenObj instanceof Iterable<?> iterable)) {
            return null;
        }
        for (final Object pair : iterable) {
            for (final Field field : pair.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                final Object candidate = field.get(pair);
                if (candidate == null) {
                    continue;
                }
                if (COMBO_BOX_CLASS.equals(candidate.getClass().getName())) {
                    return candidate;
                }
                final Field enumCombo = tryFindField(candidate.getClass(), ENUM_COMBO_FIELD);
                if (enumCombo != null) {
                    enumCombo.setAccessible(true);
                    final Object combo = enumCombo.get(candidate);
                    if (combo != null && COMBO_BOX_CLASS.equals(combo.getClass().getName())) {
                        return combo;
                    }
                }
            }
        }
        return null;
    }

    private static Object readField(final Object target, final String name) throws ReflectiveOperationException {
        final Field field = findField(target.getClass(), name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static Object readStaticField(final String className, final String fieldName, final ClassLoader loader)
            throws ReflectiveOperationException {
        final Class<?> type = Class.forName(className, true, loader);
        final Field field = type.getField(fieldName);
        return field.get(null);
    }

    private static Field findField(final Class<?> type, final String name) throws NoSuchFieldException {
        final Field found = tryFindField(type, name);
        if (found == null) {
            throw new NoSuchFieldException(type.getName() + "." + name);
        }
        return found;
    }

    private static Field tryFindField(final Class<?> type, final String name) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                current = current.getSuperclass();
            }
        }
        return null;
    }

    private static Object handleObjectMethod(final Object proxy, final Method method, final Object[] args) {
        return switch (method.getName()) {
            case "equals" -> args != null && args.length == 1 && proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            case "toString" -> "TurboismTemporaryDeformOnSelectedWrapper";
            default -> null;
        };
    }

    private static final class PanelState {
        private boolean comboHooked;
        private boolean reconciling;
        private Object comboBox;
        private Object originalOnSelectedFunc;
        private JPanel optionsPanel;
    }

    /** Custom dropdown entry rendered inside Cubism's native Deform Method combo box. */
    static final class CustomToolComboItem {
        private final String qualifiedId;
        private final String label;

        CustomToolComboItem(final String qualifiedId, final String label) {
            this.qualifiedId = Objects.requireNonNull(qualifiedId, "qualifiedId");
            this.label = Objects.requireNonNull(label, "label");
        }

        String qualifiedId() {
            return qualifiedId;
        }

        String label() {
            return label;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private record ChoiceOptionItem(String value, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    /** Dropdown entry metadata for custom temporary deform tools. */
    record ToolDetailEntry(String qualifiedId, String label, int order) {}
}
