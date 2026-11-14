package dev.turboism.tests.plugin;

import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressInspectorSurface.UI_MAP_MAX_DEPTH;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressPartActors.ActorPart;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.Observed;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.POLL_MILLIS;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.blank;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.concat;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.containsAny;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.finite;
import static dev.turboism.tests.plugin.WindowsHistoryNativeUiIngressVerdicts.onEdt;

import java.awt.Point;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import javax.swing.JTable;
import javax.swing.SwingUtilities;

/**
 * Canvas surface discovery, window raising, and confirmation dialog handling.
 *
 * <p>The model view is a nested GL surface, so the scan reaches deeper than the evidence dump.
 * Press points are validated against the visible leaf rule before any input is dispatched.</p>
 */
final class WindowsHistoryNativeUiIngressCanvasActor {
    /** Smallest edge a component needs before the canvas fallback may treat it as the view. */
    static final int MIN_CANVAS_EDGE = 128;

    /** Bound on how many candidate surfaces the canvas actor will drag in one step. */
    static final int MAX_CANVAS_CANDIDATES = 6;

    /**
     * Deepest level the canvas scan may reach.
     *
     * <p>The r19 run showed the model view's actual GL surface nested deeper than the ui-map's
     * dump bound: every ancestor leaf check failed because the visible leaf rule stopped before
     * the surface, while the surface's own parent chain stayed invisible to a depth-12 walk. The
     * discovery walk gets a deeper bound than the evidence dump; press-point validation below is
     * what keeps a container press from landing on a control.</p>
     */
    static final int CANVAS_SCAN_DEPTH = UI_MAP_MAX_DEPTH * 2;

    /**
     * Starts a daemon that keeps the host frame in front for the actor's whole run: the
     * launcher's own {@code cmd.exe} console shares the display at the same geometry and
     * can re-cover the editor mid-step, sending real Robot input to the console instead.
     */
    static Thread hostWindowRaiser() {
        final Thread thread = new Thread(
                () -> {
                    try {
                        while (!Thread.currentThread().isInterrupted()) {
                            try {
                                raiseHostWindow();
                            } catch (InterruptedException interrupted) {
                                Thread.currentThread().interrupt();
                                throw interrupted;
                            } catch (Throwable ignored) {
                                // A missed raise only costs the next interval.
                            }
                            Thread.sleep(2000L);
                        }
                    } catch (InterruptedException ignored) {
                        Thread.currentThread().interrupt();
                    }
                },
                "native-ui-window-raiser");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Raises the host frame before a Robot gesture: the launcher's own {@code cmd.exe}
     * console window shares the display at the same geometry and can sit on top of the
     * editor, in which case real input lands on the console instead of the canvas.
     */
    static void raiseHostWindow() throws Exception {
        onEdt(() -> {
            java.awt.Window best = null;
            for (final java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof java.awt.Frame
                        && window.isVisible()
                        && (best == null
                                || (long) window.getWidth() * window.getHeight()
                                        > (long) best.getWidth() * best.getHeight())) {
                    best = window;
                }
            }
            if (best != null) {
                best.toFront();
                best.requestFocus();
            }
            return null;
        });
        Thread.sleep(POLL_MILLIS);
    }

    static boolean isDescendant(final String sourceId, final String candidateId, final Map<String, ActorPart> byId) {
        final ArrayDeque<String> pending = new ArrayDeque<>();
        final Set<String> visited = new HashSet<>();
        pending.add(sourceId);
        while (!pending.isEmpty()) {
            final String current = pending.removeFirst();
            final ActorPart part = byId.get(current);
            if (part == null || !visited.add(current)) continue;
            for (final String childId : part.childIds()) {
                if (candidateId.equals(childId)) return true;
                pending.addLast(childId);
            }
        }
        return false;
    }

    static boolean fullyVisible(final javax.swing.JTable table, final java.awt.Rectangle cell) {
        final java.awt.Rectangle viewport = table.getVisibleRect();
        return viewport.contains(cell.x, cell.y)
                && viewport.contains(cell.x + cell.width - 1, cell.y + cell.height - 1);
    }

    static boolean hasPackageComponent(
            final java.awt.Component component, final String packageMarker, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return false;
        if (component.getClass().getName().contains(packageMarker) && component.isShowing()) return true;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (hasPackageComponent(child, packageMarker, depth + 1)) return true;
            }
        }
        return false;
    }

    static java.awt.Rectangle screenBounds(final java.awt.Component component) {
        if (component == null || !component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new java.awt.Rectangle(origin.x, origin.y, component.getWidth(), component.getHeight());
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    static java.util.Set<java.awt.Window> visibleWindows() {
        final java.util.Set<java.awt.Window> set = new java.util.HashSet<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) set.add(window);
        }
        return set;
    }

    static java.awt.Window newWindowSince(final java.util.Set<java.awt.Window> before) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && !before.contains(window)) return window;
        }
        return null;
    }

    /** The screen centre of a dialog's OK-looking button, or null. Runs on the EDT. */
    static int[] confirmPoint(final java.awt.Window dialog) {
        final javax.swing.AbstractButton[] found = {null};
        findConfirmButton(dialog, found, 0);
        return found[0] == null ? null : fieldCentre(found[0]);
    }

    static final String[] CONFIRM_MARKERS = {"OK", "确定", "適用", "适用", "Apply"};

    static void findConfirmButton(
            final java.awt.Component component, final javax.swing.AbstractButton[] found, final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || found[0] != null) return;
        if (component instanceof javax.swing.AbstractButton button
                && button.isShowing()
                && button.isEnabled()
                && button.getText() != null
                && containsAny(button.getText(), CONFIRM_MARKERS)) {
            found[0] = button;
            return;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                findConfirmButton(child, found, depth + 1);
            }
        }
    }

    static int visibleWindowCount() {
        int count = 0;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) count++;
        }
        return count;
    }

    /** The screen centre of a showing component, or {@code null} when it is not showing. */
    static int[] fieldCentre(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new int[] {origin.x + component.getWidth() / 2, origin.y + component.getHeight() / 2};
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /**
     * The rectangle of a component that is actually on screen.
     *
     * <p>A {@code JComponent} inside a viewport can extend far beyond what is rendered; pressing
     * at the bounds centre would land on whatever happens to sit at that screen point. Plain
     * heavyweight components have no such clipping, so their bounds are the visible region.</p>
     */
    static java.awt.Rectangle visibleBounds(final java.awt.Component component) {
        if (component instanceof javax.swing.JComponent swing) return swing.getVisibleRect();
        return new java.awt.Rectangle(0, 0, component.getWidth(), component.getHeight());
    }

    /** The visible Cubism document window, preferring the one whose title names the model. */
    static java.awt.Frame hostFrame() {
        java.awt.Frame fallback = null;
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!frame.isVisible()) continue;
            final String title = frame.getTitle();
            if (title != null && title.contains(".cmo3")) return frame;
            if (title != null && title.contains("Cubism")) fallback = frame;
            if (fallback == null) fallback = frame;
        }
        return fallback;
    }

    /**
     * The components most likely to be the model canvas, best first.
     *
     * <p>The document view does not have to live in a {@code Frame} — the r11 run showed the
     * model window is a {@code JDialog} — so discovery scans every visible window. A class whose
     * name carries a canvas/GL marker wins over the structural fallback, which keeps the largest
     * showing leaves that are not interactive controls; a press inside a palette button or a
     * slider would prove the wrong family, so those leaves never qualify.</p>
     */
    static List<java.awt.Component> canvasCandidates() {
        final List<java.awt.Component> named = new ArrayList<>();
        final List<java.awt.Component> regions = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectCanvas(window, named, regions, 0);
        }
        regions.sort((first, second) -> Long.compare(
                (long) second.getWidth() * second.getHeight(), (long) first.getWidth() * first.getHeight()));
        // A region whose centre resolves to a host-class component is the model view itself —
        // press those first; a region whose centre resolves to a control is refused entirely so
        // its remaining fractions can never press a button the panel happens to wrap. Palette
        // regions are demoted below the document view: the r20 run showed the Parts palette's
        // inner cell component winning the host-class bucket and turning a "canvas move" into a
        // Parts drag.
        final List<java.awt.Component> hostHits = new ArrayList<>();
        final List<java.awt.Component> palettes = new ArrayList<>();
        final List<java.awt.Component> plain = new ArrayList<>();
        for (final java.awt.Component region : regions) {
            final String hit = pressTarget(region, new java.awt.Point(region.getWidth() / 2, region.getHeight() / 2));
            if (hit == null) continue;
            if (hit.contains(".palette.") || region.getClass().getName().contains(".palette.")) {
                palettes.add(region);
            } else if (isHostClassName(hit)) {
                hostHits.add(region);
            } else {
                plain.add(region);
            }
        }
        final List<java.awt.Component> candidates = new ArrayList<>(named);
        final java.util.Set<java.awt.Rectangle> seen = new java.util.HashSet<>();
        for (final java.awt.Component candidate : named) {
            final java.awt.Rectangle rect = screenRect(candidate);
            if (rect != null) seen.add(rect);
        }
        for (final java.awt.Component region : concat(hostHits, concat(palettes, plain))) {
            if (candidates.size() >= MAX_CANVAS_CANDIDATES) break;
            if (candidates.contains(region)) continue;
            // Two nested panels often cover the same pixels; one press point per region keeps the
            // bounded attempts spread across distinct screen areas instead of repeating a drag.
            final java.awt.Rectangle rect = screenRect(region);
            if (rect == null || !seen.add(rect)) continue;
            candidates.add(region);
        }
        return candidates;
    }

    static boolean isHostClassName(final String className) {
        return !className.startsWith("javax.swing.")
                && !className.startsWith("java.awt.")
                && !className.startsWith("sun.")
                && !className.startsWith("com.formdev.flatlaf.");
    }

    /** The component's on-screen rectangle, or {@code null} when it is not showing. */
    static java.awt.Rectangle screenRect(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new java.awt.Rectangle(origin.x, origin.y, component.getWidth(), component.getHeight());
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /**
     * Whether a press on this component is allowed to reach the host.
     *
     * <p>A candidate only supplies a screen region — the Robot press lands on whatever component
     * the host's own hit-testing finds. Interactive controls are still refused at the point level
     * by {@link #pressTarget}, so a container whose centre resolves to the GL view is a safe drag
     * surface even though it is not a leaf.</p>
     */
    static boolean isExcludedControl(final java.awt.Component component) {
        return component instanceof javax.swing.AbstractButton
                || component instanceof javax.swing.JSlider
                || component instanceof javax.swing.JComboBox<?>
                || component instanceof javax.swing.text.JTextComponent
                || component instanceof javax.swing.JSpinner
                || component instanceof java.awt.Scrollbar;
    }

    /**
     * The component a press at {@code local} would actually reach, or {@code null} when the point
     * is refused.
     *
     * <p>Refused means the deepest showing component at the point is an interactive control, or a
     * sliver thinner than a drag target — the pane dividers are a few pixels wide and pressing one
     * would resize the dock layout instead of moving the model.</p>
     */
    static String pressTarget(final java.awt.Component canvas, final java.awt.Point local) {
        java.awt.Component hit = javax.swing.SwingUtilities.getDeepestComponentAt(canvas, local.x, local.y);
        if (hit == null) hit = canvas;
        if (isExcludedControl(hit)) return null;
        if (hit.getWidth() < 8 || hit.getHeight() < 8) return null;
        return hit.getClass().getName();
    }

    static void collectCanvas(
            final java.awt.Component component,
            final List<java.awt.Component> named,
            final List<java.awt.Component> regions,
            final int depth) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        // Only a host/JOGL class may win by name: a Swing widget whose name happens to contain a
        // canvas/GL marker (JToggleButton carries "gl", JViewport carries "View") would be
        // dragged like the canvas and could toggle a mode instead of moving the model. A plain
        // java.awt.Canvas stays eligible because a heavyweight GL surface is exactly that class.
        // The marker is matched on the simple name, not the package — the r21 run promoted
        // CPartsTreeTable.g into this bucket because every com.live2d.cubism.view.* class carries
        // the ".view." package segment.
        final boolean hostClass = component instanceof java.awt.Canvas
                || (!className.startsWith("javax.swing.")
                        && !className.startsWith("java.awt.")
                        && !className.startsWith("sun."));
        if (component.isShowing()
                && hostClass
                && !className.contains(".palette.")
                && component.getClass().getSimpleName().matches(".*[cC]anvas.*|.*[gG][lL].*|.*[vV]iew.*")
                && !named.contains(component)) {
            named.add(component);
        }
        // A drag surface only has to mark a plausible press region: the r19 run showed the model
        // view's ancestors rejecting the leaf rule while the GL surface itself sat deeper than
        // the old scan reached. Every showing non-control component above the minimum area is a
        // region candidate; {@link #pressTarget} refuses any point that resolves to a control or
        // a divider sliver, so a container entry can never press a button it happens to contain.
        if (component.isShowing()
                && component.getWidth() >= MIN_CANVAS_EDGE
                && component.getHeight() >= MIN_CANVAS_EDGE
                && !isExcludedControl(component)
                && !(component instanceof java.awt.Window)) {
            regions.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectCanvas(child, named, regions, depth + 1);
            }
        }
    }

    static String boundsOf(final java.awt.Component component) {
        try {
            final java.awt.Point origin =
                    component.isShowing() ? component.getLocationOnScreen() : new java.awt.Point(0, 0);
            return "[" + origin.x + ',' + origin.y + ',' + component.getWidth() + 'x' + component.getHeight() + ']';
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return "[hidden]";
        }
    }

    /** Validates only the confirmed lifecycle; before events remain independent hook evidence. */
    static ConfirmationCheck checkConfirmations(final List<Observed> events) {
        final Set<Long> beforeSequences = new HashSet<>();
        final Map<Long, Observed> onBySequence = new LinkedHashMap<>();
        final Map<Long, Observed> afterBySequence = new HashMap<>();
        for (Observed event : events) {
            if (event == null) return ConfirmationCheck.invalid("null-event");
            final String phase = event.phase();
            if ("before".equals(phase)) {
                if (!beforeSequences.add(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                continue;
            }
            if ("on".equals(phase)) {
                if (onBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                if (afterBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("confirmation-reversed");
                }
                onBySequence.put(event.sequence(), event);
                continue;
            }
            if ("after".equals(phase)) {
                if (afterBySequence.containsKey(event.sequence())) {
                    return ConfirmationCheck.invalid("duplicate-confirmation-phase");
                }
                final Observed on = onBySequence.get(event.sequence());
                if (on == null) {
                    return ConfirmationCheck.invalid(
                            onBySequence.isEmpty() ? "orphan-after-event" : "confirmation-sequence-mismatch");
                }
                if (blank(on.operation()) || blank(on.origin())) {
                    return ConfirmationCheck.invalid("confirmation-fields-missing");
                }
                if (!sameConfirmationFields(on, event)) {
                    return ConfirmationCheck.invalid("confirmation-fields-mismatch");
                }
                afterBySequence.put(event.sequence(), event);
                continue;
            }
            return ConfirmationCheck.invalid("unknown-event-phase");
        }
        final List<ConfirmationPair> pairs = new ArrayList<>(onBySequence.size());
        for (Map.Entry<Long, Observed> entry : onBySequence.entrySet()) {
            final Observed after = afterBySequence.get(entry.getKey());
            if (after == null) return ConfirmationCheck.invalid("orphan-on-event");
            pairs.add(new ConfirmationPair(entry.getValue(), after));
        }
        return new ConfirmationCheck(true, "", List.copyOf(pairs));
    }

    static boolean sameConfirmationFields(final Observed on, final Observed after) {
        return Objects.equals(on.operation(), after.operation())
                && Objects.equals(on.origin(), after.origin())
                && Objects.equals(on.subjectId(), after.subjectId());
    }

    static boolean within(final Float value, final float start, final float end) {
        if (!finite(value)) return false;
        final float low = Math.min(start, end);
        final float high = Math.max(start, end);
        return value >= low && value <= high;
    }

    static Point toScreenPoint(final JTable table, final Point local) {
        final java.awt.Point screen = new java.awt.Point(local);
        SwingUtilities.convertPointToScreen(screen, table);
        return screen;
    }

    record ConfirmationPair(Observed on, Observed after) {}

    record ConfirmationCheck(boolean valid, String code, List<ConfirmationPair> pairs) {
        static ConfirmationCheck invalid(final String code) {
            return new ConfirmationCheck(false, code, List.of());
        }
    }
}
