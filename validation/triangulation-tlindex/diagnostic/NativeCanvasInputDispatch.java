import java.awt.Component;
import java.awt.event.MouseEvent;
import java.lang.reflect.Field;
import java.util.List;
import javax.swing.SwingUtilities;

/** One task-owned native canvas mouse-move delivery; never moves the OS pointer or writes native fields. */
final class NativeCanvasInputDispatch {
    private NativeCanvasInputDispatch() { }
    static MouseEvent event(Component component) {
        if (component.getWidth() < 2 || component.getHeight() < 2)
            throw new IllegalStateException("canvas dimensions required");
        return new MouseEvent(component, MouseEvent.MOUSE_MOVED, System.currentTimeMillis(), 0,
                component.getWidth() / 2, component.getHeight() / 2, 0, false, MouseEvent.NOBUTTON);
    }
    private static Field field(Class<?> type, String name) throws Exception {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field f = current.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException missing) { /* Official hierarchy only. */ }
        }
        throw new NoSuchFieldException(name);
    }
    static void dispatch(Object doc) throws Exception {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("canvas dispatch requires EDT");
        Object value = NativeAutoConnect.invokeNamed(doc, "getViewContexts");
        if (!(value instanceof List<?> views) || views.size() != 1)
            throw new IllegalStateException("one task document view required");
        Object view = views.get(0);
        if (!view.getClass().getName().equals("com.live2d.cubism.view.context.CEViewContext_ModelingView"))
            throw new IllegalStateException("task modeling view required");
        Object widget = NativeAutoConnect.invokeNamed(view, "getComponent");
        Object component = NativeAutoConnect.invokeNamed(widget, "getJComponent");
        if (!(component instanceof Component canvas) || !canvas.isShowing()
                || canvas.getMouseMotionListeners().length == 0)
            throw new IllegalStateException("visible native canvas motion listeners required");
        canvas.dispatchEvent(event(canvas));
        Class<?> mouse = Class.forName("com.live2d.ui.event.m", false, doc.getClass().getClassLoader());
        Object last = field(view.getClass(), "lastMouseEvent").get(view);
        if (field(mouse, "i").get(null) != widget || last == null || !mouse.isInstance(last))
            throw new IllegalStateException("actual native listener delivery not established");
    }
}
