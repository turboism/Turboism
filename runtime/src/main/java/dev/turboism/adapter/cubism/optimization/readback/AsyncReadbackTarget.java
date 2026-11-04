package dev.turboism.adapter.cubism.optimization.readback;

import dev.turboism.mapping.verification.HostArtifactDigest;
import dev.turboism.mapping.verification.ReviewedHostArtifacts;
import java.util.Objects;

/**
 * Reviewed target for the opt-in pipelined (PBO) canvas readback.
 *
 * <p>JOGL's {@code GLJPanel$OffscreenBackend.postGL} ends every painted frame by copying the
 * offscreen FBO into the panel's {@code BufferedImage} with a synchronous
 * {@code GL.glReadPixels}. That call makes the EDT wait for the whole GPU frame; measured on the
 * reviewed host it costs 3.5–7 ms per frame and, more importantly, removes any CPU/GPU overlap.
 * The rewrite replaces <em>only</em> the GLSL-flip readback call with
 * {@link AsyncReadbackBridge#read}, which reads into a pixel-pack buffer and hands the caller the
 * previous frame. Pixels are identical, one frame late.</p>
 *
 * <p>The bundled {@code jogl-all.jar} is digest-attested, and every installed Editor build shares
 * exactly one artifact: 5.0.00 through 5.3.05 beta1 — 38 local installations, stable and beta —
 * all carry the same 3,616,948-byte JOGL 2.4.0 ({@code 7dbedb4b…}, the reviewed
 * {@code CUBISM_5_3_03_JOGL} digest). Editor versions also share the canvas architecture: every
 * checked build ships the same fourteen {@code glWrapper} classes and constructs
 * {@code GLJPanel}, so the rewritten path exists on all of them. The hook therefore depends on the
 * JOGL artifact only, never on the Editor jar. Unknown JOGL builds are refused before any class is
 * retransformed, and the exact {@code postGL} instruction shape is re-verified against the
 * artifact's own reference bytes on every transform.</p>
 */
public final class AsyncReadbackTarget {

    /** JOGL implementation class that owns the offscreen readback. */
    public static final String OWNER = "com/jogamp/opengl/awt/GLJPanel$OffscreenBackend";
    /** Painted-frame entry whose GLSL-flip branch performs the readback. */
    public static final String METHOD = "postGL";
    /** {@code postGL(Graphics, boolean) void}. */
    public static final String DESCRIPTOR = "(Ljava/awt/Graphics;Z)V";
    /** Interface that owns the synchronous readback call. */
    public static final String READ_OWNER = "com/jogamp/opengl/GL";
    /** Readback method name. */
    public static final String READ_METHOD = "glReadPixels";
    /** Client-buffer readback descriptor; the {@code long} (PBO) overload is a different site. */
    public static final String READ_DESCRIPTOR = "(IIIIIILjava/nio/Buffer;)V";
    /** Loader-visible bridge the rewritten call targets. */
    public static final String BRIDGE_CLASS = "dev.turboism.adapter.cubism.optimization.readback.AsyncReadbackBridge";
    /** Internal name of the same bridge, as emitted into the rewritten bytecode. */
    public static final String BRIDGE_OWNER = "dev/turboism/adapter/cubism/optimization/readback/AsyncReadbackBridge";
    /** Bridge entry point. */
    public static final String BRIDGE_METHOD = "read";
    /** {@code read(Object, int, int, int, int, int, int, Buffer) void} — same operands as the native call. */
    public static final String BRIDGE_DESCRIPTOR = "(Ljava/lang/Object;IIIIIILjava/nio/Buffer;)V";

    private AsyncReadbackTarget() {}

    /**
     * Whether the bundled JOGL artifact is the reviewed one.
     *
     * <p>Only the pinned digest is admitted. The class shape is still re-verified per artifact,
     * so a future review that adds another JOGL build gets the same fail-closed protection.</p>
     */
    public static boolean admitted(final HostArtifactDigest joglDigest) {
        return ReviewedHostArtifacts.CUBISM_5_3_03_JOGL.equals(Objects.requireNonNull(joglDigest, "joglDigest"));
    }

    /** Human-readable identity used in diagnostics. */
    public static String describe(final HostArtifactDigest joglDigest) {
        return "jogl=" + joglDigest.size() + ":" + joglDigest.sha256().substring(0, 16);
    }
}
