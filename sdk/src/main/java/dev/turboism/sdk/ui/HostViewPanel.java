package dev.turboism.sdk.ui;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.Incubating;

/**
 * Framework capability: contributes plugin-owned {@link PanelView} content into a named
 * host view.
 *
 * <p>An anchor (see {@link HostViewAnchors}) names one host window the runtime can render
 * into. The runtime owns attachment, rendering, ordering and disposal; the plugin owns the
 * panel's content and its refresh policy. Attaching is legal before the host view exists:
 * the content is rendered when the view is constructed and again after every host rebuild,
 * so a plugin never tracks the host's UI lifecycle.</p>
 */
@Incubating
@CubismEditor(from = "5.2.00")
public interface HostViewPanel {

    /**
     * Attaches plugin-owned content to one host view anchor.
     *
     * @param anchor the host view to attach to, one of the {@link HostViewAnchors} constants
     * @param content the toolkit-neutral panel value the runtime renders, never {@code null}
     * @param order ascending position among the panels attached to the same anchor
     * @return the handle that replaces the content, reports visibility and detaches it
     * @throws UnsupportedOperationException when this instance is the {@link #unavailable()} sentinel
     * @throws IllegalArgumentException when the runtime cannot render into {@code anchor}
     * @throws IllegalStateException when the anchor or this capability is already closed
     */
    HostViewPanelHandle attach(String anchor, PanelView content, int order);

    /**
     * Reports whether a live runtime backend backs this service.
     *
     * @return {@code false} when the backend backing this instance is unavailable, including
     *     the {@link #unavailable()} sentinel
     */
    default boolean isAvailable() {
        return true;
    }

    /**
     * Returns this service's fail-closed {@code Unavailable} sentinel.
     *
     * @return the shared singleton; {@link #isAvailable()} is {@code false} only for it
     */
    static HostViewPanel unavailable() {
        return Unavailable.INSTANCE;
    }

    /**
     * Sentinel returned by {@link #unavailable()}: {@link #attach} throws a stable
     * {@link UnsupportedOperationException}, matching the other unavailable contribution
     * surfaces; probe with {@link #isAvailable()} first.
     */
    enum Unavailable implements HostViewPanel {
        INSTANCE;

        @Override
        public boolean isAvailable() {
            return false;
        }

        @Override
        public HostViewPanelHandle attach(final String anchor, final PanelView content, final int order) {
            throw new UnsupportedOperationException("host view panel contribution is unavailable");
        }
    }
}
