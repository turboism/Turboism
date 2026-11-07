package dev.turboism.exportsettings;

import static dev.turboism.exportsettings.ProtectedExportHostVocabulary.*;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import java.io.File;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;

/**
 * Native export and file-cache half of {@link VerifiedProtectedExportHostOperations}: export
 * dialog identity, the export driver's re-driven native entry point, the {@code Function2}
 * completion proxy, and the forced file-handle release that lets staging replace a locked file.
 *
 * <p>Package-private: these are host members the orchestrator reaches through the operations
 * facade, never a public surface. Every access resolves through
 * {@link VerifiedMemberResolver}, so a mismatched host fails closed.</p>
 */
final class ProtectedExportHostExportSurfaceOps {

    private final VerifiedMemberResolver resolver;

    /** Binds the native export surface to one exact-version resolver. */
    ProtectedExportHostExportSurfaceOps(final VerifiedMemberResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    // ------------------------------------------------------------------
    // Export dialog identity + native re-drive
    // ------------------------------------------------------------------

    boolean isExportDialog(final Object owner) {
        return resolver.isInstance(DIALOG_CLASS, owner);
    }

    Object dialogModelSource(final Object dialogOwner) {
        if (!isExportDialog(dialogOwner)) {
            return null;
        }
        return resolver.readField(DIALOG_MODEL_SOURCE, dialogOwner);
    }

    Object exportDriver() {
        return resolver.readStaticField(DRIVER_INSTANCE);
    }

    void invokeNativeExport(
            final Object driver, final Object modelSource, final Object frame, final Object completionCallback) {
        if (driver == null || !resolver.isInstance(DRIVER_CLASS, driver)) {
            throw new IllegalStateException("export driver is not the verified driver type");
        }
        resolver.invoke(
                DRIVER_EXPORT,
                driver,
                Objects.requireNonNull(modelSource, "modelSource"),
                frame,
                Objects.requireNonNull(completionCallback, "completionCallback"));
    }

    Object newExportCompletionProxy(final BiConsumer<File, List<String>> callback) {
        Objects.requireNonNull(callback, "callback");
        // Function2 is stable Kotlin stdlib surface, not an obfuscated host member; the
        // verified export descriptor already pins it as the callback parameter type.
        final ClassLoader hostLoader = resolver.hostClassLoader();
        try {
            final Class<?> function2 = Class.forName("kotlin.jvm.functions.Function2", false, hostLoader);
            if (!function2.isInterface()) {
                throw new IllegalStateException("host Function2 is not an interface");
            }
            final InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class) {
                    return switch (method.getName()) {
                        case "toString" -> "ProtectedExportCompletion";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == (arguments == null ? null : arguments[0]);
                        default -> null;
                    };
                }
                if (!Modifier.isAbstract(method.getModifiers())) {
                    return null;
                }
                final File file = arguments != null && arguments[0] instanceof File f ? f : null;
                final List<String> outputs = new ArrayList<>();
                if (arguments != null && arguments[1] instanceof List<?> list) {
                    for (Object entry : list) {
                        if (entry instanceof String path) {
                            outputs.add(path);
                        }
                    }
                }
                callback.accept(file, List.copyOf(outputs));
                return null;
            };
            return Proxy.newProxyInstance(hostLoader, new Class<?>[] {function2}, handler);
        } catch (ClassNotFoundException failure) {
            throw new IllegalStateException("host Function2 interface is unavailable", failure);
        }
    }

    // ------------------------------------------------------------------
    // Native file-cache force release
    // ------------------------------------------------------------------

    boolean releaseFileHandleFor(final File file) {
        Objects.requireNonNull(file, "file");
        final Object cache = resolver.readStaticField(FILE_CACHE_INSTANCE);
        if (cache == null || !resolver.isInstance(FILE_CACHE_CLASS, cache)) {
            return false;
        }
        final Object handles = resolver.invoke(FILE_CACHE_HANDLES, cache);
        if (!(handles instanceof List<?> list)) {
            return false;
        }
        for (Object handle : new ArrayList<>(list)) {
            if (handle == null || !resolver.isInstance(FILE_HANDLE_CLASS, handle)) {
                continue;
            }
            final Object handleFile = resolver.invoke(FILE_HANDLE_FILE, handle);
            if (!(handleFile instanceof File path) || !path.equals(file)) {
                continue;
            }
            resolver.invoke(FILE_HANDLE_UNLOAD, handle);
            resolver.invoke(FILE_HANDLE_RELEASE, handle, Boolean.TRUE);
            resolver.invoke(FILE_CACHE_REMOVE, cache, handle);
        }
        final Object remaining = resolver.invoke(FILE_CACHE_BY_FILE, cache, file);
        return remaining == null;
    }

    List<String> fileHandleDiagnostics(final File file) {
        Objects.requireNonNull(file, "file");
        final Object cache = resolver.readStaticField(FILE_CACHE_INSTANCE);
        final Object handles = cache == null ? null : resolver.invoke(FILE_CACHE_HANDLES, cache);
        if (!(handles instanceof List<?> list)) {
            return List.of();
        }
        final List<String> diagnostics = new ArrayList<>();
        for (Object handle : list) {
            if (handle == null || !resolver.isInstance(FILE_HANDLE_CLASS, handle)) {
                continue;
            }
            final Object handleFile = resolver.invoke(FILE_HANDLE_FILE, handle);
            if (!(handleFile instanceof File path) || !path.equals(file)) {
                continue;
            }
            final Object loader = resolver.invoke(FILE_HANDLE_LOADER, handle);
            final Object listeners = resolver.invoke(FILE_HANDLE_LISTENERS, handle);
            diagnostics.add(handle.getClass().getName()
                    + "|loader=" + (loader == null ? "null" : loader.getClass().getName())
                    + "|listeners=" + (listeners instanceof List<?> l ? l.size() : "?"));
        }
        return List.copyOf(diagnostics);
    }
}
