package dev.turboism.validation.session;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Offline self-test for {@link SessionRunner} and {@link SessionPlan}; simulates
 * probes as background threads that watch trigger files and publish result files.
 * Never loads or launches Cubism.
 */
public final class SessionRunnerSelfTest {
    private static final ConcurrentLinkedQueue<String> FAILURES = new ConcurrentLinkedQueue<>();

    public static void main(String[] args) throws Exception {
        planParsing();
        sequentialPass();
        stepTimeoutContinues();
        stepFailureContinues();
        healthCheckTerminates();
        startupAbort();
        if (!FAILURES.isEmpty()) {
            FAILURES.forEach(System.err::println);
            System.exit(1);
        }
        System.out.println("PASS: session runner self-test; not host evidence");
    }

    private static void planParsing() throws Exception {
        final String json = """
            {"format":"turboism.validation.session-plan","schemaVersion":1,
             "session":"standard","hostVersion":"5304","startupTimeoutSeconds":12,
             "steps":[
               {"name":"a","spec":"a:5304@standard","trigger":"state/session/go/a.flag",
                "result":"state/x/r.properties","timeoutSeconds":5,
                "triggerProperties":{"turboism.validation.k":"v"}},
               {"name":"b","spec":"b:5304","trigger":"state/session/go/b.flag",
                "result":"state/y/r.properties","timeoutSeconds":7}]}
            """;
        final SessionPlan.Plan plan = SessionPlan.parse(json);
        check(plan.steps().size() == 2, "plan step count");
        check(plan.startupTimeoutSeconds() == 12L, "startup timeout parsed");
        check("v".equals(plan.steps().get(0).triggerProperties().get("turboism.validation.k")),
                "trigger properties parsed");
        check(plan.steps().get(1).triggerProperties().isEmpty(), "missing triggerProperties -> empty");
        rejectsParse("{\"format\":\"other\",\"schemaVersion\":1,\"steps\":[]}", "bad format");
        rejectsParse("{\"format\":\"turboism.validation.session-plan\",\"schemaVersion\":1,"
                + "\"steps\":[{\"name\":\"a\",\"spec\":\"a:5304\",\"trigger\":\"../x\","
                + "\"result\":\"state/r\",\"timeoutSeconds\":1}]}", "path escape refused");
        rejectsParse("{\"format\":\"turboism.validation.session-plan\",\"schemaVersion\":1,"
                + "\"steps\":[{\"name\":\"a\",\"spec\":\"a:5304\",\"trigger\":\"state/t\","
                + "\"result\":\"state/r\",\"timeoutSeconds\":1},"
                + "{\"name\":\"a\",\"spec\":\"a:5304\",\"trigger\":\"state/t2\","
                + "\"result\":\"state/r2\",\"timeoutSeconds\":1}]}", "duplicate step refused");
    }

    private static void sequentialPass() throws Exception {
        final Path home = Files.createTempDirectory("session-pass");
        final ProbeSimulator one = new ProbeSimulator(home,
                "state/session/go/dev.a.flag", "state/a/result.properties", "PASS", 50);
        final ProbeSimulator two = new ProbeSimulator(home,
                "state/session/go/dev.b.flag", "state/b/result.properties", "PASS", 50);
        one.start();
        two.start();
        final SessionRunner.SessionOutcome outcome = SessionRunner.run(home, plan(
                step("one", "one:5304@standard", "state/session/go/dev.a.flag",
                        "state/a/result.properties", 30),
                step("two", "two:5304@standard", "state/session/go/dev.b.flag",
                        "state/b/result.properties", 30)),
                () -> null, Thread::sleep, m -> { });
        one.join();
        two.join();
        check("PASS".equals(outcome.status()), "all-pass aggregate is PASS");
        check(one.startedBefore(two), "steps run in plan order");
        check(Files.isRegularFile(home.resolve("state/session/steps/one-result.properties")),
                "step one archived");
        check(Files.isRegularFile(home.resolve("state/session/steps/two-result.properties")),
                "step two archived");
        check(aggregate(home).getProperty("step.1.status", "").equals("PASS"),
                "aggregate lists step status");
        check(Files.isRegularFile(home.resolve("state/session/finished.flag")),
                "finished flag written");
    }

    private static void stepTimeoutContinues() throws Exception {
        final Path home = Files.createTempDirectory("session-timeout");
        final ProbeSimulator one = new ProbeSimulator(home,
                "state/session/go/dev.a.flag", "state/a/result.properties", "PASS", 50);
        final ProbeSimulator three = new ProbeSimulator(home,
                "state/session/go/dev.c.flag", "state/c/result.properties", "PASS", 30);
        one.start();
        three.start(); // the middle step's probe never publishes
        final SessionRunner.SessionOutcome outcome = SessionRunner.run(home, plan(
                step("one", "one:5304", "state/session/go/dev.a.flag",
                        "state/a/result.properties", 30),
                step("stuck", "stuck:5304", "state/session/go/dev.stuck.flag",
                        "state/stuck/result.properties", 1),
                step("three", "three:5304", "state/session/go/dev.c.flag",
                        "state/c/result.properties", 30)),
                () -> null, Thread::sleep, m -> { });
        one.join();
        three.join();
        check("FAIL".equals(outcome.status()), "timeout makes aggregate FAIL");
        check("PASS".equals(outcome.steps().get(0).status()), "first step still PASS");
        check("FAIL".equals(outcome.steps().get(1).status()), "timed-out step is FAIL");
        check("PASS".equals(outcome.steps().get(2).status()),
                "later step still runs after a timeout");
        check(Files.isRegularFile(home.resolve("state/session/steps/stuck-result.properties")),
                "synthetic FAIL archived for timed-out step");
        check(aggregate(home).getProperty("step.1.name", "").equals("stuck"),
                "aggregate names the timed-out step");
    }

    private static void stepFailureContinues() throws Exception {
        final Path home = Files.createTempDirectory("session-fail");
        final ProbeSimulator bad = new ProbeSimulator(home,
                "state/session/go/dev.a.flag", "state/a/result.properties", "FAIL", 30);
        final ProbeSimulator good = new ProbeSimulator(home,
                "state/session/go/dev.b.flag", "state/b/result.properties", "PASS", 30);
        bad.start();
        good.start();
        final SessionRunner.SessionOutcome outcome = SessionRunner.run(home, plan(
                step("bad", "bad:5304", "state/session/go/dev.a.flag",
                        "state/a/result.properties", 30),
                step("good", "good:5304", "state/session/go/dev.b.flag",
                        "state/b/result.properties", 30)),
                () -> null, Thread::sleep, m -> { });
        bad.join();
        good.join();
        check("FAIL".equals(outcome.status()), "one FAIL step fails the session");
        check("PASS".equals(outcome.steps().get(1).status()), "session continues after step FAIL");
    }

    private static void healthCheckTerminates() throws Exception {
        final Path home = Files.createTempDirectory("session-health");
        final ProbeSimulator one = new ProbeSimulator(home,
                "state/session/go/dev.a.flag", "state/a/result.properties", "PASS", 30);
        final ProbeSimulator two = new ProbeSimulator(home,
                "state/session/go/dev.b.flag", "state/b/result.properties", "PASS", 30);
        one.start();
        two.start();
        // Step one's probe leaves a modal dialog behind: the health check fails
        // and every remaining step is recorded NOT_RUN without a trigger write.
        final SessionRunner.SessionOutcome outcome = SessionRunner.run(home, plan(
                step("one", "one:5304", "state/session/go/dev.a.flag",
                        "state/a/result.properties", 30),
                step("two", "two:5304", "state/session/go/dev.b.flag",
                        "state/b/result.properties", 30)),
                () -> "modal dialog visible", Thread::sleep, m -> { });
        one.join();
        two.join();
        check("FAIL".equals(outcome.status()), "health failure fails the session");
        check("PASS".equals(outcome.steps().get(0).status()), "healthy step keeps its verdict");
        check("NOT_RUN".equals(outcome.steps().get(1).status()), "remaining step is NOT_RUN");
        check(!Files.exists(home.resolve("state/session/go/dev.b.flag")),
                "later trigger never written after health failure");
        check(two.started == 0L, "later probe never ran");
    }

    private static void startupAbort() throws Exception {
        final Path home = Files.createTempDirectory("session-abort");
        final SessionRunner.SessionOutcome outcome = SessionRunner.abortAll(home, plan(
                step("one", "one:5304", "state/session/go/dev.a.flag",
                        "state/a/result.properties", 30)),
                "host never reached a usable document", m -> { });
        check("FAIL".equals(outcome.status()), "aborted session is FAIL");
        check("NOT_RUN".equals(outcome.steps().get(0).status()), "aborted step is NOT_RUN");
        check("NOT_RUN".equals(aggregate(home).getProperty("step.0.status")),
                "aggregate records NOT_RUN");
    }

    // -- helpers ---------------------------------------------------------

    private static SessionPlan.Step step(final String name, final String spec,
            final String trigger, final String result, final long timeout) {
        return new SessionPlan.Step(name, spec, trigger, result, timeout, Map.of());
    }

    private static SessionPlan.Plan plan(final SessionPlan.Step... steps) {
        return new SessionPlan.Plan("test-session", "5304", 60L, List.of(steps));
    }

    private static Properties aggregate(final Path home) throws Exception {
        final Properties props = new Properties();
        try (var input = Files.newInputStream(
                home.resolve("state/session/session-result.properties"))) {
            props.load(input);
        }
        return props;
    }

    private static void rejectsParse(final String json, final String label) {
        try {
            SessionPlan.parse(json);
            FAILURES.add("FAIL " + label + ": malformed plan was accepted");
        } catch (IllegalArgumentException expected) {
        }
    }

    private static void check(final boolean condition, final String label) {
        if (!condition) {
            FAILURES.add("FAIL " + label);
        }
    }

    /** Simulated probe: waits for its trigger file, then publishes the result file. */
    private static final class ProbeSimulator {
        private final Path home;
        private final Path trigger;
        private final Path result;
        private final String status;
        private final long delayMillis;
        private final Thread thread;
        private volatile long started;

        ProbeSimulator(final Path home, final String trigger, final String result,
                final String status, final long delayMillis) {
            this.home = home;
            this.trigger = home.resolve(trigger);
            this.result = home.resolve(result);
            this.status = status;
            this.delayMillis = delayMillis;
            this.thread = new Thread(this::poll, "probe-sim-" + this.trigger.getFileName());
            this.thread.setDaemon(true);
        }

        void start() {
            thread.start();
        }

        void join() throws InterruptedException {
            thread.join(15_000L);
        }

        boolean startedBefore(final ProbeSimulator other) {
            return started != 0L && other.started != 0L && started < other.started;
        }

        private void poll() {
            try {
                final Path finished = home.resolve("state/session/finished.flag");
                final long deadline = System.currentTimeMillis() + 20_000L;
                while (!Files.isRegularFile(trigger) && !Files.isRegularFile(finished)
                        && System.currentTimeMillis() < deadline) {
                    Thread.sleep(10L);
                }
                if (!Files.isRegularFile(trigger)) return;
                started = System.nanoTime();
                Thread.sleep(delayMillis);
                Files.createDirectories(result.getParent());
                Files.writeString(result, "schemaVersion=1\nstatus=" + status + "\n");
            } catch (Exception ignored) {
            }
        }
    }
}
