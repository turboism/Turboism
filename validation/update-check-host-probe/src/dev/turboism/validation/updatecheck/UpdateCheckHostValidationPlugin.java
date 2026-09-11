package dev.turboism.validation.updatecheck;

import dev.turboism.sdk.plugin.PluginContext;
import dev.turboism.sdk.plugin.PluginLogger;
import dev.turboism.sdk.plugin.TurboismPlugin;

import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Task-local exerciser for the stable update checker on an exact Cubism host.
 *
 * <p>This probe never replaces the production endpoint and never injects a fake
 * transport. It lets the real runtime perform its real request against the real
 * release API, then records two independent kinds of evidence:</p>
 *
 * <ol>
 *   <li>the decision the production service actually committed, read back from
 *       the {@code update-state.json} that the production store wrote;</li>
 *   <li>what the user would actually see, read from the live Swing component
 *       tree of the running host rather than asked of an API.</li>
 * </ol>
 *
 * <p>The validation wrapper pins the runtime locale to {@code en}, so the
 * expected rendered strings are exact rather than fuzzy matches.</p>
 *
 * <p>It only uses the public SDK and plain JDK AWT/Swing. It never imports or
 * reflects {@code com.live2d.*} types, opens a browser, mutates a document, or
 * writes outside its own state directory.</p>
 */
public final class UpdateCheckHostValidationPlugin implements TurboismPlugin {

    private static final String RESULT = "update-check-result.txt";
    private static final String MODE_PROPERTY = "turboism.validation.updateCheck.mode";
    private static final String EXPECT_TEXT_PROPERTY = "turboism.validation.updateCheck.expectText";
    private static final String EXPECT_BUILD_PROPERTY = "turboism.validation.updateCheck.expectBuild";

    /** Rendered panel control ids owned by the production core plugin, by {@code Component#getName()}. */
    private static final String CHECK_BUTTON_NAME = "turboism-update-check";
    private static final String DOWNLOAD_BUTTON_NAME = "turboism-update-download";

    /**
     * Every localized update-available message quotes the advertised identity, which always carries
     * this marker. Matching it keeps the "no false reminder" assertion locale-independent.
     */
    private static final String ADVERTISED_MARKER = "(Build ";

    private static final String UPDATE_STATE_FILE = "update-state.json";

    private static final long HOST_READY_TIMEOUT_MILLIS = 180_000L;
    private static final long DECISION_TIMEOUT_MILLIS = 90_000L;
    private static final long QUIET_WINDOW_MILLIS = 30_000L;
    private static final long SETTLE_STEP_MILLIS = 2_000L;
    private static final long PASS_SETTLE_MILLIS = 3_000L;

    /** Reviewed exact host versions the runtime report may advertise as READY. */
    private static final List<String> REVIEWED_HOST_VERSIONS = List.of("5.2.03", "5.3.02", "5.3.03");

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
        if (!awaitVerifiedHost()) {
            fail("readiness", "active-runtime-report-or-model-not-present");
            return;
        }
        logger.info("UPDATE_CHECK_EXERCISER_READY"
            + " hostVersion=" + activeReviewedRuntimeVersion().orElse("unknown")
            + " mode=" + mode()
            + " locale=" + System.getProperty("turboism.locale", "host"));

        final List<String> failures = new ArrayList<>();
        final List<String> observations = new ArrayList<>();
        try {
            switch (mode()) {
                case "new" -> assertUpdateOffered(failures, observations);
                case "current" -> assertUpToDateIsQuietThenManual(failures, observations);
                case "safe" -> assertSafeModeNeverRequests(failures, observations);
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
        sleep(PASS_SETTLE_MILLIS);
        Runtime.getRuntime().exit(pass ? 0 : 2);
    }

    private void fail(final String phase, final String detail) {
        writeResult(false, List.of(phase + ": " + detail), List.of());
        logger.warn("UPDATE_CHECK_EXERCISER_RESULT status=FAIL phase=" + phase + " detail=" + detail);
        Runtime.getRuntime().halt(2);
    }

    // ---------------------------------------------------------------- modes

    /**
     * The installed bundle is deliberately older than the published stable build, so the real
     * request must resolve to an update and render the reminder without any probe-driven action.
     */
    private void assertUpdateOffered(final List<String> failures, final List<String> observations) {
        final String expectedText = expectedText();
        final String expectedBuild = System.getProperty(EXPECT_BUILD_PROPERTY, "");

        final String rendered = awaitLabelEqual(expectedText, DECISION_TIMEOUT_MILLIS);
        if (rendered == null) {
            failures.add("no rendered update reminder equal to '" + expectedText + "' appeared within "
                + DECISION_TIMEOUT_MILLIS + "ms");
        } else {
            observations.add("renderedReminder=" + rendered);
            if (!expectedBuild.isEmpty() && !rendered.contains(ADVERTISED_MARKER + expectedBuild + ")")) {
                failures.add("rendered reminder omits build " + expectedBuild + ": " + rendered);
            }
        }

        final String state = normalized(readUpdateState());
        if (state == null) {
            failures.add("the runtime committed no " + UPDATE_STATE_FILE + " after a ready check");
            return;
        }
        observations.add("committedUpdateState=" + summarize(readUpdateState()));
        if (!state.contains("\"status\":\"ready\"")) {
            failures.add("committed update state is not a ready document");
        }
        if (!expectedBuild.isEmpty() && !state.contains("\"buildNumber\":" + expectedBuild)) {
            failures.add("committed update state does not carry buildNumber " + expectedBuild);
        }
        recordPanelControls(observations);
    }

    /**
     * The installed bundle matches the published stable version and carries no build number, so the
     * automatic check must commit a ready decision and stay quiet; a manual check must then render a
     * new result.
     */
    private void assertUpToDateIsQuietThenManual(
        final List<String> failures,
        final List<String> observations
    ) {
        final String state = normalized(awaitCommittedUpdateState(DECISION_TIMEOUT_MILLIS));
        if (state == null) {
            failures.add("the automatic check never committed " + UPDATE_STATE_FILE);
        } else {
            observations.add("committedUpdateState=" + summarize(readUpdateState()));
            if (!state.contains("\"status\":\"ready\"")) {
                failures.add("committed update state is not a ready document");
            }
        }

        assertNoFalseReminder(failures, observations);
        assertManualCheckRenders(failures, observations);
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

        assertNoFalseReminder(failures, observations);
        assertManualCheckRenders(failures, observations);
    }

    private void assertNoFalseReminder(final List<String> failures, final List<String> observations) {
        final String falselyOffered = awaitLabelContaining(ADVERTISED_MARKER, QUIET_WINDOW_MILLIS);
        if (falselyOffered != null) {
            failures.add("a non-update installation was falsely told an update exists: "
                + falselyOffered);
        } else {
            observations.add("noFalseReminder=true");
        }
    }

    /**
     * Exercises the rendered control through the real handler and requires the click to add one
     * more rendered instance of the expected result text (the status notification), on top of
     * whatever the panel already shows.
     */
    private void assertManualCheckRenders(final List<String> failures, final List<String> observations) {
        final String expectedText = expectedText();
        final JButton check = findButton(CHECK_BUTTON_NAME);
        if (check == null) {
            failures.add("the update panel check control is not present in the host window tree");
            return;
        }
        observations.add("panelCheckButton=present");
        final int before = countLabelsEqual(expectedText);
        observations.add("labelsEqualToExpectedBeforeClick=" + before);

        final boolean clicked = clickButton(check);
        observations.add("panelCheckButtonClicked=" + clicked);
        if (!clicked) {
            failures.add("the rendered check control could not be clicked on the EDT");
            return;
        }

        final long deadline = System.currentTimeMillis() + DECISION_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            final int after = countLabelsEqual(expectedText);
            if (after > before) {
                observations.add("labelsEqualToExpectedAfterClick=" + after);
                observations.add("renderedManualResult=" + expectedText);
                return;
            }
            sleep(SETTLE_STEP_MILLIS);
        }
        failures.add("a manual check rendered no new result equal to '" + expectedText
            + "'; observed labels=" + String.join(" | ", labelTexts()));
    }

    // ------------------------------------------------------------- readiness

    private boolean awaitVerifiedHost() {
        final long deadline = System.currentTimeMillis() + HOST_READY_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (activeReviewedRuntimeVersion().isPresent()
                    && !context.cubism().model().active().id().value().isBlank()) {
                    return true;
                }
            } catch (RuntimeException unavailable) {
                // Readiness is retried until the deadline; an unavailable Cubism read is not fatal yet.
            }
            sleep(SETTLE_STEP_MILLIS);
        }
        return false;
    }

    private java.util.Optional<String> activeReviewedRuntimeVersion() {
        final Path report = stateDir.getParent().resolve("runtime/preview-runtime-report.json");
        try {
            final String json = Files.readString(report);
            final boolean ready = json.contains("\"identityState\":\"MATCHED\"")
                && json.contains("\"adapterState\":\"READY\"")
                && json.contains("\"runtimeState\":\"RUNNING\"");
            if (!ready) {
                return java.util.Optional.empty();
            }
            for (final String reviewed : REVIEWED_HOST_VERSIONS) {
                if (json.contains("\"version\":\"" + reviewed + "\"")) {
                    return java.util.Optional.of(reviewed);
                }
            }
            return java.util.Optional.empty();
        } catch (java.io.IOException unavailable) {
            return java.util.Optional.empty();
        }
    }

    // --------------------------------------------------------------- evidence

    /** Reads the production store's own file. Returns null when no check ever committed a result. */
    private String readUpdateState() {
        try {
            return Files.readString(home().resolve(UPDATE_STATE_FILE));
        } catch (java.io.IOException unavailable) {
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

    private String awaitLabelEqual(final String text, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (final String candidate : labelTexts()) {
                if (text.equals(candidate)) return candidate;
            }
            sleep(SETTLE_STEP_MILLIS);
        }
        return null;
    }

    private String awaitLabelContaining(final String needle, final long timeoutMillis) {
        final long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            for (final String candidate : labelTexts()) {
                if (candidate.contains(needle)) return candidate;
            }
            sleep(SETTLE_STEP_MILLIS);
        }
        return null;
    }

    private int countLabelsEqual(final String text) {
        int count = 0;
        for (final String candidate : labelTexts()) {
            if (text.equals(candidate)) count++;
        }
        return count;
    }

    private JButton findButton(final String name) {
        final List<JButton> found = new ArrayList<>();
        onEdt(() -> {
            for (final Window window : Window.getWindows()) {
                walkButtons(window, name, found);
            }
            return null;
        });
        return found.isEmpty() ? null : found.get(0);
    }

    private boolean clickButton(final JButton button) {
        try {
            onEdt(() -> {
                button.doClick();
                return null;
            });
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private void recordPanelControls(final List<String> observations) {
        final List<String> names = new ArrayList<>();
        onEdt(() -> {
            for (final Window window : Window.getWindows()) {
                walkButtonNames(window, names);
            }
            return null;
        });
        observations.add("panelDownloadButton="
            + (names.contains(DOWNLOAD_BUTTON_NAME) ? "present" : "absent"));
        observations.add("panelCheckButton="
            + (names.contains(CHECK_BUTTON_NAME) ? "present" : "absent"));
    }

    private List<String> labelTexts() {
        final List<String> texts = new ArrayList<>();
        onEdt(() -> {
            for (final Window window : Window.getWindows()) {
                walkLabels(window, texts);
            }
            return null;
        });
        return texts;
    }

    private void walkLabels(final Component component, final List<String> texts) {
        if (component instanceof JLabel label) {
            final String text = label.getText();
            if (text != null && !text.isBlank()) texts.add(text);
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                walkLabels(child, texts);
            }
        }
    }

    private void walkButtons(final Component component, final String name, final List<JButton> found) {
        if (component instanceof JButton button && name.equals(button.getName())) {
            found.add(button);
        }
        if (component instanceof Container container) {
            for (final Component child : container.getComponents()) {
                walkButtons(child, name, found);
            }
        }
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

    private static String expectedText() {
        return System.getProperty(EXPECT_TEXT_PROPERTY, "");
    }

    /** The production store writes pretty-printed JSON; assertions compare it without whitespace. */
    private static String normalized(final String json) {
        return json == null ? null : json.replaceAll("\\s+", "");
    }

    private static String summarize(final String state) {
        final String compact = normalized(state);
        if (compact == null) return "<absent>";
        return compact.length() <= 400 ? compact : compact.substring(0, 400) + "…";
    }

    private boolean writeResult(
        final boolean pass,
        final List<String> failures,
        final List<String> observations
    ) {
        final StringBuilder result = new StringBuilder()
            .append("status=").append(pass ? "PASS" : "FAIL").append('\n')
            .append("mode=").append(mode()).append('\n')
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
        } catch (java.io.IOException failure) {
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
