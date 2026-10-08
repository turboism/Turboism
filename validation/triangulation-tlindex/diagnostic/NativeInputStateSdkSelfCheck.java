import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Validates packaged raw-field access against the actual 5303 SDK, without initializing UI classes. */
public final class NativeInputStateSdkSelfCheck {
    private NativeInputStateSdkSelfCheck() { }
    public static void main(String[] args) throws Exception {
        Method read = NativeInputStateObservation.class.getDeclaredMethod("field", Class.class, String.class);
        read.setAccessible(true);
        String[][] fields = {{"com.live2d.ui.event.m", "i", "com.live2d.ui.CWidget", "static"},
            {"com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual",
             "currentMeshEditSubTool", "com.live2d.cubism.view.palette.tool.toolMode.meshEditor.ToolMode_MeshEdit_Manual$a", "static"},
            {"com.live2d.cubism.view.context.CEViewContext", "lastMouseEvent", "com.live2d.ui.event.m", "instance"},
            {"com.live2d.cubism.view.context.CEViewContext", "_lastActionPack", "com.live2d.cubism.view.context.actionManager.N", "instance"}};
        for (String[] row : fields) {
            Class<?> type = Class.forName(row[0], false, ClassLoader.getSystemClassLoader());
            if (type.getClassLoader() != ClassLoader.getSystemClassLoader()) throw new AssertionError("actual SDK required");
            Field f = (Field) read.invoke(null, type, row[1]);
            if (!f.getType().getName().equals(row[2]) || Modifier.isStatic(f.getModifiers()) != row[3].equals("static"))
                throw new AssertionError("unreviewed native input field " + f);
        }
        if (!java.awt.GraphicsEnvironment.isHeadless() || java.awt.Frame.getFrames().length != 0)
            throw new AssertionError("no Editor allowed");
        System.out.println("NativeInputStateSdkSelfCheck PASS actual5303Fields=4 UIInitialization=NOT_REQUESTED");
        System.exit(0);
    }
}
