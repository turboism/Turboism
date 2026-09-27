package dev.turboism.validation.atlasimage.t039;

/** Checks premain rejection leaves no transformer or owned resource active. */
public final class T039FailureHarness {
    private T039FailureHarness() {
    }

    public static void main(final String[] args) {
        final T039ShadowAgent.Snapshot snapshot = T039ShadowAgent.snapshot();
        final String kind = args.length == 0 ? "unspecified" : args[0];
        check(!snapshot.transformerRegistered()
                && "NOT_ATTEMPTED".equals(snapshot.removalStatus()),
            "failed premain removal state was not truthful: " + snapshot);
        check(snapshot.targetEvents() == 0 && snapshot.candidateCount() == 0,
            "failed premain touched target state: " + snapshot);
        System.out.println("t039FailureCleanup=PASS kind=" + kind + " state=" + snapshot.state()
            + " reason=" + snapshot.reason());
        System.out.println("OFFLINE_PASS");
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
