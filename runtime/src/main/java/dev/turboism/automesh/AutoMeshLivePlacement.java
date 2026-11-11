package dev.turboism.automesh;

import dev.turboism.adapter.cubism.profile.CubismAutoMeshUiProfile;
import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import dev.turboism.ui.host.EdtDispatch;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;

/**
 * Re-runs the host's own live-update route after the selector row stores a change.
 *
 * <p>The Editor re-places points as soon as its own mode or parameter changes, because its container
 * resolves the current selection and calls the guarded native funnel. The plugin selector must use
 * the same route instead of growing a second placement pipeline: the route already resolves targets,
 * enters the funnel guard, groups Undo entries and refuses an unconfirmed selection.</p>
 *
 * <p>Each reviewed container declares one static entry that takes the container and the event the
 * Editor uses for a direct update. Builds before 5.0.06 pass only the event; later builds also take
 * the Undo group and the completion callback, so both shapes are accepted and the optional values
 * stay {@code null} for the host to create.</p>
 *
 * <p>The call runs on the Editor UI thread. A container whose build does not expose the entry keeps
 * the stored selection and reports the refusal, so the next native Generate still uses it.</p>
 */
final class AutoMeshLivePlacement {

    private static final String POST_LABEL = "auto mesh live update";
    private static final String NO_CONTAINER = "auto mesh live update: no container is available";
    private static final String NO_ROUTE = "auto mesh live update: %s exposes no route";
    private static final String ROUTE_FAILED = "auto mesh live update: %s refused the route: %s";
    private static final String UNSUPPORTED_PARAMETER = "auto mesh live update: %s declares an unknown parameter %s";

    private AutoMeshLivePlacement() {}

    /**
     * Places the current Editor selection with the container's own live-update route.
     *
     * @param container the native Auto Mesh container the row decorates
     * @param diagnostics sink for a refused route; it must not throw
     */
    static void place(final Object container, final Consumer<String> diagnostics) {
        if (container == null) {
            report(diagnostics, NO_CONTAINER);
            return;
        }
        final Runnable task = () -> apply(container, diagnostics);
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            EdtDispatch.post(POST_LABEL, task);
        }
    }

    private static void apply(final Object container, final Consumer<String> diagnostics) {
        try {
            final Class<?> containerType = container.getClass();
            if (!isKnownContainer(containerType.getName())) {
                report(diagnostics, NO_ROUTE.formatted(containerType.getName()));
                return;
            }
            final Class<?> ownerType = containerType.getEnclosingClass();
            final Method route = routeOf(ownerType, containerType);
            if (route == null) {
                report(diagnostics, NO_ROUTE.formatted(containerType.getName()));
                return;
            }
            final Object owner = ownerOf(ownerType);
            if (owner == null) {
                report(diagnostics, NO_ROUTE.formatted(containerType.getName()));
                return;
            }
            ReflectAccess.open(route);
            route.invoke(owner, argumentsOf(route, owner, container, containerType));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            report(diagnostics, ROUTE_FAILED.formatted(container.getClass().getName(), describe(failure)));
        }
    }

    private static boolean isKnownContainer(final String className) {
        return CubismAutoMeshUiProfile.MODELING_PANEL_TYPE.equals(className)
                || CubismAutoMeshUiProfile.MODELING_POPUP_TYPE.equals(className)
                || CubismAutoMeshUiProfile.MESH_EDITOR_POPUP_TYPE.equals(className);
    }

    /**
     * Finds the one static entry that takes the container owner and the live container.
     *
     * <p>The reviewed builds obfuscate the entry's name, so the exact first two parameter types
     * identify it. The synthetic default-arguments copy takes the complete pack second, which keeps
     * it out of the match.</p>
     */
    private static Method routeOf(final Class<?> ownerType, final Class<?> containerType) {
        if (ownerType == null) {
            return null;
        }
        Method found = null;
        for (final Method method : ownerType.getDeclaredMethods()) {
            if (!Modifier.isStatic(method.getModifiers()) || method.getParameterCount() < 3) {
                continue;
            }
            final Class<?>[] parameters = method.getParameterTypes();
            if (parameters[0] != ownerType || !parameters[1].isAssignableFrom(containerType)) {
                continue;
            }
            if (found != null) {
                return null;
            }
            found = method;
        }
        return found;
    }

    private static Object ownerOf(final Class<?> ownerType) throws IllegalAccessException {
        for (final Field field : ownerType.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && field.getType() == ownerType) {
                ReflectAccess.open(field);
                return field.get(null);
            }
        }
        return null;
    }

    private static Object[] argumentsOf(
            final Method route, final Object owner, final Object container, final Class<?> containerType)
            throws ReflectiveOperationException {
        final Class<?>[] parameters = route.getParameterTypes();
        final Object[] arguments = new Object[parameters.length];
        arguments[0] = owner;
        arguments[1] = container;
        final ClassLoader loader = containerType.getClassLoader();
        final Object event = eventOf(loader);
        for (int index = 2; index < parameters.length; index++) {
            final String name = parameters[index].getName();
            arguments[index] = switch (name) {
                case CubismAutoMeshUiProfile.EVENT_TYPE -> event;
                case CubismAutoMeshUiProfile.GROUP_UNDO_TYPE -> null;
                case CubismAutoMeshUiProfile.FUNCTION0_TYPE -> null;
                case CubismAutoMeshUiProfile.INSPECTOR_EVENT_TYPE ->
                    inspectorEventOf(parameters[index], container, event);
                default -> throw new NoSuchMethodException(UNSUPPORTED_PARAMETER.formatted(route.getName(), name));
            };
        }
        return arguments;
    }

    private static Object eventOf(final ClassLoader loader) throws ReflectiveOperationException {
        final Field field = Class.forName(CubismAutoMeshUiProfile.EVENT_TYPE, false, loader)
                .getField(CubismAutoMeshUiProfile.EVENT_DIRECT_UPDATE_FIELD);
        return field.get(null);
    }

    private static Object inspectorEventOf(final Class<?> type, final Object container, final Object event)
            throws ReflectiveOperationException {
        final Constructor<?> constructor =
                type.getDeclaredConstructor(Object.class, List.class, List.class, Map.class, event.getClass());
        ReflectAccess.open(constructor);
        return constructor.newInstance(container, List.of(), List.of(), Map.of(), event);
    }

    private static void report(final Consumer<String> diagnostics, final String message) {
        try {
            diagnostics.accept(message);
        } catch (RuntimeException | LinkageError failure) {
            FatalErrors.rethrowIfFatal(failure);
            // A diagnostic sink must never change whether the native route runs.
        }
    }

    private static String describe(final Throwable failure) {
        Throwable current = failure;
        while (current instanceof InvocationTargetException && current.getCause() != null) {
            current = current.getCause();
        }
        final String message = current.getMessage();
        return current.getClass().getName() + (message == null ? "" : ": " + message.replace('\n', ' '));
    }
}
