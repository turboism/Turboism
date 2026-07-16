package dev.turboism.tests.sdk;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.ToolProvider;
import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SdkApiSurfaceTest {

    @TempDir
    Path temporary;

    private static final Set<String> ALLOWED_OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

    @Test
    void publicSdkApiSurfaceDoesNotExposeHostUiReflectionOrRawObjectEscapeHatches() throws Exception {
        for (Class<?> type : publicSdkClasses()) {
            assertPublicApiTypesAreAllowed(type);
        }
    }

    @Test
    void compiledGateAutomaticallyDiscoversFutureSdkPackagesAndRejectsForbiddenTypes()
        throws Exception {
        Path source = temporary.resolve("src/dev/turboism/sdk/future/ForbiddenApi.java");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
            package dev.turboism.sdk.future;
            public interface ForbiddenApi {
                java.awt.Color leakedColor();
            }
            """);
        int result = ToolProvider.getSystemJavaCompiler().run(
            null,
            null,
            null,
            "-d",
            classes.toString(),
            source.toString()
        );
        assertTrue(result == 0, "compiled SDK fixture must compile");

        try (URLClassLoader fixtureLoader = new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            List<Class<?>> discovered = publicSdkClasses(classes, fixtureLoader);

            assertTrue(
                discovered.stream().anyMatch(type -> type.getName().equals(
                    "dev.turboism.sdk.future.ForbiddenApi"
                )),
                "compiled gate must automatically discover every dev.turboism.sdk package"
            );
            AssertionError error = assertThrows(
                AssertionError.class,
                () -> discovered.forEach(SdkApiSurfaceTest::assertPublicApiTypesAreAllowed)
            );
            assertTrue(
                error.getMessage().contains("java.awt.Color"),
                () -> "forbidden fixture failure must identify java.awt.Color but was: " + error.getMessage()
            );
        }
    }

    @Test
    void compiledGateRejectsInheritedPublicRawObjectEscapeHatches() throws Exception {
        Path parentSource = temporary.resolve("src/fixture/host/RawObjectParent.java");
        Path sdkSource = temporary.resolve("src/dev/turboism/sdk/future/InheritedRawObjectApi.java");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(parentSource.getParent());
        Files.createDirectories(sdkSource.getParent());
        Files.createDirectories(classes);
        Files.writeString(parentSource, """
            package fixture.host;
            public interface RawObjectParent {
                boolean contains(Object value);
                boolean remove(Object value);
            }
            """);
        Files.writeString(sdkSource, """
            package dev.turboism.sdk.future;
            public interface InheritedRawObjectApi extends fixture.host.RawObjectParent {
            }
            """);
        int result = ToolProvider.getSystemJavaCompiler().run(
            null,
            null,
            null,
            "-d",
            classes.toString(),
            parentSource.toString(),
            sdkSource.toString()
        );
        assertTrue(result == 0, "inherited raw-Object SDK fixture must compile");

        try (URLClassLoader fixtureLoader = new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            List<Class<?>> discovered = publicSdkClasses(classes, fixtureLoader);

            assertThrows(
                AssertionError.class,
                () -> discovered.forEach(SdkApiSurfaceTest::assertPublicApiTypesAreAllowed)
            );
        }
    }

    @Test
    void compiledGateRejectsGenericToStringOverride() throws Exception {
        Path source = temporary.resolve("src/dev/turboism/sdk/future/GenericToStringApi.java");
        Path classes = temporary.resolve("classes");
        Files.createDirectories(source.getParent());
        Files.createDirectories(classes);
        Files.writeString(source, """
            package dev.turboism.sdk.future;
            public class GenericToStringApi<T extends String> {
                @Override
                public T toString() {
                    return null;
                }
            }
            """);
        int result = ToolProvider.getSystemJavaCompiler().run(
            null,
            null,
            null,
            "-d",
            classes.toString(),
            source.toString()
        );
        assertTrue(result == 0, "generic toString SDK fixture must compile");

        try (URLClassLoader fixtureLoader = new URLClassLoader(
            new java.net.URL[] {classes.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            List<Class<?>> discovered = publicSdkClasses(classes, fixtureLoader);

            assertThrows(
                AssertionError.class,
                () -> discovered.forEach(SdkApiSurfaceTest::assertPublicApiTypesAreAllowed)
            );
        }
    }

    @Test
    void genericTypeGateAllowsImplicitObjectTypeVariableBoundButRejectsExplicitObjectExposure() throws Exception {
        Method typeVariableMethod = GenericTypeFixtures.class.getDeclaredMethod("identity", Object.class);
        Method rawObjectMethod = GenericTypeFixtures.class.getDeclaredMethod("rawObject", Object.class);
        Method wildcardListMethod = GenericTypeFixtures.class.getDeclaredMethod("wildcardList", List.class);
        Method objectListMethod = GenericTypeFixtures.class.getDeclaredMethod("objectList", List.class);
        Method boundedWildcardListMethod = GenericTypeFixtures.class.getDeclaredMethod("boundedWildcardList", List.class);

        assertMethodTypesAreAllowed(GenericTypeFixtures.class, typeVariableMethod);
        assertMethodTypesAreAllowed(GenericTypeFixtures.class, wildcardListMethod);
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(GenericTypeFixtures.class, rawObjectMethod));
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(GenericTypeFixtures.class, objectListMethod));
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(GenericTypeFixtures.class, boundedWildcardListMethod));
    }

    @Test
    void objectMethodGateAllowsOnlyStandardObjectOverrideSignatures() throws Exception {
        Method equalsMethod = ObjectMethodFixtures.class.getDeclaredMethod("equals", Object.class);
        Method hashCodeMethod = ObjectMethodFixtures.class.getDeclaredMethod("hashCode");
        Method toStringMethod = ObjectMethodFixtures.class.getDeclaredMethod("toString");
        Method overloadedEquals = ObjectMethodFixtures.class.getDeclaredMethod("equals", Object.class, Object.class);
        Method overloadedHashCode = ObjectMethodFixtures.class.getDeclaredMethod("hashCode", Object.class);
        Method overloadedToString = ObjectMethodFixtures.class.getDeclaredMethod("toString", Object.class);

        assertMethodTypesAreAllowed(ObjectMethodFixtures.class, equalsMethod);
        assertMethodTypesAreAllowed(ObjectMethodFixtures.class, hashCodeMethod);
        assertMethodTypesAreAllowed(ObjectMethodFixtures.class, toStringMethod);
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(ObjectMethodFixtures.class, overloadedEquals));
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(ObjectMethodFixtures.class, overloadedHashCode));
        assertThrows(AssertionError.class,
            () -> assertMethodTypesAreAllowed(ObjectMethodFixtures.class, overloadedToString));
    }

    private static void assertPublicApiTypesAreAllowed(Class<?> type) {
        String owner = type.getName();
        Type genericSuperclass = type.getGenericSuperclass();
        if (genericSuperclass != Object.class) {
            assertGenericTypeIsAllowed(owner + " generic superclass", genericSuperclass, new HashSet<>());
        }
        assertGenericTypesAreAllowed(owner + " generic interfaces", type.getGenericInterfaces(), new HashSet<>());
        assertTypeVariablesAreAllowed(owner + " type parameters", type.getTypeParameters());

        for (Constructor<?> constructor : type.getDeclaredConstructors()) {
            if (isApiMember(constructor) && !constructor.isSynthetic()) {
                assertExecutableTypesAreAllowed(owner + constructor, constructor.getParameterTypes(),
                    constructor.getGenericParameterTypes(), constructor.getGenericExceptionTypes(),
                    constructor.getTypeParameters());
            }
        }
        Set<MethodSignature> inspectedMethods = new HashSet<>();
        for (Method method : type.getMethods()) {
            if (method.getDeclaringClass() != Object.class
                && !method.isBridge()
                && !method.isSynthetic()
                && inspectedMethods.add(MethodSignature.of(method))) {
                assertMethodTypesAreAllowed(type, method);
            }
        }
        assertProtectedSdkHierarchyMethodsAreAllowed(type, type, inspectedMethods, new HashSet<>());
        for (Field field : type.getDeclaredFields()) {
            if (isApiMember(field) && !field.isSynthetic()) {
                assertTypeIsAllowed(owner + "." + field.getName(), field.getType());
                assertGenericTypeIsAllowed(owner + "." + field.getName() + " generic type",
                    field.getGenericType(), new HashSet<>());
            }
        }
    }

    private static boolean isApiMember(Member member) {
        return Modifier.isPublic(member.getModifiers()) || Modifier.isProtected(member.getModifiers());
    }

    private static void assertProtectedSdkHierarchyMethodsAreAllowed(
        Class<?> owner,
        Class<?> hierarchyType,
        Set<MethodSignature> inspectedMethods,
        Set<Class<?>> inspectedTypes
    ) {
        if (hierarchyType == null
            || !isSdkClass(hierarchyType.getName())
            || !inspectedTypes.add(hierarchyType)) {
            return;
        }
        for (Method method : hierarchyType.getDeclaredMethods()) {
            if (Modifier.isProtected(method.getModifiers())
                && !method.isBridge()
                && !method.isSynthetic()
                && inspectedMethods.add(MethodSignature.of(method))) {
                assertMethodTypesAreAllowed(owner, method);
            }
        }
        for (Class<?> interfaceType : hierarchyType.getInterfaces()) {
            assertProtectedSdkHierarchyMethodsAreAllowed(
                owner,
                interfaceType,
                inspectedMethods,
                inspectedTypes
            );
        }
        assertProtectedSdkHierarchyMethodsAreAllowed(
            owner,
            hierarchyType.getSuperclass(),
            inspectedMethods,
            inspectedTypes
        );
    }

    private static void assertMethodTypesAreAllowed(Class<?> owner, Method method) {
        String source = owner.getName() + "." + method.getName();
        boolean allowedObjectMethod = isAllowedObjectMethod(method);
        if (ALLOWED_OBJECT_METHODS.contains(method.getName())) {
            assertTrue(
                allowedObjectMethod,
                () -> source + " has non-standard Object method signature"
            );
        }
        assertTypeIsAllowed(source + " return", method.getReturnType());
        if (allowedObjectMethod) {
            assertTypesAreAllowed(source + " parameters", method.getParameterTypes());
            assertGenericTypesAreAllowed(source + " throws", method.getGenericExceptionTypes(), new HashSet<>());
            assertTypeVariablesAreAllowed(source + " type parameters", method.getTypeParameters());
        } else {
            assertExecutableTypesAreAllowed(source, method.getParameterTypes(), method.getGenericParameterTypes(),
                method.getGenericExceptionTypes(), method.getTypeParameters());
        }
        assertGenericTypeIsAllowed(source + " generic return", method.getGenericReturnType(), new HashSet<>());
        if (!allowedObjectMethod) {
            assertFalse(
                method.getReturnType() == Object.class && method.getGenericReturnType() == Object.class,
                () -> source + " returns raw Object"
            );
        }
    }

    private static boolean isAllowedObjectMethod(Method method) {
        if (!ALLOWED_OBJECT_METHODS.contains(method.getName()) || Modifier.isStatic(method.getModifiers())
            || method.getTypeParameters().length != 0) {
            return false;
        }
        return switch (method.getName()) {
            case "equals" -> method.getReturnType() == boolean.class
                && method.getParameterCount() == 1
                && method.getParameterTypes()[0] == Object.class
                && method.getGenericParameterTypes()[0] == Object.class;
            case "hashCode" -> method.getReturnType() == int.class && method.getParameterCount() == 0;
            case "toString" -> method.getReturnType() == String.class
                && method.getGenericReturnType() == String.class
                && method.getParameterCount() == 0;
            default -> false;
        };
    }

    private record MethodSignature(String name, List<Class<?>> parameterTypes) {
        private static MethodSignature of(Method method) {
            return new MethodSignature(method.getName(), List.of(method.getParameterTypes()));
        }
    }

    private static void assertExecutableTypesAreAllowed(
        String source,
        Class<?>[] parameterTypes,
        Type[] genericParameterTypes,
        Type[] genericExceptionTypes,
        TypeVariable<?>[] typeParameters
    ) {
        assertTypesAreAllowed(source + " parameters", parameterTypes);
        assertGenericTypesAreAllowed(source + " generic parameters", genericParameterTypes, new HashSet<>());
        assertGenericTypesAreAllowed(source + " throws", genericExceptionTypes, new HashSet<>());
        assertTypeVariablesAreAllowed(source + " type parameters", typeParameters);
        for (int index = 0; index < parameterTypes.length; index++) {
            int parameterIndex = index;
            assertFalse(
                parameterTypes[index] == Object.class && genericParameterTypes[index] == Object.class,
                () -> source + " accepts raw Object at parameter " + parameterIndex
            );
        }
    }

    private static void assertTypeVariablesAreAllowed(String source, TypeVariable<?>[] typeVariables) {
        for (TypeVariable<?> typeVariable : typeVariables) {
            Type[] bounds = typeVariable.getBounds();
            if (!(bounds.length == 1 && bounds[0] == Object.class)) {
                assertGenericTypesAreAllowed(source, bounds, new HashSet<>());
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
        if (type == null || !seen.add(type)) {
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
            Type[] bounds = typeVariable.getBounds();
            if (!(bounds.length == 1 && bounds[0] == Object.class)) {
                assertGenericTypesAreAllowed(source, bounds, seen);
            }
        } else if (type instanceof WildcardType wildcardType) {
            Type[] upperBounds = wildcardType.getUpperBounds();
            Type[] lowerBounds = wildcardType.getLowerBounds();
            if (!(lowerBounds.length == 0 && upperBounds.length == 1 && upperBounds[0] == Object.class)) {
                assertGenericTypesAreAllowed(source, upperBounds, seen);
                assertGenericTypesAreAllowed(source, lowerBounds, seen);
            }
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
        return publicSdkClasses(
            Path.of(System.getProperty("sdkBuildDir")).resolve("classes/java/main"),
            SdkApiSurfaceTest.class.getClassLoader()
        );
    }

    private static List<Class<?>> publicSdkClasses(Path classesRoot, ClassLoader classLoader)
        throws IOException, ClassNotFoundException {
        try (Stream<Path> stream = Files.walk(classesRoot)) {
            return stream
                .filter(path -> path.toString().endsWith(".class"))
                .map(path -> className(classesRoot, path))
                .filter(SdkApiSurfaceTest::isSdkClass)
                .<Class<?>>map(className -> loadClass(className, classLoader))
                .filter(type -> !type.isSynthetic())
                .filter(type -> Modifier.isPublic(type.getModifiers()) || Modifier.isProtected(type.getModifiers()))
                .toList();
        }
    }

    private static String className(Path classesRoot, Path classFile) {
        String relative = classesRoot.relativize(classFile).toString();
        return relative.substring(0, relative.length() - ".class".length())
            .replace(classFile.getFileSystem().getSeparator(), ".");
    }

    private static boolean isSdkClass(String className) {
        return className.startsWith("dev.turboism.sdk.")
            && !className.endsWith("package-info")
            && !className.equals("module-info");
    }

    private static Class<?> loadClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    private static final class GenericTypeFixtures {
        private static <T> T identity(T value) {
            return value;
        }

        private static Object rawObject(Object value) {
            return value;
        }

        private static List<?> wildcardList(List<?> value) {
            return value;
        }

        private static List<Object> objectList(List<Object> value) {
            return value;
        }

        private static List<? extends Type> boundedWildcardList(List<? extends Type> value) {
            return value;
        }
    }

    private static final class ObjectMethodFixtures {
        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return 0;
        }

        @Override
        public String toString() {
            return "fixture";
        }

        private boolean equals(Object first, Object second) {
            return first == second;
        }

        private int hashCode(Object value) {
            return value.hashCode();
        }

        private String toString(Object value) {
            return String.valueOf(value);
        }
    }
}
