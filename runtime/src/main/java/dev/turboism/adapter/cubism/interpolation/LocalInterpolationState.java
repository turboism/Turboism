package dev.turboism.adapter.cubism.interpolation;

import dev.turboism.core.runtime.work.FatalErrors;
import java.util.List;

/** Invocation-owned fixed-point candidate. Published cache entries contain only its detached key/mask. */
final class LocalInterpolationState {
    final InterpolationCurveCache.Key key;
    boolean[] actualWrites;
    private final int writes;
    private final int[] input;
    private final int targetStart;
    private final LocalFormState state;

    private LocalInterpolationState(long domain, int[] input, int targetStart, LocalFormState state, int writes) {
        this.input = input;
        this.targetStart = targetStart;
        this.state = state;
        key = InterpolationCurveCache.Key.owned(domain, input);
        this.writes = writes;
    }

    static LocalInterpolationState create(
            LocalInterpolationCache cache,
            long domain,
            int[] geometry,
            LocalFormState state,
            List<?> targets,
            List<?> sources,
            float[][] dst)
            throws Throwable {
        if (state == null || geometry == null) return null;
        int count = targets.size(), size = dst[0].length;
        long writes = (long) count * size / 2;
        long words = 4L + geometry.length + (long) sources.size() * state.width + (long) count * (size + state.width);
        if (256L + 4L * words + writes > LocalInterpolationCache.MAX_ENTRY_BYTES
                || !state.independentColors(targets, sources)) return null;
        int[] input = cache.borrowInput((int) words);
        int at = 0;
        input[at++] = state.tag;
        input[at++] = state.width;
        input[at++] = count;
        input[at++] = size;
        System.arraycopy(geometry, 0, input, at, geometry.length);
        at += geometry.length;
        try {
            for (Object form : sources) at = state.append(form, input, at);
            int targetStart = at;
            for (int i = 0; i < count; i++) {
                for (float coordinate : dst[i]) input[at++] = Float.floatToRawIntBits(coordinate);
                at = state.append(targets.get(i), input, at);
            }
            return new LocalInterpolationState(domain, input, targetStart, state, (int) writes);
        } catch (Throwable failure) {
            cache.release(input);
            FatalErrors.rethrowIfFatal(failure);
            if (LocalFormState.nonFinite(failure)) return null;
            throw failure;
        }
    }

    void capture(LocalInterpolationCache cache) {
        actualWrites = cache.borrowWrites(writes);
    }

    void release(LocalInterpolationCache cache) {
        cache.release(input);
        cache.release(actualWrites);
    }

    void completed(LocalInterpolationCache cache, List<?> targets, float[][] dst) throws Throwable {
        int at = targetStart;
        try {
            for (int i = 0; i < targets.size(); i++) {
                for (float coordinate : dst[i]) if (input[at++] != Float.floatToRawIntBits(coordinate)) return;
                if (!state.matches(targets.get(i), input, at)) return;
                at += state.width;
            }
        } catch (Throwable failure) {
            FatalErrors.rethrowIfFatal(failure);
            if (LocalFormState.nonFinite(failure)) return;
            throw failure;
        }
        cache.put(key, actualWrites);
    }
}
