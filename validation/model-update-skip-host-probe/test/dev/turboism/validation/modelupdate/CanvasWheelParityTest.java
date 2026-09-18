package dev.turboism.validation.modelupdate;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;

/** Executes the real wheel parity path, without a Cubism/GL host. */
public final class CanvasWheelParityTest {
    public static void main(String[] args) throws Exception {
        exercise(false);
        exercise(true);
        System.out.println("CanvasWheelParityTest PASS (real wheel path, queued action, exact mismatch rejection)");
    }

    private static void exercise(boolean incorrectCachedImage) throws Exception {
        Object previousStats = System.getProperties().get(NarrowUniformTrial.STATS);
        NarrowUniformTrial trial = new NarrowUniformTrial();
        Canvas canvas = new Canvas(incorrectCachedImage);
        CanvasWheelWorkload workload = new CanvasWheelWorkload("fixture", Path.of("unused"));
        set(workload, "canvas", canvas);
        set(workload, "narrowTrial", trial);
        var verify = CanvasWheelWorkload.class.getDeclaredMethod("verifyUniformPixelsAtState", String.class, StringBuilder.class);
        verify.setAccessible(true);
        StringBuilder report = new StringBuilder();
        try {
            System.getProperties().put(NarrowUniformTrial.STATS,
                (Supplier<Map<String, Long>>) () -> Map.of("active", 1L, "completedFrames", canvas.frames,
                    "queries", canvas.frames, "hits", canvas.hits));
            try {
                Object digest = verify.invoke(workload, "fixed", report);
                if (incorrectCachedImage) throw new AssertionError("changed cached pixel was accepted");
                if (!(digest instanceof String value) || value.length() != 64) throw new AssertionError("digest missing");
            } catch (InvocationTargetException failure) {
                if (!incorrectCachedImage) throw new AssertionError("queued action split wheel parity controls", failure.getCause());
                if (!(failure.getCause() instanceof IllegalStateException)
                    || !failure.getCause().getMessage().contains("pixel mismatch")) throw failure;
            }
            EventQueue.invokeAndWait(() -> { });
            if (!canvas.transitioned) throw new AssertionError("queued action was dropped");
            if (!canvas.variants.equals(List.of(false, false, true, false))) throw new AssertionError("capture order changed");
            if (!report.toString().contains("distinctPixelsAtLeast=")) throw new AssertionError("nonblank evidence missing");
        } finally {
            EventQueue.invokeAndWait(trial::close);
            if (previousStats == null) System.getProperties().remove(NarrowUniformTrial.STATS);
            else System.getProperties().put(NarrowUniformTrial.STATS, previousStats);
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static final class Canvas extends JComponent {
        private final boolean incorrectCachedImage;
        private final List<Boolean> variants = new ArrayList<>();
        private long frames, hits;
        private boolean transitioned;
        Canvas(boolean incorrectCachedImage) {
            this.incorrectCachedImage = incorrectCachedImage;
            setSize(8, 8);
        }
        @Override public void paint(Graphics graphics) {
            if (!EventQueue.isDispatchThread()) throw new AssertionError("paint left EDT");
            boolean enabled = Boolean.getBoolean(NarrowUniformTrial.ENABLE);
            variants.add(enabled);
            frames++;
            if (enabled) hits++;
            graphics.setColor(transitioned ? Color.GREEN : Color.RED);
            graphics.fillRect(0, 0, 8, 8);
            graphics.setColor(Color.BLUE);
            graphics.fillRect(0, 0, 2, 2);
            if (enabled && incorrectCachedImage) {
                graphics.setColor(Color.WHITE);
                graphics.fillRect(7, 7, 1, 1);
            }
            if (frames == 1) EventQueue.invokeLater(() -> transitioned = true);
        }
    }
}
