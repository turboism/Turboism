package dev.turboism.validation.triweave.shadow;

import java.util.Objects;

/** Shadow analog of the official triangle type l: three edge getters; a getter may fault. */
public class ShadowL {
    private final ShadowJ d, e, f;
    private final int faultAt;                 // 0=none, 1=d, 2=e, 3=f
    private final RuntimeException fault;

    public ShadowL(ShadowJ d, ShadowJ e, ShadowJ f) { this(d, e, f, 0, null); }

    public ShadowL(ShadowJ d, ShadowJ e, ShadowJ f, int faultAt, RuntimeException fault) {
        this.d = d; this.e = e; this.f = f; this.faultAt = faultAt; this.fault = fault;
    }

    private void maybeThrow(int which) {
        if (which == faultAt) throw Objects.requireNonNull(fault);
    }

    public ShadowJ d() { maybeThrow(1); return d; }
    public ShadowJ e() { maybeThrow(2); return e; }
    public ShadowJ f() { maybeThrow(3); return f; }
}
