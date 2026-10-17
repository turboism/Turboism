package dev.turboism.plugin.parameterlock;

import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable serializable snapshot of locked parameter IDs for a specific Cubism model.
 *
 * <p>Keyed by native model GUID (from {@code CModelSource.guid}), guaranteeing cross-session
 * persistence when a model file is reopened.</p>
 */
record ParameterLockData(int version, String modelGuid, Set<String> lockedParameterIds, String savedAt) {

    public static final int CURRENT_VERSION = 1;

    public ParameterLockData {
        modelGuid = Objects.requireNonNull(modelGuid, "modelGuid").strip();
        if (modelGuid.isEmpty()) {
            throw new IllegalArgumentException("modelGuid must not be blank");
        }
        lockedParameterIds =
                Set.copyOf(new LinkedHashSet<>(Objects.requireNonNull(lockedParameterIds, "lockedParameterIds")));
        savedAt = savedAt == null || savedAt.isBlank() ? Instant.now().toString() : savedAt;
    }

    public static ParameterLockData empty(final String modelGuid) {
        return new ParameterLockData(
                CURRENT_VERSION, modelGuid, Set.of(), Instant.now().toString());
    }

    public static ParameterLockData of(final String modelGuid, final Collection<String> lockedParameterIds) {
        return new ParameterLockData(
                CURRENT_VERSION,
                modelGuid,
                Set.copyOf(lockedParameterIds),
                Instant.now().toString());
    }

    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", (long) version);
        map.put("modelGuid", modelGuid);
        map.put("lockedParameterIds", List.copyOf(lockedParameterIds));
        map.put("savedAt", savedAt);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ParameterLockData fromMap(final Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        final Number verNum = map.get("version") instanceof Number n ? n : CURRENT_VERSION;
        final int ver = verNum.intValue();
        final String guid = map.get("modelGuid") instanceof String s ? s.strip() : "";
        if (guid.isEmpty()) {
            return null;
        }
        final Set<String> ids = new LinkedHashSet<>();
        if (map.get("lockedParameterIds") instanceof List<?> list) {
            for (final Object item : list) {
                if (item instanceof String id && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }
        final String timestamp =
                map.get("savedAt") instanceof String s ? s : Instant.now().toString();
        return new ParameterLockData(ver, guid, ids, timestamp);
    }
}
