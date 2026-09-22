package dev.turboism.validation.modelupdate;

import java.nio.Buffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Test-only observation of consecutive submitted payloads, NOT a GPU-content cache.
 * No command is suppressed. Complete requested float/integer payloads are compared
 * by raw bits; caller position, limit and backing storage are never modified.
 *
 * ARRAY_BUFFER and explicitly bound ELEMENT_ARRAY_BUFFER payloads are tracked.
 * VAO changes discard the known element binding until another explicit bind.
 * Unobserved/shared-context GPU writers are not ruled out, so
 * even identical payloads are only candidates, never proof that an upload is safe
 * to omit. Native GL errors are observed only when the application queries them.
 */
final class UploadPayloadObserver {
    private static final int ARRAY_BUFFER = 34962;
    private static final int ELEMENT_ARRAY_BUFFER = 34963;
    private final long maxBytes;
    private final int maxEntries;
    private final Map<Integer, Entry> entries = new LinkedHashMap<>(16, 0.75f, true);
    private Object context;
    private int arrayBinding, elementBinding;
    private long retainedBytes, peakRetainedBytes;
    private long observedCalls, comparableCalls, baselineCalls, changedCalls;
    private long duplicates, duplicateBytes, duplicateDelegateNanos, scanNanos;
    private long arrayScanNanos, elementScanNanos;
    private long unknownBindings, unsupportedPayloads, unsupportedTargets;
    private long evictions, invalidations, observerFailures;

    private record Entry(long offset, long bytes, boolean floats, ByteOrder order, int[] words) { }

    UploadPayloadObserver() { this(64L * 1024 * 1024, 4096); }

    UploadPayloadObserver(long maxBytes, int maxEntries) {
        if (maxBytes < 0L || maxEntries < 1) throw new IllegalArgumentException("invalid observation bound");
        this.maxBytes = maxBytes;
        this.maxEntries = maxEntries;
    }

    synchronized void start() {
        clear();
        observedCalls = comparableCalls = baselineCalls = changedCalls = 0L;
        duplicates = duplicateBytes = duplicateDelegateNanos = scanNanos = 0L;
        arrayScanNanos = elementScanNanos = 0L;
        unknownBindings = unsupportedPayloads = unsupportedTargets = 0L;
        evictions = invalidations = observerFailures = peakRetainedBytes = 0L;
    }

    synchronized void stop() { clear(); }

    private void clear() {
        clearEntries();
        context = null;
        arrayBinding = elementBinding = 0;
    }

    private void clearEntries() {
        entries.clear();
        retainedBytes = 0L;
    }

    private void invalidate(int name) {
        Entry old = entries.remove(name);
        if (old != null) retainedBytes -= old.bytes();
        invalidations++;
    }

    /** Returns true only for a complete match to the last observed same-range payload. */
    synchronized boolean before(Object currentContext, String method, Object[] args) {
        if (currentContext == null || currentContext != context) {
            clear();
            context = currentContext;
        }
        if (method.equals("glBindBuffer")) {
            if (integer(args, 0) == ARRAY_BUFFER) arrayBinding = integer(args, 1);
            else if (integer(args, 0) == ELEMENT_ARRAY_BUFFER) elementBinding = integer(args, 1);
            return false;
        }
        if (method.startsWith("glBindVertexArray") || method.startsWith("glDeleteVertexArrays")
            || method.startsWith("glVertexArrayElementBuffer")) {
            // Element binding belongs to VAO state. Never infer a previous VAO's
            // binding or query GL: the next explicit element bind is authoritative.
            elementBinding = 0;
            return false;
        }
        if (method.equals("glDeleteBuffers")) {
            clearEntries();
            arrayBinding = elementBinding = 0;
            invalidations++;
            return false;
        }
        if (method.equals("glBufferData") || method.equals("glBufferStorage")) {
            if (integer(args, 0) == ARRAY_BUFFER) invalidate(arrayBinding);
            else if (integer(args, 0) == ELEMENT_ARRAY_BUFFER) invalidate(elementBinding);
            else { clearEntries(); invalidations++; }
            return false;
        }
        if (method.startsWith("glNamedBuffer") || method.startsWith("glMap")
            || method.startsWith("glUnmap") || method.startsWith("glFlushMapped")
            || method.startsWith("glCopyBuffer") || method.startsWith("glCopyNamedBuffer")
            || method.startsWith("glClearBufferData") || method.startsWith("glClearBufferSubData")
            || method.startsWith("glClearNamedBuffer") || method.startsWith("glInvalidateBuffer")) {
            clearEntries();
            invalidations++;
            return false;
        }
        if (!method.equals("glBufferSubData")) return false;
        observedCalls++;
        int target = integer(args, 0);
        if (target != ARRAY_BUFFER && target != ELEMENT_ARRAY_BUFFER) {
            // This is client-payload observation, not a GPU-state cache. Keep the
            // last observed supported payloads while explicitly counting this gap;
            // clearing every vertex observation on each index upload would conceal
            // cross-frame duplicates. No residency or safe-omission claim follows.
            unsupportedTargets++;
            return false;
        }
        int binding = target == ARRAY_BUFFER ? arrayBinding : elementBinding;
        if (context == null || binding <= 0) { unknownBindings++; return false; }
        long offset = number(args, 1), size = number(args, 2);
        Object payload = args != null && args.length > 3 ? args[3] : null;
        if (!(payload instanceof FloatBuffer || payload instanceof IntBuffer)
            || offset < 0L || size <= 0L || offset > Long.MAX_VALUE - size
            || (size & 3L) != 0L || (offset & 3L) != 0L || size > maxBytes
            || size / Integer.BYTES > Integer.MAX_VALUE
            || size > (long) ((Buffer) payload).remaining() * Integer.BYTES) {
            invalidate(binding);
            unsupportedPayloads++;
            return false;
        }
        long started = System.nanoTime();
        try {
            Buffer buffer = (Buffer) payload;
            boolean floats = payload instanceof FloatBuffer;
            ByteOrder order = floats ? ((FloatBuffer) payload).order() : ((IntBuffer) payload).order();
            int count = (int) (size / Integer.BYTES), position = buffer.position();
            Entry previous = entries.get(binding);
            boolean sameRange = previous != null && previous.offset() == offset && previous.bytes() == size
                && previous.floats() == floats && previous.order().equals(order);
            if (sameRange) {
                comparableCalls++;
                boolean equal = true;
                for (int i = 0; i < count; i++) {
                    if (previous.words()[i] != word(buffer, floats, position + i)) { equal = false; break; }
                }
                if (equal) return true;
                changedCalls++;
                for (int i = 0; i < count; i++) previous.words()[i] = word(buffer, floats, position + i);
                return false;
            }
            baselineCalls++;
            if (previous != null) invalidate(binding);
            while (!entries.isEmpty() && (retainedBytes + size > maxBytes || entries.size() >= maxEntries)) {
                Integer oldest = entries.keySet().iterator().next();
                Entry removed = entries.remove(oldest);
                retainedBytes -= removed.bytes();
                evictions++;
            }
            int[] words = new int[count];
            for (int i = 0; i < count; i++) words[i] = word(buffer, floats, position + i);
            entries.put(binding, new Entry(offset, size, floats, order, words));
            retainedBytes += size;
            peakRetainedBytes = Math.max(peakRetainedBytes, retainedBytes);
            return false;
        } finally {
            long elapsed = System.nanoTime() - started;
            scanNanos += elapsed;
            if (target == ARRAY_BUFFER) arrayScanNanos += elapsed;
            else elementScanNanos += elapsed;
        }
    }

    private static int word(Buffer buffer, boolean floats, int index) {
        return floats ? Float.floatToRawIntBits(((FloatBuffer) buffer).get(index)) : ((IntBuffer) buffer).get(index);
    }

    /** Counts only normally returned calls, without pretending that return proves GL success. */
    synchronized void completed(boolean identical, long bytes, long delegateNanos) {
        if (identical) {
            duplicates++;
            duplicateBytes += bytes;
            duplicateDelegateNanos += delegateNanos;
        }
    }

    synchronized void nativeFailure() {
        clear();
        invalidations++;
    }

    synchronized void observerFailure() {
        clear();
        observerFailures++;
    }

    synchronized boolean failed() { return observerFailures != 0L; }

    private static long number(Object[] args, int index) {
        return args != null && args.length > index && args[index] instanceof Number value ? value.longValue() : -1L;
    }

    private static int integer(Object[] args, int index) { return (int) number(args, index); }

    synchronized String report() {
        return "uploadPayload.enabled=true\n"
            + "uploadPayload.meaning=identical-consecutive-submitted-client-data-only\n"
            + "uploadPayload.gpuResidencyVerified=false\n"
            + "uploadPayload.completeWriteCoverage=false\n"
            + "uploadPayload.performanceAccepted=false\n"
            + "uploadPayload.scope=GL_ARRAY_BUFFER-and-explicit-ELEMENT_ARRAY_BUFFER-full-requested-range\n"
            + "uploadPayload.observedCalls=" + observedCalls + "\n"
            + "uploadPayload.comparableCalls=" + comparableCalls + "\n"
            + "uploadPayload.baselineCalls=" + baselineCalls + "\n"
            + "uploadPayload.changedCalls=" + changedCalls + "\n"
            + "uploadPayload.exactDuplicateCalls=" + duplicates + "\n"
            + "uploadPayload.exactDuplicateBytes=" + duplicateBytes + "\n"
            + "uploadPayload.duplicateDelegateNanos=" + duplicateDelegateNanos + "\n"
            + "uploadPayload.scanNanos=" + scanNanos + "\n"
            + "uploadPayload.arrayScanNanos=" + arrayScanNanos + "\n"
            + "uploadPayload.elementScanNanos=" + elementScanNanos + "\n"
            + "uploadPayload.unknownBindings=" + unknownBindings + "\n"
            + "uploadPayload.unsupportedPayloads=" + unsupportedPayloads + "\n"
            + "uploadPayload.unsupportedTargets=" + unsupportedTargets + "\n"
            + "uploadPayload.evictions=" + evictions + "\n"
            + "uploadPayload.invalidations=" + invalidations + "\n"
            + "uploadPayload.observerFailures=" + observerFailures + "\n"
            + "uploadPayload.maxBytes=" + maxBytes + "\n"
            + "uploadPayload.maxEntries=" + maxEntries + "\n"
            + "uploadPayload.retainedBytes=" + retainedBytes + "\n"
            + "uploadPayload.peakRetainedBytes=" + peakRetainedBytes + "\n";
    }
}
