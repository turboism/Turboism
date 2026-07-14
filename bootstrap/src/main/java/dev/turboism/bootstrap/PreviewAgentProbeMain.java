package dev.turboism.bootstrap;

/** Short-lived main used by the Windows preview launcher to prove premain execution. */
public final class PreviewAgentProbeMain {

    private PreviewAgentProbeMain() {
    }

    public static void main(final String[] args) throws InterruptedException {
        Thread.sleep(2_000L);
    }
}
