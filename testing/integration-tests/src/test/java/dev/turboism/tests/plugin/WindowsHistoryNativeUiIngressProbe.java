package dev.turboism.tests.plugin;

import dev.turboism.sdk.cubism.CubismPlugin;
import dev.turboism.sdk.cubism.event.CubismOperationEvent;
import dev.turboism.sdk.plugin.PluginContext;

import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Manual-test-only probe: records what an operator's own Editor edits look like to Turboism.
 *
 * <p>The automated probes drive the host through the SDK, so they can never show what the
 * {@code before} phase sees for an edit made by clicking and dragging in the native user
 * interface. This probe therefore hands the actions to the operator: it publishes one
 * instruction, waits until the native undo manager actually moves, and then records the native
 * entries the action produced together with every semantic event Turboism published for it.</p>
 *
 * <p>Outside the bounded actor paths below it changes nothing: it writes evidence, publishes
 * nothing, and never mutates the model itself. With {@code -Dturboism.history.nativeUi.automate}
 * the listed actors drive the same real host UI an operator would — the recorded change is then
 * caused by the probe's own UI action, which the artifact names per step.</p>
 */
public final class WindowsHistoryNativeUiIngressProbe implements CubismPlugin {

    private static final long MAX_EVIDENCE_BYTES = 2_097_152L;
    private static final long TERMINAL_RESERVE_BYTES = 4_096L;
    private static final int MAX_RECORDED_EVENTS = 512;
    private static final long POLL_MILLIS = 250L;

    /**
     * Whether the probe drives the bounded native actions itself.
     *
     * <p>Automation is opt-in through {@code -Dturboism.history.nativeUi.automate=true}: the
     * default stays the operator runbook. When enabled, a step with an actor performs its action
     * through the real host UI — menu accelerators and {@code Robot} input on the visible
     * windows — and a step without one keeps waiting for the operator. An actor that cannot
     * resolve its control reports {@code unresolved} and the operator window stays open, so an
     * automated run degrades to manual pacing instead of failing blind.</p>
     */
    static final boolean AUTOMATE =
        Boolean.parseBoolean(System.getProperty("turboism.history.nativeUi.automate", "false"));

    /** Bound on the component dump written for actor targeting review. */
    private static final int UI_MAP_MAX_COMPONENTS = 400;
    private static final int UI_MAP_MAX_WINDOWS = 8;
    private static final int UI_MAP_MAX_DEPTH = 12;
    private static final int UI_MAP_MAX_CHARS = 65_536;

    /** Smallest edge a component needs before the canvas fallback may treat it as the view. */
    private static final int MIN_CANVAS_EDGE = 128;

    /**
     * How often a pending step re-announces its instruction.
     *
     * <p>The step window opens when the instruction is written, and a human who is not at the
     * desk yet reads nothing. Re-announcing inside the same window means a late operator sees the
     * current instruction instead of losing the step; it costs no extra time because the deadline
     * does not move.</p>
     */
    private static final long REMINDER_MILLIS = 90_000L;

    /** How long an edit step waits for a multi-entry action to finish committing. */
    private static final long ACTION_SETTLE_MILLIS = 2_000L;

    /**
     * How long a navigation step waits.
     *
     * <p>Deliberately short: a native Undo or Redo is one atomic step, and a long settle lets the
     * operator's next keystroke land inside this step's window, which is how the previous three
     * runs misattributed the redo.</p>
     */
    private static final long NAVIGATION_SETTLE_MILLIS = 300L;

    /**
     * How long one sample of the host may take.
     *
     * <p>A sample runs on the Editor thread. If that thread has stopped pumping events — because
     * the Editor is closing, or a modal dialog is up — the wait has to end anyway, so the probe
     * can write its verdicts and its summary instead of hanging with no terminal line.</p>
     */
    private static final long SAMPLE_TIMEOUT_MILLIS = 15_000L;
    private static final long AWAIT_DOCUMENT_MILLIS = 240_000L;
    /**
     * How long one step waits for the operator.
     *
     * <p>Generous on purpose: the window opens when the instruction is written, and a human has to
     * read it and walk the mouse to the right part of the Editor before anything happens.</p>
     */
    private static final long STEP_TIMEOUT_MILLIS = 420_000L;

    /**
     * Operator steps in order.
     *
     * <p>The first six are the native action families whose ingress is being reviewed, split so
     * that each one produces a shape a decoder can be judged against: a whole-object move
     * translates every point by the same vector, while a mesh deformation does not. The last two
     * are a native Undo and Redo of whatever the operator left on the stack.</p>
     */
    private static final List<Step> STEPS = List.of(
        new Step(
            "parts-tree-drag",
            "ACTION",
            "Drag one Part onto a different Part in the Parts tree, then release."
        ),
        new Step(
            "deformer-assign",
            "ACTION",
            "Assign a different Deformer target to one Part or ArtMesh and confirm it: the"
                + " Deformers palette must show the new target. Selecting the object alone is not"
                + " an assignment and does not count."
        ),
        new Step(
            "canvas-move",
            "ACTION",
            "Move ONE model object as a whole on the canvas with the mouse, then release. Do not"
                + " edit its vertices or mesh points."
        ),
        new Step(
            "canvas-deform",
            "ACTION",
            "Deform ONE model object instead of moving it: drag a single mesh point (or use the"
                + " mesh edit tool) so its shape changes, then release."
        ),
        new Step(
            "native-parameter",
            "ACTION",
            "Change one Parameter value in the native Parameter palette only (drag its slider or"
                + " type a value), then release. Do not move any object."
        ),
        new Step(
            "native-color",
            "ACTION",
            "Change only the multiply colour (正片叠底色) of one drawable in the native"
                + " palette, then confirm. Do not move the object and do not edit its mesh."
        ),
        new Step(
            "native-undo",
            "UNDO",
            "Press the native Undo shortcut once (Ctrl+Z)."
        ),
        new Step(
            "native-redo",
            "REDO",
            "Press the native Redo shortcut once (Ctrl+Y)."
        )
    );

    private final Object lock = new Object();
    private final List<Observed> observed = new ArrayList<>();
    private PluginContext context;
    private Path artifact;
    private Thread worker;
    private volatile boolean running;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        context.logger().info("Native UI ingress probe initialized");
    }

    @Override
    public void enable() {
        running = true;
        worker = new Thread(this::run, "turboism-history-native-ui-ingress");
        worker.setDaemon(true);
        worker.start();
    }

    @Override
    public void disable() {
        running = false;
        final Thread current = worker;
        if (current != null) {
            current.interrupt();
            try {
                current.join(5_000L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void beforeCubismOperation(final CubismOperationEvent event) {
        record("before", event);
    }

    @Override
    public void onCubismOperationConfirmed(final CubismOperationEvent event) {
        record("on", event);
    }

    @Override
    public void afterCubismOperation(final CubismOperationEvent event) {
        record("after", event);
    }

    /**
     * Records one semantic event.
     *
     * <p>Runs on the host's own thread, so it allocates one small immutable record and returns.
     * Nothing here may throw: the runtime isolates a failing hook, but a probe that throws would
     * lose the evidence for the very operation it exists to describe.</p>
     */
    private void record(final String phase, final CubismOperationEvent event) {
        try {
            if (event == null) return;
            final Observed value = new Observed(
                phase,
                event.sequence(),
                event.operation() == null ? "" : event.operation().name(),
                event.origin() == null ? "" : event.origin().name(),
                event.subjectId().orElse(""),
                event.label().orElse(""),
                Thread.currentThread().getName(),
                Instant.now().toString()
            );
            synchronized (lock) {
                observed.add(value);
                if (observed.size() > MAX_RECORDED_EVENTS) {
                    observed.remove(0);
                }
            }
        } catch (Throwable ignored) {
            // Observing must never disturb the operation being observed.
        }
    }

    private void run() {
        artifact = context.paths().dataDir().resolve("history-native-ui-ingress.jsonl");
        try {
            Files.createDirectories(artifact.getParent());
            if (Files.exists(artifact)) {
                throw new IllegalStateException("Native UI ingress evidence already exists");
            }
            final List<String> failures = new ArrayList<>();
            write(artifact, "{\"type\":\"event\",\"phase\":\"start\"}\n", false);
            final boolean ready = awaitDocument();
            write(artifact, "{\"type\":\"event\",\"phase\":\"document\",\"ready\":" + ready + "}\n", false);
            if (!ready) {
                failures.add("no-native-document");
            }
            final WindowsHistoryManagerValidationProbe.Snapshot baseline = sample();
            write(artifact, paired(baseline, "baseline"), false);
            String knownSignificant = significantSequence(baseline);
            long knownPosition = position(baseline);
            boolean hookFired = false;
            boolean observerFired = false;
            boolean labelSeen = false;

            for (int index = 0; ready && index < STEPS.size(); index++) {
                final Step step = STEPS.get(index);
                final int start = cursor();
                final String instruction = "STEP " + (index + 1) + "/" + STEPS.size()
                    + " [" + step.id() + "] " + step.instruction();
                write(
                    artifact,
                    "{\"type\":\"prompt\",\"phase\":\"" + json(step.id()) + "\",\"instruction\":\""
                        + json(step.instruction()) + "\",\"openedAt\":\"" + Instant.now()
                        + "\",\"expiresAt\":\""
                        + Instant.ofEpochMilli(System.currentTimeMillis() + STEP_TIMEOUT_MILLIS)
                        + "\"}\n",
                    false
                );
                context.logger().info(instruction);

                if (AUTOMATE) {
                    final String actor = act(step, knownSignificant);
                    write(
                        artifact,
                        "{\"type\":\"actor\",\"phase\":\"" + json(step.id())
                            // The discovery detail legitimately exceeds a label's bound; clipping
                            // it hid why the r9 Parts-tree selection never resolved.
                            + "\",\"result\":\"" + json(actor, 8192) + "\"}\n",
                        false
                    );
                    // The map is captured when an actor actually ran — by then the document UI is
                    // populated, and the dump shows the controls the actor just used or missed.
                    if (!"none".equals(actor)) {
                        write(artifact, "{\"type\":\"ui-map\",\"phase\":\"" + json(step.id())
                            + "\",\"at\":\"" + Instant.now()
                            + "\",\"map\":\"" + json(uiMap(), UI_MAP_MAX_CHARS) + "\"}\n", false);
                    }
                }

                final WindowsHistoryManagerValidationProbe.Snapshot after =
                    awaitChange(step, knownSignificant, knownPosition);
                if (after == null) {
                    failures.add(step.id() + ":no-native-change");
                    write(
                        artifact,
                        new Verdict(false, "no-native-change", "unchanged after " + STEP_TIMEOUT_MILLIS + "ms")
                            .json(step.id()),
                        false
                    );
                    continue;
                }
                knownSignificant = significantSequence(after);
                knownPosition = position(after);
                write(artifact, paired(after, step.id()), false);
                final List<Observed> events;
                synchronized (lock) {
                    events = List.copyOf(observed.subList(Math.min(start, observed.size()), observed.size()));
                }
                for (Observed event : events) {
                    write(artifact, event.json(step.id()), false);
                }
                final Verdict verdict = checkStep(step, events);
                write(artifact, verdict.json(step.id()), false);
                if (!verdict.ok()) failures.add(step.id() + ":" + verdict.code());
                hookFired |= events.stream().anyMatch(event -> event.phase().equals("before"));
                observerFired |= events.stream().anyMatch(
                    event -> event.phase().equals("on") || event.phase().equals("after")
                );
                labelSeen |= events.stream().anyMatch(
                    event -> event.phase().equals("before") && !event.label().isBlank()
                );
            }

            final WindowsHistoryManagerValidationProbe.Snapshot finalSnapshot = sample();
            write(artifact, paired(finalSnapshot, "final"), false);
            write(
                artifact,
                new Verdict(
                    hookFired,
                    "hook-fired",
                    hookFired ? "at least one before event observed" : "no before event observed"
                ).json("overall"),
                false
            );
            write(
                artifact,
                new Verdict(
                    observerFired,
                    "observer-fired",
                    observerFired
                        ? "the ingress attached and published at least one operation"
                        : "no operation was published for any native change"
                ).json("overall"),
                false
            );
            write(
                artifact,
                new Verdict(
                    labelSeen,
                    "label-transported",
                    labelSeen
                        ? "at least one before event carried the native edit name"
                        : "every before event carried an empty name"
                ).json("overall"),
                false
            );
            // Only the hook firing is gated. A family whose native edit name is empty is a finding
            // to report, not a probe failure: the label is presentation, and the operation is
            // identified structurally.
            if (!hookFired) failures.add("hook-did-not-fire");
            if (!observerFired) failures.add("observer-did-not-fire");
            // The failure list is its own line: the host runner detects the terminal result by
            // comparing a whole line, so the summary line must be exactly what it looks for.
            write(
                artifact,
                "{\"type\":\"failures\",\"failures\":["
                    + String.join(
                        ",",
                        failures.stream().map(WindowsHistoryNativeUiIngressProbe::quoted).toList()
                    )
                    + "]}\n",
                false
            );
            write(artifact, summaryLine(failures.isEmpty()), true);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            // The Editor was closed while a step was pending. The runner matches a whole line, so
            // returning silently would leave it with nothing to match and no evidence at all.
            try {
                write(
                    artifact,
                    "{\"type\":\"error\",\"class\":\"interrupted\",\"message\":\""
                        + "the probe was disabled while a step was pending\"}\n"
                        + "{\"type\":\"failures\",\"failures\":[\"probe-disabled-mid-run\"]}\n"
                        + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                    true
                );
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
        } catch (Exception exception) {
            context.logger().error("Native UI ingress probe failed", exception);
            try {
                write(
                    artifact,
                    "{\"type\":\"error\",\"class\":\"" + json(exception.getClass().getName())
                        + "\",\"message\":\"" + json(exception.getMessage()) + "\"}\n"
                        + "{\"type\":\"summary\",\"status\":\"FAIL\"}\n",
                    true
                );
            } catch (Exception ignored) {
                context.logger().error("Native UI ingress evidence could not be written", ignored);
            }
        }
    }

    /**
     * Waits for the operator's action, then for the runtime to settle.
     *
     * <p>Detecting the native position, or the number of <em>significant</em> entries, moving is
     * what proves the operator's action reached the native undo manager; a prompt alone would let
     * the probe record an unrelated edit.</p>
     *
     * <p>Significant entries rather than all entries, because a selection is itself an ordinary
     * native undo entry. The host marks each entry's significance, so a step whose instruction
     * begins with a click would otherwise be closed by the click that preceded the real edit and
     * every following step would be attributed to the previous step's family. Every selection
     * entry the probe has recorded so far is insignificant and every real edit is significant,
     * which makes this a fact the host already states rather than a rule about labels.</p>
     *
     * <p>The wait is bounded, and so is each sample: a sample is taken on the Editor thread, so an
     * Editor that has stopped pumping events would otherwise park this thread for the life of the
     * process and the run would end with no verdict and no summary at all.</p>
     */
    private WindowsHistoryManagerValidationProbe.Snapshot awaitChange(
        final Step step,
        final String knownSignificant,
        final long knownPosition
    ) throws Exception {
        final long deadline = System.currentTimeMillis() + STEP_TIMEOUT_MILLIS;
        long nextReminder = System.currentTimeMillis() + REMINDER_MILLIS;
        // Direction is measured against the last position actually observed, not against the
        // window's opening position. An edit made inside the window raises the position, so a
        // later undo returns to the opening value rather than below it, and a window baseline
        // would never see the step's own move.
        long lastPosition = knownPosition;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting the operator");
            Thread.sleep(POLL_MILLIS);
            final WindowsHistoryManagerValidationProbe.Snapshot current = sample();
            if (current == null) continue;
            if (hasMoved(
                step,
                significantSequence(current),
                position(current),
                knownSignificant,
                lastPosition
            )) {
                Thread.sleep(settleMillis(step));
                return sample();
            }
            lastPosition = position(current);
            if (System.currentTimeMillis() >= nextReminder) {
                nextReminder = System.currentTimeMillis() + REMINDER_MILLIS;
                remind(step);
            }
        }
        return null;
    }

    /**
     * Re-announces a step that is still pending, so an operator who arrives late still sees it.
     *
     * <p>Logged rather than published as a new prompt: the step window has not moved, and a second
     * prompt line would read as a second step.</p>
     */
    private void remind(final Step step) {
        try {
            context.logger().info("STILL WAITING [" + step.id() + "] " + step.instruction());
            write(
                artifact,
                "{\"type\":\"reminder\",\"phase\":\"" + json(step.id())
                    + "\",\"at\":\"" + Instant.now() + "\"}\n",
                false
            );
        } catch (Exception ignored) {
            // A reminder is a courtesy; losing one must not fail the step.
        }
    }

    private boolean awaitDocument() throws Exception {
        final long deadline = System.currentTimeMillis() + AWAIT_DOCUMENT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            if (!running) throw new InterruptedException("Probe disabled while awaiting the document");
            Thread.sleep(1_000L);
            if (sample() != null) return true;
        }
        return false;
    }

    /**
     * Runs the bounded native action registered for this step, if any.
     *
     * <p>The return value is evidence, not control flow: it is written to the artifact verbatim
     * and the step still closes only on the native change {@link #awaitChange} observes. An actor
     * that cannot resolve its UI target, or whose action the host rejects, simply produces no
     * significant entry and the operator window runs its course.</p>
     */
    String act(final Step step, final String knownSignificant) {
        try {
            return switch (step.id()) {
                case "parts-tree-drag" -> dragPartRow();
                case "canvas-move" -> dragCanvas(knownSignificant);
                case "native-undo" -> shortcut(java.awt.event.KeyEvent.VK_Z);
                case "native-redo" -> shortcut(java.awt.event.KeyEvent.VK_Y);
                default -> "none";
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception failure) {
            final String message = failure.getMessage();
            return "failed:" + failure.getClass().getSimpleName()
                + (message == null ? "" : ":" + message);
        }
    }

    /**
     * Drags one Part row onto another in the Parts palette.
     *
     * <p>Reuses the mesh-edit probe's exact Parts-tree discovery: it finds the tree by its
     * listener/owner classes rather than a title, expands and scrolls the named rows into view,
     * and returns their owner-table cell centers in screen coordinates. The drag itself is real
     * input — Robot press, interpolated move, release — so the host's own drop handling decides
     * whether a hierarchy entry is committed.</p>
     */
    private String dragPartRow() throws Exception {
        final List<String> names = onEdt(() ->
            context.cubism().model().active().parts().all().stream()
                .map(dev.turboism.sdk.cubism.model.Part::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        if (names.size() < 2) return "unresolved:fewer-than-two-parts";
        final String source = names.get(0);
        final String target = names.get(names.size() - 1);
        final WindowsMeshEditValidationProbe.SelectionAttempt from =
            onEdt(() -> WindowsMeshEditValidationProbe.selectTreePath(source));
        final WindowsMeshEditValidationProbe.SelectionAttempt to =
            onEdt(() -> WindowsMeshEditValidationProbe.selectTreePath(target));
        if (!from.selected() || !to.selected()) {
            return "unresolved:sought=" + source + "->" + target
                + ":from=" + from.treeDescription() + "|to=" + to.treeDescription();
        }
        robotDrag(from.screenX(), from.screenY(), to.screenX(), to.screenY());
        return "dragged:" + source + "->" + target;
    }

    /**
     * Drags inside the model canvas the way the operator does: left button held down, moved,
     * released.
     *
     * <p>The canvas component is identified structurally — a canvas/GL class name first, then the
     * largest showing leaf component inside the document window — and the pick is recorded so a
     * run that hit the wrong surface is visible in the evidence rather than silently ambiguous.
     * A press that misses the model starts a marquee selection, which is insignificant and leaves
     * the step open, so the actor works down a small grid of press points and samples the undo
     * manager after each drag: the first press that lands on the object produces the significant
     * entry being reviewed and the retries stop.</p>
     */
    private String dragCanvas(final String knownSignificant) throws Exception {
        final java.awt.Component canvas = onEdt(WindowsHistoryNativeUiIngressProbe::canvasComponent);
        if (canvas == null) return "unresolved:no-canvas-component";
        final java.awt.Rectangle bounds = canvas.getBounds();
        final int dx = Math.min(60, Math.max(10, bounds.width / 8));
        final int dy = Math.min(40, Math.max(10, bounds.height / 8));
        // Centre first — the model sits centered on load — then a small cross of nearby points.
        final int[][] fractions = {
            {1, 2, 1, 2}, {1, 3, 1, 2}, {2, 3, 1, 2}, {1, 2, 1, 3}, {1, 2, 2, 3}
        };
        for (int attempt = 0; attempt < fractions.length; attempt++) {
            final java.awt.Point press = new java.awt.Point(
                Math.max(1, bounds.width * fractions[attempt][0] / fractions[attempt][1]),
                Math.max(1, bounds.height * fractions[attempt][2] / fractions[attempt][3]));
            SwingUtilities.convertPointToScreen(press, canvas);
            robotDrag(press.x, press.y, press.x + dx, press.y + dy);
            // The host commits the undo entry after the release; give it a short bounded settle
            // before deciding the press missed the model.
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "dragged:" + canvas.getClass().getName() + bounds
                        + ":attempt=" + (attempt + 1);
                }
            }
        }
        return "dragged:" + canvas.getClass().getName() + bounds + ":no-significant-entry";
    }

    /**
     * Sends one native Ctrl+key shortcut exactly as the operator would: the enabled menu
     * accelerator first, then a focused-window Robot keystroke when no menu item claims it.
     */
    private String shortcut(final int key) throws Exception {
        if (Boolean.TRUE.equals(onEdt(() -> menuShortcut(key)))) return "menu-accelerator";
        final java.awt.Robot robot = new java.awt.Robot();
        final java.awt.Frame frame = onEdt(WindowsHistoryNativeUiIngressProbe::hostFrame);
        if (frame != null) {
            final java.awt.Rectangle bounds = frame.getBounds();
            robot.mouseMove(bounds.x + Math.max(20, bounds.width / 2), bounds.y + 12);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        }
        Thread.sleep(300L);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        return "robot";
    }

    /** Clicks the enabled menu item carrying a Ctrl+key accelerator, when one exists. */
    private static boolean menuShortcut(final int key) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenuItem match = findMenuShortcut(bar.getMenu(index), key);
                if (match != null && match.isEnabled()) {
                    match.doClick(0);
                    return true;
                }
            }
        }
        return false;
    }

    private static javax.swing.JMenuItem findMenuShortcut(
        final javax.swing.JMenuItem item,
        final int key
    ) {
        if (item == null) return null;
        final javax.swing.KeyStroke accelerator = item.getAccelerator();
        if (accelerator != null && accelerator.getKeyCode() == key
            && (accelerator.getModifiers() & java.awt.event.InputEvent.CTRL_DOWN_MASK) != 0) {
            return item;
        }
        if (item instanceof javax.swing.JMenu menu) {
            for (java.awt.Component component : menu.getMenuComponents()) {
                if (component instanceof javax.swing.JMenuItem child) {
                    final javax.swing.JMenuItem found = findMenuShortcut(child, key);
                    if (found != null) return found;
                }
            }
        }
        return null;
    }

    /** One Robot drag between two screen points, interpolated so the host sees real motion. */
    private static void robotDrag(
        final int fromX,
        final int fromY,
        final int toX,
        final int toY
    ) throws Exception {
        final java.awt.Robot robot = new java.awt.Robot();
        robot.mouseMove(fromX, fromY);
        Thread.sleep(120L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(180L);
        final int segments = 12;
        for (int segment = 1; segment <= segments; segment++) {
            robot.mouseMove(
                fromX + (toX - fromX) * segment / segments,
                fromY + (toY - fromY) * segment / segments
            );
            Thread.sleep(35L);
        }
        Thread.sleep(180L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
    }

    /** The visible Cubism document window, preferring the one whose title names the model. */
    private static java.awt.Frame hostFrame() {
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
     * The component most likely to be the model canvas.
     *
     * <p>A class whose name contains a canvas/GL marker wins; otherwise the largest showing leaf
     * component of the document window. Nothing is picked outside the document window, so a drag
     * can never land on a palette or a dialog.</p>
     */
    private static java.awt.Component canvasComponent() {
        final java.awt.Frame frame = hostFrame();
        if (frame == null) return null;
        final java.util.concurrent.atomic.AtomicReference<java.awt.Component> named =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<java.awt.Component> largest =
            new java.util.concurrent.atomic.AtomicReference<>();
        collectCanvas(frame, named, largest, 0);
        return named.get() != null ? named.get() : largest.get();
    }

    private static void collectCanvas(
        final java.awt.Component component,
        final java.util.concurrent.atomic.AtomicReference<java.awt.Component> named,
        final java.util.concurrent.atomic.AtomicReference<java.awt.Component> largest,
        final int depth
    ) {
        if (depth > UI_MAP_MAX_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        // Only a host/JOGL class may win by name: a Swing widget whose name happens to contain a
        // canvas/GL marker (JToggleButton carries "gl", JViewport carries "View") would be
        // dragged like the canvas and could toggle a mode instead of moving the model. A plain
        // java.awt.Canvas stays eligible because a heavyweight GL surface is exactly that class.
        final boolean hostClass = component instanceof java.awt.Canvas
            || (!className.startsWith("javax.swing.")
                && !className.startsWith("java.awt.")
                && !className.startsWith("sun."));
        if (component.isShowing() && named.get() == null && hostClass
            && className.matches(".*[cC]anvas.*|.*[gG][lL].*|.*[vV]iew.*")) {
            named.set(component);
        }
        final boolean leaf = !(component instanceof java.awt.Container container)
            || container.getComponentCount() == 0;
        // The largest-leaf fallback still has to look like a canvas: the r9 run found nothing by
        // name and the fallback picked the window's title bar — a real component, but a drag
        // there moves the window, not the model. Below a minimum area the pick is too small to
        // be a model view and the step stays unresolved instead.
        if (component.isShowing() && leaf
            && component.getWidth() >= MIN_CANVAS_EDGE && component.getHeight() >= MIN_CANVAS_EDGE) {
            final java.awt.Component current = largest.get();
            if (current == null
                || component.getWidth() * (long) component.getHeight()
                    > current.getWidth() * (long) current.getHeight()) {
                largest.set(component);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectCanvas(child, named, largest, depth + 1);
            }
        }
    }

    /**
     * A bounded dump of the visible component tree, written once per automated run.
     *
     * <p>The map exists for actor review: it records which windows, trees, tables, buttons and
     * sliders were actually present, so a future step can be targeted at a real control instead
     * of a guessed one. Class names and bounds only — no text longer than the existing label
     * bound, and nothing a label-only evidence rule would not already carry.</p>
     */
    private String uiMap() throws Exception {
        return onEdt(() -> {
            final StringBuilder out = new StringBuilder(UI_MAP_MAX_CHARS);
            final int[] seen = {0};
            int windows = 0;
            for (java.awt.Window window : java.awt.Window.getWindows()) {
                if (!window.isVisible()) continue;
                if (windows >= UI_MAP_MAX_WINDOWS || seen[0] >= UI_MAP_MAX_COMPONENTS) break;
                windows++;
                out.append("W:").append(window.getClass().getSimpleName())
                    .append('(')
                    .append(WindowsHistoryManagerValidationProbe.boundedLabel(window.getName()))
                    .append(')')
                    .append(boundsOf(window)).append('\n');
                mapComponent(window, 0, out, seen);
            }
            return out.toString();
        });
    }

    private static void mapComponent(
        final java.awt.Component component,
        final int depth,
        final StringBuilder out,
        final int[] seen
    ) {
        if (seen[0] >= UI_MAP_MAX_COMPONENTS || depth > UI_MAP_MAX_DEPTH
            || out.length() >= UI_MAP_MAX_CHARS - 256) {
            return;
        }
        seen[0]++;
        out.append("  ".repeat(Math.min(depth, 16)))
            .append(component.getClass().getName())
            .append(boundsOf(component));
        if (!component.isShowing()) out.append(":hidden");
        if (component instanceof javax.swing.JTree tree) {
            out.append(":rows=").append(tree.getRowCount());
        } else if (component instanceof javax.swing.JTable table) {
            out.append(":rows=").append(table.getRowCount())
                .append("x").append(table.getColumnCount());
        } else if (component instanceof javax.swing.AbstractButton button) {
            out.append(":text=")
                .append(WindowsHistoryManagerValidationProbe.boundedLabel(button.getText()));
        } else if (component instanceof javax.swing.JSlider slider) {
            out.append(":range=").append(slider.getMinimum())
                .append('-').append(slider.getMaximum())
                .append('=').append(slider.getValue());
        } else if (component instanceof javax.swing.JComboBox<?> combo) {
            out.append(":items=").append(combo.getItemCount());
        }
        out.append('\n');
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                mapComponent(child, depth + 1, out, seen);
            }
        }
    }

    private static String boundsOf(final java.awt.Component component) {
        try {
            final java.awt.Point origin = component.isShowing()
                ? component.getLocationOnScreen()
                : new java.awt.Point(0, 0);
            return "[" + origin.x + ',' + origin.y + ','
                + component.getWidth() + 'x' + component.getHeight() + ']';
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return "[hidden]";
        }
    }

    /**
     * Runs one callable on the host event thread with a bound.
     *
     * <p>Same discipline as {@link #sample()}: the wait has to end even when the host thread has
     * stopped pumping, because actor discovery must never park the worker past the step
     * deadline.</p>
     */
    private static <T> T onEdt(final java.util.concurrent.Callable<T> call) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return call.call();
        final AtomicReference<T> result = new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch completed = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(call.call());
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                completed.countDown();
            }
        });
        if (!completed.await(SAMPLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            throw new IllegalStateException("Cubism EDT did not accept the action in time");
        }
        if (failure.get() != null) throw failure.get();
        return result.get();
    }

    private WindowsHistoryManagerValidationProbe.Snapshot sample() throws Exception {
        final AtomicReference<WindowsHistoryManagerValidationProbe.Snapshot> result =
            new AtomicReference<>();
        final AtomicReference<Exception> failure = new AtomicReference<>();
        final CountDownLatch sampled = new CountDownLatch(1);
        // invokeLater rather than invokeAndWait: the wait has to be bounded, because an Editor
        // whose event thread has stopped pumping would otherwise park this thread for the life of
        // the process and the run would end with no verdict, no failure list and no summary.
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(WindowsHistoryManagerValidationProbe.sample(context));
            } catch (Exception exception) {
                failure.set(exception);
            } finally {
                sampled.countDown();
            }
        });
        if (!sampled.await(SAMPLE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            context.logger().warn(
                "Native UI ingress probe sampling timed out after " + SAMPLE_TIMEOUT_MILLIS + "ms"
            );
            return null;
        }
        if (failure.get() != null) return null;
        return result.get();
    }

    /**
     * Checks one step's events against what that step is supposed to be.
     *
     * <p>An action the operator performs must be observed as a host edit and must be announced by
     * the entry hook first. A native Undo or Redo is not an edit entry at all, so it must produce
     * no {@code before} event and must be attributed as navigation.</p>
     */
    static Verdict checkStep(final Step step, final List<Observed> events) {
        final long before = events.stream().filter(event -> event.phase().equals("before")).count();
        final long confirmed = events.stream()
            .filter(event -> event.phase().equals("on") || event.phase().equals("after"))
            .count();
        final String seen = "before=" + before + ",confirmed=" + confirmed
            + ",events=" + events.size();
        if (step.kind().equals("ACTION")) {
            if (before == 0) return new Verdict(false, "no-before-event", seen);
            if (confirmed == 0) return new Verdict(false, "no-confirmed-event", seen);
            return new Verdict(true, "action-announced-before-it-was-observed", seen);
        }
        if (before != 0) return new Verdict(false, "navigation-was-announced-as-an-edit", seen);
        final boolean attributed = events.stream().anyMatch(event -> event.origin().equals(step.kind()));
        if (!attributed) return new Verdict(false, "navigation-not-attributed", seen);
        return new Verdict(true, "navigation-attributed-without-an-edit", seen);
    }

    /**
     * The native change signal: how many <em>significant</em> entries the undo manager holds.
     *
     * <p>Entry count rather than position, because an edit made after an Undo replaces the
     * redoable tail and can leave the position unchanged.</p>
     *
     * <p>Significant entries rather than all entries, because the host records a selection as an
     * ordinary undo entry. Counting those would close a step on the click that precedes the
     * operator's real action, which is what happened on the previous run.</p>
     */
    static String significantSequence(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        if (snapshot == null) return "";
        return significantSequence(snapshot.current().entries());
    }

    /** Package-private so a focused test can pin the boundary rule without a live host. */
    static String significantSequence(final List<WindowsHistoryManagerValidationProbe.Entry> entries) {
        final StringBuilder sequence = new StringBuilder();
        for (WindowsHistoryManagerValidationProbe.Entry entry : entries) {
            if (!entry.significant()) continue;
            if (sequence.length() > 0) sequence.append('|');
            sequence.append(entry.index()).append(':').append(entry.label());
        }
        return sequence.toString();
    }

    /**
     * Whether the operator's step has produced what that step is supposed to produce.
     *
     * <p>An ACTION step is closed by the <em>significant</em> entries changing, never by the undo
     * position moving: the host records a selection as an ordinary entry, so closing on the entry
     * count or the position lets the click that precedes an action satisfy the step, and the real
     * action then lands in the next step's window. Undo and Redo are the opposite case — they
     * change the position and add no entry at all — so those steps are closed by the position.</p>
     */
    static boolean hasMoved(
        final Step step,
        final String currentSignificant,
        final long currentPosition,
        final String knownSignificant,
        final long knownPosition
    ) {
        if (currentSignificant == null) return false;
        if (step.kind().equals("ACTION")) {
            return !currentSignificant.equals(knownSignificant);
        }
        // Navigation is only closed by a move in its own direction, measured against the last
        // observed position. A selection also moves the position forwards, so accepting any change
        // would let the click before the operator's real action close an Undo step; and comparing
        // against the window's opening position would miss an undo that follows an edit made
        // inside the same window.
        if (step.kind().equals("UNDO")) return currentPosition < knownPosition;
        if (step.kind().equals("REDO")) return currentPosition > knownPosition;
        return false;
    }

    /** How long this step settles after its signal appears. */
    static long settleMillis(final Step step) {
        return step.kind().equals("ACTION")
            ? ACTION_SETTLE_MILLIS
            : NAVIGATION_SETTLE_MILLIS;
    }

    private static long position(final WindowsHistoryManagerValidationProbe.Snapshot snapshot) {
        return snapshot == null ? -1L : snapshot.current().position();
    }

    private int cursor() {
        synchronized (lock) {
            return observed.size();
        }
    }

    private String paired(
        final WindowsHistoryManagerValidationProbe.Snapshot snapshot,
        final String phase
    ) {
        if (snapshot == null) {
            return "{\"type\":\"paired-snapshot\",\"phase\":\"" + json(phase)
                + "\",\"unavailable\":true}\n";
        }
        return snapshot.pairedJson(phase) + "\n";
    }

    private void write(final Path artifact, final String value, final boolean terminal) throws Exception {
        final byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        final long existing = Files.exists(artifact) ? Files.size(artifact) : 0L;
        final long reserve = terminal ? 0L : TERMINAL_RESERVE_BYTES;
        if (bytes.length > MAX_EVIDENCE_BYTES
            || existing + bytes.length > MAX_EVIDENCE_BYTES - reserve) {
            throw new IllegalStateException("Native UI ingress evidence budget exhausted");
        }
        Files.writeString(
            artifact,
            value,
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.APPEND
        );
    }

    /**
     * {@return the terminal result line the host runner matches}
     *
     * <p>The runner compares one whole line, so nothing else may share it.</p>
     */
    static String summaryLine(final boolean passed) {
        return passed
            ? "{\"type\":\"summary\",\"status\":\"PASS\"}\n"
            : "{\"type\":\"summary\",\"status\":\"FAIL\"}\n";
    }

    private static String quoted(final String value) {
        return "\"" + json(value) + "\"";
    }

    static String json(final String value) {
        return json(value, 512);
    }

    /**
     * Escapes a string for the JSONL artifact, bounded to {@code maxCodePoints} code points.
     *
     * <p>The default bound sizes a label; the ui-map carries the larger component-tree bound
     * instead, since clipping it at a label's size made the first automated run's discovery
     * evidence unusable.</p>
     */
    static String json(final String value, final int maxCodePoints) {
        if (value == null) return "";
        final StringBuilder escaped = new StringBuilder();
        value.codePoints().limit(maxCodePoints).forEach(codePoint -> {
            switch (codePoint) {
                case '\\' -> escaped.append("\\\\");
                case '"' -> escaped.append("\\\"");
                case '\n' -> escaped.append("\\n");
                case '\r' -> escaped.append("\\r");
                case '\t' -> escaped.append("\\t");
                default -> {
                    if (codePoint < 0x20) escaped.append(String.format("\\u%04x", codePoint));
                    else escaped.appendCodePoint(codePoint);
                }
            }
        });
        return escaped.toString();
    }

    /** One operator instruction and the kind of native outcome it must produce. */
    record Step(String id, String kind, String instruction) {
    }

    /** One semantic event as the probe saw it. */
    record Observed(
        String phase,
        long sequence,
        String operation,
        String origin,
        String subjectId,
        String label,
        String thread,
        String observedAt
    ) {
        String json(final String step) {
            return "{\"type\":\"semantic-event\",\"step\":\"" + WindowsHistoryNativeUiIngressProbe.json(step)
                + "\",\"phase\":\"" + WindowsHistoryNativeUiIngressProbe.json(phase)
                + "\",\"sequence\":" + sequence
                + ",\"operation\":\"" + WindowsHistoryNativeUiIngressProbe.json(operation)
                + "\",\"origin\":\"" + WindowsHistoryNativeUiIngressProbe.json(origin)
                + "\",\"subjectId\":\"" + WindowsHistoryNativeUiIngressProbe.json(subjectId)
                + "\",\"label\":\"" + WindowsHistoryNativeUiIngressProbe.json(label)
                + "\",\"thread\":\"" + WindowsHistoryNativeUiIngressProbe.json(thread)
                + "\",\"observedAt\":\"" + WindowsHistoryNativeUiIngressProbe.json(observedAt) + "\"}\n";
        }
    }

    /** One step's verdict. */
    record Verdict(boolean ok, String code, String detail) {
        String json(final String step) {
            return "{\"type\":\"check\",\"check\":\""
                + WindowsHistoryNativeUiIngressProbe.json(step + "-" + code) + "\",\"ok\":" + ok
                + ",\"detail\":\"" + WindowsHistoryNativeUiIngressProbe.json(detail) + "\"}\n";
        }
    }
}
