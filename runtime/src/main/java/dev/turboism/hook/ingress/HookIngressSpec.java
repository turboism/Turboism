package dev.turboism.hook.ingress;

/**
 * Runtime-internal description of one semantic hook ingress point.
 *
 * <p>Never handed to plugins: it names the SDK event an ingress may emit, not the
 * raw host hook target. The constructor refuses to build a production-enabled spec
 * at all, so production hook injection cannot be turned on through this type.</p>
 *
 * @param hookId           runtime identity of the ingress, non-blank
 * @param emittedEvent     name of the single SDK event this ingress may emit, non-blank
 * @param productionEnabled must be {@code false}; any other value is rejected
 * @param safeMode         the restriction the ingress operates under, e.g.
 *                         {@code enqueue-only}; non-blank
 * @throws IllegalArgumentException when a text component is null or blank, or when
 *     {@code productionEnabled} is {@code true}
 */
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
