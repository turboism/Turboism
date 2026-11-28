package dev.turboism.adapter.cubism.optimization.modelopen;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Provides cached {@link MethodHandle} dispatch for Cubism XML property assignment and
 * reflection-based object instantiation.
 *
 * <p>All caches are anchored to host {@link Class} instances via {@link ClassValue} so unloaded
 * class loaders never leak. Unrecognized or incompatible targets return {@code false} (or delegate
 * to {@link Class#newInstance()}) to preserve exact host semantics.</p>
 */
public final class DeserializeReflectFastBridge {

    private static final String REFLECT_JVM_MAPPING_CLASS = "kotlin.reflect.jvm.ReflectJvmMapping";
    private static final String GET_JAVA_SETTER_NAME = "getJavaSetter";
    private static final String GET_JAVA_FIELD_NAME = "getJavaField";
    private static final int SINGLE_PARAM_COUNT = 1;

    private static final MethodType SETTER_TYPE = MethodType.methodType(void.class, Object.class, Object.class);
    private static final MethodType NO_ARG_CTOR_TYPE = MethodType.methodType(Object.class);

    private static final ClassValue<ConcurrentHashMap<Object, PropertyAccessor>> PROPERTY_CACHE = new ClassValue<>() {
        @Override
        protected ConcurrentHashMap<Object, PropertyAccessor> computeValue(final Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    private static final ClassValue<KotlinReflectMapping> KOTLIN_MAPPING_CACHE = new ClassValue<>() {
        @Override
        protected KotlinReflectMapping computeValue(final Class<?> propertyClass) {
            return resolveKotlinMapping(propertyClass.getClassLoader());
        }
    };

    private static final ClassValue<ConstructorEntry> CONSTRUCTOR_CACHE = new ClassValue<>() {
        @Override
        protected ConstructorEntry computeValue(final Class<?> type) {
            return resolveNoArgConstructor(type);
        }
    };

    private DeserializeReflectFastBridge() {}

    /**
     * Assigns {@code value} to {@code target} through a cached setter or field handle for a
     * Kotlin {@code KMutableProperty1}.
     *
     * @param propertySet owning {@code PropertySet} instance used as cache key
     * @param property Kotlin {@code KMutableProperty1} descriptor
     * @param backingField optional JVM backing field from {@code PropertySet}
     * @param target receiver instance being populated
     * @param value deserialized property value
     * @return {@code true} when assigned, or {@code false} to run the original Kotlin fallback
     * @throws Throwable any exception thrown by the target setter itself
     */
    public static boolean trySetMutableProperty(
            final Object propertySet,
            final Object property,
            final Field backingField,
            final Object target,
            final Object value)
            throws Throwable {
        if (property == null || target == null) {
            return false;
        }
        final Object cacheOwner = propertySet != null ? propertySet : property;
        final ConcurrentHashMap<Object, PropertyAccessor> map = PROPERTY_CACHE.get(cacheOwner.getClass());
        PropertyAccessor accessor = map.get(cacheOwner);
        if (accessor == null) {
            accessor = resolveMutableAccessor(property, backingField);
            final PropertyAccessor existing = map.putIfAbsent(cacheOwner, accessor);
            if (existing != null) {
                accessor = existing;
            }
        }
        return accessor.tryAssign(target, value);
    }

    /**
     * Assigns {@code value} to {@code target} through a cached field handle or pre-opened
     * {@link Field} for read-only ({@code val}) or field-backed properties.
     *
     * @param propertySet owning {@code PropertySet} instance used as cache key
     * @param backingField JVM field to assign
     * @param target receiver instance being populated
     * @param value deserialized field value
     * @return {@code true} when assigned, or {@code false} to run the original reflective fallback
     * @throws Throwable any exception thrown by the underlying field write
     */
    public static boolean trySetBackingField(
            final Object propertySet, final Field backingField, final Object target, final Object value)
            throws Throwable {
        if (backingField == null || target == null) {
            return false;
        }
        final Object cacheOwner = propertySet != null ? propertySet : backingField.getDeclaringClass();
        final Object cacheKey = propertySet != null ? propertySet : backingField;
        final ConcurrentHashMap<Object, PropertyAccessor> map = PROPERTY_CACHE.get(cacheOwner.getClass());
        PropertyAccessor accessor = map.get(cacheKey);
        if (accessor == null) {
            accessor = resolveFieldAccessor(backingField);
            final PropertyAccessor existing = map.putIfAbsent(cacheKey, accessor);
            if (existing != null) {
                accessor = existing;
            }
        }
        return accessor.tryAssign(target, value);
    }

    /**
     * Instantiates {@code type} via a cached no-arg constructor {@link MethodHandle}.
     *
     * @param type concrete class to instantiate
     * @return newly created instance
     * @throws Throwable any exception thrown by the constructor or {@link Class#newInstance()}
     */
    @SuppressWarnings("deprecation")
    public static Object newInstance(final Class<?> type) throws Throwable {
        if (type == null) {
            throw new NullPointerException();
        }
        final ConstructorEntry entry = CONSTRUCTOR_CACHE.get(type);
        if (entry.handle != null) {
            return entry.handle.invokeExact();
        }
        return type.newInstance();
    }

    private static PropertyAccessor resolveMutableAccessor(final Object property, final Field backingField) {
        final KotlinReflectMapping mapping = KOTLIN_MAPPING_CACHE.get(property.getClass());
        if (!mapping.available) {
            return PropertyAccessor.FALLBACK;
        }
        final Method setterMethod = mapping.getJavaSetter(property);
        if (setterMethod != null) {
            return resolveSetterMethodAccessor(setterMethod);
        }
        final Field resolvedField = backingField != null ? backingField : mapping.getJavaField(property);
        if (resolvedField != null) {
            return resolveFieldAccessor(resolvedField);
        }
        return PropertyAccessor.FALLBACK;
    }

    private static PropertyAccessor resolveSetterMethodAccessor(final Method setterMethod) {
        if (Modifier.isStatic(setterMethod.getModifiers())
                || setterMethod.getParameterCount() != SINGLE_PARAM_COUNT
                || setterMethod.getReturnType() != void.class) {
            return PropertyAccessor.FALLBACK;
        }
        final MethodHandle handle = unreflectMethod(setterMethod);
        if (handle == null) {
            return PropertyAccessor.FALLBACK;
        }
        return PropertyAccessor.forHandle(
                handle, setterMethod.getDeclaringClass(), setterMethod.getParameterTypes()[0]);
    }

    private static PropertyAccessor resolveFieldAccessor(final Field field) {
        if (field == null || Modifier.isStatic(field.getModifiers())) {
            return PropertyAccessor.FALLBACK;
        }
        if (!Modifier.isFinal(field.getModifiers())) {
            final MethodHandle handle = unreflectFieldSetter(field);
            if (handle != null) {
                return PropertyAccessor.forHandle(handle, field.getDeclaringClass(), field.getType());
            }
        }
        try {
            final Field opened = ReflectAccess.open(field);
            return PropertyAccessor.forOpenedField(opened, field.getDeclaringClass(), field.getType());
        } catch (final RuntimeException refused) {
            return PropertyAccessor.FALLBACK;
        }
    }

    private static MethodHandle unreflectMethod(final Method method) {
        try {
            final MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflect(method).asType(SETTER_TYPE);
        } catch (final IllegalAccessException | RuntimeException ignored) {
            try {
                final Method opened = ReflectAccess.open(method);
                return MethodHandles.lookup().unreflect(opened).asType(SETTER_TYPE);
            } catch (final IllegalAccessException | RuntimeException refused) {
                return null;
            }
        }
    }

    private static MethodHandle unreflectFieldSetter(final Field field) {
        try {
            final MethodHandles.Lookup lookup =
                    MethodHandles.privateLookupIn(field.getDeclaringClass(), MethodHandles.lookup());
            return lookup.unreflectSetter(field).asType(SETTER_TYPE);
        } catch (final IllegalAccessException | RuntimeException ignored) {
            try {
                final Field opened = ReflectAccess.open(field);
                return MethodHandles.lookup().unreflectSetter(opened).asType(SETTER_TYPE);
            } catch (final IllegalAccessException | RuntimeException refused) {
                return null;
            }
        }
    }

    private static ConstructorEntry resolveNoArgConstructor(final Class<?> type) {
        final int typeMods = type.getModifiers();
        if (!Modifier.isPublic(typeMods)
                || Modifier.isAbstract(typeMods)
                || type.isInterface()
                || type.isArray()
                || type.isPrimitive()) {
            return ConstructorEntry.FALLBACK;
        }
        try {
            final Constructor<?> ctor = type.getDeclaredConstructor();
            if (!Modifier.isPublic(ctor.getModifiers())) {
                return ConstructorEntry.FALLBACK;
            }
            MethodHandle raw;
            try {
                raw = MethodHandles.privateLookupIn(type, MethodHandles.lookup())
                        .unreflectConstructor(ctor);
            } catch (final IllegalAccessException refused) {
                raw = MethodHandles.publicLookup().unreflectConstructor(ctor);
            }
            return new ConstructorEntry(raw.asType(NO_ARG_CTOR_TYPE));
        } catch (final ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return ConstructorEntry.FALLBACK;
        }
    }

    private static KotlinReflectMapping resolveKotlinMapping(final ClassLoader loader) {
        try {
            final Class<?> mappingClass = Class.forName(REFLECT_JVM_MAPPING_CLASS, false, loader);
            Method getJavaSetter = null;
            Method getJavaField = null;
            for (final Method candidate : mappingClass.getMethods()) {
                if (!Modifier.isStatic(candidate.getModifiers())
                        || candidate.getParameterCount() != SINGLE_PARAM_COUNT) {
                    continue;
                }
                if (GET_JAVA_SETTER_NAME.equals(candidate.getName()) && candidate.getReturnType() == Method.class) {
                    getJavaSetter = candidate;
                } else if (GET_JAVA_FIELD_NAME.equals(candidate.getName())
                        && candidate.getReturnType() == Field.class) {
                    getJavaField = candidate;
                }
            }
            if (getJavaSetter == null) {
                return KotlinReflectMapping.UNAVAILABLE;
            }
            return new KotlinReflectMapping(true, getJavaSetter, getJavaField);
        } catch (final Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            return KotlinReflectMapping.UNAVAILABLE;
        }
    }

    private static Class<?> wrapperFor(final Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type == int.class) {
            return Integer.class;
        }
        if (type == float.class) {
            return Float.class;
        }
        if (type == boolean.class) {
            return Boolean.class;
        }
        if (type == double.class) {
            return Double.class;
        }
        if (type == long.class) {
            return Long.class;
        }
        if (type == byte.class) {
            return Byte.class;
        }
        if (type == short.class) {
            return Short.class;
        }
        if (type == char.class) {
            return Character.class;
        }
        return type;
    }

    private static final class ConstructorEntry {
        static final ConstructorEntry FALLBACK = new ConstructorEntry(null);

        final MethodHandle handle;

        ConstructorEntry(final MethodHandle handle) {
            this.handle = handle;
        }
    }

    private static final class KotlinReflectMapping {
        static final KotlinReflectMapping UNAVAILABLE = new KotlinReflectMapping(false, null, null);

        final boolean available;
        private final Method getJavaSetter;
        private final Method getJavaField;

        KotlinReflectMapping(final boolean available, final Method getJavaSetter, final Method getJavaField) {
            this.available = available;
            this.getJavaSetter = getJavaSetter;
            this.getJavaField = getJavaField;
        }

        Method getJavaSetter(final Object property) {
            if (getJavaSetter == null) {
                return null;
            }
            try {
                return (Method) getJavaSetter.invoke(null, property);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }

        Field getJavaField(final Object property) {
            if (getJavaField == null) {
                return null;
            }
            try {
                return (Field) getJavaField.invoke(null, property);
            } catch (final Throwable failure) {
                FatalErrors.rethrowIfFatal(failure);
                return null;
            }
        }
    }

    private static final class PropertyAccessor {
        static final PropertyAccessor FALLBACK = new PropertyAccessor(null, null, null, null, false);

        private final MethodHandle handle;
        private final Field openedField;
        private final Class<?> receiverType;
        private final Class<?> valueBoxType;
        private final boolean primitiveValue;

        private PropertyAccessor(
                final MethodHandle handle,
                final Field openedField,
                final Class<?> receiverType,
                final Class<?> valueBoxType,
                final boolean primitiveValue) {
            this.handle = handle;
            this.openedField = openedField;
            this.receiverType = receiverType;
            this.valueBoxType = valueBoxType;
            this.primitiveValue = primitiveValue;
        }

        static PropertyAccessor forHandle(
                final MethodHandle handle, final Class<?> receiverType, final Class<?> valueType) {
            return new PropertyAccessor(handle, null, receiverType, wrapperFor(valueType), valueType.isPrimitive());
        }

        static PropertyAccessor forOpenedField(
                final Field openedField, final Class<?> receiverType, final Class<?> valueType) {
            return new PropertyAccessor(
                    null, openedField, receiverType, wrapperFor(valueType), valueType.isPrimitive());
        }

        boolean tryAssign(final Object target, final Object value) throws Throwable {
            if (handle == null && openedField == null) {
                return false;
            }
            if (!isCompatible(target, value)) {
                return false;
            }
            if (handle != null) {
                handle.invokeExact(target, value);
                return true;
            }
            openedField.set(target, value);
            return true;
        }

        private boolean isCompatible(final Object target, final Object value) {
            if (!receiverType.isInstance(target)) {
                return false;
            }
            if (value == null) {
                return !primitiveValue;
            }
            return valueBoxType.isInstance(value);
        }
    }
}
