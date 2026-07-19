package dev.turboism.plugin.psdimport.b1.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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

    public List<PsdActionDescriptor> inventory() {
        return INVENTORY;
    }

    public PsdLifecycleState state() {
        return state;
    }

    public LifecycleOperationResult enable() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.SHUTDOWN_REJECTED;
        if (state == PsdLifecycleState.ENABLED) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.ENABLED;
        return LifecycleOperationResult.CHANGED;
    }

    public LifecycleOperationResult disable() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.SHUTDOWN_REJECTED;
        if (state == PsdLifecycleState.DISABLED) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.DISABLED;
        return LifecycleOperationResult.CHANGED;
    }

    public LifecycleOperationResult shutdown() {
        if (state == PsdLifecycleState.SHUTDOWN) return LifecycleOperationResult.UNCHANGED;
        state = PsdLifecycleState.SHUTDOWN;
        return LifecycleOperationResult.CHANGED;
    }

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
