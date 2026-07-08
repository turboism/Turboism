package dev.turboism.core.dependency;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Resolves plugin dependencies, propagates dependency failures, and disables cyclic plugins.
 */
public final class DependencyResolver {

    public record ResolvedPlugin(String id, PluginVersion version, PluginDescriptor descriptor) {
    }

    public record ResolutionResult(
        List<ResolvedPlugin> loadOrder,
        List<String> disabledIds,
        List<String> cycles
    ) {
    }

    private enum ResolutionState {
        RESOLVING,
        RESOLVED,
        DISABLED
    }

    public ResolutionResult resolve(Collection<PluginDescriptor> descriptors) {
        Map<String, PluginDescriptor> byId = new LinkedHashMap<>();
        for (PluginDescriptor descriptor : descriptors) {
            byId.put(descriptor.id(), descriptor);
        }

        List<String> cycles = new ArrayList<>();
        Set<String> disabledIds = new LinkedHashSet<>();
        List<ResolvedPlugin> order = new ArrayList<>();
        Map<String, ResolutionState> states = new HashMap<>();

        for (String id : byId.keySet()) {
            resolvePlugin(id, byId, states, new ArrayDeque<>(), order, disabledIds, cycles);
        }

        return new ResolutionResult(order, List.copyOf(disabledIds), cycles);
    }

    private boolean resolvePlugin(
        String id,
        Map<String, PluginDescriptor> byId,
        Map<String, ResolutionState> states,
        Deque<String> stack,
        List<ResolvedPlugin> order,
        Set<String> disabledIds,
        List<String> cycles
    ) {
        ResolutionState existing = states.get(id);
        if (existing == ResolutionState.RESOLVED) {
            return true;
        }
        if (existing == ResolutionState.DISABLED) {
            return false;
        }
        if (existing == ResolutionState.RESOLVING || stack.contains(id)) {
            disableCycle(id, stack, states, disabledIds, cycles);
            return false;
        }

        PluginDescriptor descriptor = byId.get(id);
        if (descriptor == null) {
            return false;
        }

        states.put(id, ResolutionState.RESOLVING);
        stack.addLast(id);
        boolean dependenciesOk = true;

        for (PluginDescriptor.DependencyRef dependency : descriptor.dependencies()) {
            if (!"required".equals(dependency.type())) {
                continue;
            }
            if (!dependencySatisfied(dependency, byId)) {
                dependenciesOk = false;
                continue;
            }
            if (!resolvePlugin(dependency.id(), byId, states, stack, order, disabledIds, cycles)) {
                dependenciesOk = false;
            }
        }

        stack.removeLast();
        if (disabledIds.contains(id) || !dependenciesOk) {
            states.put(id, ResolutionState.DISABLED);
            disabledIds.add(id);
            return false;
        }

        try {
            order.add(new ResolvedPlugin(id, PluginVersion.parse(descriptor.version()), descriptor));
            states.put(id, ResolutionState.RESOLVED);
            return true;
        } catch (IllegalArgumentException exception) {
            states.put(id, ResolutionState.DISABLED);
            disabledIds.add(id);
            return false;
        }
    }

    private static boolean dependencySatisfied(
        PluginDescriptor.DependencyRef dependency,
        Map<String, PluginDescriptor> byId
    ) {
        PluginDescriptor target = byId.get(dependency.id());
        if (target == null) {
            return false;
        }
        try {
            PluginVersion targetVersion = PluginVersion.parse(target.version());
            VersionRange range = VersionRange.parse(dependency.version());
            return range.contains(targetVersion);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static void disableCycle(
        String repeatedId,
        Deque<String> stack,
        Map<String, ResolutionState> states,
        Set<String> disabledIds,
        List<String> cycles
    ) {
        List<String> path = new ArrayList<>(stack);
        int start = path.indexOf(repeatedId);
        if (start < 0) {
            disabledIds.add(repeatedId);
            states.put(repeatedId, ResolutionState.DISABLED);
            cycles.add(repeatedId + " -> " + repeatedId);
            return;
        }

        List<String> cycleIds = new ArrayList<>(path.subList(start, path.size()));
        cycleIds.add(repeatedId);
        cycles.add(String.join(" -> ", cycleIds));
        for (String cycleId : cycleIds) {
            disabledIds.add(cycleId);
            states.put(cycleId, ResolutionState.DISABLED);
        }
    }
}
