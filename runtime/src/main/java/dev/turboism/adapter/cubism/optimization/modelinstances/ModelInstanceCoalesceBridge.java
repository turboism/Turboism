package dev.turboism.adapter.cubism.optimization.modelinstances;

import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import javax.swing.SwingUtilities;

/**
 * Loader-neutral state machine for coalescing repeated native model-instance updates.
 *
 * <p>The injected host bytecode only reads JDK functional-interface slots from system
 * properties. Two update calls coalesce only when they describe the same native edit
 * group at the same undo count. The first call still runs immediately, so any code
 * that reads instances after an update sees current state; only an identical trailing
 * call is dropped. A successful significant edit becomes the committed token, which
 * also suppresses the common "publish inside the edit, publish again after
 * {@code endEdit}" pattern. Undo and redo invalidate both tokens, so a restored model
 * is always republished.</p>
 *
 * <p>Any reflection, host-shape or callback failure falls back to the unmodified
 * native path. Counters are exposed through {@value #STATS_PROPERTY}.</p>
 */
public final class ModelInstanceCoalesceBridge implements AutoCloseable {

    /** Opt-out switch; coalescing is enabled unless this property is {@code false}. */
    public static final String ENABLE_PROPERTY = "turboism.optimization.modelInstanceCoalesce";

    /** Entry slot for {@code Predicate<Object>} receiving the native {@code CModelSource}. */
    public static final String UPDATE_CALLBACK_PROPERTY = "turboism.model-instance-coalesce.update";

    /** Normal-return slot for {@code Consumer<Object>} receiving the native {@code CModelSource}. */
    public static final String AFTER_UPDATE_PROPERTY = "turboism.model-instance-coalesce.after-update";

    /** Entry slot for {@code Consumer<Object>} receiving the committing {@code ACEditMode}. */
    public static final String COMMIT_CALLBACK_PROPERTY = "turboism.model-instance-coalesce.commit";

    /** Normal-return slot for {@code Consumer<Object>} receiving the committing {@code ACEditMode}. */
    public static final String AFTER_COMMIT_PROPERTY = "turboism.model-instance-coalesce.after-commit";

    /** Entry slot for {@code Consumer<Object>} receiving the invalidating {@code CUndoManager}. */
    public static final String INVALIDATE_CALLBACK_PROPERTY = "turboism.model-instance-coalesce.invalidate";

    /** Payload-free statistics slot. */
    public static final String STATS_PROPERTY = "turboism.model-instance-coalesce.stats";

    private static volatile boolean configuredDefault = true;

    private final Host host;
    private final Consumer<Runnable> eventScheduler;
    private final AtomicBoolean active = new AtomicBoolean();
    private final ThreadLocal<ThreadState> states = ThreadLocal.withInitial(ThreadState::new);
    private final Predicate<Object> updateCallback = this::beforeUpdate;
    private final Consumer<Object> afterUpdate = this::afterUpdate;
    private final Consumer<Object> commitCallback = this::beforeCommit;
    private final Consumer<Object> afterCommitCallback = this::afterCommit;
    private final Consumer<Object> invalidateCallback = ignored -> invalidate();
    private final Supplier<Map<String, Long>> statistics = this::snapshot;
    private final LongAdder updates = new LongAdder(),
            skipped = new LongAdder(),
            committed = new LongAdder(),
            invalidations = new LongAdder(),
            failures = new LongAdder();
    private Properties installedProperties;
    private volatile boolean closed;

    /**
     * Resolves the small stable host surface used by the coalescer.
     *
     * @param loader the admitted host loader
     * @throws ReflectiveOperationException when any dependency is absent or mistyped
     */
    public ModelInstanceCoalesceBridge(final ClassLoader loader) throws ReflectiveOperationException {
        this(ReflectiveHost.resolve(Objects.requireNonNull(loader, "loader")), SwingUtilities::invokeLater);
    }

    ModelInstanceCoalesceBridge(final Host host) {
        this(host, SwingUtilities::invokeLater);
    }

    ModelInstanceCoalesceBridge(final Host host, final Consumer<Runnable> eventScheduler) {
        this.host = Objects.requireNonNull(host, "host");
        this.eventScheduler = Objects.requireNonNull(eventScheduler, "eventScheduler");
    }

    /** Updates the persisted-config fallback used when {@link #ENABLE_PROPERTY} is unset. */
    public static void setConfiguredDefault(final boolean enabled) {
        configuredDefault = enabled;
    }

    /** Whether the preference and any local override permit coalescing. */
    public static boolean enabledByPreference() {
        try {
            final String explicit = System.getProperty(ENABLE_PROPERTY);
            return explicit == null ? configuredDefault : Boolean.parseBoolean(explicit);
        } catch (SecurityException denied) {
            return false;
        }
    }

    /** Occupies every callback slot; refuses to replace another installation. */
    public synchronized void install() {
        if (active.get()) throw new IllegalStateException("model-instance coalescing already installed");
        final Properties properties = System.getProperties();
        synchronized (properties) {
            for (final String key : propertyKeys()) {
                if (properties.containsKey(key)) {
                    throw new IllegalStateException("model-instance coalescing slots occupied: " + key);
                }
            }
            try {
                properties.put(UPDATE_CALLBACK_PROPERTY, updateCallback);
                properties.put(AFTER_UPDATE_PROPERTY, afterUpdate);
                properties.put(COMMIT_CALLBACK_PROPERTY, commitCallback);
                properties.put(AFTER_COMMIT_PROPERTY, afterCommitCallback);
                properties.put(INVALIDATE_CALLBACK_PROPERTY, invalidateCallback);
                properties.put(STATS_PROPERTY, statistics);
                installedProperties = properties;
                active.set(true);
            } catch (RuntimeException | Error failure) {
                removeSlots(properties);
                throw failure;
            }
        }
    }

    private static String[] propertyKeys() {
        return new String[] {
            UPDATE_CALLBACK_PROPERTY,
            AFTER_UPDATE_PROPERTY,
            COMMIT_CALLBACK_PROPERTY,
            AFTER_COMMIT_PROPERTY,
            INVALIDATE_CALLBACK_PROPERTY,
            STATS_PROPERTY
        };
    }

    /**
     * Decides whether the entering update is an exact repeat of the last executed one.
     *
     * @param source native {@code CModelSource}
     * @return true only when the native body can be skipped safely
     */
    boolean beforeUpdate(final Object source) {
        if (closed || !active.get() || !enabledByPreference()) return false;
        updates.increment();
        try {
            final ThreadState state = states.get();
            final Object document = host.document(source);
            if (document == null) {
                state.pending = null;
                return false;
            }
            final Object editMode = host.editMode(document);
            final Token token;
            if (editMode != null && host.editing(editMode)) {
                final Object undo = host.currentUndo(editMode);
                if (undo == null) {
                    state.pending = null;
                    return false;
                }
                token = new Token(document, undo, host.editCount(undo));
            } else {
                final Token justCommitted = state.justCommitted;
                if (justCommitted == null
                        || justCommitted.document() != document
                        || !same(justCommitted, state.lastExecuted)) {
                    state.pending = null;
                    return false;
                }
                token = justCommitted;
            }
            state.pending = token;
            if (token.group() != null && same(token, state.lastExecuted)) {
                state.pending = null;
                skipped.increment();
                return true;
            }
            return false;
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            failures.increment();
            return false;
        }
    }

    /** Commits the token captured at entry only after the native body returned normally. */
    void afterUpdate(final Object source) {
        final ThreadState state = states.get();
        final Token pending = state.pending;
        state.pending = null;
        if (pending != null) state.lastExecuted = pending;
    }

    /** Captures the edit group before a significant edit updates the document timestamp. */
    void beforeCommit(final Object editMode) {
        if (closed || !active.get()) return;
        final ThreadState state = states.get();
        try {
            final Object undo = host.currentUndo(editMode);
            if (undo == null) {
                state.pendingCommit = null;
                return;
            }
            state.pendingCommit = new Token(host.editDocument(editMode), undo, host.editCount(undo));
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            state.pendingCommit = null;
            failures.increment();
        }
    }

    /** Publishes the committed token after the significant-edit timestamp update returns. */
    void afterCommit(final Object editMode) {
        final ThreadState state = states.get();
        final Token pending = state.pendingCommit;
        state.pendingCommit = null;
        if (pending != null) {
            state.justCommitted = pending;
            committed.increment();
            try {
                eventScheduler.accept(() -> clearJustCommitted(state, pending));
            } catch (RuntimeException | Error failure) {
                FatalErrors.rethrowIfFatal(failure);
                state.justCommitted = null;
            }
        }
    }

    @SuppressWarnings("ReferenceEquality") // Clear the exact scheduled token, not a value-equal one.
    private static void clearJustCommitted(final ThreadState state, final Token token) {
        if (state.justCommitted == token) state.justCommitted = null;
    }

    /** Undo/redo restore an older model state; no cached token may survive them. */
    void invalidate() {
        final ThreadState state = states.get();
        state.pending = null;
        state.pendingCommit = null;
        state.lastExecuted = null;
        state.justCommitted = null;
        invalidations.increment();
    }

    /** Resets every thread-visible token; intended for the close path and tests. */
    void reset() {
        states.remove();
        updates.reset();
        skipped.reset();
        committed.reset();
        invalidations.reset();
        failures.reset();
    }

    private static boolean same(final Token left, final Token right) {
        return left != null
                && right != null
                && left.document() == right.document()
                && left.group() == right.group()
                && left.editCount() == right.editCount();
    }

    /** Snapshot for diagnostics and tests. */
    public Map<String, Long> snapshot() {
        return Map.of(
                "updates", updates.sum(),
                "skipped", skipped.sum(),
                "committedTokens", committed.sum(),
                "invalidations", invalidations.sum(),
                "failures", failures.sum());
    }

    /** Clears all owned slots; outstanding callbacks immediately fall back to native. */
    @Override
    public synchronized void close() {
        closed = true;
        active.set(false);
        states.remove();
        final Properties properties = installedProperties;
        installedProperties = null;
        if (properties != null) {
            synchronized (properties) {
                removeSlots(properties);
            }
        }
    }

    private void removeSlots(final Properties properties) {
        properties.remove(UPDATE_CALLBACK_PROPERTY, updateCallback);
        properties.remove(AFTER_UPDATE_PROPERTY, afterUpdate);
        properties.remove(COMMIT_CALLBACK_PROPERTY, commitCallback);
        properties.remove(AFTER_COMMIT_PROPERTY, afterCommitCallback);
        properties.remove(INVALIDATE_CALLBACK_PROPERTY, invalidateCallback);
        properties.remove(STATS_PROPERTY, statistics);
    }

    private record Token(Object document, Object group, int editCount) {}

    private static final class ThreadState {
        private Token pending;
        private Token pendingCommit;
        private Token lastExecuted;
        private Token justCommitted;
    }

    /** Minimal host surface, kept separate so the state machine is unit-testable. */
    interface Host {
        Object document(Object source);

        Object editMode(Object document);

        boolean editing(Object editMode);

        Object currentUndo(Object editMode);

        int editCount(Object undo);

        Object editDocument(Object editMode);
    }

    private record ReflectiveHost(
            MethodHandle document,
            MethodHandle editMode,
            MethodHandle editing,
            MethodHandle currentUndo,
            MethodHandle editCount,
            MethodHandle editDocument)
            implements Host {

        static ReflectiveHost resolve(final ClassLoader loader) throws ReflectiveOperationException {
            final Class<?> source =
                    Class.forName(ModelInstanceCoalesceTarget.SOURCE_OWNER.replace('/', '.'), false, loader);
            final Class<?> document = Class.forName("com.live2d.cubism.doc.modeling.CModelingDocument", false, loader);
            final Class<?> editMode =
                    Class.forName(ModelInstanceCoalesceTarget.EDIT_MODE_OWNER.replace('/', '.'), false, loader);
            final Class<?> undo = Class.forName("com.live2d.undo.GroupUndo", false, loader);
            return new ReflectiveHost(
                    handle(source, "getDocument", "()Lcom/live2d/cubism/doc/modeling/CModelingDocument;"),
                    handle(document, "getCurrentEditMode", "()Lcom/live2d/cubism/doc/modeling/CModelingEditMode_Base;"),
                    handle(editMode, "isEditing", "()Z"),
                    handle(editMode, "getCurrentUndo", "()Lcom/live2d/undo/GroupUndo;"),
                    handle(undo, "getEditCount", "()I"),
                    handle(editMode, "getDoc", "()Lcom/live2d/cubism/doc/IDocument;"));
        }

        private static MethodHandle handle(final Class<?> owner, final String name, final String descriptor)
                throws ReflectiveOperationException {
            for (final Method method : owner.getMethods()) {
                if (!method.getName().equals(name)) continue;
                final String actual = MethodType.methodType(method.getReturnType(), method.getParameterTypes())
                        .descriptorString();
                if (actual.equals(descriptor)) {
                    return MethodHandles.publicLookup().unreflect(method);
                }
            }
            throw new NoSuchMethodException(owner.getName() + "." + name + descriptor);
        }

        @Override
        public Object document(final Object source) {
            return invoke(document, source);
        }

        @Override
        public Object editMode(final Object document) {
            return invoke(editMode, document);
        }

        @Override
        public boolean editing(final Object editMode) {
            return (boolean) invoke(this.editing, editMode);
        }

        @Override
        public Object currentUndo(final Object editMode) {
            return invoke(currentUndo, editMode);
        }

        @Override
        public int editCount(final Object undo) {
            return (int) invoke(editCount, undo);
        }

        @Override
        public Object editDocument(final Object editMode) {
            return invoke(editDocument, editMode);
        }

        private static Object invoke(final MethodHandle handle, final Object receiver) {
            try {
                return handle.invokeWithArguments(receiver);
            } catch (Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                throw new IllegalStateException(failure);
            }
        }
    }
}
