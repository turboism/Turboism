package dev.turboism.adapter.cubism.mesh;

import dev.turboism.sdk.cubism.mesh.MeshEditTool;
import dev.turboism.sdk.cubism.mesh.MeshMirrorToolEligibility;
import dev.turboism.sdk.plugin.Registration;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Shared registry consulted synchronously by the exact-host mirror eligibility hook. */
public final class RuntimeMeshMirrorToolEligibility implements MeshMirrorToolEligibility {

    private final Map<MeshEditTool, Integer> registrations = new EnumMap<>(MeshEditTool.class);

    @Override
    public Registration extendEligibleTools(final Set<MeshEditTool> tools) {
        final Set<MeshEditTool> contribution = Set.copyOf(Objects.requireNonNull(tools, "tools"));
        if (contribution.isEmpty() || contribution.contains(MeshEditTool.UNKNOWN)) {
            throw new IllegalArgumentException("eligible mesh tools must be nonempty and known");
        }
        synchronized (registrations) {
            contribution.forEach(tool -> registrations.merge(tool, 1, Integer::sum));
        }
        return new Registration() {
            private boolean closed;

            @Override
            public void close() {
                synchronized (registrations) {
                    if (closed) return;
                    closed = true;
                    contribution.forEach(tool -> registrations.computeIfPresent(
                        tool,
                        (ignored, count) -> count == 1 ? null : count - 1
                    ));
                }
            }
        };
    }

    boolean isExtended(final MeshEditTool tool) {
        synchronized (registrations) {
            return registrations.containsKey(tool);
        }
    }

    void resetSession() {
        synchronized (registrations) {
            registrations.clear();
        }
    }
}
