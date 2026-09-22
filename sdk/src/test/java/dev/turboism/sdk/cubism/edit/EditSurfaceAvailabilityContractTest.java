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
 * three exact supported versions, so every public top-level type must carry the explicit pin;
 * otherwise a future reviewed version (for example a 5.4 line) would silently inherit the
 * surface. Nested types are covered transitively: they must either carry the pin themselves or
 * be enclosed by — or implement — a pinned type.</p>
 */
final class EditSurfaceAvailabilityContractTest {

    private static final String[] SUPPORTED = {"5.2.03", "5.3.02", "5.3.03"};

    @Test
    void everyTopLevelEditTypeIsPinnedToTheExactSupportedVersions() throws Exception {
        final List<Class<?>> topLevel = topLevelEditTypes();
        assertTrue(topLevel.size() > 30, "edit package type inventory shrank unexpectedly");

        for (final Class<?> type : topLevel) {
            final CubismEditor pin = type.getAnnotation(CubismEditor.class);
            assertNotNull(
                pin,
                type.getName() + " is missing @CubismEditor and would leak onto future versions");
            assertArrayEquals(SUPPORTED, pin.value(), type.getName());
            assertArrayEquals(new String[0], pin.exclude(), type.getName());
            assertEquals("", pin.from(), type.getName());
            assertEquals("", pin.to(), type.getName());
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
    void facadeEntryPointAndOperationFamiliesStayOnTheExactVersions() throws Exception {
        final Method edit = CubismFacade.class.getMethod("edit");
        assertArrayEquals(SUPPORTED, edit.getAnnotation(CubismEditor.class).value());

        assertArrayEquals(
            SUPPORTED, EditSessionService.class.getAnnotation(CubismEditor.class).value());
        assertArrayEquals(
            SUPPORTED, EditSession.class.getAnnotation(CubismEditor.class).value());
        for (final Class<?> family : List.of(
            ParameterKeyOps.class,
            ParameterStructureOps.class,
            SelectionOps.class,
            PartObjectOps.class,
            DeformerOps.class
        )) {
            assertArrayEquals(
                SUPPORTED,
                family.getAnnotation(CubismEditor.class).value(),
                family.getSimpleName());
        }
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
