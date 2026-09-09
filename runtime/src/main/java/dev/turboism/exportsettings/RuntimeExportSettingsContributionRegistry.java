package dev.turboism.exportsettings;

import dev.turboism.sdk.cubism.export.ExportSettingsContribution;
import dev.turboism.sdk.cubism.export.ExportSettingsContributionService;
import dev.turboism.sdk.cubism.export.ExportSettingsDecision;
import dev.turboism.sdk.cubism.id.ModelId;
import dev.turboism.sdk.plugin.Registration;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Plugin-scoped inert registry for embedded-model Export Settings contributions.
 *
 * <p>The registry owns one plugin generation. It records contributions keyed by local
 * option id and resolves a host decision without holding its lifecycle lock while plugin
 * code runs. A selected option is deliberately rejected in this foundation slice, even
 * when its callback asks to proceed unchanged.</p>
 */
public final class RuntimeExportSettingsContributionRegistry
    implements ExportSettingsContributionService, AutoCloseable {

    /** Bounded rejection identities surfaced through the runtime decision boundary. */
    static final String STALE_GENERATION_KEY = "export-settings.stale-generation";
    static final String RECURSION_KEY = "export-settings.recursive";
    static final String UNKNOWN_OPTION_KEY = "export-settings.option-unknown";
    static final String CALLBACK_FAILED_KEY = "export-settings.callback-failed";
    static final String PROCEED_UNEXPECTED_KEY = "export-settings.proceed-unexpected";
    static final String CALLBACK_DRAIN_TIMEOUT_KEY = "export-settings.callback-drain-timeout";
    private static final long CALLBACK_DRAIN_TIMEOUT_MILLIS = 5_000L;

    private final String pluginId;
    private final long generation;
    private final Object lifecycleLock = new Object();
    private final Map<String, ContributionEntry> contributions = new LinkedHashMap<>();
    private final ThreadLocal<Integer> callbackDepth = ThreadLocal.withInitial(() -> 0);
    private boolean closed;
    private int activeCallbacks;

    public RuntimeExportSettingsContributionRegistry(final String pluginId, final long generation) {
        this.pluginId = requireText(pluginId, "pluginId");
        if (generation < 0L) {
            throw new IllegalArgumentException("generation must not be negative");
        }
        this.generation = generation;
    }

    @Override
    public Registration contribute(final ExportSettingsContribution contribution) {
        final ExportSettingsContribution requested = Objects.requireNonNull(contribution, "contribution");
        final ContributionEntry entry = new ContributionEntry(requested);
        synchronized (lifecycleLock) {
            if (closed) {
                throw new IllegalStateException("export settings contribution registry is closed");
            }
            if (contributions.putIfAbsent(requested.optionId(), entry) != null) {
                throw new IllegalArgumentException(
                    "duplicate export settings option id: " + requested.optionId()
                );
            }
        }
        return new Registration() {
            private final AtomicBoolean registrationClosed = new AtomicBoolean();

            @Override
            public void close() {
                if (!registrationClosed.compareAndSet(false, true)) {
                    return;
                }
                synchronized (lifecycleLock) {
                    contributions.remove(requested.optionId(), entry);
                }
            }
        };
    }

    /** Returns the exact plugin id owned by this registry. */
    String pluginId() {
        return pluginId;
    }

    /** The generation token captured by the host flow and required by {@link #invoke}. */
    long generation() {
        return generation;
    }

    /**
     * Inert decision invocation for the future host orchestration phase.
     *
     * <p>An unselected option bypasses callbacks and returns {@code PROCEED_UNCHANGED},
     * preserving native passthrough. A selected option obtains a callback execution lease,
     * invokes plugin code outside {@code lifecycleLock}, and rechecks the registration under
     * that lock. Every selected result is still rejected in this slice.</p>
     */
    ExportSettingsDecision invoke(
        final String optionId,
        final boolean selected,
        final String documentId,
        final ModelId modelId,
        final long expectedGeneration
    ) {
        Objects.requireNonNull(optionId, "optionId");
        Objects.requireNonNull(documentId, "documentId");
        Objects.requireNonNull(modelId, "modelId");

        final ContributionEntry entry;
        final int previousDepth;
        synchronized (lifecycleLock) {
            if (closed || expectedGeneration != generation) {
                return ExportSettingsDecision.reject(STALE_GENERATION_KEY);
            }
            if (!selected) {
                return ExportSettingsDecision.proceedUnchanged();
            }
            previousDepth = callbackDepth.get();
            if (previousDepth > 0) {
                return ExportSettingsDecision.reject(RECURSION_KEY);
            }
            entry = contributions.get(optionId);
            if (entry == null) {
                return ExportSettingsDecision.reject(UNKNOWN_OPTION_KEY);
            }
            callbackDepth.set(previousDepth + 1);
            activeCallbacks++;
        }

        ExportSettingsDecision callbackDecision = null;
        Throwable callbackFailure = null;
        try {
            try {
                callbackDecision = entry.contribution().callback().decide(true, documentId, modelId);
            } catch (Throwable failure) {
                callbackFailure = failure;
            }
        } finally {
            if (previousDepth == 0) {
                callbackDepth.remove();
            } else {
                callbackDepth.set(previousDepth);
            }
        }

        synchronized (lifecycleLock) {
            final ExportSettingsDecision result;
            if (closed || expectedGeneration != generation || contributions.get(optionId) != entry) {
                result = ExportSettingsDecision.reject(STALE_GENERATION_KEY);
            } else if (callbackFailure != null || callbackDecision == null) {
                result = ExportSettingsDecision.reject(CALLBACK_FAILED_KEY);
            } else if (callbackDecision.outcome() == ExportSettingsDecision.Outcome.PROCEED_UNCHANGED) {
                result = ExportSettingsDecision.reject(PROCEED_UNEXPECTED_KEY);
            } else {
                result = callbackDecision;
            }
            activeCallbacks--;
            lifecycleLock.notifyAll();
            return result;
        }
    }

    /** Immutable ordered snapshot of the currently contributed options. */
    List<ExportSettingsContribution> snapshotContributions() {
        synchronized (lifecycleLock) {
            return contributions.values().stream()
                .map(ContributionEntry::contribution)
                .toList();
        }
    }

    /**
     * Plugin scope cleanup. New callbacks are rejected immediately; an already leased
     * callback is allowed to finish, with a bounded drain for a different closing thread.
     */
    @Override
    public void close() {
        final boolean reentrant;
        final long deadline;
        synchronized (lifecycleLock) {
            if (closed) {
                return;
            }
            closed = true;
            contributions.clear();
            lifecycleLock.notifyAll();
            reentrant = callbackDepth.get() > 0;
            deadline = System.nanoTime()
                + CALLBACK_DRAIN_TIMEOUT_MILLIS * 1_000_000L;
            if (reentrant) {
                return;
            }
            while (activeCallbacks > 0) {
                final long remainingNanos = deadline - System.nanoTime();
                if (remainingNanos <= 0L) {
                    throw new IllegalStateException(CALLBACK_DRAIN_TIMEOUT_KEY);
                }
                try {
                    final long millis = Math.max(1L, remainingNanos / 1_000_000L);
                    lifecycleLock.wait(millis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(CALLBACK_DRAIN_TIMEOUT_KEY, interrupted);
                }
            }
        }
    }

    private record ContributionEntry(ExportSettingsContribution contribution) {
        private ContributionEntry {
            Objects.requireNonNull(contribution, "contribution");
        }
    }

    private static String requireText(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
