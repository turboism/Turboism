package dev.turboism.adapter.cubism.optimization.uniform;

/**
 * Reviewed JOGL members the uniform-location cache's lifecycle weave depends on.
 *
 * <p>The lifecycle transform wraps program creation and deletion, so it reads the current GL context
 * and asks whether that context is shared and already created. Those three members live on the JOGL
 * context class, and the shader query class exposes the GL handle the weave keys its cache by. Naming
 * them here keeps the composition layer free of host-package literals.</p>
 */
public final class UniformLocationTarget {

    /** Shader helper exposing the GL handle the uniform cache keys its entries by. */
    public static final String GL_OWNER = "com.live2d.graphics3d.a";

    /** Shader helper accessor returning the current GL handle. */
    public static final String GL_ACCESSOR = "a";

    /** {@code a() GL3}. */
    public static final String GL_ACCESSOR_DESCRIPTOR = "()Lcom/jogamp/opengl/GL3;";

    /** Concrete JOGL implementation whose context accessor the lifecycle weave reads. */
    public static final String GL_IMPL_OWNER = "jogamp.opengl.gl4.GL4bcImpl";

    /** Context accessor on the concrete JOGL implementation. */
    public static final String CONTEXT_ACCESSOR = "getContext";

    /** {@code getContext() GLContext}. */
    public static final String CONTEXT_ACCESSOR_DESCRIPTOR = "()Lcom/jogamp/opengl/GLContext;";

    /** JOGL context class carrying the sharing and creation predicates. */
    public static final String CONTEXT_OWNER = "com.jogamp.opengl.GLContext";

    /** Current-context accessor on the JOGL context class. */
    public static final String CURRENT_ACCESSOR = "getCurrent";

    /** {@code getCurrent() GLContext}. */
    public static final String CURRENT_ACCESSOR_DESCRIPTOR = "()Lcom/jogamp/opengl/GLContext;";

    /** Sharing predicate on the JOGL context class. */
    public static final String SHARED_ACCESSOR = "isShared";

    /** Creation predicate on the JOGL context class. */
    public static final String CREATED_ACCESSOR = "isCreated";

    /** {@code isShared() boolean} and {@code isCreated() boolean}. */
    public static final String BOOLEAN_ACCESSOR_DESCRIPTOR = "()Z";

    private UniformLocationTarget() {}
}
