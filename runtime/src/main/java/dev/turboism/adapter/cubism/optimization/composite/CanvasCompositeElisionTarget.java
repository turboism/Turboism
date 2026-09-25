package dev.turboism.adapter.cubism.optimization.composite;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Optional;

/**
 * Reviewed target definitions for the test-only canvas-composite elision
 * experiment. The transform does not rewrite the host artifact at all: the two
 * rewritten methods live in the bundled JDK ({@code java.desktop}) and in the
 * FlatLaf library sitting next to {@code Live2D_Cubism.jar} — admission stays
 * pinned to the reviewed Editor digests so the experiment is only offered on
 * the hosts it was validated on.
 *
 * <p>Both sites are entry consults with exact owner/method/descriptor pins:</p>
 * <ul>
 *   <li>{@code javax/swing/RepaintManager$PaintManager.paint(JComponent,
 *   JComponent, Graphics, int,int,int,int)Z}: returning {@code false} selects
 *   the JDK's own direct-paint fallback in
 *   {@code RepaintManager.paint(...)} ({@code setClip} +
 *   {@code paintToOffscreen}), bypassing the shared offscreen back buffer and
 *   its screen blit for canvas repaints;</li>
 *   <li>{@code com/formdev/flatlaf/ui/FlatPanelUI.update(Graphics,JComponent)V}:
 *   returning early skips the background fill only when the panel is fully
 *   covered by an opaque {@code GLJPanel} subtree, in which case the fill is
 *   provably invisible overdraw;</li>
 * </ul>
 */
public record CanvasCompositeElisionTarget(String version, HostArtifactDigest digest) {

    /** Internal name of the JDK paint dispatcher consulted for elision. */
    public static final String PAINT_OWNER = "javax/swing/RepaintManager$PaintManager";
    /** Reviewed entry method of the paint dispatcher. */
    public static final String PAINT_METHOD = "paint";
    /** Reviewed descriptor of the paint dispatcher entry. */
    public static final String PAINT_DESCRIPTOR =
        "(Ljavax/swing/JComponent;Ljavax/swing/JComponent;Ljava/awt/Graphics;IIII)Z";

    /**
     * The {@code RepaintManager.paint} fallback that the false return selects;
     * its shape is verified as a dependency so a JDK without the direct-paint
     * fallback cannot be admitted.
     */
    public static final String PAINT_CALLER_OWNER = "javax/swing/RepaintManager";
    /** Reviewed caller method name. */
    public static final String PAINT_CALLER_METHOD = "paint";
    /** Reviewed caller descriptor. */
    public static final String PAINT_CALLER_DESCRIPTOR =
        "(Ljavax/swing/JComponent;Ljavax/swing/JComponent;Ljava/awt/Graphics;IIII)V";

    /** Internal name of the FlatLaf panel background update consulted for elision. */
    public static final String FILL_OWNER = "com/formdev/flatlaf/ui/FlatPanelUI";
    /** Reviewed fill method name. */
    public static final String FILL_METHOD = "update";
    /** Reviewed fill descriptor. */
    public static final String FILL_DESCRIPTOR =
        "(Ljava/awt/Graphics;Ljavax/swing/JComponent;)V";

    /** Host widget subtree marker the bridge searches for. */
    public static final String GL_PANEL_OWNER = "com/jogamp/opengl/awt/GLJPanel";

    /** Returns the reviewed target for a host artifact digest, or empty. */
    public static Optional<CanvasCompositeElisionTarget> of(final HostArtifactDigest digest) {
        if (ReviewedHostArtifacts.CUBISM_5_2_03.equals(digest)) {
            return Optional.of(new CanvasCompositeElisionTarget("5.2.03", digest));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_02.equals(digest)) {
            return Optional.of(new CanvasCompositeElisionTarget("5.3.02", digest));
        }
        if (ReviewedHostArtifacts.CUBISM_5_3_03.equals(digest)) {
            return Optional.of(new CanvasCompositeElisionTarget("5.3.03", digest));
        }
        return Optional.empty();
    }
}
