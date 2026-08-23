package dev.turboism.hook.ingress;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Registry for semantic hook ingress definitions.
 *
 * <p>Ingress specs are internal runtime metadata only. They never expose raw
 * hook targets to plugins and never enable production hook injection.</p>
 */
public final class HookIngressRegistry {

    private final Map<String, HookIngressSpec> specs;

    public HookIngressRegistry(Collection<HookIngressSpec> specs) {
        Objects.requireNonNull(specs, "specs");
        Map<String, HookIngressSpec> keyed = new LinkedHashMap<>();
        for (HookIngressSpec spec : specs) {
            HookIngressSpec previous = keyed.put(spec.hookId(), spec);
            if (previous != null) {
                throw new IllegalArgumentException("Duplicate hook ingress spec: " + spec.hookId());
            }
        }
        this.specs = Map.copyOf(keyed);
    }

    /**
     * @return a registry holding the built-in
     *     {@link DefaultHookIngressSpecs#DEFAULT_SPECS}
     */
    public static HookIngressRegistry defaults() {
        return new HookIngressRegistry(DefaultHookIngressSpecs.DEFAULT_SPECS);
    }

    /**
     * @param hookId the ingress identity to look up; {@code null} simply misses
     * @return the registered spec, or empty when no ingress carries that id
     */
    public Optional<HookIngressSpec> find(String hookId) {
        return Optional.ofNullable(specs.get(hookId));
    }

    /**
     * @return every registered spec in registration order, as an unmodifiable view of
     *     the registry’s immutable backing map
     */
    public Collection<HookIngressSpec> specs() {
        return specs.values();
    }
}
