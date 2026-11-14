package dev.turboism.sdk.ui;

import dev.turboism.sdk.CubismEditor;

/**
 * Plugin-owned handle for content contributed through {@link HostViewPanel#attach}.
 *
 * <p>The plugin updates the rendered content through this semantic surface; the host
 * renderer and its concrete widget types stay owned by the runtime adapter. Every method is
 * safe to call from any thread and never throws because the host view moved on.</p>
 */
@CubismEditor(from = "5.2.00")
public interface HostViewPanelHandle extends AutoCloseable {

    /**
     * Replaces the rendered content. The runtime marshals the update onto its UI thread; an
     * update that arrives after this handle or its anchor is fenced is dropped silently
     * rather than thrown back into the host, and the last accepted content stays rendered.
     *
     * @param content the new panel value, never {@code null}
     */
    void setContent(PanelView content);

    /**
     * Reports whether the contributed content is currently on screen. A plugin that polls a
     * host session to keep its panel current reads the session only while this is {@code true},
     * so a hidden, minimized or not-yet-rebuilt host view costs no host reads. The value turns
     * back to {@code true} once the host shows the panel again.
     *
     * @return {@code true} while the panel is attached to a showing host view
     */
    boolean isVisible();

    /**
     * Detaches the content and releases its runtime registration.
     *
     * <p>Idempotent, and terminal: a handle kept past its plugin's generation fence stays
     * closed, so a late {@link #setContent} never renders into a retired host view.
     */
    @Override
    void close();
}
