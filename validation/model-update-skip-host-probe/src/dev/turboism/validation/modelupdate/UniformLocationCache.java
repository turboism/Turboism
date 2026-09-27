package dev.turboism.validation.modelupdate;

import java.util.HashMap;
import java.util.Map;

/**
 * Validation-only cache scoped to one native display and its context/thread.
 * A returned location is pending until the application's own glGetError returns
 * GL_NO_ERROR. No GL command or additional error query is issued by this class.
 */
final class UniformLocationCache {
    private record Key(int program, String name) { }
    private final int bound;
    private final Map<Key, Integer> ready = new HashMap<>();
    private final Map<Key, Integer> pending = new HashMap<>();
    private Object context;
    private Thread owner;
    private long hits, misses, confirmed, frames, invalidations;

    UniformLocationCache(int bound) {
        if (bound < 1) throw new IllegalArgumentException("cache bound must be positive");
        this.bound = bound;
    }
    synchronized void begin(Object currentContext) {
        clear();
        context = currentContext;
        owner = currentContext == null ? null : Thread.currentThread();
        frames++;
    }
    synchronized void end() { clear(); context = null; owner = null; }
    private void clear() { ready.clear(); pending.clear(); }
    private boolean owns(Object currentContext) {
        if (owner == null || context == null || currentContext != context || Thread.currentThread() != owner) {
            end();
            return false;
        }
        return true;
    }
    private static boolean valid(int program, String name) {
        return program > 0 && name != null && name.length() <= 512;
    }
    synchronized Integer lookup(Object currentContext, int program, String name) {
        Integer result = owns(currentContext) && valid(program, name) ? ready.get(new Key(program, name)) : null;
        if (result == null) misses++; else hits++;
        return result;
    }
    synchronized void queried(Object currentContext, int program, String name, int location) {
        if (!owns(currentContext) || !valid(program, name)) return;
        Key key = new Key(program, name);
        if (ready.size() + pending.size() >= bound && !pending.containsKey(key) && !ready.containsKey(key)) return;
        pending.put(key, location);
    }
    synchronized void checkedError(int error) {
        if (owner == null || Thread.currentThread() != owner) { end(); return; }
        if (error != 0) { clear(); invalidations++; return; }
        confirmed += pending.size();
        ready.putAll(pending);
        pending.clear();
    }
    synchronized void invalidate(int program) {
        ready.keySet().removeIf(key -> key.program() == program);
        pending.keySet().removeIf(key -> key.program() == program);
        invalidations++;
    }
    synchronized void fault() { end(); invalidations++; }
    synchronized int retained() { return ready.size() + pending.size(); }
    synchronized Map<String, Long> snapshot() {
        return Map.of("hits", hits, "misses", misses, "confirmed", confirmed,
            "frames", frames, "invalidations", invalidations);
    }
}
