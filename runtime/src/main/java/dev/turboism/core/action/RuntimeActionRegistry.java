package dev.turboism.core.action;

import dev.turboism.core.diagnostics.StartupReport;
import dev.turboism.core.event.RuntimeEventBroker;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.sdk.action.ActionInvocationEvent;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.permissions.PermissionChecker;
import dev.turboism.sdk.action.ActionRegistry;
import dev.turboism.sdk.permission.PermissionIds;
import dev.turboism.sdk.plugin.Registration;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Runtime implementation of {@link ActionRegistry}.
 *
 * <p>Action handlers are stored keyed by action ID and executed asynchronously
 * through the {@link RuntimeScheduler} so that the invoker thread never runs
 * plugin handler code inline.
 *
 * <p>Duplicate action IDs are handled deterministically: the most recent
 * registration wins, the previous registration handle becomes a no-op, and a
 * diagnostic warning is emitted.
 */
public final class RuntimeActionRegistry implements ActionRegistry {

    private final RuntimeScheduler scheduler;
    private final Consumer<StartupReport.DiagnosticProblem> diagnosticSink;
    private final String ownerPluginId;
    private final PermissionChecker permissionChecker;
    private final RuntimeEventBroker eventBroker;
    private final ConcurrentHashMap<String, RegisteredAction> actions = new ConcurrentHashMap<>();

    public RuntimeActionRegistry(
        RuntimeScheduler scheduler,
        Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
        String ownerPluginId,
        PermissionChecker permissionChecker
    ) {
        this(scheduler, diagnosticSink, ownerPluginId, permissionChecker, null);
    }

    public RuntimeActionRegistry(
        RuntimeScheduler scheduler,
        Consumer<StartupReport.DiagnosticProblem> diagnosticSink,
        String ownerPluginId,
        PermissionChecker permissionChecker,
        RuntimeEventBroker eventBroker
    ) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.diagnosticSink = Objects.requireNonNull(diagnosticSink, "diagnosticSink");
        this.ownerPluginId = Objects.requireNonNull(ownerPluginId, "ownerPluginId");
        this.permissionChecker = Objects.requireNonNull(permissionChecker, "permissionChecker");
        this.eventBroker = eventBroker;
    }

    @Override
    public Registration register(String id, Action action) {
        String key = requireText(id, "id");
        Objects.requireNonNull(action, "action");
        permissionChecker.check(PermissionIds.TURBOISM_ACTION_REGISTER, "action.register");

        RegistrationHandle handle = new RegistrationHandle(key);
        RegisteredAction registered = new RegisteredAction(action, handle);
        RegisteredAction previous = actions.put(key, registered);
        if (previous != null) {
            // The previous registration is superseded; closing it is now a no-op.
            previous.handle().invalidate();
            emitDuplicate(key);
        }
        return handle;
    }

    /**
     * Executes the action registered under {@code id} asynchronously.
     *
     * <p>If no action is registered for the ID, a diagnostic warning is
     * emitted and the call returns without scheduling work.
     */
    public void execute(String id, ActionContext context) {
        String key = requireText(id, "id");
        Objects.requireNonNull(context, "context");
        permissionChecker.check(PermissionIds.TURBOISM_ACTION_REGISTER, "action.execute");

        RegisteredAction registered = actions.get(key);
        if (registered == null) {
            emitNotFound(key);
            return;
        }

        final RuntimeEventBroker broker = eventBroker;
        if (broker != null) {
            broker.publishRuntime(new ActionInvocationEvent(
                ownerPluginId,
                key,
                context.uiEvent(),
                context.contextMenuSelection().isPresent(),
                context.panelTabSelection().isPresent()
            ));
        }
        PluginTask task = new PluginTask("action.handle", ownerPluginId, "action:" + key, "none");
        scheduler.dispatch(task, () -> registered.action().handler().accept(context));
    }

    private void emitDuplicate(String id) {
        diagnosticSink.accept(new StartupReport.DiagnosticProblem(
            "ACTION_DUPLICATE_ID",
            "Action ID '%s' was re-registered; previous registration has been replaced.".formatted(id),
            "action://" + id,
            StartupReport.Severity.WARNING
        ));
    }

    private void emitNotFound(String id) {
        diagnosticSink.accept(new StartupReport.DiagnosticProblem(
            "ACTION_NOT_FOUND",
            "No action registered for ID '%s'.".formatted(id),
            "action://" + id,
            StartupReport.Severity.WARNING
        ));
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record RegisteredAction(Action action, RegistrationHandle handle) {}

    private final class RegistrationHandle implements Registration {
        private final String id;
        private volatile boolean valid;

        private RegistrationHandle(String id) {
            this.id = id;
            this.valid = true;
        }

        private void invalidate() {
            valid = false;
        }

        @Override
        public void close() {
            if (!valid) {
                return;
            }
            valid = false;
            actions.computeIfPresent(id, (key, registered) -> registered.handle() == this ? null : registered);
        }
    }
}
