package dev.turboism.validation.triprobe;

import java.lang.instrument.ClassFileTransformer;
import java.net.URL;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.ProtectionDomain;
import java.util.HexFormat;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Observes each definition event for the target owner (bounded to four events + one overflow
 * marker), then applies loader/SHA/CodeSource-exact/shape gates and, on full acceptance, returns
 * the iterator-weaved bytes. A SHA mismatch records the observation and rejects without any
 * attribution claim.
 */
final class DefinitionObserver implements ClassFileTransformer {
    private static final int OBSERVE_BUDGET = 4;

    private final ProbeConfig config;
    private final AtomicInteger eventSeq = new AtomicInteger();
    private final AtomicBoolean overflowMarked = new AtomicBoolean();

    DefinitionObserver(ProbeConfig config) {
        this.config = config;
    }

    @Override
    public byte[] transform(Module module, ClassLoader loader, String className, Class<?> redefined,
            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(ProbeConfig.TARGET_INTERNAL)
                || classfileBuffer == null) {
            return null;
        }
        int ev = eventSeq.incrementAndGet();
        if (ev > OBSERVE_BUDGET) {
            if (overflowMarked.compareAndSet(false, true)) {
                observe("seq=" + ev + " overflow=true droppedFurther=true");
            }
            return null;
        }
        String sha = sha256(classfileBuffer);
        String loaderTok = loaderToken(loader);
        String codeSource = codeSourceLocation(protectionDomain);
        String moduleName = module == null ? "null" : module.getName();
        observe("seq=" + ev + " sha256=" + sha + " loader=" + loaderTok
            + " module=" + moduleName + " codeSource=" + codeSource);
        if (!config.expectLoader.equals(loader == null ? "bootstrap" : loader.getClass().getName())) {
            observe("seq=" + ev + " gate=reject reason=loader expected=" + config.expectLoader);
            return null;
        }
        if (!config.expectClassSha.equalsIgnoreCase(sha)) {
            observe("seq=" + ev + " gate=reject reason=classSha expected="
                + config.expectClassSha + " note=sha-diff-is-not-attribution");
            return null;
        }
        if (!config.codeSourceMatches(codeSource)) {
            observe("seq=" + ev + " gate=reject reason=codeSource expected="
                + config.expectCodeSource);
            return null;
        }
        TriangleListShape.Result shape = TriangleListShape.check(classfileBuffer);
        if (!shape.accepted) {
            observe("seq=" + ev + " gate=reject reason=" + shape.reason);
            return null;
        }
        try {
            byte[] woven = IteratorWeave.weave(classfileBuffer);
            observe("seq=" + ev + " gate=accept weave=applied");
            return woven;
        } catch (Throwable t) {
            observe("seq=" + ev + " gate=reject reason=weave-error:"
                + t.getClass().getSimpleName());
            return null;
        }
    }

    private static String loaderToken(ClassLoader loader) {
        if (loader == null) return "bootstrap";
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static void observe(String line) {
        try {
            Probe.defineObserved(line);
        } catch (Throwable ignored) {
            // Helper unavailable: observation is best-effort; never break class loading.
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
