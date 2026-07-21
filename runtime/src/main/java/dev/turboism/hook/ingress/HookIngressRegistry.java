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

    public static HookIngressRegistry defaults() {
        return new HookIngressRegistry(DefaultHookIngressSpecs.DEFAULT_SPECS);
    }

    public Optional<HookIngressSpec> find(String hookId) {
        return Optional.ofNullable(specs.get(hookId));
    }

    public Collection<HookIngressSpec> specs() {
        return specs.values();
    }
}
