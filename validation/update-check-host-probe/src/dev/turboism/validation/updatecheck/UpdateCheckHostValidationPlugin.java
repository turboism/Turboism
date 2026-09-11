package dev.turboism.validation.updatecheck;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;
import dev.turboism.sdk.ui.CanvasHintHandle;
import dev.turboism.sdk.ui.CanvasHintNotification;

import javax.swing.JButton;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Task-local exerciser for the stable update checker on an exact Cubism host.
 *
 * <p>This probe never replaces the production endpoint and never injects a fake transport. It lets
 * the real runtime perform its real request against the real release API, then records what the
 * production code actually did:</p>
 *
 * <ul>
 *   <li>the decision the checker committed, read back from the {@code update-state.json} that the
 *       production store itself wrote;</li>
 *   <li>that the runtime log carries the core plugin's decision to present an offered update in the
 *       host's native drawing-area hint;</li>
 *   <li>that this host's native hint surface accepts hints at all, established by driving the same
 *       SDK entry point with a probe-owned key;</li>
 *   <li>that nothing was pushed into the docked panel.</li>
 * </ul>
 *
 * <p>A native canvas hint is drawn by the host and is not an AWT component, so no Swing walk can
 * read it. This probe therefore does not claim to have read the hint's pixels: it separates "the
 * host cannot present hints" from "core had nothing to present", and the {@code demo} mode is how a
 * human confirms the rendering.</p>
 *
 * <p>It only uses the public SDK and plain JDK AWT/Swing. It never imports or reflects
 * {@code com.live2d.*} types, opens a browser, mutates a document, or writes outside its own state
 * directory.</p>
 */
public final class UpdateCheckHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "update-check-result.txt";
    private static final String MODE_PROPERTY = "turboism.validation.updateCheck.mode";
    private static final String EXPECT_BUILD_PROPERTY = "turboism.validation.updateCheck.expectBuild";

    /** Probe-owned hint key, so the probe's own hint can never collide with the production hint. */
    private static final String HINT_PROBE_ID = "update-check-validation-probe";

    /** Panel control ids the production update presentation must never contribute. */
    private static final String UPDATE_CONTROL_PREFIX = "turboism-update";

    private static final String UPDATE_STATE_FILE = "update-state.json";
    private static final String CORE_HINT_SENT_MARKER = "UPDATE_HINT_SENT";

    /** Human-inspection session: records what is on screen, then leaves the host running. */
    private static final String DEMO_MODE = "demo";

    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long DECISION_TIMEOUT_MILLIS = 90_000L;
    private static final long QUIET_WINDOW_MILLIS = 30_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;

    /** Reviewed exact host versions the runtime report may advertise as READY. */
    private static final List<String> REVIEWED_HOST_VERSIONS = List.of("5.2.03", "5.3.02", "5.3.03");

    /**
     * A supported host may legitimately report {@code version: UNKNOWN} in the runtime report while
     * its artifact SHA still matches the reviewed installation.
     */
    private static final String UNKNOWN_HOST_VERSION = "UNKNOWN";

    private PluginLogger logger;
    private PluginContext context;
    private Path stateDir;

    @Override
    public void init(final PluginContext context) {
        this.context = context;
        this.logger = context.logger();
        this.stateDir = context.paths().stateDir();
        final Thread exerciser = new Thread(this::runWhenHostReady, "update-check-host-exerciser");
        exerciser.setDaemon(true);
        exerciser.start();
    }

    @Override
    public void enable() {
        logger.info("UPDATE_CHECK_EXERCISER_ENABLED");
    }

    @Override
    public void shutdown() {
        logger.info("UPDATE_CHECK_EXERCISER_SHUTDOWN");
    }

    private void runWhenHostReady() {
        final Optional<String> hostVersion = awaitVerifiedHost();
        if (hostVersion.isEmpty()) {
            fail("readiness", "active-runtime-report-or-model-not-present");
            return;
        }
        logger.info("UPDATE_CHECK_EXERCISER_READY"
            + " hostVersion=" + hostVersion.orElse("unknown")
            + " mode=" + mode()
            + " locale=" + System.getProperty("turboism.locale", "host"));

        final List<String> failures = new ArrayList<>();
        final List<String> observations = new ArrayList<>();
        try {
            switch (mode()) {
                case "new" -> assertUpdateOffered(failures, observations);
                case "current" -> assertQuietAndUnchanged(failures, observations);
                case "safe" -> assertSafeModeNeverRequests(failures, observations);
                case DEMO_MODE -> observeForHumanInspection(observations);
                default -> failures.add("unsupported mode: " + mode());
            }
        } catch (RuntimeException | Error failure) {
            failures.add(mode() + " failed safely: " + failure.getClass().getName());
        }

        final boolean pass = failures.isEmpty();
        writeResult(pass, failures, observations);
        logger.info("UPDATE_CHECK_EXERCISER_RESULT status=" + (pass ? "PASS" : "FAIL")
            + " mode=" + mode()
            + " failures=" + failures.size()
            + " observations=" + observations.size());
        for (final String failure : failures) {
            logger.warn("UPDATE_CHECK_EXERCISER_FAILURE " + failure);
        }
        if (DEMO_MODE.equals(mode())) {
            // The window is the artifact here. Park without exiting so the operator can inspect and
            // click; the runner's exit timeout owns cleanup.
            logger.info("UPDATE_CHECK_EXERCISER_DEMO_HANDOFF"
                + " the editor is intentionally left running for human inspection");
            park();
            return;
        }
        sleep(PASS_SETTLE_MILLIS);
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private void fail(final String phase, final String detail) {
        writeResult(false, List.of(phase + ": " + detail), List.of());
        logger.warn("UPDATE_CHECK_EXERCISER_RESULT status=FAIL phase=" + phase + " detail=" + detail);
        Runtime.getRuntime().halt(2);
    }

    // ------------------------------------------------------------------ modes

    /**
     * The installed bundle is deliberately older than the published stable build, so the real
     * request must resolve to an update and core must present it in the native drawing-area hint.
     */
    private void assertUpdateOffered(final List<String> failures, final List<String> observations) {
        final String expectedBuild = System.getProperty(EXPECT_BUILD_PROPERTY, "");

        final String state = normalized(readUpdateState());
        if (state == null) {
            failures.add("the runtime committed no " + UPDATE_STATE_FILE + " after a ready check");
        } else {
            observations.add("committedUpdateState=" + summarize(readUpdateState()));
            if (!state.contains("\"status\":\"ready\"")) {
                failures.add("committed update state is not a ready document");
            }
            if (!expectedBuild.isEmpty() && !state.contains("\"buildNumber\":" + expectedBuild)) {
                failures.add("committed update state does not carry buildNumber " + expectedBuild);
            }
        }

        observations.add("nativeHintSurfaceAccepted=" + nativeHintSurfaceAcceptsHints());

        final String hint = awaitCoreHintSent(DECISION_TIMEOUT_MILLIS);
        if (hint == null) {
            failures.add("core never reported presenting the offered update as a canvas hint");
        } else {
            observations.add("coreHintSent=" + hint);
            if (!hint.contains("Build " + expectedBuild + ")")) {
                failures.add("the presented hint does not name build " + expectedBuild + ": " + hint);
            }
        }

        assertNoUpdatePanel(failures, observations);
    }

    /**
     * The installed bundle matches the published stable version and carries no build number, so the
     * automatic check must commit a ready decision and must not claim an update.
     */
    private void assertQuietAndUnchanged(final List<String> failures, final List<String> observations) {
        final String state = normalized(awaitCommittedUpdateState(DECISION_TIMEOUT_MILLIS));
        if (state == null) {
            failures.add("the automatic check never committed " + UPDATE_STATE_FILE);
        } else {
            observations.add("committedUpdateState=" + summarize(readUpdateState()));
            if (!state.contains("\"status\":\"ready\"")) {
                failures.add("committed update state is not a ready document");
            }
        }
        assertNoUpdateHint(failures, observations);
        assertNoUpdatePanel(failures, observations);
    }

    /** Safe mode forbids networking, so the checker must never commit a request result. */
    private void assertSafeModeNeverRequests(
        final List<String> failures,
        final List<String> observations
    ) {
        final String state = awaitCommittedUpdateState(QUIET_WINDOW_MILLIS);
        if (state != null) {
            failures.add("safe mode still performed a check and committed "
                + UPDATE_STATE_FILE + ": " + summarize(state));
        } else {
            observations.add("noUpdateStateWritten=true");
        }
        assertNoUpdateHint(failures, observations);
        assertNoUpdatePanel(failures, observations);
    }

    /** No update was offered, so core must not have presented one. */
    private void assertNoUpdateHint(final List<String> failures, final List<String> observations) {
        final String hint = awaitCoreHintSent(QUIET_WINDOW_MILLIS);
        if (hint != null) {
            failures.add("a non-update was presented as an available update: " + hint);
        } else {
            observations.add("noUpdateHintPresented=true");
        }
    }

    /** The update presentation belongs in the native hint, never in the docked panel. */
    private void assertNoUpdatePanel(final List<String> failures, final List<String> observations) {
        final List<String> names = buttonNames();
        observations.add("panelControls=" + String.join(",", names));
        for (final String name : names) {
            if (name.startsWith(UPDATE_CONTROL_PREFIX)) {
                failures.add("the update was added to the docked panel as '" + name + "'");
            }
        }
        observations.add("panelUpdateControls=absent");
    }

    /**
     * Records what the operator will be looking at, then hands the host over. Unlike the automated
     * modes this asserts nothing: a human is the assertion, so a missing hint is recorded rather than
     * failed, and the result is explicitly marked as a handoff rather than as evidence.
     */
    private void observeForHumanInspection(final List<String> observations) {
        final String expectedBuild = System.getProperty(EXPECT_BUILD_PROPERTY, "");
        observations.add("nativeHintSurfaceAccepted=" + nativeHintSurfaceAcceptsHints());
        observations.add("committedUpdateState=" + summarize(readUpdateState()));
        final String state = normalized(readUpdateState());
        if (state != null) {
            observations.add("committedReady=" + state.contains("\"status\":\"ready\""));
        }
        final String hint = awaitCoreHintSent(DECISION_TIMEOUT_MILLIS);
        observations.add("coreHintSent=" + (hint == null ? "<none>" : hint));
        observations.add("expectedBuildNamed="
            + (hint != null && !expectedBuild.isEmpty() && hint.contains("Build " + expectedBuild + ")")));
        observations.add("panelUpdateControls="
            + (buttonNames().stream().anyMatch(name -> name.startsWith(UPDATE_CONTROL_PREFIX))
                ? "present" : "absent"));
        observations.add("waitingForHumanInspection=true");
    }

    /** Holds this thread while the editor stays available to a human operator. */
    private void park() {
        while (true) {
            sleep(SETTLE_STEP_MILLIS);
        }
    }

    // -------------------------------------------------------------- readiness

    /**
     * Readiness is the runtime's own verified-host report, not a version string: this host reports
     * {@code version: UNKNOWN} while {@code identityState: MATCHED} plus the pinned
     * {@code artifactSha256} carry the real identity guarantee. The active model is required so the
     * probe observes a host in the same state a user would be in.
     *
     * @return the reported host version for diagnostics, or empty when the host never became ready
     */
    private Optional<String> awaitVerifiedHost() {
        final long deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MILLIS;
        String lastObservation = "runtime-report-absent";
        while (System.currentTimeMillis() < deadline) {
            try {
                final Optional<String> version = activeReviewedRuntimeVersion();
                if (version.isPresent()) {
                    final String modelId = context.cubism().model().active().id().value();
                    if (!modelId.isBlank()) {
                        return version;
                    }
                    lastObservation = "active-model-not-present";
                } else {
                    lastObservation = "runtime-report-not-MATCHED-READY-RUNNING";
                }
            } catch (RuntimeException unavailable) {
                lastObservation = "cubism-read-failed: " + unavailable.getClass().getSimpleName();
            }
            sleep(SETTLE_STEP_MILLIS);
        }
        logger.warn("UPDATE_CHECK_EXERCISER_NOT_READY " + lastObservation);
        return Optional.empty();
    }

    /**
     * The verified-host runtime report for the running host. The MATCHED/READY/RUNNING triple is the
     * identity guarantee; the reported version string is informational because a supported host may
     * legitimately report it as {@code UNKNOWN}.
     */
    private Optional<String> activeReviewedRuntimeVersion() {
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            final boolean ready = json.contains("\"identityState\":\"MATCHED\"")
                && json.contains("\"adapterState\":\"READY\"")
                && json.contains("\"runtimeState\":\"RUNNING\"");
            if (!ready) {
                return Optional.empty();
            }
            for (final String reviewed : REVIEWED_HOST_VERSIONS) {
                if (json.contains("\"version\":\"" + reviewed + "\"")) {
                    return Optional.of(reviewed);
                }
            }
            return Optional.of(UNKNOWN_HOST_VERSION);
        } catch (IOException unavailable) {
            return Optional.empty();
        }
    }

    // ------------------------------------------------------- native hint evidence

    /**
     * Asks the host to show a hint under this probe's own key and reports whether the native surface
     * accepted it.
     *
     * <p>The production hint is drawn by the host and is not an AWT component, so a Swing walk cannot
     * see it. Driving the same SDK entry point with a probe-owned key is how the existing status-bar
     * probe establishes that the native route works on this host; recording it here separates a host
     * that cannot present hints from a host that simply had nothing to present.</p>
     */
    private boolean nativeHintSurfaceAcceptsHints() {
        try {
            final CanvasHintHandle handle = context.uiHost().notifyCanvasHint(
                new CanvasHintNotification(
                    HINT_PROBE_ID,
                    "Turboism update-check validation",
                    CanvasHintNotification.UNTIL_DISMISSED
                )
            );
            if (handle == null) return false;
            handle.renew();
            handle.close();
            logger.info("CANVAS_HINT_PROBE id=" + HINT_PROBE_ID + " accepted=true");
            return true;
        } catch (RuntimeException unavailable) {
            logger.warn("CANVAS_HINT_PROBE id=" + HINT_PROBE_ID + " accepted=false reason="
                + unavailable.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Reads the runtime's own log for the core plugin's presentation record.
     *
     * <p>The log is the surface core actually used to reach the native hint. This is weaker than
     * reading pixels, so the result is labelled as a recorded intent and the {@code demo} mode is
     * what a human confirms.</p>
     */
    private String awaitCoreHintSent(final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final String found = coreHintSentLine();
            if (found != null) return found;
            sleep(SETTLE_STEP_MILLIS);
        }
        return null;
    }

    private String coreHintSentLine() {
        final Path logs = home().resolve("logs/runtime");
        if (!Files.isDirectory(logs)) return null;
        try (Stream<Path> walk = Files.walk(logs)) {
            final List<Path> files = walk
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .sorted(Comparator.comparingLong(UpdateCheckHostValidationPlugin::lastModified).reversed())
                .toList();
            for (final Path file : files) {
                final String text = Files.readString(file);
                for (final String line : text.split("\r?\n")) {
                    if (line.contains(CORE_HINT_SENT_MARKER)) return line.trim();
                }
            }
        } catch (IOException | RuntimeException unavailable) {
            return null;
        }
        return null;
    }

    private static long lastModified(final Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException unavailable) {
            return 0L;
        }
    }

    // --------------------------------------------------------------- production state

    /** Reads the production store's own file. Returns null when no check ever committed a result. */
    private String readUpdateState() {
        try {
            return Files.readString(home().resolve(UPDATE_STATE_FILE));
        } catch (IOException unavailable) {
            return null;
        }
    }

    private String awaitCommittedUpdateState(final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            final String state = readUpdateState();
            if (state != null && state.contains("cachedResult")) return state;
            sleep(SETTLE_STEP_MILLIS);
        }
        return null;
    }

    // --------------------------------------------------------------- panel evidence

    private List<String> buttonNames() {
        final List<String> names = new ArrayList<>();
        onEdt(() -> {
            for (final Window window : Window.getWindows()) {
                walkButtonNames(window, names);
            }
            return null;
        });
        return names;
    }

    private void walkButtonNames(final Component component, final List<String> names) {
        if (component instanceof JButton button && button.getName() != null) {
            names.add(button.getName());
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                walkButtonNames(child, names);
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private Path home() {
        return stateDir.getParent().getParent();
    }

    private static String mode() {
        return System.getProperty(MODE_PROPERTY, "new");
    }

    /** The production store writes pretty-printed JSON; assertions compare it without whitespace. */
    private static String normalized(final String json) {
        return json == null ? null : json.replaceAll("\\s+", "");
    }

    private static String summarize(final String state) {
        final String compact = normalized(state);
        if (compact == null) return "<absent>";
        return compact.length() <= 400 ? compact : compact.substring(0, 400) + "\u2026";
    }

    private boolean writeResult(
        final boolean pass,
        final List<String> failures,
        final List<String> observations
    ) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("mode=").append(mode()).append('\n')
            .append("evidenceKind=")
            .append(DEMO_MODE.equals(mode()) ? "human-inspection-handoff" : "automated")
            .append('\n')
            .append("locale=").append(System.getProperty("turboism.locale", "host")).append('\n')
            .append("failures=").append(failures.size()).append('\n')
            .append("observations=").append(observations.size()).append('\n');
        for (int index = 0; index < failures.size(); index++) {
            result.append("failure.").append(index).append('=')
                .append(failures.get(index).replace('\n', ' ')).append('\n');
        }
        for (int index = 0; index < observations.size(); index++) {
            result.append("observation.").append(index).append('=')
                .append(observations.get(index).replace('\n', ' ')).append('\n');
        }
        try {
            Files.writeString(stateDir.resolve(RESULT), result.toString());
            return true;
        } catch (IOException failure) {
            return false;
        }
    }

    private <T> T onEdt(final java.util.concurrent.Callable<T> operation) {
        final java.util.concurrent.atomic.AtomicReference<T> result =
            new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Runnable runnable = () -> {
            try {
                result.set(operation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            runnable.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(runnable);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("update-check scan interrupted", interrupted);
            } catch (java.lang.reflect.InvocationTargetException exception) {
                throw new IllegalStateException("update-check scan failed on EDT", exception);
            }
        }
        if (failure.get() != null) {
            throw new IllegalStateException("update-check scan failed safely", failure.get());
        }
        return result.get();
    }

    private static void sleep(final long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
