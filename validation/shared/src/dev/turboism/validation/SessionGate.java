package dev.turboism.validation;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * Session-mode hooks and the shared trigger gate used by host-validation probes.
 *
 * <p>Unless {@code -Dturboism.validation.session=true} is set every session method here is inert and
 * probes keep their standalone behavior exactly. In session mode an auto-run probe waits for
 * its own go-flag under {@code state/session/go/<plugin-state-dir-name>.flag} before doing any
 * work, and its terminal path records a step-done marker under {@code state/session/steps/}
 * instead of exiting or halting the host process. The session coordinator plugin owns step
 * ordering, per-step timeouts, health checks and the final host shutdown.</p>
 *
 * <p>The go-flag file may carry {@code turboism.validation.*} key=value lines; they are applied
 * as system properties when the flag is observed so one plugin build can serve variant steps
 * (for example the PSD export observation profile of the texture probe).</p>
 *
 * <p>{@link #trigger(Path)} owns the one trigger wait every flag-gated probe needs: poll the
 * runner-written trigger file, run the probe body once the file appears, and end the host when
 * the standalone deadline passes without it. A probe supplies only its own log markers, poll
 * cadence and timeout action. The loop lives here because nine probe copies of it had already
 * drifted apart.</p>
 */
public final class SessionGate {
    public static final String ENABLED_PROPERTY = "turboism.validation.session";
    public static final long POLL_MILLIS = 1_000L;
    /** Trigger file name the generic runner writes into a probe's state directory. */
    public static final String TRIGGER_FLAG_NAME = "exerciser.flag";
    /** Standalone wait budget for the trigger file; the runner kills the host after it. */
    public static final long TRIGGER_TIMEOUT_MILLIS = 240_000L;
    /** Standalone trigger poll interval. */
    public static final long TRIGGER_POLL_MILLIS = 2_000L;
    /** Exit status a standalone probe reports when its trigger never arrives. */
    public static final int TRIGGER_TIMEOUT_HALT_CODE = 2;
    private static final String VALIDATION_PREFIX = "turboism.validation.";
    private static final String DEFAULT_TIMEOUT_MARKER = "PROBE_FLAG_TIMEOUT";
    private static final String DEFAULT_SESSION_ENDED_MARKER =
        "SESSION_GATE_FINISHED_WITHOUT_TRIGGER";

    private SessionGate() {}

    public static boolean enabled() {
        return Boolean.getBoolean(ENABLED_PROPERTY);
    }

    /** The {@code state/} root shared by all plugins: the parent of a per-plugin state dir. */
    public static Path stateRoot(final Path pluginStateDir) {
        final Path root = pluginStateDir.getParent();
        if (root == null) throw new IllegalStateException("plugin state directory has no state root");
        return root;
    }

    public static Path sessionDir(final Path pluginStateDir) {
        return stateRoot(pluginStateDir).resolve("session");
    }

    /** Written by the coordinator when the session terminates, so waiting probes return. */
    public static Path finishedFlag(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("finished.flag");
    }

    /** The coordinator's per-step go-flag for this plugin (keyed by its state dir name). */
    public static Path goFlag(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("go")
            .resolve(pluginStateDir.getFileName() + ".flag");
    }

    public static Path doneMarker(final Path pluginStateDir) {
        return sessionDir(pluginStateDir).resolve("steps")
            .resolve(pluginStateDir.getFileName() + ".done");
    }

    /**
     * Waits for this plugin's session go-flag. When observed, {@code turboism.validation.*}
     * properties carried in the flag file are applied and returned; {@code null} means the
     * session finished first and the caller must return without doing any work.
     */
    public static Properties awaitTurn(final Path pluginStateDir, final Consumer<String> log) {
        final Path flag = goFlag(pluginStateDir);
        final Path finished = finishedFlag(pluginStateDir);
        log.accept("SESSION_GATE_WAIT flag=" + flag);
        while (true) {
            if (Files.isRegularFile(flag)) {
                return readTriggerProperties(flag, log);
            }
            if (Files.isRegularFile(finished)) {
                log.accept("SESSION_GATE_FINISHED_WITHOUT_TURN flag=" + flag);
                return null;
            }
            try {
                Thread.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
    }

    /**
     * Starts the trigger wait of one probe. The caller configures the wait and calls
     * {@link Trigger#await()}; a {@code true} result means the probe body must run now.
     */
    public static Trigger trigger(final Path pluginStateDir) {
        return new Trigger(pluginStateDir);
    }

    /** Records the step outcome; session probes call this instead of exit/halt/closeIfEligible. */
    public static void complete(final Path pluginStateDir, final String status) {
        final Path marker = doneMarker(pluginStateDir);
        try {
            Files.createDirectories(marker.getParent());
            final Path pending = marker.resolveSibling(marker.getFileName() + ".pending");
            Files.writeString(pending, "status=" + status + "\n", StandardCharsets.UTF_8);
            Files.move(pending, marker, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            logFallback("SESSION_STEP_DONE_WRITE_FAILED " + marker + " " + failure);
        }
    }

    /**
     * Applies the {@code turboism.validation.*} lines a go-flag carries, so one plugin build can
     * serve variant session steps. A flag that cannot be read yields {@code null} and no property,
     * because a half-applied step profile would run the wrong variant.
     */
    private static Properties readTriggerProperties(final Path flag, final Consumer<String> log) {
        final Properties step = new Properties();
        try {
            step.load(new StringReader(Files.readString(flag, StandardCharsets.UTF_8)));
        } catch (Exception failure) {
            log.accept("SESSION_GATE_FLAG_UNREADABLE " + failure);
            return null;
        }
        for (String name : step.stringPropertyNames()) {
            if (name.startsWith(VALIDATION_PREFIX)) {
                System.setProperty(name, step.getProperty(name));
            }
        }
        log.accept("SESSION_GATE_GO flag=" + flag + " properties=" + step.stringPropertyNames());
        return step;
    }

    private static void logFallback(final String line) {
        System.err.println(line);
        System.err.flush();
    }

    /**
     * The trigger wait of one probe.
     *
     * <p>Standalone it polls the trigger file until the deadline, then runs {@link #onTimeout}.
     * That action defaults to halting the host with {@link #TRIGGER_TIMEOUT_HALT_CODE}, because a
     * probe without its trigger cannot publish evidence and the runner must not wait for the
     * result timeout. A probe that owes a result file first overrides the action.</p>
     *
     * <p>A session-aware probe ({@link #sessionAware()}) drops the deadline: the coordinator
     * grants the turn later, so the wait ends only on the trigger file or the session finished
     * flag.</p>
     */
    public static final class Trigger {
        private final Path pluginStateDir;
        private String flagFileName = TRIGGER_FLAG_NAME;
        private long timeoutMillis = TRIGGER_TIMEOUT_MILLIS;
        private long pollMillis = TRIGGER_POLL_MILLIS;
        private LongSupplier nanoClock = System::nanoTime;
        private Consumer<String> log = SessionGate::logFallback;
        private Consumer<String> warn = SessionGate::logFallback;
        private String timeoutMarker = DEFAULT_TIMEOUT_MARKER;
        private String sessionEndedMarker = DEFAULT_SESSION_ENDED_MARKER;
        private String triggerMarker;
        private boolean sessionAware;
        private Runnable beforePoll;
        private Runnable onTrigger;
        private Runnable onTimeout = () -> Runtime.getRuntime().halt(TRIGGER_TIMEOUT_HALT_CODE);

        private Trigger(final Path pluginStateDir) {
            this.pluginStateDir = pluginStateDir;
        }

        /** Overrides the trigger file name; every current probe keeps the shared default. */
        public Trigger flagFileName(final String name) {
            this.flagFileName = name;
            return this;
        }

        public Trigger timeoutMillis(final long millis) {
            this.timeoutMillis = millis;
            return this;
        }

        public Trigger pollMillis(final long millis) {
            this.pollMillis = millis;
            return this;
        }

        /** Replaces the deadline clock; the selection-lag probe injects its own test clock. */
        public Trigger nanoClock(final LongSupplier clock) {
            this.nanoClock = clock;
            return this;
        }

        public Trigger log(final Consumer<String> sink) {
            this.log = sink;
            return this;
        }

        public Trigger warn(final Consumer<String> sink) {
            this.warn = sink;
            return this;
        }

        /** The runner greps this marker to attribute a missing trigger to this probe. */
        public Trigger timeoutMarker(final String marker) {
            this.timeoutMarker = marker;
            return this;
        }

        /** Marker a session probe logs when the session ends before its turn. */
        public Trigger sessionEndedMarker(final String marker) {
            this.sessionEndedMarker = marker;
            return this;
        }

        /** Marker logged when the trigger file appears, before the probe body runs. */
        public Trigger triggerMarker(final String marker) {
            this.triggerMarker = marker;
            return this;
        }

        /** Work to repeat on every poll in either mode, such as answering a startup dialog. */
        public Trigger beforePoll(final Runnable action) {
            this.beforePoll = action;
            return this;
        }

        public Trigger onTrigger(final Runnable action) {
            this.onTrigger = action;
            return this;
        }

        public Trigger onTimeout(final Runnable action) {
            this.onTimeout = action;
            return this;
        }

        /**
         * Drops the standalone deadline when a session is active, so the wait ends only on the
         * trigger file or the session finished flag. Standalone behavior stays unchanged.
         */
        public Trigger sessionAware() {
            this.sessionAware = true;
            return this;
        }

        /**
         * Waits for the trigger. Returns {@code true} when the probe body must run, and
         * {@code false} when the wait ended without a turn: the session finished first, the
         * thread was interrupted, or the timeout action already ran.
         */
        public boolean await() {
            return sessionAware && SessionGate.enabled() ? awaitSessionTurn() : awaitStandalone();
        }

        private boolean awaitStandalone() {
            final Path flag = pluginStateDir.resolve(flagFileName);
            final long deadline = nanoClock.getAsLong() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
            while (nanoClock.getAsLong() < deadline) {
                fire(beforePoll);
                if (Files.isRegularFile(flag)) {
                    return triggerNow(flag);
                }
                if (!sleep()) {
                    return false;
                }
            }
            warn.accept(timeoutMarker + " flag=" + flag);
            fire(onTimeout);
            return false;
        }

        private boolean awaitSessionTurn() {
            final Path flag = pluginStateDir.resolve(flagFileName);
            final Path turn = goFlag(pluginStateDir);
            final Path finished = finishedFlag(pluginStateDir);
            while (true) {
                fire(beforePoll);
                if (Files.isRegularFile(flag)) {
                    return triggerNow(flag);
                }
                // A session step may be granted through the coordinator's go-flag instead of
                // the probe's own trigger file; its properties select the step variant.
                if (Files.isRegularFile(turn)) {
                    readTriggerProperties(turn, log);
                    return triggerNow(turn);
                }
                if (Files.isRegularFile(finished)) {
                    log.accept(sessionEndedMarker + " flag=" + flag);
                    return false;
                }
                if (!sleep()) {
                    return false;
                }
            }
        }

        private boolean triggerNow(final Path observed) {
            if (triggerMarker != null) {
                log.accept(triggerMarker + " flag=" + observed);
            }
            fire(onTrigger);
            return true;
        }

        private boolean sleep() {
            try {
                Thread.sleep(pollMillis);
                return true;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private static void fire(final Runnable action) {
            if (action != null) {
                action.run();
            }
        }
    }
}
