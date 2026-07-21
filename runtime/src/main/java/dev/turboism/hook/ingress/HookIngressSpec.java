package dev.turboism.hook.ingress;

public record HookIngressSpec(String hookId, String emittedEvent, boolean productionEnabled, String safeMode) {
    public HookIngressSpec {
        if (hookId == null || hookId.isBlank()) {
            throw new IllegalArgumentException("hookId must not be null or blank");
        }
        if (emittedEvent == null || emittedEvent.isBlank()) {
            throw new IllegalArgumentException("emittedEvent must not be null or blank");
        }
        if (productionEnabled) {
            throw new IllegalArgumentException("Hook ingress specs must not enable production hooks");
        }
        if (safeMode == null || safeMode.isBlank()) {
            throw new IllegalArgumentException("safeMode must not be null or blank");
        }
    }
}
