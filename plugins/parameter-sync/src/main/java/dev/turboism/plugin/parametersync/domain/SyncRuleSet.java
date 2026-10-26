package dev.turboism.plugin.parametersync.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Manages the set of parameters synchronized together for an active model.
 */
public final class SyncRuleSet {

    private final Set<String> synchronizedIds = new LinkedHashSet<>();
    private volatile boolean globalEnabled = true;

    public SyncRuleSet() {}

    public SyncRuleSet(final Set<String> initialIds, final boolean globalEnabled) {
        if (initialIds != null) {
            for (final String id : initialIds) {
                if (id != null && !id.isBlank()) {
                    synchronizedIds.add(id.strip());
                }
            }
        }
        this.globalEnabled = globalEnabled;
    }

    /** Returns whether synchronization is globally enabled for this model. */
    public synchronized boolean isGlobalEnabled() {
        return globalEnabled;
    }

    /** Sets the model-wide synchronization enabled state. */
    public synchronized void setGlobalEnabled(final boolean enabled) {
        this.globalEnabled = enabled;
    }

    /** Returns an immutable snapshot of selected parameter IDs. */
    public synchronized Set<String> synchronizedIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(synchronizedIds));
    }

    /** Returns the number of selected parameters. */
    public synchronized int synchronizedCount() {
        return synchronizedIds.size();
    }

    /** Returns whether the parameter is selected and global synchronization is enabled. */
    public synchronized boolean isSynchronized(final String parameterId) {
        if (parameterId == null || !globalEnabled) {
            return false;
        }
        return synchronizedIds.contains(parameterId.strip());
    }

    /** Adds or removes the parameter from the selected set. */
    public synchronized void setSynchronized(final String parameterId, final boolean sync) {
        Objects.requireNonNull(parameterId, "parameterId");
        final String clean = parameterId.strip();
        if (sync) {
            synchronizedIds.add(clean);
        } else {
            synchronizedIds.remove(clean);
        }
    }

    /** Replaces selected IDs, discarding null and blank entries. */
    public synchronized void setAllSynchronized(final Set<String> ids) {
        synchronizedIds.clear();
        if (ids != null) {
            for (final String id : ids) {
                if (id != null && !id.isBlank()) {
                    synchronizedIds.add(id.strip());
                }
            }
        }
    }

    /** Clears the stored synchronization entries. */
    public synchronized void clear() {
        synchronizedIds.clear();
    }

    /** Returns whether this set contains no selected parameters. */
    public synchronized boolean isEmpty() {
        return synchronizedIds.isEmpty();
    }

    // ── Backward Compatibility Accessors ──

    /** Projects all selected parameters as one legacy rule when at least two exist. */
    public synchronized List<SyncRule> allRules() {
        if (synchronizedIds.size() < 2) {
            return List.of();
        }
        return List.of(SyncRule.of(new ArrayList<>(synchronizedIds)).withEnabled(globalEnabled));
    }

    /** Merges legacy rule members into the selected set and preserves enabled rules. */
    public synchronized void addOrUpdate(final SyncRule rule) {
        if (rule != null) {
            synchronizedIds.addAll(rule.memberIds());
            if (rule.enabled()) {
                globalEnabled = true;
            }
        }
    }

    /** Clears all selected parameters; the legacy rule ID is ignored. */
    public synchronized boolean removeRule(final String ruleId) {
        clear();
        return true;
    }

    /** Returns the selected parameter count for legacy callers. */
    public synchronized int size() {
        return synchronizedIds.size();
    }

    /** Returns the shared compatibility rule when this parameter is actively synchronized. */
    public List<SyncRule> findActiveRulesForParameter(final String parameterId) {
        if (isSynchronized(parameterId) && synchronizedIds.size() >= 2) {
            return allRules();
        }
        return List.of();
    }

    /** Returns active compatibility rules for the supplied member parameter. */
    public List<SyncRule> findActiveRulesForSource(final String parameterId) {
        return findActiveRulesForParameter(parameterId);
    }
}
