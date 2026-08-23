package dev.turboism.core.action;

import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionInvocationEvent;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
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
    void acceptedInvocationPublishesDetachedUiFact() throws Exception {
        problems.clear();
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events =
            new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 2, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.action-observer");
        final AtomicReference<ActionInvocationEvent> observed = new AtomicReference<>();
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(observer.key(), ActionInvocationEvent.class, event -> {
            observed.set(event);
            delivered.countDown();
        });
        observer.activate();
        final RuntimeActionRegistry registry = new RuntimeActionRegistry(
            scheduler,
            problems::add,
            PLUGIN_ID,
            PermissionChecker.allowAll(),
            broker
        );
        registry.register("test.action", new TestAction(
            "test.action",
            "Test Action",
            ignored -> { }
        ));

        registry.execute("test.action", new ActionRegistry.ActionContext() {
            @Override
            public java.util.Optional<UiActionEvent> uiEvent() {
                return java.util.Optional.of(UiActionEvent.toggle("enabled", true));
            }
        });

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(PLUGIN_ID, observed.get().pluginId());
        assertEquals("test.action", observed.get().actionId());
        assertTrue(observed.get().uiEvent().isPresent());
        assertEquals("enabled", observed.get().uiEvent().orElseThrow().sourceId());
    }

    @Test
    void rejectedInvocationPublishesNoActionFact() throws Exception {
        problems.clear();
        final List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events =
            new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            task -> "action.handle".equals(task.taskType())
                ? WorkBudget.REJECTED
                : WorkBudget.LIGHTWEIGHT,
            new PluginWorkExecutorRegistry(1, 2, events::add, CLOCK),
            SidecarDispatcher.noop(),
            events::add
        );
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner observer = broker.admit("plugin.action-observer");
        final AtomicInteger deliveries = new AtomicInteger();
        broker.subscribe(
            observer.key(),
            ActionInvocationEvent.class,
            ignored -> deliveries.incrementAndGet()
        );
        observer.activate();
        final RuntimeActionRegistry registry = new RuntimeActionRegistry(
            scheduler,
            problems::add,
            PLUGIN_ID,
            PermissionChecker.allowAll(),
            broker
        );
        final AtomicInteger executions = new AtomicInteger();
        registry.register("test.action", new TestAction(
            "test.action",
            "Test Action",
            ignored -> executions.incrementAndGet()
        ));

        registry.execute("test.action", new ActionRegistry.ActionContext() { });

        scheduler.dispatch(
            new PluginTask(
                "event.subscribe",
                "plugin.action-observer",
                "admission barrier",
                "none"
            ),
            () -> { }
        );
        observer.beginClosing();
        assertTrue(observer.awaitQuiescence(java.time.Duration.ofSeconds(1)));
        assertEquals(0, executions.get());
        assertEquals(0, deliveries.get());
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
        List<dev.turboism.core.diagnostics.PluginWorkBudgetEvent> events = new CopyOnWriteArrayList<>();
        scheduler = new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 2, events::add, CLOCK),
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
