import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;

/** Event semantics only; actual native listener delivery requires the host diagnostic. */
public final class NativeCanvasInputDispatchSelfCheck {
    private NativeCanvasInputDispatchSelfCheck() { }
    public static void main(String[] args) throws Exception {
        JPanel panel = new JPanel(); panel.setSize(200, 100);
        int[] calls = {0};
        panel.addMouseMotionListener(new MouseAdapter() {
            @Override public void mouseMoved(MouseEvent e) {
                if (e.getID() != MouseEvent.MOUSE_MOVED || e.getModifiersEx() != 0 || e.getButton() != MouseEvent.NOBUTTON
                        || e.getClickCount() != 0 || e.getX() != 100 || e.getY() != 50 || e.getSource() != panel)
                    throw new AssertionError("unexpected canvas input");
                calls[0]++;
            }
        });
        SwingUtilities.invokeAndWait(() -> panel.dispatchEvent(NativeCanvasInputDispatch.event(panel)));
        if (calls[0] != 1) throw new AssertionError("motion delivery required");
        panel.setSize(0, 0);
        try { NativeCanvasInputDispatch.event(panel); throw new AssertionError("empty canvas accepted"); }
        catch (IllegalStateException expected) { /* Dimension refusal. */ }
        try { NativeCanvasInputDispatch.dispatch(null); throw new AssertionError("off-EDT native access accepted"); }
        catch (IllegalStateException expected) { /* Thread refusal before native access. */ }
        System.out.println("NativeCanvasInputDispatchSelfCheck PASS eventSemantics/noButtons/emptyCanvas/EDT controls nativeDelivery=UNPROVEN");
    }
}
