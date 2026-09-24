package dev.turboism.adapter.cubism.optimization.inputpath;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Reviewed input-path elision target for the test-only per-event Win32/Wine
 * focus/cursor experiment.
 *
 * <p>Every handled input event routes through
 * {@code com.live2d.ui.CWidget}: its {@code requestFocus()V} forwards to
 * {@code getJComponent().requestFocus()} (JDK {@code requestFocusHelper} has no
 * focus-owner short-circuit, so each call reaches
 * {@code peer.requestFocus} → {@code shouldNativelyFocusHeavyweight} → Wine
 * Win32 queries) and its {@code setCursor(Lcom/live2d/type/CCursor;)V} forwards
 * to {@code getJComponent().setCursor(cursor.getJCursor())} (each call ends in
 * {@code Component.updateCursorImmediately} → the native cursor re-query).
 * Neither method de-duplicates: {@code SGViewWindowManager.mouseWheel} and the
 * unconditional {@code setCurrentViewContext → activateView} both hit
 * {@code requestFocus} per wheel event, and {@code decideAction/mouseAction →
 * N.a → CECompletePack.setCursorPack} re-issues {@code setCursor} per event.</p>
 *
 * <p>The reviewed instruction streams of both methods — and of the consulted
 * dependencies {@code CWidget.getJComponent()Ljavax/swing/JComponent;} and
 * {@code CCursor.getJCursor()Ljava/awt/Cursor;} — are bytecode-identical on the
 * reviewed 5.2.03, 5.3.02 and 5.3.03 artifacts (javap comparison), so all three
 * digests admit the experiment; the per-artifact {@code ReviewedMethodShape}
 * gate still pins the exact body before any rewrite.</p>
 */
public record InputPathElisionTarget(
        HostArtifactDigest digest,
        String version) {

    /** The widget base class both call chains dispatch to. */
    public static final String OWNER = "com/live2d/ui/CWidget";
    /** Focus forwarder: {@code requestFocus() void}. */
    public static final String FOCUS_METHOD = "requestFocus";
    /** {@code requestFocus() void}. */
    public static final String FOCUS_DESCRIPTOR = "()V";
    /** Cursor forwarder: {@code setCursor(CCursor) void}. */
    public static final String CURSOR_METHOD = "setCursor";
    /** {@code setCursor(Lcom/live2d/type/CCursor;)V}. */
    public static final String CURSOR_DESCRIPTOR = "(Lcom/live2d/type/CCursor;)V";
    /** Dependency on the widget: {@code getJComponent() JComponent}. */
    public static final String COMPONENT_METHOD = "getJComponent";
    /** {@code getJComponent() Ljavax/swing/JComponent;}. */
    public static final String COMPONENT_DESCRIPTOR = "()Ljavax/swing/JComponent;";
    /** Dependency owner unwrapping the cursor argument. */
    public static final String CURSOR_OWNER = "com/live2d/type/CCursor";
    /** Dependency: {@code getJCursor() Cursor}. */
    public static final String CURSOR_ACCESSOR = "getJCursor";
    /** {@code getJCursor() Ljava/awt/Cursor;}. */
    public static final String CURSOR_ACCESSOR_DESCRIPTOR = "()Ljava/awt/Cursor;";

    public InputPathElisionTarget {
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(version, "version");
    }

    private static final InputPathElisionTarget CUBISM_5203 =
        new InputPathElisionTarget(ReviewedHostArtifacts.CUBISM_5_2_03, "5.2.03");
    private static final InputPathElisionTarget CUBISM_5302 =
        new InputPathElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_02, "5.3.02");
    private static final InputPathElisionTarget CUBISM_5303 =
        new InputPathElisionTarget(ReviewedHostArtifacts.CUBISM_5_3_03, "5.3.03");

    /** The reviewed target for a host artifact digest, or empty when unsupported. */
    public static Optional<InputPathElisionTarget> of(final HostArtifactDigest digest) {
        Objects.requireNonNull(digest, "digest");
        if (CUBISM_5203.digest().equals(digest)) return Optional.of(CUBISM_5203);
        if (CUBISM_5302.digest().equals(digest)) return Optional.of(CUBISM_5302);
        if (CUBISM_5303.digest().equals(digest)) return Optional.of(CUBISM_5303);
        return Optional.empty();
    }

    /** Every reviewed target, oldest supported version first. */
    public static List<InputPathElisionTarget> all() {
        return List.of(CUBISM_5203, CUBISM_5302, CUBISM_5303);
    }
}
