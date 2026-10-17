package dev.turboism.plugin.parameterlock;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe in-memory state tracking locked parameters per model GUID.
 *
 * <p>Protects against race conditions where user modifications happen while an asynchronous
 * load from disk is still in flight: user deltas applied during loading are merged rather than
 * overwritten.</p>
 */
final class ParameterLockState {

    private final Map<String, ModelLockEntry> models = new ConcurrentHashMap<>();
    private volatile String activeModelGuid;

    public void setActiveModel(final String modelGuid) {
        this.activeModelGuid = modelGuid == null ? null : modelGuid.strip();
    }

    public String activeModel() {
        return activeModelGuid;
    }

    public boolean hasLoaded(final String modelGuid) {
        if (modelGuid == null) {
            return false;
        }
        final ModelLockEntry entry = models.get(modelGuid.strip());
        return entry != null && entry.isLoaded();
    }

    public boolean isLocked(final String modelGuid, final String parameterId) {
        if (modelGuid == null || parameterId == null) {
            return false;
        }
        final ModelLockEntry entry = models.get(modelGuid.strip());
        return entry != null && entry.isLocked(parameterId.strip());
    }

    public Set<String> getLockedParameters(final String modelGuid) {
        if (modelGuid == null) {
            return Set.of();
        }
        final ModelLockEntry entry = models.get(modelGuid.strip());
        return entry == null ? Set.of() : entry.getLockedParameters();
    }

    /**
     * Merges loaded lock IDs from disk into the model state, preserving any user modifications
     * that occurred while the asynchronous read was in flight.
     */
    public void mergeLoadedLocks(final String modelGuid, final Collection<String> loadedIds) {
        Objects.requireNonNull(modelGuid, "modelGuid");
        final ModelLockEntry entry = models.computeIfAbsent(modelGuid.strip(), ignored -> new ModelLockEntry());
        entry.mergeLoaded(loadedIds);
    }

    public void setLocked(final String modelGuid, final String parameterId, final boolean locked) {
        Objects.requireNonNull(modelGuid, "modelGuid");
        Objects.requireNonNull(parameterId, "parameterId");
        final ModelLockEntry entry = models.computeIfAbsent(modelGuid.strip(), ignored -> new ModelLockEntry());
        entry.setLocked(parameterId.strip(), locked);
    }

    public boolean toggleLock(final String modelGuid, final String parameterId) {
        Objects.requireNonNull(modelGuid, "modelGuid");
        Objects.requireNonNull(parameterId, "parameterId");
        final ModelLockEntry entry = models.computeIfAbsent(modelGuid.strip(), ignored -> new ModelLockEntry());
        return entry.toggle(parameterId.strip());
    }

    public void lockAll(final String modelGuid, final Collection<String> parameterIds) {
        Objects.requireNonNull(modelGuid, "modelGuid");
        if (parameterIds == null || parameterIds.isEmpty()) {
            return;
        }
        final ModelLockEntry entry = models.computeIfAbsent(modelGuid.strip(), ignored -> new ModelLockEntry());
        entry.lockAll(parameterIds);
    }

    public void unlockAll(final String modelGuid, final Collection<String> parameterIds) {
        Objects.requireNonNull(modelGuid, "modelGuid");
        if (parameterIds == null || parameterIds.isEmpty()) {
            return;
        }
        final ModelLockEntry entry = models.computeIfAbsent(modelGuid.strip(), ignored -> new ModelLockEntry());
        entry.unlockAll(parameterIds);
    }

    public void clearAll() {
        models.clear();
        activeModelGuid = null;
    }

    private static final class ModelLockEntry {
        private final Set<String> locks = ConcurrentHashMap.newKeySet();
        private final Set<String> userAdded = ConcurrentHashMap.newKeySet();
        private final Set<String> userRemoved = ConcurrentHashMap.newKeySet();
        private volatile boolean loaded = false;

        boolean isLoaded() {
            return loaded;
        }

        boolean isLocked(final String parameterId) {
            return locks.contains(parameterId);
        }

        Set<String> getLockedParameters() {
            return Collections.unmodifiableSet(Set.copyOf(locks));
        }

        synchronized void mergeLoaded(final Collection<String> loadedIds) {
            if (loadedIds != null) {
                for (final String id : loadedIds) {
                    if (id != null && !id.isBlank() && !userRemoved.contains(id.strip())) {
                        locks.add(id.strip());
                    }
                }
            }
            locks.addAll(userAdded);
            loaded = true;
        }

        synchronized void setLocked(final String parameterId, final boolean locked) {
            if (locked) {
                locks.add(parameterId);
                userAdded.add(parameterId);
                userRemoved.remove(parameterId);
            } else {
                locks.remove(parameterId);
                userRemoved.add(parameterId);
                userAdded.remove(parameterId);
            }
        }

        synchronized boolean toggle(final String parameterId) {
            final boolean willLock = !locks.contains(parameterId);
            setLocked(parameterId, willLock);
            return willLock;
        }

        synchronized void lockAll(final Collection<String> ids) {
            for (final String id : ids) {
                if (id != null && !id.isBlank()) {
                    setLocked(id.strip(), true);
                }
            }
        }

        synchronized void unlockAll(final Collection<String> ids) {
            for (final String id : ids) {
                if (id != null && !id.isBlank()) {
                    setLocked(id.strip(), false);
                }
            }
        }
    }
}
