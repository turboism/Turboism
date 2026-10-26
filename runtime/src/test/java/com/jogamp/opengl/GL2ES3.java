package com.jogamp.opengl;

public interface GL2ES3 extends GL {
    void glBlitFramebuffer(
            int sourceLeft,
            int sourceTop,
            int sourceRight,
            int sourceBottom,
            int targetLeft,
            int targetTop,
            int targetRight,
            int targetBottom,
            int mask,
            int filter);
}
