package dev.turboism.validation;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/** Test-only access to the current registry-owned native installation, never a legacy agent slot. */
final class NativeHookHandles {
    private NativeHookHandles() {}

    static Object textureInstallation(Class<?> agent) throws ReflectiveOperationException {
        Object registry = ((AtomicReference<?>) field(agent, "HOOKS").get(null)).get();
        if (registry == null) throw new IllegalStateException("hook registry absent");
        Object found = null;
        for (Object entry : (List<?>) field(registry.getClass(), "entries").get(registry)) {
            if (!"TURBOISM_TEXTURE_UPLOAD_PREPARATION".equals(field(entry.getClass(), "id").get(entry))) continue;
            Object handle = field(entry.getClass(), "handle").get(entry);
            // Both TurboismAgent and the native contributor wrap the installation.
            Object candidate = unwrap(handle, "dev.turboism.bootstrap.VerifiedTextureUploadPreparationInstaller", 4);
            if (candidate != null) {
                if (found != null) throw new IllegalStateException("multiple texture installations");
                found = candidate;
            }
        }
        if (found == null) throw new IllegalStateException("registry-owned texture installer absent");
        return found;
    }

    static Object unwrap(Object handle, String expectedClassName, int depth) throws IllegalAccessException {
        if (handle == null || depth < 0) return null;
        if (handle.getClass().getName().equals(expectedClassName)) return handle;
        if (!(handle instanceof AutoCloseable)) return null;
        Object found = null;
        for (Field captured : handle.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(captured.getModifiers())) continue;
            captured.setAccessible(true);
            Object candidate = captured.get(handle);
            if (!(candidate instanceof AutoCloseable)) continue;
            Object installation = unwrap(candidate, expectedClassName, depth - 1);
            if (installation != null) {
                if (found != null) throw new IllegalStateException("multiple captured installations");
                found = installation;
            }
        }
        return found;
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }
}
