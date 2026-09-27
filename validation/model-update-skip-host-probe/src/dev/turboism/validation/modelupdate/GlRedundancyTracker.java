package dev.turboism.validation.modelupdate;

import java.lang.reflect.Method;
import java.nio.Buffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

/**
 * Test-only redundant-state observation for the GL submission probe. Tracks a
 * bounded, per-context "known state" table for the state-write calls listed in
 * the T08 dispatch (bind/enable/program/blend/raster/viewport/pixel-store/
 * attrib/uniform families) and reports calls whose arguments are identical to
 * the last recorded write. Every native call still delegates exactly once;
 * nothing is ever suppressed, reordered or skipped.
 *
 * <p>Undercounting is deliberate and safe: entries live in fixed open-addressed
 * tables keyed by exact (domain, discriminators); unknown units, programs,
 * buffer bindings, uncomparable payload forms, crowded slots, context switches,
 * exceptions and untracked mutators all resolve to "unknown", and the first call
 * after an entry turns unknown is never redundant. Domain invalidations are
 * generation bumps; {@link #ALL} mutators and unrecognized state-write names
 * conservatively invalidate every domain.</p>
 */
final class GlRedundancyTracker {

    // ---- domains -------------------------------------------------------
    private static final int D_BUFFER = 0;
    private static final int D_TEXTURE = 1;
    private static final int D_SAMPLER = 2;
    private static final int D_PROGRAM = 3;
    private static final int D_CAP = 4;
    private static final int D_BLEND = 5;
    private static final int D_RASTER = 6;
    private static final int D_VIEWPORT = 7;
    private static final int D_PIXELSTORE = 8;
    private static final int D_ATTRIB_ON = 9;
    private static final int D_ATTRIB_POINTER = 10;
    private static final int D_UNIFORM = 11;
    private static final int D_ATEX = 12;
    private static final int D_COUNT = 13;

    private static final int ALL = (1 << D_COUNT) - 1;
    // Targeted invalidations above the domain mask.
    private static final int I_ELEMENT = 1 << 16;   // ELEMENT_ARRAY_BUFFER slot
    private static final int I_CAP = 1 << 17;       // cap slot keyed by args[0]
    private static final int I_TARGET = 1 << 18;    // buffer-target slot keyed by args[0]

    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private static final int ARRAY_BUFFER = 34962;

    // ---- tracked kinds ---------------------------------------------------
    static final int K_NONE = -1;
    private static final int K_BIND_BUFFER = 0;
    private static final int K_BIND_TEXTURE = 1;
    private static final int K_ACTIVE_TEXTURE = 2;
    private static final int K_BIND_SAMPLER = 3;
    private static final int K_USE_PROGRAM = 4;
    private static final int K_ENABLE = 5;
    private static final int K_DISABLE = 6;
    private static final int K_BLENDFUNC = 7;
    private static final int K_BLENDFUNC_SEPARATE = 8;
    private static final int K_BLENDEQUATION = 9;
    private static final int K_BLENDEQUATION_SEPARATE = 10;
    private static final int K_CULLFACE = 11;
    private static final int K_FRONTFACE = 12;
    private static final int K_DEPTHMASK = 13;
    private static final int K_DEPTHFUNC = 14;
    private static final int K_COLORMASK = 15;
    private static final int K_STENCILFUNC = 16;
    private static final int K_STENCILOP = 17;
    private static final int K_STENCILMASK = 18;
    private static final int K_VIEWPORT = 19;
    private static final int K_SCISSOR = 20;
    private static final int K_PIXELSTORE = 21;
    private static final int K_ATTRIB_ON = 22;
    private static final int K_ATTRIB_OFF = 23;
    private static final int K_ATTRIB_POINTER = 24;
    private static final int K_UNIFORM_S1 = 25;
    private static final int K_UNIFORM_S2 = 26;
    private static final int K_UNIFORM_S3 = 27;
    private static final int K_UNIFORM_S4 = 28;
    private static final int K_UNIFORM_V = 29;
    private static final int K_UNIFORM_M = 30;
    private static final int K_PROGUNIFORM_S1 = 31;
    private static final int K_PROGUNIFORM_S2 = 32;
    private static final int K_PROGUNIFORM_S3 = 33;
    private static final int K_PROGUNIFORM_S4 = 34;
    private static final int K_PROGUNIFORM_V = 35;
    private static final int K_PROGUNIFORM_M = 36;

    private static final int STATE_SLOTS = 256;
    private static final int UNIFORM_SLOTS = 512;
    private static final int PROBE_LIMIT = 8;
    private static final int MAX_COMPONENTS = 16;

    /** One table entry; all storage is preallocated at construction. */
    private static final class Slot {
        long key;           // 0 = empty
        int gen = -1;       // domain generation when written
        Object context;     // GL context identity when written
        long v0, v1, v2, v3, v4, v5; // scalar payload
        long meta;          // form extras (component count / transpose)
        int fvLen = -1;     // >=0 → fv[] payload, scalars ignored
        final float[] fv = new float[MAX_COMPONENTS]; // buffer payload as raw bits
    }

    private final Slot[] slots = new Slot[STATE_SLOTS];
    private final Slot[] uniformSlots = new Slot[UNIFORM_SLOTS];
    private final int[] domainGen = new int[D_COUNT];
    private final java.util.function.Supplier<Object> contextSource;
    private long invalidations;

    // Per-call scratch, filled by evaluate and consumed by apply. The probe
    // contract confines collection to the drawable EDT, so scratch reuse is
    // safe and allocates nothing in the call path.
    private boolean sKnown, sHasKey;
    private int sDomain, sD0, sD1, sFvLen;
    private long sKey, sV0, sV1, sV2, sV3, sV4, sV5, sMeta;
    private final float[] sFv = new float[MAX_COMPONENTS];
    private Slot sSlot;
    private Object sContext;

    GlRedundancyTracker(java.util.function.Supplier<Object> contextSource) {
        this.contextSource = contextSource;
        for (int i = 0; i < slots.length; i++) slots[i] = new Slot();
        for (int i = 0; i < uniformSlots.length; i++) uniformSlots[i] = new Slot();
    }

    long invalidations() { return invalidations; }

    /** Invalidates everything; used at collection boundaries. */
    void invalidateAll() {
        for (int d = 0; d < D_COUNT; d++) domainGen[d]++;
        invalidations++;
    }

    /**
     * Construction-time classification. The returned spec packs
     * {@code (kind+1) << 20 | invalidationMask}; decode with {@link #kindOf}
     * and {@link #maskOf}.
     */
    static int classify(Method method) {
        String n = method.getName();
        Integer kind = switch (n) {
            case "glBindBuffer" -> sig(method, int.class, int.class) ? K_BIND_BUFFER : null;
            case "glBindTexture" -> sig(method, int.class, int.class) ? K_BIND_TEXTURE : null;
            case "glActiveTexture" -> sig(method, int.class) ? K_ACTIVE_TEXTURE : null;
            case "glBindSampler" -> sig(method, int.class, int.class) ? K_BIND_SAMPLER : null;
            case "glUseProgram" -> sig(method, int.class) ? K_USE_PROGRAM : null;
            case "glEnable" -> sig(method, int.class) ? K_ENABLE : null;
            case "glDisable" -> sig(method, int.class) ? K_DISABLE : null;
            case "glBlendFunc" -> sig(method, int.class, int.class) ? K_BLENDFUNC : null;
            case "glBlendFuncSeparate" -> sig(method, int.class, int.class, int.class, int.class) ? K_BLENDFUNC_SEPARATE : null;
            case "glBlendEquation" -> sig(method, int.class) ? K_BLENDEQUATION : null;
            case "glBlendEquationSeparate" -> sig(method, int.class, int.class) ? K_BLENDEQUATION_SEPARATE : null;
            case "glCullFace" -> sig(method, int.class) ? K_CULLFACE : null;
            case "glFrontFace" -> sig(method, int.class) ? K_FRONTFACE : null;
            case "glDepthMask" -> sig(method, boolean.class) ? K_DEPTHMASK : null;
            case "glDepthFunc" -> sig(method, int.class) ? K_DEPTHFUNC : null;
            case "glColorMask" -> sig(method, boolean.class, boolean.class, boolean.class, boolean.class) ? K_COLORMASK : null;
            case "glStencilFunc" -> sig(method, int.class, int.class, int.class) ? K_STENCILFUNC : null;
            case "glStencilOp" -> sig(method, int.class, int.class, int.class) ? K_STENCILOP : null;
            case "glStencilMask" -> sig(method, int.class) ? K_STENCILMASK : null;
            case "glViewport" -> sig(method, int.class, int.class, int.class, int.class) ? K_VIEWPORT : null;
            case "glScissor" -> sig(method, int.class, int.class, int.class, int.class) ? K_SCISSOR : null;
            case "glPixelStorei" -> sig(method, int.class, int.class) ? K_PIXELSTORE : null;
            case "glEnableVertexAttribArray" -> sig(method, int.class) ? K_ATTRIB_ON : null;
            case "glDisableVertexAttribArray" -> sig(method, int.class) ? K_ATTRIB_OFF : null;
            case "glVertexAttribPointer" -> attribPointerSpec(method);
            default -> null;
        };
        if (kind != null) return pack(kind, 0);
        if (n.startsWith("glUniform") || n.startsWith("glProgramUniform")) {
            return uniformSpec(n, method);
        }
        return pack(K_NONE, invalidationMask(n));
    }

    static int kindOf(int spec) { return (spec >>> 20) - 1; }
    static int maskOf(int spec) { return spec & 0xFFFFF; }

    /** Uniform kinds aggregate under {@code uniformRedundant} in the report. */
    static boolean uniformKind(int kind) {
        return kind >= K_UNIFORM_S1 && kind <= K_PROGUNIFORM_M;
    }

    private static int pack(int kind, int mask) { return ((kind + 1) << 20) | (mask & 0xFFFFF); }
    private static int tracked(int kind) { return pack(kind, 0); }

    private static Integer attribPointerSpec(Method m) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length == 6 && p[0] == int.class && p[1] == int.class && p[2] == int.class
            && p[3] == boolean.class && p[4] == int.class && p[5] == long.class) {
            return K_ATTRIB_POINTER;
        }
        // Client-memory Buffer form or any drift: a pointer change we cannot
        // compare → the attrib-pointer domain is invalidated instead.
        return null;
    }

    private static int uniformSpec(String n, Method m) {
        boolean program = n.startsWith("glProgramUniform");
        String suffix = n.substring(program ? "glProgramUniform".length() : "glUniform".length());
        Class<?>[] p = m.getParameterTypes();
        int shift = program ? 1 : 0;
        if (program && (p.length < 2 || p[0] != int.class || p[1] != int.class)) {
            return pack(K_NONE, 1 << D_UNIFORM);
        }
        if (suffix.startsWith("Matrix")) {
            // glUniformMatrixNfv(loc, count, transpose, FloatBuffer)
            if (suffix.endsWith("fv") && p.length == 4 + shift
                && p[shift] == int.class && p[shift + 1] == int.class
                && p[shift + 2] == boolean.class && Buffer.class.isAssignableFrom(p[shift + 3])) {
                return tracked(program ? K_PROGUNIFORM_M : K_UNIFORM_M);
            }
            return pack(K_NONE, 1 << D_UNIFORM);
        }
        if (suffix.length() < 2 || suffix.charAt(0) < '1' || suffix.charAt(0) > '4') {
            return pack(K_NONE, 1 << D_UNIFORM);
        }
        int arity = suffix.charAt(0) - '0';
        String type = suffix.substring(1);
        boolean vector = type.endsWith("v");
        String base = vector ? type.substring(0, type.length() - 1) : type;
        Class<?> component = switch (base) {
            case "i", "ui" -> int.class;
            case "f" -> float.class;
            default -> null;
        };
        if (component == null) return pack(K_NONE, 1 << D_UNIFORM);
        if (vector) {
            if (p.length == 3 + shift && p[shift] == int.class && p[shift + 1] == int.class
                && Buffer.class.isAssignableFrom(p[shift + 2])) {
                return tracked(program ? K_PROGUNIFORM_V : K_UNIFORM_V);
            }
            return pack(K_NONE, 1 << D_UNIFORM);
        }
        if (p.length == arity + 1 + shift && p[shift] == int.class) {
            for (int i = 0; i < arity; i++) {
                if (p[shift + 1 + i] != component) return pack(K_NONE, 1 << D_UNIFORM);
            }
            return tracked(program ? K_PROGUNIFORM_S1 + arity - 1 : K_UNIFORM_S1 + arity - 1);
        }
        return pack(K_NONE, 1 << D_UNIFORM);
    }

    /**
     * Invalidation table for untracked calls that may change tracked state.
     * Recognized mutators invalidate the narrowest honest domain set; any
     * remaining state-write-family name falls back to invalidating everything
     * so an unmapped mutator can never leave stale "known" entries behind.
     */
    private static int invalidationMask(String n) {
        int mask = switch (n) {
            case "glDeleteBuffers" -> (1 << D_BUFFER) | (1 << D_ATTRIB_POINTER);
            case "glDeleteTextures" -> 1 << D_TEXTURE;
            case "glDeleteSamplers" -> 1 << D_SAMPLER;
            case "glDeleteProgram", "glDeletePrograms", "glDeleteProgramPipelines" ->
                (1 << D_PROGRAM) | (1 << D_UNIFORM);
            case "glDeleteVertexArrays" ->
                (1 << D_ATTRIB_ON) | (1 << D_ATTRIB_POINTER) | I_ELEMENT;
            case "glLinkProgram" -> 1 << D_UNIFORM;
            case "glBindVertexArray" ->
                (1 << D_ATTRIB_ON) | (1 << D_ATTRIB_POINTER) | I_ELEMENT;
            case "glVertexArrayElementBuffer" -> I_ELEMENT;
            case "glBindBufferBase", "glBindBufferRange", "glBindBuffersBase",
                 "glBindBuffersRange" -> I_TARGET;
            case "glEnablei", "glDisablei" -> I_CAP;
            case "glStencilFuncSeparate", "glStencilOpSeparate", "glStencilMaskSeparate" ->
                1 << D_RASTER;
            case "glBlendEquationi", "glBlendEquationSeparatei",
                 "glBlendFunci", "glBlendFuncSeparatei" -> 1 << D_BLEND;
            case "glScissorIndexed", "glScissorIndexedv", "glViewportIndexedf",
                 "glViewportIndexedfv", "glViewportArrayv", "glScissorArrayv",
                 "glDepthRangeIndexed", "glDepthRangeArrayv" -> 1 << D_VIEWPORT;
            case "glPixelStoref" -> 1 << D_PIXELSTORE;
            case "glUseProgramStages", "glActiveShaderProgram", "glBindProgramPipeline" ->
                (1 << D_PROGRAM) | (1 << D_UNIFORM);
            case "glEnableVertexArrayAttrib", "glDisableVertexArrayAttrib" -> 1 << D_ATTRIB_ON;
            case "glBindVertexBuffer", "glBindVertexBuffers", "glVertexAttribBinding",
                 "glVertexAttribFormat", "glVertexAttribIFormat", "glVertexAttribLFormat",
                 "glVertexAttribDivisor", "glVertexAttribIPointer", "glVertexAttribLPointer",
                 "glVertexArrayAttribFormat", "glVertexArrayAttribIFormat",
                 "glVertexArrayAttribLFormat", "glVertexArrayAttribBinding",
                 "glVertexArrayVertexBuffer", "glVertexArrayVertexBuffers" ->
                1 << D_ATTRIB_POINTER;
            case "glPushAttrib", "glPopAttrib", "glPushClientAttrib", "glPopClientAttrib" -> ALL;
            // glVertexAttribPointer's client-memory Buffer form cannot be
            // compared; its tracked long-offset form never reaches this table.
            case "glVertexAttribPointer" -> 1 << D_ATTRIB_POINTER;
            default -> 0;
        };
        if (mask != 0) return mask;
        // State-write families we did not map precisely invalidate everything.
        // Query/draw/upload/payload names never reach this list.
        for (String prefix : MUTATOR_PREFIXES) {
            if (n.startsWith(prefix)) return ALL;
        }
        return 0;
    }

    private static final String[] MUTATOR_PREFIXES = {
        "glBind", "glUse", "glEnable", "glDisable", "glVertex", "glProgram",
        "glUniform", "glDelete", "glLink", "glPush", "glPop",
        "glBlend", "glStencil", "glDepth", "glColor", "glCullFace", "glFrontFace",
        "glViewport", "glScissor", "glPixelStore", "glActive"
    };

    private static boolean sig(Method m, Class<?>... expected) {
        Class<?>[] p = m.getParameterTypes();
        if (p.length != expected.length) return false;
        for (int i = 0; i < expected.length; i++) if (p[i] != expected[i]) return false;
        return true;
    }

    private static int in(Object[] args, int i) { return ((Number) args[i]).intValue(); }
    private static long ln(Object[] args, int i) { return ((Number) args[i]).longValue(); }
    private static long bool(Object[] args, int i) { return Boolean.TRUE.equals(args[i]) ? 1L : 0L; }
    private static long fbits(Object[] args, int i) {
        return Float.floatToRawIntBits(((Number) args[i]).floatValue()) & 0xFFFFFFFFL;
    }

    // ---- call-path protocol ----------------------------------------------

    /**
     * Evaluates whether this call rewrites identical state. Must run before the
     * delegate call; the scratch it fills is consumed by {@link #apply}.
     */
    boolean evaluate(int kind, Object[] args) {
        sKnown = true; sHasKey = true; sFvLen = -1;
        sV0 = sV1 = sV2 = sV3 = sV4 = sV5 = sMeta = 0;
        sD0 = sD1 = 0; sSlot = null;
        sContext = context();
        try {
            extract(kind, args);
        } catch (Throwable failure) {
            // Extraction failure can never prove redundancy: treat the call as
            // an opaque state write that invalidates every domain.
            sKnown = false; sHasKey = false; sDomain = -1;
            return false;
        }
        if (!sHasKey) return false;
        // The primary discriminator packs into 24 key bits; anything wider is
        // unrepresentable and falls back to a domain invalidation.
        if (sD0 < 0 || sD0 > 0xFFFFFF) { sKnown = false; sHasKey = false; return false; }
        sKey = key(sDomain, sD0, sD1);
        if (sKey == 0) { sHasKey = false; return false; }
        sSlot = locate(tableFor(sDomain), sKey);
        return sKnown && sSlot != null && sSlot.key == sKey
            && sSlot.gen == domainGen[sDomain] && sSlot.context == sContext && valuesEqual(sSlot);
    }

    private void extract(int kind, Object[] args) {
        switch (kind) {
            case K_BIND_BUFFER -> { sDomain = D_BUFFER; sD0 = in(args, 0); sV0 = in(args, 1); }
            case K_BIND_TEXTURE -> {
                long unit = activeUnit();
                if (unit < 0) { sKnown = false; sHasKey = false; sDomain = D_TEXTURE; return; }
                sDomain = D_TEXTURE; sD0 = (int) unit; sD1 = in(args, 0); sV0 = in(args, 1);
            }
            case K_ACTIVE_TEXTURE -> { sDomain = D_ATEX; sV0 = in(args, 0); }
            case K_BIND_SAMPLER -> { sDomain = D_SAMPLER; sD0 = in(args, 0); sV0 = in(args, 1); }
            case K_USE_PROGRAM -> { sDomain = D_PROGRAM; sV0 = in(args, 0); }
            case K_ENABLE -> { sDomain = D_CAP; sD0 = in(args, 0); sV0 = 1; }
            case K_DISABLE -> { sDomain = D_CAP; sD0 = in(args, 0); sV0 = 0; }
            case K_BLENDFUNC -> { sDomain = D_BLEND; sD0 = 1; sV0 = in(args, 0); sV1 = in(args, 1); }
            case K_BLENDFUNC_SEPARATE -> { sDomain = D_BLEND; sD0 = 2;
                sV0 = in(args, 0); sV1 = in(args, 1); sV2 = in(args, 2); sV3 = in(args, 3); }
            case K_BLENDEQUATION -> { sDomain = D_BLEND; sD0 = 3; sV0 = in(args, 0); }
            case K_BLENDEQUATION_SEPARATE -> { sDomain = D_BLEND; sD0 = 4; sV0 = in(args, 0); sV1 = in(args, 1); }
            case K_CULLFACE -> { sDomain = D_RASTER; sD0 = 1; sV0 = in(args, 0); }
            case K_FRONTFACE -> { sDomain = D_RASTER; sD0 = 2; sV0 = in(args, 0); }
            case K_DEPTHMASK -> { sDomain = D_RASTER; sD0 = 3; sV0 = bool(args, 0); }
            case K_DEPTHFUNC -> { sDomain = D_RASTER; sD0 = 4; sV0 = in(args, 0); }
            case K_COLORMASK -> { sDomain = D_RASTER; sD0 = 5;
                sV0 = bool(args, 0); sV1 = bool(args, 1); sV2 = bool(args, 2); sV3 = bool(args, 3); }
            case K_STENCILFUNC -> { sDomain = D_RASTER; sD0 = 6;
                sV0 = in(args, 0); sV1 = in(args, 1); sV2 = in(args, 2); }
            case K_STENCILOP -> { sDomain = D_RASTER; sD0 = 7;
                sV0 = in(args, 0); sV1 = in(args, 1); sV2 = in(args, 2); }
            case K_STENCILMASK -> { sDomain = D_RASTER; sD0 = 8; sV0 = in(args, 0); }
            case K_VIEWPORT -> { sDomain = D_VIEWPORT; sD0 = 1;
                sV0 = in(args, 0); sV1 = in(args, 1); sV2 = in(args, 2); sV3 = in(args, 3); }
            case K_SCISSOR -> { sDomain = D_VIEWPORT; sD0 = 2;
                sV0 = in(args, 0); sV1 = in(args, 1); sV2 = in(args, 2); sV3 = in(args, 3); }
            case K_PIXELSTORE -> { sDomain = D_PIXELSTORE; sD0 = in(args, 0); sV0 = in(args, 1); }
            case K_ATTRIB_ON -> { sDomain = D_ATTRIB_ON; sD0 = in(args, 0); sV0 = 1; }
            case K_ATTRIB_OFF -> { sDomain = D_ATTRIB_ON; sD0 = in(args, 0); sV0 = 0; }
            case K_ATTRIB_POINTER -> {
                long buffer = boundBuffer(ARRAY_BUFFER);
                sDomain = D_ATTRIB_POINTER; sD0 = in(args, 0);
                sV0 = in(args, 1); sV1 = in(args, 2); sV2 = bool(args, 3);
                sV3 = in(args, 4); sV4 = ln(args, 5); sV5 = buffer;
                if (buffer < 0) sKnown = false;
            }
            default -> uniformExtract(kind, args);
        }
    }

    private void uniformExtract(int kind, Object[] args) {
        boolean programForm = kind >= K_PROGUNIFORM_S1;
        int shift = programForm ? 1 : 0;
        long program;
        if (programForm) {
            program = in(args, 0);
        } else {
            program = currentProgram();
            if (program < 0) { sKnown = false; sHasKey = false; sDomain = D_UNIFORM; return; }
        }
        sDomain = D_UNIFORM;
        sD0 = (int) program;
        sD1 = in(args, shift);
        switch (kind) {
            case K_UNIFORM_S1, K_PROGUNIFORM_S1 -> { sMeta = 1; sV0 = scalar(args, shift + 1); }
            case K_UNIFORM_S2, K_PROGUNIFORM_S2 -> { sMeta = 2; sV0 = scalar(args, shift + 1); sV1 = scalar(args, shift + 2); }
            case K_UNIFORM_S3, K_PROGUNIFORM_S3 -> { sMeta = 3; sV0 = scalar(args, shift + 1); sV1 = scalar(args, shift + 2); sV2 = scalar(args, shift + 3); }
            case K_UNIFORM_S4, K_PROGUNIFORM_S4 -> { sMeta = 4; sV0 = scalar(args, shift + 1); sV1 = scalar(args, shift + 2); sV2 = scalar(args, shift + 3); sV3 = scalar(args, shift + 4); }
            case K_UNIFORM_V, K_PROGUNIFORM_V -> vectorPayload(args, shift + 1, shift + 2);
            case K_UNIFORM_M, K_PROGUNIFORM_M -> {
                sMeta = bool(args, shift + 2);
                vectorPayload(args, shift + 1, shift + 3);
            }
            default -> { sKnown = false; sHasKey = false; }
        }
    }

    private static long scalar(Object[] args, int i) {
        return args[i] instanceof Float ? fbits(args, i) : in(args, i) & 0xFFFFFFFFL;
    }

    /** Copies up to MAX_COMPONENTS buffer elements into scratch as raw bits. */
    private void vectorPayload(Object[] args, int countIndex, int bufferIndex) {
        sMeta |= (long) in(args, countIndex) << 32;
        Buffer buffer = (Buffer) args[bufferIndex];
        int n = buffer.remaining();
        if (n > MAX_COMPONENTS) { sKnown = false; sFvLen = -2; return; }
        sFvLen = n;
        if (buffer instanceof FloatBuffer fb) {
            for (int i = 0; i < n; i++) sFv[i] = fb.get(fb.position() + i);
        } else if (buffer instanceof IntBuffer ib) {
            for (int i = 0; i < n; i++) sFv[i] = Float.intBitsToFloat(ib.get(ib.position() + i));
        } else {
            sKnown = false; sFvLen = -2;
        }
    }

    private boolean valuesEqual(Slot s) {
        if (s.fvLen != sFvLen || s.meta != sMeta) return false;
        if (sFvLen >= 0) {
            for (int i = 0; i < sFvLen; i++) {
                if (Float.floatToRawIntBits(s.fv[i]) != Float.floatToRawIntBits(sFv[i])) return false;
            }
            return true;
        }
        return s.v0 == sV0 && s.v1 == sV1 && s.v2 == sV2
            && s.v3 == sV3 && s.v4 == sV4 && s.v5 == sV5;
    }

    /**
     * Commits the call's state effect after the delegate returns. Successful
     * tracked calls record their new state; failed or uncomparable calls
     * invalidate the narrowest honest target. Invalidator masks apply either
     * way — a mutator that threw still gets no benefit of the doubt.
     */
    void apply(int kind, int invalidate, Object[] args, boolean success) {
        try {
            if (kind >= 0) {
                if (success && sKnown && sHasKey && sSlot != null) {
                    Slot s = sSlot;
                    if (s.key == 0) s.key = sKey;
                    s.gen = domainGen[sDomain];
                    s.context = sContext;
                    s.v0 = sV0; s.v1 = sV1; s.v2 = sV2; s.v3 = sV3; s.v4 = sV4; s.v5 = sV5;
                    s.meta = sMeta;
                    s.fvLen = sFvLen;
                    if (sFvLen >= 0) System.arraycopy(sFv, 0, s.fv, 0, sFvLen);
                } else if (!sKnown || !success) {
                    if (sHasKey && sKey != 0) invalidateSlot(sDomain, sKey);
                    else if (sDomain >= 0) domainGen[sDomain]++;
                    else invalidateAll();
                }
            }
            if (invalidate != 0) applyInvalidation(invalidate, args);
        } catch (Throwable failure) {
            // Bookkeeping must never alter call semantics; fail closed instead.
            invalidateAll();
        }
    }

    private void applyInvalidation(int mask, Object[] args) {
        for (int d = 0; d < D_COUNT; d++) {
            if ((mask & (1 << d)) != 0) domainGen[d]++;
        }
        if ((mask & I_ELEMENT) != 0) {
            invalidateSlot(D_BUFFER, key(D_BUFFER, ELEMENT_ARRAY_BUFFER, 0));
        }
        if ((mask & I_CAP) != 0) invalidateSlot(D_CAP, key(D_CAP, in(args, 0), 0));
        if ((mask & I_TARGET) != 0) invalidateSlot(D_BUFFER, key(D_BUFFER, in(args, 0), 0));
        invalidations++;
    }

    private void invalidateSlot(int domain, long key) {
        Slot s = locate(tableFor(domain), key);
        if (s != null && s.key == key) { s.key = 0; s.gen = -1; }
    }

    // ---- table mechanics ---------------------------------------------------

    private Slot[] tableFor(int domain) { return domain == D_UNIFORM ? uniformSlots : slots; }

    private static long key(int domain, long d0, long d1) {
        return ((long) domain << 56) | ((d0 & 0xFFFFFFL) << 32) | (d1 & 0xFFFFFFFFL);
    }

    private Slot locate(Slot[] table, long key) {
        int mask = table.length - 1;
        int i = (int) (mix(key) & mask);
        for (int probe = 0; probe < PROBE_LIMIT; probe++) {
            Slot s = table[i];
            if (s.key == 0 || s.key == key) return s;
            i = (i + 1) & mask;
        }
        return null; // crowded probe chain: undercount, never collide
    }

    private static long mix(long k) {
        k ^= k >>> 33; k *= 0xff51afd7ed558ccdL; k ^= k >>> 33;
        return k;
    }

    private long activeUnit() {
        Slot s = locate(slots, key(D_ATEX, 0, 0));
        return s != null && s.key != 0 && s.gen == domainGen[D_ATEX] && s.context == sContext
            ? s.v0 : -1;
    }

    private long boundBuffer(int target) {
        Slot s = locate(slots, key(D_BUFFER, target, 0));
        return s != null && s.key != 0 && s.gen == domainGen[D_BUFFER] && s.context == sContext
            ? s.v0 : -1;
    }

    private long currentProgram() {
        Slot s = locate(slots, key(D_PROGRAM, 0, 0));
        return s != null && s.key != 0 && s.gen == domainGen[D_PROGRAM] && s.context == sContext
            ? s.v0 : -1;
    }

    private Object context() {
        // Any read failure yields a fresh identity so recorded state can never
        // match — the pathological path allocates once per affected call.
        try {
            Object ctx = contextSource.get();
            return ctx != null ? ctx : new Object();
        } catch (Throwable failure) {
            return new Object();
        }
    }
}
