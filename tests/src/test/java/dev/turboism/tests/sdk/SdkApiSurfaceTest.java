package dev.turboism.tests.sdk;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkApiSurfaceTest {

    private static final List<String> SCANNED_PACKAGES = List.of(
        "dev.turboism.sdk.action",
        "dev.turboism.sdk.event",
        "dev.turboism.sdk.menu",
        "dev.turboism.sdk.ui",
        "dev.turboism.sdk.ui.context",
        "dev.turboism.sdk.ui.toolbar",
        "dev.turboism.sdk.config",
        "dev.turboism.sdk.cubism",
        "dev.turboism.sdk.cubism.event",
        "dev.turboism.sdk.cubism.boundingbox",
        "dev.turboism.sdk.cubism.deformer",
        "dev.turboism.sdk.cubism.id",
        "dev.turboism.sdk.cubism.mesh",
        "dev.turboism.sdk.cubism.psd",
        "dev.turboism.sdk.cubism.service.query",
        "dev.turboism.sdk.cubism.service.read",
        "dev.turboism.sdk.cubism.transaction",
        "dev.turboism.sdk.cubism.write",
        "dev.turboism.sdk.plugin",
        "dev.turboism.sdk.theme"
    );
    private static final List<String> REQUIRED_M12_PACKAGES = List.of(
        "dev.turboism.sdk.cubism",
        "dev.turboism.sdk.cubism.event",
        "dev.turboism.sdk.cubism.boundingbox",
        "dev.turboism.sdk.cubism.deformer",
        "dev.turboism.sdk.cubism.id",
        "dev.turboism.sdk.cubism.mesh",
        "dev.turboism.sdk.cubism.psd",
        "dev.turboism.sdk.cubism.service.read",
        "dev.turboism.sdk.cubism.transaction",
        "dev.turboism.sdk.cubism.write",
        "dev.turboism.sdk.theme"
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
                assertGenericTypesAreAllowed(type.getName() + constructor, constructor.getGenericParameterTypes(), new HashSet<>());
            }
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class || method.isBridge() || method.isSynthetic()) {
                    continue;
                }
                assertMethodTypesAreAllowed(type, method);
            }
        }
    }

    @Test
    void publicSdkApiSurfaceGateCoversM12Packages() {
        assertTrue(SCANNED_PACKAGES.containsAll(REQUIRED_M12_PACKAGES),
            "SDK API surface gate must cover M12 Cubism, write, transaction, id, and theme packages");
    }

    private static void assertMethodTypesAreAllowed(Class<?> owner, Method method) {
        assertTypeIsAllowed(owner.getName() + "." + method.getName() + " return", method.getReturnType());
        assertTypesAreAllowed(owner.getName() + "." + method.getName() + " parameters", method.getParameterTypes());
        if (!ALLOWED_OBJECT_METHODS.contains(method.getName())) {
            assertGenericTypeIsAllowed(owner.getName() + "." + method.getName() + " generic return", method.getGenericReturnType(), new HashSet<>());
            assertGenericTypesAreAllowed(owner.getName() + "." + method.getName() + " generic parameters", method.getGenericParameterTypes(), new HashSet<>());
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

    private static void assertGenericTypesAreAllowed(String source, Type[] types, Set<Type> seen) {
        for (Type type : types) {
            assertGenericTypeIsAllowed(source, type, seen);
        }
    }

    private static void assertGenericTypeIsAllowed(String source, Type type, Set<Type> seen) {
        if (!seen.add(type)) {
            return;
        }
        if (type instanceof Class<?> rawClass) {
            assertTypeIsAllowed(source, rawClass);
            assertFalse(rawClass == Object.class, () -> source + " exposes raw Object");
        } else if (type instanceof ParameterizedType parameterizedType) {
            assertGenericTypeIsAllowed(source, parameterizedType.getRawType(), seen);
            assertGenericTypesAreAllowed(source, parameterizedType.getActualTypeArguments(), seen);
        } else if (type instanceof GenericArrayType genericArrayType) {
            assertGenericTypeIsAllowed(source, genericArrayType.getGenericComponentType(), seen);
        } else if (type instanceof TypeVariable<?> typeVariable) {
            assertGenericTypesAreAllowed(source, typeVariable.getBounds(), seen);
        } else if (type instanceof WildcardType wildcardType) {
            assertGenericTypesAreAllowed(source, wildcardType.getUpperBounds(), seen);
            assertGenericTypesAreAllowed(source, wildcardType.getLowerBounds(), seen);
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
