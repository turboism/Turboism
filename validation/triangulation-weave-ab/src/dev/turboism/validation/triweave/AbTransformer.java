package dev.turboism.validation.triweave;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;

import dev.turboism.validation.kmembership.Weave;
import dev.turboism.validation.triweave.WeaveAbConfig.Target;

/**
 * Gated transformer for the T029 dump+weave A/B agents. Per target-class definition event
 * (bounded to four plus an overflow marker, per class): record sha/loader/codeSource, apply
 * the identity gates, then dispatch on the resolved target role:
 *
 * <ul>
 *   <li>TRIAB ({@code dump-only|dump+weave}): a single target — TriangleList.b() gets the
 *       KWEAVE candidate weave (woven mode) and the return capture (both modes).</li>
 *   <li>DWEAVE ({@code dm-dump-only|dm-dump+weave}): two targets — h.c() gets the
 *       single-allocation-site MatchList weave in woven mode only (no capture on h),
 *       TriangleList.b() gets the identical capture in both modes and no candidate.</li>
 * </ul>
 *
 * In any dump-only mode the candidate transform is never attempted and the candidate helper
 * is never needed. Any transform-level reject on pinned-correct bytes (helper missing in
 * woven mode, capture missing, candidate shape reject, capture shape reject) marks the leg
 * INVALID — the class is returned unmodified and the leg must not be silently graded as
 * baseline.
 */
final class AbTransformer implements ClassFileTransformer {
    private static final int OBSERVE_BUDGET = 4;

    private final WeaveAbConfig config;

    AbTransformer(WeaveAbConfig config) {
        this.config = config;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
            Class<?> redefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        Target t = className == null ? null : targetOf(className);
        if (t == null || classfileBuffer == null) {
            return null;
        }
        int ev = t.events.incrementAndGet();
        if (ev > OBSERVE_BUDGET) {
            if (t.overflow.compareAndSet(false, true)) {
                observe(fields("seq", Integer.toString(ev),
                        "class", className,
                        "overflow", "true", "droppedFurther", "true"));
            }
            return null;
        }
        String sha = sha256(classfileBuffer);
        String codeSource = codeSourceLocation(protectionDomain);
        String moduleName = module == null ? "null" : module.getName();
        observe(fields("seq", Integer.toString(ev),
                "class", className,
                "sha256", sha,
                "loader", loaderToken(loader),
                "module", moduleName,
                "codeSource", codeSource));
        if (!config.expectLoader.equals(loader == null ? "bootstrap"
                : loader.getClass().getName())) {
            return reject(t, ev, "loader", "expected", config.expectLoader);
        }
        if (!t.expectSha.equalsIgnoreCase(sha)) {
            return reject(t, ev, "classSha", "expected", t.expectSha,
                    "note", "sha-diff-is-not-attribution");
        }
        if (!WeaveAbConfig.codeSourceMatches(config.expectCodeSource, codeSource)) {
            return reject(t, ev, "codeSource", "expected", config.expectCodeSource);
        }

        // --- resolvability gates on the TARGET loader (the callsite's resolver) ----------
        if (config.woven() && t.candidateHelperInternal != null
                && !resolvable(t.candidateHelperInternal, loader)) {
            return legInvalid(t, ev, "helper-unavailable");
        }
        if (t.captureInternal != null && !resolvable(t.captureInternal, loader)) {
            return legInvalid(t, ev, "capture-unavailable");
        }

        // --- candidate weave (woven leg only), then the identical capture weave ----------
        byte[] stage = classfileBuffer;
        String candidate = "none";
        if (t.membershipWeave != null || t.matchListWeave != null || t.tliWeave != null) {
            if (config.woven()) {
                byte[] woven;
                String reject;
                if (t.membershipWeave != null) {
                    Weave.Result r = Weave.weaveChecked(t.membershipWeave, stage);
                    woven = r.bytes;
                    reject = r.rejectReason;
                } else if (t.tliWeave != null) {
                    dev.turboism.validation.tlindex.TliWeave.Result r =
                        dev.turboism.validation.tlindex.TliWeave.weaveChecked(
                            t.tliWeave, stage);
                    woven = r.bytes;
                    reject = r.rejectReason;
                } else {
                    dev.turboism.validation.dweave.Weave.Result r =
                        dev.turboism.validation.dweave.Weave.weaveChecked(
                            t.matchListWeave, stage);
                    woven = r.bytes;
                    reject = r.rejectReason;
                }
                if (reject != null) {
                    return legInvalid(t, ev, "weave-reject:" + reject);
                }
                stage = woven;
                candidate = "applied";
            } else {
                candidate = "skipped";
            }
        }
        String capture = "none";
        if (t.captureInternal != null) {
            CaptureWeave.Result c = CaptureWeave.weaveChecked(t.captureMethod,
                t.captureDesc, t.captureInternal, stage);
            if (c.rejectReason != null) {
                return legInvalid(t, ev, "capture-reject:" + c.rejectReason);
            }
            stage = c.bytes;
            capture = "applied";
        }
        observe(fields("seq", Integer.toString(ev),
                "class", className,
                "gate", "accept",
                "mode", config.mode,
                "candidate", candidate,
                "capture", capture));
        return stage == classfileBuffer ? null : stage;
    }

    private Target targetOf(String className) {
        for (Target t : config.targets) {
            if (t.internal.equals(className)) return t;
        }
        return null;
    }

    private byte[] reject(Target t, int ev, String reason, String... extra) {
        observe(fields(rejectKv(t, ev, reason, extra)));
        return null;
    }

    private byte[] legInvalid(Target t, int ev, String reason) {
        Sink.markInvalid(reason);
        observe(fields(rejectKv(t, ev, reason, "legStatus", "INVALID")));
        report("leg-invalid reason=" + reason);
        return null;
    }

    private static String[] rejectKv(Target t, int ev, String reason, String... rest) {
        String[] kv = new String[8 + rest.length];
        kv[0] = "seq"; kv[1] = Integer.toString(ev);
        kv[2] = "class"; kv[3] = t.internal;
        kv[4] = "gate"; kv[5] = "reject";
        kv[6] = "reason"; kv[7] = reason;
        System.arraycopy(rest, 0, kv, 8, rest.length);
        return kv;
    }

    private static boolean resolvable(String internalName, ClassLoader loader) {
        try {
            Class.forName(internalName.replace('/', '.'), false, loader);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void report(String line) {
        System.err.println("[tri-weave] " + line);
    }

    private static String fields(String... kv) {
        StringBuilder sb = new StringBuilder(512);
        for (int i = 0; i + 1 < kv.length; i += 2) {
            Sink.field(sb, kv[i], kv[i + 1]);
        }
        return sb.toString();
    }

    private static String loaderToken(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@"
            + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void observe(String line) {
        try {
            Sink.def(line);
        } catch (Throwable ignored) {
            // Observation is best-effort; never break class loading.
        }
    }

    private static String codeSourceLocation(ProtectionDomain pd) {
        if (pd == null) return "no-pd";
        CodeSource cs = pd.getCodeSource();
        URL loc = cs == null ? null : cs.getLocation();
        return loc == null ? "no-codesource" : loc.toString();
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception e) {
            return "sha-error:" + e.getClass().getSimpleName();
        }
    }
}
