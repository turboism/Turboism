package dev.turboism.adapter.ui;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.StatusBarVerificationManifest;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.ui.CanvasHintNotification;
import dev.turboism.sdk.ui.CanvasHintPosition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class VerifiedCxStatusBarHostAccessTest {

    @AfterEach
    void clearHost() {
        SyntheticAppCtrl.instance = null;
    }

    @Test
    void contentRootReturnsNullWhenAnyRootChainLinkIsMissing() {
        VerifiedCxStatusBarHostAccess access = access();

        assertNull(access.contentRoot(), "no app controller instance means not ready");

        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null));
        assertNull(access.contentRoot(), "missing main frame means not ready");

        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticMainFrameCtrl(new SyntheticFrame(null))
        );
        assertNull(access.contentRoot(), "missing content pane means not ready");
    }

    @Test
    void contentRootWalksTheVerifiedRootChain() {
        SyntheticContainer contentPane = new SyntheticContainer();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(
            new SyntheticMainFrameCtrl(new SyntheticFrame(contentPane))
        );

        assertSame(contentPane, access().contentRoot());
    }

    @Test
    void canvasHintUsesTheCurrentViewContextAndReturnsAnExplicitDismissalHandle() {
        SyntheticViewContext viewContext = new SyntheticViewContext();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null), viewContext);

        Registration registration = access().showCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 2.5f)
        );

        assertEquals("Incompatible", viewContext.message);
        assertEquals(2.5f, viewContext.durationSeconds);
        assertEquals("screen-color", viewContext.key);
        registration.close();
        assertEquals("", viewContext.message);
        assertEquals(0.0f, viewContext.durationSeconds);
        assertEquals("screen-color", viewContext.key);
    }

    @Test
    void canvasHintUntilDismissedUsesAFiniteNativeDeadline() {
        SyntheticViewContext viewContext = new SyntheticViewContext();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null), viewContext);

        Registration registration = access().showCanvasHint(
            new CanvasHintNotification("screen-color", "Persistent", CanvasHintNotification.UNTIL_DISMISSED)
        );

        assertTrue(Float.isFinite(viewContext.durationSeconds));
        assertTrue(viewContext.durationSeconds > 0.0f);
        registration.close();
    }

    @Test
    void canvasHintWithAClickActionUsesTheClickableNativeEntryPoint() {
        SyntheticViewContext viewContext = new SyntheticViewContext();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null), viewContext);
        List<String> clicks = new ArrayList<>();

        Registration registration = access().showCanvasHint(new CanvasHintNotification(
            "screen-color",
            "Incompatible",
            2.5f
        ).withOnClick(() -> clicks.add("clicked")));

        assertEquals("Incompatible", viewContext.message);
        assertEquals(2.5f, viewContext.durationSeconds);
        assertEquals("screen-color", viewContext.key);
        assertTrue(viewContext.action != null, "a click action must reach the native entry point");

        viewContext.action.clicked(new Object());
        assertEquals(List.of("clicked"), clicks);

        registration.close();
        assertNull(viewContext.action, "closing a hint through showHint clears its action");
    }

    @Test
    void canvasHintWithAPositionOverridePassesTheVerifiedPosition() {
        SyntheticViewContext viewContext = new SyntheticViewContext();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null), viewContext);

        access().showCanvasHint(
            new CanvasHintNotification("screen-color", "Incompatible", 2.5f)
                .withPosition(new CanvasHintPosition(120.0f, 340.0f))
        );

        assertNull(viewContext.action, "a position-only hint must not register a click action");
        assertTrue(viewContext.position instanceof SyntheticPosition,
            "the verified GVector2 constructor must produce the position argument");
        assertEquals(120.0f, ((SyntheticPosition) viewContext.position).x);
        assertEquals(340.0f, ((SyntheticPosition) viewContext.position).y);
    }

    @Test
    void canvasHintWithoutAClickActionStaysOnThePassiveEntryPoint() {
        SyntheticViewContext viewContext = new SyntheticViewContext();
        SyntheticAppCtrl.instance = new SyntheticAppCtrl(new SyntheticMainFrameCtrl(null), viewContext);

        access().showCanvasHint(new CanvasHintNotification("screen-color", "Incompatible", 2.5f));

        assertNull(viewContext.action, "a passive hint must not register a click action");
    }

    @Test
    void childrenOnlyEnumerateExactContainersAndFailClosedOnNonListChildren() {
        VerifiedCxStatusBarHostAccess access = access();
        SyntheticContainer container = new SyntheticContainer();
        SyntheticLabel label = new SyntheticLabel("x");

        assertNull(access.children(label), "non-container nodes must yield null");
        assertSame(container.children, access.children(container));

        SyntheticContainer broken = new SyntheticContainer() {
            @Override
            public List<Object> getChildren() {
                return null;
            }
        };
        assertThrows(IllegalStateException.class, () -> access.children(broken),
            "a null children result must fail closed");
    }

    @Test
    void classificationAndLabelConstructionUseTheExactHostClasses() {
        VerifiedCxStatusBarHostAccess access = access();
        SyntheticLabel label = new SyntheticLabel("text");
        SyntheticMemoryViewer viewer = new SyntheticMemoryViewer();

        assertTrue(access.isCLabel(label));
        assertFalse(access.isCLabel(viewer));
        assertTrue(access.isCMemoryViewerPanel(viewer));
        assertFalse(access.isCMemoryViewerPanel(label));

        SyntheticLabel created = (SyntheticLabel) access.createLabel("status", "hello");
        assertEquals("hello", created.text);
    }

    @Test
    void severityAppearancePrefixesStripsAndSetsTooltip() {
        VerifiedCxStatusBarHostAccess access = access();
        SyntheticLabel label = new SyntheticLabel("");

        access.setText(label, "building");
        access.setSeverityAppearance(label, "INFO");
        assertEquals("[I] building", label.text);
        assertEquals("INFO", label.tooltip);

        access.setText(label, "slow");
        access.setSeverityAppearance(label, "WARNING");
        assertEquals("[!] slow", label.text);
        assertEquals("WARNING", label.tooltip);

        access.setText(label, "failed");
        access.setSeverityAppearance(label, "ERROR");
        assertEquals("[X] failed", label.text);
        assertEquals("ERROR", label.tooltip);
    }

    @Test
    void severityUpdateIsIdempotentPreservesMessagePrefixesAndUnknownSeverityFailsClosed() {
        VerifiedCxStatusBarHostAccess access = access();
        SyntheticLabel label = new SyntheticLabel("");

        access.setText(label, "building");
        access.setSeverityAppearance(label, "INFO");
        access.setText(label, "building");
        access.setSeverityAppearance(label, "INFO");
        assertEquals("[I] building", label.text, "repeated INFO must not double-prefix");

        access.setText(label, "[I] legacy");
        access.setSeverityAppearance(label, "ERROR");
        assertEquals("[X] [I] legacy", label.text, "message prefix content must be preserved");

        assertThrows(IllegalArgumentException.class,
            () -> access.setSeverityAppearance(label, "DEBUG"));
    }

    @Test
    void addRemoveAndRefreshUseTheVerifiedNativeOperations() {
        VerifiedCxStatusBarHostAccess access = access();
        SyntheticContainer statusBar = new SyntheticContainer();
        SyntheticLabel coordinates = new SyntheticLabel("coordinates");
        statusBar.children.add(coordinates);

        SyntheticLabel widget = (SyntheticLabel) access.createLabel("status", "msg");
        access.setName(widget, "scoped-id");
        assertEquals("scoped-id", widget.name);

        access.add(statusBar, widget, 0);
        assertEquals(List.of(widget, coordinates), statusBar.children);

        access.refresh(statusBar);
        assertTrue(statusBar.revalidated);
        assertTrue(statusBar.repainted);

        access.remove(statusBar, widget);
        assertEquals(List.of(coordinates), statusBar.children);
    }

    private static VerifiedCxStatusBarHostAccess access() {
        return new VerifiedCxStatusBarHostAccess(statusResolver());
    }

    public static VerifiedMemberResolver statusResolver() {
        String appCtrl = name(SyntheticAppCtrl.class);
        String mainFrameCtrl = name(SyntheticMainFrameCtrl.class);
        String frame = name(SyntheticFrame.class);
        String container = name(SyntheticContainer.class);
        String widget = name(SyntheticWidget.class);
        String label = name(SyntheticLabel.class);
        String memoryViewer = name(SyntheticMemoryViewer.class);
        String viewContext = name(SyntheticViewContext.class);
        return TestVerifiedResolvers.create(
            StatusBarVerificationManifest.ADAPTER_SLICE_ID,
            StatusBarVerificationManifest.CAPABILITY_IDS,
            List.of(
                StaticSelector.classSelector("cubism.ui-status-bar.app-controller.class", appCtrl),
                StaticSelector.staticMethod("cubism.ui-status-bar.app-controller.instance", appCtrl,
                    "instance", "()L" + appCtrl + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.app-controller.main-frame", appCtrl,
                    "getMainFrameCtrl", "()L" + mainFrameCtrl + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.main-frame-controller.class", mainFrameCtrl),
                StaticSelector.method("cubism.ui-status-bar.main-frame-controller.frame", mainFrameCtrl,
                    "getMainFrame", "()L" + frame + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.frame.class", frame),
                StaticSelector.method("cubism.ui-status-bar.frame.content-pane", frame,
                    "getContentPane", "()L" + container + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.widget.class", widget),
                StaticSelector.method("cubism.ui-status-bar.widget.set-name", widget,
                    "setName", "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.widget.set-tooltip", widget,
                    "setToolTipText", "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.widget.revalidate", widget,
                    "revalidate", "()V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.widget.repaint", widget,
                    "repaint", "()V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.container.class", container),
                StaticSelector.method("cubism.ui-status-bar.container.children", container,
                    "getChildren", "()Ljava/util/List;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.container.add", container,
                    "add", "(Ljava/lang/Object;I)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.container.remove", container,
                    "remove", "(Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.label.class", label),
                StaticSelector.constructor("cubism.ui-status-bar.label.create", label,
                    "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.label.text", label,
                    "getText", "()Ljava/lang/String;", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-status-bar.label.set-text", label,
                    "setText", "(Ljava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-status-bar.memory-viewer.class", memoryViewer),
                StaticSelector.method("cubism.ui-canvas-hint.app-controller.current-view-context", appCtrl,
                    "getCurrentViewContext", "()L" + viewContext + ";", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-canvas-hint.view-context.class", viewContext),
                StaticSelector.method("cubism.ui-canvas-hint.view-context.show-hint", viewContext,
                    "showHint", "(Ljava/lang/String;FLjava/lang/String;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.method("cubism.ui-canvas-hint.view-context.show-hint-with-action", viewContext,
                    "showHintWithFunc", "(Ljava/lang/String;FLjava/lang/String;L" + name(SyntheticHintAction.class)
                        + ";Ljava/lang/Object;)V", StaticSelector.ACCESS_PUBLIC),
                StaticSelector.classSelector("cubism.ui-canvas-hint.position.class",
                    name(SyntheticPosition.class)),
                StaticSelector.constructor("cubism.ui-canvas-hint.position.create",
                    name(SyntheticPosition.class), "(FF)V", StaticSelector.ACCESS_PUBLIC)
            ),
            VerifiedCxStatusBarHostAccessTest.class.getClassLoader()
        );
    }

    private static String name(final Class<?> type) {
        return type.getName().replace('.', '/');
    }

    /** Stands in for the host's Kotlin {@code Function1<GButtonEntity, Unit>} click action. */
    public interface SyntheticHintAction {
        void clicked(Object button);
    }

    /** Stands in for the host's {@code GVector2(float, float)} position override. */
    public static final class SyntheticPosition {
        final float x;
        final float y;

        public SyntheticPosition(final float x, final float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class SyntheticViewContext {
        String message;
        float durationSeconds;
        String key;
        SyntheticHintAction action;
        Object position;

        public void showHint(final String message, final float durationSeconds, final String key) {
            this.message = message;
            this.durationSeconds = durationSeconds;
            this.key = key;
            this.action = null;
        }

        public void showHintWithFunc(
            final String message,
            final float durationSeconds,
            final String key,
            final SyntheticHintAction action,
            final Object position
        ) {
            this.message = message;
            this.durationSeconds = durationSeconds;
            this.key = key;
            this.action = action;
            this.position = position;
        }
    }

    public static final class SyntheticAppCtrl {
        private static SyntheticAppCtrl instance;
        private final SyntheticMainFrameCtrl mainFrameCtrl;
        private final SyntheticViewContext viewContext;

        SyntheticAppCtrl(final SyntheticMainFrameCtrl mainFrameCtrl) {
            this(mainFrameCtrl, new SyntheticViewContext());
        }

        SyntheticAppCtrl(
            final SyntheticMainFrameCtrl mainFrameCtrl,
            final SyntheticViewContext viewContext
        ) {
            this.mainFrameCtrl = mainFrameCtrl;
            this.viewContext = viewContext;
        }

        public static SyntheticAppCtrl instance() {
            return instance;
        }

        public SyntheticMainFrameCtrl getMainFrameCtrl() {
            return mainFrameCtrl;
        }

        public SyntheticViewContext getCurrentViewContext() {
            return viewContext;
        }
    }

    public static final class SyntheticMainFrameCtrl {
        private final SyntheticFrame frame;

        SyntheticMainFrameCtrl(final SyntheticFrame frame) {
            this.frame = frame;
        }

        public SyntheticFrame getMainFrame() {
            return frame;
        }
    }

    public static final class SyntheticFrame {
        private final SyntheticContainer contentPane;

        SyntheticFrame(final SyntheticContainer contentPane) {
            this.contentPane = contentPane;
        }

        public SyntheticContainer getContentPane() {
            return contentPane;
        }
    }

    public static class SyntheticContainer extends SyntheticWidget {
        final List<Object> children = new ArrayList<>();

        public List<Object> getChildren() {
            return children;
        }

        public void add(final Object widget, final int index) {
            children.add(index, widget);
        }

        public void remove(final Object widget) {
            children.remove(widget);
        }
    }

    public static class SyntheticWidget {
        String name;
        String tooltip;
        boolean revalidated;
        boolean repainted;

        public void setName(final String name) {
            this.name = name;
        }

        public void setToolTipText(final String tooltip) {
            this.tooltip = tooltip;
        }

        public void revalidate() {
            revalidated = true;
        }

        public void repaint() {
            repainted = true;
        }
    }

    public static final class SyntheticLabel extends SyntheticWidget {
        String text;

        public SyntheticLabel(final String text) {
            this.text = text;
        }

        public String getText() {
            return text;
        }

        public void setText(final String text) {
            this.text = text;
        }
    }

    public static final class SyntheticMemoryViewer extends SyntheticWidget {
    }
}
