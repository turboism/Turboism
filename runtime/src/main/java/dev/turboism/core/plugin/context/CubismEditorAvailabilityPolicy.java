package dev.turboism.core.plugin.context;

import dev.turboism.sdk.CubismEditor;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Resolves reviewed Cubism Editor availability declared on public SDK interfaces and methods. */
final class CubismEditorAvailabilityPolicy {

    private static final List<String> REVIEWED_VERSIONS = List.of("5.2.03", "5.3.02", "5.3.03");
    private static final Set<String> REVIEWED_VERSION_SET = Set.copyOf(REVIEWED_VERSIONS);
    // ClassValue keeps the cache with the exposed SDK interface rather than retaining unloaded
    // plugin classloaders in a global Method map. Only immutable declarations are cached.
    private static final ClassValue<java.util.concurrent.ConcurrentMap<Method, Resolution>> RESOLUTIONS =
            new ClassValue<>() {
                @Override
                protected java.util.concurrent.ConcurrentMap<Method, Resolution> computeValue(final Class<?> type) {
                    return new java.util.concurrent.ConcurrentHashMap<>();
                }
            };

    private CubismEditorAvailabilityPolicy() {}

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
        final LinkedHashSet<String> supported = new LinkedHashSet<>(REVIEWED_VERSIONS);
        for (CubismEditor declaration : declarations) {
            supported.retainAll(expand(declaration, apiId(method)));
        }
        return new Resolution(
                true, REVIEWED_VERSIONS.stream().filter(supported::contains).toList(), declarations);
    }

    static boolean restricts(final Class<?> type) {
        return hasTypeDeclaration(type, new LinkedHashSet<>()) || declaresAnnotatedMethod(type);
    }

    static List<String> reviewedVersions() {
        return REVIEWED_VERSIONS;
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
        if (!visited.add(type)) return;
        for (Class<?> parent : type.getInterfaces()) {
            collectDeclarations(parent, method, declarations, visited);
        }
        final CubismEditor direct = type.getAnnotation(CubismEditor.class);
        if (direct != null) declarations.add(direct);
        try {
            final CubismEditor methodDeclaration = type.getDeclaredMethod(method.getName(), method.getParameterTypes())
                    .getAnnotation(CubismEditor.class);
            if (methodDeclaration != null) declarations.add(methodDeclaration);
        } catch (NoSuchMethodException inherited) {
            // The interface-level constraint still applies to inherited methods.
        }
    }

    private static boolean declaresAnnotatedMethod(final Class<?> type) {
        for (Method method : type.getMethods()) {
            if (method.isAnnotationPresent(CubismEditor.class)) return true;
        }
        return false;
    }

    private static boolean hasTypeDeclaration(final Class<?> type, final Set<Class<?>> visited) {
        if (!visited.add(type)) return false;
        if (type.isAnnotationPresent(CubismEditor.class)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (hasTypeDeclaration(parent, visited)) return true;
        }
        return false;
    }

    private static Set<String> expand(final CubismEditor declaration, final String apiId) {
        final List<String> exact = List.of(declaration.value());
        final List<String> excluded = List.of(declaration.exclude());
        final String from = declaration.from();
        final String to = declaration.to();
        final boolean hasRange = !from.isEmpty() || !to.isEmpty();
        if ((!exact.isEmpty() && hasRange)
                || hasDuplicates(exact)
                || hasDuplicates(excluded)
                || exact.stream().anyMatch(version -> !isDeclaredOrReviewedVersion(version))
                || exact.stream().anyMatch(version -> !isExactVersion(version))
                || excluded.stream().anyMatch(version -> !isExactVersion(version))
                || (!from.isEmpty() && !isExactVersion(from))
                || (!to.isEmpty() && !isExactVersion(to))
                || (!from.isEmpty() && !to.isEmpty() && compareVersions(from, to) > 0)) {
            throw new IllegalStateException("Invalid @CubismEditor declaration on " + apiId);
        }
        final LinkedHashSet<String> expanded =
                exact.isEmpty() ? new LinkedHashSet<>(REVIEWED_VERSIONS) : new LinkedHashSet<>(exact);
        if (hasRange) {
            expanded.removeIf(version -> (!from.isEmpty() && compareVersions(version, from) < 0)
                    || (!to.isEmpty() && compareVersions(version, to) > 0));
        }
        expanded.removeAll(excluded);
        return Set.copyOf(expanded);
    }

    private static boolean hasDuplicates(final List<String> versions) {
        return new LinkedHashSet<>(versions).size() != versions.size();
    }

    private static boolean isDeclaredOrReviewedVersion(final String version) {
        return REVIEWED_VERSION_SET.contains(version);
    }

    private static boolean isExactVersion(final String version) {
        if (version == null || version.isEmpty()) return false;
        final String[] components = version.split("\\.", -1);
        if (components.length != 3) return false;
        for (String component : components) {
            if (component.isEmpty()) return false;
            for (int index = 0; index < component.length(); index++) {
                if (!Character.isDigit(component.charAt(index))) return false;
            }
        }
        return true;
    }

    private static int compareVersions(final String left, final String right) {
        final String[] leftComponents = left.split("\\.", -1);
        final String[] rightComponents = right.split("\\.", -1);
        for (int index = 0; index < 3; index++) {
            final int compared =
                    new BigInteger(leftComponents[index]).compareTo(new BigInteger(rightComponents[index]));
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static String apiId(final Method method) {
        final ArrayList<String> parameters = new ArrayList<>();
        for (Class<?> parameter : method.getParameterTypes()) parameters.add(parameter.getTypeName());
        return method.getDeclaringClass().getName() + "#" + method.getName() + "(" + String.join(",", parameters) + ")";
    }

    /**
     * One method's resolved availability.
     *
     * @param restricted whether any type/inherited/method {@code @CubismEditor} applies
     * @param supportedVersions the reviewed-version expansion for diagnostics
     * @param declarations the collected annotation declarations in hierarchy order
     */
    record Resolution(boolean restricted, List<String> supportedVersions, List<CubismEditor> declarations) {
        Resolution {
            supportedVersions = List.copyOf(supportedVersions);
            declarations = List.copyOf(declarations);
        }

        /**
         * Evaluates every collected declaration against the host's own declared
         * version. Type, inherited-type, and method declarations are intersected:
         * a single denial fails the method. Exact {@code value} lists match only
         * named versions; {@code from}/{@code to} compare numerically against any
         * well-formed {@code x.y.z} version, so open ranges can cover unreviewed
         * releases; {@code exclude} always removes the named real version.
         *
         * @param realVersion the version the host declared at probe time
         * @return {@code true} when the annotation set permits that version
         */
        boolean permits(final String realVersion) {
            if (!restricted || realVersion == null || !isExactVersion(realVersion)) {
                return !restricted;
            }
            for (final CubismEditor declaration : declarations) {
                if (!permits(declaration, realVersion)) {
                    return false;
                }
            }
            return true;
        }

        private static boolean permits(final CubismEditor declaration, final String realVersion) {
            if (List.of(declaration.exclude()).contains(realVersion)) {
                return false;
            }
            final List<String> exact = List.of(declaration.value());
            if (!exact.isEmpty()) {
                return exact.contains(realVersion);
            }
            final String from = declaration.from();
            final String to = declaration.to();
            if (!from.isEmpty() && compareVersions(realVersion, from) < 0) {
                return false;
            }
            return to.isEmpty() || compareVersions(realVersion, to) <= 0;
        }
    }
}
