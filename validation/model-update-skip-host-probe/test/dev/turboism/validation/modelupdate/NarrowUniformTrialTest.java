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
                    deferredColdCapture(trial, counts);
                    for (String failure : new String[] {"no-hit", "pixel-change", "gl-error", "no-confirmation"}) {
                        rejectInvalidWarmup(trial, counts, failure);
                    }
                    counts.replaceAll((key, value) -> key.equals("active") ? 1L : 0L);
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
    /** Replays the OFF/OFF/ON parity transition: ON frame one has pending results only. */
    private static void deferredColdCapture(NarrowUniformTrial trial, Map<String, Long> counts) {
        final int[] paints = {0};
        JComponent panel = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                paints[0]++;
                counts.merge("completedFrames", 1L, Long::sum);
                counts.merge("queries", 4L, Long::sum);
                if (!Boolean.getBoolean(NarrowUniformTrial.ENABLE)) {
                    counts.put("retained", 0L);
                    counts.merge("nativeResults", 4L, Long::sum);
                } else {
                    if (counts.getOrDefault("retained", 0L) > 0L) counts.merge("hits", 4L, Long::sum);
                    else counts.merge("nativeResults", 4L, Long::sum);
                    counts.merge("deferredFrames", 1L, Long::sum);
                    counts.put("retained", 2L); // confirmation occurs only at this frame's end
                }
                g.setColor(Color.BLACK); g.fillRect(0, 0, 64, 64);
                g.setColor(Color.WHITE); g.fillRect(0, 0, 32, 32);
            }
        };
        panel.setSize(64, 64);
        trial.setEnabled(false);
        FrameReadback nativePixels = trial.capture(panel);
        trial.capture(panel);
        trial.setEnabled(true);
        FrameReadback cachedPixels = trial.capture(panel);
        check(nativePixels.samePixels(cachedPixels), "confirmed cached pixels match cold native pixels");
        check(paints[0] == 4, "OFF/OFF/ON capture needs exactly one untimed confirmation paint");
        Map<String, Long> before = trial.snapshot();
        trial.capture(panel);
        check(paints[0] == 5, "already confirmed capture must not add a warmup paint");
        check(NarrowUniformTrial.delta(before, trial.snapshot(), "hits") == 4, "final captured frame really hits");
    }

    private static void rejectInvalidWarmup(NarrowUniformTrial trial, Map<String, Long> counts, String failure) {
        counts.replaceAll((key, value) -> key.equals("active") ? 1L : 0L);
        trial.setEnabled(true);
        int[] paints = {0};
        JComponent panel = new JComponent() {
            @Override protected void paintComponent(Graphics g) {
                paints[0]++;
                counts.merge("completedFrames", 1L, Long::sum);
                counts.merge("queries", 4L, Long::sum);
                if (!failure.equals("no-confirmation")) {
                    counts.merge("deferredFrames", 1L, Long::sum);
                    counts.put("retained", 2L);
                }
                if (paints[0] > 1 && !failure.equals("no-hit")) counts.merge("hits", 4L, Long::sum);
                if (failure.equals("gl-error")) counts.put("glErrors", 1L);
                g.setColor(Color.BLACK); g.fillRect(0, 0, 64, 64);
                g.setColor(failure.equals("pixel-change") && paints[0] > 1 ? Color.RED : Color.WHITE);
                g.fillRect(0, 0, 32, 32);
            }
        };
        panel.setSize(64, 64);
        boolean rejected = false;
        try { trial.capture(panel); } catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "capture must reject " + failure);
        check(paints[0] == (failure.equals("no-hit") || failure.equals("pixel-change") ? 2 : 1),
            "no retries beyond one clean confirmed warmup: " + failure);
        counts.replaceAll((key, value) -> key.equals("active") ? 1L : 0L);
        Map<String, Long> before = trial.snapshot();
        counts.put("completedFrames", 1L); counts.put("queries", 4L);
        counts.put("deferredFrames", 1L); counts.put("retained", 2L);
        rejected = false;
        try { trial.requireLeg(before, trial.snapshot(), true, 1); }
        catch (IllegalStateException expected) { rejected = true; }
        check(rejected, "a timed leg with zero hits still fails, even when the frame confirmed pending results");
    }

    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
