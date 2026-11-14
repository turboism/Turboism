package dev.turboism.sdk.ui;

/**
 * Anchor identifiers ({@link HostViewPanel#attach}) naming the host views a plugin may
 * contribute a panel into.
 *
 * <p>An anchor is data, not an SDK type: the runtime keeps the allowlist of anchors its
 * adapters can render into, so a new host window arrives as a runtime adapter and a new
 * constant here rather than as another attachment interface.</p>
 */
public final class HostViewAnchors {

    /** The native texture-atlas editor window; panels are appended below the editor view. */
    public static final String TEXTURE_ATLAS_EDITOR = "cubism.view.texture-atlas-editor";

    private HostViewAnchors() {}
}
