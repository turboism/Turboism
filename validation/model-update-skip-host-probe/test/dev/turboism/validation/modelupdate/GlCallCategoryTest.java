package dev.turboism.validation.modelupdate;

/** Boundary tests for the single GL call-category table; no GL is loaded. */
public final class GlCallCategoryTest {
    public static void main(String[] args) {
        for (String name : new String[]{
            "glDrawElements", "glDrawArrays", "glDrawElementsInstanced",
            "glDrawElementsBaseVertex", "glDrawRangeElements", "glMultiDrawElements",
            "glDrawTransformFeedback", "glClear", "glClearBufferfv", "glClearBufferfi",
            "glClearNamedBufferiv"}) {
            check(GlCallCategory.of(name) == GlCallCategory.DRAW, "draw: " + name);
        }
        for (String name : new String[]{
            "glBufferData", "glBufferSubData", "glBufferStorage",
            "glNamedBufferData", "glNamedBufferSubData",
            "glCopyBufferSubData", "glCopyNamedBufferSubData",
            "glMapBuffer", "glMapBufferRange", "glMapNamedBufferRange",
            "glUnmapBuffer", "glUnmapNamedBuffer",
            "glFlushMappedBufferRange", "glFlushMappedNamedBufferRange",
            "glClearBufferData", "glClearBufferSubData",
            "glClearNamedBufferData", "glClearNamedBufferSubData",
            "glClearTexImage", "glClearTexSubImage",
            "glTexImage2D", "glTexSubImage3D", "glTexStorage2D", "glTextureSubImage2D",
            "glCompressedTexImage2D", "glCompressedTexSubImage2D",
            "glCopyTexImage2D", "glCopyTexSubImage2D"}) {
            check(GlCallCategory.of(name) == GlCallCategory.UPLOAD, "upload: " + name);
        }
        for (String name : new String[]{
            "glGetUniformLocation", "glGetUniformfv", "glGetUniformiv",
            "glGetIntegerv", "glGetString", "glGetBufferParameteriv",
            "glIsEnabled", "glIsBuffer", "glCheckFramebufferStatus"}) {
            check(GlCallCategory.of(name) == GlCallCategory.QUERY, "query: " + name);
        }
        check(GlCallCategory.of("glGetError") == GlCallCategory.ERROR_CHECK,
            "glGetError is its own category, not a generic query");
        for (String name : new String[]{
            "glGenBuffers", "glGenTextures", "glGenVertexArrays", "glGenFramebuffers",
            "glGenRenderbuffers", "glGenSamplers", "glGenQueries",
            "glGenTransformFeedbacks", "glGenProgramPipelines",
            "glDeleteBuffers", "glDeleteTextures", "glDeleteVertexArrays",
            "glDeleteFramebuffers", "glDeleteRenderbuffers", "glDeleteSamplers",
            "glDeleteQueries", "glDeleteSync", "glDeleteProgram", "glDeleteShader"}) {
            check(GlCallCategory.of(name) == GlCallCategory.BUFFER_LIFECYCLE,
                "buffer lifecycle: " + name);
        }
        for (String name : new String[]{
            "glUniform1i", "glUniform1f", "glUniform2f", "glUniform4f",
            "glUniformMatrix4fv", "glUniformBlockBinding",
            "glProgramUniform1i", "glProgramUniformMatrix4fv"}) {
            check(GlCallCategory.of(name) == GlCallCategory.UNIFORM_WRITE, "uniform write: " + name);
        }
        for (String name : new String[]{
            "glBindBuffer", "glBindBufferBase", "glBindTexture", "glBindVertexArray",
            "glBindFramebuffer", "glBindSampler", "glUseProgram",
            "glEnable", "glDisable", "glEnableVertexAttribArray", "glDisablei",
            "glBlendFunc", "glBlendEquationSeparate", "glViewport", "glScissor",
            "glVertexAttribPointer", "glVertexAttribDivisor", "glVertexAttribFormat",
            "glVertexArrayElementBuffer", "glVertexArrayAttribBinding",
            "glActiveTexture", "glPixelStorei", "glTexParameteri",
            "glDepthFunc", "glDepthMask", "glStencilFunc", "glStencilMask",
            "glColorMask", "glLineWidth", "glCullFace", "glFrontFace",
            "glPolygonOffset", "glHint", "glDrawBuffer", "glDrawBuffers",
            "glReadBuffer", "glProvokingVertex", "glPrimitiveRestartIndex",
            "glClearColor", "glClearDepth", "glClearStencil"}) {
            check(GlCallCategory.of(name) == GlCallCategory.STATE, "state: " + name);
        }
        check(GlCallCategory.of("glReadPixels") == GlCallCategory.READBACK, "readback");
        check(GlCallCategory.of("glReadnPixels") == GlCallCategory.READBACK, "readback variant");
        for (String name : new String[]{
            "glFlush", "glFinish", "glCreateShader",
            "glShaderSource", "glCompileShader", "glAttachShader", "glLinkProgram",
            "glFenceSync", "glClientWaitSync",
            "glGenerateMipmap", "glGenerateTextureMipmap", "glDrawPixels",
            "glInvalidateBufferData",
            "getGL3", "getContext", ""}) {
            check(GlCallCategory.of(name) == GlCallCategory.OTHER, "other: " + name);
        }
        check(GlCallCategory.of(null) == GlCallCategory.OTHER, "null input is uncategorized");
        // Order-sensitive boundaries: these names are prefixes of one another.
        check(GlCallCategory.of("glClearBufferData") == GlCallCategory.UPLOAD,
            "buffer-data clear is an upload, not a framebuffer clear");
        check(GlCallCategory.of("glClearBufferfv") == GlCallCategory.DRAW,
            "framebuffer clear is a draw, not a buffer-data upload");
        check(GlCallCategory.of("glGetUniformLocation") == GlCallCategory.QUERY,
            "uniform location query is a query, not a uniform write");
        check(GlCallCategory.of("glDrawBuffer") == GlCallCategory.STATE,
            "draw-buffer selection is state, not a draw submission");
        check(GlCallCategory.of("glReadBuffer") == GlCallCategory.STATE,
            "read-buffer selection is state, not a readback");
        check(GlCallCategory.of("glEnableVertexAttribArray") == GlCallCategory.STATE,
            "attrib-array toggle is state via the enable family");
        check(GlCallCategory.of("glGenerateMipmap") == GlCallCategory.OTHER,
            "mipmap generation is content generation, not object lifecycle");
        check(GlCallCategory.of("glIsBuffer") == GlCallCategory.QUERY,
            "object identity query stays a query, not lifecycle");
        System.out.println("GlCallCategoryTest PASS (category boundaries, prefix ordering, uncategorized fallback)");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
