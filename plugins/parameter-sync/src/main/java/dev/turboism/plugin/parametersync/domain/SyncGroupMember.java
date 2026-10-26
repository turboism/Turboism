package dev.turboism.plugin.parametersync.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A member parameter within a parameter synchronization group.
 */
public record SyncGroupMember(String parameterId, SyncMode mode, float ratio, float offset) {

    public SyncGroupMember {
        Objects.requireNonNull(parameterId, "parameterId");
        parameterId = parameterId.strip();
        if (parameterId.isEmpty()) {
            throw new IllegalArgumentException("parameterId must not be blank");
        }
        mode = mode != null ? mode : SyncMode.NORMALIZED_RANGE;
        ratio = Float.isFinite(ratio) ? ratio : 1.0f;
        offset = Float.isFinite(offset) ? offset : 0.0f;
    }

    /** Creates a group member with the supplied ID and optional synchronization mode. */
    public static SyncGroupMember of(final String parameterId) {
        return new SyncGroupMember(parameterId, SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f);
    }

    /** Creates a group member with the supplied ID and optional synchronization mode. */
    public static SyncGroupMember of(final String parameterId, final SyncMode mode) {
        return new SyncGroupMember(parameterId, mode, 1.0f, 0.0f);
    }

    /** Returns the serialized configuration fields for storage. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("parameterId", parameterId);
        map.put("mode", mode.name());
        map.put("ratio", (double) ratio);
        map.put("offset", (double) offset);
        return map;
    }

    /** Reads a serialized configuration; returns null when its required identity is missing. */
    public static SyncGroupMember fromMap(final Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        final String paramId = map.get("parameterId") instanceof String s ? s.strip() : null;
        if (paramId == null || paramId.isEmpty()) {
            return null;
        }
        final String modeStr = map.get("mode") instanceof String s ? s : SyncMode.NORMALIZED_RANGE.name();
        SyncMode mode;
        try {
            mode = SyncMode.valueOf(modeStr);
        } catch (IllegalArgumentException e) {
            mode = SyncMode.NORMALIZED_RANGE;
        }
        final float ratio = map.get("ratio") instanceof Number n ? n.floatValue() : 1.0f;
        final float offset = map.get("offset") instanceof Number n ? n.floatValue() : 0.0f;
        return new SyncGroupMember(paramId, mode, ratio, offset);
    }
}
