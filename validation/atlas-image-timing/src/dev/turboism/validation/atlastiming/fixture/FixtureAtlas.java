package dev.turboism.validation.atlastiming.fixture;

/** Own fixture mirroring the nested per-page entry points. Never a host class. */
public class FixtureAtlas {
    public int calls;

    public void updateTexture(final boolean force, final Object progress) {
        calls++;
        setupCacheImage(force, progress);
    }

    public void setupCacheImage(final boolean force, final Object progress) {
        calls++;
        innerDraw();
    }

    private void innerDraw() {
        calls++;
    }

    public void throwingPath() {
        calls++;
        throw new IllegalStateException("fixture failure");
    }

    /** Catches a throwing instrumented callee so the stale enter must be discarded on exit. */
    public void catchingPath() {
        calls++;
        try {
            throwingPath();
        } catch (IllegalStateException expected) {
            calls++;
        }
    }

    /** A sampled-metric entry that exits by throwing; the entry stack sample must still land. */
    public void throwingUpdate(final boolean force, final Object progress) {
        calls++;
        throw new IllegalStateException("fixture update failure");
    }

    /** Unwoven recursion deep enough to overflow the stack-sample depth cap. */
    public void deepDrive(final int remaining) {
        if (remaining <= 0) {
            updateTexture(true, null);
            return;
        }
        deepDrive(remaining - 1);
    }

    public abstract static class AbstractBase {
        public abstract void notConcrete();
    }
}
