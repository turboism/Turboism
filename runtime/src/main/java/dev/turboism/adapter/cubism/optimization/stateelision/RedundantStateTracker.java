package dev.turboism.adapter.cubism.optimization.stateelision;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-context "known GL state" table for the test-only redundant-state elision
 * experiment. State is keyed by the {@code GL4bcImpl} instance (one instance
 * per GLContext in this JOGL) and recorded only at consult time of a call that
 * is about to run natively; every invalidation path — object deletion, VAO /
 * framebuffer / program binding variants, push/pop, display-list replay,
 * cross-thread access, or an exception inside a tracked call — clears the
 * table rather than risk a stale signature. A stale signature would elide a
 * call that changes real state, so every uncertain path fails open.
 *
 * <p>Deletion invalidates globally: a shared-context group can reuse the freed
 * name in another context, so a per-context clear would not be enough. All
 * other invalidators clear only the calling context's table.</p>
 */
public final class RedundantStateTracker {

    /** Default outcome when the armed gate is off or a consult cannot prove equality. */
    public static final int UNKNOWN_TEXTURE_UNIT = -1;

    private static final int SLOT_PROGRAM = 0;
    private static final int SLOT_ENABLE = 1;
    private static final int SLOT_BLEND_FUNC = 2;
    private static final int SLOT_BLEND_EQ = 3;
    private static final int SLOT_CULL = 4;
    private static final int SLOT_FRONT = 5;
    private static final int SLOT_DEPTH_MASK = 6;
    private static final int SLOT_DEPTH_FUNC = 7;
    private static final int SLOT_COLOR_MASK = 8;
    private static final int SLOT_STENCIL_FUNC = 9;
    private static final int SLOT_STENCIL_OP = 10;
    private static final int SLOT_STENCIL_MASK = 11;
    private static final int SLOT_ACTIVE_TEXTURE = 12;
    private static final int SLOT_BIND_TEXTURE = 13;
    private static final int SLOT_BIND_SAMPLER = 14;
    private static final int SLOT_VERTEX_ATTRIB = 15;
    private static final int SLOT_BIND_BUFFER = 16;

    private final ConcurrentHashMap<Object, Context> contexts = new ConcurrentHashMap<>();
    private final AtomicLong epoch = new AtomicLong(1L);
    private final long[] calls = new long[RedundantStateElisionTarget.SITE_NAMES.length];
    private final long[] elided = new long[RedundantStateElisionTarget.SITE_NAMES.length];
    private final long[] passed = new long[RedundantStateElisionTarget.SITE_NAMES.length];
    private final Map<Integer, String> invalidatorNames = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> invalidations = new ConcurrentHashMap<>();
    private long passGate, passNoBaseline, passChanged, passUnknownUnit;
    private long epochClears, contextClears, threadClears, exceptionClears, observerFailures;
    private volatile boolean armed;

    /** Per-GL4bcImpl state: owner thread, last-seen epoch and the signature table. */
    private static final class Context {
        Thread owner;
        long epoch;
        int activeTexture = UNKNOWN_TEXTURE_UNIT;
        final HashMap<Long, long[]> state = new HashMap<>();
        void clear() {
            state.clear();
            activeTexture = UNKNOWN_TEXTURE_UNIT;
        }
    }

    /** Arms/disarms elision. Tracking continues either way so the measured delta is the native-call saving alone. */
    public void setArmed(final boolean value) {
        armed = value;
    }

    /** Whether elision is currently armed. */
    public boolean armed() {
        return armed;
    }

    /** Registers the site-id → method-name mapping used for invalidation stats. */
    public void registerInvalidator(final int site, final String name) {
        invalidatorNames.put(site, name);
    }

    /**
     * Consulted at the entry of a tracked setter: returns true when the
     * recorded signature for this context+key equals the call arguments. A
     * pass records the signature before the native call runs; a thrown call
     * clears the context through {@link #exception(Object)}.
     */
    public boolean consult(final Object gl, final int site,
                           final int a0, final int a1, final int a2, final int a3) {
        try {
            final Context ctx = contexts.computeIfAbsent(gl, key -> {
                final Context created = new Context();
                created.owner = Thread.currentThread();
                created.epoch = epoch.get();
                return created;
            });
            synchronized (ctx) {
                calls[site]++;
                if (!armed) {
                    passGate++;
                    record(ctx, site, a0, a1, a2, a3);
                    return false;
                }
                if (ctx.owner != Thread.currentThread()) {
                    ctx.clear();
                    ctx.owner = Thread.currentThread();
                    threadClears++;
                }
                final long current = epoch.get();
                if (ctx.epoch != current) {
                    ctx.clear();
                    ctx.epoch = current;
                    epochClears++;
                }
                final long key = key(ctx, site, a0, a1);
                if (key < 0L) {
                    passUnknownUnit++;
                    passed[site]++;
                    return false;
                }
                final long[] sig = signature(site, a0, a1, a2, a3);
                final long[] known = ctx.state.get(key);
                if (known != null && Arrays.equals(known, sig)) {
                    elided[site]++;
                    return true;
                }
                if (known == null) passNoBaseline++; else passChanged++;
                ctx.state.put(key, sig);
                if (site == 15) ctx.activeTexture = a0;
                passed[site]++;
                return false;
            }
        } catch (final Throwable observerFailure) {
            observerFailures++;
            return false;
        }
    }

    /**
     * Invalidation entry point: {@code glDelete*} names bump the global epoch
     * (shared-group name reuse), every other invalidator clears only the
     * calling context's table.
     */
    public void invalidate(final Object gl, final int site) {
        try {
            final String name = invalidatorNames.getOrDefault(site, "site-" + site);
            invalidations.computeIfAbsent(name, key -> new AtomicLong()).incrementAndGet();
            if (name.startsWith("glDelete")) {
                epoch.incrementAndGet();
                return;
            }
            final Context ctx = contexts.get(gl);
            if (ctx != null) {
                synchronized (ctx) {
                    ctx.clear();
                    contextClears++;
                }
            }
        } catch (final Throwable observerFailure) {
            observerFailures++;
        }
    }

    /** A tracked call threw: the signature recorded at entry may not be applied — clear the context. */
    public void exception(final Object gl) {
        try {
            final Context ctx = contexts.get(gl);
            if (ctx != null) {
                synchronized (ctx) {
                    ctx.clear();
                    exceptionClears++;
                }
            }
        } catch (final Throwable observerFailure) {
            observerFailures++;
        }
    }

    /** Records the signature without an elision decision (disarmed consults). */
    private void record(final Context ctx, final int site,
                        final int a0, final int a1, final int a2, final int a3) {
        final long key = key(ctx, site, a0, a1);
        if (key < 0L) return;
        ctx.state.put(key, signature(site, a0, a1, a2, a3));
        if (site == 15) ctx.activeTexture = a0;
        passed[site]++;
    }

    /** Domain key for the consult: {@code slot << 40 | aux}, or -1 when unkeyable. */
    private static long key(final Context ctx, final int site, final int a0, final int a1) {
        final long aux;
        switch (site) {
            case 0: case 3: case 4: case 5: case 6: case 7: case 8: case 9:
            case 10: case 11: case 12: case 13: case 14: case 15:
                aux = 0L;
                break;
            case 1: case 2:   // enable/disable: keyed by capability
            case 18: case 19: // vertex-attrib array enable: keyed by index
                aux = a0 & 0xFFFFFFFFFFL;
                break;
            case 16: {        // texture binding: keyed by (active unit, target)
                if (ctx.activeTexture == UNKNOWN_TEXTURE_UNIT) return -1L;
                aux = ((ctx.activeTexture & 0xFFFFFL) << 20) | (a0 & 0xFFFFFL);
                break;
            }
            case 17:          // sampler binding: keyed by unit
            case 20:          // buffer binding: keyed by target
                aux = a0 & 0xFFFFFFFFFFL;
                break;
            default:
                return -1L;
        }
        return (slotFor(site) << 40) | aux;
    }

    private static long slotFor(final int site) {
        switch (site) {
            case 0: return SLOT_PROGRAM;
            case 1: case 2: return SLOT_ENABLE;
            case 3: case 4: return SLOT_BLEND_FUNC;
            case 5: case 6: return SLOT_BLEND_EQ;
            case 7: return SLOT_CULL;
            case 8: return SLOT_FRONT;
            case 9: return SLOT_DEPTH_MASK;
            case 10: return SLOT_DEPTH_FUNC;
            case 11: return SLOT_COLOR_MASK;
            case 12: return SLOT_STENCIL_FUNC;
            case 13: return SLOT_STENCIL_OP;
            case 14: return SLOT_STENCIL_MASK;
            case 15: return SLOT_ACTIVE_TEXTURE;
            case 16: return SLOT_BIND_TEXTURE;
            case 17: return SLOT_BIND_SAMPLER;
            case 18: case 19: return SLOT_VERTEX_ATTRIB;
            case 20: return SLOT_BIND_BUFFER;
            default: throw new IllegalArgumentException("site " + site);
        }
    }

    /** Exact argument signature (normalized for alias forms like glBlendFunc). */
    private static long[] signature(final int site,
                                    final int a0, final int a1, final int a2, final int a3) {
        return switch (site) {
            case 3 -> pack(a0, a1, a0, a1);            // glBlendFunc(s,d) == separate(s,d,s,d)
            case 5 -> pack(a0, 0, a0, 0);              // glBlendEquation(m) == separate(m,m)
            case 6 -> pack(a0, 0, a1, 0);              // separate(m,n) normalized for the alias
            case 1, 18 -> pack(1, 0, 0, 0);            // glEnable / glEnableVertexAttribArray
            case 2, 19 -> pack(0, 0, 0, 0);            // glDisable / glDisableVertexAttribArray
            case 16 -> pack(a1, 0, 0, 0);              // texture name (unit+target in key)
            case 17, 20 -> pack(a1, 0, 0, 0);          // sampler/buffer name (unit/target in key)
            default -> pack(a0, a1, a2, a3);
        };
    }

    private static long[] pack(final int a0, final int a1, final int a2, final int a3) {
        return new long[]{((long) a0 << 32) | (a1 & 0xFFFFFFFFL),
            ((long) a2 << 32) | (a3 & 0xFFFFFFFFL)};
    }

    /** Snapshot of counters/gauges for leg reporting and the close marker. */
    public Map<String, Long> snapshot(final boolean currentArmed) {
        final Map<String, Long> stats = new TreeMap<>();
        long totalCalls = 0L, totalElided = 0L, totalPassed = 0L, entries = 0L;
        for (int site = 0; site < RedundantStateElisionTarget.SITE_NAMES.length; site++) {
            final String prefix = RedundantStateElisionTarget.SITE_NAMES[site];
            stats.put(prefix + "Calls", calls[site]);
            stats.put(prefix + "Elided", elided[site]);
            stats.put(prefix + "Passed", passed[site]);
            totalCalls += calls[site];
            totalElided += elided[site];
            totalPassed += passed[site];
        }
        for (final Context ctx : contexts.values()) {
            synchronized (ctx) {
                entries += ctx.state.size();
            }
        }
        for (final var entry : invalidations.entrySet()) {
            stats.put(entry.getKey() + "Invalidations", entry.getValue().get());
        }
        stats.put("armed", currentArmed ? 1L : 0L);
        stats.put("calls", totalCalls);
        stats.put("elided", totalElided);
        stats.put("passed", totalPassed);
        stats.put("passGate", passGate);
        stats.put("passNoBaseline", passNoBaseline);
        stats.put("passChanged", passChanged);
        stats.put("passUnknownUnit", passUnknownUnit);
        stats.put("entries", entries);
        stats.put("contexts", (long) contexts.size());
        stats.put("epochClears", epochClears);
        stats.put("contextClears", contextClears);
        stats.put("threadClears", threadClears);
        stats.put("exceptionClears", exceptionClears);
        stats.put("observerFailures", observerFailures);
        return stats;
    }
}
