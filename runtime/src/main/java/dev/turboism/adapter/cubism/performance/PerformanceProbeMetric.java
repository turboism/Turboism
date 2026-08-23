package dev.turboism.adapter.cubism.performance;

/**
 * The Cubism render-path phases the probe times, each with its own sampling rate.
 *
 * <p>Hot inner phases are sampled rather than measured on every call - traversal every
 * 16th call and renderer dispatch every 64th - so the probe own cost stays small on
 * the render thread; the outer phases are timed on every call. Ordinals are the wire
 * ids the woven bytecode passes, so reordering constants changes instrumented code.</p>
 */
public enum PerformanceProbeMetric {
    RENDER_SCENE(1),
    MODELING_PRE_RENDER_UPDATE(1),
    RENDER_SYSTEM(1),
    SCENE_TRAVERSAL(16),
    RENDERER_DISPATCH(64),
    UPDATE_MODEL_INSTANCES(1),
    REINIT_MODEL_INSTANCE_EXE(1);

    private static final PerformanceProbeMetric[] ALL = values();

    private final int sampleEvery;

    PerformanceProbeMetric(final int sampleEvery) {
        this.sampleEvery = sampleEvery;
    }

    int sampleEvery() {
        return sampleEvery;
    }

    int id() {
        return ordinal();
    }

    /**
     * @return a single-bit mask identifying this metric ({@code 1L << ordinal}), for
     *     packing a set of metrics into one long
     */
    public long mask() {
        return 1L << id();
    }

    static PerformanceProbeMetric byId(final int id) {
        return ALL[id];
    }
}
