package dev.turboism.core.plugin.context;

import java.lang.reflect.Array;
import java.lang.reflect.GenericArrayType;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Pure internal traversal helper for SDK carrier types (Optional, List, Collection, Set, Map,
 * array, and CompletionStage).
 */
public final class SdkCarrierTraversal {

    /**
     * Strategy callback providing interface proxying policy for target SDK interfaces.
     */
    @FunctionalInterface
    public interface InterfaceWrapper {

        /**
         * Wraps one target value as a proxy implementing the declared SDK interface, or returns
         * the value unchanged if it should not be proxied under the caller's policy.
         *
         * @param value the candidate object to wrap
         * @param sdkInterface the declared SDK interface class
         * @return the proxied handle or the original value
         */
        Object wrapInterface(Object value, Class<?> sdkInterface);
    }

    // Interface shape and modifiers never change for a loaded class. Cache the fast-path
    // decision per raw return type so hot calls avoid repeated hierarchy and modifier checks.
    private static final ClassValue<Boolean> RETURN_WRAPPING_REQUIRED = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> returnType) {
            if (returnType == void.class || returnType.isPrimitive() || returnType.isEnum()) {
                return Boolean.FALSE;
            }
            // Arrays are defensively copied even when their component type is final.
            if (returnType.isArray()) {
                return Boolean.TRUE;
            }
            if (!Modifier.isFinal(returnType.getModifiers())) {
                return Boolean.TRUE;
            }
            // Optional is final but still a carrier; a final CompletionStage subclass is
            // traversed too because wrapValue checks that carrier by assignability.
            return returnType == Optional.class || CompletionStage.class.isAssignableFrom(returnType);
        }
    };

    // Generic signatures are immutable but expensive to resolve. Cache the resolved return
    // type per declaring class so repeated calls through one SDK proxy reuse the parse result.
    private static final ClassValue<ConcurrentMap<Method, Type>> GENERIC_RETURN_TYPES = new ClassValue<>() {
        @Override
        protected ConcurrentMap<Method, Type> computeValue(final Class<?> type) {
            return new ConcurrentHashMap<>();
        }
    };

    // Most SDK arguments are immutable leaves (ids, floats, strings, enums) that cannot be
    // proxies or carriers. Cache the classification per class so a hot call skips both the
    // unwrapper callback and the carrier instanceof chain. JDK immutable collections are final
    // but still carriers, so carrier assignability is checked before the final-class shortcut.
    private static final ClassValue<Boolean> LEAF_ARGUMENT = new ClassValue<>() {
        @Override
        protected Boolean computeValue(final Class<?> type) {
            if (type.isArray() || Proxy.isProxyClass(type) || type == Optional.class) {
                return Boolean.FALSE;
            }
            if (List.class.isAssignableFrom(type)
                    || Set.class.isAssignableFrom(type)
                    || Map.class.isAssignableFrom(type)) {
                return Boolean.FALSE;
            }
            return type == String.class
                    || isPrimitiveWrapper(type)
                    || Enum.class.isAssignableFrom(type)
                    || Modifier.isFinal(type.getModifiers());
        }
    };

    private SdkCarrierTraversal() {}

    /**
     * Wraps one reflected method result without resolving its generic signature for leaf values.
     *
     * <p>Methods returning {@code void}, primitives, enums, or final non-array values other than
     * {@link Optional} can never contain a wrappable SDK carrier. Their result is returned as-is
     * without calling {@link Method#getGenericReturnType()}, which reparses and clones the generic
     * type tree when a signature is present. Remaining results delegate to {@link #wrapValue} with
     * the cached generic return type.</p>
     *
     * @param method the reflected method that produced the value
     * @param result the method result
     * @param interfaceWrapper the callback governing interface proxying
     * @return the wrapped result, or the original value when no carrier traversal is required
     */
    public static Object wrapMethodReturnValue(
            final Method method, final Object result, final InterfaceWrapper interfaceWrapper) {
        if (result == null || !requiresReturnWrapping(method.getReturnType())) {
            return result;
        }
        return wrapValue(result, genericReturnType(method), interfaceWrapper);
    }

    /**
     * Returns whether a value declared as {@code returnType} can need carrier traversal or
     * interface proxying. Final classes cannot be interfaces and, apart from {@link Optional}
     * and {@link CompletionStage} subtypes, are not traversed by {@link #wrapValue}.
     *
     * @param returnType the raw declared return type
     * @return {@code true} when the generic return type must be inspected
     */
    public static boolean requiresReturnWrapping(final Class<?> returnType) {
        return RETURN_WRAPPING_REQUIRED.get(returnType);
    }

    private static Type genericReturnType(final Method method) {
        return GENERIC_RETURN_TYPES
                .get(method.getDeclaringClass())
                .computeIfAbsent(method, Method::getGenericReturnType);
    }

    /**
     * Recursively traverses SDK carrier structures (Optional, List, Collection, Set, Map, array,
     * and CompletionStage) to wrap contained SDK interfaces using the caller's proxy strategy.
     *
     * <p>When elements remain unchanged, collections and Optionals preserve their original identity
     * to honor immutable snapshots; changed collections return unmodifiable wrappers preserving
     * element order and nulls. Arrays are always defensively copied on output.</p>
     *
     * @param value the raw or wrapped carrier value
     * @param declaredType the declared generic type of the value
     * @param interfaceWrapper the callback governing interface proxying
     * @return the wrapped carrier, or the original value if unchanged
     */
    public static Object wrapValue(
            final Object value, final Type declaredType, final InterfaceWrapper interfaceWrapper) {
        if (value == null) {
            return null;
        }
        if (declaredType instanceof Class<?> declaredClass) {
            if (declaredClass.isInterface()) {
                return interfaceWrapper.wrapInterface(value, declaredClass);
            }
            if (declaredClass.isArray()) {
                return mapArray(
                        value,
                        declaredClass.getComponentType(),
                        declaredClass.getComponentType(),
                        (item, type) -> wrapValue(item, type, interfaceWrapper));
            }
            return value;
        }
        if (declaredType instanceof GenericArrayType arrayType) {
            final Type component = arrayType.getGenericComponentType();
            return mapArray(
                    value, rawClass(component), component, (item, type) -> wrapValue(item, type, interfaceWrapper));
        }
        if (!(declaredType instanceof ParameterizedType parameterized)) {
            return value;
        }
        final Class<?> raw = rawClass(parameterized.getRawType());
        final Type[] types = parameterized.getActualTypeArguments();
        if (raw == Optional.class) {
            return wrapOptional(value, item -> wrapValue(item, types[0], interfaceWrapper));
        }
        if (raw == List.class || raw == Collection.class) {
            return wrapCollection(value, types[0], (item, type) -> wrapValue(item, type, interfaceWrapper));
        }
        if (raw == Set.class) {
            return wrapSet(value, types[0], (item, type) -> wrapValue(item, type, interfaceWrapper));
        }
        if (raw == Map.class) {
            return wrapMap(value, types[0], types[1], (item, type) -> wrapValue(item, type, interfaceWrapper));
        }
        if (CompletionStage.class.isAssignableFrom(raw)) {
            return ((CompletionStage<?>) value).thenApply(item -> wrapValue(item, types[0], interfaceWrapper));
        }
        if (raw.isInterface()) {
            return interfaceWrapper.wrapInterface(value, raw);
        }
        return value;
    }

    /**
     * Unwraps owned proxy handles inside argument values and nested carrier structures before
     * delegating invocation.
     *
     * <p>Only proxies recognized by {@code ownedProxyUnwrapper} are unwrapped to their underlying
     * delegate; unchanged carrier collections preserve their identity, and arrays are defensively
     * copied. The argument array is cloned lazily: when no element changes, the original array is
     * returned without allocating a replacement.</p>
     *
     * @param arguments the method argument array to unwrap
     * @param ownedProxyUnwrapper the callback unwrapping owned proxies to delegates
     * @return the unwrapped argument array, or the original array if empty, null, or unchanged
     */
    public static Object[] unwrapArguments(
            final Object[] arguments, final Function<Object, Object> ownedProxyUnwrapper) {
        if (arguments == null || arguments.length == 0) {
            return arguments;
        }
        Object[] unwrapped = null;
        for (int index = 0; index < arguments.length; index++) {
            final Object argument = arguments[index];
            final Object value = unwrapValue(argument, ownedProxyUnwrapper);
            if (value != argument) {
                if (unwrapped == null) {
                    unwrapped = arguments.clone();
                }
                unwrapped[index] = value;
            }
        }
        return unwrapped == null ? arguments : unwrapped;
    }

    private static Object unwrapValue(final Object value, final Function<Object, Object> ownedProxyUnwrapper) {
        if (value == null) {
            return null;
        }
        if (LEAF_ARGUMENT.get(value.getClass())) {
            return value;
        }
        final Object unwrapped = ownedProxyUnwrapper.apply(value);
        if (unwrapped != value) {
            return unwrapped;
        }
        if (value instanceof Optional<?> optional) {
            return wrapOptional(optional, item -> unwrapValue(item, ownedProxyUnwrapper));
        }
        if (value instanceof List<?> list) {
            return wrapCollection(list, null, (item, ignored) -> unwrapValue(item, ownedProxyUnwrapper));
        }
        if (value instanceof Set<?> set) {
            return wrapSet(set, null, (item, ignored) -> unwrapValue(item, ownedProxyUnwrapper));
        }
        if (value instanceof Map<?, ?> map) {
            return wrapMap(map, null, null, (item, ignored) -> unwrapValue(item, ownedProxyUnwrapper));
        }
        if (value.getClass().isArray()) {
            final Class<?> componentClass = value.getClass().getComponentType();
            return mapArray(value, componentClass, null, (item, ignored) -> unwrapValue(item, ownedProxyUnwrapper));
        }
        return value;
    }

    private static Object wrapOptional(final Object value, final Function<Object, Object> mapper) {
        final Optional<?> optional = (Optional<?>) value;
        if (optional.isEmpty()) {
            return optional;
        }
        final Object item = optional.get();
        final Object mapped = mapper.apply(item);
        return mapped == item ? optional : Optional.ofNullable(mapped);
    }

    private static Object wrapCollection(
            final Object value, final Type elementType, final BiFunction<Object, Type, Object> elementMapper) {
        final Collection<?> source = (Collection<?>) value;
        final Iterator<?> it = source.iterator();
        int index = 0;
        while (it.hasNext()) {
            final Object item = it.next();
            final Object mapped = elementMapper.apply(item, elementType);
            if (mapped != item) {
                final ArrayList<Object> result = new ArrayList<>(source.size());
                final Iterator<?> prefixIt = source.iterator();
                for (int p = 0; p < index; p++) {
                    result.add(prefixIt.next());
                }
                result.add(mapped);
                while (it.hasNext()) {
                    result.add(elementMapper.apply(it.next(), elementType));
                }
                return Collections.unmodifiableList(result);
            }
            index++;
        }
        return source;
    }

    private static Object wrapSet(
            final Object value, final Type elementType, final BiFunction<Object, Type, Object> elementMapper) {
        final Set<?> source = (Set<?>) value;
        final Iterator<?> it = source.iterator();
        int index = 0;
        while (it.hasNext()) {
            final Object item = it.next();
            final Object mapped = elementMapper.apply(item, elementType);
            if (mapped != item) {
                final LinkedHashSet<Object> result =
                        new LinkedHashSet<>(Math.max((int) (source.size() / .75f) + 1, 16));
                final Iterator<?> prefixIt = source.iterator();
                for (int p = 0; p < index; p++) {
                    result.add(prefixIt.next());
                }
                result.add(mapped);
                while (it.hasNext()) {
                    result.add(elementMapper.apply(it.next(), elementType));
                }
                return Collections.unmodifiableSet(result);
            }
            index++;
        }
        return source;
    }

    private static Object wrapMap(
            final Object value,
            final Type keyType,
            final Type valueType,
            final BiFunction<Object, Type, Object> elementMapper) {
        final Map<?, ?> source = (Map<?, ?>) value;
        final Iterator<? extends Map.Entry<?, ?>> it = source.entrySet().iterator();
        int index = 0;
        while (it.hasNext()) {
            final Map.Entry<?, ?> entry = it.next();
            final Object key = entry.getKey();
            final Object val = entry.getValue();
            final Object mappedKey = elementMapper.apply(key, keyType);
            final Object mappedVal = elementMapper.apply(val, valueType);
            if (mappedKey != key || mappedVal != val) {
                final LinkedHashMap<Object, Object> result =
                        new LinkedHashMap<>(Math.max((int) (source.size() / .75f) + 1, 16));
                final Iterator<? extends Map.Entry<?, ?>> prefixIt =
                        source.entrySet().iterator();
                for (int p = 0; p < index; p++) {
                    final Map.Entry<?, ?> prefixEntry = prefixIt.next();
                    result.put(prefixEntry.getKey(), prefixEntry.getValue());
                }
                result.put(mappedKey, mappedVal);
                while (it.hasNext()) {
                    final Map.Entry<?, ?> rem = it.next();
                    result.put(
                            elementMapper.apply(rem.getKey(), keyType), elementMapper.apply(rem.getValue(), valueType));
                }
                return Collections.unmodifiableMap(result);
            }
            index++;
        }
        return source;
    }

    private static Object mapArray(
            final Object value,
            final Class<?> componentClass,
            final Type componentType,
            final BiFunction<Object, Type, Object> valueMapper) {
        final int length = Array.getLength(value);
        final Object mapped = Array.newInstance(componentClass, length);
        if (componentClass.isPrimitive()) {
            System.arraycopy(value, 0, mapped, 0, length);
            return mapped;
        }
        final Object[] source = (Object[]) value;
        final Object[] target = (Object[]) mapped;
        for (int index = 0; index < length; index++) {
            target[index] = valueMapper.apply(source[index], componentType);
        }
        return target;
    }

    private static boolean isPrimitiveWrapper(final Class<?> type) {
        return type == Boolean.class
                || type == Byte.class
                || type == Character.class
                || type == Short.class
                || type == Integer.class
                || type == Long.class
                || type == Float.class
                || type == Double.class
                || type == Void.class;
    }

    private static Class<?> rawClass(final Type type) {
        if (type instanceof Class<?> value) {
            return value;
        }
        if (type instanceof ParameterizedType value) {
            return rawClass(value.getRawType());
        }
        if (type instanceof GenericArrayType value) {
            final Class<?> componentClass = rawClass(value.getGenericComponentType());
            return Array.newInstance(componentClass, 0).getClass();
        }
        throw new IllegalStateException("Unsupported SDK carrier type: " + type.getTypeName());
    }
}
