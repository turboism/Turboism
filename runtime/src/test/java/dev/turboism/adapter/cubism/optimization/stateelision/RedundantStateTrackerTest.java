package dev.turboism.adapter.cubism.optimization.stateelision;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Semantics of the per-context redundant-state tracker. A repeat may only be
 * suppressed while the armed gate is live and the recorded signature for this
 * context+key matches exactly; capability, texture unit+target, sampler unit,
 * attribute index and buffer target all participate in the key. Deletion
 * clears every context through the global epoch, other invalidators clear only
 * their own context, a foreign thread or a thrown call clears the table, and
 * every fail-open path prefers a redundant pass over a wrong elision.
 */
public class RedundantStateTrackerTest {

    private static final int USE_PROGRAM = site("glUseProgram(I)V");
    private static final int ENABLE = site("glEnable(I)V");
    private static final int DISABLE = site("glDisable(I)V");
    private static final int BLEND_FUNC = site("glBlendFunc(II)V");
    private static final int BLEND_FUNC_SEPARATE = site("glBlendFuncSeparate(IIII)V");
    private static final int BLEND_EQUATION = site("glBlendEquation(I)V");
    private static final int BLEND_EQUATION_SEPARATE = site("glBlendEquationSeparate(II)V");
    private static final int CULL_FACE = site("glCullFace(I)V");
    private static final int FRONT_FACE = site("glFrontFace(I)V");
    private static final int DEPTH_MASK = site("glDepthMask(Z)V");
    private static final int DEPTH_FUNC = site("glDepthFunc(I)V");
    private static final int COLOR_MASK = site("glColorMask(ZZZZ)V");
    private static final int STENCIL_FUNC = site("glStencilFunc(III)V");
    private static final int STENCIL_OP = site("glStencilOp(III)V");
    private static final int STENCIL_MASK = site("glStencilMask(I)V");
    private static final int ACTIVE_TEXTURE = site("glActiveTexture(I)V");
    private static final int BIND_TEXTURE = site("glBindTexture(II)V");
    private static final int BIND_SAMPLER = site("glBindSampler(II)V");
    private static final int ENABLE_VAA = site("glEnableVertexAttribArray(I)V");
    private static final int DISABLE_VAA = site("glDisableVertexAttribArray(I)V");
    private static final int BIND_BUFFER = site("glBindBuffer(II)V");

    private static int site(final String key) {
        final Integer site = RedundantStateElisionTarget.SITES.get(key);
        assertNotNull(site, key);
        return site;
    }

    private final Object gl = new Object();

    private static boolean call(final RedundantStateTracker tracker, final Object gl,
                                final int site, final int... args) {
        final int[] padded = new int[4];
        System.arraycopy(args, 0, padded, 0, args.length);
        return tracker.consult(gl, site, padded[0], padded[1], padded[2], padded[3]);
    }

    private RedundantStateTracker armedTracker() {
        final RedundantStateTracker tracker = new RedundantStateTracker();
        tracker.setArmed(true);
        return tracker;
    }

    @Test void exactRepeatElidesPerSite() {
        final RedundantStateTracker tracker = armedTracker();
        final int[][] cases = {
            {USE_PROGRAM, 7, 0, 0, 0},
            {BLEND_FUNC_SEPARATE, 1, 0, 1, 0},
            {BLEND_EQUATION_SEPARATE, 4, 5, 0, 0},
            {CULL_FACE, 1029, 0, 0, 0},
            {FRONT_FACE, 2304, 0, 0, 0},
            {DEPTH_MASK, 1, 0, 0, 0},
            {DEPTH_FUNC, 519, 0, 0, 0},
            {COLOR_MASK, 1, 1, 1, 1},
            {STENCIL_FUNC, 519, 1, 255, 0},
            {STENCIL_OP, 7680, 7681, 7682, 0},
            {STENCIL_MASK, 255, 0, 0, 0},
            {ACTIVE_TEXTURE, 33984, 0, 0, 0},
            {BIND_SAMPLER, 3, 77, 0, 0},
            {BIND_BUFFER, 34962, 42, 0, 0},
        };
        for (final int[] c : cases) {
            assertFalse(call(tracker, gl, c[0], c[1], c[2], c[3], c[4]),
                "first call records for site " + c[0]);
            assertTrue(call(tracker, gl, c[0], c[1], c[2], c[3], c[4]),
                "identical repeat elides for site " + c[0]);
        }
        final var stats = tracker.snapshot(true);
        assertEquals(cases.length, stats.get("elided").intValue());
        assertEquals(cases.length, stats.get("passed").intValue());
        assertEquals(cases.length, stats.get("passNoBaseline").intValue());
    }

    @Test void changedArgumentsPassAndUpdateBaseline() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        assertFalse(call(tracker, gl, USE_PROGRAM, 8), "different program passes");
        assertTrue(call(tracker, gl, USE_PROGRAM, 8), "new value becomes the baseline");
        assertFalse(call(tracker, gl, USE_PROGRAM, 7), "old value differs again");
        assertEquals(2L, tracker.snapshot(true).get("passChanged"));
    }

    @Test void enableDisableShareCapabilityKeyButDifferInSignature() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, ENABLE, 3042));       // GL_BLEND on
        assertTrue(call(tracker, gl, ENABLE, 3042));
        assertFalse(call(tracker, gl, DISABLE, 3042), "disable is a different signature");
        assertTrue(call(tracker, gl, DISABLE, 3042));
        assertFalse(call(tracker, gl, ENABLE, 2929), "other capability has its own key");
        assertFalse(call(tracker, gl, ENABLE, 3042), "cap key still carries the off state");
    }

    @Test void blendFuncAliasFormsShareTheSeparateSignature() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, BLEND_FUNC_SEPARATE, 1, 0, 1, 0));
        assertTrue(call(tracker, gl, BLEND_FUNC, 1, 0),
            "glBlendFunc(s,d) aliases separate(s,d,s,d)");
        assertFalse(call(tracker, gl, BLEND_FUNC, 1, 1), "changed dst factor passes");
        assertTrue(call(tracker, gl, BLEND_FUNC_SEPARATE, 1, 1, 1, 1));
        assertFalse(call(tracker, gl, BLEND_EQUATION, 4));
        assertTrue(call(tracker, gl, BLEND_EQUATION_SEPARATE, 4, 4),
            "glBlendEquation(m) aliases separate(m,m)");
        assertFalse(call(tracker, gl, BLEND_EQUATION_SEPARATE, 4, 5));
    }

    @Test void textureBindingIsKeyedByActiveUnitAndTarget() {
        final RedundantStateTracker tracker = armedTracker();
        final int texture0 = 33984, texture1 = 33985;
        final int tex2d = 3553, texCube = 34067;
        assertEquals(0L, tracker.snapshot(true).get("passUnknownUnit"),
            "counter starts at zero");
        assertFalse(call(tracker, gl, BIND_TEXTURE, tex2d, 11),
            "bind before any activeTexture is unkeyable");
        assertEquals(1L, tracker.snapshot(true).get("passUnknownUnit"));
        call(tracker, gl, ACTIVE_TEXTURE, texture0);
        assertFalse(call(tracker, gl, BIND_TEXTURE, tex2d, 11));
        assertTrue(call(tracker, gl, BIND_TEXTURE, tex2d, 11));
        assertFalse(call(tracker, gl, BIND_TEXTURE, texCube, 11), "different target is a different slot");
        call(tracker, gl, ACTIVE_TEXTURE, texture1);
        assertFalse(call(tracker, gl, BIND_TEXTURE, tex2d, 22), "unit 1 has its own binding");
        assertTrue(call(tracker, gl, BIND_TEXTURE, tex2d, 22));
        call(tracker, gl, ACTIVE_TEXTURE, texture0);
        assertTrue(call(tracker, gl, BIND_TEXTURE, tex2d, 11), "unit 0 binding was retained");
        assertFalse(call(tracker, gl, BIND_TEXTURE, tex2d, 22), "unit 0 texture differs");
    }

    @Test void samplerAndBufferKeysAreUnitAndTargetScoped() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, BIND_SAMPLER, 0, 7));
        assertTrue(call(tracker, gl, BIND_SAMPLER, 0, 7));
        assertFalse(call(tracker, gl, BIND_SAMPLER, 1, 7), "same sampler on unit 1 is a new key");
        assertFalse(call(tracker, gl, BIND_SAMPLER, 0, 8));
        assertFalse(call(tracker, gl, BIND_BUFFER, 34962, 5));
        assertTrue(call(tracker, gl, BIND_BUFFER, 34962, 5));
        assertFalse(call(tracker, gl, BIND_BUFFER, 34963, 5), "element binding is separate");
    }

    @Test void vertexAttribEnablesAreIndexedPerAttribute() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, ENABLE_VAA, 0));
        assertTrue(call(tracker, gl, ENABLE_VAA, 0));
        assertFalse(call(tracker, gl, ENABLE_VAA, 1));
        assertFalse(call(tracker, gl, DISABLE_VAA, 0), "disable is a different signature");
        assertTrue(call(tracker, gl, DISABLE_VAA, 0));
    }

    @Test void contextsAreIndependent() {
        final RedundantStateTracker tracker = armedTracker();
        final Object other = new Object();
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        assertFalse(call(tracker, other, USE_PROGRAM, 7), "other context has its own table");
        assertTrue(call(tracker, other, USE_PROGRAM, 7));
        assertTrue(call(tracker, gl, USE_PROGRAM, 7), "first context retained its baseline");
        assertEquals(2L, tracker.snapshot(true).get("contexts"));
    }

    @Test void contextScopedInvalidatorClearsOnlyThatContext() {
        final RedundantStateTracker tracker = armedTracker();
        final Object other = new Object();
        tracker.registerInvalidator(1000, "glBindFramebuffer");
        tracker.registerInvalidator(1001, "glLinkProgram");
        call(tracker, gl, USE_PROGRAM, 7);
        call(tracker, other, USE_PROGRAM, 7);
        tracker.invalidate(gl, 1000);
        assertFalse(call(tracker, gl, USE_PROGRAM, 7), "cleared context passes once");
        assertTrue(call(tracker, other, USE_PROGRAM, 7), "other context unaffected");
        assertEquals(1L, tracker.snapshot(true).get("contextClears"));
        assertEquals(1L, tracker.snapshot(true).get("glBindFramebufferInvalidations"));
    }

    @Test void glDeleteInvalidationClearsEveryContextViaEpoch() {
        final RedundantStateTracker tracker = armedTracker();
        final Object other = new Object();
        tracker.registerInvalidator(1000, "glDeleteBuffers");
        call(tracker, gl, USE_PROGRAM, 7);
        call(tracker, other, USE_PROGRAM, 7);
        tracker.invalidate(gl, 1000);
        assertFalse(call(tracker, other, USE_PROGRAM, 7), "global epoch clears all contexts");
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        assertEquals(2L, tracker.snapshot(true).get("epochClears"));
    }

    @Test void foreignThreadClearsTheContext() throws Exception {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        final boolean[] second = new boolean[1];
        final Thread other = new Thread(() -> second[0] = call(tracker, gl, USE_PROGRAM, 7));
        other.start();
        other.join();
        assertFalse(second[0], "foreign thread consult cleared and recorded fresh");
        assertFalse(call(tracker, gl, USE_PROGRAM, 7), "owner switch back clears again");
        assertEquals(2L, tracker.snapshot(true).get("threadClears"));
    }

    @Test void thrownCallClearsTheContext() {
        final RedundantStateTracker tracker = armedTracker();
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        tracker.exception(gl);
        assertFalse(call(tracker, gl, USE_PROGRAM, 7), "exception may have skipped the write");
        assertEquals(1L, tracker.snapshot(true).get("exceptionClears"));
    }

    @Test void disarmedGatePassesButStillRecords() {
        final RedundantStateTracker tracker = new RedundantStateTracker();
        assertFalse(call(tracker, gl, USE_PROGRAM, 7), "disarmed never elides");
        assertFalse(call(tracker, gl, USE_PROGRAM, 7));
        tracker.setArmed(true);
        assertTrue(call(tracker, gl, USE_PROGRAM, 7),
            "state recorded while disarmed stays usable when armed");
        final var stats = tracker.snapshot(true);
        assertEquals(2L, stats.get("passGate"));
        assertEquals(1L, stats.get("elided"));
    }

    @Test void observerSlotsAbsentNeverThrows() {
        final RedundantStateTracker tracker = armedTracker();
        tracker.invalidate(gl, 4242);
        assertEquals(1L, tracker.snapshot(true).get("site-4242Invalidations"));
    }

    @Test void invalidatorNameRules() {
        assertTrue(RedundantStateElisionTarget.invalidates("glDeleteBuffers"));
        assertTrue(RedundantStateElisionTarget.invalidates("glDeleteProgram"));
        assertTrue(RedundantStateElisionTarget.invalidates("glBindVertexArray"));
        assertTrue(RedundantStateElisionTarget.invalidates("glBindFramebuffer"));
        assertTrue(RedundantStateElisionTarget.invalidates("glPushAttrib"));
        assertTrue(RedundantStateElisionTarget.invalidates("glPopClientAttrib"));
        assertTrue(RedundantStateElisionTarget.invalidates("glLinkProgram"));
        assertTrue(RedundantStateElisionTarget.invalidates("glUseProgramStages"));
        assertTrue(RedundantStateElisionTarget.invalidates("glBindBufferBase"));
        assertTrue(RedundantStateElisionTarget.invalidates("glBindTextures"));
        assertTrue(RedundantStateElisionTarget.invalidates("glBindSampler"),
            "the bare name is prefix-true; the exact tracked name+desc check happens before it");
        assertTrue(RedundantStateElisionTarget.invalidates("glEnablei"));
        assertTrue(RedundantStateElisionTarget.invalidates("glStencilFuncSeparate"));
        assertTrue(RedundantStateElisionTarget.invalidates("glColorMaski"));
        assertTrue(RedundantStateElisionTarget.invalidates("glEnableVertexAttribAPPLE"));
        assertTrue(RedundantStateElisionTarget.invalidates("glDrawCommandsStatesNV"));
        assertTrue(RedundantStateElisionTarget.invalidates("glCallList"));
        assertFalse(RedundantStateElisionTarget.invalidates("glDrawElements"));
        assertFalse(RedundantStateElisionTarget.invalidates("glUniformMatrix4fv"));
        assertFalse(RedundantStateElisionTarget.invalidates("glBufferSubData"));
        assertFalse(RedundantStateElisionTarget.invalidates("glVertexAttribPointer"));
        assertFalse(RedundantStateElisionTarget.invalidates("glTexSubImage2D"));
        assertFalse(RedundantStateElisionTarget.invalidates("glViewport"));
        assertFalse(RedundantStateElisionTarget.invalidates("glScissor"));
        assertFalse(RedundantStateElisionTarget.invalidates("glClear"));
        assertFalse(RedundantStateElisionTarget.invalidates("glGetError"));
        assertFalse(RedundantStateElisionTarget.invalidates("glTexImage2D"));
        assertFalse(RedundantStateElisionTarget.invalidates("glCompileShader"));
        assertFalse(RedundantStateElisionTarget.invalidates("glDrawArrays"));
    }
}
