package dev.turboism.validation.modelupdate;

import java.nio.FloatBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * Validation-only exact uniform-value experiment, scoped to one display/context/thread.
 * Bindings and values become reusable only after an existing native GL_NO_ERROR.
 * No GL commands or error queries are issued here. Shared-context writers remain
 * outside this experiment's admission; this is not a production state cache.
 */
final class UniformValueCache {
    private record Key(int program, int location) { }
    private record Value(String method, boolean transpose, int[] words) {
        boolean matches(String operation, Object[] args) {
            if (!method.equals(operation)) return false;
            if (!method.equals("glUniformMatrix4fv")) {
                if (args.length != words.length + 1) return false;
                for (int i = 0; i < words.length; i++) {
                    Object item = args[i + 1];
                    if (method.equals("glUniform1i")) {
                        if (!(item instanceof Integer n) || words[i] != n) return false;
                    } else if (!(item instanceof Float n)
                        || words[i] != Float.floatToRawIntBits(n)) return false;
                }
                return true;
            }
            if (args.length < 4 || !(args[1] instanceof Integer count) || count != 1
                || !(args[2] instanceof Boolean transposed) || transpose != transposed) return false;
            if (args.length == 4 && args[3] instanceof FloatBuffer buffer && buffer.remaining() >= 16) {
                for (int i = 0; i < 16; i++) {
                    if (words[i] != Float.floatToRawIntBits(buffer.get(buffer.position() + i))) return false;
                }
                return true;
            }
            if (args.length == 5 && args[3] instanceof float[] data && args[4] instanceof Integer offset
                && offset >= 0 && offset <= data.length - 16) {
                for (int i = 0; i < 16; i++) {
                    if (words[i] != Float.floatToRawIntBits(data[offset + i])) return false;
                }
                return true;
            }
            return false;
        }
    }
    private final int bound;
    private final Map<Key, Value> ready = new HashMap<>(), pending = new HashMap<>();
    private Object context;
    private Thread owner;
    private int program;
    private boolean requestedProgram, trustedProgram;
    private long hits, misses, confirmed, invalidations, unsupported, overflows;

    UniformValueCache(int bound) {
        if (bound < 1) throw new IllegalArgumentException("invalid bound");
        this.bound = bound;
    }
    synchronized void begin(Object current) {
        end(); context = current; owner = current == null ? null : Thread.currentThread();
    }
    synchronized void end() {
        ready.clear(); pending.clear(); context = null; owner = null;
        program = 0; requestedProgram = trustedProgram = false;
    }
    synchronized void fault() { end(); invalidations++; }
    private boolean owns(Object current) {
        if (owner == null) return false;
        if (current != context || Thread.currentThread() != owner) { fault(); return false; }
        return true;
    }
    private void invalidate(int id) {
        ready.keySet().removeIf(key -> key.program() == id);
        pending.keySet().removeIf(key -> key.program() == id);
        invalidations++;
    }

    /** True means omit this exact already-confirmed scalar or single-matrix write. */
    synchronized boolean before(Object current, String method, Object[] args) {
        if (!owns(current)) return false;
        if (method.equals("glUseProgram")) {
            if (args == null || args.length != 1 || !(args[0] instanceof Integer id)) { fault(); return false; }
            program = id; requestedProgram = true; trustedProgram = false;
            return false;
        }
        if (method.equals("glLinkProgram") || method.equals("glProgramBinary") || method.equals("glDeleteProgram")) {
            if (args == null || args.length == 0 || !(args[0] instanceof Integer id)) { fault(); return false; }
            invalidate(id);
            if (id == program) trustedProgram = false;
            if (method.equals("glDeleteProgram") && id == program) { program = 0; requestedProgram = false; }
            return false;
        }
        if (method.startsWith("glProgramUniform") || method.equals("glBindProgramPipeline")
            || method.equals("glActiveShaderProgram") || method.equals("glUseProgramStages")) {
            fault(); return false;
        }
        if (!method.startsWith("glUniform") || program <= 0) return false;
        if (args == null || args.length < 2 || !(args[0] instanceof Integer location)) {
            invalidate(program); unsupported++; return false;
        }
        if (location < 0) return false;
        Key key = new Key(program, location);
        Value previous = trustedProgram ? ready.get(key) : null;
        // Compare against the input view before allocating a replacement snapshot.
        // This remains a complete raw-bit comparison, never sampling or hashing.
        if (previous != null && previous.matches(method, args)) { hits++; return true; }
        Value value = value(method, args);
        if (value == null) { invalidate(program); unsupported++; return false; }
        misses++;
        // Invalidate BEFORE an attempted write: a later error must never leave an
        // older confirmed value eligible after an unconfirmed replacement.
        ready.remove(key); pending.remove(key);
        if (ready.size() + pending.size() >= bound) { overflows++; return false; }
        pending.put(key, value);
        return false;
    }

    private static Value value(String method, Object[] args) {
        int count = switch (method) {
            case "glUniform1i", "glUniform1f" -> 1;
            case "glUniform2f" -> 2;
            case "glUniform4f" -> 4;
            default -> 0;
        };
        if (count > 0) {
            if (args.length != count + 1) return null;
            int[] words = new int[count];
            for (int i = 0; i < count; i++) {
                if (method.equals("glUniform1i")) {
                    if (!(args[i + 1] instanceof Integer v)) return null;
                    words[i] = v;
                } else {
                    if (!(args[i + 1] instanceof Float v)) return null;
                    words[i] = Float.floatToRawIntBits(v);
                }
            }
            return new Value(method, false, words);
        }
        if (!method.equals("glUniformMatrix4fv") || args.length < 4
            || !(args[1] instanceof Integer matrices) || matrices != 1
            || !(args[2] instanceof Boolean transpose)) return null;
        int[] words = new int[16];
        if (args.length == 4 && args[3] instanceof FloatBuffer buffer && buffer.remaining() >= 16) {
            for (int i = 0; i < 16; i++) words[i] = Float.floatToRawIntBits(buffer.get(buffer.position() + i));
        } else if (args.length == 5 && args[3] instanceof float[] data && args[4] instanceof Integer offset
            && offset >= 0 && offset <= data.length - 16) {
            for (int i = 0; i < 16; i++) words[i] = Float.floatToRawIntBits(data[offset + i]);
        } else return null;
        return new Value(method, transpose, words);
    }

    synchronized void checkedError(int error) {
        if (owner == null) return;
        if (Thread.currentThread() != owner) { fault(); return; }
        if (error != 0) {
            ready.clear(); pending.clear(); program = 0;
            requestedProgram = trustedProgram = false; invalidations++; return;
        }
        if (requestedProgram && program > 0) trustedProgram = true;
        confirmed += pending.size(); ready.putAll(pending); pending.clear();
    }
    synchronized int retained() { return ready.size() + pending.size(); }
    synchronized Map<String, Long> snapshot() {
        return Map.of("hits", hits, "misses", misses, "confirmed", confirmed,
            "invalidations", invalidations, "unsupported", unsupported, "overflows", overflows);
    }
}
