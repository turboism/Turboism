package dev.turboism.plugin.parametersync.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Serializable snapshot of parameter synchronization configuration for a model.
 */
public record ParameterSyncData(
        int version, String modelGuid, boolean globalEnabled, Set<String> synchronizedParameterIds, String savedAt) {

    public static final int CURRENT_VERSION = 4;

    public ParameterSyncData {
        modelGuid = Objects.requireNonNull(modelGuid, "modelGuid").strip();
        if (modelGuid.isEmpty()) {
            throw new IllegalArgumentException("modelGuid must not be blank");
        }
        synchronizedParameterIds =
                Set.copyOf(new LinkedHashSet<>(synchronizedParameterIds != null ? synchronizedParameterIds : Set.of()));
        savedAt = savedAt == null || savedAt.isBlank() ? Instant.now().toString() : savedAt;
    }

    /** Creates enabled synchronization settings with no selected parameters. */
    public static ParameterSyncData empty(final String modelGuid) {
        return new ParameterSyncData(
                CURRENT_VERSION, modelGuid, true, Set.of(), Instant.now().toString());
    }

    /** Creates a timestamped snapshot of the supplied model settings. */
    public static ParameterSyncData of(
            final String modelGuid, final boolean globalEnabled, final Set<String> synchronizedIds) {
        return new ParameterSyncData(
                CURRENT_VERSION,
                modelGuid,
                globalEnabled,
                synchronizedIds,
                Instant.now().toString());
    }

    /** Projects the selected parameters as one compatibility rule when at least two exist. */
    public List<SyncRule> rules() {
        if (synchronizedParameterIds.size() < 2) {
            return List.of();
        }
        return List.of(SyncRule.of(new ArrayList<>(synchronizedParameterIds)).withEnabled(globalEnabled));
    }

    /** Returns the serialized configuration fields for storage. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("version", (long) version);
        map.put("modelGuid", modelGuid);
        map.put("globalEnabled", globalEnabled);
        map.put("synchronizedParameterIds", List.copyOf(synchronizedParameterIds));
        map.put("savedAt", savedAt);
        return map;
    }

    /** Reads a serialized configuration; returns null when its required identity is missing. */
    public static ParameterSyncData fromMap(final Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        final Number verNum = map.get("version") instanceof Number n ? n : CURRENT_VERSION;
        final int ver = verNum.intValue();
        final String guid = map.get("modelGuid") instanceof String s ? s.strip() : "";
        if (guid.isEmpty()) {
            return null;
        }
        final boolean enabled = !(map.get("globalEnabled") instanceof Boolean b) || b;
        final Set<String> ids = new LinkedHashSet<>();

        // Format v4: synchronizedParameterIds
        if (map.get("synchronizedParameterIds") instanceof List<?> list) {
            for (final Object item : list) {
                if (item instanceof String id && !id.isBlank()) {
                    ids.add(id.strip());
                }
            }
        }

        // Format v3 / v2 / v1 fallback: rules list
        if (ids.isEmpty() && map.get("rules") instanceof List<?> rulesList) {
            for (final Object rObj : rulesList) {
                if (rObj instanceof Map<?, ?> r) {
                    if (r.get("sourceId") instanceof String src && !src.isBlank()) {
                        ids.add(src.strip());
                    }
                    if (r.get("members") instanceof List<?> memberList) {
                        for (final Object m : memberList) {
                            if (m instanceof Map<?, ?> mMap && mMap.get("parameterId") instanceof String mId) {
                                ids.add(mId.strip());
                            }
                        }
                    }
                    if (r.get("targets") instanceof List<?> targetList) {
                        for (final Object t : targetList) {
                            if (t instanceof Map<?, ?> tMap && tMap.get("targetId") instanceof String tId) {
                                ids.add(tId.strip());
                            }
                        }
                    }
                    if (r.get("targetId") instanceof String tgt && !tgt.isBlank()) {
                        ids.add(tgt.strip());
                    }
                }
            }
        }

        final String timestamp =
                map.get("savedAt") instanceof String s ? s : Instant.now().toString();
        return new ParameterSyncData(ver, guid, enabled, ids, timestamp);
    }
}
