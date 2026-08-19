package dev.turboism.bootstrap;

/** Short-lived main used by the Windows preview launcher to prove premain execution. */
public final class PreviewAgentProbeMain {

    private PreviewAgentProbeMain() {
    }

    /**
     * Keeps the JVM alive briefly so the launcher can observe that premain ran.
     *
     * @param args ignored
     * @throws InterruptedException when the probe is interrupted while waiting
     */
    public static void main(final String[] args) throws InterruptedException {
        Thread.sleep(2_000L);
    }
}
