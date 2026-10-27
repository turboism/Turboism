package dev.turboism.adapter.cubism.warpalt;

import dev.turboism.core.runtime.work.FatalErrors;
import java.awt.event.MouseEvent;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;

/** Keeps the pressed side of each selected pair in charge of a native point drag. */
final class NativeWarpPointGesture {
    private static final ThreadLocal<Gesture> CURRENT = new ThreadLocal<>();

    static final class Gesture {
        final float x;
        final float y;
        final WeakReference<Object> view;
        final Map<float[], Map<Integer, Integer>> drivers = new IdentityHashMap<>();
        final Map<float[], Set<Integer>> written = new IdentityHashMap<>();

        Gesture(Object view, float x, float y) {
            this.view = new WeakReference<>(view);
            this.x = x;
            this.y = y;
        }

        int driver(float[] positions, int index, int other, float px, float py, float ox, float oy) {
            Map<Integer, Integer> pairDrivers = drivers.computeIfAbsent(positions, ignored -> new HashMap<>());
            int key = Math.min(index, other);
            return pairDrivers.computeIfAbsent(key, ignored -> {
                double a = Math.pow(px - x, 2) + Math.pow(py - y, 2);
                double b = Math.pow(ox - x, 2) + Math.pow(oy - y, 2);
                return a < b ? index : b < a ? other : key;
            });
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
                Class<?> componentPoint = Class.forName(
                        "com.live2d.type.CPoint", false, view.getClass().getClassLoader());
                Object point =
                        componentPoint.getConstructor(int.class, int.class).newInstance(mouse.getX(), mouse.getY());
                Object camera = call(view, "getCameraManager");
                Object document = camera.getClass()
                        .getMethod("componentToDocument", componentPoint)
                        .invoke(camera, point);
                CURRENT.set(new Gesture(view, number(document, "getX"), number(document, "getY")));
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

    private static Object call(Object target, String name) throws ReflectiveOperationException {
        return target.getClass().getMethod(name).invoke(target);
    }

    private static float number(Object target, String name) throws ReflectiveOperationException {
        return ((Number) call(target, name)).floatValue();
    }
}
