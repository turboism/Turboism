package dev.turboism.test.fake;

/**
 * Fake Cubism host for first-phase testing. No real Cubism classes are used.
 */
public final class FakeCubismHost {

    private boolean running;

    public void start() {
        running = true;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }
}
