package dev.turboism.adapter.cubism.interpolation;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed-schema numerical access, bound once per admitted form class, without per-call reflection/boxing. */
final class LocalFormState {
    private static final Map<String, Set<String>> FIELDS = Map.of(
            "ACForm",
                    Set.of(
                            "_source",
                            "guid",
                            "name",
                            "notes",
                            "isParameterOutside",
                            "isAnimatedForm",
                            "isLocalAnimatedForm",
                            "editVersion",
                            "instanceNoForDebug",
                            "_instance"),
            "ACDrawableForm", Set.of("drawOrder", "_drawOrder", "opacity", "multiplyColor", "screenColor", "coordType"),
            "ACDeformerForm", Set.of("opacity", "multiplyColor", "screenColor", "coordType"),
            "CArtMeshForm", Set.of("positions", "showPointsInTree", "treeAccess"),
            "CWarpDeformerForm", Set.of("positions", "treeAccess"),
            "CRotationDeformerForm",
                    Set.of("angle", "originX", "originY", "scale", "isReflectX", "isReflectY", "treeAccess"));

    private record Slot(MethodHandle getter, Class<?> type) {
        int bits(Object form) throws Throwable {
            if (type == float.class) {
                float value = (float) getter.invokeExact(form);
                if (!Float.isFinite(value)) throw NonFinite.INSTANCE;
                return Float.floatToRawIntBits(value);
            }
            if (type == int.class) return (int) getter.invokeExact(form);
            return (boolean) getter.invokeExact(form) ? 1 : 0;
        }
    }

    private static final class NonFinite extends Exception {
        static final NonFinite INSTANCE = new NonFinite();

        private NonFinite() {
            super(null, null, false, false);
        }
    }

    final int tag, width;
    private final Slot[] slots;
    private final MethodHandle multiply, screen;
    private final MethodHandle[] channels;

    static LocalFormState bind(Class<?> form) {
        try {
            return new LocalFormState(form);
        } catch (ReflectiveOperationException | IllegalArgumentException unsupported) {
            return null;
        }
    }

    private LocalFormState(Class<?> form) throws ReflectiveOperationException {
        tag = switch (form.getSimpleName()) {
            case "CArtMeshForm" -> 1;
            case "CWarpDeformerForm" -> 2;
            case "CRotationDeformerForm" -> 3;
            default -> throw new IllegalArgumentException("form");
        };
        var fields = new ArrayList<java.lang.reflect.Field>();
        for (Class<?> c = form; c != Object.class; c = c.getSuperclass()) {
            Set<String> expected = FIELDS.get(c.getSimpleName());
            if (expected == null) throw new IllegalArgumentException("unknown superclass");
            var found = new java.util.HashSet<String>();
            for (var f : c.getDeclaredFields())
                if (!Modifier.isStatic(f.getModifiers())) {
                    found.add(f.getName());
                    if (!expected.contains(f.getName())) throw new IllegalArgumentException("unknown form field");
                    if (f.getType().isPrimitive()
                            && !f.getName().equals("editVersion")
                            && !f.getName().equals("instanceNoForDebug")) fields.add(f);
                }
            if (!found.equals(expected)) throw new IllegalArgumentException("missing form field");
        }
        fields.sort(Comparator.comparing(f -> f.getDeclaringClass().getName() + "." + f.getName()));
        slots = new Slot[fields.size()];
        for (int i = 0; i < slots.length; i++) {
            var f = fields.get(i);
            if (f.getType() != float.class && f.getType() != int.class && f.getType() != boolean.class)
                throw new IllegalArgumentException("primitive");
            var getter = MethodHandles.privateLookupIn(f.getDeclaringClass(), MethodHandles.lookup())
                    .unreflectGetter(f)
                    .asType(MethodType.methodType(f.getType(), Object.class));
            slots[i] = new Slot(getter, f.getType());
        }
        multiply = objectGetter(form, "getMultiplyColor");
        screen = objectGetter(form, "getScreenColor");
        Class<?> color = form.getMethod("getMultiplyColor").getReturnType();
        channels = new MethodHandle[4];
        int i = 0;
        for (String name : List.of("getA", "getR", "getG", "getB"))
            channels[i++] = MethodHandles.publicLookup()
                    .unreflect(color.getMethod(name))
                    .asType(MethodType.methodType(float.class, Object.class));
        width = slots.length + 8;
    }

    private static MethodHandle objectGetter(Class<?> c, String method) throws ReflectiveOperationException {
        return MethodHandles.publicLookup()
                .unreflect(c.getMethod(method))
                .asType(MethodType.methodType(Object.class, Object.class));
    }
    /** Distinct forms must not share mutable colors; coordinates are checked by the bridge. */
    boolean independentColors(List<?> targets, List<?> sources) throws Throwable {
        var owners = new IdentityHashMap<Object, Object>();
        for (List<?> forms : List.of(targets, sources))
            for (Object form : forms) {
                Object a = multiply.invokeExact(form), b = screen.invokeExact(form);
                if (a == null || b == null || a == b) return false;
                Object old = owners.putIfAbsent(a, form);
                if (old != null && old != form) return false;
                old = owners.putIfAbsent(b, form);
                if (old != null && old != form) return false;
            }
        return true;
    }

    int append(Object form, int[] out, int at) throws Throwable {
        for (Slot slot : slots) out[at++] = slot.bits(form);
        for (int i = 0; i < 2; i++) {
            Object color = i == 0 ? multiply.invokeExact(form) : screen.invokeExact(form);
            for (MethodHandle channel : channels) {
                float value = (float) channel.invokeExact(color);
                if (!Float.isFinite(value)) throw NonFinite.INSTANCE;
                out[at++] = Float.floatToRawIntBits(value);
            }
        }
        return at;
    }

    boolean matches(Object form, int[] before, int at) throws Throwable {
        for (Slot slot : slots) if (before[at++] != slot.bits(form)) return false;
        for (int i = 0; i < 2; i++) {
            Object color = i == 0 ? multiply.invokeExact(form) : screen.invokeExact(form);
            for (MethodHandle channel : channels)
                if (before[at++] != Float.floatToRawIntBits((float) channel.invokeExact(color))) return false;
        }
        return true;
    }

    static boolean nonFinite(Throwable failure) {
        return failure == NonFinite.INSTANCE;
    }
}
