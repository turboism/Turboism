package dev.turboism.config;

import dev.turboism.config.NativeOptimizationConfig.Choice;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Startup facts kept separately from editable preferences; never infers installation from a
 * checkbox.
 *
 * <p>The protocols and what each of them proves are declared by
 * {@link OptimizationCatalog}, so this class only records what a contributor reported and folds it
 * into one status per persisted entry.</p>
 */
public final class NativeOptimizationDiagnostics {
    /** Requested startup state and the outcome of the verified installation attempt. */
    public record Status(Choice choice, String state, String reason) {}

    /** Aggregate state for an entry whose serving protocols did not all install. */
    public static final String STATE_PARTIAL = "partial";

    private static PerformanceSettingsConfig startup;
    private static final Map<PerformanceSettingsConfig.Field, String> OBSERVED =
            new EnumMap<>(PerformanceSettingsConfig.Field.class);
    /** Resolved preference per entry; reports never rewrite it. */
    private static final Map<Optimization, Choice> REQUESTED = new LinkedHashMap<>();
    /** Last outcome per protocol, kept apart so two hooks serving one entry cannot overwrite. */
    private static final Map<String, Report> REPORTS = new LinkedHashMap<>();

    /** One protocol's own outcome, before it is aggregated into an entry. */
    private record Report(String state, String reason) {}

    /** Publishes the complete snapshot captured before any performance hook installs. */
    public static synchronized void startup(PerformanceSettingsConfig snapshot) {
        startup = snapshot;
        OBSERVED.clear();
        REQUESTED.clear();
        REPORTS.clear();
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            var value = snapshot.value(PerformanceSettingsConfig.Field.forKey(optimization.key()));
            requested(optimization, new Choice(Boolean.parseBoolean(value.value()), value.source()));
        }
    }
    /** Clears the launch snapshot and observed statuses for isolated test suites. */
    public static synchronized void resetForTesting() {
        startup = null;
        OBSERVED.clear();
        REQUESTED.clear();
        REPORTS.clear();
    }
    /** Current launch snapshot; absent when bootstrap was not observed. */
    public static synchronized PerformanceSettingsConfig startup() {
        return startup;
    }
    /** Records a result observed from the live host, rather than inferring it from a preference. */
    public static synchronized void observed(PerformanceSettingsConfig.Field field, String result) {
        OBSERVED.put(field, result);
    }
    /** Last host observation for a non-transformer preference. */
    public static synchronized String observed(PerformanceSettingsConfig.Field field) {
        return OBSERVED.getOrDefault(field, "not-observed");
    }

    private NativeOptimizationDiagnostics() {}

    /** Records the resolved preference without replacing an observed installation outcome. */
    public static synchronized void requested(Optimization optimization, Choice choice) {
        REQUESTED.put(optimization, choice);
    }

    /**
     * Records one contributor's actual result for the configurable native optimizations. The
     * outcome is kept per protocol and aggregated per entry in {@link #snapshot()}, so two hooks
     * serving one preference can neither overwrite nor reorder each other.
     *
     * <p>A protocol that only proves a diagnostic-only probe is recorded nowhere: the probe is a
     * reviewed measurement, not a user-visible optimization, so its report never becomes a status.</p>
     */
    public static synchronized void installed(String protocol, String state, String reason) {
        if (!surfaces(protocol)) return;
        final Report previous = REPORTS.get(protocol);
        if ("pending".equals(state) && previous != null && "installed".equals(previous.state())) {
            return;
        }
        REPORTS.put(protocol, new Report(state, reason));
    }

    /**
     * Detached startup diagnostics for every persisted entry, keyed by its catalogue row. Missing
     * entries mean no proven installation attempt.
     */
    public static synchronized Map<Optimization, Status> snapshot() {
        final Map<Optimization, Status> statuses = new LinkedHashMap<>();
        for (Optimization optimization : OptimizationCatalog.persisted()) {
            final List<String> protocols = optimization.protocols();
            if (REQUESTED.containsKey(optimization) || protocols.stream().anyMatch(REPORTS::containsKey)) {
                statuses.put(optimization, aggregate(optimization, protocols));
            }
        }
        return Collections.unmodifiableMap(statuses);
    }

    /** @return {@code true} when {@code protocol} proves a persisted preference */
    private static boolean surfaces(final String protocol) {
        return OptimizationCatalog.byProtocol(protocol)
                .filter(Optimization::persisted)
                .isPresent();
    }

    /**
     * Reduces one entry's protocol reports to the user-visible status: all serving protocols
     * installed, some installed ({@link #STATE_PARTIAL} listing every protocol that did not install
     * with its own reason), or none installed, where the most significant report and the preference
     * precedence of a single report decide.
     */
    private static Status aggregate(final Optimization optimization, final List<String> protocols) {
        final Choice choice = REQUESTED.get(optimization);
        final List<String> notInstalled = new ArrayList<>();
        String installedReason = null;
        int installed = 0;
        for (final String protocol : protocols) {
            final Report report = REPORTS.get(protocol);
            if (report == null) {
                notInstalled.add(protocol + "=not-attempted");
            } else if ("installed".equals(report.state())) {
                installed++;
                if (installedReason == null) installedReason = report.reason();
            } else {
                notInstalled.add(protocol + "=" + report.reason());
            }
        }
        if (protocols.isEmpty()) return new Status(choice, "not-installed", defaultReason(choice));
        if (installed == protocols.size()) return new Status(choice, "installed", installedReason);
        if (installed > 0) return new Status(choice, STATE_PARTIAL, String.join(", ", notInstalled));
        return noneInstalled(protocols, choice);
    }

    /** No serving protocol installed: one deterministic report stands for the entry. */
    private static Status noneInstalled(final List<String> protocols, final Choice choice) {
        Report reported = null;
        for (final String protocol : protocols) {
            final Report report = REPORTS.get(protocol);
            if (report != null && (reported == null || precedence(report.state()) > precedence(reported.state()))) {
                reported = report;
            }
        }
        if (reported == null) return new Status(choice, "not-installed", defaultReason(choice));
        return new Status(
                choice,
                reported.state(),
                choice == null || choice.enabled() ? reported.reason() : "preference-disabled");
    }

    /** Significance of a non-installed report, so merging never depends on arrival order. */
    private static int precedence(final String state) {
        return switch (state) {
            case "failed" -> 3;
            case "not-installed" -> 2;
            case "pending" -> 1;
            default -> 0;
        };
    }

    /** A preference that never reached an install attempt still reports why. */
    private static String defaultReason(final Choice choice) {
        return choice == null || choice.enabled() ? "not-attempted" : "preference-disabled";
    }
}
