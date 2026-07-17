package dev.turboism.task;

import dev.turboism.cleanup.CleanupEvidenceCollector;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.RuntimeTimerSubmission;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.failure.RuntimeFailureCollector;
import dev.turboism.sdk.plugin.DisposableScope;
import dev.turboism.sdk.plugin.WorkBudget;
import dev.turboism.sdk.task.FixedDelayTaskRequest;
import dev.turboism.sdk.task.PluginTaskKind;
import dev.turboism.sdk.task.PluginTaskPriority;
import dev.turboism.sdk.task.PluginTaskRequest;
import dev.turboism.sdk.task.TaskId;
import dev.turboism.sdk.task.TaskOutcome;
import dev.turboism.sdk.task.TaskOutcomeStatus;
import dev.turboism.sdk.task.TaskRejectionReason;
import dev.turboism.sdk.task.TaskRunOutcomeStatus;
import dev.turboism.sdk.task.TaskSubmission;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimePluginTaskSchedulerTest {

    private RuntimeScheduler runtimeScheduler;
    private DisposableScope scope;
    private RuntimePluginTaskScheduler scheduler;

    @AfterEach
    void closeRuntime() throws Exception {
        if (scope != null) {
            scope.close();
        }
        if (runtimeScheduler != null) {
            runtimeScheduler.shutdown();
        }
    }

    @Test
    void kindAndPriorityReachRuntimePolicyClassification() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<PluginTask> classified =
            new java.util.concurrent.atomic.AtomicReference<>();
        createScheduler(1, 8, task -> {
            classified.set(task);
            return WorkBudget.LIGHTWEIGHT;
        });
        final TaskSubmission submission = scheduler.submit(new PluginTaskRequest(
            new TaskId("classified"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            token -> { }
        ));

        assertTrue(submission.accepted());
        assertEquals(
            TaskOutcomeStatus.SUCCEEDED,
            submission.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );
        assertEquals("plugin.refresh.low", classified.get().taskType());
        assertTrue(classified.get().payloadDescription().contains("classified"));
    }

    @Test
    void completesOneShotAndReusesIdAfterTerminalCleanup() throws Exception {
        createScheduler(1, 8);
        final AtomicInteger calls = new AtomicInteger();
        final PluginTaskRequest request = request("one-shot", token -> calls.incrementAndGet());

        final TaskSubmission first = scheduler.submit(request);
        final TaskOutcome firstOutcome = first.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);

        assertTrue(first.accepted());
        assertEquals(TaskOutcomeStatus.SUCCEEDED, firstOutcome.status());
        assertEquals(1, firstOutcome.runCount());
        assertEquals(TaskRunOutcomeStatus.SUCCEEDED, firstOutcome.lastRunOutcome().orElseThrow().status());
        assertEquals(1, calls.get());

        final TaskSubmission reused = scheduler.submit(request);
        assertTrue(reused.accepted());
        assertEquals(
            TaskOutcomeStatus.SUCCEEDED,
            reused.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );
        assertEquals(2, calls.get());
    }

    @Test
    void terminalAcceptedTasksReleaseScopeOwnershipWithoutAccumulation() throws Exception {
        createScheduler(1, 8);
        final int baselineScopeRegistrations = scopedRegistrationCount(scope);

        for (int index = 0; index < 64; index++) {
            final TaskSubmission completed = scheduler.submit(request(
                "terminal-release-completed-" + index,
                token -> { }
            ));
            assertTrue(completed.accepted());
            assertEquals(TaskOutcomeStatus.SUCCEEDED, completed.handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS).status());
            assertEquals(baselineScopeRegistrations, scopedRegistrationCount(scope),
                "naturally completed ownership must leave the scope");

            final TaskSubmission canceled = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId("terminal-release-canceled-" + index),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofHours(1),
                Duration.ofHours(1),
                token -> { }
            ));
            assertTrue(canceled.accepted());
            assertTrue(canceled.handle().cancel());
            assertEquals(TaskOutcomeStatus.CANCELED, canceled.handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS).status());
            assertEquals(baselineScopeRegistrations, scopedRegistrationCount(scope),
                "manually canceled ownership must leave the scope");
        }

        assertEquals(0, scheduler.activeTaskCount());
        assertEquals(64, scheduler.availableActiveTaskPermits());
    }

    @Test
    void rejectsDuplicateActiveIdWithTerminalNonNullHandle() throws Exception {
        createScheduler(1, 8);
        final CountDownLatch release = new CountDownLatch(1);
        final TaskSubmission first = scheduler.submit(request("duplicate", token -> release.await()));
        final TaskSubmission duplicate = scheduler.submit(request("duplicate", token -> { }));

        assertTrue(first.accepted());
        assertFalse(duplicate.accepted());
        assertNotNull(duplicate.handle());
        assertEquals(Optional.of(TaskRejectionReason.DUPLICATE_ACTIVE_ID), duplicate.rejectionReason());
        final TaskOutcome rejected = duplicate.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.REJECTED, rejected.status());
        assertEquals(0, rejected.runCount());
        assertFalse(duplicate.handle().cancel());

        final var exposed = duplicate.handle().completion().toCompletableFuture();
        assertThrows(UnsupportedOperationException.class, () -> exposed.obtrudeException(
            new IllegalStateException("plugin-forced")
        ));
        final AtomicReference<String> continuationThread = new AtomicReference<>();
        duplicate.handle().completion().thenRun(() ->
            continuationThread.set(Thread.currentThread().getName())
        ).toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(continuationThread.get().contains("plugin.tasks"));

        release.countDown();
        first.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void duplicateCleanupNeverRemovesOrReleasesTheOriginalActiveTask() throws Exception {
        createScheduler(1, 128);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TaskSubmission original = scheduler.submit(request("same-id", token -> {
            started.countDown();
            release.await();
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        final TaskSubmission duplicate = scheduler.submit(request("same-id", token -> { }));

        assertFalse(duplicate.accepted());
        assertEquals(Optional.of(TaskRejectionReason.DUPLICATE_ACTIVE_ID), duplicate.rejectionReason());
        assertEquals(1, scheduler.activeTaskCount());
        assertEquals(63, scheduler.availableActiveTaskPermits());
        assertTrue(original.handle().cancel());
        assertEquals(0, scheduler.activeTaskCount());
        assertEquals(64, scheduler.availableActiveTaskPermits());
        release.countDown();
    }

    @Test
    void cancelBeforeQueuedActionStartsKeepsRunCountZero() throws Exception {
        createScheduler(1, 8);
        final CountDownLatch blockerStarted = new CountDownLatch(1);
        final CountDownLatch releaseBlocker = new CountDownLatch(1);
        runtimeScheduler.dispatch(
            new PluginTask("ui.schedule", "plugin.tasks", "block worker", "none"),
            () -> {
                blockerStarted.countDown();
                await(releaseBlocker);
            }
        );
        assertTrue(blockerStarted.await(1, TimeUnit.SECONDS));

        final AtomicBoolean ran = new AtomicBoolean();
        final TaskSubmission submission = scheduler.submit(request("queued", token -> ran.set(true)));
        assertTrue(submission.accepted());
        assertTrue(submission.handle().cancel());
        assertFalse(submission.handle().cancel());

        releaseBlocker.countDown();
        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.CANCELED, outcome.status());
        assertEquals(0, outcome.runCount());
        assertFalse(ran.get());
    }

    @Test
    void cancelRunningActionWinsTerminalCanceledAndSignalsToken() throws Exception {
        createScheduler(1, 8);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch observedCancellation = new CountDownLatch(1);
        final TaskSubmission submission = scheduler.submit(request("running", token -> {
            started.countDown();
            while (!token.isCancellationRequested()) {
                Thread.onSpinWait();
            }
            observedCancellation.countDown();
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        final java.util.concurrent.atomic.AtomicReference<String> continuationThread =
            new java.util.concurrent.atomic.AtomicReference<>();
        final CountDownLatch continuationRan = new CountDownLatch(1);
        submission.handle().completion().thenRun(() -> {
            continuationThread.set(Thread.currentThread().getName());
            continuationRan.countDown();
        });

        assertTrue(submission.handle().cancel());
        assertTrue(observedCancellation.await(1, TimeUnit.SECONDS));
        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));

        assertEquals(TaskOutcomeStatus.CANCELED, outcome.status());
        assertTrue(
            continuationThread.get().contains("plugin.tasks"),
            () -> "unexpected continuation thread: " + continuationThread.get()
        );
        assertEquals(1, outcome.runCount());
        assertEquals(TaskRunOutcomeStatus.CANCELED, outcome.lastRunOutcome().orElseThrow().status());
    }

    @Test
    void actionFailureProducesSanitizedTerminalFailure() throws Exception {
        createScheduler(1, 8);
        final TaskSubmission submission = scheduler.submit(request("failure", token -> {
            throw new IllegalStateException("private-value");
        }));

        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.FAILED, outcome.status());
        assertEquals(TaskRunOutcomeStatus.FAILED, outcome.lastRunOutcome().orElseThrow().status());
        assertTrue(outcome.failure().isPresent());
        assertFalse(outcome.failure().orElseThrow().message().contains("private-value"));
    }

    @Test
    void acceptedTerminalFailureUsesStableSubmitOperationWithoutLeakingTaskId() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        createScheduler(1, 8, failures);
        final String secretTaskId = "private-one-shot-task-id";
        final TaskSubmission submission = scheduler.submit(request(secretTaskId, token -> {
            throw new IllegalStateException("private-value");
        }));

        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        submission.handle().cancel();

        assertEquals(TaskOutcomeStatus.FAILED, outcome.status());
        assertEquals(1, failures.snapshot().taskFailures().size());
        final var failure = failures.snapshot().taskFailures().get(0);
        assertEquals("TASK_FAILED", failure.code());
        assertEquals("execution", failure.phase());
        assertEquals("plugin.tasks", failure.pluginId());
        assertEquals("task.submit", failure.operationId());
        assertFalse(failure.operationId().contains(secretTaskId));
        assertFalse(failure.message().contains(secretTaskId));
        assertEquals(1, failure.count());
    }

    @Test
    void fixedDelayTerminalFailureUsesStableScheduleOperationWithoutLeakingTaskId() throws Exception {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        createScheduler(1, 8, failures);
        final String secretTaskId = "private-fixed-delay-task-id";
        final TaskSubmission submission = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId(secretTaskId),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ofHours(1),
            token -> { throw new IllegalStateException("private-value"); }
        ));

        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture()
            .get(2, TimeUnit.SECONDS);
        final var failure = failures.snapshot().taskFailures().get(0);

        assertEquals(TaskOutcomeStatus.FAILED, outcome.status());
        assertEquals("TASK_FAILED", failure.code());
        assertEquals("task.schedule", failure.operationId());
        assertFalse(failure.operationId().contains(secretTaskId));
        assertFalse(failure.message().contains(secretTaskId));
    }

    @Test
    void rejectedSubmitUsesStableOperationWithoutLeakingTaskId() {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        createScheduler(1, 8, task -> WorkBudget.REJECTED, failures);
        final String secretTaskId = "secret-submit-task-id";

        final TaskSubmission submission = scheduler.submit(request(secretTaskId, token -> { }));

        assertFalse(submission.accepted());
        final var failure = failures.snapshot().taskFailures().get(0);
        assertEquals("TASK_REJECTED_POLICY_REJECTED", failure.code());
        assertEquals("task.submit", failure.operationId());
        assertFalse(failure.toString().contains(secretTaskId));
    }

    @Test
    void rejectedFixedDelayScheduleUsesStableOperationWithoutLeakingTaskId() {
        final RuntimeFailureCollector failures = new RuntimeFailureCollector();
        createScheduler(1, 128, failures);
        for (int index = 0; index < 64; index++) {
            assertTrue(scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId("reserved-schedule-" + index),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofHours(1),
                Duration.ofHours(1),
                token -> { }
            )).accepted());
        }
        final String secretTaskId = "secret-scheduled-task-id";

        final TaskSubmission submission = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId(secretTaskId),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ofHours(1),
            Duration.ofHours(1),
            token -> { }
        ));

        assertFalse(submission.accepted());
        final var failure = failures.snapshot().taskFailures().get(0);
        assertEquals("TASK_REJECTED_BACKPRESSURE", failure.code());
        assertEquals("task.schedule", failure.operationId());
        assertFalse(failure.toString().contains(secretTaskId));
    }

    @Test
    void fixedDelayIsNonOverlappingAndCancelDuringDelayPreservesLastSuccess() throws Exception {
        createScheduler(1, 8);
        final AtomicInteger running = new AtomicInteger();
        final AtomicInteger maxRunning = new AtomicInteger();
        final CountDownLatch twoRuns = new CountDownLatch(2);
        final TaskSubmission submission = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId("refresh"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ofMillis(200),
            token -> {
                final int current = running.incrementAndGet();
                maxRunning.accumulateAndGet(current, Math::max);
                try {
                    Thread.sleep(20);
                } finally {
                    running.decrementAndGet();
                    twoRuns.countDown();
                }
            }
        ));

        assertTrue(submission.accepted());
        assertTrue(twoRuns.await(2, TimeUnit.SECONDS));
        waitUntil(() -> submission.handle().progress().runCount() >= 2
            && submission.handle().progress().lastRunOutcome().isPresent(), 1_000);
        assertTrue(submission.handle().cancel());
        final TaskOutcome outcome = submission.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertEquals(1, maxRunning.get());
        assertEquals(TaskOutcomeStatus.CANCELED, outcome.status());
        assertTrue(outcome.runCount() >= 2);
        assertEquals(TaskRunOutcomeStatus.SUCCEEDED, outcome.lastRunOutcome().orElseThrow().status());
    }

    @Test
    void rejectsTheSixtyFifthActiveTaskWithoutBlockingAndReleasesPermitAfterCancellation() throws Exception {
        createScheduler(1, 128);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TaskSubmission first = scheduler.submit(request("limited-0", token -> {
            firstStarted.countDown();
            release.await();
        }));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        for (int index = 1; index < 64; index++) {
            assertTrue(scheduler.submit(request("limited-" + index, token -> { })).accepted());
        }

        final long startedAt = System.nanoTime();
        final TaskSubmission overflow = scheduler.submit(request("limited-overflow", token -> { }));
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);

        assertFalse(overflow.accepted());
        assertEquals(Optional.of(TaskRejectionReason.BACKPRESSURE), overflow.rejectionReason());
        assertTrue(elapsedMillis < 100, "active-task admission must not block");
        assertTrue(first.handle().cancel());
        final TaskSubmission replacement = scheduler.submit(request("limited-replacement", token -> { }));
        assertTrue(replacement.accepted());
        release.countDown();
    }

    @Test
    void scopeOwnershipExistsBeforePhysicalRuntimeAdmission() throws Exception {
        final CountDownLatch admissionEntered = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        createScheduler(1, 8, task -> {
            admissionEntered.countDown();
            await(releaseAdmission);
            return WorkBudget.LIGHTWEIGHT;
        });
        final AtomicReference<TaskSubmission> submitted = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread submitter = new Thread(() -> {
            try {
                submitted.set(scheduler.submit(request("owned-before-admission", token -> { })));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "task-admission-probe");
        submitter.setDaemon(true);
        try {
            submitter.start();
            assertTrue(admissionEntered.await(1, TimeUnit.SECONDS));
            assertEquals(2, scopedRegistrationCount(scope),
                "the candidate cleanup must belong to the scope before runtime admission");
        } finally {
            releaseAdmission.countDown();
            joinOrInterrupt(submitter);
        }

        assertFalse(submitter.isAlive());
        assertEquals(null, failure.get());
        assertTrue(submitted.get().accepted());
        assertEquals(TaskOutcomeStatus.SUCCEEDED,
            submitted.get().handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
    }

    @Test
    void runtimeRejectionDisarmsAndRemovesPreAdmissionScopeOwnership() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final CountDownLatch admissionEntered = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        runtimeScheduler = new RuntimeScheduler(
            task -> {
                admissionEntered.countDown();
                await(releaseAdmission);
                return WorkBudget.HEAVY;
            },
            new PluginExecutorRegistry(500L, 1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler("plugin.tasks", runtimeScheduler, scope, evidence);
        final AtomicReference<TaskSubmission> submitted = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread submitter = new Thread(() -> {
            try {
                submitted.set(scheduler.submit(request("runtime-rejected-cleanup", token -> { })));
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "runtime-rejection-probe");
        submitter.setDaemon(true);
        try {
            submitter.start();
            assertTrue(admissionEntered.await(1, TimeUnit.SECONDS));
            assertEquals(2, scopedRegistrationCount(scope));
        } finally {
            releaseAdmission.countDown();
            joinOrInterrupt(submitter);
        }

        assertFalse(submitter.isAlive());
        assertEquals(null, failure.get());
        assertFalse(submitted.get().accepted());
        assertEquals(Optional.of(TaskRejectionReason.POLICY_REJECTED),
            submitted.get().rejectionReason());
        assertEquals(1, scopedRegistrationCount(scope),
            "runtime rejection must remove the disarmed candidate cleanup");
        assertEquals(0, scheduler.activeTaskCount());
        assertEquals(64, scheduler.availableActiveTaskPermits());
        assertEquals(0, evidence.snapshot().taskHandlesCanceled());
        assertEquals(0, evidence.snapshot().taskCompletionsSettled());
        assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
    }

    @Test
    void concurrentScopeCloseDuringRuntimeRejectionDisarmsWithoutCleanupEvidence() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final CountDownLatch admissionEntered = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        final CountDownLatch ownershipCloseObserved = new CountDownLatch(1);
        final CountDownLatch releaseScopeClose = new CountDownLatch(1);
        runtimeScheduler = new RuntimeScheduler(
            task -> {
                admissionEntered.countDown();
                await(releaseAdmission);
                return WorkBudget.HEAVY;
            },
            new PluginExecutorRegistry(500L, 1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler("plugin.tasks", runtimeScheduler, scope, evidence);
        scope.register(() -> {
            ownershipCloseObserved.countDown();
            await(releaseScopeClose);
        });
        final AtomicBoolean actionStarted = new AtomicBoolean();
        final AtomicReference<TaskSubmission> submitted = new AtomicReference<>();
        final AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread submitter = new Thread(() -> {
            try {
                submitted.set(scheduler.submit(request(
                    "close-during-runtime-rejection",
                    token -> actionStarted.set(true)
                )));
            } catch (Throwable throwable) {
                submitFailure.set(throwable);
            }
        }, "runtime-rejection-race-submitter");
        final Thread closer = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable throwable) {
                closeFailure.set(throwable);
            }
        }, "runtime-rejection-race-closer");
        submitter.setDaemon(true);
        closer.setDaemon(true);

        try {
            submitter.start();
            assertTrue(admissionEntered.await(1, TimeUnit.SECONDS));
            closer.start();
            assertTrue(ownershipCloseObserved.await(1, TimeUnit.SECONDS),
                "the ownership close must run before runtime admission is decided");
            releaseAdmission.countDown();
            submitter.join(1_000);

            assertFalse(submitter.isAlive());
            assertEquals(null, submitFailure.get());
            assertNotNull(submitted.get());
            assertFalse(submitted.get().accepted());
            assertEquals(Optional.of(TaskRejectionReason.PLUGIN_INACTIVE),
                submitted.get().rejectionReason());
            final TaskOutcome outcome = submitted.get().handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskOutcomeStatus.REJECTED, outcome.status());
            assertEquals(0, outcome.runCount());
            assertTrue(outcome.lastRunOutcome().isEmpty());
            assertFalse(actionStarted.get());
            scheduler.awaitContinuationQuiescence(Duration.ofSeconds(1));
            assertEquals(0, scopedRegistrationCount(scope),
                "scope closing must have cleared every registration");
            assertEquals(0, scheduler.activeTaskCount());
            assertEquals(64, scheduler.availableActiveTaskPermits());
            assertEquals(0, scheduler.pendingCompletionCount());
            assertEquals(0, evidence.snapshot().taskHandlesCanceled(),
                "runtime rejection must disarm a close-requested ownership without cleanup");
            assertEquals(0, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
        } finally {
            releaseAdmission.countDown();
            releaseScopeClose.countDown();
            joinOrInterrupt(submitter);
            joinOrInterrupt(closer);
        }

        assertEquals(null, closeFailure.get());
    }

    @Test
    void concurrentScopeCloseDuringAdmissionCancelsOwnedCandidateWithoutLeaks() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final CountDownLatch admissionEntered = new CountDownLatch(1);
        final CountDownLatch releaseAdmission = new CountDownLatch(1);
        final CountDownLatch ownershipCloseObserved = new CountDownLatch(1);
        final CountDownLatch releaseScopeClose = new CountDownLatch(1);
        final AtomicBoolean actionStarted = new AtomicBoolean();
        runtimeScheduler = new RuntimeScheduler(
            task -> {
                admissionEntered.countDown();
                await(releaseAdmission);
                return WorkBudget.LIGHTWEIGHT;
            },
            new PluginExecutorRegistry(500L, 1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler("plugin.tasks", runtimeScheduler, scope, evidence);
        scope.register(() -> {
            ownershipCloseObserved.countDown();
            await(releaseScopeClose);
        });
        final AtomicReference<TaskSubmission> submitted = new AtomicReference<>();
        final AtomicReference<Throwable> submitFailure = new AtomicReference<>();
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread submitter = new Thread(() -> {
            try {
                submitted.set(scheduler.submit(request(
                    "close-during-admission",
                    token -> actionStarted.set(true)
                )));
            } catch (Throwable throwable) {
                submitFailure.set(throwable);
            }
        }, "concurrent-admission-submitter");
        final Thread closer = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable throwable) {
                closeFailure.set(throwable);
            }
        }, "concurrent-admission-scope-closer");
        submitter.setDaemon(true);
        closer.setDaemon(true);

        try {
            submitter.start();
            assertTrue(admissionEntered.await(1, TimeUnit.SECONDS));
            closer.start();
            assertTrue(ownershipCloseObserved.await(1, TimeUnit.SECONDS),
                "the ownership close must return before runtime admission is released");
            releaseAdmission.countDown();
            joinOrInterrupt(submitter);

            assertEquals(null, submitFailure.get());
            assertNotNull(submitted.get());
            assertTrue(submitted.get().accepted());
            final TaskOutcome outcome = submitted.get().handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskOutcomeStatus.CANCELED, outcome.status());
            assertEquals(0, outcome.runCount());
            assertTrue(outcome.lastRunOutcome().isEmpty());
            assertFalse(actionStarted.get());
            scheduler.awaitContinuationQuiescence(Duration.ofSeconds(1));
            assertEquals(0, scheduler.activeTaskCount());
            assertEquals(64, scheduler.availableActiveTaskPermits());
            assertEquals(0, scheduler.pendingCompletionCount());
            assertEquals(0, scopedRegistrationCount(scope));
            assertEquals(1, evidence.snapshot().taskHandlesCanceled());
            assertEquals(1, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
        } finally {
            releaseAdmission.countDown();
            releaseScopeClose.countDown();
            joinOrInterrupt(submitter);
            joinOrInterrupt(closer);
        }

        assertEquals(null, submitFailure.get());
        assertEquals(null, closeFailure.get());
    }

    @Test
    void concurrentScopeCloseDuringFixedDelayIterationAdmissionCancelsOwnedCandidateWithoutLeaks()
        throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        final CountDownLatch iterationAdmissionEntered = new CountDownLatch(1);
        final CountDownLatch releaseIterationAdmission = new CountDownLatch(1);
        final CountDownLatch ownershipCloseObserved = new CountDownLatch(1);
        final CountDownLatch releaseScopeClose = new CountDownLatch(1);
        final AtomicBoolean actionStarted = new AtomicBoolean();
        runtimeScheduler = new RuntimeScheduler(
            task -> {
                iterationAdmissionEntered.countDown();
                await(releaseIterationAdmission);
                return WorkBudget.LIGHTWEIGHT;
            },
            new PluginExecutorRegistry(500L, 1, 8, ignored -> { }, Clock.systemUTC()),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler("plugin.tasks", runtimeScheduler, scope, evidence);
        scope.register(() -> {
            ownershipCloseObserved.countDown();
            await(releaseScopeClose);
        });
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable throwable) {
                closeFailure.set(throwable);
            }
        }, "fixed-delay-admission-scope-closer");
        closer.setDaemon(true);

        final TaskSubmission submitted = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId("close-during-fixed-delay-admission"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ofHours(1),
            token -> actionStarted.set(true)
        ));
        assertTrue(submitted.accepted());
        try {
            assertTrue(iterationAdmissionEntered.await(1, TimeUnit.SECONDS));
            closer.start();
            assertTrue(ownershipCloseObserved.await(1, TimeUnit.SECONDS),
                "the ownership close must return before iteration admission is released");
            releaseIterationAdmission.countDown();

            final TaskOutcome outcome = submitted.handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskOutcomeStatus.CANCELED, outcome.status());
            assertEquals(0, outcome.runCount());
            assertTrue(outcome.lastRunOutcome().isEmpty());
            assertFalse(actionStarted.get());
            scheduler.awaitContinuationQuiescence(Duration.ofSeconds(1));
            assertEquals(0, scheduler.activeTaskCount());
            assertEquals(64, scheduler.availableActiveTaskPermits());
            assertEquals(0, scheduler.pendingCompletionCount());
            assertEquals(0, scopedRegistrationCount(scope));
            assertEquals(1, evidence.snapshot().taskHandlesCanceled());
            assertEquals(1, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
        } finally {
            releaseIterationAdmission.countDown();
            releaseScopeClose.countDown();
            joinOrInterrupt(closer);
        }

        assertEquals(null, closeFailure.get());
    }

    @Test
    void fixedDelayTimerStartRejectionReturnsBackpressureWithoutOwnershipOrEvidence() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final int baselineScopeRegistrations = scopedRegistrationCount(scope);
        final ArrayList<RuntimeTimerSubmission> timers = new ArrayList<>();
        try {
            for (int index = 0; index < 1_024; index++) {
                final RuntimeTimerSubmission timer = runtimeScheduler.schedule(
                    Duration.ofHours(1),
                    () -> { }
                );
                assertTrue(timer.accepted(), "timer admission failed at index " + index);
                timers.add(timer);
            }
            assertFalse(runtimeScheduler.schedule(Duration.ofHours(1), () -> { }).accepted());

            final AtomicBoolean actionStarted = new AtomicBoolean();
            final TaskSubmission rejected = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
                new TaskId("fixed-delay-timer-start-rejected"),
                PluginTaskKind.LOW_FREQUENCY_REFRESH,
                PluginTaskPriority.LOW,
                Duration.ofHours(1),
                Duration.ofHours(1),
                token -> actionStarted.set(true)
            ));

            assertFalse(rejected.accepted());
            assertEquals(Optional.of(TaskRejectionReason.BACKPRESSURE), rejected.rejectionReason());
            assertTrue(rejected.handle() instanceof RejectedTaskHandle);
            final TaskOutcome outcome = rejected.handle().completion()
                .toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertEquals(TaskOutcomeStatus.REJECTED, outcome.status());
            assertEquals(0, outcome.runCount());
            assertTrue(outcome.lastRunOutcome().isEmpty());
            assertFalse(actionStarted.get());
            assertEquals(baselineScopeRegistrations, scopedRegistrationCount(scope));
            assertEquals(0, scheduler.activeTaskCount());
            assertEquals(64, scheduler.availableActiveTaskPermits());
            assertEquals(0, scheduler.pendingCompletionCount());
            assertEquals(0, evidence.snapshot().taskHandlesCanceled());
            assertEquals(0, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
        } finally {
            for (RuntimeTimerSubmission timer : timers) {
                timer.handle().cancel();
            }
        }
    }

    @Test
    void rejectedRuntimeSubmissionReleasesActiveTaskPermitExactlyOnce() throws Exception {
        createScheduler(1, 1, 5_000L, ignored -> WorkBudget.LIGHTWEIGHT);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        assertTrue(scheduler.submit(request("runtime-first", token -> {
            firstStarted.countDown();
            release.await();
        })).accepted());
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        assertTrue(scheduler.submit(request("runtime-queued", token -> { })).accepted());

        for (int index = 0; index < 80; index++) {
            final TaskSubmission rejected = scheduler.submit(request("runtime-rejected-" + index, token -> { }));
            assertFalse(rejected.accepted());
            assertTrue(
                rejected.rejectionReason().filter(reason ->
                    reason == TaskRejectionReason.BACKPRESSURE
                        || reason == TaskRejectionReason.CIRCUIT_OPEN
                ).isPresent()
            );
        }
        assertEquals(2, scheduler.activeTaskCount());
        release.countDown();
    }

    @Test
    void policyAndBackpressureRejectionsReturnClosedReasons() throws Exception {
        createScheduler(1, 1, ignored -> WorkBudget.HEAVY);
        final TaskSubmission policy = scheduler.submit(request("policy", token -> { }));
        assertFalse(policy.accepted());
        assertEquals(Optional.of(TaskRejectionReason.POLICY_REJECTED), policy.rejectionReason());
        scope.close();
        runtimeScheduler.shutdown();

        createScheduler(1, 1, 5_000L, ignored -> WorkBudget.LIGHTWEIGHT);
        final CountDownLatch firstStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TaskSubmission first = scheduler.submit(request("first", token -> {
            firstStarted.countDown();
            release.await();
        }));
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
        final TaskSubmission queued = scheduler.submit(request("queued-two", token -> { }));
        final TaskSubmission rejected = scheduler.submit(request("overflow", token -> { }));

        assertTrue(first.accepted());
        assertTrue(queued.accepted());
        assertFalse(rejected.accepted());
        assertEquals(Optional.of(TaskRejectionReason.BACKPRESSURE), rejected.rejectionReason());
        release.countDown();
        first.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        queued.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
    }

    @Test
    void runtimeTimeoutAndFixedDelayFailureUseTerminalFailureAlgebra() throws Exception {
        createScheduler(1, 8);
        final TaskSubmission timed = scheduler.submit(request("timed", token -> Thread.sleep(2_000)));
        final java.util.concurrent.atomic.AtomicReference<String> timeoutContinuationThread =
            new java.util.concurrent.atomic.AtomicReference<>();
        final CountDownLatch timeoutContinuationRan = new CountDownLatch(1);
        timed.handle().completion().thenRun(() -> {
            timeoutContinuationThread.set(Thread.currentThread().getName());
            timeoutContinuationRan.countDown();
        });
        final TaskOutcome timedOutcome = timed.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.TIMED_OUT, timedOutcome.status());
        assertTrue(timeoutContinuationRan.await(1, TimeUnit.SECONDS));
        assertTrue(
            timeoutContinuationThread.get().contains("plugin.tasks"),
            () -> "unexpected timeout continuation thread: " + timeoutContinuationThread.get()
        );
        assertEquals(TaskRunOutcomeStatus.TIMED_OUT, timedOutcome.lastRunOutcome().orElseThrow().status());

        final TaskSubmission fixed = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId("fixed-failure"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ZERO,
            Duration.ofMillis(20),
            token -> { throw new IllegalStateException("private-fixed-value"); }
        ));
        final TaskOutcome fixedOutcome = fixed.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.FAILED, fixedOutcome.status());
        assertEquals(TaskRunOutcomeStatus.FAILED, fixedOutcome.lastRunOutcome().orElseThrow().status());
        assertFalse(fixedOutcome.failure().orElseThrow().message().contains("private-fixed-value"));
    }

    @Test
    void scopeCloseWaitsForCompletionContinuationQuiescence() throws Exception {
        createScheduler(1, 8);
        final CountDownLatch actionStarted = new CountDownLatch(1);
        final CountDownLatch continuationStarted = new CountDownLatch(1);
        final CountDownLatch releaseContinuation = new CountDownLatch(1);
        final TaskSubmission submission = scheduler.submit(request("quiescence", token -> {
            actionStarted.countDown();
            while (!token.isCancellationRequested()) {
                Thread.onSpinWait();
            }
        }));
        assertTrue(actionStarted.await(1, TimeUnit.SECONDS));
        submission.handle().completion().thenRun(() -> {
            continuationStarted.countDown();
            await(releaseContinuation);
        });

        final CountDownLatch scopeClosed = new CountDownLatch(1);
        final Thread closer = new Thread(() -> {
            try {
                scope.close();
            } catch (Exception exception) {
                throw new AssertionError("scope close failed", exception);
            } finally {
                scopeClosed.countDown();
            }
        }, "scope-closer");
        closer.start();

        assertTrue(continuationStarted.await(1, TimeUnit.SECONDS));
        assertFalse(scopeClosed.await(100, TimeUnit.MILLISECONDS));
        releaseContinuation.countDown();
        assertTrue(scopeClosed.await(1, TimeUnit.SECONDS));
        assertEquals(0, scheduler.pendingCompletionCount());
    }

    @Test
    void continuationRegisteredAfterCompletionStillUsesPluginExecutor() throws Exception {
        createScheduler(1, 8);
        final TaskSubmission submission = scheduler.submit(request(
            "late-continuation",
            token -> { }
        ));
        assertEquals(
            TaskOutcomeStatus.SUCCEEDED,
            submission.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );

        final java.util.concurrent.atomic.AtomicReference<String> continuationThread =
            new java.util.concurrent.atomic.AtomicReference<>();
        final CountDownLatch continuationRan = new CountDownLatch(1);
        submission.handle().completion().thenRun(() -> {
            continuationThread.set(Thread.currentThread().getName());
            continuationRan.countDown();
        });

        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertTrue(
            continuationThread.get().contains("plugin.tasks"),
            () -> "unexpected late-continuation thread: " + continuationThread.get()
        );
    }

    @Test
    void runtimeShutdownIsRejectedUntilPluginTaskSchedulerQuiesces() throws Exception {
        createScheduler(1, 8);
        final TaskSubmission submission = scheduler.submit(request(
            "shutdown-order",
            token -> { }
        ));
        assertTrue(submission.accepted());
        assertEquals(
            TaskOutcomeStatus.SUCCEEDED,
            submission.handle().completion().toCompletableFuture().get(2, TimeUnit.SECONDS).status()
        );

        assertThrows(IllegalStateException.class, runtimeScheduler::shutdown);
        scope.close();
        runtimeScheduler.shutdown();
    }

    @Test
    void acceptedHandleIsCanceledByItsScopeBeforeSchedulerCleanupAndReportsEvidence() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch continuationRan = new CountDownLatch(1);
        final AtomicReference<TaskSubmission> accepted = new AtomicReference<>();
        scope.register(() -> {
            assertFalse(accepted.get().handle().cancel(),
                "the handle cleanup must run before the scheduler cleanup path");
            assertEquals(1, evidence.snapshot().taskHandlesCanceled());
        });
        final TaskSubmission submission = scheduler.submit(request("scoped-cleanup", token -> {
            started.countDown();
            while (!token.isCancellationRequested()) {
                Thread.onSpinWait();
            }
        }));
        accepted.set(submission);
        submission.handle().completion().thenRun(continuationRan::countDown);
        assertTrue(started.await(1, TimeUnit.SECONDS));

        scope.close();

        assertEquals(
            TaskOutcomeStatus.CANCELED,
            submission.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status()
        );
        assertTrue(continuationRan.await(1, TimeUnit.SECONDS));
        assertEquals(1, evidence.snapshot().taskHandlesCanceled());
        assertEquals(1, evidence.snapshot().taskCompletionsSettled());
        assertEquals(1, evidence.snapshot().pluginContinuationsDrained());
    }

    @Test
    void manualCancelBeforeScopeCloseDoesNotBecomeCleanupEvidence() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final TaskSubmission submission = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId("manual-before-scope"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ofHours(1),
            Duration.ofHours(1),
            token -> { }
        ));
        assertTrue(submission.accepted());
        assertTrue(submission.handle().cancel());
        submission.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        scheduler.awaitContinuationQuiescence();

        scope.close();

        assertEquals(0, evidence.snapshot().taskHandlesCanceled());
        assertEquals(0, evidence.snapshot().taskCompletionsSettled());
        assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
    }

    @Test
    void rejectedHandleIsNotRegisteredForScopedCleanup() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final CountDownLatch started = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final TaskSubmission accepted = scheduler.submit(request("duplicate-scope", token -> {
            started.countDown();
            release.await();
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        final TaskSubmission rejected = scheduler.submit(request("duplicate-scope", token -> { }));
        assertFalse(rejected.accepted());
        assertEquals(2, scopedRegistrationCount(scope),
            "only the scheduler and accepted handle belong to the scope");
        scope.register(() -> {
            assertEquals(TaskOutcomeStatus.REJECTED,
                rejected.handle().completion().toCompletableFuture().join().status());
            assertFalse(rejected.handle().cancel());
            assertEquals(0, evidence.snapshot().taskHandlesCanceled());
        });

        scope.close();
        release.countDown();

        assertEquals(TaskOutcomeStatus.CANCELED,
            accepted.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
        assertEquals(1, evidence.snapshot().taskHandlesCanceled());
    }

    @Test
    void registrationIntoClosingScopeReturnsStructuredInactiveRejectionWithoutAdmission() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final CountDownLatch scopeCloseStarted = new CountDownLatch(1);
        final CountDownLatch releaseScopeClose = new CountDownLatch(1);
        scope.register(() -> {
            scopeCloseStarted.countDown();
            await(releaseScopeClose);
        });
        final AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        final Thread closer = new Thread(() -> {
            try {
                scope.close();
            } catch (Throwable failure) {
                closeFailure.set(failure);
            }
        }, "closed-scope-registration");
        closer.setDaemon(true);
        final AtomicBoolean actionStarted = new AtomicBoolean();
        try {
            closer.start();
            assertTrue(scopeCloseStarted.await(1, TimeUnit.SECONDS));
            final TaskSubmission rejected = scheduler.submit(request(
                "closed-registration",
                token -> actionStarted.set(true)
            ));

            assertFalse(rejected.accepted());
            assertNotNull(rejected.handle());
            assertTrue(rejected.handle() instanceof RejectedTaskHandle);
            assertEquals(Optional.of(TaskRejectionReason.PLUGIN_INACTIVE), rejected.rejectionReason());
            assertEquals(TaskOutcomeStatus.REJECTED,
                rejected.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS).status());
            assertFalse(actionStarted.get());
            assertEquals(0, scheduler.activeTaskCount());
            assertEquals(64, scheduler.availableActiveTaskPermits());
            assertEquals(0, evidence.snapshot().taskHandlesCanceled());
            assertEquals(0, evidence.snapshot().taskCompletionsSettled());
            assertEquals(0, evidence.snapshot().pluginContinuationsDrained());
        } finally {
            releaseScopeClose.countDown();
            joinOrInterrupt(closer);
        }
        assertFalse(closer.isAlive());
        assertEquals(null, closeFailure.get());
    }

    @Test
    void repeatedScopeAndSchedulerCloseDoesNotInflateCleanupEvidence() throws Exception {
        final CleanupEvidenceCollector evidence = new CleanupEvidenceCollector();
        createScheduler(1, 8, evidence);
        final TaskSubmission submission = scheduler.scheduleWithFixedDelay(new FixedDelayTaskRequest(
            new TaskId("idempotent-cleanup"),
            PluginTaskKind.LOW_FREQUENCY_REFRESH,
            PluginTaskPriority.LOW,
            Duration.ofHours(1),
            Duration.ofHours(1),
            token -> { }
        ));
        assertTrue(submission.accepted());

        scheduler.close();
        final CleanupEvidenceCollector.Snapshot first = evidence.snapshot();
        scheduler.close();
        scope.close();
        scope.close();

        assertEquals(first, evidence.snapshot());
        assertEquals(1, first.taskHandlesCanceled());
        assertEquals(1, first.taskCompletionsSettled());
    }

    @Test
    void closingScopeCancelsAcceptedTasksAndClosedSchedulerRejectsNewOnes() throws Exception {
        createScheduler(1, 8);
        final CountDownLatch started = new CountDownLatch(1);
        final TaskSubmission active = scheduler.submit(request("scope", token -> {
            started.countDown();
            while (!token.isCancellationRequested()) {
                Thread.onSpinWait();
            }
        }));
        assertTrue(started.await(1, TimeUnit.SECONDS));

        scope.close();
        final TaskOutcome canceled = active.handle().completion().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(TaskOutcomeStatus.CANCELED, canceled.status());

        final TaskSubmission rejected = scheduler.submit(request("after-close", token -> { }));
        assertFalse(rejected.accepted());
        assertEquals(Optional.of(TaskRejectionReason.PLUGIN_INACTIVE), rejected.rejectionReason());
    }

    private void createScheduler(final int workers, final int queueCapacity) {
        createScheduler(workers, queueCapacity, ignored -> WorkBudget.LIGHTWEIGHT);
    }

    private void createScheduler(
        final int workers,
        final int queueCapacity,
        final CleanupEvidenceCollector cleanupEvidence
    ) {
        runtimeScheduler = new RuntimeScheduler(
            ignored -> WorkBudget.LIGHTWEIGHT,
            new PluginExecutorRegistry(
                500L,
                workers,
                queueCapacity,
                ignored -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler(
            "plugin.tasks", runtimeScheduler, scope, cleanupEvidence
        );
    }

    private void createScheduler(
        final int workers,
        final int queueCapacity,
        final RuntimeFailureCollector failures
    ) {
        createScheduler(workers, queueCapacity, ignored -> WorkBudget.LIGHTWEIGHT, failures);
    }

    private void createScheduler(
        final int workers,
        final int queueCapacity,
        final WorkBudgetPolicy policy,
        final RuntimeFailureCollector failures
    ) {
        runtimeScheduler = new RuntimeScheduler(
            policy,
            new PluginExecutorRegistry(
                500L,
                workers,
                queueCapacity,
                ignored -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler(
            "plugin.tasks",
            runtimeScheduler,
            scope,
            new CleanupEvidenceCollector(),
            failures
        );
    }

    private void createScheduler(
        final int workers,
        final int queueCapacity,
        final WorkBudgetPolicy policy
    ) {
        createScheduler(workers, queueCapacity, 500L, policy);
    }

    private void createScheduler(
        final int workers,
        final int queueCapacity,
        final long timeoutMillis,
        final WorkBudgetPolicy policy
    ) {
        runtimeScheduler = new RuntimeScheduler(
            policy,
            new PluginExecutorRegistry(
                timeoutMillis,
                workers,
                queueCapacity,
                ignored -> { },
                Clock.systemUTC()
            ),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
        scope = new DisposableScope();
        scheduler = new RuntimePluginTaskScheduler("plugin.tasks", runtimeScheduler, scope);
    }

    private static int scopedRegistrationCount(final DisposableScope target) throws Exception {
        final var field = DisposableScope.class.getDeclaredField("closeables");
        field.setAccessible(true);
        synchronized (target) {
            return ((java.util.List<?>) field.get(target)).size();
        }
    }

    private static int scopedRegistrationCountUnchecked(final DisposableScope target) {
        try {
            return scopedRegistrationCount(target);
        } catch (Exception exception) {
            throw new AssertionError("Could not inspect disposable scope registrations", exception);
        }
    }

    private static PluginTaskRequest request(
        final String id,
        final dev.turboism.sdk.task.PluginTaskAction action
    ) {
        return new PluginTaskRequest(
            new TaskId(id),
            PluginTaskKind.COMPUTE,
            PluginTaskPriority.NORMAL,
            action
        );
    }

    private static void await(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static void joinOrInterrupt(final Thread thread) throws InterruptedException {
        if (thread.getState() == Thread.State.NEW) {
            return;
        }
        thread.join(1_000);
        if (thread.isAlive()) {
            thread.interrupt();
            thread.join(1_000);
        }
        assertFalse(thread.isAlive(), () -> "thread did not terminate: " + thread.getName());
    }

    private static void waitUntil(final java.util.function.BooleanSupplier condition, final long timeoutMillis)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertTrue(condition.getAsBoolean(), "condition did not become true before timeout");
    }
}
