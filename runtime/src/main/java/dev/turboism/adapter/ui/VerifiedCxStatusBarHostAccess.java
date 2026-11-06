package dev.turboism.adapter.ui;

import dev.turboism.mapping.verification.UiStatusBarAliases;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.CanvasHintPosition;
import java.util.List;
import java.util.Objects;

/**
 * Verified-resolver implementation of {@link CxStatusBarHostAccess} for the
 * reviewed exact Cubism 5.2.03, 5.3.02 and 5.3.03 status slice.
 *
 * <p>Every host interaction goes through {@link VerifiedMemberResolver} aliases
 * authorized by {@code StatusBarVerificationManifest}; no hard-coded host-private
 * objects and no direct mutation of the legacy children list. The root chain
 * {@code CEAppCtrl.instance -> getMainFrameCtrl -> getMainFrame -> getContentPane}
 * returns {@code null} at any missing link (not ready); child enumeration is
 * restricted to exact {@code CContainer} instances and must yield a {@code List}
 * or the call fails closed.</p>
 */
public final class VerifiedCxStatusBarHostAccess implements CxStatusBarHostAccess {

    private static final String PREFIX_INFO = "[I] ";
    private static final String PREFIX_WARNING = "[!] ";
    private static final String PREFIX_ERROR = "[X] ";

    /**
     * The native timer adds duration milliseconds to the current epoch time. A
     * literal {@code Float.POSITIVE_INFINITY} becomes {@code Long.MAX_VALUE}
     * during that conversion and overflows the native deadline immediately.
     */
    private static final float NATIVE_UNTIL_DISMISSED_SECONDS = 1_000_000_000.0f;

    /**
     * Clickable and positionable variant of {@link #UiStatusBarAliases.VIEW_CONTEXT_SHOW_HINT}. Its fourth
     * parameter is the host's {@code Function1<GButtonEntity, Unit>} action and the
     * fifth is the optional position override; either may be {@code null}, which keeps
     * the native behaviour for that argument.
     */
    private static final int HINT_ACTION_PARAMETER_INDEX = 3;

    /** Native {@code GVector2(float, float)} used only for an explicit hint position. */
    private final VerifiedMemberResolver resolver;

    public VerifiedCxStatusBarHostAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Object contentRoot() {
        final Object appController = resolver.invokeStatic(UiStatusBarAliases.APP_CONTROLLER_INSTANCE);
        if (appController == null) {
            return null;
        }
        final Object mainFrameController = resolver.invoke(UiStatusBarAliases.APP_CONTROLLER_MAIN_FRAME, appController);
        if (mainFrameController == null) {
            return null;
        }
        final Object frame = resolver.invoke(UiStatusBarAliases.MAIN_FRAME_CONTROLLER_FRAME, mainFrameController);
        if (frame == null) {
            return null;
        }
        return resolver.invoke(UiStatusBarAliases.FRAME_CONTENT_PANE, frame);
    }

    @Override
    public Registration showCanvasHint(final CanvasHintNotification notification) {
        Objects.requireNonNull(notification, "notification");
        final Object appController = resolver.invokeStatic(UiStatusBarAliases.APP_CONTROLLER_INSTANCE);
        if (appController == null) {
            throw new IllegalStateException("CX canvas-hint app controller is not ready");
        }
        final Object viewContext =
                resolver.invoke(UiStatusBarAliases.APP_CONTROLLER_CURRENT_VIEW_CONTEXT, appController);
        if (viewContext == null) {
            throw new IllegalStateException("CX canvas-hint view context is not ready");
        }
        if (!resolver.isInstance(UiStatusBarAliases.VIEW_CONTEXT_CLASS, viewContext)) {
            throw new IllegalStateException("CX canvas-hint view context type is invalid");
        }
        showHint(viewContext, notification);
        return () ->
                resolver.invoke(UiStatusBarAliases.VIEW_CONTEXT_SHOW_HINT, viewContext, "", 0.0f, notification.id());
    }

    /**
     * Routes to the passive native entry point only when the notification needs neither
     * a click action nor an explicit position; the richer entry point carries both. Every
     * route shares the same hint key, so replacing or closing a hint is route-independent.
     */
    private void showHint(final Object viewContext, final CanvasHintNotification notification) {
        final float durationSeconds = nativeDurationSeconds(notification.durationSeconds());
        final boolean clickable = notification.onClick().isPresent();
        final boolean positioned = notification.position().isPresent();
        if (!clickable && !positioned) {
            resolver.invoke(
                    UiStatusBarAliases.VIEW_CONTEXT_SHOW_HINT,
                    viewContext,
                    notification.message(),
                    durationSeconds,
                    notification.id());
            return;
        }
        // A null action is a supported argument: the native button checks for it before
        // invoking, so a position-only hint passes no click callback.
        final Object nativeAction = clickable ? nativeAction(notification) : null;
        final Object nativePosition = positioned ? nativePosition(notification) : null;
        resolver.invoke(
                UiStatusBarAliases.VIEW_CONTEXT_SHOW_HINT_WITH_ACTION,
                viewContext,
                notification.message(),
                durationSeconds,
                notification.id(),
                nativeAction,
                nativePosition);
    }

    private Object nativeAction(final CanvasHintNotification notification) {
        final Runnable action = notification.onClick().orElseThrow();
        return resolver.createFunctionalArgumentProxy(
                UiStatusBarAliases.VIEW_CONTEXT_SHOW_HINT_WITH_ACTION, HINT_ACTION_PARAMETER_INDEX, ignored -> {
                    action.run();
                    // The native click handler discards this result; the host never
                    // inspects the Unit instance, so null cannot reach Kotlin as a value.
                    return null;
                });
    }

    private Object nativePosition(final CanvasHintNotification notification) {
        final CanvasHintPosition override = notification.position().orElseThrow();
        return resolver.construct(UiStatusBarAliases.POSITION_CREATE, override.x(), override.y());
    }

    private static float nativeDurationSeconds(final float durationSeconds) {
        return Float.isInfinite(durationSeconds) ? NATIVE_UNTIL_DISMISSED_SECONDS : durationSeconds;
    }

    @Override
    public List<?> children(final Object container) {
        if (container == null || !resolver.isInstance(UiStatusBarAliases.CONTAINER_CLASS, container)) {
            return null;
        }
        final Object children = resolver.invoke(UiStatusBarAliases.CONTAINER_CHILDREN, container);
        if (!(children instanceof List<?> list)) {
            throw new IllegalStateException("CX status-region container children are not a List; failing closed");
        }
        return list;
    }

    @Override
    public boolean isCLabel(final Object widget) {
        return resolver.isInstance(UiStatusBarAliases.LABEL_CLASS, widget);
    }

    @Override
    public boolean isCMemoryViewerPanel(final Object widget) {
        return resolver.isInstance(UiStatusBarAliases.MEMORY_VIEWER_CLASS, widget);
    }

    @Override
    public Object createLabel(final String id, final String text) {
        Objects.requireNonNull(id, "id");
        return resolver.construct(UiStatusBarAliases.LABEL_CREATE, Objects.requireNonNull(text, "text"));
    }

    @Override
    public void setName(final Object widget, final String id) {
        resolver.invoke(UiStatusBarAliases.WIDGET_SET_NAME, widget, Objects.requireNonNull(id, "id"));
    }

    @Override
    public void setText(final Object widget, final String text) {
        resolver.invoke(UiStatusBarAliases.LABEL_SET_TEXT, widget, Objects.requireNonNull(text, "text"));
    }

    @Override
    public void setSeverityAppearance(final Object widget, final String severity) {
        Objects.requireNonNull(severity, "severity");
        final String prefix =
                switch (severity) {
                    case "INFO" -> PREFIX_INFO;
                    case "WARNING" -> PREFIX_WARNING;
                    case "ERROR" -> PREFIX_ERROR;
                    default -> throw new IllegalArgumentException("unsupported status severity: " + severity);
                };
        // Operations always set the raw message immediately before appearance.
        // Preserve it byte-for-byte even when the message itself starts with a marker.
        final Object current = resolver.invoke(UiStatusBarAliases.LABEL_TEXT, widget);
        final String text = current instanceof String value ? value : "";
        resolver.invoke(UiStatusBarAliases.LABEL_SET_TEXT, widget, prefix + text);
        resolver.invoke(UiStatusBarAliases.WIDGET_SET_TOOLTIP, widget, severity);
    }

    @Override
    public void add(final Object parent, final Object widget, final int index) {
        resolver.invoke(UiStatusBarAliases.CONTAINER_ADD, parent, widget, index);
    }

    @Override
    public void remove(final Object parent, final Object widget) {
        resolver.invoke(UiStatusBarAliases.CONTAINER_REMOVE, parent, widget);
    }

    @Override
    public void refresh(final Object widget) {
        resolver.invoke(UiStatusBarAliases.WIDGET_REVALIDATE, widget);
        resolver.invoke(UiStatusBarAliases.WIDGET_REPAINT, widget);
    }
}
