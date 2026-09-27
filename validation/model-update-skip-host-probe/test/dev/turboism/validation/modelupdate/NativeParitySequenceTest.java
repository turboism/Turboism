package dev.turboism.validation.modelupdate;

import java.awt.EventQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

/** Reproduces a queued native action transition between parity captures. */
public final class NativeParitySequenceTest {
    public static void main(String[] arguments) throws Exception {
        int[] action = {0};
        List<Boolean> modes = new ArrayList<>();
        List<Integer> captures = NativeParitySequence.capture(enabled -> {
            require(EventQueue.isDispatchThread(), "capture must run on EDT");
            modes.add(enabled);
            if (modes.size() == 1) EventQueue.invokeLater(() -> action[0]++);
            return action[0];
        });
        EventQueue.invokeAndWait(() -> { });
        require(modes.equals(List.of(false, false, true, false)), "native/native/cached/native order");
        require(captures.equals(List.of(0, 0, 0, 0)),
            "queued native action must not interleave captures: " + captures);
        require(action[0] == 1, "queued events must still execute, not be suppressed");

        IllegalStateException sentinel = new IllegalStateException("capture-failure");
        int[] calls = {0};
        try {
            NativeParitySequence.capture(enabled -> {
                calls[0]++;
                if (enabled) throw sentinel;
                return 0;
            });
            throw new AssertionError("capture failure must propagate");
        } catch (ExecutionException expected) {
            require(expected.getCause() == sentinel, "original failure identity");
        }
        require(calls[0] == 3, "no duplicate capture or continued capture after failure");
        System.out.println("NativeParitySequenceTest PASS (same EDT turn, queued events preserved, exact order, failures)");
    }
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
