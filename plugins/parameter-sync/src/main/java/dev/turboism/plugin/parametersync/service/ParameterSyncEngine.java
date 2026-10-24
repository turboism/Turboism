package dev.turboism.plugin.parametersync.service;

import dev.turboism.plugin.parametersync.domain.ProgrammaticSuppressionGuard;
import dev.turboism.plugin.parametersync.domain.SyncRuleSet;
import dev.turboism.sdk.cubism.model.Parameter;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/**
 * Normalized parameter synchronization engine.
 *
 * <p>When any synchronized parameter is manually changed on the UI, its normalized progress
 * (percentage in its min-max range) is derived and immediately applied to all other synchronized parameters.</p>
 */
public final class ParameterSyncEngine {

    private final ProgrammaticSuppressionGuard suppressionGuard;

    public ParameterSyncEngine() {
        this(new ProgrammaticSuppressionGuard());
    }

    public ParameterSyncEngine(final ProgrammaticSuppressionGuard suppressionGuard) {
        this.suppressionGuard = Objects.requireNonNull(suppressionGuard, "suppressionGuard");
    }

    public ProgrammaticSuppressionGuard suppressionGuard() {
        return suppressionGuard;
    }

    public record SyncPlan(String triggerId, float triggerValue, Map<String, Float> plannedValues) {
        public boolean isEmpty() {
            return plannedValues.isEmpty();
        }
    }

    /**
     * Calculates the synchronized values for all other synchronized parameters in the active set.
     */
    public SyncPlan planSync(
            final String changedParamId,
            final float newValue,
            final SyncRuleSet ruleSet,
            final Function<String, Optional<Parameter>> parameterLookup) {
        Objects.requireNonNull(changedParamId, "changedParamId");
        Objects.requireNonNull(ruleSet, "ruleSet");
        Objects.requireNonNull(parameterLookup, "parameterLookup");

        if (!ruleSet.isGlobalEnabled() || !ruleSet.isSynchronized(changedParamId) || ruleSet.synchronizedCount() < 2) {
            return new SyncPlan(changedParamId, newValue, Map.of());
        }

        final Optional<Parameter> changedParamOpt = parameterLookup.apply(changedParamId);
        if (changedParamOpt.isEmpty()) {
            return new SyncPlan(changedParamId, newValue, Map.of());
        }
        final Parameter changedParam = changedParamOpt.get();

        // Calculate normalized progress t in [0.0, 1.0] from the active driver
        final float min = changedParam.getMinimumValue();
        final float max = changedParam.getMaximumValue();
        final float range = max - min;
        final float t;
        if (Math.abs(range) < 1e-6f) {
            t = 0.5f;
        } else {
            final float clamped = Math.max(min, Math.min(max, newValue));
            t = (clamped - min) / range;
        }

        final Map<String, Float> plannedValues = new LinkedHashMap<>();

        // Apply normalized progress to all other synchronized parameters
        for (final String targetId : ruleSet.synchronizedIds()) {
            if (targetId.equals(changedParamId)) {
                continue;
            }
            final Optional<Parameter> targetOpt = parameterLookup.apply(targetId);
            if (targetOpt.isEmpty()) {
                continue;
            }
            final Parameter targetParam = targetOpt.get();
            final float tgtMin = targetParam.getMinimumValue();
            final float tgtMax = targetParam.getMaximumValue();
            final float computedVal = tgtMin + t * (tgtMax - tgtMin);
            plannedValues.put(targetId, computedVal);
        }

        return new SyncPlan(changedParamId, newValue, Collections.unmodifiableMap(plannedValues));
    }

    /**
     * Executes synchronization, registering every written value in the suppression guard.
     */
    public Map<String, Float> executeSync(
            final String changedParamId,
            final float newValue,
            final SyncRuleSet ruleSet,
            final Function<String, Optional<Parameter>> parameterLookup) {
        final SyncPlan plan = planSync(changedParamId, newValue, ruleSet, parameterLookup);
        if (plan.isEmpty()) {
            return Map.of();
        }

        for (final Map.Entry<String, Float> entry : plan.plannedValues().entrySet()) {
            final String targetId = entry.getKey();
            final float targetVal = entry.getValue();
            final Optional<Parameter> targetOpt = parameterLookup.apply(targetId);
            if (targetOpt.isPresent()) {
                final Parameter targetParam = targetOpt.get();
                if (Math.abs(targetParam.getValue() - targetVal) < 1e-4f) {
                    continue;
                }
                suppressionGuard.markProgrammaticWrite(targetId, targetVal);
                try {
                    targetParam.setValue(targetVal);
                } catch (RuntimeException failure) {
                    // Suppress write failure gracefully
                }
            }
        }

        return plan.plannedValues();
    }
}
