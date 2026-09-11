package dev.turboism.adapter.ui;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintNotification;

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

    private static final String SHOW_HINT_ALIAS = "cubism.ui-canvas-hint.view-context.show-hint";

    /**
     * Clickable variant of {@link #SHOW_HINT_ALIAS}. Its fourth parameter is the
     * host's {@code Function1<GButtonEntity, Unit>} action and the fifth is the
     * optional position override, which stays {@code null} for the native
     * lower-right placement.
     */
    private static final String SHOW_HINT_WITH_ACTION_ALIAS =
        "cubism.ui-canvas-hint.view-context.show-hint-with-action";

    private static final int HINT_ACTION_PARAMETER_INDEX = 3;

    private final VerifiedMemberResolver resolver;

    public VerifiedCxStatusBarHostAccess(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    @Override
    public Object contentRoot() {
        final Object appController = resolver.invokeStatic(
            "cubism.ui-status-bar.app-controller.instance"
        );
        if (appController == null) {
            return null;
        }
        final Object mainFrameController = resolver.invoke(
            "cubism.ui-status-bar.app-controller.main-frame",
            appController
        );
        if (mainFrameController == null) {
            return null;
        }
        final Object frame = resolver.invoke(
            "cubism.ui-status-bar.main-frame-controller.frame",
            mainFrameController
        );
        if (frame == null) {
            return null;
        }
        return resolver.invoke("cubism.ui-status-bar.frame.content-pane", frame);
    }

    @Override
    public Registration showCanvasHint(final CanvasHintNotification notification) {
        Objects.requireNonNull(notification, "notification");
        final Object appController = resolver.invokeStatic("cubism.ui-status-bar.app-controller.instance");
        if (appController == null) {
            throw new IllegalStateException("CX canvas-hint app controller is not ready");
        }
        final Object viewContext = resolver.invoke(
            "cubism.ui-canvas-hint.app-controller.current-view-context",
            appController
        );
        if (viewContext == null) {
            throw new IllegalStateException("CX canvas-hint view context is not ready");
        }
        if (!resolver.isInstance("cubism.ui-canvas-hint.view-context.class", viewContext)) {
            throw new IllegalStateException("CX canvas-hint view context type is invalid");
        }
        showHint(viewContext, notification);
        return () -> resolver.invoke(
            SHOW_HINT_ALIAS,
            viewContext,
            "",
            0.0f,
            notification.id()
        );
    }

    /**
     * Routes to the clickable native entry point when the notification carries a
     * click action, and to the passive one otherwise. Both share the same hint key,
     * so replacing or closing a hint works identically for either route.
     */
    private void showHint(final Object viewContext, final CanvasHintNotification notification) {
        final float durationSeconds = nativeDurationSeconds(notification.durationSeconds());
        if (notification.onClick().isEmpty()) {
            resolver.invoke(
                SHOW_HINT_ALIAS,
                viewContext,
                notification.message(),
                durationSeconds,
                notification.id()
            );
            return;
        }
        final Runnable action = notification.onClick().orElseThrow();
        final Object nativeAction = resolver.createFunctionalArgumentProxy(
            SHOW_HINT_WITH_ACTION_ALIAS,
            HINT_ACTION_PARAMETER_INDEX,
            ignored -> {
                action.run();
                // The native click handler discards this result; the host never
                // inspects the Unit instance, so null cannot reach Kotlin as a value.
                return null;
            }
        );
        resolver.invoke(
            SHOW_HINT_WITH_ACTION_ALIAS,
            viewContext,
            notification.message(),
            durationSeconds,
            notification.id(),
            nativeAction,
            null
        );
    }

    private static float nativeDurationSeconds(final float durationSeconds) {
        return Float.isInfinite(durationSeconds)
            ? NATIVE_UNTIL_DISMISSED_SECONDS
            : durationSeconds;
    }

    @Override
    public List<?> children(final Object container) {
        if (container == null || !resolver.isInstance("cubism.ui-status-bar.container.class", container)) {
            return null;
        }
        final Object children = resolver.invoke("cubism.ui-status-bar.container.children", container);
        if (!(children instanceof List<?> list)) {
            throw new IllegalStateException(
                "CX status-region container children are not a List; failing closed"
            );
        }
        return list;
    }

    @Override
    public boolean isCLabel(final Object widget) {
        return resolver.isInstance("cubism.ui-status-bar.label.class", widget);
    }

    @Override
    public boolean isCMemoryViewerPanel(final Object widget) {
        return resolver.isInstance("cubism.ui-status-bar.memory-viewer.class", widget);
    }

    @Override
    public Object createLabel(final String id, final String text) {
        Objects.requireNonNull(id, "id");
        return resolver.construct("cubism.ui-status-bar.label.create", Objects.requireNonNull(text, "text"));
    }

    @Override
    public void setName(final Object widget, final String id) {
        resolver.invoke("cubism.ui-status-bar.widget.set-name", widget, Objects.requireNonNull(id, "id"));
    }

    @Override
    public void setText(final Object widget, final String text) {
        resolver.invoke("cubism.ui-status-bar.label.set-text", widget, Objects.requireNonNull(text, "text"));
    }

    @Override
    public void setSeverityAppearance(final Object widget, final String severity) {
        Objects.requireNonNull(severity, "severity");
        final String prefix = switch (severity) {
            case "INFO" -> PREFIX_INFO;
            case "WARNING" -> PREFIX_WARNING;
            case "ERROR" -> PREFIX_ERROR;
            default -> throw new IllegalArgumentException("unsupported status severity: " + severity);
        };
        // Operations always set the raw message immediately before appearance.
        // Preserve it byte-for-byte even when the message itself starts with a marker.
        final Object current = resolver.invoke("cubism.ui-status-bar.label.text", widget);
        final String text = current instanceof String value ? value : "";
        resolver.invoke("cubism.ui-status-bar.label.set-text", widget, prefix + text);
        resolver.invoke("cubism.ui-status-bar.widget.set-tooltip", widget, severity);
    }

    @Override
    public void add(final Object parent, final Object widget, final int index) {
        resolver.invoke("cubism.ui-status-bar.container.add", parent, widget, index);
    }

    @Override
    public void remove(final Object parent, final Object widget) {
        resolver.invoke("cubism.ui-status-bar.container.remove", parent, widget);
    }

    @Override
    public void refresh(final Object widget) {
        resolver.invoke("cubism.ui-status-bar.widget.revalidate", widget);
        resolver.invoke("cubism.ui-status-bar.widget.repaint", widget);
    }
}
