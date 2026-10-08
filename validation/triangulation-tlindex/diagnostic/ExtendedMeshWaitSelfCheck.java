/** Guard proof for long-run diagnostics; no host dispatch or production code. */
final class ExtendedMeshWaitSelfCheck {
    private static int assertions;
    private static void require(boolean value) {
        assertions++;
        if (!value) throw new AssertionError("extended mesh wait guard " + assertions);
    }
    public static void main(String[] args) throws Exception {
        var queued = new FixedEdt.Invocation<>(() -> { throw new AssertionError("cancelled action executed"); });
        require(queued.timeoutIfQueued() == FixedEdt.State.TIMED_OUT);
        require(!queued.tryStart());
        queued.skipAfterTimeout();
        require(queued.awaitCompletedMillis(1));
        require(T040ShadowSceneDriverAgent.mayRetryExtendedMeshWait(
            new FixedEdt.Timeout(FixedEdt.Operation.MESH_CONNECT, queued.state()), 1, 2));
        for (var state : new FixedEdt.State[] {FixedEdt.State.QUEUED, FixedEdt.State.STARTED, FixedEdt.State.COMPLETED}) {
            require(!T040ShadowSceneDriverAgent.mayRetryExtendedMeshWait(
                new FixedEdt.Timeout(FixedEdt.Operation.MESH_CONNECT, state), 1, 2));
        }
        var timeout = new FixedEdt.Timeout(FixedEdt.Operation.MESH_CONNECT, FixedEdt.State.TIMED_OUT);
        require(!T040ShadowSceneDriverAgent.mayRetryExtendedMeshWait(timeout, 2, 2));
        require(!T040ShadowSceneDriverAgent.mayRetryExtendedMeshWait(
            new FixedEdt.Timeout(FixedEdt.Operation.MESH_CAPTURE, FixedEdt.State.TIMED_OUT), 1, 2));
        Thread.currentThread().interrupt();
        try { require(!T040ShadowSceneDriverAgent.mayRetryExtendedMeshWait(timeout, 1, 2)); }
        finally { Thread.interrupted(); }
        System.out.println("Extended mesh queue guard PASS: " + assertions + " hostExecuted=false");
    }
}
