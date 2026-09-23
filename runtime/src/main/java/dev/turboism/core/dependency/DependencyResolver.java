package dev.turboism.core.dependency;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Resolves plugin dependencies, propagates dependency failures, and disables cyclic plugins.
 *
 * <p>Existence/version requirements and load-order constraints are separate concerns:</p>
 *
 * <ul>
 *   <li>A {@code required} dependency must be present, carry a parsable version inside the
 *       declared range, and itself resolve; otherwise the declaring plugin is disabled and the
 *       failure propagates transitively to its required dependents.</li>
 *   <li>An {@code optional} dependency never disables a plugin. The reference is inert unless
 *       the target is present, resolves, and matches the declared version range; only then does
 *       its ordering constraint apply. A present optional target that is disabled or
 *       version-incompatible is treated exactly like an absent one.</li>
 *   <li>{@code ordering} is the declaring plugin's position relative to the dependency target:
 *       {@code before} loads the declarer before the target, {@code after} loads it after, and
 *       {@code none} declares no constraint — for required dependencies too, so two plugins that
 *       merely require each other without ordering form no cycle. An ordering token outside
 *       {@code before}/{@code after}/{@code none} fails closed: the declaring plugin is disabled
 *       and diagnosed, matching the admission-time schema rejection.</li>
 * </ul>
 *
 * <p>Any cycle in the resulting order graph — formed by required or optional ordering edges —
 * is reported and its member plugins are disabled, then the remaining graph is sorted again.
 * Among unconstrained plugins the descriptor iteration (discovery) order decides.</p>
 */
public final class DependencyResolver {

    /**
     * A plugin that resolved successfully, paired with its parsed version.
     *
     * @param id plugin ID
     * @param version version parsed from the descriptor; a descriptor whose version cannot be
     *     parsed is disabled instead of resolved, so this is always a valid version
     * @param descriptor the descriptor the plugin was resolved from
     */
    public record ResolvedPlugin(String id, PluginVersion version, PluginDescriptor descriptor) {
    }

    /**
     * Outcome of one resolution pass over a set of descriptors.
     *
     * @param loadOrder plugins that resolved, in an order honouring every declared ordering
     *     constraint: {@code before} places the declarer ahead of its target, {@code after}
     *     behind it, and an optional dependency orders against the declarer only while the target
     *     resolves and matches the declared version range
     * @param disabledIds IDs excluded from the load order, each because a required dependency was
     *     missing or version-incompatible, because it took part in an ordering cycle, because a
     *     declared ordering token was unsupported, or because its own version string could not be
     *     parsed
     * @param disabledReasons per-ID explanation of why each disabled plugin was excluded, for
     *     diagnostics; contains exactly the IDs of {@code disabledIds}
     * @param cycles human-readable descriptions of the ordering cycles that were broken
     */
    public record ResolutionResult(
        List<ResolvedPlugin> loadOrder,
        List<String> disabledIds,
        Map<String, String> disabledReasons,
        List<String> cycles
    ) {
        public ResolutionResult {
            loadOrder = List.copyOf(loadOrder);
            disabledIds = List.copyOf(disabledIds);
            disabledReasons = Map.copyOf(disabledReasons);
            cycles = List.copyOf(cycles);
        }
    }

    /**
     * Resolves the given descriptors into a load order, disabling rather than failing on any
     * problem: a missing or version-incompatible required dependency, an unparsable version, an
     * unsupported ordering token, or an ordering cycle disables the affected plugins and
     * propagates to everything that requires them.
     *
     * @param descriptors plugin descriptors to resolve; iteration order seeds the traversal order
     *     and breaks ties between plugins no ordering constraint relates
     * @return the load order plus the disabled IDs and the cycles that were detected
     */
    public ResolutionResult resolve(Collection<PluginDescriptor> descriptors) {
        Map<String, PluginDescriptor> byId = new LinkedHashMap<>();
        for (PluginDescriptor descriptor : descriptors) {
            byId.put(descriptor.id(), descriptor);
        }
        List<String> discoveryOrder = List.copyOf(byId.keySet());
        Map<String, Integer> discoveryIndex = new HashMap<>();
        for (int index = 0; index < discoveryOrder.size(); index++) {
            discoveryIndex.put(discoveryOrder.get(index), index);
        }

        List<String> cycles = new ArrayList<>();
        Set<String> disabledIds = new LinkedHashSet<>();
        Map<String, String> disabledReasons = new LinkedHashMap<>();

        // Settle every disable decision before ordering: disabling a plugin after it was placed
        // would leave a dead entry inside the load order, so existence failures and ordering
        // cycles propagate to a fixpoint first and the topological sort runs once on the result.
        propagateUnsatisfiedRequired(byId, disabledIds, disabledReasons);
        while (true) {
            Map<String, Set<String>> predecessors = orderingPredecessors(byId, disabledIds);
            List<Set<String>> cyclic = cyclicComponents(predecessors, discoveryIndex);
            if (cyclic.isEmpty()) {
                break;
            }
            for (Set<String> component : cyclic) {
                String description = String.join(" -> ", cyclePath(component, predecessors, discoveryIndex));
                cycles.add(description);
                for (String member : component) {
                    disable(member, "ordering cycle: " + description, disabledIds, disabledReasons);
                }
            }
            propagateUnsatisfiedRequired(byId, disabledIds, disabledReasons);
        }

        List<ResolvedPlugin> order = new ArrayList<>();
        Map<String, Set<String>> predecessors = orderingPredecessors(byId, disabledIds);
        TreeSet<Integer> ready = new TreeSet<>();
        for (Map.Entry<String, Set<String>> entry : predecessors.entrySet()) {
            if (entry.getValue().isEmpty()) {
                ready.add(discoveryIndex.get(entry.getKey()));
            }
        }
        while (!ready.isEmpty()) {
            String id = discoveryOrder.get(ready.pollFirst());
            predecessors.remove(id);
            order.add(new ResolvedPlugin(
                id, PluginVersion.parse(byId.get(id).version()), byId.get(id)
            ));
            for (Map.Entry<String, Set<String>> entry : predecessors.entrySet()) {
                if (entry.getValue().remove(id) && entry.getValue().isEmpty()) {
                    ready.add(discoveryIndex.get(entry.getKey()));
                }
            }
        }
        // The fixpoint above leaves an acyclic graph, so every surviving plugin emits here;
        // anything left would indicate a resolver bug, not a descriptor problem.
        for (String id : predecessors.keySet()) {
            disable(id, "unresolvable ordering constraints", disabledIds, disabledReasons);
        }

        return new ResolutionResult(order, List.copyOf(disabledIds), disabledReasons, cycles);
    }

    /**
     * Every strongly connected component of the order graph that cannot be satisfied: components
     * with more than one member, or a single plugin that orders against itself. Returned in
     * discovery order of each component's earliest member so diagnostics are stable.
     */
    private static List<Set<String>> cyclicComponents(
        Map<String, Set<String>> predecessors,
        Map<String, Integer> discoveryIndex
    ) {
        Map<String, Set<String>> successors = new LinkedHashMap<>();
        for (String id : predecessors.keySet()) {
            successors.put(id, new LinkedHashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : predecessors.entrySet()) {
            for (String predecessor : entry.getValue()) {
                successors.get(predecessor).add(entry.getKey());
            }
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> lowLink = new HashMap<>();
        Deque<String> stack = new ArrayDeque<>();
        Set<String> onStack = new HashSet<>();
        List<Set<String>> components = new ArrayList<>();
        int[] counter = {0};
        for (String id : successors.keySet()) {
            if (!index.containsKey(id)) {
                strongConnect(id, successors, index, lowLink, stack, onStack, components, counter);
            }
        }
        List<Set<String>> cyclic = new ArrayList<>();
        for (Set<String> component : components) {
            if (component.size() > 1
                || predecessors.get(component.iterator().next()).contains(component.iterator().next())) {
                cyclic.add(component);
            }
        }
        cyclic.sort(java.util.Comparator.comparingInt(component ->
            component.stream().mapToInt(discoveryIndex::get).min().orElse(0)));
        return cyclic;
    }

    /** Tarjan strong-connect: {@code counter[0]} is the shared visit counter. */
    private static void strongConnect(
        String id,
        Map<String, Set<String>> successors,
        Map<String, Integer> index,
        Map<String, Integer> lowLink,
        Deque<String> stack,
        Set<String> onStack,
        List<Set<String>> components,
        int[] counter
    ) {
        index.put(id, counter[0]);
        lowLink.put(id, counter[0]);
        counter[0]++;
        stack.addLast(id);
        onStack.add(id);
        for (String next : successors.getOrDefault(id, Set.of())) {
            if (!index.containsKey(next)) {
                strongConnect(next, successors, index, lowLink, stack, onStack, components, counter);
                lowLink.merge(id, lowLink.get(next), Math::min);
            } else if (onStack.contains(next)) {
                lowLink.merge(id, index.get(next), Math::min);
            }
        }
        if (lowLink.get(id).equals(index.get(id))) {
            Set<String> component = new LinkedHashSet<>();
            String member;
            do {
                member = stack.removeLast();
                onStack.remove(member);
                component.add(member);
            } while (!member.equals(id));
            components.add(component);
        }
    }

    /**
     * Extracts one real cycle path inside a cyclic component for diagnostics: predecessor edges
     * restricted to the component always continue, so the walk must revisit a node. The returned
     * path repeats its first element at the end.
     */
    private static List<String> cyclePath(
        Set<String> component,
        Map<String, Set<String>> predecessors,
        Map<String, Integer> discoveryIndex
    ) {
        java.util.Comparator<String> byDiscovery = java.util.Comparator.comparingInt(discoveryIndex::get);
        List<String> path = new ArrayList<>();
        String current = component.stream().min(byDiscovery).orElseThrow();
        while (!path.contains(current)) {
            path.add(current);
            current = predecessors.getOrDefault(current, Set.of()).stream()
                .filter(component::contains)
                .min(byDiscovery)
                .orElseThrow();
        }
        List<String> cycle = new ArrayList<>(path.subList(path.indexOf(current), path.size()));
        cycle.add(current);
        return cycle;
    }

    /**
     * Disables every plugin whose own version cannot be parsed, whose declared ordering token is
     * unsupported, or whose required dependency is absent, version-incompatible, or already
     * disabled, repeating until no plugin remains whose requirements are unmet.
     */
    private static void propagateUnsatisfiedRequired(
        Map<String, PluginDescriptor> byId,
        Set<String> disabledIds,
        Map<String, String> disabledReasons
    ) {
        boolean changed = true;
        while (changed) {
            changed = false;
            for (Map.Entry<String, PluginDescriptor> entry : byId.entrySet()) {
                String id = entry.getKey();
                if (disabledIds.contains(id)) {
                    continue;
                }
                String reason = unsatisfiedReason(entry.getValue(), byId, disabledIds);
                if (reason != null) {
                    disable(id, reason, disabledIds, disabledReasons);
                    changed = true;
                }
            }
        }
    }

    private static String unsatisfiedReason(
        PluginDescriptor descriptor,
        Map<String, PluginDescriptor> byId,
        Set<String> disabledIds
    ) {
        try {
            PluginVersion.parse(descriptor.version());
        } catch (IllegalArgumentException exception) {
            return "unparsable own version: " + descriptor.version();
        }
        for (PluginDescriptor.DependencyRef dependency : descriptor.dependencies()) {
            if (!orderingSupported(dependency.ordering())) {
                return "unsupported ordering token '"
                    + dependency.ordering() + "' on dependency '" + dependency.id() + "'";
            }
            if ("required".equals(dependency.type())
                && !dependencySatisfied(dependency, byId, disabledIds)) {
                return "required dependency '" + dependency.id()
                    + "' is missing, disabled, or version-incompatible";
            }
        }
        return null;
    }

    private static boolean orderingSupported(String ordering) {
        return "none".equals(ordering) || "before".equals(ordering) || "after".equals(ordering);
    }

    private static boolean dependencySatisfied(
        PluginDescriptor.DependencyRef dependency,
        Map<String, PluginDescriptor> byId,
        Set<String> disabledIds
    ) {
        PluginDescriptor target = byId.get(dependency.id());
        if (target == null || disabledIds.contains(dependency.id())) {
            return false;
        }
        return versionMatches(dependency, target);
    }

    private static boolean versionMatches(
        PluginDescriptor.DependencyRef dependency,
        PluginDescriptor target
    ) {
        try {
            return VersionRange.parse(dependency.version())
                .contains(PluginVersion.parse(target.version()));
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    /**
     * Builds the declared order constraints between plugins that are still loadable, keyed by the
     * plugin that must wait. Edges to or from disabled plugins are dropped, an optional reference
     * contributes an edge only while its target is present and satisfies the declared version
     * range — an inapplicable optional reference is treated like an absent one — and {@code none}
     * contributes no edge regardless of dependency type.
     */
    private static Map<String, Set<String>> orderingPredecessors(
        Map<String, PluginDescriptor> byId,
        Set<String> disabledIds
    ) {
        Map<String, Set<String>> predecessors = new LinkedHashMap<>();
        for (Map.Entry<String, PluginDescriptor> entry : byId.entrySet()) {
            String id = entry.getKey();
            if (disabledIds.contains(id)) {
                continue;
            }
            predecessors.computeIfAbsent(id, key -> new LinkedHashSet<>());
            for (PluginDescriptor.DependencyRef dependency : entry.getValue().dependencies()) {
                String target = dependency.id();
                if (disabledIds.contains(target)) {
                    continue;
                }
                boolean required = "required".equals(dependency.type());
                if (!required && !optionalApplies(dependency, byId)) {
                    continue;
                }
                if ("before".equals(dependency.ordering())) {
                    predecessors.computeIfAbsent(target, key -> new LinkedHashSet<>()).add(id);
                } else if ("after".equals(dependency.ordering())) {
                    predecessors.get(id).add(target);
                }
            }
        }
        return predecessors;
    }

    /**
     * Whether an optional reference's ordering constraint applies: the target must exist and its
     * version must satisfy the declared range. Callers already exclude disabled targets, so a
     * disabled optional dependency behaves exactly like a missing one.
     */
    private static boolean optionalApplies(
        PluginDescriptor.DependencyRef dependency,
        Map<String, PluginDescriptor> byId
    ) {
        PluginDescriptor target = byId.get(dependency.id());
        return target != null && versionMatches(dependency, target);
    }

    private static void disable(
        String id,
        String reason,
        Set<String> disabledIds,
        Map<String, String> disabledReasons
    ) {
        if (disabledIds.add(id)) {
            disabledReasons.put(id, reason);
        }
    }
}
