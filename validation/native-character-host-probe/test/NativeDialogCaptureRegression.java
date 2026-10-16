import dev.turboism.validation.nativecharacter.NativeCharacterHostProbe;
import java.awt.*;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.*;

/** Deterministically dismiss a dialog after the first EDT visibility check. */
public final class NativeDialogCaptureRegression {
    public static void main(String[] args) throws Exception {
        var armed = new AtomicBoolean();
        var pane = new JOptionPane("Import complete", JOptionPane.INFORMATION_MESSAGE);
        var dialog = new JDialog() {
            @Override public boolean isShowing() {
                boolean showing = super.isShowing();
                boolean probeRead = java.util.Arrays.stream(Thread.currentThread().getStackTrace())
                        .anyMatch(f -> f.getClassName().equals(NativeCharacterHostProbe.class.getName())
                                && f.getMethodName().equals("visible"));
                if (showing && probeRead && armed.compareAndSet(true, false))
                    EventQueue.invokeLater(this::dispose);
                return showing;
            }
        };
        dialog.setContentPane(pane);
        dialog.pack();
        SwingUtilities.invokeAndWait(() -> dialog.setVisible(true));
        var method = NativeCharacterHostProbe.class.getDeclaredMethod("awaitPane", int.class);
        method.setAccessible(true);
        armed.set(true);
        try {
            Object captured = method.invoke(new NativeCharacterHostProbe(), 2);
            SwingUtilities.invokeAndWait(() -> {});
            if (captured != pane || dialog.isShowing())
                throw new AssertionError("Expected the captured, now-dismissed dialog");
            System.out.println("PASS: dialog retained after concurrent dismissal");
        } finally {
            SwingUtilities.invokeAndWait(dialog::dispose);
        }
    }
}
