package dev.turboism.validation.triweave;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import dev.turboism.validation.kmembership.Weave;

/**
 * Gated transformer for the T029-TRIAB A/B agent. Per target definition event (bounded to
 * four plus an overflow marker): record sha/loader/codeSource, apply the identity gates,
 * then
 *
 * <ul>
 *   <li>{@code dump-only}: apply the capture weave only — the candidate transform is never
 *       attempted; Helper is never needed.</li>
 *   <li>{@code dump+weave}: require Helper resolvable through the target loader, apply the
 *       generalized KWEAVE candidate transform, then the identical capture weave.</li>
 * </ul>
 *
 * Any transform-level reject on pinned-correct bytes (helper missing in woven mode, capture
 * missing, candidate shape reject, capture shape reject) marks the leg INVALID — the class
 * is returned unmodified and the leg must not be silently graded as baseline.
 */
final class AbTransformer implements ClassFileTransformer {
    private static final int OBSERVE_BUDGET = 4;

    private final WeaveAbConfig config;
    private final AtomicInteger eventSeq = new AtomicInteger();
    private final AtomicBoolean overflowMarked = new AtomicBoolean();

    AbTransformer(WeaveAbConfig config) {
        this.config = config;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className,
            Class<?> redefined, ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(config.targetInternal)
                || classfileBuffer == null) {
            return null;
        }
        int ev = eventSeq.incrementAndGet();
        if (ev > OBSERVE_BUDGET) {
            if (overflowMarked.compareAndSet(false, true)) {
                observe(fields("seq", Integer.toString(ev),
                        "overflow", "true", "droppedFurther", "true"));
            }
            return null;
        }
        String sha = sha256(classfileBuffer);
        String codeSource = codeSourceLocation(protectionDomain);
        String moduleName = module == null ? "null" : module.getName();
        observe(fields("seq", Integer.toString(ev),
                "sha256", sha,
                "loader", loaderToken(loader),
                "module", moduleName,
                "codeSource", codeSource));
        if (!config.expectLoader.equals(loader == null ? "bootstrap"
                : loader.getClass().getName())) {
            return reject(ev, "loader", "expected", config.expectLoader);
        }
        if (!config.expectClassSha.equalsIgnoreCase(sha)) {
            return reject(ev, "classSha", "expected", config.expectClassSha,
                    "note", "sha-diff-is-not-attribution");
        }
        if (!WeaveAbConfig.codeSourceMatches(config.expectCodeSource, codeSource)) {
            return reject(ev, "codeSource", "expected", config.expectCodeSource);
        }

        // --- resolvability gates on the TARGET loader (the callsite's resolver) ----------
        if (config.woven() && !resolvable(config.weave.helperInternal, loader)) {
            return legInvalid(ev, "helper-unavailable");
        }
        if (!resolvable(config.captureInternal, loader)) {
            return legInvalid(ev, "capture-unavailable");
        }

        // --- candidate weave (woven leg only), then the identical capture weave ----------
        byte[] stage = classfileBuffer;
        if (config.woven()) {
            Weave.Result r = Weave.weaveChecked(config.weave, classfileBuffer);
            if (r.rejectReason != null) {
                return legInvalid(ev, "weave-reject:" + r.rejectReason);
            }
            stage = r.bytes;
        }
        CaptureWeave.Result c = CaptureWeave.weaveChecked(config.weave.methodName,
            config.weave.methodDesc, config.captureInternal, stage);
        if (c.rejectReason != null) {
            return legInvalid(ev, "capture-reject:" + c.rejectReason);
        }
        observe(fields("seq", Integer.toString(ev), "gate", "accept",
                "mode", config.mode,
                "candidate", config.woven() ? "applied" : "skipped",
                "capture", "applied"));
        return c.bytes;
    }

    private byte[] reject(int ev, String reason, String... extra) {
        observe(fields(rejectKv(ev, reason, extra)));
        return null;
    }

    private byte[] legInvalid(int ev, String reason) {
        Sink.markInvalid(reason);
        observe(fields(rejectKv(ev, reason, "legStatus", "INVALID")));
        report("leg-invalid reason=" + reason);
        return null;
    }

    private static String[] rejectKv(int ev, String reason, String... rest) {
        String[] kv = new String[6 + rest.length];
        kv[0] = "seq"; kv[1] = Integer.toString(ev);
        kv[2] = "gate"; kv[3] = "reject";
        kv[4] = "reason"; kv[5] = reason;
        System.arraycopy(rest, 0, kv, 6, rest.length);
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
