package dev.turboism.validation.modelupdate;

import java.awt.Color;
import java.awt.EventQueue;
import java.awt.Graphics;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JComponent;

/** Offline controls and non-vacuous capture checks; does not load native GL. */
public final class NarrowUniformTrialTest {
    public static void main(String[] args) throws Exception {
        Object priorStats = System.getProperties().get(NarrowUniformTrial.STATS);
        String priorEnable = System.getProperty(NarrowUniformTrial.ENABLE);
        Map<String, Long> counts = new HashMap<>(Map.of("active", 1L, "completedFrames", 0L,
            "queries", 0L, "hits", 0L, "nativeResults", 0L, "failures", 0L, "glErrors", 0L));
        System.getProperties().put(NarrowUniformTrial.STATS, (Supplier<Map<String, Long>>) () -> Map.copyOf(counts));
        System.setProperty(NarrowUniformTrial.ENABLE, "original-value");
        try {
            EventQueue.invokeAndWait(() -> {
                try (NarrowUniformTrial trial = new NarrowUniformTrial()) {
                    JComponent panel = new JComponent() {
                        @Override protected void paintComponent(Graphics g) {
                            counts.merge("completedFrames", 1L, Long::sum);
                            counts.merge("queries", 2L, Long::sum);
                            counts.merge("nativeResults", 1L, Long::sum);
                            if (Boolean.getBoolean(NarrowUniformTrial.ENABLE)) counts.merge("hits", 1L, Long::sum);
                            g.setColor(Color.BLACK); g.fillRect(0, 0, 64, 64);
                            g.setColor(Color.WHITE); g.fillRect(0, 0, 32, 32);
                        }
                    };
                    panel.setSize(64, 64);
                    trial.setEnabled(false);
                    FrameReadback off = trial.capture(panel);
                    trial.setEnabled(true);
                    FrameReadback on = trial.capture(panel);
                    check(off.samePixels(on) && on.distinctPixels() == 2, "actual complete pixels must match");
                    Map<String, Long> before = trial.snapshot();
                    counts.merge("completedFrames", 2L, Long::sum);
                    counts.merge("queries", 4L, Long::sum); counts.merge("hits", 2L, Long::sum);
                    trial.requireLeg(before, trial.snapshot(), true, 2);
                    boolean rejected = false;
                    try { trial.requireLeg(trial.snapshot(), trial.snapshot(), true, 1); }
                    catch (IllegalStateException expected) { rejected = true; }
                    check(rejected, "no executed hook may not certify parity or speedup");
                    JComponent blank = new JComponent() {
                        @Override protected void paintComponent(Graphics g) {
                            counts.merge("completedFrames", 1L, Long::sum);
                            counts.merge("queries", 2L, Long::sum); counts.merge("hits", 1L, Long::sum);
                            g.setColor(Color.BLACK); g.fillRect(0, 0, 64, 64);
                        }
                    };
                    blank.setSize(64, 64);
                    rejected = false;
                    try { trial.capture(blank); } catch (IllegalStateException expected) { rejected = true; }
                    check(rejected, "blank image must be rejected");
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            check("original-value".equals(System.getProperty(NarrowUniformTrial.ENABLE)), "switch restored exactly");
            System.out.println("NarrowUniformTrialTest PASS (hook execution, full pixels, blank rejection, switch restoration)");
        } finally {
            if (priorStats == null) System.getProperties().remove(NarrowUniformTrial.STATS);
            else System.getProperties().put(NarrowUniformTrial.STATS, priorStats);
            if (priorEnable == null) System.clearProperty(NarrowUniformTrial.ENABLE);
            else System.setProperty(NarrowUniformTrial.ENABLE, priorEnable);
        }
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
