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
import java.util.ArrayDeque;
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

    /** Bound on how many candidate surfaces the canvas actor will drag in one step. */
    private static final int MAX_CANVAS_CANDIDATES = 6;

    /**
     * Deepest level the canvas scan may reach.
     *
     * <p>The r19 run showed the model view's actual GL surface nested deeper than the ui-map's
     * dump bound: every ancestor leaf check failed because the visible leaf rule stopped before
     * the surface, while the surface's own parent chain stayed invisible to a depth-12 walk. The
     * discovery walk gets a deeper bound than the evidence dump; press-point validation below is
     * what keeps a container press from landing on a control.</p>
     */
    private static final int CANVAS_SCAN_DEPTH = UI_MAP_MAX_DEPTH * 2;

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
     * <p>Generous on purpose in manual mode: the window opens when the instruction is written,
     * and a human has to read it and walk the mouse to the right part of the Editor before
     * anything happens. Automated runs share the host display with other work, so they default
     * to a much shorter residual window — the actors already verify each attempt against the
     * undo manager themselves, and the window only exists to catch a late commit or an operator
     * who happens to be present. {@code -Dturboism.history.nativeUi.stepTimeoutMillis} overrides
     * either default.</p>
     */
    private static final long STEP_TIMEOUT_MILLIS = Long.parseLong(
        System.getProperty(
            "turboism.history.nativeUi.stepTimeoutMillis",
            AUTOMATE ? "60000" : "420000"));

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
        final Thread raiser = hostWindowRaiser();
        try {
            return switch (step.id()) {
                case "parts-tree-drag", "deformer-assign" -> dragPartRow(knownSignificant);
                case "canvas-move", "canvas-deform" -> dragCanvas(knownSignificant);
                case "native-parameter" -> dragParameterSlider(knownSignificant);
                case "native-color" -> editColorField(knownSignificant);
                case "native-undo" -> shortcut(java.awt.event.KeyEvent.VK_Z, knownSignificant);
                case "native-redo" -> shortcut(java.awt.event.KeyEvent.VK_Y, knownSignificant);
                default -> "none";
            };
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return "interrupted";
        } catch (Exception failure) {
            final String message = failure.getMessage();
            return "failed:" + failure.getClass().getSimpleName()
                + (message == null ? "" : ":" + message);
        } finally {
            raiser.interrupt();
        }
    }

    /**
     * Starts a daemon that keeps the host frame in front for the actor's whole run: the
     * launcher's own {@code cmd.exe} console shares the display at the same geometry and
     * can re-cover the editor mid-step, sending real Robot input to the console instead.
     */
    private Thread hostWindowRaiser() {
        final Thread thread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    try {
                        raiseHostWindow();
                    } catch (InterruptedException interrupted) {
                        throw interrupted;
                    } catch (Throwable ignored) {
                        // A missed raise only costs the next interval.
                    }
                    Thread.sleep(2000L);
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "native-ui-window-raiser");
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /**
     * Raises the host frame before a Robot gesture: the launcher's own {@code cmd.exe}
     * console window shares the display at the same geometry and can sit on top of the
     * editor, in which case real input lands on the console instead of the canvas.
     */
    private void raiseHostWindow() throws Exception {
        onEdt(() -> {
            java.awt.Window best = null;
            for (final java.awt.Window window : java.awt.Window.getWindows()) {
                if (window instanceof java.awt.Frame && window.isVisible()
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

    /**
     * Drags one Part row onto another in the Parts palette.
     *
     * <p>Reuses the mesh-edit probe's exact Parts-tree discovery: it finds the tree by its
     * listener/owner classes rather than a title, expands and scrolls the named rows into view,
     * and returns their owner-table cell centers in screen coordinates. The drag itself is real
     * input — Robot press, interpolated move, release — so the host's own drop handling decides
     * whether a hierarchy entry is committed.</p>
     */
    private String dragPartRow(final String knownSignificant) throws Exception {
        final List<String> names = onEdt(() ->
            context.cubism().model().active().parts().all().stream()
                .map(dev.turboism.sdk.cubism.model.Part::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        if (names.size() < 2) return "unresolved:fewer-than-two-parts";
        // Press the rendered row directly and never pre-select it first: a press on the
        // already-selected row can begin label editing instead of a drag. The row point comes
        // from the Parts tree-table's widest column — the name column — because the first
        // column's cell centre can land on a checkbox. The SDK lists every part including the
        // model's root container, which never owns a row, so unresolved names are skipped.
        String source = null;
        int[] fromPoint = null;
        for (final String name : names) {
            final int[] point = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (point != null) {
                source = name;
                fromPoint = point;
                break;
            }
        }
        if (fromPoint == null) {
            return "unresolved:no-selectable-part-row:" + names;
        }
        final int fromX = fromPoint[0];
        final int fromY = fromPoint[1];
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : names) {
            if (name.equals(source) || tried.size() >= 3) continue;
            final int[] toPoint = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (toPoint == null) continue;
            tried.add(name);
            final int toX = toPoint[0];
            final int toY = toPoint[1];
            final int height = toPoint[2];
            final int edgeY = toY + Math.max(1, height / 2) - 1;
            final int[][] drops = {{toX, toY}, {toX, edgeY}};
            for (int drop = 0; drop < drops.length; drop++) {
                robotDrag(fromX, fromY, drops[drop][0], drops[drop][1]);
                for (int settle = 0; settle < 12; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "dragged:" + source + "->" + name
                            + ":drop=" + (drop == 0 ? "on-row" : "row-edge")
                            + ":targets=" + tried.size();
                    }
                }
            }
        }
        if (tried.isEmpty()) {
            return "unresolved:no-target-row:" + names;
        }
        return "dragged:" + source + "->tried=" + tried + ":no-significant-entry:"
            + onEdt(WindowsHistoryNativeUiIngressProbe::partsTreeDnD);
    }

    /**
     * The Parts tree-table row's centre in screen coordinates on its widest column.
     *
     * <p>Runs on the EDT. The embedded {@code JTree} is a renderer component — never showing —
     * so the row index comes from its model and the press point from the owner table's cell
     * rect; the widest column is the name column, the only place a row drag can start.</p>
     *
     * @return {@code [x, y, rowHeight]} in screen coordinates, or {@code null} when no Parts
     *     tree-table row renders {@code displayName}
     */
    private static int[] partsRowPoint(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            // Collapsed parents give the path no row at all — expand first.
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0 || row >= table.getRowCount()) continue;
            int column = 0;
            int widest = -1;
            for (int index = 0; index < table.getColumnCount(); index++) {
                final int width = table.getColumnModel().getColumn(index).getWidth();
                if (width > widest) {
                    widest = width;
                    column = index;
                }
            }
            final java.awt.Rectangle rect = table.getCellRect(row, column, true);
            if (rect.isEmpty()) continue;
            table.scrollRectToVisible(rect);
            final java.awt.Point centre = new java.awt.Point(
                rect.x + Math.max(1, rect.width / 2),
                rect.y + Math.max(1, rect.height / 2));
            SwingUtilities.convertPointToScreen(centre, table);
            return new int[] {centre.x, centre.y, rect.height};
        }
        return null;
    }

    private static javax.swing.JTable findPartsTable(final java.awt.Component component) {
        if (component instanceof javax.swing.JTable table
            && table.getClass().getName().contains("PartsTreeTable")) {
            return table;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JTable found = findPartsTable(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * Reports whether the Parts tree supports Swing drag-and-drop at all.
     *
     * <p>Only consulted when both drops missed: if the host's tree has no drag gesture or no
     * drop handler, no amount of pointer accuracy will produce an entry and the step needs a
     * different interaction. The report is bounded to the trees that look like the Parts
     * palette.</p>
     */
    private static String partsTreeDnD() {
        final StringBuilder out = new StringBuilder(2048);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectTreeDnD(window, out, 0);
        }
        return out.length() > 0 ? out.toString() : "no-trees";
    }

    private static void collectTreeDnD(
        final java.awt.Component component,
        final StringBuilder out,
        final int depth
    ) {
        if (depth > UI_MAP_MAX_DEPTH || !component.isVisible() || out.length() > 1800) return;
        if (component instanceof javax.swing.JTree tree) {
            final java.awt.Container owner = SwingUtilities.getAncestorOfClass(
                javax.swing.JTable.class, tree);
            out.append("tree:").append(tree.getClass().getName())
                .append(":drag=").append(tree.getDragEnabled())
                .append(":drop=").append(tree.getDropTarget() != null)
                .append(":handler=").append(tree.getTransferHandler() == null
                    ? "none" : tree.getTransferHandler().getClass().getSimpleName())
                .append(":owner=").append(owner == null ? "none" : owner.getClass().getName())
                .append(';');
        } else if (component instanceof javax.swing.JTable table
            && table.getClass().getName().toLowerCase(java.util.Locale.ROOT)
                .contains("treetable")) {
            // A TreeTable embeds its JTree as a cell renderer, so the drag gesture and the
            // transfer handler live on the table — checking only the tree would report a DnD
            // that can never fire.
            out.append("table:").append(table.getClass().getName())
                .append(":drag=").append(table.getDragEnabled())
                .append(":drop=").append(table.getDropTarget() != null)
                .append(":mode=").append(table.getDropMode())
                .append(":handler=").append(table.getTransferHandler() == null
                    ? "none" : table.getTransferHandler().getClass().getName())
                .append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectTreeDnD(child, out, depth + 1);
            }
        }
    }

    /**
     * Drags inside the model canvas the way the operator does: left button held down, moved,
     * released.
     *
     * <p>The surface is identified structurally — a canvas/GL class name first, then the largest
     * showing leaf components across every visible window, because the r11 run showed the model
     * view lives in a {@code JDialog}, not the main frame — and every attempted pick is recorded
     * so a run that hit the wrong surface is visible in the evidence. A press that misses the
     * model starts a marquee selection, which is insignificant and leaves the step open, so the
     * actor works across a bounded set of surfaces and press points and samples the undo manager
     * after each drag: the first press that lands on the object produces the significant entry
     * being reviewed and the retries stop.</p>
     */
    private String dragCanvas(final String knownSignificant) throws Exception {
        // The r14 run proved the component tree can be mid-relayout when the actor runs — an
        // invisible ancestor prunes the whole subtree and discovery comes back empty while the
        // post-action map shows a perfectly good surface. Give the layout a few bounded chances
        // to settle before reporting the miss.
        List<java.awt.Component> candidates =
            onEdt(WindowsHistoryNativeUiIngressProbe::canvasCandidates);
        for (int retry = 0; candidates.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            candidates = onEdt(WindowsHistoryNativeUiIngressProbe::canvasCandidates);
        }
        if (candidates.isEmpty()) {
            // The r13 run returned empty here while the post-action map showed a showing leaf —
            // record what was actually rejected so the next run names the reason instead of
            // needing another guess.
            return "unresolved:no-canvas-component:" + onEdt(
                WindowsHistoryNativeUiIngressProbe::canvasRejects);
        }
        final ArrayList<String> tried = new ArrayList<>();
        // Centre of the visible region first — the model sits centered on load — then a second
        // point before moving to the next candidate surface.
        final int[][] fractions = {{1, 2, 1, 2}, {1, 3, 1, 2}, {2, 3, 1, 2}};
        final java.util.Set<java.awt.Point> pressed = new java.util.HashSet<>();
        for (final java.awt.Component canvas : candidates) {
            final java.awt.Rectangle visible = onEdt(() -> visibleBounds(canvas));
            if (visible.isEmpty()) continue;
            final int dx = Math.min(60, Math.max(10, visible.width / 8));
            final int dy = Math.min(40, Math.max(10, visible.height / 8));
            for (final int[] fraction : fractions) {
                final java.awt.Point local = new java.awt.Point(
                    visible.x + Math.max(1, visible.width * fraction[0] / fraction[1]),
                    visible.y + Math.max(1, visible.height * fraction[2] / fraction[3]));
                final java.awt.Point press = new java.awt.Point(local);
                SwingUtilities.convertPointToScreen(press, canvas);
                if (!pressed.add(press)) continue;
                final String hit = onEdt(() -> pressTarget(canvas, local));
                if (hit == null) continue;
                robotDrag(press.x, press.y, press.x + dx, press.y + dy);
                // The host commits the undo entry after the release; give it a short bounded
                // settle before deciding the press missed the model.
                for (int settle = 0; settle < 12; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "dragged:" + canvas.getClass().getName() + visible
                            + ":hit=" + hit + ":attempt=" + (tried.size() + 1);
                    }
                }
                tried.add(hit + "@" + press.x + "," + press.y);
            }
        }
        return "dragged:" + tried.size() + "-points:no-significant-entry:" + String.join("|", tried);
    }

    /**
     * Drags one Parameter-palette slider thumb the way the operator does: left button held on the
     * thumb, moved along the track, released.
     *
     * <p>Every showing horizontal slider in any visible window is a candidate — the Parameter
     * palette's sliders are the common case, and a slider that turns out to drive something that
     * commits no undo entry (a view zoom, for instance) simply produces no significant entry and
     * the actor moves to the next candidate.</p>
     */
    private String dragParameterSlider(final String knownSignificant) throws Exception {
        // The Parameter palette's value rows are CSlider widgets whose Swing mirrors are
        // com.live2d.ui.swingImpl.A (a JSlider subclass). They only exist inside the
        // parameter tab's dock column — the status-bar zoom slider is also an A, so the
        // palette rows must be scoped to that column. A row whose drag changes the value
        // commits a significant undo entry.
        String paletteNote = "";
        List<javax.swing.JSlider> dockSliders = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterDockSliders);
        if (dockSliders.isEmpty()) {
            paletteNote = "palette=" + openPalette(
                PARAMETER_MARKERS,
                () -> !parameterDockSliders().isEmpty());
            for (int retry = 0; dockSliders.isEmpty() && retry < 12; retry++) {
                Thread.sleep(POLL_MILLIS);
                dockSliders = onEdt(
                    WindowsHistoryNativeUiIngressProbe::parameterDockSliders);
            }
            if (dockSliders.isEmpty()) {
                paletteNote += ":scope=" + onEdt(
                    WindowsHistoryNativeUiIngressProbe::paletteClassCensus);
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.JSlider slider : dockSliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "dragged:param-row:" + slider.getClass().getName()
                        + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add("dock:" + slider.getClass().getSimpleName()
                + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        // Palettes whose rows live only in the CWidget tree expose no Swing sliders —
        // their CSlider widgets still carry on-screen rects a Robot drag can ride.
        if (tried.size() < 6) {
            final List<java.awt.Component> cwSliders = onEdt(
                WindowsHistoryNativeUiIngressProbe::parameterCWidgetSliders);
            for (final java.awt.Component surface : cwSliders) {
                final int[] at = onEdt(() -> fieldCentre(surface));
                if (at == null) continue;
                robotDrag(at[0] - 8, at[1], at[0] + 24, at[1]);
                for (int settle = 0; settle < 12; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "dragged:cslider:attempt=" + (tried.size() + 1);
                    }
                }
                tried.add("cw@" + at[0] + "," + at[1]);
                if (tried.size() >= 6) break;
            }
        }
        List<java.awt.Component> rows = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterRows);
        for (final java.awt.Component row : rows) {
            final int[] at = onEdt(() -> fieldCentre(row));
            if (at == null) continue;
            robotDrag(at[0] - 8, at[1], at[0] + 24, at[1]);
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "dragged:param-row:" + row.getClass().getName()
                        + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add(row.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 6) break;
        }
        List<javax.swing.JSlider> sliders = onEdt(
            WindowsHistoryNativeUiIngressProbe::parameterSliders);
        if (sliders.isEmpty() && rows.isEmpty()) return "unresolved:no-slider-or-param-row:"
            + paletteNote + ":"
            + onEdt(WindowsHistoryNativeUiIngressProbe::canvasRejects);
        for (final javax.swing.JSlider slider : sliders) {
            final int[] plan = onEdt(() -> sliderDragPlan(slider));
            if (plan == null) continue;
            robotDrag(plan[0], plan[1], plan[2], plan[3]);
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "dragged:" + slider.getClass().getName() + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add(slider.getClass().getSimpleName() + "@" + plan[0] + "," + plan[1]);
            if (tried.size() >= 6) break;
        }
        return "dragged:" + tried.size() + "-sliders:no-significant-entry:" + String.join("|", tried)
            + (paletteNote.isEmpty() ? "" : ":" + paletteNote)
            + ":" + onEdt(WindowsHistoryNativeUiIngressProbe::parameterWidgetCensus);
    }

    /**
     * The CWidget census inside the selected parameter tab's dock column — every widget
     * class, rect and label the palette actually instantiated. Runs on the EDT.
     */
    private static String parameterWidgetCensus() {
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope == null) continue;
            Object root = cwidgetOf(scope);
            if (root == null) root = cwidgetTop(cwidgetOf(tab));
            final List<Object> widgets = cwidgetTree(root);
            return "pcw{n=" + widgets.size() + ":" + cwidgetCensus(widgets, 1400) + "}";
        }
        return "pcw{no-selected-tab}";
    }

    /**
     * Every showing enabled horizontal slider wide enough to grab.
     *
     * <p>Runs on the EDT. A slider narrower than 48px is a layout artefact, not a parameter row,
     * and a disabled slider cannot take the drag.</p>
     */
    private static List<javax.swing.JSlider> parameterSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectSliders(window, found, 0);
        }
        return found;
    }

    private static void collectSliders(
        final java.awt.Component component,
        final List<javax.swing.JSlider> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.JSlider slider
            && slider.isShowing() && slider.isEnabled()
            && slider.getOrientation() == javax.swing.JSlider.HORIZONTAL
            && slider.getWidth() >= 48) {
            found.add(slider);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectSliders(child, found, depth + 1);
            }
        }
    }

    /**
     * The showing Parameter-palette rows a drag could move.
     *
     * <p>The palette's value widgets are host classes under {@code palette.parameter}, not
     * {@code JSlider} — the r20 run only found the status-bar zoom slider, which changes the
     * view, not the model. A component living in the parameter palette package, wide enough to
     * be a value row, is a candidate; the Robot drags a few pixels along its horizontal centre
     * and the undo manager decides whether the gesture was a value change.</p>
     */
    private static List<java.awt.Component> parameterRows() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectParameterRows(window, found, 0);
        }
        return found;
    }

    private static void collectParameterRows(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        final String className = component.getClass().getName();
        if (component.isShowing() && className.contains(".palette.parameter")
            && !(component instanceof javax.swing.AbstractButton)
            && !(component instanceof javax.swing.text.JTextComponent)
            && component.getWidth() >= 48 && component.getWidth() <= 400
            && component.getHeight() >= 10 && component.getHeight() <= 48
            && !found.contains(component)) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectParameterRows(child, found, depth + 1);
            }
        }
    }

    /**
     * Opens a palette through the host's own Window menu and reports the result.
     *
     * <p>The fixture layout has palettes closed: the only slider on screen is the status-bar
     * zoom. The frame keeps a fully populated {@link javax.swing.JMenuBar} — the palette items
     * are checkbox mirrors whose programmatic {@code doClick} does not fire the host's toggle —
     * so the menu is opened through the {@link javax.swing.MenuSelectionManager} and the item
     * is clicked with a real Robot press, exactly as the operator does.</p>
     */
    private String openPalette(
        final String[] itemMarkers,
        final java.util.function.Supplier<Boolean> realized
    ) throws Exception {
        final StringBuilder diag = new StringBuilder(320);
        // Palettes dock as tabs — the ui-map shows a 参数 tab (com.live2d.ui.swingImpl.K) in
        // the right dock's tab bar. A real press on the tab realizes the palette.
        final java.awt.Robot robot = new java.awt.Robot();
        for (int attempt = 0; attempt < 6; attempt++) {
            // Several tabs can carry the same palette name in different dock groups —
            // cycle through them instead of pressing the first one four times.
            final List<javax.swing.AbstractButton> tabs =
                onEdt(() -> paletteTabs(itemMarkers, diag));
            if (tabs.isEmpty()) {
                diag.append("tab-miss;");
                break;
            }
            final javax.swing.AbstractButton tabButton =
                tabs.get(attempt % tabs.size());
            final int[] tab = onEdt(() -> fieldCentre(tabButton));
            if (tab == null) {
                diag.append("tab-not-showing;");
                break;
            }
            // K tabs are toggles — re-pressing a tab that is already selected switches
            // its palette back off. Only press it when it is not selected.
            if (!tabButton.isSelected()) {
                robot.mouseMove(tab[0], tab[1]);
                Thread.sleep(60L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80L);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            }
            for (int poll = 0; poll < 8; poll++) {
                Thread.sleep(POLL_MILLIS);
                if (Boolean.TRUE.equals(onEdt(realized::get))) {
                    return "tab-click:" + diag;
                }
            }
            diag.append("tab-no-realize:sel=").append(tabButton.isSelected()).append(';');
        }
        final javax.swing.JMenu menu = onEdt(() -> windowMenu(diag));
        if (menu == null) return "no-window-menu:" + diag;
        onEdt(() -> {
            if (menu.getParent() instanceof javax.swing.JMenuBar bar) {
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(
                    new javax.swing.MenuElement[] {bar, menu, menu.getPopupMenu()});
            } else {
                menu.setPopupMenuVisible(true);
            }
            return true;
        });
        Thread.sleep(600L);
        final javax.swing.JMenuItem jmi = onEdt(() -> popupItem(menu, itemMarkers, diag));
        if (jmi == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-no-palette-item:" + diag + "|" + onEdt(
                WindowsHistoryNativeUiIngressProbe::windowMenuTexts);
        }
        // The mirror maps back to its real CMenuItem through the host's component→widget
        // registry (com.live2d.ui.k.a). Clicking the CMenuItem fires the Kotlin action.
        final Object widget = widgetOf(jmi, diag);
        if (widget != null) {
            // The check item can be flagged open while its dock node was never built —
            // toggling alone does not realize the palette. Also try the menu's
            // "show panels" entry and its "reset state" entry, which rebuild the dock
            // layout, then re-toggle the palette item.
            final Object showPanels = onEdt(
                () -> menuWidget(menu, SHOW_PANELS_MARKERS, diag));
            final Object resetState = onEdt(
                () -> menuWidget(menu, RESET_STATE_MARKERS, diag));
            // The workspace submenu carries layout presets — switching layout rebuilds the
            // whole dock, palettes included. It is a JMenu inside the popup: open it and
            // take its first real item.
            final Object workspace = onEdt(() -> {
                final javax.swing.JMenuItem sub = popupItem(menu, WORKSPACE_MARKERS, diag);
                if (!(sub instanceof javax.swing.JMenu submenu)) return null;
                javax.swing.MenuSelectionManager.defaultManager().setSelectedPath(
                    new javax.swing.MenuElement[] {
                        menu.getParent() instanceof javax.swing.JMenuBar b ? b : menu,
                        menu, menu.getPopupMenu(), submenu, submenu.getPopupMenu()});
                diag.append("sub{");
                for (java.awt.Component component : submenu.getPopupMenu().getComponents()) {
                    if (component instanceof javax.swing.JMenu) continue;
                    if (!(component instanceof javax.swing.JMenuItem leaf)
                        || leaf.getText() == null || leaf.getText().isBlank()) continue;
                    diag.append(leaf.getText()).append(',');
                    // Layout presets only — skip save/manage entries that write host state.
                    if (containsAny(leaf.getText(), WORKSPACE_SKIP_MARKERS)) continue;
                    return widgetOf(leaf, diag);
                }
                diag.append('}');
                return null;
            });
            final StringBuilder note = new StringBuilder(
                "menu-doClick:" + widget.getClass().getSimpleName());
            final Object[] sequence =
                {widget, showPanels, widget, resetState, widget, workspace, widget};
            for (final Object target : sequence) {
                if (target == null) {
                    note.append(":skip-null");
                    continue;
                }
                onEdt(() -> {
                    target.getClass().getMethod("doClick").invoke(target);
                    return true;
                });
                for (int poll = 0; poll < 8; poll++) {
                    Thread.sleep(POLL_MILLIS);
                    if (Boolean.TRUE.equals(onEdt(realized::get))) {
                        onEdt(() -> {
                            javax.swing.MenuSelectionManager.defaultManager()
                                .clearSelectedPath();
                            return true;
                        });
                        return note.append(":opened").toString();
                    }
                }
                note.append(":sel=").append(jmi.isSelected());
            }
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return note.append(":not-realized:").append(diag).toString();
        }
        // Fallback: a real Robot press on the showing item.
        final int[] at = onEdt(() -> jmi.isShowing() ? fieldCentre(jmi) : null);
        if (at == null) {
            onEdt(() -> {
                javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
                return true;
            });
            return "menu-item-not-showing:" + diag;
        }
        robot.mouseMove(at[0], at[1]);
        Thread.sleep(80L);
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(80L);
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
        Thread.sleep(600L);
        return "menu-click:" + diag;
    }

    /** The Window-ish top menu of the host frame — the {@code s} JMenu carrying the palette
     * items. Prefers the frame JMenuBar, then scans the title-bar menubar in the layered
     * pane. Runs on the EDT. */
    private static javax.swing.JMenu windowMenu(final StringBuilder diag) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar != null) {
                diag.append("bar=").append(bar.getClass().getName())
                    .append(':').append(bar.getMenuCount()).append(';');
                for (int index = 0; index < bar.getMenuCount(); index++) {
                    final javax.swing.JMenu menu = bar.getMenu(index);
                    if (menu != null && menu.getText() != null
                        && containsAny(menu.getText(), MENU_WINDOW_MARKERS)) return menu;
                }
            } else {
                diag.append("no-bar;");
            }
            final javax.swing.JMenu inTree = windowMenuInTree(frame, 0);
            if (inTree != null) return inTree;
        }
        return null;
    }

    private static javax.swing.JMenu windowMenuInTree(
        final java.awt.Component component,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return null;
        if (component instanceof javax.swing.JMenu menu && menu.getText() != null
            && containsAny(menu.getText(), MENU_WINDOW_MARKERS)) return menu;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.JMenu found = windowMenuInTree(child, depth + 1);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * The screen centre of a dock tab button carrying a palette name — the host renders dock
     * tabs as small buttons (com.live2d.ui.swingImpl.K) in a 21px-high strip. Menu-bar titles
     * and popup items are excluded; a tab is small, showing, and below the toolbar band.
     * Runs on the EDT.
     */
    private static javax.swing.AbstractButton paletteTab(
        final String[] markers,
        final StringBuilder diag
    ) {
        javax.swing.AbstractButton best = null;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.AbstractButton hit = paletteTabIn(window, markers, 0, diag);
            if (hit != null) best = hit;
        }
        return best;
    }

    /** Every showing dock tab whose text carries a marker, across all windows. */
    private static List<javax.swing.AbstractButton> paletteTabs(
        final String[] markers,
        final StringBuilder diag
    ) {
        final List<javax.swing.AbstractButton> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            collectPaletteTabs(window, markers, found, 0, diag);
        }
        return found;
    }

    private static void collectPaletteTabs(
        final java.awt.Component component,
        final String[] markers,
        final List<javax.swing.AbstractButton> found,
        final int depth,
        final StringBuilder diag
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.AbstractButton button
            && !(component instanceof javax.swing.JMenuItem)
            && button.isShowing() && button.getText() != null
            && containsAny(button.getText(), markers)
            && button.getHeight() <= 30 && button.getWidth() <= 200) {
            final java.awt.Point p = component.getLocationOnScreen();
            if (p.y > 42 && !found.contains(button)) {
                diag.append("tab=").append(button.getClass().getSimpleName())
                    .append('[').append(button.getText()).append(']')
                    .append('@').append(p.x + button.getWidth() / 2)
                    .append(',').append(p.y + button.getHeight() / 2).append(';');
                found.add(button);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectPaletteTabs(child, markers, found, depth + 1, diag);
            }
        }
    }

    private static javax.swing.AbstractButton paletteTabIn(
        final java.awt.Component component,
        final String[] markers,
        final int depth,
        final StringBuilder diag
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return null;
        if (component instanceof javax.swing.AbstractButton button
            && !(component instanceof javax.swing.JMenuItem)
            && button.isShowing() && button.getText() != null
            && containsAny(button.getText(), markers)
            && button.getHeight() <= 30 && button.getWidth() <= 200) {
            final java.awt.Point p = component.getLocationOnScreen();
            // The menu strip lives at y<42; dock tabs sit below it.
            if (p.y > 42) {
                diag.append("tab=").append(button.getClass().getSimpleName())
                    .append('[').append(button.getText()).append(']')
                    .append('@').append(p.x + button.getWidth() / 2)
                    .append(',').append(p.y + button.getHeight() / 2).append(';');
                return button;
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                final javax.swing.AbstractButton hit =
                    paletteTabIn(child, markers, depth + 1, diag);
                if (hit != null) return hit;
            }
        }
        return null;
    }

    /** Whether a dock tab carrying one of {@code markers} is currently selected. */
    private static boolean paletteTabSelected(final String[] markers) {
        final StringBuilder ignored = new StringBuilder(1);
        final javax.swing.AbstractButton tab = paletteTab(markers, ignored);
        return tab != null && tab.isSelected();
    }

    /** The CWidget behind the popup item whose text matches a marker, or null. The menu must
     * already be open. Runs on the EDT. */
    private static Object menuWidget(
        final javax.swing.JMenu menu,
        final String[] markers,
        final StringBuilder diag
    ) {
        final javax.swing.JMenuItem jmi = popupItem(menu, markers, diag);
        return jmi == null ? null : widgetOf(jmi, diag);
    }

    /** The host CWidget registered for a mirror component, via {@code com.live2d.ui.k.a}. */
    private static Object widgetOf(
        final javax.swing.JMenuItem jmi,
        final StringBuilder diag
    ) {
        try {
            final Class<?> registry = Class.forName(
                "com.live2d.ui.k", true, jmi.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class).invoke(null, jmi);
        } catch (final Exception e) {
            diag.append("registry-threw:").append(e.getClass().getSimpleName()).append(';');
            return null;
        }
    }

    /** The popup item whose text matches a marker — the menu is already open. Runs on the
     * EDT. */
    private static javax.swing.JMenuItem popupItem(
        final javax.swing.JMenu menu,
        final String[] markers,
        final StringBuilder diag
    ) {
        final javax.swing.JPopupMenu popup = menu == null ? null : menu.getPopupMenu();
        if (popup == null) {
            diag.append("no-popup;");
            return null;
        }
        diag.append("items{");
        for (java.awt.Component component : popup.getComponents()) {
            if (!(component instanceof javax.swing.JMenuItem jmi)) continue;
            diag.append(jmi.getText() == null ? "?" : jmi.getText()).append(',');
            if (jmi.getText() != null && containsAny(jmi.getText(), markers)) {
                diag.append('}');
                return jmi;
            }
        }
        diag.append('}');
        return null;
    }

    /** The texts under the Window-ish top menu, for diagnostics when the palette item is absent. */
    private static String windowMenuTexts() {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            final StringBuilder out = new StringBuilder(512);
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu menu = bar.getMenu(index);
                if (menu == null || menu.getText() == null
                    || !containsAny(menu.getText(), MENU_WINDOW_MARKERS)) continue;
                for (java.awt.Component component : menu.getMenuComponents()) {
                    if (component instanceof javax.swing.JMenuItem child) {
                        out.append(child.getText() == null ? "?" : child.getText()).append(';');
                    }
                }
            }
            return out.length() > 0 ? out.toString() : "window-menu-empty";
        }
        return "no-menubar";
    }

    private static final String[] MENU_WINDOW_MARKERS = {"视窗", "ウィンドウ", "Window"};
    private static final String[] PARAMETER_MARKERS = {"参数", "パラメータ", "arameter"};
    private static final String[] INSPECTOR_MARKERS = {"检视", "检查", "インスペクタ", "nspector"};
    private static final String[] SHOW_PANELS_MARKERS =
        {"显示面板", "パネルを表示", "how panels", "anels"};
    private static final String[] RESET_STATE_MARKERS =
        {"重置状态", "状態をリセット", "eset state", "eset"};
    private static final String[] WORKSPACE_MARKERS =
        {"工作区", "ワークスペース", "orkspace"};
    private static final String[] WORKSPACE_SKIP_MARKERS =
        {"保存", "管理", "新建", "删除", "編集", "管理", "ave", "anage", "ew ", "elete"};

    /**
     * A bounded census of palette-package components per visible window: which windows exist,
     * and how many {@code palette.parameter} / {@code palette.inspector} components each one
     * holds — visible or not — so a palette that opened collapsed still shows up.
     */
    private static String paletteClassCensus() {
        final StringBuilder out = new StringBuilder(480);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final int[] counts = {0, 0};
            censusPaletteClasses(window, counts, 0);
            out.append(window.getClass().getSimpleName()).append('[')
                .append(window.getWidth()).append('x').append(window.getHeight()).append(']')
                .append(":param=").append(counts[0])
                .append(":insp=").append(counts[1]).append(';');
            if (out.length() > 440) break;
        }
        return out.toString();
    }

    private static void censusPaletteClasses(
        final java.awt.Component component,
        final int[] counts,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return;
        final String className = component.getClass().getName();
        if (className.contains(".palette.parameter")) counts[0]++;
        if (className.contains(".palette.inspector")) counts[1]++;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                censusPaletteClasses(child, counts, depth + 1);
            }
        }
    }

    private static boolean containsAny(final String text, final String[] markers) {
        for (final String marker : markers) {
            if (text.contains(marker)) return true;
        }
        return false;
    }

    /**
     * The screen line a thumb drag for {@code slider} should follow: press at the thumb, drag
     * right by an eighth of the track.
     *
     * @return {@code [fromX, fromY, toX, toY]} in screen coordinates, or {@code null} when the
     *     slider has no room to move
     */
    private static int[] sliderDragPlan(final javax.swing.JSlider slider) {
        if (!slider.isShowing()) return null;
        final int range = slider.getMaximum() - slider.getMinimum();
        if (range <= 0 || slider.getWidth() < 48) return null;
        final double fraction =
            (double) (slider.getValue() - slider.getMinimum()) / (double) range;
        // The track rarely spans the whole component; keep the press inside it.
        final int localX = Math.max(6, Math.min(
            slider.getWidth() - 6, (int) Math.round(fraction * slider.getWidth())));
        final int localY = slider.getHeight() / 2;
        final int delta = Math.max(6, slider.getWidth() / 8);
        if (localX + delta > slider.getWidth() - 2 && slider.getValue() == slider.getMaximum()) {
            return null;
        }
        final java.awt.Point from = new java.awt.Point(localX, localY);
        SwingUtilities.convertPointToScreen(from, slider);
        return new int[] {from.x, from.y, from.x + delta, from.y};
    }

    /**
     * Edits one colour text field the way the operator does: click, select all, type a different
     * hex colour, confirm with Enter.
     *
     * <p>The inspector's multiply/screen colour rows are text fields carrying {@code #RRGGBB};
     * any showing editable field whose text already matches that shape is a candidate, and a
     * field whose commit produces no undo entry is skipped for the next one. A field that rejects
     * the value — wrong palette, read-only, not a colour — leaves the step waiting for the
     * operator like every other missed actor.</p>
     */
    private String editColorField(final String knownSignificant) throws Exception {
        List<javax.swing.text.JTextComponent> fields = onEdt(
            WindowsHistoryNativeUiIngressProbe::colorFields);
        for (int retry = 0; fields.isEmpty() && retry < 4; retry++) {
            Thread.sleep(POLL_MILLIS);
            fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
        }
        final java.awt.Robot robot = new java.awt.Robot();
        int swatchCount = -1;
        final StringBuilder dialogDiag = new StringBuilder(160);
        final String[] cwidgetNote = {null};
        if (fields.isEmpty()) {
            // The inspector palette — where the multiply-colour field lives — may not be
            // open in the fixture layout; open it first so it can observe the selection.
            final String palette = openPalette(
                INSPECTOR_MARKERS,
                () -> inspectorShowing() || paletteTabSelected(INSPECTOR_MARKERS));
            // Then select a drawable — the inspector fills its property rows, colour
            // swatch included, when a selection event arrives while it is visible. A plain
            // click selects, it does not drag, so no undo entry is produced by this step.
            final String selection = selectDrawableRow(robot);
            for (int retry = 0; fields.isEmpty() && retry < 8; retry++) {
                Thread.sleep(POLL_MILLIS);
                fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
            }
            if (fields.isEmpty()
                && (inspectorShowing()
                    || onEdt(() -> paletteTabSelected(INSPECTOR_MARKERS)))) {
                // The inspector paints custom CWidgets onto one panel — Swing sees no
                // children there, but every mirror maps back through com.live2d.ui.k
                // and CWidget.traverse exposes the real controls: the colour row
                // holds a CColorChooserButton plus an ARGB CTextField whose
                // JTextComponent mirror accepts real Robot typing.
                final java.awt.Component contentPanel = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorContentPanel);
                final java.awt.Container dockScope = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorDockScope);
                final java.awt.Rectangle dockRect = onEdt(() -> {
                    if (dockScope == null || !dockScope.isShowing()) return null;
                    final java.awt.Point origin = dockScope.getLocationOnScreen();
                    return new java.awt.Rectangle(
                        origin.x, origin.y,
                        dockScope.getWidth(), dockScope.getHeight());
                });
                final List<javax.swing.text.JTextComponent> argb = new ArrayList<>();
                final List<javax.swing.text.JTextComponent> argbOther = new ArrayList<>();
                final List<java.awt.Component> chooser = new ArrayList<>();
                final List<java.awt.Component> chooserOther = new ArrayList<>();
                cwidgetNote[0] = onEdt(() -> {
                    Object root = cwidgetOf(contentPanel);
                    if (root == null) {
                        final javax.swing.AbstractButton tab = paletteTab(
                            INSPECTOR_MARKERS, new StringBuilder(1));
                        root = cwidgetTop(cwidgetOf(tab));
                    }
                    final List<Object> widgets = cwidgetTree(root);
                    for (final Object widget : widgets) {
                        if (dockRect != null) {
                            final java.awt.Rectangle rect = cwidgetRect(widget);
                            if (rect == null || !rect.intersects(dockRect)) {
                                continue;
                            }
                        }
                        final javax.swing.text.JTextComponent text =
                            cwidgetTextComponent(widget);
                        if (text != null) {
                            (multiplyMarked(widget) ? argb : argbOther).add(text);
                            continue;
                        }
                        if (widget.getClass().getName()
                            .endsWith("CColorChooserButton")) {
                            final java.awt.Component surface = cwidgetSurface(widget);
                            if (surface != null) {
                                (multiplyMarked(widget) ? chooser : chooserOther)
                                    .add(surface);
                            }
                        }
                    }
                    return "cw{root=" + (root == null ? "null"
                        : root.getClass().getSimpleName())
                        + ":n=" + widgets.size()
                        + ":" + cwidgetCensus(widgets) + "}";
                });
                fields.addAll(argb);
                fields.addAll(argbOther);
                // The swatch is a small coloured component inside the inspector palette;
                // pressing it opens the colour editor dialog where the hex field lives. A
                // swatch that opens some other dialog leaves it up — dismiss it with Escape
                // so it cannot swallow the next step's input.
                final List<java.awt.Component> swatches = new ArrayList<>(chooser);
                swatches.addAll(chooserOther);
                if (fields.isEmpty()) {
                    swatches.addAll(onEdt(
                        WindowsHistoryNativeUiIngressProbe::colorSwatches));
                }
                if (swatches.isEmpty()) {
                    // The inspector paints its property rows onto one component rather than
                    // hosting Swing children — the swatch is a painted square on a row. Scan
                    // the panel's actual pixels for saturated colour blocks and press those.
                    final java.awt.Component panel = onEdt(
                        WindowsHistoryNativeUiIngressProbe::inspectorContentPanel);
                    final int[] bounds = onEdt(() -> {
                        if (panel == null) return null;
                        final java.awt.Point p = panel.getLocationOnScreen();
                        return new int[] {p.x, p.y, panel.getWidth(), panel.getHeight()};
                    });
                    if (bounds != null) {
                        for (final int[] point : saturatedBlocks(robot, bounds)) {
                            final int fx = point[0];
                            final int fy = point[1];
                            swatches.add(new java.awt.Component() {
                                @Override public boolean isShowing() { return true; }
                                @Override public java.awt.Point getLocationOnScreen() {
                                    return new java.awt.Point(fx, fy);
                                }
                                @Override public int getWidth() { return 1; }
                                @Override public int getHeight() { return 1; }
                            });
                        }
                    }
                }
                swatchCount = swatches.size();
                int swatchAttempts = 0;
                for (final java.awt.Component swatch : swatches) {
                    if (swatchAttempts >= 14) break;
                    final int[] at = onEdt(() -> fieldCentre(swatch));
                    if (at == null) continue;
                    swatchAttempts++;
                    final java.util.Set<java.awt.Window> before =
                        onEdt(WindowsHistoryNativeUiIngressProbe::visibleWindows);
                    robot.mouseMove(at[0], at[1]);
                    robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                    java.awt.Window opened = null;
                    for (int poll = 0; poll < 8; poll++) {
                        Thread.sleep(POLL_MILLIS);
                        fields = onEdt(WindowsHistoryNativeUiIngressProbe::colorFields);
                        if (!fields.isEmpty()) break;
                        opened = onEdt(() -> newWindowSince(before));
                        if (opened != null) break;
                    }
                    if (!fields.isEmpty()) break;
                    if (opened != null) {
                        // A swatch press opened the colour editor — its value fields may be
                        // decimal RGB rather than hex. Edit one and let the dialog confirm.
                        dumpWindowScreenshot(robot, opened, "dialog");
                        final StringBuilder dialogNote = new StringBuilder(80);
                        final String edited = editDialogField(
                            robot, opened, knownSignificant, dialogNote);
                        if (edited != null) return "edited-dialog:" + edited;
                        dialogDiag.append("dialog:").append(opened.getClass().getSimpleName())
                            .append('{').append(dialogNote).append('}');
                        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE);
                        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE);
                        Thread.sleep(POLL_MILLIS);
                    }
                }
            }
            if (fields.isEmpty()) {
                final String dock = onEdt(
                    WindowsHistoryNativeUiIngressProbe::inspectorLeafCensus);
                dumpRegionScreenshot(robot, "inspector");
                return "unresolved:no-color-field:palette=" + palette
                    + ":sel=" + selection
                    + ":swatches=" + swatchCount
                    + ":" + dialogDiag
                    + ":" + cwidgetNote[0]
                    + ":dock{" + dock + "}";
            }
        }
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.text.JTextComponent field : fields) {
            final int[] at = onEdt(() -> fieldCentre(field));
            if (at == null) continue;
            final String previous = onEdt(field::getText);
            final String replacement = previous != null && previous.contains("33")
                ? "2244CC" : "CC4433";
            robot.mouseMove(at[0], at[1]);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(150L);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            Thread.sleep(80L);
            for (final char digit : replacement.toCharArray()) {
                typeChar(robot, digit);
            }
            robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            for (int settle = 0; settle < 12; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "edited:" + field.getClass().getName()
                        + ":attempt=" + (tried.size() + 1);
                }
            }
            tried.add(field.getClass().getSimpleName() + "@" + at[0] + "," + at[1]);
            if (tried.size() >= 4) break;
        }
        return "edited:" + tried.size() + "-fields:no-significant-entry:" + String.join("|", tried);
    }

    /** Every showing editable text component whose content already looks like a hex colour. */
    private static List<javax.swing.text.JTextComponent> colorFields() {
        final List<javax.swing.text.JTextComponent> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorFields(window, found, 0);
        }
        return found;
    }

    private static void collectColorFields(
        final java.awt.Component component,
        final List<javax.swing.text.JTextComponent> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
            && text.isShowing() && text.isEnabled() && text.isEditable()) {
            final String value = text.getText();
            if (value != null && value.trim().matches("#?[0-9a-fA-F]{6}")) {
                found.add(text);
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorFields(child, found, depth + 1);
            }
        }
    }

    /**
     * Presses the first ArtMesh row found in the Parts tree so the inspector palette shows
     * that drawable's property rows, colour included.
     */
    private String selectDrawableRow(final java.awt.Robot robot) throws Exception {
        final List<String> drawableNames = onEdt(() ->
            context.cubism().model().active().drawables().all().stream()
                .map(dev.turboism.sdk.cubism.model.Drawable::name)
                .filter(name -> name != null && !name.isBlank())
                .distinct()
                .toList());
        int rows = 0;
        final ArrayList<String> tried = new ArrayList<>();
        for (final String name : drawableNames) {
            if (tried.size() >= 6) break;
            final int[] point = onEdt(
                () -> WindowsHistoryNativeUiIngressProbe.partsRowPoint(name));
            if (point == null) continue;
            rows++;
            robot.mouseMove(point[0], point[1]);
            Thread.sleep(60L);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(80L);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(POLL_MILLIS);
            // The SDK runtime selection projection is an unimplemented stub, so verify
            // at the widget that owns the row: a press that selected it leaves the Parts
            // table's selection on that row.
            final int selectedRow = onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
            if (selectedRow >= 0) return "row:" + name + ":tableRow=" + selectedRow;
            tried.add(name);
        }
        // A row press that did not register as a host selection (mis-hit column, focus
        // quirk) still leaves the inspector empty. Drive the tree's own selection model
        // on the EDT — the Parts palette propagates tree selection to the Editor, which
        // is what fills the inspector. This commits no undo entry.
        for (final String name : drawableNames) {
            if (tried.size() >= 8) break;
            final boolean selected = onEdt(() -> selectPartsTreeRow(name));
            if (!selected) continue;
            Thread.sleep(POLL_MILLIS);
            final int selectedRow = onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
            if (selectedRow >= 0) {
                return "tree-select:" + name + ":tableRow=" + selectedRow;
            }
            tried.add("edt:" + name);
        }
        // Fallback: a single press on the model canvas selects the drawable under the
        // cursor — that selection is what populates the inspector's property rows. It
        // commits only a selection entry, never an edit.
        final int[] canvasCentre = onEdt(() -> {
            for (final java.awt.Component canvas : canvasCandidates()) {
                final java.awt.Rectangle visible = visibleBounds(canvas);
                if (visible.isEmpty()) continue;
                final java.awt.Point centre = new java.awt.Point(
                    visible.x + visible.width / 2,
                    visible.y + visible.height / 2);
                SwingUtilities.convertPointToScreen(centre, canvas);
                return new int[] {centre.x, centre.y};
            }
            return null;
        });
        if (canvasCentre != null) {
            // Try a small grid of canvas points — the model does not cover the whole view.
            final int[][] offsets = {{0, 0}, {-60, 0}, {60, 0}, {0, -60}, {0, 60}};
            for (final int[] offset : offsets) {
                robot.mouseMove(canvasCentre[0] + offset[0], canvasCentre[1] + offset[1]);
                Thread.sleep(60L);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(80L);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                Thread.sleep(POLL_MILLIS);
                final int selectedRow = onEdt(
                    WindowsHistoryNativeUiIngressProbe::partsTableSelectedRow);
                if (selectedRow >= 0) {
                    return "canvas@" + (canvasCentre[0] + offset[0]) + ","
                        + (canvasCentre[1] + offset[1]) + ":tableRow=" + selectedRow;
                }
            }
            return "canvas-miss:tried-rows=" + tried;
        }
        return "none:names=" + drawableNames.size() + ":rows=" + rows
            + ":tried=" + tried + ":tree=" + onEdt(
                WindowsHistoryNativeUiIngressProbe::partsTreeLabels);
    }

    /**
     * Selects the Parts-tree row for {@code displayName} through the table/tree selection
     * models. The palette forwards tree selection to the Editor selection, which is what
     * the inspector palette renders. Runs on the EDT; returns whether a row was set.
     */
    private static boolean selectPartsTreeRow(final String displayName) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null || !table.isShowing()) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final java.util.List<javax.swing.tree.TreePath> paths =
                WindowsMeshEditValidationProbe.findTreePaths(tree, displayName);
            if (paths.size() != 1) continue;
            tree.expandPath(paths.get(0).getParentPath());
            final int row = tree.getRowForPath(paths.get(0));
            if (row < 0) continue;
            tree.setSelectionPath(paths.get(0));
            table.setRowSelectionInterval(row, row);
            table.scrollRectToVisible(table.getCellRect(row, 0, true));
            return true;
        }
        return false;
    }

    /** The Parts palette table's selected row, or {@code -1}. Runs on the EDT. */
    private static int partsTableSelectedRow() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table != null && table.isShowing() && table.getSelectedRow() >= 0) {
                return table.getSelectedRow();
            }
        }
        return -1;
    }

    /** A bounded sample of the labels the Parts tree actually renders. Runs on the EDT. */
    private static String partsTreeLabels() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            final javax.swing.JTable table = findPartsTable(window);
            if (table == null) continue;
            final javax.swing.JTree tree =
                WindowsMeshEditValidationProbe.extractTree(table);
            if (tree == null) continue;
            final ArrayList<String> labels = new ArrayList<>();
            final ArrayDeque<Object> pending = new ArrayDeque<>();
            pending.add(tree.getModel().getRoot());
            while (!pending.isEmpty() && labels.size() < 28) {
                final Object node = pending.removeFirst();
                labels.add(tree.convertValueToText(node, false, false, false, 0, false));
                final int children = tree.getModel().getChildCount(node);
                for (int index = 0; index < children; index++) {
                    pending.addLast(tree.getModel().getChild(node, index));
                }
            }
            return "[" + String.join(",", labels) + "]";
        }
        return "no-parts-tree";
    }

    /** Whether any component from the inspector palette package is instantiated and showing. */
    private static boolean inspectorShowing() {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && hasPackageComponent(window, ".palette.inspector", 0)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasPackageComponent(
        final java.awt.Component component,
        final String packageMarker,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4) return false;
        if (component.getClass().getName().contains(packageMarker) && component.isShowing()) {
            return true;
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (hasPackageComponent(child, packageMarker, depth + 1)) return true;
            }
        }
        return false;
    }

    /**
     * Small pressable components inside the inspector palette — the multiply/screen colour
     * swatches among them open the host colour editor. When no {@code .palette.inspector}
     * component exists the search falls back to the dock region that holds the currently
     * selected inspector tab: the palette contents may be built from generic classes.
     */
    private static List<java.awt.Component> colorSwatches() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectColorSwatches(window, found, false, 0);
        }
        if (found.isEmpty()) {
            final StringBuilder ignored = new StringBuilder(1);
            final javax.swing.AbstractButton tab = paletteTab(INSPECTOR_MARKERS, ignored);
            if (tab != null && tab.isSelected()) {
                // The palette content lives in a sibling cell of the same dock column, not
                // inside the tab's own cell — climb to the column (the narrow container
                // holding every docked palette) and search it, skipping the tab strips.
                java.awt.Container scope = tab.getParent();
                while (scope != null && scope.getParent() != null
                    && scope.getParent().getWidth() <= 200) {
                    scope = scope.getParent();
                }
                if (scope != null) {
                    collectColorSwatches(scope, found, true, null, 0);
                }
            }
        }
        return found;
    }

    private static void collectColorSwatches(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final boolean insideInspector,
        final int depth
    ) {
        collectColorSwatches(component, found, insideInspector, null, depth);
    }

    private static void collectColorSwatches(
        final java.awt.Component component,
        final List<java.awt.Component> found,
        final boolean insideInspector,
        final java.awt.Container exclude,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible()) return;
        if (exclude != null && component == exclude) return;
        final boolean inInspector = insideInspector
            || component.getClass().getName().contains(".palette.inspector");
        if (inInspector && component.isShowing() && component.isEnabled()
            && component.getHeight() <= 40 && component.getHeight() >= 8
            && component.getWidth() <= 120 && component.getWidth() >= 8
            // Dock tab buttons are not swatches — pressing one just switches palettes.
            && !component.getClass().getName().equals("com.live2d.ui.swingImpl.K")
            && (component instanceof javax.swing.AbstractButton
                    && !(component instanceof javax.swing.JMenuItem)
                || looksLikeSwatch(component))) {
            found.add(component);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectColorSwatches(child, found, inInspector, exclude, depth + 1);
            }
        }
    }

    /**
     * Whether a component plausibly renders a colour swatch: a leaf whose background is a
     * saturated, non-grey, non-default colour.
     */
    private static boolean looksLikeSwatch(final java.awt.Component component) {
        if (component instanceof java.awt.Container container
            && container.getComponentCount() > 0) {
            return false;
        }
        final java.awt.Color bg = component.getBackground();
        if (bg == null) return false;
        final int max = Math.max(bg.getRed(), Math.max(bg.getGreen(), bg.getBlue()));
        final int min = Math.min(bg.getRed(), Math.min(bg.getGreen(), bg.getBlue()));
        return max - min >= 48;
    }

    /**
     * The inspector palette's content panel — the largest showing {@code u} container in the
     * selected inspector tab's dock column. The inspector paints its property rows onto this
     * single component, so it is the click surface for row scanning. Runs on the EDT.
     */
    /**
     * The dock column that hosts the inspector palette — the narrow container holding
     * every docked palette on that edge. Null unless the inspector tab is selected.
     */
    private static java.awt.Container inspectorDockScope() {
        return dockScopeFor(INSPECTOR_MARKERS);
    }

    /**
     * The dock column hosting the palette whose tab matches the markers — the narrow
     * container holding every docked palette on that edge. Null unless the tab is
     * selected.
     */
    private static java.awt.Container dockScopeFor(final String[] markers) {
        final javax.swing.AbstractButton tab = paletteTab(markers, new StringBuilder(1));
        if (tab == null || !tab.isSelected()) return null;
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null
            && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        return scope;
    }

    /**
     * The sliders inside the Parameter palette's dock columns — the palette's value rows
     * are CSlider widgets mirrored as {@code swingImpl.A} JSliders. Every selected
     * parameter tab's column is searched. Runs on the EDT.
     */
    private static List<javax.swing.JSlider> parameterDockSliders() {
        final List<javax.swing.JSlider> found = new ArrayList<>();
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope != null) collectSliders(scope, found, 0);
        }
        return found;
    }

    /**
     * Click surfaces over every showing {@code CSlider} CWidget inside a selected
     * parameter tab's dock column — for palettes whose rows exist only in the custom
     * widget tree, not as Swing children. Runs on the EDT.
     */
    private static List<java.awt.Component> parameterCWidgetSliders() {
        final List<java.awt.Component> found = new ArrayList<>();
        for (final javax.swing.AbstractButton tab
            : paletteTabs(PARAMETER_MARKERS, new StringBuilder(1))) {
            if (!tab.isSelected()) continue;
            java.awt.Container scope = tab.getParent();
            while (scope != null && scope.getParent() != null
                && scope.getParent().getWidth() <= 200) {
                scope = scope.getParent();
            }
            if (scope == null || !scope.isShowing()) continue;
            final java.awt.Point origin = scope.getLocationOnScreen();
            final java.awt.Rectangle dockRect = new java.awt.Rectangle(
                origin.x, origin.y, scope.getWidth(), scope.getHeight());
            Object root = cwidgetOf(scope);
            if (root == null) root = cwidgetTop(cwidgetOf(tab));
            for (final Object widget : cwidgetTree(root)) {
                final String name = widget.getClass().getName();
                // Parameter rows are palette.parameter widgets — a horizontal drag
                // across a row scrubs its value; CSlider mirrors qualify too.
                final boolean row = name.endsWith("CSlider")
                    || name.contains(".palette.parameter");
                if (!row) continue;
                final java.awt.Rectangle rect = cwidgetRect(widget);
                if (rect == null || !rect.intersects(dockRect)
                    || rect.width < 48 || rect.height < 6
                    || rect.height > 60) continue;
                final java.awt.Component surface = cwidgetSurface(widget);
                if (surface != null && !found.contains(surface)) {
                    found.add(surface);
                }
            }
        }
        return found;
    }

    private static java.awt.Component inspectorContentPanel() {
        final java.awt.Container scope = inspectorDockScope();
        if (scope == null) return null;
        final java.awt.Component[] best = {null};
        findInspectorPanel(scope, best, 0);
        return best[0];
    }

    private static void findInspectorPanel(
        final java.awt.Component component,
        final java.awt.Component[] best,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isShowing()) return;
        if (component.getClass().getName().equals("com.live2d.ui.swingImpl.u")
            && component.getHeight() >= 120 && component.getWidth() >= 100) {
            final java.awt.Component current = best[0];
            if (current == null
                || (long) component.getHeight() * component.getWidth()
                    > (long) current.getHeight() * current.getWidth()) {
                best[0] = component;
            }
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                findInspectorPanel(child, best, depth + 1);
            }
        }
    }

    /**
     * The custom {@code CWidget} behind a Swing mirror component, resolved through the
     * host's {@code com.live2d.ui.k} registry. Null when the component is not a mirror.
     */
    private static Object cwidgetOf(final java.awt.Component component) {
        if (component == null) return null;
        try {
            final Class<?> registry = Class.forName(
                "com.live2d.ui.k", true, component.getClass().getClassLoader());
            return registry.getMethod("a", java.awt.Component.class)
                .invoke(null, component);
        } catch (final Exception e) {
            return null;
        }
    }

    /** The topmost CWidget ancestor of a widget, or the widget itself. */
    private static Object cwidgetTop(final Object widget) {
        Object cursor = widget;
        while (cursor != null) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent")
                    .invoke(cursor);
                if (parent == null) return cursor;
                cursor = parent;
            } catch (final Exception e) {
                return cursor;
            }
        }
        return cursor;
    }

    /** Every CWidget under the root, depth-first. Runs on the EDT. */
    private static List<Object> cwidgetTree(final Object root) {
        final List<Object> found = new ArrayList<>();
        if (root == null) return found;
        for (final boolean direction : new boolean[] {true, false}) {
            try {
                final java.util.Iterator<?> it = (java.util.Iterator<?>)
                    root.getClass().getMethod("traverse", boolean.class)
                        .invoke(root, direction);
                while (it.hasNext()) {
                    found.add(it.next());
                }
            } catch (final Exception ignored) {
            }
            if (!found.isEmpty()) break;
        }
        return found;
    }

    private static java.awt.Rectangle cwidgetRect(final Object widget) {
        try {
            final Object rect = widget.getClass().getMethod("getRectOnScreen")
                .invoke(widget);
            return (java.awt.Rectangle) rect.getClass().getMethod("getJrect")
                .invoke(rect);
        } catch (final Exception e) {
            return null;
        }
    }

    private static boolean cwidgetShowing(final Object widget) {
        try {
            return Boolean.TRUE.equals(widget.getClass()
                .getMethod("isShowing").invoke(widget));
        } catch (final Exception e) {
            return false;
        }
    }

    /**
     * A {@code CTextField}'s editable {@code JTextComponent} mirror — real Robot typing
     * into it is a native edit. Null for any other widget.
     */
    private static javax.swing.text.JTextComponent cwidgetTextComponent(
        final Object widget
    ) {
        try {
            final Object text = widget.getClass().getMethod("getJTextComponent")
                .invoke(widget);
            return text instanceof javax.swing.text.JTextComponent tc
                && tc.isShowing() && tc.isEnabled() && tc.isEditable() ? tc : null;
        } catch (final Exception e) {
            return null;
        }
    }

    private static String cwidgetText(final Object widget) {
        for (final String method : new String[] {"getText", "getName", "getToolTipText"}) {
            try {
                final Object value = widget.getClass().getMethod(method).invoke(widget);
                if (value instanceof String s && !s.isBlank()) return s;
            } catch (final Exception ignored) {
            }
        }
        return "";
    }

    /**
     * Whether a widget sits in an inspector row carrying the multiply-colour label —
     * climbs the row containers it belongs to looking for a sibling text widget with
     * the marker text.
     */
    private static boolean multiplyMarked(final Object widget) {
        Object cursor = widget;
        for (int up = 0; cursor != null && up < 5; up++) {
            try {
                final Object parent = cursor.getClass().getMethod("getParent")
                    .invoke(cursor);
                if (parent == null) return false;
                final Object children = parent.getClass().getMethod("getChildren")
                    .invoke(parent);
                if (children instanceof List<?> siblings) {
                    for (final Object sibling : siblings) {
                        if (cwidgetText(sibling)
                            .matches(".*(正片叠底|乗算|乘算|[Mm]ultiply).*")) {
                            return true;
                        }
                    }
                }
                cursor = parent;
            } catch (final Exception e) {
                return false;
            }
        }
        return false;
    }

    /**
     * A click-surface pseudo-component over the CWidget's on-screen rect so the Robot
     * can press controls that have no visible Swing mirror of their own.
     */
    private static java.awt.Component cwidgetSurface(final Object widget) {
        if (!cwidgetShowing(widget)) return null;
        final java.awt.Rectangle rect = cwidgetRect(widget);
        if (rect == null || rect.isEmpty()) return null;
        return new java.awt.Component() {
            @Override public boolean isShowing() { return true; }
            @Override public java.awt.Point getLocationOnScreen() {
                return rect.getLocation();
            }
            @Override public int getWidth() { return rect.width; }
            @Override public int getHeight() { return rect.height; }
        };
    }

    /** A compact census of a CWidget subtree for diagnostics. */
    private static String cwidgetCensus(final List<Object> widgets) {
        return cwidgetCensus(widgets, 560);
    }

    private static String cwidgetCensus(final List<Object> widgets, final int cap) {
        final StringBuilder out = new StringBuilder(cap + 64);
        for (final Object widget : widgets) {
            if (out.length() > cap) {
                out.append("...");
                break;
            }
            out.append(widget.getClass().getSimpleName());
            final java.awt.Rectangle rect = cwidgetRect(widget);
            if (rect != null) {
                out.append('@').append(rect.x).append(',').append(rect.y)
                    .append(' ').append(rect.width).append('x').append(rect.height);
            }
            if (!cwidgetShowing(widget)) out.append(":hidden");
            final String text = cwidgetText(widget);
            if (!text.isBlank()) out.append('[').append(text).append(']');
            out.append(';');
        }
        return out.toString();
    }

    /**
     * Captures the inspector content panel's screen pixels into the evidence stream as a
     * base64 PNG so a failed colour step shows what the palette actually rendered.
     */
    private void dumpRegionScreenshot(final java.awt.Robot robot, final String name) {
        try {
            final byte[] png = onEdt(() -> {
                final java.awt.Component panel = inspectorContentPanel();
                if (panel == null || panel.getWidth() < 4 || panel.getHeight() < 4) {
                    return null;
                }
                // Paint the component itself rather than grabbing screen pixels — Robot
                // captures whatever window happens to cover the region, while printing
                // gives the inspector's real rendered rows.
                final java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(
                    panel.getWidth(), panel.getHeight(),
                    java.awt.image.BufferedImage.TYPE_INT_RGB);
                final java.awt.Graphics2D g = image.createGraphics();
                try {
                    panel.printAll(g);
                } finally {
                    g.dispose();
                }
                final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                javax.imageio.ImageIO.write(image, "png", out);
                return out.toByteArray();
            });
            if (png == null) return;
            write(artifact, "{\"type\":\"screenshot\",\"region\":" + quoted(name)
                + ",\"png\":\"" + java.util.Base64.getEncoder().encodeToString(png)
                + "\"}\n", false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
    }

    private void dumpWindowScreenshot(
        final java.awt.Robot robot,
        final java.awt.Window window,
        final String name
    ) {
        try {
            final java.awt.Rectangle rect = onEdt(() -> {
                if (!window.isShowing()) return null;
                return new java.awt.Rectangle(
                    window.getX(), window.getY(), window.getWidth(), window.getHeight());
            });
            if (rect == null || rect.width < 4 || rect.height < 4) return;
            final java.awt.Rectangle clipped = rect.intersection(new java.awt.Rectangle(
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getMaximumWindowBounds()));
            if (clipped.width < 4 || clipped.height < 4) return;
            rect.setBounds(clipped);
            final java.awt.image.BufferedImage image = robot.createScreenCapture(rect);
            final java.io.ByteArrayOutputStream png = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(image, "png", png);
            write(artifact, "{\"type\":\"screenshot\",\"region\":" + quoted(name)
                + ",\"width\":" + rect.width + ",\"height\":" + rect.height
                + ",\"png\":\"" + java.util.Base64.getEncoder().encodeToString(png.toByteArray())
                + "\"}\n", false);
        } catch (Throwable ignored) {
            // Diagnostics must never fail the step.
        }
    }

    /**
     * Scans a screen rectangle for saturated colour blocks — the centres of small regions
     * whose pixels stay vividly coloured are the inspector's painted colour swatches.
     */
    private static List<int[]> saturatedBlocks(
        final java.awt.Robot robot,
        final int[] bounds
    ) {
        final List<int[]> points = new ArrayList<>();
        int lastY = -100;
        for (int y = bounds[1] + 6; y < bounds[1] + bounds[3] - 6; y += 4) {
            int runStart = -1;
            int runEnd = -1;
            for (int x = bounds[0] + 6; x < bounds[0] + bounds[2] - 6; x += 3) {
                final java.awt.Color pixel = robot.getPixelColor(x, y);
                final int max = Math.max(pixel.getRed(),
                    Math.max(pixel.getGreen(), pixel.getBlue()));
                final int min = Math.min(pixel.getRed(),
                    Math.min(pixel.getGreen(), pixel.getBlue()));
                if (max - min >= 64 && max >= 90) {
                    if (runStart < 0) runStart = x;
                    runEnd = x;
                } else if (runStart >= 0) {
                    if (runEnd - runStart >= 8 && runEnd - runStart <= 60
                        && y - lastY > 18) {
                        points.add(new int[] {(runStart + runEnd) / 2, y});
                        lastY = y;
                    }
                    runStart = -1;
                }
            }
            if (runStart >= 0 && runEnd - runStart >= 8 && runEnd - runStart <= 60
                && y - lastY > 18) {
                points.add(new int[] {(runStart + runEnd) / 2, y});
                lastY = y;
            }
        }
        return points;
    }

    /**
     * A census of leaf components inside the selected inspector tab's dock region — class,
     * bounds and background — so a missed swatch shows what the palette actually contains.
     * Runs on the EDT.
     */
    private static String inspectorLeafCensus() {
        final StringBuilder ignored = new StringBuilder(1);
        final javax.swing.AbstractButton tab = paletteTab(INSPECTOR_MARKERS, ignored);
        if (tab == null) return "no-tab";
        java.awt.Container scope = tab.getParent();
        while (scope != null && scope.getParent() != null
            && scope.getParent().getWidth() <= 200) {
            scope = scope.getParent();
        }
        if (scope == null) return "no-scope";
        final StringBuilder out = new StringBuilder(1200);
        collectLeafCensus(scope, out, null, 0);
        return out.length() > 0 ? out.toString() : "empty";
    }

    private static void collectLeafCensus(
        final java.awt.Component component,
        final StringBuilder out,
        final java.awt.Container exclude,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH + 4 || !component.isVisible()
            || out.length() > 1100) return;
        if (exclude != null && component == exclude) return;
        if (!(component instanceof java.awt.Container container)
            || container.getComponentCount() == 0) {
            final java.awt.Color bg = component.getBackground();
            out.append(component.getClass().getSimpleName())
                .append('[').append(component.getWidth()).append('x')
                .append(component.getHeight()).append(']');
            if (bg != null) {
                out.append('#').append(Integer.toHexString(bg.getRGB() & 0xFFFFFF));
            }
            out.append(';');
            return;
        }
        for (java.awt.Component child : container.getComponents()) {
            collectLeafCensus(child, out, exclude, depth + 1);
        }
    }

    private static java.util.Set<java.awt.Window> visibleWindows() {
        final java.util.Set<java.awt.Window> set = new java.util.HashSet<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) set.add(window);
        }
        return set;
    }

    private static java.awt.Window newWindowSince(final java.util.Set<java.awt.Window> before) {
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible() && !before.contains(window)) return window;
        }
        return null;
    }

    /**
     * Edits one editable text field inside a just-opened dialog and confirms it.
     *
     * <p>The replacement adapts to the field's current content: a hex colour gets another
     * hex colour, an integer channel gets a shifted value, anything else is skipped. The
     * dialog is then confirmed with Enter, and when that does not move the significant
     * sequence, with the first OK-looking button.
     *
     * @return a short note for the actor result, or {@code null} when nothing committed
     */
    private String editDialogField(
        final java.awt.Robot robot,
        final java.awt.Window dialog,
        final String knownSignificant,
        final StringBuilder note
    ) throws Exception {
        final List<javax.swing.text.JTextComponent> fields = onEdt(() -> {
            final List<javax.swing.text.JTextComponent> found = new ArrayList<>();
            collectEditableFields(dialog, found, 0);
            return found;
        });
        final ArrayList<String> tried = new ArrayList<>();
        for (final javax.swing.text.JTextComponent field : fields) {
            if (tried.size() >= 4) break;
            final int[] at = onEdt(() -> fieldCentre(field));
            if (at == null) continue;
            final String previous = onEdt(field::getText);
            final String replacement = replacementValue(previous);
            if (replacement == null) continue;
            tried.add((previous == null ? "?" : previous.trim()) + "->" + replacement);
            note.append(previous == null ? "?" : previous.trim())
                .append("->").append(replacement).append(';');
            robot.mouseMove(at[0], at[1]);
            robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(150L);
            robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
            robot.keyPress(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_A);
            robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
            Thread.sleep(80L);
            for (final char digit : replacement.toCharArray()) {
                typeChar(robot, digit);
            }
            robot.keyPress(java.awt.event.KeyEvent.VK_ENTER);
            robot.keyRelease(java.awt.event.KeyEvent.VK_ENTER);
            for (int settle = 0; settle < 10; settle++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return "field:" + replacement;
                }
            }
            // Enter alone may only apply the field — the dialog still needs its OK button.
            final int[] ok = onEdt(() -> confirmPoint(dialog));
            if (ok != null) {
                robot.mouseMove(ok[0], ok[1]);
                robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK);
                for (int settle = 0; settle < 10; settle++) {
                    Thread.sleep(POLL_MILLIS);
                    if (!significantSequence(sample()).equals(knownSignificant)) {
                        return "field-ok:" + replacement;
                    }
                }
            }
        }
        if (fields.isEmpty()) note.append("no-editable-fields;");
        return null;
    }

    /** A plausible replacement for a colour-editor field's current text, or null. */
    private static String replacementValue(final String previous) {
        if (previous == null) return null;
        final String value = previous.trim();
        if (value.matches("#?[0-9a-fA-F]{6}")) {
            return value.contains("33") ? "2244CC" : "CC4433";
        }
        if (value.matches("\\d{1,3}")) {
            final int channel = Integer.parseInt(value);
            return String.valueOf((channel + 96) % 256);
        }
        if (value.matches("\\d+\\.\\d+")) {
            return value.startsWith("0.5") ? "0.7" : "0.5";
        }
        return null;
    }

    /** Every showing editable text component inside one window. */
    private static void collectEditableFields(
        final java.awt.Component component,
        final List<javax.swing.text.JTextComponent> found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible()) return;
        if (component instanceof javax.swing.text.JTextComponent text
            && text.isShowing() && text.isEnabled() && text.isEditable()) {
            found.add(text);
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectEditableFields(child, found, depth + 1);
            }
        }
    }

    /** The screen centre of a dialog's OK-looking button, or null. Runs on the EDT. */
    private static int[] confirmPoint(final java.awt.Window dialog) {
        final javax.swing.AbstractButton[] found = {null};
        findConfirmButton(dialog, found, 0);
        return found[0] == null ? null : fieldCentre(found[0]);
    }

    private static final String[] CONFIRM_MARKERS =
        {"OK", "确定", "適用", "适用", "Apply"};

    private static void findConfirmButton(
        final java.awt.Component component,
        final javax.swing.AbstractButton[] found,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || found[0] != null) return;
        if (component instanceof javax.swing.AbstractButton button
            && button.isShowing() && button.isEnabled() && button.getText() != null
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

    private static int visibleWindowCount() {
        int count = 0;
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) count++;
        }
        return count;
    }

    /** The screen centre of a showing component, or {@code null} when it is not showing. */
    private static int[] fieldCentre(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new int[] {
                origin.x + component.getWidth() / 2,
                origin.y + component.getHeight() / 2
            };
        } catch (java.awt.IllegalComponentStateException notShowing) {
            return null;
        }
    }

    /** Types one ASCII hex character through the Robot. */
    private static void typeChar(final java.awt.Robot robot, final char digit) throws Exception {
        final int key = switch (digit) {
            case '0' -> java.awt.event.KeyEvent.VK_0;
            case '1' -> java.awt.event.KeyEvent.VK_1;
            case '2' -> java.awt.event.KeyEvent.VK_2;
            case '3' -> java.awt.event.KeyEvent.VK_3;
            case '4' -> java.awt.event.KeyEvent.VK_4;
            case '5' -> java.awt.event.KeyEvent.VK_5;
            case '6' -> java.awt.event.KeyEvent.VK_6;
            case '7' -> java.awt.event.KeyEvent.VK_7;
            case '8' -> java.awt.event.KeyEvent.VK_8;
            case '9' -> java.awt.event.KeyEvent.VK_9;
            default -> java.awt.event.KeyEvent.VK_A + (Character.toUpperCase(digit) - 'A');
        };
        robot.keyPress(key);
        robot.keyRelease(key);
        Thread.sleep(25L);
    }

    /**
     * The rectangle of a component that is actually on screen.
     *
     * <p>A {@code JComponent} inside a viewport can extend far beyond what is rendered; pressing
     * at the bounds centre would land on whatever happens to sit at that screen point. Plain
     * heavyweight components have no such clipping, so their bounds are the visible region.</p>
     */
    private static java.awt.Rectangle visibleBounds(final java.awt.Component component) {
        if (component instanceof javax.swing.JComponent swing) return swing.getVisibleRect();
        return new java.awt.Rectangle(0, 0, component.getWidth(), component.getHeight());
    }

    /**
     * Sends one native Ctrl+key shortcut exactly as the operator would: the enabled menu
     * accelerator first, then a focused-window Robot keystroke when no menu item claims it.
     */
    private String shortcut(final int key, final String knownSignificant) throws Exception {
        String route = "none";
        primeMenus();
        if (Boolean.TRUE.equals(onEdt(() -> menuShortcut(key)))) {
            route = "menu-accelerator";
            // A mirror menu item can be enabled yet disconnected from the real action —
            // only treat the accelerator as delivered when the edit position moves.
            for (int poll = 0; poll < 10; poll++) {
                Thread.sleep(POLL_MILLIS);
                if (!significantSequence(sample()).equals(knownSignificant)) {
                    return route;
                }
            }
            route += ":silent";
        }
        final java.awt.Robot robot = new java.awt.Robot();
        // No focusing click: a press on the model canvas is itself recorded as a drag-select
        // undo entry, which the shortcut would then consume instead of the operator's edit.
        Thread.sleep(300L);
        robot.keyPress(java.awt.event.KeyEvent.VK_CONTROL);
        robot.keyPress(key);
        robot.keyRelease(key);
        robot.keyRelease(java.awt.event.KeyEvent.VK_CONTROL);
        return route.equals("none") ? "robot" : route + "+robot";
    }

    /**
     * Opens every top menu briefly so the mirror items bind their real actions — they do so
     * lazily on menu open, and a {@code doClick} before that is a silent no-op.
     */
    private void primeMenus() throws Exception {
        final javax.swing.JMenuBar bar = onEdt(() -> {
            for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
                if (frame instanceof javax.swing.JFrame swingFrame && frame.isVisible()
                    && swingFrame.getJMenuBar() != null) {
                    return swingFrame.getJMenuBar();
                }
            }
            return null;
        });
        if (bar == null || primedMenus) return;
        onEdt(() -> {
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu top = bar.getMenu(index);
                if (top != null) top.setPopupMenuVisible(true);
            }
            return true;
        });
        Thread.sleep(400L);
        onEdt(() -> {
            javax.swing.MenuSelectionManager.defaultManager().clearSelectedPath();
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenu top = bar.getMenu(index);
                if (top != null) top.setPopupMenuVisible(false);
            }
            return true;
        });
        Thread.sleep(150L);
        primedMenus = true;
    }

    private volatile boolean primedMenus;

    /** Clicks the enabled menu item carrying a Ctrl+key accelerator, when one exists. */
    private static boolean menuShortcut(final int key) {
        for (java.awt.Frame frame : java.awt.Frame.getFrames()) {
            if (!(frame instanceof javax.swing.JFrame swingFrame) || !frame.isVisible()) continue;
            final javax.swing.JMenuBar bar = swingFrame.getJMenuBar();
            if (bar == null) continue;
            for (int index = 0; index < bar.getMenuCount(); index++) {
                final javax.swing.JMenuItem match = findMenuShortcut(bar.getMenu(index), key);
                if (match != null && match.isEnabled()) {
                    // The real action lives on the host's CMenuItem — resolve the mirror
                    // through the component→widget registry before falling back to the
                    // mirror's own doClick, which can be a silent no-op.
                    final Object widget = widgetOf(match, new StringBuilder(1));
                    if (widget != null) {
                        try {
                            widget.getClass().getMethod("doClick").invoke(widget);
                            return true;
                        } catch (Exception ignored) {
                            // fall through to the mirror click
                        }
                    }
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
     * The components most likely to be the model canvas, best first.
     *
     * <p>The document view does not have to live in a {@code Frame} — the r11 run showed the
     * model window is a {@code JDialog} — so discovery scans every visible window. A class whose
     * name carries a canvas/GL marker wins over the structural fallback, which keeps the largest
     * showing leaves that are not interactive controls; a press inside a palette button or a
     * slider would prove the wrong family, so those leaves never qualify.</p>
     */
    private static List<java.awt.Component> canvasCandidates() {
        final List<java.awt.Component> named = new ArrayList<>();
        final List<java.awt.Component> regions = new ArrayList<>();
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (window.isVisible()) collectCanvas(window, named, regions, 0);
        }
        regions.sort((first, second) -> Long.compare(
            (long) second.getWidth() * second.getHeight(),
            (long) first.getWidth() * first.getHeight()));
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
            final String hit = pressTarget(
                region, new java.awt.Point(region.getWidth() / 2, region.getHeight() / 2));
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

    private static boolean isHostClassName(final String className) {
        return !className.startsWith("javax.swing.")
            && !className.startsWith("java.awt.")
            && !className.startsWith("sun.")
            && !className.startsWith("com.formdev.flatlaf.");
    }

    private static List<java.awt.Component> concat(
        final List<java.awt.Component> first,
        final List<java.awt.Component> second
    ) {
        final List<java.awt.Component> all = new ArrayList<>(first.size() + second.size());
        all.addAll(first);
        all.addAll(second);
        return all;
    }

    /** The component's on-screen rectangle, or {@code null} when it is not showing. */
    private static java.awt.Rectangle screenRect(final java.awt.Component component) {
        if (!component.isShowing()) return null;
        try {
            final java.awt.Point origin = component.getLocationOnScreen();
            return new java.awt.Rectangle(
                origin.x, origin.y, component.getWidth(), component.getHeight());
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
    private static boolean isExcludedControl(final java.awt.Component component) {
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
    private static String pressTarget(final java.awt.Component canvas, final java.awt.Point local) {
        java.awt.Component hit = javax.swing.SwingUtilities.getDeepestComponentAt(
            canvas, local.x, local.y);
        if (hit == null) hit = canvas;
        if (isExcludedControl(hit)) return null;
        if (hit.getWidth() < 8 || hit.getHeight() < 8) return null;
        return hit.getClass().getName();
    }

    /**
     * A bounded report of why every plausible canvas leaf was rejected.
     *
     * <p>Only consulted when discovery came back empty: it walks the same windows and records
     * each leaf that was large enough to matter along with the check that excluded it, so an
     * empty candidate list explains itself in the artifact.</p>
     */
    private static String canvasRejects() {
        final StringBuilder out = new StringBuilder(4096);
        for (final java.awt.Window window : java.awt.Window.getWindows()) {
            if (!window.isVisible()) continue;
            out.append("W:").append(window.getClass().getSimpleName())
                .append(":showing=").append(window.isShowing()).append(';');
            collectRejects(window, out, 0);
        }
        return out.length() > 0 ? out.substring(0, Math.min(out.length(), 4000)) : "no-windows";
    }

    private static void collectRejects(
        final java.awt.Component component,
        final StringBuilder out,
        final int depth
    ) {
        if (depth > CANVAS_SCAN_DEPTH || !component.isVisible() || out.length() > 3800) return;
        boolean visibleLeaf = true;
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                if (child.isShowing()) {
                    visibleLeaf = false;
                    break;
                }
            }
        }
        if (component.getWidth() >= MIN_CANVAS_EDGE
            && component.getHeight() >= MIN_CANVAS_EDGE) {
            final String reason = !component.isShowing() ? "not-showing"
                : isExcludedControl(component) ? "control"
                : !visibleLeaf ? "container"
                : "eligible";
            out.append(component.getClass().getName())
                .append('[').append(component.getWidth()).append('x')
                .append(component.getHeight()).append(']')
                .append('@').append(depth)
                .append('=').append(reason).append(';');
        }
        if (component instanceof java.awt.Container container) {
            for (java.awt.Component child : container.getComponents()) {
                collectRejects(child, out, depth + 1);
            }
        }
    }

    private static void collectCanvas(
        final java.awt.Component component,
        final List<java.awt.Component> named,
        final List<java.awt.Component> regions,
        final int depth
    ) {
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
        if (component.isShowing() && hostClass && !className.contains(".palette.")
            && component.getClass().getSimpleName()
                .matches(".*[cC]anvas.*|.*[gG][lL].*|.*[vV]iew.*")
            && !named.contains(component)) {
            named.add(component);
        }
        // A drag surface only has to mark a plausible press region: the r19 run showed the model
        // view's ancestors rejecting the leaf rule while the GL surface itself sat deeper than
        // the old scan reached. Every showing non-control component above the minimum area is a
        // region candidate; {@link #pressTarget} refuses any point that resolves to a control or
        // a divider sliver, so a container entry can never press a button it happens to contain.
        if (component.isShowing()
            && component.getWidth() >= MIN_CANVAS_EDGE && component.getHeight() >= MIN_CANVAS_EDGE
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
