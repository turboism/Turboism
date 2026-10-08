import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import javax.swing.SwingUtilities;

/** Unpackaged diagnostic adapter. No host classes are loaded or mutated during discovery. */
final class AtlasNativeUndo {
    private AtlasNativeUndo() {}

    static AtlasUndoGuard.Snapshot snapshot(Object controller, File taskFixture) throws Exception {
        requireEdt();
        Object doc = call(controller, "getCurrentDoc");
        if (doc == null) throw new IllegalStateException("no active document");
        Object content = call(doc, "getFileContent");
        Object file = call(content, "getFile");
        if (!(file instanceof File bound) || !bound.getCanonicalFile().equals(taskFixture.getCanonicalFile())) {
            throw new IllegalStateException("active document is not task fixture");
        }
        Object mode = call(doc, "getCurrentEditMode");
        Object manager = call(mode, "getUndoManager");
        Object position = call(manager, "getCurrentPos");
        Object entries = call(manager, "getUndoList");
        if (!(position instanceof Integer index) || !(entries instanceof List<?> list)) {
            throw new IllegalStateException("unexpected native undo shape");
        }
        return new AtlasUndoGuard.Snapshot(doc, manager, index, list);
    }

    static void undo(Object controller, File taskFixture, AtlasUndoGuard.Snapshot before,
                     AtlasUndoGuard.Snapshot applied) throws Exception {
        requireEdt();
        AtlasUndoGuard.requireSingleNewEdit(before, applied);
        AtlasUndoGuard.requireUnchanged(applied, snapshot(controller, taskFixture));
        if (!Boolean.TRUE.equals(call(applied.manager(), "canUndo"))) {
            throw new IllegalStateException("native edit cannot undo");
        }
        Method command = null;
        for (Method candidate : controller.getClass().getMethods()) {
            if (candidate.getName().equals("command_undo") && candidate.getParameterCount() == 1
                    && candidate.getReturnType() == void.class
                    && candidate.getParameterTypes()[0].isInstance(applied.document())) {
                if (command != null) throw new IllegalStateException("ambiguous native undo command");
                command = candidate;
            }
        }
        if (command == null) throw new IllegalStateException("native undo command unavailable");
        invoke(command, controller, applied.document());
        AtlasUndoGuard.requireRestored(before, applied, snapshot(controller, taskFixture));
    }

    private static Object call(Object receiver, String name) throws Exception {
        if (receiver == null) throw new IllegalStateException("missing native object: " + name);
        return invoke(receiver.getClass().getMethod(name), receiver);
    }

    private static Object invoke(Method method, Object receiver, Object... args) throws Exception {
        try {
            return method.invoke(receiver, args);
        } catch (InvocationTargetException wrapped) {
            Throwable cause = wrapped.getCause();
            if (cause instanceof Error error) throw error;
            if (cause instanceof Exception exception) throw exception;
            throw new IllegalStateException("native invocation failed", cause);
        }
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) {
            throw new IllegalStateException("native undo requires EDT");
        }
    }
}
