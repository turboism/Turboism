package com.live2d.core;

/**
 * Test-only stand-in for the host's copyable contract.
 *
 * <p>The Auto Mesh writer copies a fresh editable mesh into the mesh the edit mode holds through
 * {@code com.live2d.core.ICopyable$b}, so the harness needs the same member shapes the host
 * carries.</p>
 */
public interface ICopyable {

    /**
     * Returns a detached copy.
     *
     * @param context copy context
     * @return the copy
     */
    ICopyable deepCopy(a context);

    /**
     * Replaces this instance's content with the source's content.
     *
     * @param source the content to take
     * @param context copy context
     */
    void reinit(ICopyable source, a context);

    /** Test-only stand-in for the host's Kotlin default-argument bridge. */
    final class b {

        private b() {}

        /**
         * Applies the source's content with the default context, as the host bridge does.
         *
         * @param target the instance to replace
         * @param source the content to take
         * @param context unused when the mask defaults it
         * @param mask default-argument mask
         * @param marker Kotlin default-argument marker
         */
        public static void a(
                final ICopyable target, final ICopyable source, final a context, final int mask, final Object marker) {
            target.reinit(source, context);
        }
    }
}
