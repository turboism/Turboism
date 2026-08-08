package dev.turboism.adapter.cubism.performance;

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

    public long mask() {
        return 1L << id();
    }

    static PerformanceProbeMetric byId(final int id) {
        return ALL[id];
    }
}
