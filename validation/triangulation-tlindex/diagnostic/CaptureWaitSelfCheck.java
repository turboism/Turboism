import java.util.concurrent.atomic.AtomicInteger;

/** Own Invocation lifecycle only: no native classes are loaded or commands executed. */
public final class CaptureWaitSelfCheck {
    private static int checks;
    private CaptureWaitSelfCheck() {}

    public static void main(String[] args) {
        AtomicInteger calls = new AtomicInteger();
        FixedEdt.Invocation<Integer> cancelled = new FixedEdt.Invocation<>(calls::incrementAndGet);
        FixedEdt.State state = cancelled.timeoutIfQueued();
        require(state == FixedEdt.State.TIMED_OUT);
        require(retry(FixedEdt.Operation.MESH_CAPTURE, state, 1, 2));
        // A late dispatch of the old query cannot perform any action after cancellation.
        require(!cancelled.tryStart());
        cancelled.skipAfterTimeout();
        require(calls.get() == 0);

        FixedEdt.Invocation<Integer> replacement = new FixedEdt.Invocation<>(calls::incrementAndGet);
        require(replacement.tryStart());
        replacement.executeStarted();
        replacement.completeStarted();
        require(replacement.value() == 1 && calls.get() == 1);

        FixedEdt.Invocation<Integer> running = new FixedEdt.Invocation<>(calls::incrementAndGet);
        require(running.tryStart());
        require(running.timeoutIfQueued() == FixedEdt.State.STARTED);
        require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.STARTED, 1, 2));
        // A timed-out running query remains its sole invocation; no replacement is admitted.
        running.executeStarted();
        running.completeStarted();
        require(calls.get() == 2 && running.state() == FixedEdt.State.COMPLETED);
        require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.COMPLETED, 1, 2));
        require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.QUEUED, 1, 2));
        require(!retry(FixedEdt.Operation.MESH_CONNECT, FixedEdt.State.TIMED_OUT, 1, 2));
        require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.TIMED_OUT, 2, 2));
        require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.TIMED_OUT, 3, 2));
        Thread.currentThread().interrupt();
        try {
            require(!retry(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.TIMED_OUT, 1, 2));
            require(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
        System.out.println("Capture wait lifecycle checks PASS: " + checks + " hostExecuted=false");
    }

    private static boolean retry(FixedEdt.Operation operation, FixedEdt.State state, long now, long deadline) {
        return T040ShadowSceneDriverAgent.mayRetryCaptureWait(new FixedEdt.Timeout(operation, state), now, deadline);
    }

    private static void require(boolean value) {
        if (!value) throw new AssertionError("capture wait lifecycle check failed");
        checks++;
    }
}
