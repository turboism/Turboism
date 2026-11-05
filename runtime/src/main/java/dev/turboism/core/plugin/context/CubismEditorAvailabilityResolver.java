package dev.turboism.core.plugin.context;

import dev.turboism.compatibility.policy.CubismEditorAvailabilityPolicy;
import dev.turboism.compatibility.policy.CubismEditorAvailabilityPolicy.Resolution;
import dev.turboism.sdk.CubismEditor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Collects reviewed {@code @CubismEditor} declarations from SDK types and delegates the version
 * decision to the host-independent policy.
 *
 * <p>Only declaration discovery lives here: the SDK interface hierarchy is walked by reflection
 * and the immutable resolutions are cached per exposed SDK interface. Validation, range
 * expansion and version permission are decided by {@link CubismEditorAvailabilityPolicy}.</p>
 */
final class CubismEditorAvailabilityResolver {

    // ClassValue keeps the cache with the exposed SDK interface rather than retaining unloaded
    // plugin classloaders in a global Method map. Only immutable declarations are cached.
    private static final ClassValue<java.util.concurrent.ConcurrentMap<Method, Resolution>> RESOLUTIONS =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.ConcurrentMap<Method, Resolution> computeValue(final Class<?> type) {
                    return new java.util.concurrent.ConcurrentHashMap<>();
                }
            };

    private CubismEditorAvailabilityResolver() {}

    static Resolution resolve(final Method method) {
        return resolve(method.getDeclaringClass(), method);
    }

    static Resolution resolve(final Class<?> sdkInterface, final Method method) {
        return RESOLUTIONS.get(sdkInterface).computeIfAbsent(method, key -> resolveUncached(sdkInterface, key));
    }

    private static Resolution resolveUncached(final Class<?> sdkInterface, final Method method) {
        final List<CubismEditor> declarations = declarations(sdkInterface, method);
        if (declarations.isEmpty()) {
            return new Resolution(false, List.of(), List.of());
        }
        return CubismEditorAvailabilityPolicy.resolve(declarations, apiId(method));
    }

    static boolean restricts(final Class<?> type) {
        return hasTypeDeclaration(type, new LinkedHashSet<>()) || declaresAnnotatedMethod(type);
    }

    static List<String> reviewedVersions() {
        return CubismEditorAvailabilityPolicy.reviewedVersions();
    }

    private static List<CubismEditor> declarations(final Class<?> sdkInterface, final Method method) {
        final ArrayList<CubismEditor> declarations = new ArrayList<>();
        collectDeclarations(sdkInterface, method, declarations, new LinkedHashSet<>());
        return List.copyOf(declarations);
    }

    private static void collectDeclarations(
            final Class<?> type,
            final Method method,
            final List<CubismEditor> declarations,
            final Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return;
        }
        for (final Class<?> parent : type.getInterfaces()) {
            collectDeclarations(parent, method, declarations, visited);
        }
        final CubismEditor direct = type.getAnnotation(CubismEditor.class);
        if (CubismEditorAvailabilityPolicy.isRestrictive(direct)) {
            declarations.add(direct);
        }
        try {
            final CubismEditor methodDeclaration = type.getDeclaredMethod(method.getName(), method.getParameterTypes())
                    .getAnnotation(CubismEditor.class);
            if (CubismEditorAvailabilityPolicy.isRestrictive(methodDeclaration)) {
                declarations.add(methodDeclaration);
            }
        } catch (NoSuchMethodException inherited) {
            // The interface-level constraint still applies to inherited methods.
        }
    }

    private static boolean declaresAnnotatedMethod(final Class<?> type) {
        for (final Method method : type.getMethods()) {
            if (CubismEditorAvailabilityPolicy.isRestrictive(method.getAnnotation(CubismEditor.class))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasTypeDeclaration(final Class<?> type, final Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return false;
        }
        if (CubismEditorAvailabilityPolicy.isRestrictive(type.getAnnotation(CubismEditor.class))) {
            return true;
        }
        for (final Class<?> parent : type.getInterfaces()) {
            if (hasTypeDeclaration(parent, visited)) {
                return true;
            }
        }
        return false;
    }

    private static String apiId(final Method method) {
        final ArrayList<String> parameters = new ArrayList<>();
        for (final Class<?> parameter : method.getParameterTypes()) {
            parameters.add(parameter.getTypeName());
        }
        return method.getDeclaringClass().getName() + "#" + method.getName() + "(" + String.join(",", parameters) + ")";
    }
}
