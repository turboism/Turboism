package dev.turboism.core.dependency;

import dev.turboism.core.version.PluginVersion;
import dev.turboism.core.version.VersionRange;
import dev.turboism.sdk.plugin.PluginDescriptor;

import java.util.*;

/**
 * Resolves plugin dependencies and detects cycles.
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

    public ResolutionResult resolve(Collection<PluginDescriptor> descriptors) {
        Map<String, PluginDescriptor> byId = new LinkedHashMap<>();
        for (PluginDescriptor d : descriptors) {
            byId.put(d.id(), d);
        }

        List<String> cycles = detectCycles(byId);
        List<String> disabledIds = new ArrayList<>();
        List<ResolvedPlugin> order = new ArrayList<>();

        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();

        for (String id : byId.keySet()) {
            if (!visited.contains(id)) {
                visit(id, byId, visited, inStack, order, disabledIds);
            }
        }

        return new ResolutionResult(order, disabledIds, cycles);
    }

    private void visit(String id, Map<String, PluginDescriptor> byId, Set<String> visited, Set<String> inStack,
                       List<ResolvedPlugin> order, List<String> disabledIds) {
        if (inStack.contains(id)) {
            return; // cycle handled separately
        }
        if (visited.contains(id)) {
            return;
        }
        visited.add(id);
        inStack.add(id);

        PluginDescriptor descriptor = byId.get(id);
        if (descriptor == null) {
            inStack.remove(id);
            return;
        }

        boolean missingDep = false;
        for (PluginDescriptor.DependencyRef dep : descriptor.dependencies()) {
            if (!"required".equals(dep.type())) {
                continue;
            }
            PluginDescriptor target = byId.get(dep.id());
            if (target == null) {
                missingDep = true;
                break;
            }
            try {
                PluginVersion targetVersion = PluginVersion.parse(target.version());
                VersionRange range = VersionRange.parse(dep.version());
                if (!range.contains(targetVersion)) {
                    missingDep = true;
                    break;
                }
            } catch (IllegalArgumentException e) {
                missingDep = true;
                break;
            }
            if (!visited.contains(dep.id())) {
                visit(dep.id(), byId, visited, inStack, order, disabledIds);
            }
        }

        inStack.remove(id);
        if (missingDep) {
            disabledIds.add(id);
        } else {
            order.add(new ResolvedPlugin(id, PluginVersion.parse(descriptor.version()), descriptor));
        }
    }

    private List<String> detectCycles(Map<String, PluginDescriptor> byId) {
        List<String> cycles = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        Set<String> inStack = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();

        for (String id : byId.keySet()) {
            if (!visited.contains(id)) {
                detectCyclesFrom(id, byId, visited, inStack, path, cycles);
            }
        }
        return cycles;
    }

    private void detectCyclesFrom(String id, Map<String, PluginDescriptor> byId, Set<String> visited, Set<String> inStack,
                                  Deque<String> path, List<String> cycles) {
        if (inStack.contains(id)) {
            StringBuilder cycle = new StringBuilder();
            boolean recording = false;
            for (String p : path) {
                if (p.equals(id)) recording = true;
                if (recording) cycle.append(p).append(" -> ");
            }
            cycle.append(id);
            cycles.add(cycle.toString());
            return;
        }
        if (visited.contains(id)) {
            return;
        }
        visited.add(id);
        inStack.add(id);
        path.addLast(id);

        PluginDescriptor descriptor = byId.get(id);
        if (descriptor != null) {
            for (PluginDescriptor.DependencyRef dep : descriptor.dependencies()) {
                if ("required".equals(dep.type()) && byId.containsKey(dep.id())) {
                    detectCyclesFrom(dep.id(), byId, visited, inStack, path, cycles);
                }
            }
        }

        path.removeLast();
        inStack.remove(id);
    }
}
