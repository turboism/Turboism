package dev.turboism.validation.modelupdate;

/**
 * Test-only classification of GL method names into coarse call categories for
 * delegate-time attribution. This table is the single classification source;
 * it inspects only the method name and never queries GL state. First matching
 * rule wins, so order is significant: the exact glGetError match, readback,
 * then upload (buffer or texture payload writes), queries, uniform writes,
 * draws, state changes, buffer/object lifecycle, and finally everything else
 * lands in OTHER. Uncategorized calls are not a classification failure; they
 * are honestly reported under OTHER.
 */
enum GlCallCategory {
    DRAW("draw"),
    UPLOAD("upload"),
    QUERY("query"),
    UNIFORM_WRITE("uniformWrite"),
    STATE("state"),
    READBACK("readback"),
    ERROR_CHECK("errorCheck"),
    BUFFER_LIFECYCLE("bufferLifecycle"),
    OTHER("other");

    private final String reportKey;

    GlCallCategory(String reportKey) {
        this.reportKey = reportKey;
    }

    String reportKey() {
        return reportKey;
    }

    // Buffer/texture payload movement and storage definition. Checked before
    // DRAW so glClearBufferData/glClearBufferSubData stay uploads while
    // framebuffer glClearBufferfv-style clears remain draws.
    private static final String[] UPLOAD_PREFIXES = {
        "glBufferData", "glBufferSubData", "glBufferStorage",
        "glNamedBufferData", "glNamedBufferSubData", "glNamedBufferStorage",
        "glCopyBufferSubData", "glCopyNamedBufferSubData",
        "glMapBuffer", "glMapBufferRange", "glMapNamedBuffer", "glMapNamedBufferRange",
        "glUnmapBuffer", "glUnmapNamedBuffer",
        "glFlushMappedBufferRange", "glFlushMappedNamedBufferRange",
        "glClearBufferData", "glClearBufferSubData",
        "glClearNamedBufferData", "glClearNamedBufferSubData",
        "glClearTexImage", "glClearTexSubImage",
        "glTexImage", "glTexSubImage", "glTexStorage", "glTextureSubImage",
        "glCompressedTexImage", "glCompressedTexSubImage",
        "glCopyTexImage", "glCopyTexSubImage"
    };

    // Every GL getter/status query except the exact glGetError, which is an
    // unconditional error-check marker and is classified as ERROR_CHECK before
    // this list. glGetUniformLocation and glGetUniform* land here, never in
    // UNIFORM_WRITE.
    private static final String[] QUERY_PREFIXES = {"glGet", "glIs", "glCheck"};

    private static final String[] UNIFORM_WRITE_PREFIXES = {"glUniform", "glProgramUniform"};

    // Rasterization submissions. No bare "glDraw" prefix: glDrawBuffer(s) are
    // state, not submissions, and reach STATE below.
    private static final String[] DRAW_PREFIXES = {
        "glDrawArrays", "glDrawElements", "glDrawRangeElements",
        "glMultiDraw", "glDrawTransformFeedback",
        "glClearBuffer", "glClearNamedBuffer"
    };

    private static final String[] STATE_PREFIXES = {
        "glBind", "glUseProgram",
        "glEnable", "glDisable",
        "glBlend", "glViewport", "glScissor", "glDepthRange", "glDepthFunc", "glDepthMask",
        "glVertexAttrib", "glVertexArray",
        "glVertexPointer", "glNormalPointer", "glColorPointer", "glTexCoordPointer", "glIndexPointer",
        "glActiveTexture", "glPixelStore", "glTexParameter",
        "glStencil", "glColorMask", "glLineWidth",
        "glCullFace", "glFrontFace", "glPolygonOffset", "glPolygonMode", "glHint",
        "glPointSize", "glSampleCoverage", "glSampleMask", "glLogicOp",
        "glDrawBuffer", "glReadBuffer", "glProvokingVertex", "glPrimitiveRestart",
        "glClipControl", "glMinSampleShading", "glPatchParameter",
        "glClearColor", "glClearDepth", "glClearStencil", "glClearAccum", "glClearIndex"
    };

    private static final String[] READBACK_PREFIXES = {"glReadPixels", "glReadnPixels"};

    // Object creation/destruction entry points: every glGen* and glDelete*.
    // glGenerate*/glGenerateTexture* are content generation, not lifecycle, and
    // are excluded explicitly so they keep their previous OTHER classification.
    private static final String[] BUFFER_LIFECYCLE_PREFIXES = {"glDelete"};

    static GlCallCategory of(String methodName) {
        if (methodName == null) return OTHER;
        // The application's unconditional error-check marker (shader/A.a) is a
        // synchronization point of interest on its own, not a generic query.
        if (methodName.equals("glGetError")) return ERROR_CHECK;
        if (any(READBACK_PREFIXES, methodName)) return READBACK;
        if (any(UPLOAD_PREFIXES, methodName)) return UPLOAD;
        if (any(QUERY_PREFIXES, methodName)) return QUERY;
        if (any(UNIFORM_WRITE_PREFIXES, methodName)) return UNIFORM_WRITE;
        if (methodName.equals("glClear") || any(DRAW_PREFIXES, methodName)) return DRAW;
        if (any(STATE_PREFIXES, methodName)) return STATE;
        if (methodName.startsWith("glGen") && !methodName.startsWith("glGenerate")
            || any(BUFFER_LIFECYCLE_PREFIXES, methodName)) return BUFFER_LIFECYCLE;
        return OTHER;
    }

    private static boolean any(String[] prefixes, String name) {
        for (String prefix : prefixes) {
            if (name.startsWith(prefix)) return true;
        }
        return false;
    }
}
