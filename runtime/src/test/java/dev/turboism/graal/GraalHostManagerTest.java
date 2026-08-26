package dev.turboism.graal;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class GraalHostManagerTest {

    @Test
    void submitReturnsBeforeAHostThatNeverReportsReadyTimesOut() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(NeverReadyHost.class, diagnostics, 200L)) {
            final long started = System.nanoTime();
            final GraalHostManager.Execution execution = manager.submit(
                "startup", "", Map.of(), (operation, payload) -> "{}"
            );
            final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertTrue(elapsedMillis < 100L, "submit blocked for " + elapsedMillis + "ms");
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.REJECTED, result.status());
            assertEquals("GRAAL_HOST_UNAVAILABLE", result.code());
        }
    }

    @Test
    void cancelBeforeStartupCompletesSettlesWithoutWaitingForTheHost() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(NeverReadyHost.class, diagnostics, 5_000L)) {
            final GraalHostManager.Execution execution = manager.submit(
                "cancel-startup", "", Map.of(), (operation, payload) -> "{}"
            );

            assertTrue(execution.cancel());
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(200, TimeUnit.MILLISECONDS);

            assertEquals(GraalHostManager.Status.CANCELLED, result.status());
            assertEquals("SCRIPT_CANCELLED", result.code());
        }
    }

    @Test
    void transportInvalidationSettlesExecutionsAlreadyOwnedByTheHost() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(InputClosingHost.class, diagnostics)) {
            final GraalHostManager.Execution first = manager.submit(
                "first", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "INPUT_CLOSED");

            final GraalHostManager.Execution second = manager.submit(
                "second", "", Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult secondResult = second.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            final GraalHostManager.TransportResult firstResult = first.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.FAILED, secondResult.status());
            assertEquals("GRAAL_HOST_WRITE_FAILED", secondResult.code());
            assertEquals(GraalHostManager.Status.FAILED, firstResult.status());
            assertEquals("GRAAL_HOST_CRASHED", firstResult.code());
        }
    }

    @Test
    void cancelSettlesWhenTheHostCannotReceiveTheRequest() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(InputClosingHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "cancel", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "INPUT_CLOSED");

            assertTrue(execution.cancel());
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.CANCELLED, result.status());
            assertEquals("SCRIPT_CANCELLED", result.code());
            assertTrue(!execution.cancel());
        }
    }

    @Test
    void aStaleReaderCannotFailAnExecutionOwnedByTheReplacementHost() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(ReplacementRaceHost.class, diagnostics)) {
            final GraalHostManager.Execution first = manager.submit(
                "first", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "FIRST_INPUT_CLOSED");

            final GraalHostManager.Execution invalidating = manager.submit(
                "invalidating", "", Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult invalidatingResult = invalidating.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            awaitDiagnostic(diagnostics, "GRAAL_HOST_INVALIDATED");

            final GraalHostManager.Execution replacement = manager.submit(
                "replacement", "", Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult replacementResult = replacement.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            final GraalHostManager.TransportResult firstResult = first.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.FAILED, invalidatingResult.status());
            assertEquals("GRAAL_HOST_WRITE_FAILED", invalidatingResult.code());
            assertEquals(
                GraalHostManager.Status.SUCCEEDED,
                replacementResult.status(),
                () -> replacementResult + " diagnostics=" + diagnostics
            );
            assertEquals("replacement", replacementResult.output());
            assertEquals(GraalHostManager.Status.FAILED, firstResult.status());
            assertEquals("GRAAL_HOST_CRASHED", firstResult.code());
        }
    }

    @Test
    void cancellationBeforeDelayedRunIsNeverSentToTheHost() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(CancelBeforeRunHost.class, diagnostics)) {
            final GraalHostManager.Execution first = manager.submit(
                "first", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "FIRST_RUN_RECEIVED");
            final GraalHostManager.Execution cancelled = manager.submit(
                "cancelled", "", Map.of(), (operation, payload) -> "{}"
            );

            assertTrue(cancelled.cancel());
            final GraalHostManager.TransportResult cancelledResult = cancelled.completion()
                .toCompletableFuture().get(200, TimeUnit.MILLISECONDS);
            assertEquals(GraalHostManager.Status.CANCELLED, cancelledResult.status());
            assertEquals("SCRIPT_CANCELLED", cancelledResult.code());
            assertFalse(cancelled.cancel());

            final GraalHostManager.TransportResult firstResult = first.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.SUCCEEDED, firstResult.status());
            assertTrue(
                diagnostics.stream().noneMatch(message -> message.contains("CANCELLED_RUN_RECEIVED")),
                () -> diagnostics.toString()
            );
        }
    }

    @Test
    void oversizedOutgoingRunRejectsOnlyThatExecutionAndKeepsHostUsable() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(EchoHost.class, diagnostics)) {
            final GraalHostManager.Execution oversized = manager.submit(
                "oversized", "x".repeat(4 * 1024 * 1024), Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult oversizedResult = oversized.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.REJECTED, oversizedResult.status());
            assertEquals("SCRIPT_MESSAGE_TOO_LARGE", oversizedResult.code());

            final GraalHostManager.TransportResult healthy = manager.submit(
                "healthy", "", Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.SUCCEEDED, healthy.status());
            assertEquals("healthy", healthy.output());
            assertTrue(
                diagnostics.stream().noneMatch(message -> message.contains("GRAAL_HOST_INVALIDATED")),
                () -> diagnostics.toString()
            );
        }
    }

    @Test
    void protocolReaderFailureTerminatesTheOwnedHostProcess() throws Exception {
        final Path pidFile = ProtocolCorruptHost.pidFile(ProcessHandle.current().pid());
        Files.deleteIfExists(pidFile);
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(ProtocolCorruptHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "protocol", "", Map.of(), (operation, payload) -> "{}"
            );
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            final long pid = Long.parseLong(Files.readString(pidFile));

            assertEquals(GraalHostManager.Status.FAILED, result.status());
            assertEquals("GRAAL_HOST_CRASHED", result.code());
            awaitProcessExit(pid);
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void oversizedUnterminatedProtocolOutputTerminatesTheFakeChild() throws Exception {
        final Path pidFile = OversizedProtocolHost.pidFile(ProcessHandle.current().pid());
        Files.deleteIfExists(pidFile);
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(OversizedProtocolHost.class, diagnostics)) {
            final GraalHostManager.TransportResult result = manager.submit(
                "oversized", "", Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);
            final long pid = Long.parseLong(Files.readString(pidFile));

            assertEquals(GraalHostManager.Status.FAILED, result.status());
            assertEquals("GRAAL_HOST_CRASHED", result.code());
            awaitProcessExit(pid);
        } finally {
            Files.deleteIfExists(pidFile);
        }
    }

    @Test
    void oversizedUnterminatedStderrIsDrainedWithBoundedDiagnostics() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(OversizedStderrHost.class, diagnostics)) {
            final GraalHostManager.TransportResult result = manager.submit(
                "stderr", "", Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(5, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.SUCCEEDED, result.status());
            assertEquals("stderr", result.output());
            awaitDiagnostic(diagnostics, "GRAAL_HOST_STDERR:");
            assertTrue(diagnostics.stream()
                .filter(message -> message.startsWith("GRAAL_HOST_STDERR: "))
                .allMatch(message -> message.length() <= "GRAAL_HOST_STDERR: ".length() + 1024),
                () -> "Unbounded stderr diagnostic: " + diagnostics);
        }
    }

    @Test
    void wildcardClasspathIsAcceptedByTheChildProcessLauncher() throws Exception {
        final Path wildcardDirectory = Files.createTempDirectory("graal-host-wildcard");
        try {
            final List<String> diagnostics = new CopyOnWriteArrayList<>();
            final String javaBinary = Path.of(
                System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
            ).toString();
            try (GraalHostManager manager = new GraalHostManager(
                new GraalHostConfiguration(
                    true,
                    javaBinary,
                    wildcardDirectory.resolve("*").toString(),
                    NeverReadyHost.class.getName(),
                    200L
                ),
                diagnostics::add
            )) {
                final GraalHostManager.TransportResult result = manager.submit(
                    "wildcard", "", Map.of(), (operation, payload) -> "{}"
                ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

                assertEquals(GraalHostManager.Status.REJECTED, result.status());
                assertTrue(
                    diagnostics.stream().noneMatch(message -> message.contains("Illegal char <*>")),
                    () -> diagnostics.toString()
                );
            }
        } finally {
            Files.deleteIfExists(wildcardDirectory);
        }
    }

    @Test
    void submitSnapshotsArgumentsBeforeTheQueuedTaskRuns() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final Map<String, String> arguments = new HashMap<>();
        arguments.put("value", "before");
        try (GraalHostManager manager = manager(ArgumentSnapshotHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "arguments", "", arguments, (operation, payload) -> "{}"
            );
            arguments.put("value", "after");

            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.SUCCEEDED, result.status());
            assertEquals("before", result.output());
        }
    }

    @Test
    void cancellingQueuedSubmissionsReclaimsSubmissionQueueCapacity() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        try (GraalHostManager manager = manager(StartupBlockingHost.class, diagnostics, 300L)) {
            final GraalHostManager.Execution blocking = manager.submit(
                "blocking", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "STARTUP_BLOCKED");
            for (int index = 0; index < 64; index++) {
                final GraalHostManager.Execution queued = manager.submit(
                    "queued-" + index, "payload-" + index, Map.of("index", Integer.toString(index)),
                    (operation, payload) -> "{}"
                );
                assertTrue(queued.cancel());
            }

            final GraalHostManager.TransportResult replacement = manager.submit(
                "replacement", "", Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertFalse(
                "GRAAL_HOST_SUBMISSION_QUEUE_FULL".equals(replacement.code()),
                () -> replacement.toString()
            );
            assertTrue(blocking.cancel() || blocking.completion().toCompletableFuture().isDone());
        }
    }

    @Test
    void processExitBeforeReadyBacksOffTheNextLaunch() throws Exception {
        final Path countFile = PreReadyExitHost.countFile(ProcessHandle.current().pid());
        Files.deleteIfExists(countFile);
        try {
            final List<String> diagnostics = new CopyOnWriteArrayList<>();
            try (GraalHostManager manager = manager(PreReadyExitHost.class, diagnostics, 2_000L)) {
                final GraalHostManager.TransportResult first = manager.submit(
                    "first", "", Map.of(), (operation, payload) -> "{}"
                ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
                final GraalHostManager.TransportResult second = manager.submit(
                    "second", "", Map.of(), (operation, payload) -> "{}"
                ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

                assertEquals(GraalHostManager.Status.REJECTED, first.status());
                assertEquals(GraalHostManager.Status.REJECTED, second.status());
                assertEquals("1", Files.readString(countFile));
            }
        } finally {
            Files.deleteIfExists(countFile);
        }
    }

    @Test
    void childJvmDoesNotInheritJvmOptionInjectionEnvironment() throws Exception {
        final String javaBinary = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        final ProcessBuilder builder = new ProcessBuilder(
            javaBinary,
            "-cp",
            System.getProperty("java.class.path"),
            ManagerEnvironmentIsolationProbe.class.getName(),
            javaBinary,
            System.getProperty("java.class.path")
        );
        builder.environment().put(
            "JAVA_TOOL_OPTIONS", "-Dturboism.test.javaToolOptionsInherited=true"
        );
        builder.environment().put(
            "_JAVA_OPTIONS", "-Dturboism.test.legacyJavaOptionsInherited=true"
        );
        builder.environment().put(
            "JDK_JAVA_OPTIONS", "-Dturboism.test.jdkJavaOptionsInherited=true"
        );

        final Process probe = builder.start();
        assertTrue(probe.waitFor(10, TimeUnit.SECONDS), "environment-isolation probe timed out");
        final String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        final String errors = new String(probe.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);

        assertEquals(0, probe.exitValue(), errors + " output=" + output);
        assertEquals("SUCCEEDED", output.trim(), errors);
    }

    @Test
    void slowHostCallCannotBlockTerminalReadOrSendALateResponse() throws Exception {
        final Path enteredFile = TerminalDuringHostCallHost.enteredFile(ProcessHandle.current().pid());
        Files.deleteIfExists(enteredFile);
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        try (GraalHostManager manager = manager(TerminalDuringHostCallHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "terminal", "", Map.of(), (operation, payload) -> {
                    Files.writeString(enteredFile, "entered");
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return "{}";
                }
            );
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.SUCCEEDED, result.status());
            assertEquals("terminal", result.output());
            assertFalse(execution.cancel());

            release.countDown();
            Thread.sleep(150L);
            assertTrue(
                diagnostics.stream().noneMatch(message -> message.contains("LATE_HOST_RESPONSE")),
                () -> diagnostics.toString()
            );
        } finally {
            release.countDown();
            Files.deleteIfExists(enteredFile);
        }
    }

    @Test
    void cancelPreventsAQueuedHostCallFromStarting() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger invocations = new AtomicInteger();
        try (GraalHostManager manager = manager(ThreeHostCallsThenCancelHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "cancel-host-call", "", Map.of(), (operation, payload) -> {
                    invocations.incrementAndGet();
                    entered.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return "{}";
                }
            );
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(execution.cancel());

            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.CANCELLED, result.status());
            release.countDown();
            Thread.sleep(100L);
            assertEquals(2, invocations.get());
            assertFalse(execution.cancel());
        } finally {
            release.countDown();
        }
    }

    @Test
    void saturatedHostCallExecutorRepliesWithoutBlockingTheReader() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(2);
        final CountDownLatch release = new CountDownLatch(1);
        try (GraalHostManager manager = manager(HostCallQueueOverflowHost.class, diagnostics)) {
            final GraalHostManager.Execution execution = manager.submit(
                "overflow", "", Map.of(), (operation, payload) -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return "{}";
                }
            );
            assertTrue(entered.await(2, TimeUnit.SECONDS));

            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.SUCCEEDED, result.status());
            assertEquals("queue-rejected", result.output());
            assertTrue(
                diagnostics.stream().anyMatch(message -> message.contains("GRAAL_HOST_CALL_QUEUE_FULL")),
                () -> diagnostics.toString()
            );
        } finally {
            release.countDown();
        }
    }

    @Test
    void throwingDiagnosticsCannotStrandAnExecution() throws Exception {
        try (GraalHostManager manager = new GraalHostManager(
            configuration(ProtocolCorruptHost.class, 5_000L),
            message -> { throw new IllegalStateException("diagnostic sink failed"); }
        )) {
            final GraalHostManager.TransportResult result = manager.submit(
                "diagnostics", "", Map.of(), (operation, payload) -> "{}"
            ).completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

            assertEquals(GraalHostManager.Status.FAILED, result.status());
            assertEquals("GRAAL_HOST_CRASHED", result.code());
        }
    }

    @Test
    void closeDoesNotWaitForTheHostInputPipeAndWinsTheExitRace() throws Exception {
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final GraalHostManager manager = manager(StalledInputHost.class, diagnostics);
        try {
            final GraalHostManager.Execution execution = manager.submit(
                "stalled", "x".repeat(3 * 1024 * 1024), Map.of(),
                (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "STALLING_INPUT");
            Thread.sleep(100L);

            CompletableFuture.runAsync(manager::close).get(1, TimeUnit.SECONDS);
            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(GraalHostManager.Status.CANCELLED, result.status());
            assertEquals("SCRIPT_RUNTIME_CLOSED", result.code());
        } finally {
            manager.close();
        }
    }

    @Test
    void closeTerminatesTheHostProcessDescendants() throws Exception {
        final Path descendantPidFile = DescendantHost.descendantPidFile(
            ProcessHandle.current().pid()
        );
        Files.deleteIfExists(descendantPidFile);
        final List<String> diagnostics = new CopyOnWriteArrayList<>();
        final GraalHostManager manager = manager(DescendantHost.class, diagnostics);
        long descendantPid = 0L;
        try {
            final GraalHostManager.Execution execution = manager.submit(
                "descendant", "", Map.of(), (operation, payload) -> "{}"
            );
            awaitDiagnostic(diagnostics, "DESCENDANT_STARTED");
            descendantPid = Long.parseLong(Files.readString(descendantPidFile));

            manager.close();

            final GraalHostManager.TransportResult result = execution.completion()
                .toCompletableFuture().get(2, TimeUnit.SECONDS);
            assertEquals("SCRIPT_RUNTIME_CLOSED", result.code());
            awaitProcessExit(descendantPid);
        } finally {
            manager.close();
            if (descendantPid != 0L) {
                ProcessHandle.of(descendantPid).ifPresent(ProcessHandle::destroyForcibly);
            }
            Files.deleteIfExists(descendantPidFile);
        }
    }

    private static GraalHostManager manager(
        final Class<?> mainClass,
        final List<String> diagnostics
    ) throws Exception {
        return manager(mainClass, diagnostics, 5_000L);
    }

    private static GraalHostManager manager(
        final Class<?> mainClass,
        final List<String> diagnostics,
        final long startupTimeoutMillis
    ) throws Exception {
        final String javaBinary = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        final String testClasses = Path.of(
            GraalHostManagerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        return new GraalHostManager(
            configuration(mainClass, startupTimeoutMillis),
            diagnostics::add
        );
    }

    private static GraalHostConfiguration configuration(
        final Class<?> mainClass,
        final long startupTimeoutMillis
    ) throws Exception {
        final String javaBinary = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        final String testClasses = Path.of(
            GraalHostManagerTest.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        return new GraalHostConfiguration(
            true, javaBinary, testClasses, mainClass.getName(), startupTimeoutMillis
        );
    }

    private static void awaitDiagnostic(
        final List<String> diagnostics,
        final String marker
    ) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (diagnostics.stream().anyMatch(message -> message.contains(marker))) {
                return;
            }
            Thread.sleep(10L);
        }
        assertTrue(false, "Missing diagnostic " + marker + ": " + diagnostics);
    }

    private static void awaitProcessExit(final long pid) throws Exception {
        final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (isRunningProcess(pid)) {
                Thread.sleep(10L);
                continue;
            }
            return;
        }
        assertTrue(false, "Host process is still alive: " + pid);
    }

    private static boolean isRunningProcess(final long pid) {
        final Optional<ProcessHandle> process = ProcessHandle.of(pid);
        if (process.isEmpty() || !process.orElseThrow().isAlive()) {
            return false;
        }
        if (!isWindows()) {
            try {
                final String status = Files.readString(Path.of("/proc", Long.toString(pid), "status"));
                if (status.lines().anyMatch(line -> line.startsWith("State:") && line.contains("Z"))) {
                    return false;
                }
            } catch (Exception ignored) {
                // Fall back to the portable ProcessHandle liveness signal.
            }
        }
        return true;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase().contains("win");
    }

    private static String executionId(final String line) {
        return field(line, "executionId");
    }

    private static String scriptId(final String line) {
        return field(line, "scriptId");
    }

    private static String field(final String line, final String name) {
        final String marker = "\"" + name + "\":\"";
        final int start = line.indexOf(marker) + marker.length();
        return line.substring(start, line.indexOf('"', start));
    }

    public static final class ArgumentSnapshotHost {
        private ArgumentSnapshotHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            final String run = input.readLine();
            if (run != null) {
                final String value = run.contains("\"value\":\"before\"") ? "before" : "after";
                output.write("{\"type\":\"COMPLETE\",\"executionId\":\""
                    + executionId(run) + "\",\"status\":\"SUCCEEDED\",\"output\":\""
                    + value + "\"}");
                output.newLine();
                output.flush();
            }
        }
    }

    public static final class StartupBlockingHost {
        private StartupBlockingHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"STARTUP_BLOCKED\","
                + "\"message\":\"test\"}");
            output.newLine();
            output.flush();
            Thread.sleep(10_000L);
        }
    }

    public static final class PreReadyExitHost {
        private PreReadyExitHost() {
        }

        public static void main(final String[] args) throws Exception {
            final Path countFile = countFile(parentPid());
            final int count = Files.exists(countFile)
                ? Integer.parseInt(Files.readString(countFile))
                : 0;
            Files.writeString(countFile, Integer.toString(count + 1));
        }

        static Path countFile(final long parentPid) {
            return Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-pre-ready-exit-" + parentPid + ".count"
            );
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    public static final class TerminalDuringHostCallHost {
        private TerminalDuringHostCallHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            final String run = input.readLine();
            if (run == null) {
                return;
            }
            final String id = executionId(run);
            output.write("{\"type\":\"HOST_CALL\",\"executionId\":\"" + id
                + "\",\"callId\":\"slow\",\"operation\":\"slow\",\"payload\":\"{}\"}");
            output.newLine();
            output.flush();
            final Path enteredFile = enteredFile(parentPid());
            final long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
            while (Files.notExists(enteredFile) && System.nanoTime() < deadline) {
                Thread.sleep(5L);
            }
            output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + id
                + "\",\"status\":\"SUCCEEDED\",\"output\":\"terminal\"}");
            output.newLine();
            output.flush();
            Thread.sleep(400L);
            if (input.ready()) {
                final String late = input.readLine();
                if (late != null && late.contains("\"callId\":\"slow\"")) {
                    output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"LATE_HOST_RESPONSE\","
                        + "\"message\":\"late response\"}");
                    output.newLine();
                    output.flush();
                }
            }
        }

        static Path enteredFile(final long parentPid) {
            return Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-host-call-entered-" + parentPid + ".marker"
            );
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    public static final class ThreeHostCallsThenCancelHost {
        private ThreeHostCallsThenCancelHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            final String run = input.readLine();
            if (run == null) {
                return;
            }
            final String id = executionId(run);
            for (int index = 0; index < 3; index++) {
                output.write("{\"type\":\"HOST_CALL\",\"executionId\":\"" + id
                    + "\",\"callId\":\"call-" + index
                    + "\",\"operation\":\"slow\",\"payload\":\"{}\"}");
                output.newLine();
            }
            output.flush();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.contains("\"type\":\"CANCEL\"")) {
                    output.write("{\"type\":\"FAILED\",\"executionId\":\"" + id
                        + "\",\"status\":\"CANCELLED\",\"code\":\"SCRIPT_CANCELLED\","
                        + "\"message\":\"cancelled\",\"output\":\"\"}");
                    output.newLine();
                    output.flush();
                    return;
                }
            }
        }
    }

    public static final class HostCallQueueOverflowHost {
        private HostCallQueueOverflowHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            final String run = input.readLine();
            if (run == null) {
                return;
            }
            final String id = executionId(run);
            for (int index = 0; index < 67; index++) {
                output.write("{\"type\":\"HOST_CALL\",\"executionId\":\"" + id
                    + "\",\"callId\":\"call-" + index
                    + "\",\"operation\":\"slow\",\"payload\":\"{}\"}");
                output.newLine();
            }
            output.flush();
            String line;
            while ((line = input.readLine()) != null) {
                if (line.contains("SCRIPT_HOST_CALL_QUEUE_FULL")) {
                    output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + id
                        + "\",\"status\":\"SUCCEEDED\",\"output\":\"queue-rejected\"}");
                    output.newLine();
                    output.flush();
                    return;
                }
            }
        }
    }

    public static final class StalledInputHost {
        private StalledInputHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"STALLING_INPUT\","
                + "\"message\":\"not reading stdin\"}");
            output.newLine();
            output.flush();
            Thread.sleep(10_000L);
        }
    }

    public static final class DescendantHost {
        private DescendantHost() {
        }

        public static void main(final String[] args) throws Exception {
            final Process descendant = new ProcessBuilder(
                Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    isWindows() ? "java.exe" : "java"
                ).toString(),
                "-cp",
                System.getProperty("java.class.path"),
                NeverReadyHost.class.getName()
            ).start();
            Files.writeString(
                descendantPidFile(parentPid()),
                Long.toString(descendant.pid())
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            ready(output);
            output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"DESCENDANT_STARTED\","
                + "\"message\":\"test\"}");
            output.newLine();
            output.flush();
            Thread.sleep(10_000L);
        }

        static Path descendantPidFile(final long parentPid) {
            return Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-descendant-" + parentPid + ".pid"
            );
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    private static void ready(final BufferedWriter output) throws Exception {
        output.write("{\"type\":\"READY\",\"protocolVersion\":1,"
            + "\"graalAvailable\":true,\"detail\":\"test\"}");
        output.newLine();
        output.flush();
    }

    public static final class NeverReadyHost {
        private NeverReadyHost() {
        }

        public static void main(final String[] args) throws Exception {
            Thread.sleep(10_000L);
        }
    }

    public static final class ProtocolCorruptHost {
        private ProtocolCorruptHost() {
        }

        public static void main(final String[] args) throws Exception {
            final Path pidFile = pidFile(parentPid());
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            if (input.readLine() != null) {
                output.write("not-json");
                output.newLine();
                output.flush();
                Thread.sleep(10_000L);
            }
        }

        static Path pidFile(final long parentPid) {
            return Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-protocol-failure-" + parentPid + ".pid"
            );
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    public static final class OversizedProtocolHost {
        private OversizedProtocolHost() {
        }

        public static void main(final String[] args) throws Exception {
            final Path pidFile = pidFile(parentPid());
            Files.writeString(pidFile, Long.toString(ProcessHandle.current().pid()));
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
            output.write("x".repeat(4 * 1024 * 1024 + 1));
            output.flush();
            Thread.sleep(10_000L);
        }

        static Path pidFile(final long parentPid) {
            return Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-oversized-protocol-" + parentPid + ".pid"
            );
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    public static final class OversizedStderrHost {
        private OversizedStderrHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            final String run = input.readLine();
            if (run != null) {
                System.err.print("e".repeat(4 * 1024 * 1024 + 1));
                System.err.flush();
                final String executionId = executionId(run);
                output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + executionId
                    + "\",\"status\":\"SUCCEEDED\",\"output\":\"stderr\"}");
                output.newLine();
                output.flush();
            }
        }

        private static String executionId(final String run) {
            final String marker = "\"executionId\":\"";
            final int start = run.indexOf(marker) + marker.length();
            return run.substring(start, run.indexOf('\"', start));
        }
    }

    public static final class ReplacementRaceHost {
        private ReplacementRaceHost() {
        }

        public static void main(final String[] args) throws Exception {
            final Path marker = Path.of(
                System.getProperty("java.io.tmpdir"),
                "turboism-graal-replacement-race-" + parentPid()
            );
            final boolean first = Files.notExists(marker);
            if (first) {
                Files.createFile(marker);
            }
            try {
                final BufferedReader input = new BufferedReader(
                    new InputStreamReader(System.in, StandardCharsets.UTF_8)
                );
                final BufferedWriter output = new BufferedWriter(
                    new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
                );
                output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
                output.newLine();
                output.flush();
                final String run = input.readLine();
                if (run == null) {
                    return;
                }
                final String executionId = executionId(run);
                if (first) {
                    System.in.close();
                    output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"FIRST_INPUT_CLOSED\",\"message\":\"replace me\"}");
                    output.newLine();
                    output.flush();
                    Thread.sleep(150L);
                } else {
                    output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + executionId
                        + "\",\"status\":\"SUCCEEDED\",\"output\":\"replacement\"}");
                    output.newLine();
                    output.flush();
                }
            } finally {
                if (!first) {
                    Files.deleteIfExists(marker);
                }
            }
        }

        private static String executionId(final String run) {
            return field(run, "executionId");
        }

        private static String scriptId(final String run) {
            return field(run, "scriptId");
        }

        private static String field(final String line, final String name) {
            final String marker = "\"" + name + "\":\"";
            final int start = line.indexOf(marker) + marker.length();
            return line.substring(start, line.indexOf('"', start));
        }

        private static long parentPid() {
            return ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(0L);
        }
    }

    public static final class CancelBeforeRunHost {
        private CancelBeforeRunHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            final String first = input.readLine();
            if (first == null) {
                return;
            }
            output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"FIRST_RUN_RECEIVED\",\"message\":\"test\"}");
            output.newLine();
            output.flush();
            Thread.sleep(100L);
            output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + executionId(first)
                + "\",\"status\":\"SUCCEEDED\",\"output\":\"first\"}");
            output.newLine();
            output.flush();
            final String second = input.readLine();
            if (second != null && second.contains("\"type\":\"RUN\"")) {
                output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"CANCELLED_RUN_RECEIVED\",\"message\":\"test\"}");
                output.newLine();
                output.flush();
            }
        }
    }

    public static final class EchoHost {
        private EchoHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();
            String line;
            while ((line = input.readLine()) != null) {
                if (!line.contains("\"type\":\"RUN\"")) {
                    continue;
                }
                final String id = executionId(line);
                final String script = scriptId(line);
                output.write("{\"type\":\"COMPLETE\",\"executionId\":\"" + id
                    + "\",\"status\":\"SUCCEEDED\",\"output\":\"" + script + "\"}");
                output.newLine();
                output.flush();
            }
        }
    }

    public static final class InputClosingHost {
        private InputClosingHost() {
        }

        public static void main(final String[] args) throws Exception {
            final BufferedReader input = new BufferedReader(
                new InputStreamReader(System.in, StandardCharsets.UTF_8)
            );
            final BufferedWriter output = new BufferedWriter(
                new OutputStreamWriter(System.out, StandardCharsets.UTF_8)
            );
            output.write("{\"type\":\"READY\",\"protocolVersion\":1,\"graalAvailable\":true,\"detail\":\"test\"}");
            output.newLine();
            output.flush();

            final String run = input.readLine();
            if (run != null && run.contains("\"type\":\"RUN\"")) {
                System.in.close();
                output.write("{\"type\":\"PROTOCOL_ERROR\",\"code\":\"INPUT_CLOSED\",\"message\":\"test host closed input\"}");
                output.newLine();
                output.flush();
                Thread.sleep(10_000L);
            }
        }
    }
}
