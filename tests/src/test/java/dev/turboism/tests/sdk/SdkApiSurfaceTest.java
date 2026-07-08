package dev.turboism.tests.sdk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;

class SdkApiSurfaceTest {

    private static final List<String> SCANNED_PACKAGES = List.of(
        "dev.turboism.sdk.action",
        "dev.turboism.sdk.event",
        "dev.turboism.sdk.menu",
        "dev.turboism.sdk.ui",
        "dev.turboism.sdk.ui.context",
        "dev.turboism.sdk.ui.toolbar",
        "dev.turboism.sdk.config",
        "dev.turboism.sdk.plugin"
    );

    private static final Set<String> ALLOWED_OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

    @Test
    void publicSdkApiSurfaceDoesNotExposeHostUiReflectionOrRawObjectEscapeHatches() throws Exception {
        // Given
        List<Class<?>> classes = publicSdkClasses();

        // When / Then
        for (Class<?> type : classes) {
            for (Constructor<?> constructor : type.getConstructors()) {
                assertTypesAreAllowed(type.getName() + constructor, constructor.getParameterTypes());
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                assertMethodTypesAreAllowed(type, method);
            }
        }
    }

    private static void assertMethodTypesAreAllowed(Class<?> owner, Method method) {
        assertTypeIsAllowed(owner.getName() + "." + method.getName() + " return", method.getReturnType());
        assertTypesAreAllowed(owner.getName() + "." + method.getName() + " parameters", method.getParameterTypes());
        if (!ALLOWED_OBJECT_METHODS.contains(method.getName())) {
            assertFalse(
                method.getReturnType() == Object.class,
                () -> owner.getName() + "." + method.getName() + " returns raw Object"
            );
            for (Class<?> parameterType : method.getParameterTypes()) {
                assertFalse(
                    parameterType == Object.class,
                    () -> owner.getName() + "." + method.getName() + " accepts raw Object"
                );
            }
        }
    }

    private static void assertTypesAreAllowed(String source, Class<?>[] types) {
        for (Class<?> type : types) {
            assertTypeIsAllowed(source, type);
        }
    }

    private static void assertTypeIsAllowed(String source, Class<?> type) {
        Class<?> normalized = normalize(type);
        String name = normalized.getName();
        assertFalse(name.startsWith("java.awt."), () -> source + " exposes " + name);
        assertFalse(name.startsWith("javax.swing."), () -> source + " exposes " + name);
        assertFalse(name.startsWith("com.live2d."), () -> source + " exposes " + name);
        assertFalse(name.startsWith("java.lang.reflect."), () -> source + " exposes " + name);
    }

    private static Class<?> normalize(Class<?> type) {
        Class<?> current = type;
        while (current.isArray()) {
            current = current.getComponentType();
        }
        return current;
    }

    private static List<Class<?>> publicSdkClasses() throws IOException, ClassNotFoundException {
        Path classesRoot = Path.of(System.getProperty("sdkBuildDir")).resolve("classes/java/main");
        try (Stream<Path> stream = Files.walk(classesRoot)) {
            return stream
                .filter(path -> path.toString().endsWith(".class"))
                .map(path -> className(classesRoot, path))
                .filter(SdkApiSurfaceTest::isScannedPackageClass)
                .<Class<?>>map(SdkApiSurfaceTest::loadClass)
                .filter(type -> Modifier.isPublic(type.getModifiers()))
                .toList();
        }
    }

    private static String className(Path classesRoot, Path classFile) {
        String relative = classesRoot.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length()).replace('/', '.');
    }

    private static boolean isScannedPackageClass(String className) {
        return SCANNED_PACKAGES.stream().anyMatch(packageName ->
            className.startsWith(packageName + ".") && !className.substring(packageName.length() + 1).contains(".")
        );
    }

    private static Class<?> loadClass(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }
}
