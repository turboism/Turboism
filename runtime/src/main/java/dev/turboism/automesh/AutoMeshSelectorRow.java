package dev.turboism.automesh;

import dev.turboism.core.reflect.MethodHandleCache;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.ui.host.EdtDispatch;
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
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * The "Algorithm / Preset / Parameters…" row one native Auto Mesh Generate container gains.
 *
 * <p>The row is built from the host's own widgets ({@code CLabel}, {@code CComboBox},
 * {@code CButton}, {@code CHBox}, {@code CSpacer}) through the container's own class loader, so
 * theme, DPI and font match the surrounding native controls. Every reflection step resolves
 * through {@link MethodHandleCache}, and any failure leaves the container exactly as the host built
 * it.</p>
 *
 * <p>The row is appended, never inserted: the container keeps its own children in their own order,
 * and the row becomes the last child of the container's centre widget. A container whose centre is
 * missing or is not a host container is left untouched.</p>
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
    private static final String FUNCTION1_TYPE = "kotlin.jvm.functions.Function1";
    private static final String UNIT_TYPE = "kotlin.Unit";
    private static final String UNIT_INSTANCE_FIELD = "INSTANCE";
    private static final String INVOKE_METHOD = "invoke";
    private static final String CENTER_GETTER = "getCenter";
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
    private static final String ALGORITHM_LABEL_KEY = "selector.algorithm.label";
    private static final String ALGORITHM_TOOLTIP_KEY = "selector.algorithm.tooltip";
    private static final String PRESET_LABEL_KEY = "selector.preset.label";
    private static final String PRESET_TOOLTIP_KEY = "selector.preset.tooltip";
    private static final String PARAMETERS_BUTTON_KEY = "selector.parameters.button";
    private static final String PARAMETERS_TOOLTIP_KEY = "selector.parameters.tooltip";

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
            final Object row = build(container);
            invoke(mount, UNARY_PLUS, row);
            tryInvoke(mount, REVALIDATE);
            tryInvoke(mount, REPAINT);
            diagnostic("auto mesh selector: row attached to "
                    + container.getClass().getSimpleName());
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            // Fail closed: the native popup keeps its own layout and its own controls.
            diagnostic("auto mesh selector: attach failed with "
                    + failure.getClass().getName() + ", the row is not added");
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
        final Object center = tryInvoke(container, CENTER_GETTER);
        if (center == null) {
            return null;
        }
        final Class<?> containerType =
                Class.forName(CONTAINER_TYPE, false, container.getClass().getClassLoader());
        return containerType.isInstance(center) ? center : null;
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

        final Object row = instantiate(loader, ROW_TYPE);
        tryInvoke(row, MARGIN_METHOD, ROW_MARGIN);

        final Object algorithmLabel = instantiate(loader, LABEL_TYPE, AutoMeshText.text(locale, ALGORITHM_LABEL_KEY));
        tryInvoke(algorithmLabel, SET_TOOLTIP, AutoMeshText.text(locale, ALGORITHM_TOOLTIP_KEY));
        invoke(row, UNARY_PLUS, algorithmLabel);

        final Object algorithmCombo = instantiate(loader, COMBO_TYPE);
        tryInvoke(algorithmCombo, SET_TOOLTIP, AutoMeshText.text(locale, ALGORITHM_TOOLTIP_KEY));
        fillCombo(algorithmCombo, labels(controller.algorithmEntries()));
        tryInvoke(algorithmCombo, SET_SELECTED_INDEX, controller.selectedAlgorithmIndex());
        invoke(row, UNARY_PLUS, algorithmCombo);

        invoke(row, UNARY_PLUS, instantiate(loader, SPACER_TYPE, SPACER_WIDTH, SPACER_HEIGHT));

        final Object presetLabel = instantiate(loader, LABEL_TYPE, AutoMeshText.text(locale, PRESET_LABEL_KEY));
        tryInvoke(presetLabel, SET_TOOLTIP, AutoMeshText.text(locale, PRESET_TOOLTIP_KEY));
        invoke(row, UNARY_PLUS, presetLabel);

        final Object presetCombo = instantiate(loader, COMBO_TYPE);
        tryInvoke(presetCombo, SET_TOOLTIP, AutoMeshText.text(locale, PRESET_TOOLTIP_KEY));
        fillCombo(presetCombo, controller.presetEntries());
        tryInvoke(presetCombo, SET_SELECTED_INDEX, controller.selectedPresetIndex());
        invoke(row, UNARY_PLUS, presetCombo);

        invoke(row, UNARY_PLUS, instantiate(loader, SPACER_TYPE, SPACER_WIDTH, SPACER_HEIGHT));

        final Object parametersButton = instantiate(loader, BUTTON_TYPE);
        tryInvoke(parametersButton, SET_TEXT, AutoMeshText.text(locale, PARAMETERS_BUTTON_KEY));
        tryInvoke(parametersButton, SET_TOOLTIP, AutoMeshText.text(locale, PARAMETERS_TOOLTIP_KEY));
        tryInvoke(parametersButton, SET_ENABLED, controller.parametersAvailable());
        invoke(row, UNARY_PLUS, parametersButton);

        final Runnable syncPresets = () -> {
            try {
                fillCombo(presetCombo, controller.presetEntries());
                tryInvoke(presetCombo, SET_SELECTED_INDEX, controller.selectedPresetIndex());
                tryInvoke(parametersButton, SET_ENABLED, controller.parametersAvailable());
            } catch (ReflectiveOperationException failure) {
                diagnostic("auto mesh selector: preset list refresh failed with "
                        + failure.getClass().getName());
            }
        };

        invoke(algorithmCombo, SET_ON_SELECTED, proxy(function1, unit, (proxy, method, arguments) -> {
            if (INVOKE_METHOD.equals(method.getName())) {
                guard(() -> controller.selectAlgorithm(selectedIndex(algorithmCombo)));
                guard(syncPresets);
            }
            return unit;
        }));
        invoke(presetCombo, SET_ON_SELECTED, proxy(function1, unit, (proxy, method, arguments) -> {
            if (INVOKE_METHOD.equals(method.getName())) {
                guard(() -> controller.selectPreset(selectedIndex(presetCombo)));
            }
            return unit;
        }));
        invoke(parametersButton, ADD_ON_ACTION, proxy(function1, unit, (proxy, method, arguments) -> {
            if (INVOKE_METHOD.equals(method.getName())) {
                guard(() -> openParameters(container));
            }
            return unit;
        }));
        return row;
    }

    private void openParameters(final Object container) {
        final Window owner =
                container instanceof java.awt.Component component ? SwingUtilities.getWindowAncestor(component) : null;
        AutoMeshParameterDialog.show(owner, controller, locale);
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
            diagnostic("auto mesh selector: callback failed with "
                    + failure.getClass().getName());
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
     */
    private static void fillCombo(final Object combo, final List<String> items) throws ReflectiveOperationException {
        final Object model = tryInvoke(combo, GET_MODEL);
        if (model == null) {
            throw new NoSuchMethodException("combo box exposes no model");
        }
        final Object replacement = instantiate(model.getClass(), List.copyOf(items));
        invoke(combo, SET_MODEL, replacement);
        tryInvoke(combo, UPDATE_POPUP_WIDTH);
    }

    private static int selectedIndex(final Object combo) {
        final Object value = tryInvoke(combo, GET_SELECTED_INDEX);
        return value instanceof Number number ? number.intValue() : 0;
    }

    private void diagnostic(final String message) {
        try {
            diagnostics.accept(message);
        } catch (RuntimeException | Error failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change whether the row is added.
        }
    }

    private static Object instantiate(final Class<?> type, final Object... arguments)
            throws ReflectiveOperationException {
        for (final Constructor<?> constructor : type.getConstructors()) {
            if (constructor.getParameterCount() != arguments.length) {
                continue;
            }
            try {
                return constructor.newInstance(arguments);
            } catch (IllegalArgumentException | InvocationTargetException ignored) {
                // Try the next public constructor (boxed or null arguments).
            }
        }
        throw new NoSuchMethodException(type.getName() + " constructor with " + arguments.length + " arguments");
    }

    private static Object instantiate(final ClassLoader loader, final String className, final Object... arguments)
            throws ReflectiveOperationException {
        return instantiate(Class.forName(className, false, loader), arguments);
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

    private static Object tryInvoke(final Object target, final String name, final Object... arguments) {
        try {
            return invoke(target, name, arguments);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static Object invoke(final Object target, final String name, final Object... arguments)
            throws ReflectiveOperationException {
        if (target == null) {
            throw new NoSuchMethodException(name);
        }
        for (final Method method : MethodHandleCache.overloads(target.getClass(), name, arguments.length)) {
            try {
                return method.invoke(target, arguments);
            } catch (IllegalArgumentException ignored) {
                // Try the next overload.
            }
        }
        throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
    }
}
