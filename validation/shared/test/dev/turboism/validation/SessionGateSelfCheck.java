package dev.turboism.validation;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Offline contract check for the shared {@link SessionGate} trigger wait.
 *
 * <p>Run with {@code validation/shared/check-session-gate.sh}. Every case drives a temporary
 * state directory and an injected clock, so no Cubism host, SDK jar or real timeout is involved.
 * The check pins the behavior nine probes used to implement themselves: the standalone deadline
 * fires the timeout action exactly once, the trigger file runs the body once, the session mode
 * ignores the deadline and ends on the finished flag, and a go-flag applies its properties.</p>
 */
public final class SessionGateSelfCheck {
    private static final long START_NANOS = 1_000L;
    private static final long STEP_NANOS = 1_000_000L;
    private static final long TEST_TIMEOUT_MILLIS = 5L;
    /** A session wait has no deadline, so the case itself bounds the polls it tolerates. */
    private static final long MAX_POLLS = 20L;
    private static final String STEP_PROPERTY = "turboism.validation.selfcheck.step";
    private static int checked;

    private SessionGateSelfCheck() {}

    public static void main(final String[] args) throws Exception {
        final Path home = Files.createTempDirectory("session-gate-selfcheck");
        try {
            check("session-off-by-default", !SessionGate.enabled());
            // SessionGate.stateRoot is the parent of a plugin state dir, so every case needs
            // its own home: a finished flag written by one case must not leak into the next.
            standaloneTimeoutFiresTheActionOnce(caseDir(home, "timeout"));
            standaloneTriggerRunsTheBodyOnce(caseDir(home, "trigger"));
            standalonePollActionRunsBeforeEachCheck(caseDir(home, "before-poll"));
            sessionModeIgnoresTheDeadline(caseDir(home, "session-finished"));
            sessionModeRunsOnTheGoFlag(caseDir(home, "session-go"));
            awaitTurnReturnsNullWhenTheSessionFinished(caseDir(home, "await-turn"));
            completeWritesTheStepStatus(caseDir(home, "complete"));
        } finally {
            System.clearProperty(SessionGate.ENABLED_PROPERTY);
            System.clearProperty(STEP_PROPERTY);
            deleteTree(home);
        }
        System.out.println("PASS: SessionGateSelfCheck checks=" + checked);
    }

    /** Creates an isolated {@code <home>/state/<plugin>} plugin state directory for one case. */
    private static Path caseDir(final Path home, final String name) throws Exception {
        final Path pluginStateDir = home.resolve(name).resolve("state").resolve(name);
        Files.createDirectories(pluginStateDir);
        return pluginStateDir;
    }

    /** A probe without its trigger must publish its timeout action, not wait forever. */
    private static void standaloneTimeoutFiresTheActionOnce(final Path pluginStateDir)
            throws Exception {
        Files.createDirectories(pluginStateDir);
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final List<String> warnings = new ArrayList<>();
        final boolean entered = standalone(pluginStateDir, clock)
            .timeoutMarker("SELFCHECK_TIMEOUT")
            .warn(warnings::add)
            .onTimeout(() -> timedOut.set(true))
            .await();
        check("timeout-does-not-enter", !entered);
        check("timeout-action-ran-once", timedOut.get());
        check("timeout-warning-count", warnings.size() == 1);
        check("timeout-warning-marker", warnings.get(0).startsWith("SELFCHECK_TIMEOUT flag="));
    }

    /** The trigger file runs the body once and reports entry. */
    private static void standaloneTriggerRunsTheBodyOnce(final Path pluginStateDir)
            throws Exception {
        Files.createDirectories(pluginStateDir);
        writeQuietly(pluginStateDir.resolve(SessionGate.TRIGGER_FLAG_NAME), "");
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final AtomicBoolean ran = new AtomicBoolean(false);
        final boolean entered = standalone(pluginStateDir, clock)
            .onTrigger(() -> ran.set(true))
            .await();
        check("trigger-enters", entered);
        check("trigger-ran-body", ran.get());
    }

    /** The per-poll action must run in standalone mode too, not only in a session. */
    private static void standalonePollActionRunsBeforeEachCheck(final Path pluginStateDir)
            throws Exception {
        Files.createDirectories(pluginStateDir);
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final AtomicLong polls = new AtomicLong(0L);
        final Path flag = pluginStateDir.resolve(SessionGate.TRIGGER_FLAG_NAME);
        final boolean entered = standalone(pluginStateDir, clock)
            .beforePoll(() -> {
                if (polls.incrementAndGet() == 2L) {
                    writeQuietly(flag, "");
                }
            })
            .onTrigger(() -> {})
            .await();
        check("before-poll-enters", entered);
        check("before-poll-ran", polls.get() == 2L);
    }

    /** A session grants the turn later, so the standalone deadline must not end the wait. */
    private static void sessionModeIgnoresTheDeadline(final Path pluginStateDir)
            throws Exception {
        Files.createDirectories(pluginStateDir);
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final AtomicBoolean timedOut = new AtomicBoolean(false);
        final List<String> logs = new ArrayList<>();
        final boolean entered = session(pluginStateDir, clock)
            .log(logs::add)
            .beforePoll(() -> {
                clock.addAndGet(STEP_NANOS);
                // The finished flag appears long after the standalone deadline passed.
                if (clock.get() > START_NANOS + 10L * STEP_NANOS) {
                    writeQuietly(SessionGate.finishedFlag(pluginStateDir), "");
                }
                checkPollBudget("session-finished", logs.size());
            })
            .onTimeout(() -> timedOut.set(true))
            .await();
        check("session-finished-does-not-enter", !entered);
        check("session-finished-skips-timeout", !timedOut.get());
        check("session-finished-marker", logs.stream().anyMatch(
            line -> line.startsWith("SESSION_GATE_FINISHED_WITHOUT_TRIGGER")));
    }

    /** The coordinator's go-flag is a turn: it applies its properties and enters the body. */
    private static void sessionModeRunsOnTheGoFlag(final Path pluginStateDir) throws Exception {
        Files.createDirectories(pluginStateDir);
        final AtomicLong clock = new AtomicLong(START_NANOS);
        final AtomicLong polls = new AtomicLong(0L);
        final AtomicBoolean ran = new AtomicBoolean(false);
        final Path turn = SessionGate.goFlag(pluginStateDir);
        final boolean entered = session(pluginStateDir, clock)
            .beforePoll(() -> {
                clock.addAndGet(STEP_NANOS);
                if (polls.incrementAndGet() == 3L) {
                    writeQuietly(turn, STEP_PROPERTY + "=go\n");
                }
                checkPollBudget("session-go", polls.get());
            })
            .onTrigger(() -> ran.set(true))
            .await();
        check("session-go-enters", entered);
        check("session-go-ran-body", ran.get());
        check("session-go-applies-properties", "go".equals(System.getProperty(STEP_PROPERTY)));
    }

    /** {@link SessionGate#awaitTurn} must report the finished flag as "no turn". */
    private static void awaitTurnReturnsNullWhenTheSessionFinished(final Path pluginStateDir)
            throws Exception {
        Files.createDirectories(pluginStateDir);
        writeQuietly(SessionGate.finishedFlag(pluginStateDir), "");
        final List<String> logs = new ArrayList<>();
        check("await-turn-null", SessionGate.awaitTurn(pluginStateDir, logs::add) == null);
        check("await-turn-marker", logs.contains(
            "SESSION_GATE_FINISHED_WITHOUT_TURN flag=" + SessionGate.goFlag(pluginStateDir)));
    }

    /** The step-done marker carries the status the coordinator reads. */
    private static void completeWritesTheStepStatus(final Path pluginStateDir) throws Exception {
        Files.createDirectories(pluginStateDir);
        SessionGate.complete(pluginStateDir, "PASS");
        final Path marker = SessionGate.doneMarker(pluginStateDir);
        check("complete-marker-exists", Files.isRegularFile(marker));
        check("complete-marker-status",
            "status=PASS".equals(Files.readString(marker, StandardCharsets.UTF_8).strip()));
        check("complete-leaves-no-pending",
            !Files.exists(marker.resolveSibling(marker.getFileName() + ".pending")));
    }

    /** A standalone wait whose clock advances one step per read, so the deadline is reached. */
    private static SessionGate.Trigger standalone(final Path pluginStateDir, final AtomicLong clock) {
        return SessionGate.trigger(pluginStateDir)
            .nanoClock(() -> clock.addAndGet(STEP_NANOS))
            .timeoutMillis(TEST_TIMEOUT_MILLIS)
            .pollMillis(0L);
    }

    /** A session-aware wait: the deadline is dropped, so the clock only paces the case. */
    private static SessionGate.Trigger session(final Path pluginStateDir, final AtomicLong clock) {
        System.setProperty(SessionGate.ENABLED_PROPERTY, "true");
        return SessionGate.trigger(pluginStateDir)
            .sessionAware()
            .nanoClock(clock::get)
            .timeoutMillis(TEST_TIMEOUT_MILLIS)
            .pollMillis(0L);
    }

    /**
     * Ends a session case that never reaches its turn. A session wait has no deadline, so a
     * regression would otherwise spin forever and hang the gate instead of reporting a failure.
     */
    private static void checkPollBudget(final String name, final long polls) {
        if (polls > MAX_POLLS) {
            throw new AssertionError("FAILED: " + name + " never reached its turn within "
                + MAX_POLLS + " polls");
        }
    }

    private static void writeQuietly(final Path path, final String content) {
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, content, StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException(failure);
        }
    }

    private static void check(final String name, final boolean condition) {
        checked++;
        if (!condition) {
            throw new AssertionError("FAILED: " + name);
        }
    }

    private static void deleteTree(final Path root) throws Exception {
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            for (final Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }
}
