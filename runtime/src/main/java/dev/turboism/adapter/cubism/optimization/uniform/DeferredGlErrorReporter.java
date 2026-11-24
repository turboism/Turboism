package dev.turboism.adapter.cubism.optimization.uniform;

import dev.turboism.core.reflect.HostHandles;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.Objects;

/**
 * Resolves shared JOGL frame-context handles and formats host-equivalent deferred GL error reports.
 */
public final class DeferredGlErrorReporter {

    private static final String FRAME_OWNER = "com/live2d/graphics3d/a";
    private static final String GL_OWNER = "com/jogamp/opengl/GL";
    private static final String GL_CONTEXT_OWNER = "com/jogamp/opengl/GLContext";
    private static final String GL_CONTEXT_DESCRIPTOR = "()Lcom/jogamp/opengl/GLContext;";
    private static final String GL3_RETURN_DESCRIPTOR = "()Lcom/jogamp/opengl/GL3;";
    private static final String BOOLEAN_DESCRIPTOR = "()Z";

    private static final int GL_INVALID_ENUM = 1280;
    private static final int GL_INVALID_VALUE = 1281;
    private static final int GL_INVALID_OPERATION = 1282;
    private static final int GL_OUT_OF_MEMORY = 1285;
    private static final int GL_INVALID_FRAMEBUFFER_OPERATION = 1286;
    private static final int HOST_ERROR_LOG_MODE = 6;

    private DeferredGlErrorReporter() {}

    /**
     * Holds JOGL frame and GL-context accessors bound through {@link HostHandles}.
     *
     * @param frameToGl extracts {@code GL} from the Cubism frame wrapper
     * @param glToContext extracts {@code GLContext} from {@code GL}
     * @param currentContext reads {@code GLContext.getCurrent()}
     * @param contextShared reads {@code GLContext.isShared()}
     * @param contextCreated reads {@code GLContext.isCreated()}
     */
    public record FrameContextAccessors(
            MethodHandle frameToGl,
            MethodHandle glToContext,
            MethodHandle currentContext,
            MethodHandle contextShared,
            MethodHandle contextCreated) {

        /** Validates that every required handle is non-null. */
        public FrameContextAccessors {
            Objects.requireNonNull(frameToGl, "frameToGl");
            Objects.requireNonNull(glToContext, "glToContext");
            Objects.requireNonNull(currentContext, "currentContext");
            Objects.requireNonNull(contextShared, "contextShared");
            Objects.requireNonNull(contextCreated, "contextCreated");
        }
    }

    /**
     * Resolves frame and GL-context accessors on the admitted host loader without initializing types.
     *
     * @param loader host class loader
     * @return resolved frame-context accessor bundle
     * @throws ReflectiveOperationException when any required JOGL or frame member is absent
     */
    public static FrameContextAccessors resolveFrameContext(final ClassLoader loader)
            throws ReflectiveOperationException {
        Objects.requireNonNull(loader, "loader");
        final Class<?> frame = HostHandles.load(loader, FRAME_OWNER);
        final Class<?> gl = HostHandles.load(loader, GL_OWNER);
        final Class<?> context = HostHandles.load(loader, GL_CONTEXT_OWNER);
        return new FrameContextAccessors(
                HostHandles.virtual(
                        frame, "a", GL3_RETURN_DESCRIPTOR, MethodType.methodType(Object.class, Object.class)),
                HostHandles.virtual(
                        gl, "getContext", GL_CONTEXT_DESCRIPTOR, MethodType.methodType(Object.class, Object.class)),
                HostHandles.staticCall(
                        context, "getCurrent", GL_CONTEXT_DESCRIPTOR, MethodType.methodType(Object.class)),
                HostHandles.virtual(
                        context, "isShared", BOOLEAN_DESCRIPTOR, MethodType.methodType(boolean.class, Object.class)),
                HostHandles.virtual(
                        context, "isCreated", BOOLEAN_DESCRIPTOR, MethodType.methodType(boolean.class, Object.class)));
    }

    /**
     * Maps known OpenGL error codes to the host logger's formatted error string, or {@code null}.
     *
     * @param error OpenGL error code returned by {@code glGetError()}
     * @return host-equivalent label, or {@code null} for unmapped codes
     */
    public static String mapErrorCode(final int error) {
        return switch (error) {
            case GL_INVALID_ENUM -> "GL_INVALID_ENUM (1280　無効な列挙　GLenum型の引数が範囲を超えている)";
            case GL_INVALID_VALUE -> "GL_INVALID_VALUE ( 1281\t無効な値　引数が範囲を超えている)";
            case GL_INVALID_OPERATION -> "GL_INVALID_OPERATION ( 1282\t　無効な演算)";
            case GL_OUT_OF_MEMORY -> "GL_OUT_OF_MEMORY (1285 実行するのにメモリが足りない)";
            case GL_INVALID_FRAMEBUFFER_OPERATION ->
                "GL_INVALID_FRAMEBUFFER_OPERATION ( 1286 完全じゃないフレームバッファを書いたり読んだりしようとしている)";
            default -> null;
        };
    }

    /**
     * Logs a mapped frame-end error through the host logger and returns any exception to arm.
     *
     * @param accessors host error logger and {@code GLException} accessors, or {@code null}
     * @param error nonzero OpenGL error code
     * @param context checkpoint message context recorded at first deferred check
     * @param throwSite whether the first checkpoint requested throwing on error
     * @return throwable to arm for frame return, or {@code null}
     */
    public static Throwable buildReport(
            final UniformLocationHookBridge.DeferredAccessors accessors,
            final int error,
            final String context,
            final boolean throwSite) {
        if (error == 0) {
            return null;
        }
        final String mapped = mapErrorCode(error);
        if (mapped == null) {
            return new IllegalStateException("Not impl : " + error);
        }
        final String message = context + ": glError " + mapped;
        try {
            if (accessors != null) {
                accessors
                        .log()
                        .invokeExact(
                                accessors.logger(),
                                (Object) message,
                                false,
                                (String) null,
                                HOST_ERROR_LOG_MODE,
                                (Object) null);
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
        return throwSite ? newException(accessors, message) : null;
    }

    /**
     * Instantiates the host {@code GLException(String)} or a same-message fallback exception.
     *
     * @param accessors host deferred accessors, or {@code null}
     * @param message formatted error message
     * @return host exception instance when reachable, otherwise {@link IllegalStateException}
     */
    public static Throwable newException(
            final UniformLocationHookBridge.DeferredAccessors accessors, final String message) {
        try {
            if (accessors != null) {
                // invoke handles constructors whose concrete type is host GLException rather than Throwable.
                return (Throwable) accessors.exceptionNew().invoke(message);
            }
        } catch (Throwable ignored) {
            FatalErrors.rethrowIfFatal(ignored);
        }
        return new IllegalStateException(message);
    }
}
