package dev.turboism.validation.session;

import java.io.StringReader;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.function.Consumer;

/**
 * Drives one multi-probe session inside a single exact-host Cubism launch.
 *
 * <p>For each plan step the runner writes the step's trigger file, polls the step's
 * result file up to the per-step timeout, archives the result into
 * {@code state/session/steps/<name>-result.properties} and runs a session health
 * check before continuing. A step timeout or missing verdict is recorded as a
 * synthetic FAIL and the session continues; a failed health check terminates the
 * session and marks every remaining step NOT_RUN. The aggregate verdict lands in
 * {@code state/session/session-result.properties} and is PASS only when every
 * step observed PASS.</p>
 */
public final class SessionRunner {
    /** Returns null while the host is healthy enough to continue the session. */
    public interface HealthCheck {
        String check() throws Exception;
    }

    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    public record StepOutcome(String name, String spec, String status,
                              long durationMillis, String detail) {}

    public record SessionOutcome(String status, List<StepOutcome> steps) {}

    private static final long POLL_MILLIS = 1_000L;
    private static final String AGGREGATE = "state/session/session-result.properties";

    private SessionRunner() {}

    public static Path sessionDir(final Path home) {
        return home.resolve("state").resolve("session");
    }

    public static Path stepsDir(final Path home) {
        return sessionDir(home).resolve("steps");
    }

    public static Path finishedFlag(final Path home) {
        return sessionDir(home).resolve("finished.flag");
    }

    public static SessionOutcome run(final Path home, final SessionPlan.Plan plan,
            final HealthCheck health, final Sleeper sleeper, final Consumer<String> log) {
        final List<StepOutcome> steps = new ArrayList<>();
        boolean terminated = false;
        for (final SessionPlan.Step step : plan.steps()) {
            if (terminated) {
                steps.add(new StepOutcome(step.name(), step.spec(), "NOT_RUN", 0L,
                        "session terminated by an earlier health check"));
                continue;
            }
            final long started = System.nanoTime();
            final StepOutcome outcome = runStep(home, step, sleeper, log, started);
            steps.add(outcome);
            if (!"PASS".equals(outcome.status())) {
                log.accept("SESSION_STEP_FAILED step=" + step.name() + " status=" + outcome.status()
                        + " detail=" + outcome.detail());
            }
            final String unhealthy = healthCheck(health, step.name(), log);
            if (unhealthy != null) {
                log.accept("SESSION_HEALTH_FAILED after=" + step.name() + " reason=" + unhealthy);
                terminated = true;
            }
        }
        final SessionOutcome outcome = aggregate(steps);
        try {
            writeAggregate(home, plan, outcome, log);
        } catch (Exception failure) {
            log.accept("SESSION_RESULT_WRITE_FAILED " + failure);
        }
        return outcome;
    }

    private static StepOutcome runStep(final Path home, final SessionPlan.Step step,
            final Sleeper sleeper, final Consumer<String> log, final long started) {
        final Path result = home.resolve(step.result());
        final Path archive = stepsDir(home).resolve(step.name() + "-result.properties");
        try {
            Files.deleteIfExists(result);
            writeTrigger(home, step);
        } catch (Exception failure) {
            recordSynthetic(step, "step-trigger-failed", String.valueOf(failure), archive);
            return new StepOutcome(step.name(), step.spec(), "FAIL",
                    elapsedMillis(started), "trigger write failed: " + failure);
        }
        final long deadline = System.nanoTime()
                + java.time.Duration.ofSeconds(step.timeoutSeconds()).toNanos();
        while (System.nanoTime() < deadline) {
            final String status = terminalStatus(result);
            if (status != null) {
                archive(home, step, result, archive, log);
                return new StepOutcome(step.name(), step.spec(), status,
                        elapsedMillis(started), "result=" + step.result());
            }
            try {
                sleeper.sleep(POLL_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                recordSynthetic(step, "step-interrupted", "result wait interrupted", archive);
                return new StepOutcome(step.name(), step.spec(), "FAIL",
                        elapsedMillis(started), "interrupted while polling " + step.result());
            }
        }
        recordSynthetic(step, "step-timeout",
                "no terminal status within " + step.timeoutSeconds() + "s", archive);
        return new StepOutcome(step.name(), step.spec(), "FAIL",
                elapsedMillis(started), "timeout waiting for " + step.result());
    }

    private static void writeTrigger(final Path home, final SessionPlan.Step step) throws Exception {
        final Path trigger = home.resolve(step.trigger());
        Files.createDirectories(trigger.getParent());
        final StringBuilder content = new StringBuilder();
        for (final var entry : step.triggerProperties().entrySet()) {
            content.append(entry.getKey()).append('=')
                .append(entry.getValue().replace("\\", "\\\\")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t"))
                .append('\n');
        }
        final Path pending = trigger.resolveSibling(trigger.getFileName() + ".pending");
        Files.writeString(pending, content.toString(), StandardCharsets.UTF_8);
        Files.move(pending, trigger, StandardCopyOption.REPLACE_EXISTING);
    }

    /** The probe's terminal verdict line, or null while the step is still running. */
    private static String terminalStatus(final Path result) {
        if (!Files.isRegularFile(result)) {
            return null;
        }
        try {
            for (final String line : Files.readAllLines(result, StandardCharsets.UTF_8)) {
                if (line.startsWith("status=")) {
                    final String status = line.substring("status=".length()).trim();
                    return status.isEmpty() ? null : status;
                }
            }
        } catch (Exception ignored) {
            // A partially written result file is re-read on the next poll.
        }
        return null;
    }

    private static void archive(final Path home, final SessionPlan.Step step,
            final Path result, final Path archive, final Consumer<String> log) {
        try {
            Files.createDirectories(archive.getParent());
            Files.copy(result, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception failure) {
            log.accept("SESSION_STEP_ARCHIVE_FAILED step=" + step.name() + " " + failure);
        }
    }

    private static void recordSynthetic(final SessionPlan.Step step,
            final String reason, final String detail, final Path archive) {
        final Properties synthetic = new Properties();
        synthetic.setProperty("schemaVersion", "1");
        synthetic.setProperty("status", "FAIL");
        synthetic.setProperty("session.synthetic", reason);
        synthetic.setProperty("session.step", step.name());
        synthetic.setProperty("session.spec", step.spec());
        synthetic.setProperty("session.detail", detail.replace('\n', ' ').replace('\r', ' '));
        try {
            Files.createDirectories(archive.getParent());
            final var output = new StringWriter();
            synthetic.store(output, "session coordinator synthetic step result");
            final Path pending = archive.resolveSibling(archive.getFileName() + ".pending");
            Files.writeString(pending, output.toString(), StandardCharsets.UTF_8);
            Files.move(pending, archive, StandardCopyOption.REPLACE_EXISTING);
        } catch (Exception ignored) {
            // The aggregate file still records the step verdict; never abort on IO here.
        }
    }

    /** Aggregate FAIL without any steps (plan unreadable); also drops finished.flag. */
    public static void writeFailedAggregate(final Path home, final String detail,
            final Consumer<String> log) {
        final Properties aggregate = new Properties();
        aggregate.setProperty("schemaVersion", "1");
        aggregate.setProperty("session", "standard");
        aggregate.setProperty("status", "FAIL");
        aggregate.setProperty("steps", "0");
        aggregate.setProperty("session.detail", detail.replace('\n', ' ').replace('\r', ' '));
        aggregate.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
        try {
            final Path target = home.resolve(AGGREGATE);
            Files.createDirectories(target.getParent());
            final var output = new StringWriter();
            aggregate.store(output, "multi-probe session aggregate");
            final Path pending = target.resolveSibling(target.getFileName() + ".pending");
            Files.writeString(pending, output.toString(), StandardCharsets.UTF_8);
            Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
            Files.writeString(finishedFlag(home), "status=FAIL\n", StandardCharsets.UTF_8);
        } catch (Exception failure) {
            log.accept("SESSION_RESULT_WRITE_FAILED " + failure);
        }
    }

    /** Every step NOT_RUN without triggering anything (startup never became usable). */
    public static SessionOutcome abortAll(final Path home, final SessionPlan.Plan plan,
            final String detail, final Consumer<String> log) {
        final List<StepOutcome> steps = new ArrayList<>();
        for (final SessionPlan.Step step : plan.steps()) {
            steps.add(new StepOutcome(step.name(), step.spec(), "NOT_RUN", 0L, detail));
        }
        final SessionOutcome outcome = new SessionOutcome("FAIL", List.copyOf(steps));
        try {
            writeAggregate(home, plan, outcome, log);
        } catch (Exception failure) {
            log.accept("SESSION_RESULT_WRITE_FAILED " + failure);
        }
        return outcome;
    }

    private static String healthCheck(final HealthCheck health, final String step,
            final Consumer<String> log) {
        try {
            return health.check();
        } catch (Exception failure) {
            return "health check threw " + failure;
        }
    }

    private static SessionOutcome aggregate(final List<StepOutcome> steps) {
        boolean pass = true;
        for (final StepOutcome step : steps) {
            if (!"PASS".equals(step.status())) {
                pass = false;
                break;
            }
        }
        return new SessionOutcome(pass ? "PASS" : "FAIL", List.copyOf(steps));
    }

    private static void writeAggregate(final Path home, final SessionPlan.Plan plan,
            final SessionOutcome outcome, final Consumer<String> log) throws Exception {
        final Properties aggregate = new Properties();
        aggregate.setProperty("schemaVersion", "1");
        aggregate.setProperty("session", plan.session());
        aggregate.setProperty("hostVersion", plan.hostVersion());
        aggregate.setProperty("runId", System.getProperty("turboism.validation.runId", ""));
        aggregate.setProperty("status", outcome.status());
        aggregate.setProperty("steps", Integer.toString(outcome.steps().size()));
        for (int index = 0; index < outcome.steps().size(); index++) {
            final StepOutcome step = outcome.steps().get(index);
            final String prefix = "step." + index + ".";
            aggregate.setProperty(prefix + "name", step.name());
            aggregate.setProperty(prefix + "spec", step.spec());
            aggregate.setProperty(prefix + "status", step.status());
            aggregate.setProperty(prefix + "durationMillis", Long.toString(step.durationMillis()));
            aggregate.setProperty(prefix + "detail", step.detail());
            aggregate.setProperty(prefix + "result",
                    "state/session/steps/" + step.name() + "-result.properties");
        }
        final Path target = home.resolve(AGGREGATE);
        Files.createDirectories(target.getParent());
        final var output = new StringWriter();
        aggregate.store(output, "multi-probe session aggregate; per-step evidence under state/session/steps/");
        final Path pending = target.resolveSibling(target.getFileName() + ".pending");
        Files.writeString(pending, output.toString(), StandardCharsets.UTF_8);
        Files.move(pending, target, StandardCopyOption.REPLACE_EXISTING);
        Files.writeString(finishedFlag(home), "status=" + outcome.status() + "\n",
                StandardCharsets.UTF_8);
        log.accept("SESSION_RESULT status=" + outcome.status() + " steps=" + outcome.steps().size());
    }

    private static long elapsedMillis(final long started) {
        return (System.nanoTime() - started) / 1_000_000L;
    }
}
