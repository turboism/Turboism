package dev.turboism.adapter.cubism.optimization.stateelision;

import java.util.Map;
import java.util.Objects;

/**
 * Reviewed redundant-GL-state elision target for the test-only experiment.
 *
 * <p>The interception point is the bundled JOGL implementation class
 * {@code jogamp/opengl/gl4/GL4bcImpl}: every public state setter method body
 * ends in a single {@code dispatch_glXxx1} native call, so an early return at
 * method entry is exactly "skip the native call". Intercepting at the
 * implementation (instead of host call sites) covers every Java caller —
 * the host renderer, JOGL internals and the GLJPanel backing path — for all
 * reviewed editors, because the pinned {@code jogl-all.jar} is byte-identical
 * across 5.2.03, 5.3.02 and 5.3.03 ({@code 7dbedb4b…}, the
 * {@code CUBISM_5_3_03_JOGL} digest).</p>
 *
 * <p>Method classification is deliberately conservative. Exact-name prefixes
 * of every tracked method (covering {@code i}/{@code Separate}/{@code ARB}/
 * {@code EXT}/{@code APPLE}/{@code AMD} variants that write the same domain)
 * plus a curated mutator family become invalidate sites; anything else passes
 * through untouched. Wrong-direction failures only ever over-report.</p>
 */
public final class RedundantStateElisionTarget {

    /** The bundled JOGL implementation owning the tracked setters. */
    public static final String OWNER = "jogamp/opengl/gl4/GL4bcImpl";

    private RedundantStateElisionTarget() { }

    /**
     * Tracked elision-capable methods. Site ids are stable and reported as
     * {@code <stat>Calls/Elided/Passed} counters. Descriptor variants of these
     * methods (any name sharing a tracked prefix but a different descriptor)
     * fall through to {@link #invalidates(String)}.
     */
    public static final Map<String, Integer> SITES = Map.ofEntries(
        Map.entry("glUseProgram(I)V", 0),
        Map.entry("glEnable(I)V", 1),
        Map.entry("glDisable(I)V", 2),
        Map.entry("glBlendFunc(II)V", 3),
        Map.entry("glBlendFuncSeparate(IIII)V", 4),
        Map.entry("glBlendEquation(I)V", 5),
        Map.entry("glBlendEquationSeparate(II)V", 6),
        Map.entry("glCullFace(I)V", 7),
        Map.entry("glFrontFace(I)V", 8),
        Map.entry("glDepthMask(Z)V", 9),
        Map.entry("glDepthFunc(I)V", 10),
        Map.entry("glColorMask(ZZZZ)V", 11),
        Map.entry("glStencilFunc(III)V", 12),
        Map.entry("glStencilOp(III)V", 13),
        Map.entry("glStencilMask(I)V", 14),
        Map.entry("glActiveTexture(I)V", 15),
        Map.entry("glBindTexture(II)V", 16),
        Map.entry("glBindSampler(II)V", 17),
        Map.entry("glEnableVertexAttribArray(I)V", 18),
        Map.entry("glDisableVertexAttribArray(I)V", 19),
        Map.entry("glBindBuffer(II)V", 20));

    /** Human-readable stat prefix per tracked site id. */
    public static final String[] SITE_NAMES = {
        "useProgram", "enable", "disable", "blendFunc", "blendFuncSeparate",
        "blendEquation", "blendEquationSeparate", "cullFace", "frontFace",
        "depthMask", "depthFunc", "colorMask", "stencilFunc", "stencilOp",
        "stencilMask", "activeTexture", "bindTexture", "bindSampler",
        "enableVertexAttribArray", "disableVertexAttribArray", "bindBuffer"};

    /** Per-site int argument count, in declared order. */
    public static final int[] SITE_ARITY = {
        1, 1, 1, 2, 4, 1, 2, 1, 1, 1, 1, 4, 3, 3, 1, 1, 2, 2, 1, 1, 2};

    /** First invalidator site id; invalidators report as {@code <name>Invalidations}. */
    public static final int INVALIDATOR_BASE = 1000;

    /**
     * Whether a concrete {@code GL4bcImpl} method named {@code name} must be
     * instrumented as an invalidation site. Checked only when the exact
     * {@code name+descriptor} is not a tracked site, so the tracked methods
     * themselves never reach this predicate.
     */
    public static boolean invalidates(final String name) {
        Objects.requireNonNull(name, "name");
        // Variants that write a tracked domain under another name/arity.
        for (final String tracked : TRACKED_PREFIXES) {
            if (name.startsWith(tracked)) return true;
        }
        for (final String prefix : INVALIDATOR_PREFIXES) {
            if (name.startsWith(prefix)) return true;
        }
        return INVALIDATOR_EXACT.contains(name);
    }

    private static final String[] TRACKED_PREFIXES = {
        "glUseProgram", "glEnable", "glDisable", "glBlendFunc", "glBlendEquation",
        "glCullFace", "glFrontFace", "glDepthMask", "glDepthFunc", "glColorMask",
        "glStencilFunc", "glStencilOp", "glStencilMask", "glActiveTexture",
        "glBindTexture", "glBindSampler", "glEnableVertexAttrib",
        "glDisableVertexAttrib", "glBindBuffer"};

    private static final String[] INVALIDATOR_PREFIXES = {
        // Object deletion: names can be reused by a shared-group context.
        "glDelete",
        // Push/pop restores enables wholesale; matrix/name/debug variants are
        // harmless extra clears and keep the rule simple.
        "glPush", "glPop",
        // VAO captures vertex-attrib enables and the element binding.
        "glBindVertexArray", "glBindVertexBuffer", "glVertexArray",
        // Framebuffer/renderbuffer binding is per-context; cleared defensively.
        "glBindFramebuffer", "glBindRenderbuffer",
        // Program lifecycle and alternate program-binding entry points.
        "glLinkProgram", "glBindProgram", "glActiveShaderProgram",
        "glBindFragDataLocation", "glBindVertexShader", "glBindFragmentShader",
        "glProgramBinary",
        // Indexed enable variants not caught by the glEnable/glDisable prefixes
        // would be impossible (they share them); keep the list honest anyway.
        "glEnableVertexArray", "glDisableVertexArray",
        // Texture/sampler binding variants outside the tracked prefixes.
        "glBindMultiTexture", "glBindImageTexture",
        "glClientActiveTexture", "glActiveStencilFace",
        // Miscellaneous domain writes the host could plausibly emit.
        "glCullParameter", "glBindTransformFeedback", "glBindVideoCapture",
        // Display lists and NV command lists replay captured state writes.
        "glNewList", "glEndList", "glCallList", "glGenLists",
        "glDrawCommandsStates", "glCallCommandList",
        "glListDrawCommandsStatesClient"};

    private static final java.util.Set<String> INVALIDATOR_EXACT = java.util.Set.of(
        "glClientAttribDefaultEXT", "glClientAttribDefaultNV",
        "glEnableVariantClientStateEXT", "glDisableVariantClientStateEXT");
}
