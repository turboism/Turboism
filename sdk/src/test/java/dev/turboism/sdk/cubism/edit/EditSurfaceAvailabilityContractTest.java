package dev.turboism.sdk.cubism.edit;

import dev.turboism.sdk.CubismEditor;
import dev.turboism.sdk.cubism.CubismFacade;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Availability-pinning contract for the external-application editing surface.
 *
 * <p>{@code @CubismEditor} treats an undeclared type or method as available on every reviewed
 * editor version. The whole {@code dev.turboism.sdk.cubism.edit} package is admitted only on the
 * reviewed 5.2/5.3 contract range, so every public top-level type must carry that bound;
 * the 5.4 native protocol must not silently inherit this integration.
 * Nested types are covered transitively: they must either carry the bound themselves or
 * be enclosed by — or implement — a pinned type.</p>
 */
final class EditSurfaceAvailabilityContractTest {

    @Test
    void everyTopLevelEditTypeAllowsCompatibleBuildsWithinTheBackportedProtocolRange() throws Exception {
        final List<Class<?>> topLevel = topLevelEditTypes();
        assertTrue(topLevel.size() > 30, "edit package type inventory shrank unexpectedly");

        for (final Class<?> type : topLevel) {
            final CubismEditor pin = type.getAnnotation(CubismEditor.class);
            assertNotNull(
                pin,
                type.getName() + " is missing @CubismEditor and would leak onto future versions");
            assertBound(pin);
        }
    }

    @Test
    void nestedEditTypesAreCoveredByAPinnedDeclaration() throws Exception {
        for (final Class<?> type : editPackageTypes()) {
            assertTrue(
                effectivelyPinned(type, new HashSet<>()),
                type.getName() + " escapes availability pinning");
        }
    }

    @Test
    void facadeEntryPointAndOperationFamiliesKeepTheSameNativeProtocolBoundary() throws Exception {
        final Method edit = CubismFacade.class.getMethod("edit");
        assertBound(edit.getAnnotation(CubismEditor.class));

        assertBound(EditSessionService.class.getAnnotation(CubismEditor.class));
        assertBound(EditSession.class.getAnnotation(CubismEditor.class));
        for (final Class<?> family : List.of(
            ParameterKeyOps.class,
            ParameterStructureOps.class,
            SelectionOps.class,
            PartObjectOps.class,
            DeformerOps.class
        )) {
            assertBound(family.getAnnotation(CubismEditor.class));
        }
    }

    private static void assertBound(final CubismEditor annotation) {
        assertNotNull(annotation);
        assertArrayEquals(new String[0], annotation.value());
        assertArrayEquals(new String[0], annotation.exclude());
        assertEquals("5.2.03", annotation.from());
        assertEquals("5.3.99", annotation.to());
    }

    private static boolean effectivelyPinned(final Class<?> type, final Set<Class<?>> visited) {
        if (!visited.add(type)) {
            return true;
        }
        if (type.isAnnotationPresent(CubismEditor.class)) {
            return true;
        }
        final Class<?> enclosing = type.getEnclosingClass();
        if (enclosing != null && effectivelyPinned(enclosing, visited)) {
            return true;
        }
        for (final Class<?> parent : type.getInterfaces()) {
            if (effectivelyPinned(parent, visited)) {
                return true;
            }
        }
        final Class<?> superclass = type.getSuperclass();
        return superclass != null && effectivelyPinned(superclass, visited);
    }

    private static List<Class<?>> topLevelEditTypes() throws Exception {
        final List<Class<?>> types = new ArrayList<>();
        for (final Class<?> type : editPackageTypes()) {
            if (type.getEnclosingClass() == null && !type.getSimpleName().equals("package-info")) {
                types.add(type);
            }
        }
        return types;
    }

    private static List<Class<?>> editPackageTypes() throws Exception {
        final ClassLoader loader = EditSession.class.getClassLoader();
        final Enumeration<URL> roots = loader.getResources("dev/turboism/sdk/cubism/edit");
        final List<Class<?>> types = new ArrayList<>();
        while (roots.hasMoreElements()) {
            final URL root = roots.nextElement();
            // Only the production classes directory counts: test classes share this package
            // and must not be mistaken for surface types.
            if (!"file".equals(root.getProtocol()) || !root.getPath().contains("/main/")) {
                continue;
            }
            final File directory = new File(root.toURI());
            final File[] files = directory.listFiles((dir, name) -> name.endsWith(".class"));
            assertNotNull(files);
            for (final File file : files) {
                final String name = file.getName().replace(".class", "");
                if (name.endsWith("-info")) {
                    continue;
                }
                types.add(Class.forName("dev.turboism.sdk.cubism.edit." + name));
            }
        }
        return types;
    }
}
