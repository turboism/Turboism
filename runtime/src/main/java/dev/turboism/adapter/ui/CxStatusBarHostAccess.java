package dev.turboism.adapter.ui;

import java.util.List;

/**
 * Narrow runtime-internal seam isolating the exact CX widget mapping for the
 * platform-owned bottom status region.
 *
 * <p>This seam expresses only the host operations this feature needs: the CX
 * content root, child enumeration, CLabel/CMemoryViewerPanel classification and
 * construction, name/text/severity appearance updates, the native
 * {@code add(CWidget, int)} / remove calls, and revalidate/repaint refresh.
 * Production binds it only through the reviewed exact-version resolver
 * (5.2.03, 5.3.02 or 5.3.03); absent, unreviewed, and other-version evidence remains
 * in safe mode.</p>
 */
interface CxStatusBarHostAccess {

    /** Content pane root of the CX widget tree, or {@code null} when not ready. */
    Object contentRoot();

    /**
     * CX children of a container, or {@code null} when the node is not a CX
     * container or its children are unavailable. The returned list must not be
     * modified by callers.
     */
    List<?> children(Object container);

    boolean isCLabel(Object widget);

    boolean isCMemoryViewerPanel(Object widget);

    Object createLabel(String id, String text);

    void setName(Object widget, String id);

    void setText(Object widget, String text);

    void setSeverityAppearance(Object widget, String severity);

    /** Native {@code add(CWidget, int)}; fails closed on failure. */
    void add(Object parent, Object widget, int index);

    /** Native remove; fails closed on failure. */
    void remove(Object parent, Object widget);

    /** revalidate + repaint. */
    void refresh(Object widget);
}
