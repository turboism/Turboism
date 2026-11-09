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
 * theme, DPI and font match the surrounding native controls. Every reflection step resolves an
 * exact signature through {@link MethodHandleCache}, and any failure leaves the container exactly
 * as the host built it.</p>
 *
 * <p>Each host member is named by its full parameter types, because a host build that keeps a
 * member name but changes a parameter is the mismatch that costs a debugging session. A refused
 * lookup therefore reports the signature it wanted and the signatures the build declares.</p>
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
    private static final String WIDGET_TYPE = "com.live2d.ui.CWidget";
    private static final String FUNCTION1_TYPE = "kotlin.jvm.functions.Function1";
    private static final String UNIT_TYPE = "kotlin.Unit";
    private static final String UNIT_INSTANCE_FIELD = "INSTANCE";
    private static final String CONSTRUCTOR_NAME = "<init>";
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
    private static final int MAX_DECLARED_HINTS = 4;
    private static final String NO_DECLARED_MEMBERS = "none";
    private static final String CAUSE_SEPARATOR = " caused by ";
    private static final String DECLARED_SEPARATOR = "; declared: ";
    private static final Class<?>[] NO_PARAMETERS = new Class<?>[0];
    private static final Class<?>[] TEXT_PARAMETER = {String.class};
    private static final Class<?>[] COUNT_PARAMETER = {int.class};
    private static final Class<?>[] FLAG_PARAMETER = {boolean.class};
    private static final Class<?>[] SIZE_PARAMETER = {int.class, int.class};
    private static final Class<?>[] ITEM_PARAMETER = {List.class};
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
            invoke(mount, UNARY_PLUS, parameterTypes(container, WIDGET_TYPE), row);
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
