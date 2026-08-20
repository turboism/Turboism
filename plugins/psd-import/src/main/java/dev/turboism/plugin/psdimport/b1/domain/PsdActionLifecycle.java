package dev.turboism.plugin.psdimport.b1.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Enable/disable/shutdown state machine for the PSD-import plugin's actions, together with the
 * fixed inventory of actions it offers and the parser for their parameters.
 *
 * <p>Pure domain state: it holds no host handles, performs no I/O, and never touches the Cubism
 * model. Instances are mutable and carry no synchronization — confine one to the thread that
 * drives the plugin's lifecycle. {@code SHUTDOWN} is terminal, so a shut-down lifecycle can never
 * be brought back to {@code ENABLED}.
 */
public final class PsdActionLifecycle {
    public static final String OVERWRITE = "overwriteNonEmptyClipMasks";
    public static final String IGNORE_LAYER_ID = "ignoreLayerId";
    private static final Map<String, Boolean> DEFAULTS = Map.of(OVERWRITE, false, IGNORE_LAYER_ID, true);
    private static final List<PsdActionDescriptor> INVENTORY = List.of(
        new PsdActionDescriptor(
            "turboism.psd-import.import-clip-masks",
            "psd.action.import-clip-masks.label",
            Map.of(OVERWRITE, false)
        ),
        new PsdActionDescriptor(
            "turboism.psd-import.repair-layer-bindings",
            "psd.action.repair-layer-bindings.label",
            Map.of(IGNORE_LAYER_ID, true)
        ),
        new PsdActionDescriptor(
            "turboism.psd-import.expand-canvas-aabb",
            "psd.action.expand-canvas.label",
            Map.of()
        )
    );
    private PsdLifecycleState state = PsdLifecycleState.DISABLED;

    /**
     * @return the fixed, immutable inventory of actions this plugin offers, in declaration order;
     *     the same list regardless of lifecycle state — being {@code DISABLED} or {@code SHUTDOWN}
     *     does not empty it
     */
    public List<PsdActionDescriptor> inventory() {
        return INVENTORY;
    }

    /**
     * @return the current lifecycle state; {@link PsdLifecycleState#DISABLED} until {@link #enable()}
     *     has succeeded at least once
     */
    public PsdLifecycleState state() {
        return state;
    }

    /**
     * Moves the lifecycle to {@link PsdLifecycleState#ENABLED}.
     *
     * @return {@code CHANGED} if the state moved, {@code UNCHANGED} if it was already enabled, or
     *     {@code SHUTDOWN_REJECTED} if the lifecycle has shut down — in which case the state is left
     *     untouched and no exception is thrown
     */
    public LifecycleOperationResult enable() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.SHUTDOWN_REJECTED;
        if (state == PsdLifecycleState.ENABLED) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.ENABLED;
        return LifecycleOperationResult.CHANGED;
    }

    /**
     * Moves the lifecycle to {@link PsdLifecycleState#DISABLED}.
     *
     * @return {@code CHANGED} if the state moved, {@code UNCHANGED} if it was already disabled, or
     *     {@code SHUTDOWN_REJECTED} if the lifecycle has shut down — in which case the state is left
     *     untouched and no exception is thrown
     */
    public LifecycleOperationResult disable() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.SHUTDOWN_REJECTED;
        if (state == PsdLifecycleState.DISABLED) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.DISABLED;
        return LifecycleOperationResult.CHANGED;
    }

    /**
     * Moves the lifecycle to the terminal {@link PsdLifecycleState#SHUTDOWN} state, after which
     * {@link #enable()} and {@link #disable()} are permanently rejected.
     *
     * <p>Idempotent, and always accepted — it is never rejected regardless of the prior state.
     *
     * @return {@code CHANGED} on the first shutdown, {@code UNCHANGED} on any later one
     */
    public LifecycleOperationResult shutdown() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.SHUTDOWN;
        return LifecycleOperationResult.CHANGED;
    }

    /**
     * Interprets raw host-supplied parameter text into the booleans the actions expect, never failing.
     *
     * <p>Both declared parameters are always present in the result, seeded with their defaults, so an
     * absent or {@code null} input simply yields the defaults with no issues. Only the exact strings
     * {@code "true"} and {@code "false"} are honoured — any other text keeps the default and records
     * {@link PsdParameterIssueCode#INVALID_DEFAULTED}; a name the actions do not declare is dropped
     * from the values and recorded as {@link PsdParameterIssueCode#UNKNOWN_PARAMETER}. Parsing is
     * case-sensitive and does not trim whitespace.
     *
     * @param parameters raw parameter names to their raw text values; {@code null} is tolerated and
     *     treated as empty
     * @return the effective values plus every substitution or omission that was made, never
     *     {@code null}
     */
    public static PsdParameterParseResult parseParameters(final Map<String, String> parameters) {
        final LinkedHashMap<String, Boolean> values = new LinkedHashMap<>();
        values.put(OVERWRITE, DEFAULTS.get(OVERWRITE));
        values.put(IGNORE_LAYER_ID, DEFAULTS.get(IGNORE_LAYER_ID));
        final List<PsdParameterIssue> issues = new ArrayList<>();
        if (parameters != null) {
            for (Map.Entry<String, String> entry : parameters.entrySet()) {
                final String name = entry.getKey();
                if (!DEFAULTS.containsKey(name)) {
                    issues.add(new PsdParameterIssue(name, PsdParameterIssueCode.UNKNOWN_PARAMETER));
                    continue;
                }
                final String raw = entry.getValue();
                if ("true".equals(raw)) values.put(name, true);
                else if ("false".equals(raw)) values.put(name, false);
                else {
                    values.put(name, DEFAULTS.get(name));
                    issues.add(new PsdParameterIssue(name, PsdParameterIssueCode.INVALID_DEFAULTED));
                }
            }
        }
        return new PsdParameterParseResult(values, issues);
    }
}
