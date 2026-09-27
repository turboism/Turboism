package dev.turboism.validation.tilepatch;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.nio.file.Path;
import java.security.ProtectionDomain;

/**
 * Validation-only agent for the tile-scratch kernel optimization.
 *
 * <p>Modes ({@code turboism.validation.tilePatch.mode}):</p>
 * <ul>
 *   <li>{@code hashOnly} — instruments {@code CTextureAtlas.setupCacheImage$cubism}
 *       to SHA-256 the produced page image at return. Pipeline untouched:
 *       the unpatched reference run.</li>
 *   <li>{@code tiled} — same page-hash instrumentation PLUS the body of
 *       {@code f.g.a(BI,Graphics2D,BI,int,int)} (or {@code e.g.a} on 5.2) replaced
 *       by {@link TiledDrawDelegate}. Page hashes must match the hashOnly run.</li>
 * </ul>
 *
 * <p>Explicit opt-in only; the shape gate fails closed.</p>
 */
public final class TilePatchAgent {

    public static final String OPT_IN_PROP = "turboism.validation.tilePatch.optIn";
    public static final String OPT_IN_TOKEN = "TILE_PATCH_EXPLICIT_OPT_IN";
    public static final String OUTPUT_PROP = "turboism.validation.tilePatch.output";
    public static final String MODE_PROP = "turboism.validation.tilePatch.mode";

    private TilePatchAgent() {
    }

    public static void premain(final String args, final Instrumentation inst) {
        final String token = System.getProperty(OPT_IN_PROP, "");
        if (!OPT_IN_TOKEN.equals(token)) {
            System.err.println("[tilepatch] not opted in ("
                + (token.isEmpty() ? "missing" : "bad token") + ") — inactive");
            return;
        }
        final String out = System.getProperty(OUTPUT_PROP, "");
        if (!out.isEmpty()) TilePatchProbe.setOutputDir(Path.of(out));
        final String mode = System.getProperty(MODE_PROP, "tiled");
        final boolean patchKernel = mode.equals("tiled");
        System.err.println("[tilepatch] mode=" + mode
            + (patchKernel ? " (kernel delegate enabled)" : " (hash-only reference)"));

        inst.addTransformer(new ClassFileTransformer() {
            @Override
            public byte[] transform(final Module module, final ClassLoader loader,
                                    final String className, final Class<?> classBeingRedefined,
                                    final ProtectionDomain protectionDomain,
                                    final byte[] classfileBuffer) {
                if (TilePatchTransformer.ATLAS_OWNER.equals(className)) {
                    byte[] out = TilePatchTransformer.instrumentPageHash(
                        classfileBuffer, className);
                    if (out != null) {
                        System.err.println("[tilepatch] page-hash hook installed on " + className
                            + " loader=" + String.valueOf(loader)
                            + " retransform=" + (classBeingRedefined != null)
                            + " inSha=" + Integer.toHexString(
                                java.util.Arrays.hashCode(classfileBuffer))
                            + " outSha=" + Integer.toHexString(java.util.Arrays.hashCode(out)));
                        // Same evidence channel as the production transformer: when a
                        // definition bypasses earlier transformers only the call stack
                        // says which host path triggered it.
                        final String ev = System.getProperty(
                            "turboism.atlasCacheReuse.evidenceFile");
                        if (ev != null && !ev.isEmpty()) {
                            try {
                                final java.io.StringWriter sink = new java.io.StringWriter();
                                new Throwable("tilepatch define-stack "
                                    + Integer.toHexString(
                                        java.util.Arrays.hashCode(classfileBuffer)))
                                    .printStackTrace(new java.io.PrintWriter(sink));
                                java.nio.file.Files.writeString(
                                    java.nio.file.Path.of(ev),
                                    sink + System.lineSeparator(),
                                    java.nio.file.StandardOpenOption.CREATE,
                                    java.nio.file.StandardOpenOption.APPEND);
                            } catch (Throwable ignored) {
                                // Diagnostics only.
                            }
                        }
                    }
                    return out;
                }
                if (!patchKernel || !(TilePatchTransformer.OWNER.equals(className)
                        || TilePatchTransformer.OWNER_5203.equals(className)))
                    return null;
                TilePatchTransformer.Outcome out =
                    TilePatchTransformer.patch(classfileBuffer, className);
                if (!out.patched()) {
                    System.err.println("[tilepatch] shape gate FAILED on " + className
                        + " anchors=" + out.anchors() + " — class left unmodified");
                    return null;
                }
                System.err.println("[tilepatch] patched " + className
                    + " private workaround -> bbox delegate; anchors=" + out.anchors());
                return out.bytes();
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (!out.isEmpty()) TilePatchProbe.writeSummary(Path.of(out));
        }, "tilepatch-summary"));
        System.err.println("[tilepatch] active");
    }
}
