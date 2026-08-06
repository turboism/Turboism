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

    /** Minimal HTML escaping for tooltip content. */
    public static String escapeHtml(final String value) {
        if (value == null) {
            return "";
        }
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
