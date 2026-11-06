package dev.turboism.bootstrap;

import dev.turboism.config.NativeOptimizationConfig;
import dev.turboism.config.PerformanceSettingsConfig;
import dev.turboism.config.RuntimeStartupConfig;
import dev.turboism.config.optimization.Optimization;
import dev.turboism.config.optimization.OptimizationCatalog;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One launch's resolved admission decisions for every declared native optimization.
 *
 * <p>The plan folds the three inputs a hook previously read on its own - the performance snapshot
 * ({@link NativeOptimizationConfig}), the launch startup subset ({@link RuntimeStartupConfig}) and
 * the cross-entry {@link Optimization.Relation relations} of the catalogue - into one immutable
 * value built once per launch. Nothing here re-reads {@code config.json}, so the preference a
 * contributor sees cannot change between two hooks of the same launch, and the refusal a relation
 * causes is one declared code instead of an {@code if} in each contributor.</p>
 *
 * <p>Evaluation order per entry is preserved exactly, because it decides the user-visible reason:
 * the persisted preference first ({@code preference-disabled}, or {@code config-rejected} when the
 * snapshot fell back because {@code config.json} could not be read), then the relations in
 * declaration order ({@code REQUIRES} refuses with {@code <target>-disabled}, {@code CONFLICTS}
 * with {@code <target>-enabled}). An entry that persists no preference of its own is decided by
 * its {@code -D} switch when that is set and by its {@code DERIVED_FROM} relations otherwise.</p>
 *
 * <p>The launch policy's own kill switch is deliberately not folded into a decision: it refuses at
 * {@link PolicyCheck} exactly where it always did, so the plan only carries that snapshot - read
 * once - for the installers that still consult it.</p>
 */
final class OptimizationPlan {

    /** Source reported for a probe whose default came from the entries it serves. */
    private static final String SOURCE_DERIVED = "derived-preference";

    private final NativeOptimizationConfig preferences;
    private final RuntimeStartupConfig policy;
    private final Map<Optimization, NativeOptimizationConfig.Choice> choices;
    private final Map<Optimization, Decision> decisions;

    private OptimizationPlan(
            final NativeOptimizationConfig preferences,
            final RuntimeStartupConfig policy,
            final Map<Optimization, NativeOptimizationConfig.Choice> choices,
            final Map<Optimization, Decision> decisions) {
        this.preferences = preferences;
        this.policy = policy;
        this.choices = Collections.unmodifiableMap(choices);
        this.decisions = Collections.unmodifiableMap(decisions);
    }

    /**
     * Resolves every catalogue entry against one launch snapshot.
     *
     * @param preferences the performance snapshot captured for this launch
     * @param policy the fail-closed startup subset of the same launch, or {@code null} when the
     *     launch has none
     * @return the immutable plan for this launch
     */
    static OptimizationPlan resolve(final NativeOptimizationConfig preferences, final RuntimeStartupConfig policy) {
        Objects.requireNonNull(preferences, "preferences");
        final Map<Optimization, NativeOptimizationConfig.Choice> choices = new LinkedHashMap<>();
        final Map<Optimization, Decision> decisions = new LinkedHashMap<>();
        // Declaration order is validated to be dependency order, so every relation target already
        // has a decision by the time it is read.
        for (final Optimization entry : OptimizationCatalog.all()) {
            final NativeOptimizationConfig.Choice choice = entry.persisted()
                    ? preferences.choice(entry)
                    : explicitChoice(entry).orElse(null);
            choices.put(entry, choice == null ? derivedChoice(entry, decisions) : choice);
            decisions.put(entry, decide(entry, choices.get(entry), decisions));
        }
        return new OptimizationPlan(preferences, policy, choices, decisions);
    }

    /** @return the performance snapshot every decision of this launch was resolved from */
    NativeOptimizationConfig preferences() {
        return preferences;
    }

    /**
     * @return the fail-closed startup subset read once for this launch, {@code null} when the
     *     launch carries none
     */
    RuntimeStartupConfig policy() {
        return policy;
    }

    /**
     * The resolved preference, without the relations: a contributor that serves one protocol of a
     * two-weave preference keeps reading the preference alone, exactly as it did before the
     * relations were declared.
     *
     * @param entry a declared optimization
     * @return the preference this launch resolved for it
     * @throws IllegalArgumentException when {@code entry} is not a declared optimization
     */
    NativeOptimizationConfig.Choice choice(final Optimization entry) {
        final NativeOptimizationConfig.Choice choice = choices.get(entry);
        if (choice == null) throw new IllegalArgumentException("Not a declared optimization: " + entry.protocols());
        return choice;
    }

    /**
     * @param entry a declared optimization
     * @return the install or refusal decision for this launch
     * @throws IllegalArgumentException when {@code entry} is not a declared optimization
     */
    Decision decision(final Optimization entry) {
        final Decision decision = decisions.get(entry);
        if (decision == null) throw new IllegalArgumentException("Not a declared optimization: " + entry.protocols());
        return decision;
    }

    /** @return whether this launch admits {@code entry} */
    boolean admitted(final Optimization entry) {
        return decision(entry) instanceof Decision.Install;
    }

    /**
     * The refusal a declared relation causes, ignoring the preference gate this caller has already
     * passed. It lets a contributor keep its own "preference is off" branch - and its log line -
     * while the relation it used to spell out as an {@code if} is reported byte-for-byte.
     *
     * @param entry a declared optimization
     * @return the refusing relation's decision, or empty when no relation refuses this launch
     */
    Optional<Decision.Refused> relationRefusal(final Optimization entry) {
        return decision(entry) instanceof Decision.Refused refused
                        && refused.cause().isPresent()
                ? Optional.of(refused)
                : Optional.empty();
    }

    /** @return every decision of this plan, keyed by its declared entry, in declaration order */
    Map<Optimization, Decision> decisions() {
        return decisions;
    }

    /** @return the explicit {@code -D} switch of a diagnostic-only probe, empty when it is unset */
    private static Optional<NativeOptimizationConfig.Choice> explicitChoice(final Optimization probe) {
        final String explicit = System.getProperty(probe.property());
        return explicit == null
                ? Optional.empty()
                : Optional.of(new NativeOptimizationConfig.Choice(Boolean.parseBoolean(explicit), "jvm-override"));
    }

    /** A probe without a switch defaults to on when any entry it derives from is admitted. */
    private static NativeOptimizationConfig.Choice derivedChoice(
            final Optimization probe, final Map<Optimization, Decision> decided) {
        final boolean derived = probe.relations().stream()
                .anyMatch(relation -> relation.kind() == Optimization.Relation.Kind.DERIVED_FROM
                        && satisfies(relation, decided.get(relation.target())));
        return derived
                ? new NativeOptimizationConfig.Choice(true, SOURCE_DERIVED)
                : new NativeOptimizationConfig.Choice(false, "platform-default");
    }

    private static Decision decide(
            final Optimization entry,
            final NativeOptimizationConfig.Choice choice,
            final Map<Optimization, Decision> decided) {
        if (!choice.enabled()) {
            // The performance snapshot fails closed on an unreadable or invalid config.json; the
            // reason names that decision instead of a preference the user never set.
            final boolean rejected = PerformanceSettingsConfig.SOURCE_INVALID_CONFIG.equals(choice.source());
            return new Decision.Refused(
                    rejected ? Decision.CONFIG_REJECTED : Decision.PREFERENCE_DISABLED,
                    Optional.empty(),
                    Optional.of(choice.source()));
        }
        for (final Optimization.Relation relation : entry.relations()) {
            // A DERIVED_FROM relation is not a gate: it only supplied the default above.
            if (relation.kind() == Optimization.Relation.Kind.DERIVED_FROM) continue;
            if (!satisfies(relation, decided.get(relation.target()))) {
                return new Decision.Refused(relation.reasonCode(), Optional.of(relation), Optional.of(choice.source()));
            }
        }
        return new Decision.Install(choice);
    }

    /** @return whether a target's decision satisfies one relation */
    private static boolean satisfies(final Optimization.Relation relation, final Decision target) {
        final boolean admitted = target instanceof Decision.Install;
        return relation.kind() == Optimization.Relation.Kind.CONFLICTS ? !admitted : admitted;
    }

    /** One entry's verdict for this launch: an installation, or the reason it was refused. */
    sealed interface Decision {

        /** The preference could not be read, so every optimization stays off. */
        String CONFIG_REJECTED = "config-rejected";

        /** The entry was not requested: no persisted preference and no explicit override. */
        String PREFERENCE_DISABLED = "preference-disabled";

        /**
         * @param choice the resolved preference this launch installs with
         */
        record Install(NativeOptimizationConfig.Choice choice) implements Decision {}

        /**
         * @param reasonCode the user-visible refusal code, unchanged from what contributors
         *     reported before the relations were declared
         * @param cause the relation that refused, empty when the preference did
         * @param preferenceSource the source the preference resolved from, for the status dialog
         */
        record Refused(String reasonCode, Optional<Optimization.Relation> cause, Optional<String> preferenceSource)
                implements Decision {

            public Refused {
                Objects.requireNonNull(reasonCode, "reasonCode");
                Objects.requireNonNull(cause, "cause");
                Objects.requireNonNull(preferenceSource, "preferenceSource");
            }
        }
    }
}
