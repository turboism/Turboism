package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.reflect.ReflectAccess;
import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.event.MouseEvent;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Keeps the pressed side of each selected pair in charge of a native point drag. */
final class NativeWarpPointGesture {
    private static final String CPOINT_CLASS = "com.live2d.type.CPoint";
    private static final ThreadLocal<Gesture> CURRENT = new ThreadLocal<>();

    static final class Gesture {
        final float x;
        final float y;
        final WeakReference<Object> view;
        final Map<float[], int[]> drivers = new IdentityHashMap<>();
        final Map<float[], Map<Integer, Integer>> overflowDrivers = new IdentityHashMap<>();
        final Map<float[], Set<Integer>> written = new IdentityHashMap<>();

        Gesture(Object view, float x, float y) {
            this.view = new WeakReference<>(view);
            this.x = x;
            this.y = y;
        }

        int driver(float[] positions, int index, int other, float px, float py, float ox, float oy) {
            int key = Math.min(index, other);
            int capacity = positions.length >> 1;
            if (key >= 0 && key < capacity) {
                int[] table = drivers.get(positions);
                if (table == null) {
                    table = new int[capacity];
                    drivers.put(positions, table);
                }
                int cached = table[key];
                if (cached != 0) return cached - 1;
                int chosen = chooseDriver(index, other, key, px, py, ox, oy);
                table[key] = chosen + 1;
                return chosen;
            }
            Map<Integer, Integer> pairDrivers = overflowDrivers.computeIfAbsent(positions, ignored -> new HashMap<>());
            Integer cached = pairDrivers.get(key);
            if (cached != null) return cached;
            int chosen = chooseDriver(index, other, key, px, py, ox, oy);
            pairDrivers.put(key, chosen);
            return chosen;
        }

        private int chooseDriver(int index, int other, int key, float px, float py, float ox, float oy) {
            double adx = px - x;
            double ady = py - y;
            double a = adx * adx + ady * ady;
            double bdx = ox - x;
            double bdy = oy - y;
            double b = bdx * bdx + bdy * bdy;
            return a < b ? index : b < a ? other : key;
        }
    }

    private NativeWarpPointGesture() {}

    static void clear() {
        CURRENT.remove();
    }

    static void input(Object view, Object pack, boolean enabled) {
        if (!enabled) {
            clear();
            return;
        }
        try {
            if (!(NativeMirrorInput.awt(pack) instanceof MouseEvent mouse)) return;
            if (mouse.getID() == MouseEvent.MOUSE_PRESSED) {
                clear();
                if (mouse.getButton() != MouseEvent.BUTTON1) return;
                InputHandles handles = INPUT_HANDLES.get(view.getClass());
                Object point = handles.pointCtor.invokeExact(mouse.getX(), mouse.getY());
                Object camera = handles.getCameraManager.invokeExact(view);
                Object document = handles.componentToDocument.invokeExact(camera, point);
                float docX = (float) handles.vecX.invokeExact(document);
                float docY = (float) handles.vecY.invokeExact(document);
                CURRENT.set(new Gesture(view, docX, docY));
            } else if (mouse.getID() == MouseEvent.MOUSE_RELEASED) {
                clear();
            } else {
                Gesture gesture = CURRENT.get();
                if (gesture != null) {
                    if (gesture.view.get() != view) clear();
                    else gesture.written.clear();
                }
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            clear();
        }
    }

    /** Returns whether the native body must skip an already mirrored or deferred counterpart. */
    static boolean move(Object ref, Object target, float weight, int axis) throws ReflectiveOperationException {
        Gesture gesture = CURRENT.get();
        if (gesture == null || ref == null || target == null || axis == 0 || !Float.isFinite(weight) || weight <= 0)
            return false;
        float[] positions = (float[]) call(ref, "g");
        int index = ((Number) call(ref, "a")).intValue();
        Object source = call(ref, "d");
        int width = ((Number) call(source, "getCol")).intValue() + 1;
        int height = ((Number) call(source, "getRow")).intValue() + 1;
        if (width < 1 || height < 1 || positions.length != 2L * width * height || index < 0 || index >= width * height)
            return false;
        int other = axis == 1
                ? (height - 1 - index / width) * width + index % width
                : index / width * width + width - 1 - index % width;
        if (other == index) return false;
        Set<Integer> written = gesture.written.computeIfAbsent(positions, ignored -> new HashSet<>());
        if (written.contains(index)) return true;
        Object counterpart = NativeWarpAltMirrorBridge.counterpartForGesture(ref, other);
        if (counterpart == null) return false;
        Object a = call(ref, "e");
        Object b = call(counterpart, "e");
        int driver = gesture.driver(
                positions, index, other, number(a, "getX"), number(a, "getY"), number(b, "getX"), number(b, "getY"));
        if (driver != index) return true;
        float dx = (number(target, "getX") - positions[index * 2]) * weight;
        float dy = (number(target, "getY") - positions[index * 2 + 1]) * weight;
        if (!Float.isFinite(dx) || !Float.isFinite(dy)) return false;
        positions[other * 2] += axis == 2 ? -dx : dx;
        positions[other * 2 + 1] += axis == 1 ? -dy : dy;
        written.add(index);
        written.add(other);
        return false;
    }

    private static final ClassValue<InputHandles> INPUT_HANDLES = new ClassValue<>() {
        @Override
        protected InputHandles computeValue(Class<?> viewType) {
            try {
                return new InputHandles(viewType);
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }
    };

    private static final class InputHandles {
        final MethodHandle pointCtor;
        final MethodHandle getCameraManager;
        final MethodHandle componentToDocument;
        final MethodHandle vecX;
        final MethodHandle vecY;

        InputHandles(Class<?> viewType) throws ReflectiveOperationException {
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Class<?> componentPoint = Class.forName(CPOINT_CLASS, false, viewType.getClassLoader());
            Constructor<?> cPointCtor = componentPoint.getConstructor(int.class, int.class);
            ReflectAccess.open(cPointCtor);
            pointCtor = lookup.unreflectConstructor(cPointCtor)
                    .asType(MethodType.methodType(Object.class, int.class, int.class));

            Method camMethod = viewType.getMethod("getCameraManager");
            ReflectAccess.open(camMethod);
            Class<?> cameraType = camMethod.getReturnType();
            getCameraManager = lookup.unreflect(camMethod).asType(MethodType.methodType(Object.class, Object.class));

            Method toDocMethod = cameraType.getMethod("componentToDocument", componentPoint);
            ReflectAccess.open(toDocMethod);
            Class<?> vecType = toDocMethod.getReturnType();
            componentToDocument = lookup.unreflect(toDocMethod)
                    .asType(MethodType.methodType(Object.class, Object.class, Object.class));

            Method xMethod = vecType.getMethod("getX");
            Method yMethod = vecType.getMethod("getY");
            ReflectAccess.open(xMethod);
            ReflectAccess.open(yMethod);
            vecX = lookup.unreflect(xMethod).asType(MethodType.methodType(float.class, Object.class));
            vecY = lookup.unreflect(yMethod).asType(MethodType.methodType(float.class, Object.class));
        }
    }

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static float number(Object target, String name) throws ReflectiveOperationException {
        return ((Number) call(target, name)).floatValue();
    }
}
