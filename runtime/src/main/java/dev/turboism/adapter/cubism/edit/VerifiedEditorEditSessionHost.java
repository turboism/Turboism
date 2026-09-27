package dev.turboism.adapter.cubism.edit;

import dev.turboism.adapter.cubism.editor.history.EditorHistorySnapshotProvider;
import dev.turboism.adapter.cubism.editor.transaction.EditorAuthoringTransactionCoordinator;
import dev.turboism.adapter.cubism.editor.transaction.VerifiedEditorAuthoringTransactionHost;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorEditSessionSelectorContract;
import dev.turboism.sdk.cubism.edit.EditSessionException;
import dev.turboism.sdk.cubism.edit.EditUnavailableException;
import dev.turboism.sdk.cubism.history.HistorySnapshot;

import javax.swing.SwingUtilities;
import java.lang.reflect.InvocationTargetException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Verified Editor-model implementation of the edit-session host boundary (spec 046, T2).
 *
 * <p>Every native member resolves through the {@link VerifiedMemberResolver}: session admission
 * requires the {@code cubism.editor-model.edit.session.edit-begin} capability row over {@link
 * EditorEditSessionSelectorContract#SESSION_ADMISSION_REQUIRED_ALIASES}, the revert path requires
 * the {@code cubism.editor-model.undo.revert} row, and the main-window handle requires the
 * declared {@code main-frame.main-window} / {@code main-frame.jframe} members. Any unverified
 * member fails closed — {@code admits}/{@code undoRevertVerified} return {@code false} and the
 * manager refuses the session before a mutating member is reached.</p>
 */
public final class VerifiedEditorEditSessionHost implements EditorEditSessionHost {

    /** Bounded synchronous wait for host-thread dispatch (spec 046, decision 6). */
    public static final long DEFAULT_DISPATCH_TIMEOUT_MS = 30_000;

    private static final String UNDO_MANAGER_ALIAS =
        "cubism.editor-history.document.undo-manager";
    private static final String UNDO_REVERT_ALIAS =
        "cubism.editor-history.manager.revert";

    private final VerifiedMemberResolver resolver;
    private final Supplier<VerifiedEditorAuthoringTransactionHost.NativeBinding> current;
    private final EditorHistorySnapshotProvider history;
    private final Object editLock = new Object();
    private final Map<Object, Object> sessionEdits = new IdentityHashMap<>();
    private final long dispatchTimeoutMs;

    /**
     * Creates a host over one reviewed Editor-model resolver.
     *
     * @param resolver verified member resolver for the active connection
     * @param current generation-bound native binding supplier (shared with the model access)
     * @param generation current model binding generation supplier
     */
    public VerifiedEditorEditSessionHost(
        final VerifiedMemberResolver resolver,
        final Supplier<VerifiedEditorAuthoringTransactionHost.NativeBinding> current,
        final LongSupplier generation
    ) {
        this(resolver, current, generation, DEFAULT_DISPATCH_TIMEOUT_MS);
    }

    public VerifiedEditorEditSessionHost(
        final VerifiedMemberResolver resolver,
        final Supplier<VerifiedEditorAuthoringTransactionHost.NativeBinding> current,
        final LongSupplier generation,
        final long dispatchTimeoutMs
    ) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.current = Objects.requireNonNull(current, "current");
        this.history = new EditorHistorySnapshotProvider(
            () -> Optional.of(this.resolver),
            Objects.requireNonNull(generation, "generation")
        );
        if (dispatchTimeoutMs <= 0) {
            throw new IllegalArgumentException("dispatchTimeoutMs must be positive");
        }
        this.dispatchTimeoutMs = dispatchTimeoutMs;
    }

    @Override
    public Optional<EditorAuthoringTransactionCoordinator.Binding> currentBinding(
        final String pluginId
    ) {
        final String owner = Objects.requireNonNull(pluginId, "pluginId").strip();
        if (owner.isEmpty()) {
            throw new IllegalArgumentException("pluginId must not be blank");
        }
        try {
            final VerifiedEditorAuthoringTransactionHost.NativeBinding binding =
                Objects.requireNonNull(current.get(), "current binding");
            return Optional.of(new EditorAuthoringTransactionCoordinator.Binding(
                owner,
                binding.identity(),
                binding.generation(),
                binding.identity(),
                binding.generation(),
                Thread.currentThread()
            ));
        } catch (RuntimeException unavailable) {
            return Optional.empty();
        }
    }

    @Override
    public boolean isCurrent(final EditorAuthoringTransactionCoordinator.Binding expected) {
        Objects.requireNonNull(expected, "expected");
        try {
            final VerifiedEditorAuthoringTransactionHost.NativeBinding active =
                Objects.requireNonNull(current.get(), "current binding");
            return expected.documentGeneration() == active.generation()
                && expected.modelGeneration() == active.generation()
                && expected.documentIdentity().equals(active.identity())
                && expected.modelIdentity().equals(active.identity());
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    @Override
    public boolean admits(final EditorAuthoringTransactionCoordinator.Binding binding) {
        Objects.requireNonNull(binding, "binding");
        try {
            return isCurrent(binding)
                && resolver.authorizesFeature(
                    EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                    EditorEditSessionSelectorContract.EDIT_BEGIN_CAPABILITY_ID,
                    EditorEditSessionSelectorContract.SESSION_ADMISSION_REQUIRED_ALIASES
                );
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public HistorySnapshot history(final EditorAuthoringTransactionCoordinator.Binding binding) {
        if (!isCurrent(binding)) {
            return HistorySnapshot.unavailable();
        }
        final HistorySnapshot snapshot = history.snapshot();
        return snapshot.availability() == HistorySnapshot.Availability.AVAILABLE
            && snapshot.generation() == binding.documentGeneration()
            ? snapshot
            : HistorySnapshot.unavailable();
    }

    @Override
    public Object beginEdit(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final String label
    ) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode",
            active.document()
        );
        final Object edit = resolver.invoke(
            "cubism.editor-model.edit-mode.begin",
            editMode,
            label
        );
        if (edit == null) {
            throw new IllegalStateException("Editor edit session did not begin");
        }
        synchronized (editLock) {
            if (sessionEdits.put(edit, editMode) != null) {
                throw new IllegalStateException("Editor edit session token identity was reused");
            }
        }
        return edit;
    }

    @Override
    public void endEdit(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object edit,
        final boolean cancel
    ) {
        final Object editMode;
        synchronized (editLock) {
            editMode = sessionEdits.get(Objects.requireNonNull(edit, "edit"));
        }
        if (editMode == null) {
            throw new IllegalArgumentException("Editor edit session token is invalid or closed");
        }
        resolver.invoke(
            "cubism.editor-model.edit-mode.end",
            editMode,
            cancel,
            null
        );
        // The token is evicted only after the native close succeeded — a failed endEdit keeps
        // it valid so the recovery path can retry the bracket close.
        synchronized (editLock) {
            sessionEdits.remove(edit);
        }
    }

    @Override
    public void undoEditGroup(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object edit
    ) {
        synchronized (editLock) {
            if (!sessionEdits.containsKey(Objects.requireNonNull(edit, "edit"))) {
                throw new IllegalArgumentException(
                    "Editor edit session token is invalid or closed");
            }
        }
        resolver.invoke("cubism.editor-model.undo.group-undo", edit);
    }

    @Override
    public Object currentEditGroup(
        final EditorAuthoringTransactionCoordinator.Binding binding
    ) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        final Object editMode = resolver.invoke(
            "cubism.editor-model.modeling-document.edit-mode",
            active.document()
        );
        return resolver.invoke(
            "cubism.editor-model.edit-mode.current-undo",
            editMode
        );
    }

    @Override
    public void undoGroup(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final Object group
    ) {
        currentFor(binding);
        resolver.invoke(
            "cubism.editor-model.undo.group-undo",
            Objects.requireNonNull(group, "group")
        );
    }

    @Override
    public void undoRedoTo(
        final EditorAuthoringTransactionCoordinator.Binding binding,
        final int position
    ) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        final Object manager = resolver.invoke(UNDO_MANAGER_ALIAS, active.document());
        resolver.invoke(
            "cubism.editor-history.manager.move-to",
            manager,
            Integer.valueOf(position)
        );
    }

    @Override
    public boolean undoRevertVerified(final EditorAuthoringTransactionCoordinator.Binding binding) {
        Objects.requireNonNull(binding, "binding");
        try {
            return resolver.authorizesFeature(
                EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                EditorEditSessionSelectorContract.UNDO_REVERT_CAPABILITY_ID,
                EditorEditSessionSelectorContract.UNDO_REVERT_REQUIRED_ALIASES
            );
        } catch (RuntimeException failure) {
            return false;
        }
    }

    @Override
    public void revert(final EditorAuthoringTransactionCoordinator.Binding binding) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        final Object manager = resolver.invoke(UNDO_MANAGER_ALIAS, active.document());
        resolver.invoke(UNDO_REVERT_ALIAS, manager);
    }

    @Override
    public Optional<Object> mainWindow(final EditorAuthoringTransactionCoordinator.Binding binding) {
        Objects.requireNonNull(binding, "binding");
        try {
            final Object app = resolver.invokeStatic(
                "cubism.editor-model.app-controller.instance"
            );
            final Object mainFrame = resolver.invoke(
                "cubism.editor-model.app-controller.main-frame",
                app
            );
            final Object window = resolver.invoke(
                "cubism.editor-model.main-frame.main-window",
                mainFrame
            );
            final Object jframe = resolver.invoke(
                "cubism.editor-model.main-frame.jframe",
                window
            );
            return Optional.ofNullable(jframe);
        } catch (RuntimeException unverified) {
            return Optional.empty();
        }
    }

    @Override
    public void refreshAfterSession(final EditorAuthoringTransactionCoordinator.Binding binding) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        resolver.invoke(
            "cubism.editor-model.model-source.update-instances",
            active.source()
        );
        final Object app = resolver.invokeStatic(
            "cubism.editor-model.app-controller.instance"
        );
        final Object completePack = resolver.invoke(
            "cubism.editor-model.app-controller.complete-pack",
            app
        );
        // The official session refresh runs once at session end — palette updates post
        // deferred UI callbacks, so they must run after the native edit bracket closed;
        // mid-session they could open a host edit that displaces the session's group.
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-parameter",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-part-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.update-deformer-palette",
            completePack,
            Boolean.TRUE
        );
        resolver.invoke(
            "cubism.editor-model.complete-pack.repaint-canvas",
            completePack,
            Boolean.TRUE
        );
    }

    @Override
    public EditSessionOpsAccess opsAccess(
        final EditorAuthoringTransactionCoordinator.Binding binding
    ) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active = currentFor(binding);
        return new VerifiedOpsAccess(active);
    }

    @Override
    public <T> T dispatch(final String label, final HostTask<T> task) throws EditSessionException {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(task, "task");
        if (SwingUtilities.isEventDispatchThread()) {
            return task.run();
        }
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Object> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        SwingUtilities.invokeLater(() -> {
            try {
                result.set(task.run());
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                done.countDown();
            }
        });
        try {
            if (!done.await(dispatchTimeoutMs, TimeUnit.MILLISECONDS)) {
                throw new EditUnavailableException(
                    "cubism.edit.dispatch-timeout",
                    label + " timed out waiting for the Cubism host thread"
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new EditUnavailableException(
                "cubism.edit.dispatch-interrupted",
                label + " was interrupted waiting for the Cubism host thread"
            );
        }
        if (failure.get() instanceof EditSessionException exception) throw exception;
        if (failure.get() instanceof RuntimeException exception) throw exception;
        if (failure.get() instanceof Error error) throw error;
        if (failure.get() != null) {
            throw new IllegalStateException(label + " host operation failed", failure.get());
        }
        @SuppressWarnings("unchecked") final T value = (T) result.get();
        return value;
    }

    @Override
    public String diagnosticId(final String code, final Throwable failure) {
        return Objects.requireNonNull(code, "code");
    }

    private VerifiedEditorAuthoringTransactionHost.NativeBinding currentFor(
        final EditorAuthoringTransactionCoordinator.Binding expected
    ) {
        final VerifiedEditorAuthoringTransactionHost.NativeBinding active =
            Objects.requireNonNull(current.get(), "current binding");
        if (expected.documentGeneration() != active.generation()
            || expected.modelGeneration() != active.generation()
            || !expected.documentIdentity().equals(active.identity())
            || !expected.modelIdentity().equals(active.identity())) {
            throw new IllegalStateException("Editor edit session binding is stale");
        }
        return active;
    }

    /**
     * Verified member surface bound to one session's native binding (spec 046, T3). Every member
     * call resolves through the {@link VerifiedMemberResolver}; {@link #authorizesFeature} gates
     * each operation on its declared capability row before a member is reached.
     */
    private final class VerifiedOpsAccess implements EditSessionOpsAccess {

        private final VerifiedEditorAuthoringTransactionHost.NativeBinding binding;

        private VerifiedOpsAccess(
            final VerifiedEditorAuthoringTransactionHost.NativeBinding binding
        ) {
            this.binding = binding;
        }

        @Override
        public Object document() {
            return binding.document();
        }

        @Override
        public Object modelSource() {
            return binding.source();
        }

        @Override
        public Object model() {
            return binding.model();
        }

        @Override
        public boolean authorizesFeature(
            final String capabilityId,
            final java.util.Set<String> aliases
        ) {
            try {
                return resolver.authorizesFeature(
                    EditorEditSessionSelectorContract.ADAPTER_SLICE_ID,
                    capabilityId,
                    aliases
                );
            } catch (RuntimeException failure) {
                return false;
            }
        }

        @Override
        public Object invoke(final String alias, final Object target, final Object... arguments) {
            return resolver.invoke(alias, target, arguments);
        }

        @Override
        public Object invokeStatic(final String alias, final Object... arguments) {
            return resolver.invokeStatic(alias, arguments);
        }

        @Override
        public Object construct(final String alias, final Object... arguments) {
            return resolver.construct(alias, arguments);
        }

        @Override
        public Object readStaticField(final String alias) {
            return resolver.readStaticField(alias);
        }

        @Override
        public boolean isInstance(final String alias, final Object value) {
            return resolver.isInstance(alias, value);
        }
    }
}
