package dev.turboism.adapter.cubism.optimization.uniform;

import java.util.HashMap;
import java.util.Map;

/**
 * Pure cache owned by one explicit render scope, GL context and thread. It never
 * calls OpenGL. The installer/bridge must establish lifecycle coverage before
 * passing supported=true; a frame token alone does not provide that proof.
 *
 * A result is pending until the application's existing error observation from the
 * SAME context and thread confirms it. Reentrancy, context changes, errors and
 * mutations retire reuse until the outer scope ends. Tokens prevent stale cleanup
 * from ending a later frame; all host/name references are released on matching end.
 */
final class FrameUniformLocationCache implements AutoCloseable {
    static final int MISS = Integer.MIN_VALUE;
    private record Key(int program, String name) { }
    private final int bound;
    private final Map<Key, Integer> ready = new HashMap<>();
    private final Map<Key, Integer> pending = new HashMap<>();
    private Object context;
    private Thread owner;
    private long sequence, scope;
    private boolean active, closed;

    FrameUniformLocationCache(int bound) {
        if (bound < 1) throw new IllegalArgumentException("cache bound must be positive");
        this.bound = bound;
    }

    synchronized long begin(Object current, boolean supported) {
        if (closed) return 0;
        if (owner != null) {
            // Do not reopen reuse inside an unclosed outer render invocation.
            invalidate();
            return 0;
        }
        if (sequence == Long.MAX_VALUE) { close(); return 0; }
        scope = ++sequence;
        owner = Thread.currentThread();
        active = supported && current != null;
        context = active ? current : null;
        clear();
        return scope;
    }

    private boolean owns(Object current) {
        if (closed || !active || owner != Thread.currentThread()) return false;
        if (current == null || current != context) { invalidate(); return false; }
        return true;
    }
    private static boolean valid(int program, String name) {
        return program > 0 && name != null && name.length() <= 512;
    }
    synchronized int lookup(Object current, int program, String name) {
        if (!owns(current) || !valid(program, name)) return MISS;
        Integer value = ready.get(new Key(program, name));
        return value == null ? MISS : value;
    }
    synchronized void record(Object current, int program, String name, int result) {
        if (!owns(current) || !valid(program, name)) return;
        if (result < -1) { invalidate(); return; }
        Key key = new Key(program, name);
        ready.remove(key);
        if (ready.size() + pending.size() < bound || pending.containsKey(key)) {
            pending.put(key, result);
        }
    }
    synchronized void checkedError(Object current, int error) {
        if (!owns(current)) return;
        if (error != 0) { invalidate(); return; }
        ready.putAll(pending);
        pending.clear();
    }
    synchronized void invalidate() {
        clear();
        active = false;
        context = null;
    }
    synchronized void end(long token) {
        if (owner != Thread.currentThread() || token == 0 || token != scope) return;
        clear();
        active = false;
        context = null;
        owner = null;
        scope = 0;
    }
    private void clear() { ready.clear(); pending.clear(); }
    synchronized int retained() { return ready.size() + pending.size(); }
    @Override public synchronized void close() {
        closed = true;
        clear();
        active = false;
        context = null;
        owner = null;
        scope = 0;
    }
}
