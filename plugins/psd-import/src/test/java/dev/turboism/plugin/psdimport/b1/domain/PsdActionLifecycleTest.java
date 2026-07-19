package dev.turboism.plugin.psdimport.b1.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class PsdActionLifecycleTest {

    @Test
    void exposesFrozenActionInventoryDefaultsAndLifecycleMatrix() {
        final PsdActionLifecycle lifecycle = new PsdActionLifecycle();
        assertEquals(List.of(
            "turboism.psd-import.import-clip-masks",
            "turboism.psd-import.repair-layer-bindings",
            "turboism.psd-import.expand-canvas-aabb"
        ), lifecycle.inventory().stream().map(PsdActionDescriptor::id).toList());
        assertEquals(false, lifecycle.inventory().get(0).booleanParameters().get("overwriteNonEmptyClipMasks"));
        assertEquals(true, lifecycle.inventory().get(1).booleanParameters().get("ignoreLayerId"));
        assertEquals(Map.of(), lifecycle.inventory().get(2).booleanParameters());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.disable());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.disable());
        assertEquals(LifecycleOperationResult.CHANGED, lifecycle.shutdown());
        assertEquals(LifecycleOperationResult.UNCHANGED, lifecycle.shutdown());
        assertEquals(LifecycleOperationResult.SHUTDOWN_REJECTED, lifecycle.enable());
        assertEquals(LifecycleOperationResult.SHUTDOWN_REJECTED, lifecycle.disable());
        assertThrows(UnsupportedOperationException.class, () -> lifecycle.inventory().add(lifecycle.inventory().get(0)));
    }

    @Test
    void parsesAllParametersInInputOrderWithStableDefaults() {
        final LinkedHashMap<String, String> values = new LinkedHashMap<>();
        values.put("unknown", "true");
        values.put("overwriteNonEmptyClipMasks", "TRUE");
        values.put("ignoreLayerId", "false");
        final PsdParameterParseResult result = PsdActionLifecycle.parseParameters(values);
        assertEquals(false, result.values().get("overwriteNonEmptyClipMasks"));
        assertEquals(false, result.values().get("ignoreLayerId"));
        assertEquals(List.of(PsdParameterIssueCode.UNKNOWN_PARAMETER, PsdParameterIssueCode.INVALID_DEFAULTED),
            result.issues().stream().map(PsdParameterIssue::code).toList());
        assertEquals(List.of("unknown", "overwriteNonEmptyClipMasks"),
            result.issues().stream().map(PsdParameterIssue::parameter).toList());
    }

    @Test
    void missingAndCanonicalValuesUseApprovedDefaults() {
        assertEquals(Map.of("overwriteNonEmptyClipMasks", false, "ignoreLayerId", true),
            PsdActionLifecycle.parseParameters(Map.of()).values());
        assertEquals(Map.of("overwriteNonEmptyClipMasks", true, "ignoreLayerId", false),
            PsdActionLifecycle.parseParameters(Map.of(
                "overwriteNonEmptyClipMasks", "true", "ignoreLayerId", "false"
            )).values());
    }
}
