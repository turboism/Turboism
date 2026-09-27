package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.nio.file.Files;
import java.util.Map;
import java.util.function.Supplier;
import javax.swing.JPanel;

/** Deterministic activation/focus transitions; no native window, GL or Editor. */
public final class NativeInteractionFocusTest {
    public static void main(String[] args) throws Exception {
        Fake delayed = new Fake(4, 6, true);
        var result = NativeInteractionFocus.acquire(delayed, 1_000_000_000L);
        check(result.acquired(), "delayed activation must complete before canvas request");
        check(delayed.windowRequests == 1 && delayed.canvasRequests == 1, "one request per owner, no focus stealing loop");
        check(!delayed.premature, "requestFocusInWindow must wait for actual task-window focus");
        Fake external = new Fake(Integer.MAX_VALUE, Integer.MAX_VALUE, false);
        result = NativeInteractionFocus.acquire(external, 200_000_000L);
        check(!result.acquired() && external.canvasRequests == 0, "inactive/external window never gets key input");
        check(external.tick <= 5 && external.windowRequests == 1, "bounded wait, no repeated toFront");
        Fake rejected = new Fake(0, Integer.MAX_VALUE, false);
        result = NativeInteractionFocus.acquire(rejected, 200_000_000L);
        check(!result.acquired() && result.diagnostics().contains("requestFocusInWindowAccepted=false"),
            "rejected canvas request remains a failure with evidence");
        Fake lost = new Fake(0, Integer.MAX_VALUE, true) {
            @Override public NativeInteractionFocus.State observe() {
                return new NativeInteractionFocus.State(tick < 2, false, "focusOwner=external");
            }
        };
        check(!NativeInteractionFocus.acquire(lost, 200_000_000L).acquired(), "focus loss is never repaired by sending keys");
        check(lost.windowRequests == 1 && lost.canvasRequests == 1, "no repeated activation after external focus change");
        Fake alreadyOwned = new Fake(0, 0, true) {
            @Override public NativeInteractionFocus.State observe() {
                return new NativeInteractionFocus.State(true, true, "focusOwner=canvas");
            }
        };
        check(NativeInteractionFocus.acquire(alreadyOwned, 200_000_000L).acquired()
                && alreadyOwned.windowRequests == 0 && alreadyOwned.canvasRequests == 0,
            "an already owned canvas needs no activation request");
        Object old = System.getProperties().get("turboism.input-path.stats");
        try {
            System.getProperties().put("turboism.input-path.stats", (Supplier<Map<String, Long>>) () ->
                Map.of("active", 1L, "armed", 1L, "focusCalls", 9L, "focusElided", 7L, "focusPassed", 2L, "observerFailures", 0L));
            EventQueue.invokeAndWait(() -> {
                try {
                    var state = NativeInteractionFocus.observe(new JPanel(), null);
                    var path = Files.createTempDirectory("focus-evidence");
                    var failure = NativeInteractionFocus.refused(path, "requestFocusInWindowAccepted=false\n", state);
                    String evidence = Files.readString(path.resolve("interaction-focus.txt"));
                    for (String key : new String[] {"focusOwner=", "focusedWindow=", "activeWindow=", "window.isFocused=",
                            "window.isActive=", "inputPath.focusElided=7", "requestFocusInWindowAccepted=false"}) {
                        check(evidence.contains(key), "missing failure evidence: " + key);
                    }
                    check(!state.owned() && failure.getMessage().contains("refusing key input"), "fail closed with diagnostic exception");
                    System.getProperties().put("turboism.input-path.stats", (Supplier<Object>) () -> { throw new AssertionError("broken observer"); });
                    check(NativeInteractionFocus.observe(new JPanel(), null).diagnostics().contains("inputPath.status=unavailable"),
                        "observer failure must not obscure focus failure");
                    try { NativeInteractionFocus.acquire(new Fake(0, 0, true), 1L); throw new AssertionError("EDT wait accepted"); }
                    catch (IllegalStateException expected) { }
                    Files.delete(path.resolve("interaction-focus.txt")); Files.delete(path);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
        } finally {
            if (old == null) System.getProperties().remove("turboism.input-path.stats");
            else System.getProperties().put("turboism.input-path.stats", old);
        }
        System.out.println("NativeInteractionFocusTest PASS (delayed activation, timeout, external focus, rejected request, diagnostics)");
    }
    private static class Fake implements NativeInteractionFocus.Driver {
        final int readyAt, ownedAt;
        final boolean accepted;
        int tick, windowRequests, canvasRequests;
        boolean premature;
        Fake(int readyAt, int ownedAt, boolean accepted) { this.readyAt = readyAt; this.ownedAt = ownedAt; this.accepted = accepted; }
        public NativeInteractionFocus.State observe() {
            return new NativeInteractionFocus.State(tick >= readyAt, canvasRequests > 0 && accepted && tick >= ownedAt,
                "focusOwner=" + (tick >= ownedAt ? "canvas" : "other"));
        }
        public void requestWindow() { windowRequests++; }
        public boolean requestCanvas() { canvasRequests++; premature |= tick < readyAt; return accepted && !premature; }
        public void pause() { tick++; }
        public long nanoTime() { return tick * 50_000_000L; }
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
