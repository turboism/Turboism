package dev.turboism.core.action;

import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.plugin.Registration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeActionRegistryTest {

    private static final String PLUGIN_ID = "dev.turboism.plugin.test";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-07-08T00:00:00Z"), ZoneOffset.UTC);

    private final List<StartupReport.DiagnosticProblem> problems = new CopyOnWriteArrayList<>();
    private RuntimeScheduler scheduler;

    @AfterEach
    void shutdownScheduler() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
    }

    @Test
    void registeredHandlerIsInvokedAsynchronouslyWhenActionIsExecuted() throws InterruptedException {
        // Given
        RuntimeActionRegistry registry = registry();
        CountDownLatch completed = new CountDownLatch(1);
        AtomicReference<String> workerThread = new AtomicReference<>();
        String invokerThread = Thread.currentThread().getName();

        registry.register("test.action", new TestAction(
            "test.action",
            "Test Action",
            context -> {
                workerThread.set(Thread.currentThread().getName());
                completed.countDown();
            }
        ));

        // When
        registry.execute("test.action", new ActionRegistry.ActionContext() {});

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertNotEquals(invokerThread, workerThread.get());
    }

    @Test
    void closingRegistrationRemovesHandler() {
        // Given
        RuntimeActionRegistry registry = registry();
        AtomicInteger executions = new AtomicInteger();
        Registration registration = registry.register(
            "test.action",
            new TestAction("test.action", "Test Action", context -> executions.incrementAndGet())
        );

        // When
        registration.close();
        registry.execute("test.action", new ActionRegistry.ActionContext() {});

        // Then
        assertEquals(0, executions.get());
    }

    @Test
    void duplicateRegistrationIsHandledDeterministically() throws InterruptedException {
        // Given
        RuntimeActionRegistry registry = registry();
        AtomicInteger firstExecutions = new AtomicInteger();
        AtomicInteger secondExecutions = new AtomicInteger();
        CountDownLatch completed = new CountDownLatch(1);

        registry.register(
            "dup.action",
            new TestAction("dup.action", "First", context -> firstExecutions.incrementAndGet())
        );
        registry.register(
            "dup.action",
            new TestAction(
                "dup.action",
                "Second",
                context -> {
                    secondExecutions.incrementAndGet();
                    completed.countDown();
                }
            )
        );

        // When
        registry.execute("dup.action", new ActionRegistry.ActionContext() {});

        // Then
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertEquals(0, firstExecutions.get());
        assertEquals(1, secondExecutions.get());
        assertEquals(1, problems.size());
        assertEquals("ACTION_DUPLICATE_ID", problems.get(0).code());
    }

    @Test
    void invokerThreadReturnsImmediately() throws InterruptedException {
        // Given
        RuntimeActionRegistry registry = registry();
        CountDownLatch completed = new CountDownLatch(1);
        registry.register(
            "slow.action",
            new TestAction("slow.action", "Slow Action", context -> {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                completed.countDown();
            })
        );

        // When
        long start = System.currentTimeMillis();
        registry.execute("slow.action", new ActionRegistry.ActionContext() {});
        long elapsed = System.currentTimeMillis() - start;

        // Then
        assertTrue(elapsed < 50, "execute returned in " + elapsed + " ms");
        assertEquals(1, completed.getCount());
        assertTrue(completed.await(2, TimeUnit.SECONDS));
    }

    private RuntimeActionRegistry registry() {
        problems.clear();
        List<dev.turboism.core.diagnostics.CallbackBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginExecutorRegistry(1, 2, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        return new RuntimeActionRegistry(scheduler, problems::add, PLUGIN_ID, PermissionChecker.allowAll());
    }

    private record TestAction(
        String id,
        String label,
        Consumer<ActionRegistry.ActionContext> handler
    ) implements ActionRegistry.Action {
    }
}
