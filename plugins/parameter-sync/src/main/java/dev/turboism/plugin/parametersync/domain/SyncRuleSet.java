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

    public synchronized boolean isGlobalEnabled() {
        return globalEnabled;
    }

    public synchronized void setGlobalEnabled(final boolean enabled) {
        this.globalEnabled = enabled;
    }

    public synchronized Set<String> synchronizedIds() {
        return Collections.unmodifiableSet(new LinkedHashSet<>(synchronizedIds));
    }

    public synchronized int synchronizedCount() {
        return synchronizedIds.size();
    }

    public synchronized boolean isSynchronized(final String parameterId) {
        if (parameterId == null || !globalEnabled) {
            return false;
        }
        return synchronizedIds.contains(parameterId.strip());
    }

    public synchronized void setSynchronized(final String parameterId, final boolean sync) {
        Objects.requireNonNull(parameterId, "parameterId");
        final String clean = parameterId.strip();
        if (sync) {
            synchronizedIds.add(clean);
        } else {
            synchronizedIds.remove(clean);
        }
    }

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

    public synchronized void clear() {
        synchronizedIds.clear();
    }

    public synchronized boolean isEmpty() {
        return synchronizedIds.isEmpty();
    }

    // ── Backward Compatibility Accessors ──

    public synchronized List<SyncRule> allRules() {
        if (synchronizedIds.size() < 2) {
            return List.of();
        }
        return List.of(SyncRule.of(new ArrayList<>(synchronizedIds)).withEnabled(globalEnabled));
    }

    public synchronized void addOrUpdate(final SyncRule rule) {
        if (rule != null) {
            synchronizedIds.addAll(rule.memberIds());
            if (rule.enabled()) {
                globalEnabled = true;
            }
        }
    }

    public synchronized boolean removeRule(final String ruleId) {
        clear();
        return true;
    }

    public synchronized int size() {
        return synchronizedIds.size();
    }

    public List<SyncRule> findActiveRulesForParameter(final String parameterId) {
        if (isSynchronized(parameterId) && synchronizedIds.size() >= 2) {
            return allRules();
        }
        return List.of();
    }

    public List<SyncRule> findActiveRulesForSource(final String parameterId) {
        return findActiveRulesForParameter(parameterId);
    }
}
