package dev.turboism.adapter.cubism.optimization.uniform;

/**
 * Pure cache owned by one explicit render scope, GL context and thread. It never
 * calls OpenGL. The installer/bridge must establish lifecycle coverage before
 * passing supported=true; a frame token alone does not provide that proof.
 *
 * A result is pending until the application's existing error observation from the
 * SAME context and thread confirms it. Reentrancy, context changes, errors and
 * mutations retire reuse until the outer scope ends. Tokens prevent stale cleanup
 * from ending a later frame. Ordinary frames release results on end; deferred
 * frames may hand confirmed results to the same thread/context on the next begin.
 */
final class FrameUniformLocationCache implements AutoCloseable {
    static final int MISS = Integer.MIN_VALUE;
    private final int bound;
    // At most half the slots are
    // occupied, so lookup always reaches an empty slot even under hash collisions.
    private String[] names = new String[0];
    private int[] programs = new int[0];
    private int[] locations = new int[0];
    private boolean[] confirmed = new boolean[0];
    private int[] occupiedSlots = new int[0];
    private int[] pendingSlots = new int[0];
    private int size, pendingCount;
    private Object context;
    private Thread owner, retainedOwner;
    private long sequence, scope;
    private boolean active, closed;

    FrameUniformLocationCache(int bound) {
        if (bound < 1) throw new IllegalArgumentException("cache bound must be positive");
        this.bound = bound;
    }

    synchronized long begin(Object current, boolean supported) {
        return begin(current, supported, false);
    }

    /**
     * {@code persistent} selects deferred-error-check retention: entries
     * confirmed by the previous frame's real boundary query stay usable
     * instead of being cleared, so a location recorded in frame N becomes
     * hittable in frame N+1 once that frame's {@code glGetError} proved clean.
     * A different context or an unsupported frame still clears everything.
     */
    synchronized long begin(Object current, boolean supported, boolean persistent) {
        if (closed) return 0;
        if (owner != null) {
            // Do not reopen reuse inside an unclosed outer render invocation.
            invalidate();
            return 0;
        }
        if (sequence == Long.MAX_VALUE) {
            close();
            return 0;
        }
        scope = ++sequence;
        owner = Thread.currentThread();
        active = supported && current != null;
        if (!(persistent && active && current == context && retainedOwner == Thread.currentThread())) {
            context = active ? current : null;
            clear();
        }
        return scope;
    }

    private boolean owns(Object current) {
        if (closed || !active || owner != Thread.currentThread()) return false;
        if (current == null || current != context) {
            invalidate();
            return false;
        }
        return true;
    }

    private static boolean valid(int program, String name) {
        return program > 0 && name != null && name.length() <= 512;
    }

    synchronized int lookup(Object current, int program, String name) {
        if (!owns(current) || !valid(program, name) || size == 0) return MISS;
        int slot = find(program, name);
        return names[slot] != null && confirmed[slot] ? locations[slot] : MISS;
    }

    synchronized void record(Object current, int program, String name, int result) {
        if (!owns(current) || !valid(program, name)) return;
        if (result < -1) {
            invalidate();
            return;
        }
        if (names.length == 0) grow();
        int slot = find(program, name);
        if (names[slot] != null) {
            locations[slot] = result;
            if (confirmed[slot]) {
                confirmed[slot] = false;
                pendingSlots[pendingCount++] = slot;
            }
            return;
        }
        if (size == bound) return;
        if (size == names.length / 2) {
            grow();
            slot = find(program, name);
        }
        names[slot] = name;
        programs[slot] = program;
        locations[slot] = result;
        confirmed[slot] = false;
        occupiedSlots[size++] = slot;
        pendingSlots[pendingCount++] = slot;
    }

    synchronized void checkedError(Object current, int error) {
        if (!owns(current)) return;
        if (error != 0) {
            invalidate();
            return;
        }
        for (int index = 0; index < pendingCount; index++) confirmed[pendingSlots[index]] = true;
        pendingCount = 0;
    }

    synchronized void invalidate() {
        clear();
        active = false;
        context = null;
    }

    synchronized void end(long token) {
        end(token, false);
    }

    /**
     * {@code retainConfirmed} is the deferred-error-check frame boundary: the
     * real boundary query returned {@code GL_NO_ERROR}, so every pending entry
     * recorded this frame is confirmed now and the whole table is kept for the
     * next frame's lookups. Without it the original per-frame wipe applies.
     */
    synchronized void end(long token, boolean retainConfirmed) {
        if (owner != Thread.currentThread() || token == 0 || token != scope) return;
        if (retainConfirmed && active) {
            for (int index = 0; index < pendingCount; index++) {
                confirmed[pendingSlots[index]] = true;
            }
            pendingCount = 0;
            retainedOwner = owner;
        } else {
            clear();
            context = null;
        }
        active = false;
        owner = null;
        scope = 0;
    }

    private int find(int program, String name) {
        int slot = hash(program, name) & (names.length - 1);
        while (names[slot] != null) {
            if (programs[slot] == program && names[slot].equals(name)) return slot;
            slot = (slot + 1) & (names.length - 1);
        }
        return slot;
    }

    private static int hash(int program, String name) {
        int hash = 31 * program + name.hashCode();
        return hash ^ (hash >>> 16);
    }

    private void grow() {
        int capacity = names.length == 0 ? 16 : Math.multiplyExact(names.length, 2);
        String[] nextNames = new String[capacity];
        int[] nextPrograms = new int[capacity];
        int[] nextLocations = new int[capacity];
        boolean[] nextConfirmed = new boolean[capacity];
        int[] nextOccupied = new int[capacity];
        int[] nextPending = new int[capacity];
        int nextPendingCount = 0;
        for (int index = 0; index < size; index++) {
            int old = occupiedSlots[index];
            int slot = hash(programs[old], names[old]) & (capacity - 1);
            while (nextNames[slot] != null) slot = (slot + 1) & (capacity - 1);
            nextNames[slot] = names[old];
            nextPrograms[slot] = programs[old];
            nextLocations[slot] = locations[old];
            nextConfirmed[slot] = confirmed[old];
            nextOccupied[index] = slot;
            if (!confirmed[old]) nextPending[nextPendingCount++] = slot;
        }
        // Publish only after every allocation and relocation succeeds.
        names = nextNames;
        programs = nextPrograms;
        locations = nextLocations;
        confirmed = nextConfirmed;
        occupiedSlots = nextOccupied;
        pendingSlots = nextPending;
        pendingCount = nextPendingCount;
    }

    private void clear() {
        retainedOwner = null;
        for (int index = 0; index < size; index++) {
            int slot = occupiedSlots[index];
            names[slot] = null;
            confirmed[slot] = false;
        }
        size = 0;
        pendingCount = 0;
    }

    synchronized int retained() {
        return size;
    }

    @Override
    public synchronized void close() {
        closed = true;
        clear();
        active = false;
        context = null;
        owner = null;
        scope = 0;
    }
}
