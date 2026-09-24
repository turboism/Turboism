package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.Registration;

import java.awt.Container;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Shared runtime authority for the inert native embedded-model Export Settings bridge.
 *
 * <p>The authority aggregates plugin-generation-local registries, owns dialog state by
 * host owner identity and epoch, and never performs export or model mutation. It may
 * attach the default-off option panel, but a checked option is rejected in this slice.</p>
 */
public final class RuntimeExportSettingsAuthority
    implements NativeExportSettingsDialogBridge.Handler, AutoCloseable {

    /** Bounded rejection identities reported to the native gate. */
    public static final String ATTACH_FAILED_KEY = "export-settings.attach-failed";
    public static final String OPTION_AMBIGUOUS_KEY = "export-settings.option-ambiguous";
    public static final String STALE_PLUGIN_KEY = "export-settings.stale-plugin";
    public static final String STALE_HOST_KEY = "export-settings.stale-host";
    public static final String CLEANUP_FAILED_KEY = "export-settings.cleanup-failed";
    /** The contributed selection could not be read back from the attached panel. */
    public static final String SELECTION_UNREADABLE_KEY = "export-settings.selection-unreadable";
    /** The dialog's captured host identity could not be re-read at confirm time. */
    public static final String IDENTITY_UNAVAILABLE_KEY = "export-settings.identity-unavailable";
    /** The live host identity no longer matches the identity captured at attach. */
    public static final String IDENTITY_MISMATCH_KEY = "export-settings.identity-mismatch";
    /** A dialog whose decision was already consumed must never be confirmed again. */
    public static final String DIALOG_CONSUMED_KEY = "export-settings.dialog-consumed";

    private final Object bindingsLock = new Object();
    private final Map<String, Binding> bindings = new LinkedHashMap<>();
    private final Object dialogsLock = new Object();
    private final IdentityHashMap<Object, DialogState> dialogs = new IdentityHashMap<>();
    /** Tombstones keyed by dialog owner, holding the invalidation reason for diagnostics. */
    private final IdentityHashMap<Object, String> invalidDialogs = new IdentityHashMap<>();
    private final ThreadLocal<Boolean> inFlight = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private final Supplier<Optional<ExportSettingsIdentity>> identitySource;
    private final Supplier<ExportSettingsAttachBackend> backendFactory;
    private volatile long hostGeneration;
    private volatile boolean closed;
    /**
     * The exporter-side chooser redirect seam. Set by the bootstrap installer once the
     * exporter bytes verifiably carry the injected redirect; without it no protected-export
     * session may arm, because a staging redirect could not be proven.
     */
    private volatile boolean protectedExportRedirectSeamInstalled;
    /** Armed-session orchestrator, or {@code null} when protected export is not wired. */
    private volatile ProtectedExportOrchestrator protectedExportOrchestrator;
    /**
     * User-visible sink for vetoed confirmations. Every decision-gate veto reports its
     * bounded reason here; the default sink is a no-op so the authority stays inert
     * without a product surface.
     */
    private volatile Consumer<ExportSettingsVetoDiagnostic> vetoReporter =
        diagnostic -> { };

    public RuntimeExportSettingsAuthority(
        final Supplier<Optional<ExportSettingsIdentity>> identitySource
    ) {
        this(identitySource, ExportSettingsAttachBackend::new);
    }

    RuntimeExportSettingsAuthority(
        final Supplier<Optional<ExportSettingsIdentity>> identitySource,
        final Supplier<ExportSettingsAttachBackend> backendFactory
    ) {
        this.identitySource = Objects.requireNonNull(identitySource, "identitySource");
        this.backendFactory = Objects.requireNonNull(backendFactory, "backendFactory");
    }

    /** Registers one plugin-scoped registry and its label resolver. */
    public Registration register(
        final String pluginId,
        final long pluginGeneration,
        final RuntimeExportSettingsContributionRegistry registry,
        final Function<String, String> labelResolver
    ) {
        requireText(pluginId, "pluginId");
        if (pluginGeneration < 0L) {
            throw new IllegalArgumentException("pluginGeneration must not be negative");
        }
        final RuntimeExportSettingsContributionRegistry requestedRegistry =
            Objects.requireNonNull(registry, "registry");
        final Function<String, String> requestedLabelResolver =
            Objects.requireNonNull(labelResolver, "labelResolver");
        if (!pluginId.equals(requestedRegistry.pluginId())
            || pluginGeneration != requestedRegistry.generation()) {
            throw new IllegalArgumentException("export settings registry ownership does not match binding");
        }

        final Binding binding = new Binding(
            pluginId, pluginGeneration, requestedRegistry, requestedLabelResolver
        );
        synchronized (bindingsLock) {
            if (closed) {
                throw new IllegalStateException("export settings authority is closed");
            }
            if (bindings.putIfAbsent(pluginId, binding) != null) {
                throw new IllegalStateException("export settings plugin already registered: " + pluginId);
            }
        }
        return new Registration() {
            private final AtomicBoolean registrationClosed = new AtomicBoolean();

            @Override
            public void close() {
                if (!registrationClosed.compareAndSet(false, true)) {
                    return;
                }
                if (removeBinding(pluginId, binding)) {
                    markDialogsWithPluginStale(pluginId, pluginGeneration, binding.registry());
                }
            }
        };
    }

    /**
     * Marks the exporter chooser-redirect seam as installed. Called by the bootstrap layer
     * only after the transformer is registered and any already-loaded exporter class has
     * been retransformed; orchestration admission checks this before arming a session.
     */
    public void markProtectedExportRedirectSeamInstalled() {
        protectedExportRedirectSeamInstalled = true;
    }

    /** Whether the exporter-side staging redirect is live for this host generation. */
    public boolean protectedExportRedirectSeamInstalled() {
        return protectedExportRedirectSeamInstalled;
    }

    /**
     * Installs the armed-session orchestrator consulted by decide/redirect dispatch.
     *
     * <p>{@code null} unwires orchestration: checked options fall back to the inert
     * checked-always-reject behaviour and every chooser pick passes through.</p>
     */
    public void protectedExportOrchestrator(final ProtectedExportOrchestrator orchestrator) {
        protectedExportOrchestrator = orchestrator;
    }

    /** The wired orchestrator, or empty when protected export is unavailable. */
    public Optional<ProtectedExportOrchestrator> protectedExportOrchestrator() {
        return Optional.ofNullable(protectedExportOrchestrator);
    }

    /**
     * Installs the user-visible sink for vetoed confirmations. An armed orchestration
     * handoff stays silent because the session reports its own terminal outcome; every
     * other veto — structural, stale, plugin rejection, unwired orchestration — names
     * its bounded reason here so a confirmed dialog can never die silently.
     */
    public void vetoReporter(final Consumer<ExportSettingsVetoDiagnostic> reporter) {
        vetoReporter = Objects.requireNonNull(reporter, "reporter");
    }

    private void reportVeto(final String key, final String detail) {
        try {
            vetoReporter.accept(new ExportSettingsVetoDiagnostic(key, detail));
        } catch (Throwable ignored) {
            // A diagnostic surface must never flip the fixed fail-closed decision.
        }
    }

    @Override
    public Object redirectChooser(final Object picked) {
        final ProtectedExportOrchestrator orchestrator = protectedExportOrchestrator;
        if (orchestrator == null) {
            return picked;
        }
        return orchestrator.redirectPickedFile(picked);
    }

    /** Current verified host epoch; the orchestrator keys armed sessions to it. */
    public long hostGeneration() {
        return hostGeneration;
    }

    /** Advances the verified host epoch; open dialogs from another epoch are invalidated. */
    public void hostGeneration(final long generation) {
        if (generation < 0L) {
            throw new IllegalArgumentException("hostGeneration must not be negative");
        }
        final boolean changed = hostGeneration != generation;
        hostGeneration = generation;
        if (changed) {
            invalidateDialogs(STALE_HOST_KEY, true);
        }
    }

    /** Invalidates the current host and all dialog-scoped state. */
    public void resetHost() {
        hostGeneration = 0L;
        invalidateDialogs(STALE_HOST_KEY, true);
    }

    /**
     * Invalidates and closes all dialog attachments without taking a lock while touching Swing.
     * Tombstones remain so a delayed confirmation cannot regain native continuation.
     */
    public void clearDialogs() {
        invalidateDialogs(STALE_HOST_KEY, true);
    }

    /** Immutable snapshot of every registered contribution with resolved labels. */
    DialogSnapshot snapshot() {
        final List<Binding> bindingSnapshot;
        synchronized (bindingsLock) {
            bindingSnapshot = List.copyOf(bindings.values());
        }

        final List<ResolvedOption> resolved = new ArrayList<>();
        final Map<OptionIdentity, ResolvedOption> optionOwners = new LinkedHashMap<>();
        for (Binding binding : bindingSnapshot) {
            for (ExportSettingsContribution contribution : binding.registry().snapshotContributions()) {
                final OptionIdentity identity = new OptionIdentity(
                    binding.pluginId(), binding.pluginGeneration(), contribution.optionId()
                );
                final String label = Objects.requireNonNull(
                    binding.labelResolver().apply(contribution.labelKey()),
                    "resolved export settings label"
                );
                final ResolvedOption option = new ResolvedOption(
                    identity,
                    contribution.optionId(),
                    label,
                    binding.pluginId(),
                    binding.pluginGeneration(),
                    binding.registry()
                );
                if (optionOwners.putIfAbsent(identity, option) != null) {
                    return new DialogSnapshot(List.of(), OPTION_AMBIGUOUS_KEY);
                }
                resolved.add(option);
            }
        }
        resolved.sort(Comparator.comparing(option -> option.identity().selectionKey()));
        return new DialogSnapshot(List.copyOf(resolved), null);
    }

    @Override
    public Object attach(final Object owner, final Object container) {
        if (owner == null || container == null || closed) {
            return null;
        }
        final ProtectedExportOrchestrator orchestrator = protectedExportOrchestrator;
        if (orchestrator != null && orchestrator.suppressDialogOptions(owner)) {
            // An armed protected-export session keeps this dialog byte-identical to a
            // native unchecked run: no contributed option, no plugin preflight state.
            return null;
        }
        final DialogSnapshot snapshot;
        final IdentityRead capturedIdentity = identity();
        final long capturedHostGeneration = hostGeneration;
        try {
            snapshot = snapshot();
        } catch (Throwable failure) {
            publishFailure(owner, new DialogState(
                List.of(), ATTACH_FAILED_KEY, capturedHostGeneration,
                capturedIdentity.identity().orElse(null)
            ));
            return null;
        }
        if (snapshot.failureKey() != null) {
            publishFailure(owner, new DialogState(
                snapshot.options(), snapshot.failureKey(), capturedHostGeneration,
                capturedIdentity.identity().orElse(null)
            ));
            return null;
        }
        if (snapshot.options().isEmpty()) {
            // No plugin option means native passthrough; do not create plugin preflight state.
            return null;
        }
        if (!(container instanceof Container target)) {
            publishFailure(owner, new DialogState(
                snapshot.options(), ATTACH_FAILED_KEY, capturedHostGeneration,
                capturedIdentity.identity().orElse(null)
            ));
            return null;
        }

        final DialogState state = new DialogState(
            snapshot.options(), null, capturedHostGeneration,
            capturedIdentity.identity().orElse(null)
        );
        synchronized (dialogsLock) {
            if (closed || dialogs.containsKey(owner)) {
                return null;
            }
            invalidDialogs.remove(owner);
            dialogs.put(owner, state);
        }

        ExportSettingsAttachBackend backend = null;
        Registration attachment = null;
        try {
            backend = Objects.requireNonNull(backendFactory.get(), "backendFactory result");
            final List<ExportSettingsOptionSnapshot> options = snapshot.options().stream()
                .map(option -> new ExportSettingsOptionSnapshot(
                    option.identity().selectionKey(), option.label(),
                    option.pluginId(), option.pluginGeneration()
                ))
                .toList();
            attachment = backend.attachResolved(target, options);
            final boolean accepted;
            synchronized (dialogsLock) {
                accepted = !closed
                    && hostGeneration == capturedHostGeneration
                    && dialogs.get(owner) == state
                    && state.acceptsAttachment();
                if (accepted) {
                    state.attach(backend, attachment);
                } else {
                    state.invalidate(STALE_HOST_KEY);
                    invalidDialogs.put(owner, STALE_HOST_KEY);
                    dialogs.remove(owner, state);
                }
            }
            if (!accepted) {
                closeAttachment(attachment);
            }
        } catch (Throwable failure) {
            final String failureKey = failure instanceof ExportSettingsAttachException typed
                && typed.getMessage() != null
                ? typed.getMessage() : ATTACH_FAILED_KEY;
            synchronized (dialogsLock) {
                if (dialogs.get(owner) == state) {
                    state.invalidate(failureKey);
                }
            }
            closeAttachment(attachment);
        }
        return null;
    }

    @Override
    public void cancel(final Object owner) {
        if (owner == null) {
            return;
        }
        final DialogState state;
        synchronized (dialogsLock) {
            state = dialogs.remove(owner);
            if (state != null) {
                if (state.invalidated()) {
                    invalidDialogs.put(owner, state.failureKey());
                } else {
                    invalidDialogs.remove(owner);
                }
                state.cancel();
            }
        }
        if (state != null) {
            closeAttachment(state.takeAttachment());
        }
        final ProtectedExportOrchestrator orchestrator = protectedExportOrchestrator;
        if (orchestrator != null) {
            orchestrator.dialogCancelled(owner);
        }
    }

    @Override
    public Boolean decide(final Object owner) {
        if (closed) {
            return Boolean.FALSE;
        }
        if (owner == null) {
            return Boolean.TRUE;
        }
        if (Boolean.TRUE.equals(inFlight.get())) {
            return Boolean.FALSE;
        }
        inFlight.set(Boolean.TRUE);
        try {
            final DialogState state;
            final String staleKey;
            synchronized (dialogsLock) {
                state = dialogs.remove(owner);
                staleKey = invalidDialogs.get(owner);
                if (state != null) {
                    state.terminal();
                }
            }
            if (state == null) {
                if (staleKey != null) {
                    reportVeto(staleKey, null);
                }
                return staleKey != null ? Boolean.FALSE : Boolean.TRUE;
            }

            Decision decision;
            try {
                decision = decideState(owner, state);
            } catch (Throwable failure) {
                decision = Decision.vetoed(ATTACH_FAILED_KEY);
                state.invalidate(ATTACH_FAILED_KEY);
            }
            try {
                closeAttachment(state.takeAttachment());
            } catch (Throwable failure) {
                state.invalidate(CLEANUP_FAILED_KEY);
                if (decision.allowed() || decision.vetoKey() == null) {
                    decision = Decision.vetoed(CLEANUP_FAILED_KEY);
                }
            }
            if (state.invalidated()) {
                synchronized (dialogsLock) {
                    invalidDialogs.put(
                        owner,
                        state.failureKey() != null ? state.failureKey() : ATTACH_FAILED_KEY
                    );
                }
            } else if (!decision.allowed()) {
                // A consumed veto tombstones the owner: this dialog instance can
                // never re-acquire native continuation. Without it a re-confirm
                // on a still-open dialog evaluates as a foreign owner and runs a
                // plain native export of the original document — bypassing a
                // vetoed or session-armed decision entirely.
                synchronized (dialogsLock) {
                    invalidDialogs.put(
                        owner,
                        decision.vetoKey() != null
                            ? decision.vetoKey() : DIALOG_CONSUMED_KEY
                    );
                }
            }
            if (!decision.allowed() && decision.vetoKey() != null) {
                reportVeto(decision.vetoKey(), decision.vetoDetail());
            }
            return decision.allowed();
        } finally {
            inFlight.remove();
        }
    }

    /**
     * Decides one confirmed dialog. {@code allowed} continues the native export only
     * for a clean unchecked selection; an armed orchestration handoff vetoes silently
     * because the session reports its own outcome; every other veto carries the bounded
     * reason that the user-visible diagnostic surface must name.
     */
    private Decision decideState(final Object owner, final DialogState state) {
        if (state.failureKey() != null) {
            return Decision.vetoed(state.failureKey());
        }
        if (hostGeneration != state.hostGeneration()) {
            return Decision.vetoed(STALE_HOST_KEY);
        }
        final SelectionRead selectionRead = readSelection(state);
        if (!selectionRead.complete()) {
            return Decision.vetoed(SELECTION_UNREADABLE_KEY);
        }
        boolean selected = false;
        for (ResolvedOption option : state.options()) {
            final Boolean value = selectionRead.selection().get(option.identity().selectionKey());
            if (value == null) {
                return Decision.vetoed(SELECTION_UNREADABLE_KEY);
            }
            selected |= value;
        }
        if (selectionRead.selection().size() != state.options().size()) {
            return Decision.vetoed(SELECTION_UNREADABLE_KEY);
        }
        if (!selected) {
            return Decision.proceed();
        }

        final IdentityRead current = identity();
        if (state.capturedIdentity() == null
            || current.failed()
            || current.identity().isEmpty()) {
            return Decision.vetoed(IDENTITY_UNAVAILABLE_KEY);
        }
        if (!state.capturedIdentity().equals(current.identity().orElseThrow())) {
            return Decision.vetoed(IDENTITY_MISMATCH_KEY);
        }
        final ExportSettingsIdentity identity = current.identity().orElseThrow();
        for (ResolvedOption option : state.options()) {
            if (!Boolean.TRUE.equals(selectionRead.selection().get(option.identity().selectionKey()))) {
                continue;
            }
            if (!isCurrentBinding(option)) {
                state.invalidate(STALE_PLUGIN_KEY);
                return Decision.vetoed(STALE_PLUGIN_KEY);
            }
            final ExportSettingsDecision decision = option.registry().invoke(
                option.optionId(), true, identity.documentId(), identity.modelId(),
                option.pluginGeneration()
            );
            // A checked option always vetoes the outer native export. When the plugin's
            // own rejection (not a registry-generated failure) belongs to the orchestrated
            // option, the orchestrator may arm a protected session; any failure to arm
            // still leaves the outer export vetoed.
            if (decision.outcome() != ExportSettingsDecision.Outcome.REJECT) {
                state.invalidate(RuntimeExportSettingsContributionRegistry.PROCEED_UNEXPECTED_KEY);
                return Decision.vetoed(
                    RuntimeExportSettingsContributionRegistry.PROCEED_UNEXPECTED_KEY
                );
            }
            if (decision.messageKey().startsWith("export-settings.")) {
                return Decision.vetoed(decision.messageKey());
            }
            final ProtectedExportOrchestrator orchestrator = protectedExportOrchestrator;
            if (orchestrator != null
                && orchestrator.isOrchestrationOption(option.pluginId(), option.optionId())) {
                // Armed: the session reports its own terminal outcome. Refused: the
                // orchestrator already named the refusal through its own reporter.
                orchestrator.requestExport(owner);
                return Decision.silentVeto();
            }
            return Decision.vetoed(
                decision.messageKey(), resolveMessage(option, decision.messageKey())
            );
        }
        return Decision.vetoed(SELECTION_UNREADABLE_KEY);
    }

    /**
     * Resolves a plugin's own rejection key to its localized text through the live
     * binding; returns {@code null} when the binding is gone or the key does not
     * resolve, leaving the raw key as the displayed diagnostic.
     */
    private String resolveMessage(final ResolvedOption option, final String key) {
        synchronized (bindingsLock) {
            final Binding binding = bindings.get(option.pluginId());
            if (binding == null || binding.registry() != option.registry()) {
                return null;
            }
            try {
                final String text = binding.labelResolver().apply(key);
                return text == null || text.isBlank() || text.equals(key) ? null : text;
            } catch (Throwable failure) {
                return null;
            }
        }
    }

    private static SelectionRead readSelection(final DialogState state) {
        final ExportSettingsAttachBackend backend = state.backend();
        if (backend == null) {
            return SelectionRead.unavailable();
        }
        try {
            final Map<String, Boolean> selection = backend.selectedSnapshot();
            return selection == null
                ? SelectionRead.unavailable()
                : new SelectionRead(selection, true);
        } catch (Throwable failure) {
            return SelectionRead.unavailable();
        }
    }

    private boolean isCurrentBinding(final ResolvedOption option) {
        synchronized (bindingsLock) {
            final Binding binding = bindings.get(option.pluginId());
            return binding != null
                && binding.pluginGeneration() == option.pluginGeneration()
                && binding.registry() == option.registry();
        }
    }

    private IdentityRead identity() {
        try {
            final Optional<ExportSettingsIdentity> current = identitySource.get();
            return current == null
                ? IdentityRead.unavailable()
                : new IdentityRead(current, false);
        } catch (Throwable failure) {
            return IdentityRead.unavailable();
        }
    }

    private boolean removeBinding(
        final String pluginId,
        final Binding binding
    ) {
        synchronized (bindingsLock) {
            return bindings.remove(pluginId, binding);
        }
    }

    private void publishFailure(final Object owner, final DialogState state) {
        synchronized (dialogsLock) {
            if (closed || dialogs.containsKey(owner)) {
                return;
            }
            invalidDialogs.remove(owner);
            dialogs.put(owner, state);
        }
    }

    private void markDialogsWithPluginStale(
        final String pluginId,
        final long pluginGeneration,
        final RuntimeExportSettingsContributionRegistry registry
    ) {
        synchronized (dialogsLock) {
            for (Map.Entry<Object, DialogState> entry : dialogs.entrySet()) {
                final DialogState state = entry.getValue();
                if (state.options().stream().anyMatch(option ->
                    pluginId.equals(option.pluginId())
                        && pluginGeneration == option.pluginGeneration()
                        && registry == option.registry())) {
                    state.invalidate(STALE_PLUGIN_KEY);
                    invalidDialogs.put(entry.getKey(), STALE_PLUGIN_KEY);
                }
            }
        }
    }

    private void invalidateDialogs(final String key, final boolean remove) {
        final List<DialogState> states;
        synchronized (dialogsLock) {
            states = new ArrayList<>(dialogs.values());
            for (Map.Entry<Object, DialogState> entry : dialogs.entrySet()) {
                entry.getValue().invalidate(key);
                invalidDialogs.put(entry.getKey(), key);
            }
            if (remove) {
                dialogs.clear();
            }
        }
        for (DialogState state : states) {
            closeAttachment(state.takeAttachment());
        }
    }

    private static void closeAttachment(final Registration attachment) {
        if (attachment != null) {
            attachment.close();
        }
    }

    @Override
    public void close() {
        synchronized (bindingsLock) {
            if (closed) {
                return;
            }
            closed = true;
            bindings.clear();
        }
        invalidateDialogs(STALE_HOST_KEY, true);
    }

    /**
     * Plugin ids whose contribution registries are currently reachable from the native dialog.
     *
     * <p>This is the observable form of "the host hook can see this plugin": a plugin that is
     * loaded but never bound stays invisible here, and the native dialog shows no options for it.
     * The list is a sorted immutable copy, so it is safe to read from a report writer while the
     * Swing thread attaches dialogs.</p>
     *
     * @return bound plugin ids, sorted; empty when no plugin is bound or the authority is closed
     */
    public List<String> boundPluginIds() {
        synchronized (bindingsLock) {
            return bindings.keySet().stream().sorted().toList();
        }
    }

    /** Whether a plugin binding is currently registered (armed-session liveness gate). */
    public boolean pluginBindingLive(final String pluginId) {
        synchronized (bindingsLock) {
            return !closed && bindings.containsKey(pluginId);
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record Binding(
        String pluginId,
        long pluginGeneration,
        RuntimeExportSettingsContributionRegistry registry,
        Function<String, String> labelResolver
    ) {
        private Binding {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(registry, "registry");
            Objects.requireNonNull(labelResolver, "labelResolver");
        }
    }

    /** One immutable option with its owning registry route. */
    record ResolvedOption(
        OptionIdentity identity,
        String optionId,
        String label,
        String pluginId,
        long pluginGeneration,
        RuntimeExportSettingsContributionRegistry registry
    ) {
        ResolvedOption {
            Objects.requireNonNull(identity, "identity");
            Objects.requireNonNull(optionId, "optionId");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(registry, "registry");
        }
    }

    /** Exact plugin-generation-local identity; local option ids are never global keys. */
    private record OptionIdentity(String pluginId, long pluginGeneration, String optionId) {
        private OptionIdentity {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(optionId, "optionId");
        }

        private String selectionKey() {
            return pluginId.length() + ":" + pluginId + ":" + pluginGeneration + ":"
                + optionId.length() + ":" + optionId;
        }
    }

    /** Immutable snapshot of one dialog open; {@code failureKey} is non-null when unavailable. */
    record DialogSnapshot(List<ResolvedOption> options, String failureKey) {
        DialogSnapshot {
            options = List.copyOf(Objects.requireNonNull(options, "options"));
        }
    }

    /**
     * One decision-gate outcome: {@code allowed} continues the native export; a veto
     * with {@code vetoKey == null} is the armed orchestration handoff (the session
     * reports its own outcome); a veto with a key must reach the user-visible sink.
     */
    private record Decision(boolean allowed, String vetoKey, String vetoDetail) {
        private static Decision proceed() {
            return new Decision(true, null, null);
        }

        private static Decision silentVeto() {
            return new Decision(false, null, null);
        }

        private static Decision vetoed(final String key) {
            return new Decision(false, key, null);
        }

        private static Decision vetoed(final String key, final String detail) {
            return new Decision(false, key, detail);
        }
    }

    private record SelectionRead(Map<String, Boolean> selection, boolean complete) {
        private SelectionRead {
            selection = Map.copyOf(Objects.requireNonNull(selection, "selection"));
        }

        private static SelectionRead unavailable() {
            return new SelectionRead(Map.of(), false);
        }
    }

    private record IdentityRead(Optional<ExportSettingsIdentity> identity, boolean failed) {
        private IdentityRead {
            identity = Objects.requireNonNull(identity, "identity");
        }

        private static IdentityRead unavailable() {
            return new IdentityRead(Optional.empty(), true);
        }
    }

    /** Dialog-scoped state; transitions are synchronized on this object, never on lifecycle locks. */
    private static final class DialogState {
        private final List<ResolvedOption> options;
        private final long hostGeneration;
        private final ExportSettingsIdentity capturedIdentity;
        private String failureKey;
        private boolean invalidated;
        private boolean terminal;
        private ExportSettingsAttachBackend backend;
        private Registration attachment;

        private DialogState(
            final List<ResolvedOption> options,
            final String failureKey,
            final long hostGeneration,
            final ExportSettingsIdentity capturedIdentity
        ) {
            this.options = List.copyOf(options);
            this.failureKey = failureKey;
            this.hostGeneration = hostGeneration;
            this.capturedIdentity = capturedIdentity;
            this.invalidated = failureKey != null;
        }

        synchronized List<ResolvedOption> options() {
            return options;
        }

        synchronized String failureKey() {
            return failureKey;
        }

        synchronized long hostGeneration() {
            return hostGeneration;
        }

        synchronized ExportSettingsIdentity capturedIdentity() {
            return capturedIdentity;
        }

        synchronized boolean invalidated() {
            return invalidated;
        }

        synchronized boolean acceptsAttachment() {
            return !terminal && !invalidated && attachment == null;
        }

        synchronized void attach(
            final ExportSettingsAttachBackend requestedBackend,
            final Registration requestedAttachment
        ) {
            backend = Objects.requireNonNull(requestedBackend, "backend");
            attachment = Objects.requireNonNull(requestedAttachment, "attachment");
        }

        synchronized void invalidate(final String key) {
            failureKey = Objects.requireNonNull(key, "key");
            invalidated = true;
        }

        synchronized void cancel() {
            terminal = true;
        }

        synchronized void terminal() {
            terminal = true;
        }

        synchronized ExportSettingsAttachBackend backend() {
            return backend;
        }

        synchronized Registration takeAttachment() {
            final Registration requested = attachment;
            attachment = null;
            backend = null;
            return requested;
        }
    }
}
