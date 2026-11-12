package dev.turboism.automesh.host;

import java.util.List;
import java.util.Optional;

/**
 * Resolves the opaque selection elements a guarded funnel hands over into host targets.
 *
 * <p>The bridge forwards the host's own argument array, so this seam is the only place that knows
 * what the two funnels put into their {@code List}. Each resolved target keeps the host handles the
 * funnel delivered, because the write replaces the editable mesh the funnel was given instead of
 * resolving it again from the current selection. An implementation must answer empty — never a
 * partial list — when any element cannot be resolved: a custom run that silently drops a selected
 * mesh would write a different selection than the one the user confirmed.</p>
 */
@FunctionalInterface
public interface AutoMeshTargetResolver {

    /**
     * Resolves the confirmed selection of one funnel invocation.
     *
     * @param funnel the funnel that was entered
     * @param hostArguments the funnel token, the receiver and the declared arguments
     * @return the selected targets in host order, or empty when any element is unresolvable
     */
    Optional<List<AutoMeshResolvedTarget>> resolve(AutoMeshFunnel funnel, Object[] hostArguments);
}
