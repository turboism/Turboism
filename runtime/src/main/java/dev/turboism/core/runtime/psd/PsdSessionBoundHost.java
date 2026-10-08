package dev.turboism.core.runtime.psd;

/**
 * Trusted runtime session identity for a current-model port that may issue PSD edit handles.
 *
 * <p>Only runtime composition that captured the live Editor document/model projection implements
 * this: the identity is issued by the adapter, never by a plugin, and the registry compares it
 * verbatim without parsing. A port that cannot supply this identity must not be treated as
 * session-bound; {@link RuntimePsdExportService} fails the export closed instead of inventing a
 * binding, because a fabricated identity would let one document's handle be used from another
 * document that happens to share a model id.</p>
 */
public interface PsdSessionBoundHost {
    /** Opaque runtime-issued document/model session identity; callers must not parse it. */
    String sessionIdentity();

    /** Monotonic generation of that same session; any change invalidates previously issued handles. */
    long generation();
}
