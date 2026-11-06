package dev.turboism.ui.workspace;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.WorkspaceControlAliases;
import dev.turboism.sdk.ui.workspace.WorkspaceId;
import dev.turboism.sdk.ui.workspace.WorkspaceInfo;
import dev.turboism.sdk.ui.workspace.WorkspaceOperationResult;
import dev.turboism.sdk.ui.workspace.WorkspaceStatus;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

final class WorkspaceReflectionEngine {
    // ponytail: 64-entry ceiling; introduce pagination only if exact-host evidence exceeds it.
    static final int MAX_AVAILABLE_WORKSPACES = 64;

    static final int MAX_DEFAULT_LAYOUT_BYTES = 16 * 1024 * 1024;

    static final Set<String> REQUIRED_ALIASES = Set.of(
            WorkspaceControlAliases.CLASS,
            WorkspaceControlAliases.INSTANCE,
            WorkspaceControlAliases.MAIN_FRAME,
            WorkspaceControlAliases.DOCK,
            WorkspaceControlAliases.PALETTE_MANAGER,
            WorkspaceControlAliases.CURRENT,
            WorkspaceControlAliases.PRESET,
            WorkspaceControlAliases.CUSTOM,
            WorkspaceControlAliases.ID,
            WorkspaceControlAliases.NAME,
            WorkspaceControlAliases.VALUE,
            WorkspaceControlAliases.CHANGE,
            WorkspaceControlAliases.UPDATE_DEFAULT,
            WorkspaceControlAliases.RESET_DEFAULT,
            WorkspaceControlAliases.DEFAULT_LAYOUT);

    private final VerifiedMemberResolver resolver;

    WorkspaceReflectionEngine(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    WorkspaceStatus readStatus() {
        try {
            final State state = state();
            if (state == null) return unavailable("workspace.host.unavailable");
            if (state.incomplete) return unavailable("workspace.enumeration.incomplete");
            return new WorkspaceStatus(
                    WorkspaceStatus.Availability.AVAILABLE,
                    Optional.of(state.current.info),
                    state.available.stream().map(value -> value.info).toList(),
                    Optional.empty());
        } catch (RuntimeException ignored) {
            return unavailable("workspace.mapping.failed");
        }
    }

    WorkspaceOperationResult.Outcome switchTo(final WorkspaceId requested) {
        try {
            final State before = state();
            if (before == null) return WorkspaceOperationResult.Outcome.UNAVAILABLE;
            // An incompletely enumerated workspace list must never produce a false NOT_FOUND or a
            // partial AVAILABLE view; fail closed without mutating.
            if (before.incomplete) return WorkspaceOperationResult.Outcome.FAILED;
            if (before.current.info.id().equals(requested)) return WorkspaceOperationResult.Outcome.NO_CHANGE;
            final HostWorkspace target = before.available.stream()
                    .filter(workspace -> workspace.info.id().equals(requested))
                    .findFirst()
                    .orElse(null);
            if (target == null) return WorkspaceOperationResult.Outcome.NOT_FOUND;
            resolver.invoke(WorkspaceControlAliases.CHANGE, before.dock, target.hostId);
            final State after = state();
            return after != null && !after.incomplete && after.current.info.id().equals(requested)
                    ? WorkspaceOperationResult.Outcome.CHANGED
                    : WorkspaceOperationResult.Outcome.FAILED;
        } catch (RuntimeException ignored) {
            return WorkspaceOperationResult.Outcome.FAILED;
        }
    }

    WorkspaceOperationResult.Outcome updateDefault() {
        return command(WorkspaceControlAliases.UPDATE_DEFAULT);
    }

    WorkspaceOperationResult.Outcome resetToDefault() {
        return command(WorkspaceControlAliases.RESET_DEFAULT);
    }

    private WorkspaceOperationResult.Outcome command(final String alias) {
        try {
            final State before = state();
            if (before == null) return WorkspaceOperationResult.Outcome.UNAVAILABLE;
            if (before.incomplete) return WorkspaceOperationResult.Outcome.FAILED;
            if (WorkspaceControlAliases.UPDATE_DEFAULT.equals(alias) && !before.currentCustom) {
                return WorkspaceOperationResult.Outcome.FAILED;
            }
            final byte[] savedBefore =
                    WorkspaceControlAliases.UPDATE_DEFAULT.equals(alias) ? defaultLayout(before.current) : null;
            resolver.invoke(alias, before.dock);
            final State after = state();
            if (WorkspaceControlAliases.UPDATE_DEFAULT.equals(alias)) {
                if (after == null
                        || after.incomplete
                        || !after.currentCustom
                        || after.current.hostWorkspace != before.current.hostWorkspace
                        || !after.current.info.id().equals(before.current.info.id())) {
                    return WorkspaceOperationResult.Outcome.FAILED;
                }
                final byte[] savedAfter = defaultLayout(after.current);
                if (savedAfter == null && savedBefore != null) return WorkspaceOperationResult.Outcome.FAILED;
                return java.util.Arrays.equals(savedBefore, savedAfter)
                        ? WorkspaceOperationResult.Outcome.NO_CHANGE
                        : WorkspaceOperationResult.Outcome.CHANGED;
            }
            return after != null && !after.incomplete && after.current.info.id().equals(before.current.info.id())
                    ? WorkspaceOperationResult.Outcome.CHANGED
                    : WorkspaceOperationResult.Outcome.FAILED;
        } catch (RuntimeException ignored) {
            return WorkspaceOperationResult.Outcome.FAILED;
        }
    }

    private State state() {
        final Object app = resolver.invokeStatic(WorkspaceControlAliases.INSTANCE);
        if (app == null) return null;
        final Object frame = resolver.invoke(WorkspaceControlAliases.MAIN_FRAME, app);
        if (frame == null) return null;
        final Object dock = resolver.invoke(WorkspaceControlAliases.DOCK, frame);
        if (dock == null) return null;
        final Object paletteManager = resolver.invoke(WorkspaceControlAliases.PALETTE_MANAGER, dock);
        if (paletteManager == null) return null;
        final HostWorkspace current = workspace(resolver.invoke(WorkspaceControlAliases.CURRENT, paletteManager));
        if (current == null) return null;

        final LinkedHashMap<WorkspaceId, HostWorkspace> available = new LinkedHashMap<>();
        final int[] traversalBudget = {MAX_AVAILABLE_WORKSPACES - 1};
        boolean incomplete = add(available, resolver.invoke(WorkspaceControlAliases.PRESET, dock), traversalBudget);
        final LinkedHashMap<WorkspaceId, HostWorkspace> custom = new LinkedHashMap<>();
        if (!incomplete) {
            incomplete = add(custom, resolver.invoke(WorkspaceControlAliases.CUSTOM, dock), traversalBudget);
        }
        final boolean currentCustom =
                custom.containsKey(current.info.id()) && !available.containsKey(current.info.id());
        custom.forEach(available::putIfAbsent);
        available.putIfAbsent(current.info.id(), current);
        return new State(dock, current, List.copyOf(available.values()), incomplete, currentCustom);
    }

    /**
     * Bounded traversal of one host workspace iterable. Returns true when enumeration is
     * incomplete: the iterable still has items after the traversal budget was exhausted, the list
     * itself is missing (null/non-Iterable), or an item has no resolvable workspace identity. Any
     * of these must fail closed instead of producing a partial AVAILABLE view. Detection uses the
     * iterator so no item is consumed after the budget runs out.
     */
    private boolean add(
            final LinkedHashMap<WorkspaceId, HostWorkspace> target, final Object value, final int[] traversalBudget) {
        if (!(value instanceof Iterable<?> values)) return true;
        final java.util.Iterator<?> iterator = values.iterator();
        while (iterator.hasNext() && traversalBudget[0] > 0) {
            traversalBudget[0]--;
            final HostWorkspace workspace = workspace(iterator.next());
            if (workspace == null) return true;
            target.putIfAbsent(workspace.info.id(), workspace);
        }
        return iterator.hasNext();
    }

    private byte[] defaultLayout(final HostWorkspace workspace) {
        final Object value = resolver.invoke(WorkspaceControlAliases.DEFAULT_LAYOUT, workspace.hostWorkspace);
        if (value == null) return null;
        if (!(value instanceof byte[] bytes) || bytes.length > MAX_DEFAULT_LAYOUT_BYTES) {
            throw new IllegalStateException("Workspace default layout is invalid or exceeds the copy limit.");
        }
        return bytes.clone();
    }

    private HostWorkspace workspace(final Object hostWorkspace) {
        if (hostWorkspace == null) return null;
        final Object hostId = resolver.invoke(WorkspaceControlAliases.ID, hostWorkspace);
        if (hostId == null) return null;
        final Object rawId = resolver.invoke(WorkspaceControlAliases.VALUE, hostId);
        if (!(rawId instanceof String id) || id.isBlank()) return null;
        final Object rawName = resolver.invoke(WorkspaceControlAliases.NAME, hostWorkspace);
        final String name = rawName instanceof String text && !text.isBlank() ? text : id;
        return new HostWorkspace(hostWorkspace, hostId, new WorkspaceInfo(new WorkspaceId(id), name));
    }

    private static WorkspaceStatus unavailable(final String diagnostic) {
        return new WorkspaceStatus(
                WorkspaceStatus.Availability.UNAVAILABLE, Optional.empty(), List.of(), Optional.of(diagnostic));
    }

    private record HostWorkspace(Object hostWorkspace, Object hostId, WorkspaceInfo info) {}

    private record State(
            Object dock,
            HostWorkspace current,
            List<HostWorkspace> available,
            boolean incomplete,
            boolean currentCustom) {}
}
