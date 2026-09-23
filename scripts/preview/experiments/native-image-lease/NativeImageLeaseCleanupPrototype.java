import java.util.IdentityHashMap;
import java.util.Map;

/** Synthetic ownership experiment; neither loads nor modifies Cubism. */
public final class NativeImageLeaseCleanupPrototype {
    private static int checks;

    @FunctionalInterface interface Body { void run() throws Throwable; }

    static final class Pool {
        final Map<Object, Boolean> borrowed = new IdentityHashMap<>();
        final Map<Object, Integer> attempts = new IdentityHashMap<>();
        int gets;
        int failGet = -1;
        boolean failAfterRegistration;
        int failRelease = -1;
        boolean failBeforeRemoval;
        int releases;
        final Throwable acquireFailure;
        final Throwable cleanupFailure = new IllegalStateException("synthetic cleanup failure");

        Pool(Throwable acquireFailure) { this.acquireFailure = acquireFailure; }

        Object get() throws Throwable {
            int call = ++gets;
            if (call == failGet && !failAfterRegistration) throw acquireFailure;
            Object image = new Object();
            borrowed.put(image, Boolean.TRUE);
            if (call == failGet) throw acquireFailure;
            return image;
        }

        void release(Object image) throws Throwable {
            int call = ++releases;
            int count = attempts.merge(image, 1, Integer::sum);
            require(count == 1, "double release attempt");
            require(borrowed.containsKey(image), "release of foreign/non-borrowed object");
            if (call == failRelease && failBeforeRemoval) throw cleanupFailure;
            borrowed.remove(image);
            if (call == failRelease) throw cleanupFailure;
        }
    }

    // Acquisition-before-finally topology only, not a native method implementation.
    static void control(Pool pool, Body body) throws Throwable {
        Object a = pool.get(), b = pool.get(), c = pool.get(), d = pool.get();
        try { body.run(); }
        finally { pool.release(a); pool.release(b); pool.release(c); pool.release(d); }
    }

    // Four locals rather than a new allocation-sensitive lease-list on the hot path.
    static void candidate(Pool pool, Body body) throws Throwable {
        Object a = null, b = null, c = null, d = null;
        Throwable primary = null;
        try {
            a = pool.get(); b = pool.get(); c = pool.get(); d = pool.get();
            body.run();
        } catch (Throwable failure) {
            primary = failure;
            throw failure;
        } finally {
            Throwable firstCleanup = null;
            firstCleanup = releaseAttempt(pool, a, firstCleanup);
            firstCleanup = releaseAttempt(pool, b, firstCleanup);
            firstCleanup = releaseAttempt(pool, c, firstCleanup);
            firstCleanup = releaseAttempt(pool, d, firstCleanup);
            // Do not allocate a suppressed-exception array during an Error path.
            // Secondary diagnostic reporting remains a separate production design issue.
            if (primary == null && firstCleanup != null) throw firstCleanup;
        }
    }

    static Throwable releaseAttempt(Pool pool, Object value, Throwable first) {
        if (value == null) return first;
        try { pool.release(value); }
        catch (Throwable failure) { if (first == null) return failure; }
        return first;
    }

    static Throwable thrown(Body body) {
        try { body.run(); return null; }
        catch (Throwable failure) { return failure; }
    }

    static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    static void acquisitionMatrix(Throwable primary) {
        for (int fail = 1; fail <= 4; fail++) {
            Pool original = new Pool(primary); original.failGet = fail;
            require(thrown(() -> control(original, () -> {})) == primary, "control primary identity");
            require(original.borrowed.size() == fail - 1, "control missing expected orphan count");
            Pool fixed = new Pool(primary); fixed.failGet = fail;
            require(thrown(() -> candidate(fixed, () -> {})) == primary, "candidate primary identity");
            require(fixed.borrowed.isEmpty(), "previously returned resources must be returned");
            require(fixed.releases == fail - 1, "one attempt per successful get");
        }
    }

    static void cleanupMatrix(Throwable primary) {
        for (int slot = 1; slot <= 4; slot++) {
            for (boolean before : new boolean[]{false, true}) {
                for (boolean bodyFails : new boolean[]{false, true}) {
                    Pool pool = new Pool(primary);
                    pool.failRelease = slot; pool.failBeforeRemoval = before;
                    Throwable actual = thrown(() -> candidate(pool, () -> {
                        if (bodyFails) throw primary;
                    }));
                    require(actual == (bodyFails ? primary : pool.cleanupFailure), "wrong propagated exception");
                    require(pool.releases == 4, "cleanup failure skipped later attempts");
                    require(pool.borrowed.size() == (before ? 1 : 0), "cleanup commit boundary misreported");
                }
            }
        }
    }

    static void internalRegistrationCounterexample(Throwable primary) {
        for (int slot = 1; slot <= 4; slot++) {
            Pool pool = new Pool(primary);
            pool.failGet = slot; pool.failAfterRegistration = true;
            require(thrown(() -> candidate(pool, () -> {})) == primary, "internal primary identity");
            require(pool.releases == slot - 1, "caller cannot release an unreturned object");
            require(pool.borrowed.size() == 1, "N02-b MUST remain visibly unsolved");
        }
    }

    static void ownershipAndControlCounterexamples() throws Throwable {
        Throwable failure = new IllegalArgumentException("synthetic acquire failure");
        Pool pool = new Pool(failure);
        Object foreign = pool.get();
        pool.failGet = pool.gets + 3;
        require(thrown(() -> candidate(pool, () -> {})) == failure, "foreign case primary");
        require(pool.borrowed.size() == 1 && pool.borrowed.containsKey(foreign), "foreign lease changed");
        pool.failGet = -1;
        candidate(pool, () -> {
            require(pool.borrowed.size() == 5, "outer four plus foreign");
            pool.failGet = pool.gets + 2;
            require(thrown(() -> candidate(pool, () -> {})) == failure, "nested primary");
            require(pool.borrowed.size() == 5, "nested cleanup touched outer leases");
        });
        require(pool.borrowed.size() == 1 && pool.borrowed.containsKey(foreign), "outer cleanup touched foreign");
        pool.release(foreign);
        require(pool.borrowed.isEmpty(), "owner final return");

        Pool original = new Pool(failure); original.failRelease = 1;
        require(thrown(() -> control(original, () -> { throw failure; })) == original.cleanupFailure,
                "control must expose original exception replacement");
        require(original.borrowed.size() == 3, "control must expose skipped cleanup");
        Pool normal = new Pool(failure);
        candidate(normal, () -> {});
        require(normal.borrowed.isEmpty() && normal.releases == 4, "normal path changed");
    }

    public static void main(String[] args) throws Throwable {
        for (Throwable primary : new Throwable[]{new RuntimeException("synthetic failure"),
                new Error("synthetic failure; not a real allocation exhaustion")}) {
            acquisitionMatrix(primary);
            cleanupMatrix(primary);
            internalRegistrationCounterexample(primary);
        }
        ownershipAndControlCounterexamples();
        System.out.println("PASS synthetic ownership checks=" + checks);
        System.out.println("N02-a=CONTROL_FLOW_VALIDATED N02-b=COUNTEREXAMPLE_PRESERVED");
        System.out.println("NATIVE_EQUIVALENCE=NOT_TESTED RAM=NOT_MEASURED CPU=NOT_MEASURED GPU=NOT_MEASURED");
        System.out.println("CONCURRENCY=NOT_TESTED CLEANUP_BEFORE_REMOVAL=UNSOLVED");
    }
}
