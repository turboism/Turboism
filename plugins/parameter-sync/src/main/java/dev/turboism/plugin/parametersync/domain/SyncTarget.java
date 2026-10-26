package dev.turboism.plugin.parametersync.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * A target parameter binding within a synchronization rule.
 */
public record SyncTarget(String targetId, SyncMode mode, float ratio, float offset) {

    public SyncTarget {
        Objects.requireNonNull(targetId, "targetId");
        targetId = targetId.strip();
        if (targetId.isEmpty()) {
            throw new IllegalArgumentException("targetId must not be blank");
        }
        mode = mode != null ? mode : SyncMode.NORMALIZED_RANGE;
        ratio = Float.isFinite(ratio) ? ratio : 1.0f;
        offset = Float.isFinite(offset) ? offset : 0.0f;
    }

    /** Creates a target binding with the supplied ID and optional mapping settings. */
    public static SyncTarget of(final String targetId) {
        return new SyncTarget(targetId, SyncMode.NORMALIZED_RANGE, 1.0f, 0.0f);
    }

    /** Creates a target binding with the supplied ID and optional mapping settings. */
    public static SyncTarget of(final String targetId, final SyncMode mode) {
        return new SyncTarget(targetId, mode, 1.0f, 0.0f);
    }

    /** Creates a target binding with the supplied ID and optional mapping settings. */
    public static SyncTarget of(final String targetId, final SyncMode mode, final float ratio, final float offset) {
        return new SyncTarget(targetId, mode, ratio, offset);
    }

    /** Returns the serialized configuration fields for storage. */
    public Map<String, Object> toMap() {
        final Map<String, Object> map = new LinkedHashMap<>();
        map.put("targetId", targetId);
        map.put("mode", mode.name());
        map.put("ratio", (double) ratio);
        map.put("offset", (double) offset);
        return map;
    }

    /** Reads a serialized configuration; returns null when its required identity is missing. */
    public static SyncTarget fromMap(final Map<String, ?> map) {
        if (map == null || map.isEmpty()) {
            return null;
        }
        final String targetId = map.get("targetId") instanceof String s ? s.strip() : null;
        if (targetId == null || targetId.isEmpty()) {
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
        return new SyncTarget(targetId, mode, ratio, offset);
    }
}
