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

    private CubismEditorAvailabilityPolicy() {
    }

    static Resolution resolve(final Method method) {
        final List<CubismEditor> declarations = declarations(method);
        if (declarations.isEmpty()) {
            return new Resolution(false, List.of());
        }
        final LinkedHashSet<String> supported = new LinkedHashSet<>(REVIEWED_VERSIONS);
        for (CubismEditor declaration : declarations) {
            supported.retainAll(expand(declaration, apiId(method)));
        }
        return new Resolution(
            true,
            REVIEWED_VERSIONS.stream().filter(supported::contains).toList()
        );
    }

    static boolean restricts(final Class<?> type) {
        return hasTypeDeclaration(type, new LinkedHashSet<>()) || declaresAnnotatedMethod(type);
    }

    static List<String> reviewedVersions() {
        return REVIEWED_VERSIONS;
    }

    private static List<CubismEditor> declarations(final Method method) {
        final ArrayList<CubismEditor> declarations = new ArrayList<>();
        collectTypeDeclarations(method.getDeclaringClass(), declarations, new LinkedHashSet<>());
        final CubismEditor methodDeclaration = method.getAnnotation(CubismEditor.class);
        if (methodDeclaration != null) {
            declarations.add(methodDeclaration);
        }
        return List.copyOf(declarations);
    }

    private static void collectTypeDeclarations(
        final Class<?> type,
        final List<CubismEditor> declarations,
        final Set<Class<?>> visited
    ) {
        if (!visited.add(type)) return;
        for (Class<?> parent : type.getInterfaces()) {
            collectTypeDeclarations(parent, declarations, visited);
        }
        final CubismEditor direct = type.getAnnotation(CubismEditor.class);
        if (direct != null) declarations.add(direct);
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
        final LinkedHashSet<String> expanded = exact.isEmpty()
            ? new LinkedHashSet<>(REVIEWED_VERSIONS)
            : new LinkedHashSet<>(exact);
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
            final int compared = new BigInteger(leftComponents[index]).compareTo(
                new BigInteger(rightComponents[index])
            );
            if (compared != 0) return compared;
        }
        return 0;
    }

    private static String apiId(final Method method) {
        final ArrayList<String> parameters = new ArrayList<>();
        for (Class<?> parameter : method.getParameterTypes()) parameters.add(parameter.getTypeName());
        return method.getDeclaringClass().getName() + "#" + method.getName()
            + "(" + String.join(",", parameters) + ")";
    }

    record Resolution(boolean restricted, List<String> supportedVersions) {
        Resolution {
            supportedVersions = List.copyOf(supportedVersions);
        }
    }
}
