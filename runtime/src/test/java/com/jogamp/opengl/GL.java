package com.jogamp.opengl;

import java.nio.Buffer;

public interface GL {
    int glGetError();

    void glGetIntegerv(int parameter, int[] values, int offset);

    void glReadPixels(int left, int top, int width, int height, int format, int type, Buffer pixels);

    void glReadBuffer(int buffer);

    void glGenFramebuffers(int count, int[] values, int offset);

    void glBindFramebuffer(int target, int framebuffer);

    void glGenTextures(int count, int[] values, int offset);

    void glBindTexture(int target, int texture);

    void glTexImage2D(
            int target,
            int level,
            int internalFormat,
            int width,
            int height,
            int border,
            int format,
            int type,
            Buffer pixels);

    void glFramebufferTexture2D(int target, int attachment, int textureTarget, int texture, int level);

    int glCheckFramebufferStatus(int target);

    void glDeleteFramebuffers(int count, int[] values, int offset);

    void glDeleteTextures(int count, int[] values, int offset);
}
