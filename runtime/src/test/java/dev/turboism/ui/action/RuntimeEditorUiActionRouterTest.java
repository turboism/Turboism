package dev.turboism.ui.action;

import dev.turboism.core.action.RuntimeActionRegistry;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.WorkBudgetPolicy;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.action.UiActionEvent;
import dev.turboism.sdk.plugin.Registration;
import dev.turboism.sdk.plugin.WorkBudget;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeEditorUiActionRouterTest {

    @Test
    void nativeCallbackRoutesToOwningPluginActionRegistry() throws Exception {
        RuntimeScheduler scheduler = scheduler();
        try {
            RuntimeActionRegistry actions = new RuntimeActionRegistry(
                scheduler,
                ignored -> { },
                "plugin.demo",
                PermissionChecker.allowAll()
            );
            CountDownLatch invoked = new CountDownLatch(1);
            actions.register("home.open", action("home.open", invoked));
            RuntimeEditorUiActionRouter router = new RuntimeEditorUiActionRouter();
            Registration binding = router.register("plugin.demo", actions);

            router.invoke("plugin.demo", "home.open");

            assertTrue(invoked.await(1, TimeUnit.SECONDS));
            binding.close();
            router.invoke("plugin.demo", "home.open");
            assertEquals(0, invoked.getCount());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void typedUiEventReachesTheOwningPluginActionContext() throws Exception {
        RuntimeScheduler scheduler = scheduler();
        try {
            RuntimeActionRegistry actions = new RuntimeActionRegistry(
                scheduler,
                ignored -> { },
                "plugin.demo",
                PermissionChecker.allowAll()
            );
            CountDownLatch invoked = new CountDownLatch(1);
            AtomicReference<Optional<UiActionEvent>> received = new AtomicReference<>(Optional.empty());
            actions.register("profile.mode.changed", new ActionRegistry.Action() {
                @Override public String id() { return "profile.mode.changed"; }
                @Override public String label() { return id(); }
                @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                    return context -> {
                        received.set(context.uiEvent());
                        invoked.countDown();
                    };
                }
            });
            RuntimeEditorUiActionRouter router = new RuntimeEditorUiActionRouter();
            router.register("plugin.demo", actions);
            UiActionEvent event = UiActionEvent.selection("mode", "safe");

            router.invoke("plugin.demo", "profile.mode.changed", Optional.of(event));

            assertTrue(invoked.await(1, TimeUnit.SECONDS));
            assertEquals(Optional.of(event), received.get());
        } finally {
            scheduler.shutdown();
        }
    }

    @Test
    void rejectsNonRuntimeActionRegistry() {
        RuntimeEditorUiActionRouter router = new RuntimeEditorUiActionRouter();
        router.register("plugin.demo", new ActionRegistry() {
            @Override public Registration register(final String id, final Action action) {
                return () -> { };
            }
        });

        assertThrows(
            IllegalStateException.class,
            () -> router.invoke("plugin.demo", "home.open")
        );
    }

    private static ActionRegistry.Action action(final String id, final CountDownLatch invoked) {
        return new ActionRegistry.Action() {
            @Override public String id() { return id; }
            @Override public String label() { return id; }
            @Override public java.util.function.Consumer<ActionRegistry.ActionContext> handler() {
                return ignored -> invoked.countDown();
            }
        };
    }

    private static RuntimeScheduler scheduler() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-23T00:00:00Z"), ZoneOffset.UTC);
        return new RuntimeScheduler(
            new WorkBudgetPolicy() {
                @Override public WorkBudget classify(final PluginTask task) {
                    return WorkBudget.LIGHTWEIGHT;
                }
            },
            new PluginWorkExecutorRegistry(1, 4, ignored -> { }, clock),
            SidecarDispatcher.noop(),
            ignored -> { }
        );
    }
}
