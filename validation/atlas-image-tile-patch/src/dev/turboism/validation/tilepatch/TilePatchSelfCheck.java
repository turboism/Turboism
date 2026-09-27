package dev.turboism.validation.tilepatch;

import java.io.InputStream;
import java.util.Map;

/**
 * Offline self-check: fixture g bytes must patch with all anchors; mutated
 * fixtures (missing callsite) must fail closed; wrong owner passes through.
 */
public final class TilePatchSelfCheck {

    public static void main(String[] args) throws Exception {
        byte[] fixture = readClass("com/live2d/util/f/g");

        TilePatchTransformer.Outcome out =
            TilePatchTransformer.patch(fixture, TilePatchTransformer.OWNER);
        check(out.patched(), "fixture g must patch");
        check(out.bytes() != null && out.bytes().length > 0, "patched bytes emitted");
        for (String k : new String[]{"pool.get", "pool.release", "h.edgefill",
                "f.copy", "f.fill", "f.c", "f.f", "i.a", "i.b", "i.c",
                "drawImage", "arraysFill"})
            check(out.anchors().containsKey(k), "anchor recorded: " + k);
        System.out.println("anchors=" + out.anchors());

        // wrong owner passes through
        check(TilePatchTransformer.patch(fixture, "some/Other").bytes() == null,
            "wrong owner must pass through");

        // The 5203 owner carries the identical shape: the e/g fixture must patch
        // under OWNER_5203; the allowlist itself still gates (the transformer's
        // dispatch supplies the class's own internal name, so a non-allowlisted
        // owner can never reach the shape scan).
        byte[] fixture52 = readClass("com/live2d/util/e/g");
        check(fixture52 != null, "e/g fixture missing");
        TilePatchTransformer.Outcome out52 =
            TilePatchTransformer.patch(fixture52, TilePatchTransformer.OWNER_5203);
        check(out52.patched(), "5203 e/g fixture must patch");
        check(out52.anchors().equals(out.anchors()),
            "5203 e/g anchors must equal the 5303 anchor map");
        check(TilePatchTransformer.patch(fixture52, "com/live2d/util/f/h").bytes() == null,
            "a neighbouring non-allowlisted owner must pass through");

        // a class whose method lacks the anchors fails closed
        byte[] alien = readClass("com/live2d/util/f/Alien");
        check(!TilePatchTransformer.patch(alien, TilePatchTransformer.OWNER).patched(),
            "anchor-missing class must NOT patch");

        // page-hash instrumentation on the fixture atlas
        byte[] atlas = readClass(
            "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas");
        check(atlas != null, "fixture atlas missing");
        byte[] hooked = TilePatchTransformer.instrumentPageHash(
            atlas, TilePatchTransformer.ATLAS_OWNER);
        check(hooked != null, "atlas hook must apply");
        check(TilePatchTransformer.instrumentPageHash(atlas, "some/Other") == null,
            "atlas hook must pass through wrong owner");
        // load instrumented class, invoke, expect a hash record
        ClassLoader cl = new ClassLoader(TilePatchSelfCheck.class.getClassLoader()) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.equals("com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) c = defineClass(name, hooked, 0, hooked.length);
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        Object inst = Class.forName(
            "com.live2d.cubism.doc.model.texture.textureAtlas.CTextureAtlas", true, cl)
            .getDeclaredConstructor().newInstance();
        inst.getClass().getMethod("setupCacheImage$cubism",
            boolean.class, com.live2d.util.a.a.class).invoke(inst, true, null);
        // probe should have recorded exactly one hash
        var fld = TilePatchProbe.class.getDeclaredField("PAGE_HASHES");
        fld.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.List<String> hashes =
            (java.util.List<String>) fld.get(null);
        check(hashes.size() == 1 && hashes.get(0).contains("8x8:"),
            "page hash hook must record the produced image, got " + hashes);

        System.out.println("SELFCHECK PASS");
    }

    private static byte[] readClass(String name) throws Exception {
        try (InputStream in = TilePatchSelfCheck.class.getClassLoader()
                .getResourceAsStream(name + ".class")) {
            return in == null ? null : in.readAllBytes();
        }
    }

    private static void check(boolean cond, String msg) {
        if (!cond) { System.err.println("FAIL: " + msg); System.exit(1); }
    }
}
