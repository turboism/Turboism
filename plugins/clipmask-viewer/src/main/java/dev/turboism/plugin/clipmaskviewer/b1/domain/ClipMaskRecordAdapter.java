package dev.turboism.plugin.clipmaskviewer.b1.domain;

import dev.turboism.sdk.cubism.service.clipmask.CubismClipMaskService.ClipMaskRecord;

/**
 * Pure display helpers turning {@link ClipMaskRecord} values into table cells,
 * node labels, and tooltip fragments. No Swing state lives here.
 */
public final class ClipMaskRecordAdapter {

    private ClipMaskRecordAdapter() {
    }

    /** First 8 characters of a GUID, or the whole value when shorter; null-safe. */
    public static String shortGuid(final String guid) {
        if (guid == null) {
            return "";
        }
        return guid.length() <= 8 ? guid : guid.substring(0, 8);
    }

    /**
     * Table label for a record: the display name, plus the id in parentheses
     * unless the id is empty or equals the display name.
     */
    public static String describe(final ClipMaskRecord record, final String fallbackGuid) {
        if (record == null) {
            return "(not found: " + shortGuid(fallbackGuid) + ")";
        }
        final String id = record.id();
        final String name = record.displayName();
        if (id == null || id.isEmpty() || id.equals(name)) {
            return name;
        }
        return name + "  (" + id + ")";
    }

    /** Minimal HTML escaping for tooltip content. */
    public static String escapeHtml(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
