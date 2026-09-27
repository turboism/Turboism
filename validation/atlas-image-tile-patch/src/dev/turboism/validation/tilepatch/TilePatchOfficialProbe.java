package dev.turboism.validation.tilepatch;

import java.io.InputStream;
import java.security.MessageDigest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Non-execution probe: read the reviewed workaround class from the official JAR
 * ({@code com/live2d/util/f/g} on 5.3.x, {@code com/live2d/util/e/g} on 5.2.03),
 * verify the shape anchors and that the transformer produces patched bytes.
 * Prints class sha256 + anchor map for evidence.
 */
public final class TilePatchOfficialProbe {

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: TilePatchOfficialProbe <cubism.jar> [5303|5203]");
            System.exit(2);
        }
        final String profile = args.length >= 2 ? args[1] : "5303";
        final String owner = switch (profile) {
            case "5303" -> TilePatchTransformer.OWNER;
            case "5203" -> TilePatchTransformer.OWNER_5203;
            default -> { System.err.println("unknown profile: " + profile); System.exit(2); yield ""; }
        };
        final String entry = owner + ".class";
        final String binaryName = owner.replace('/', '.');
        byte[] bytes;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry(entry);
            if (e == null) { System.err.println("class not found: " + entry); System.exit(1); }
            try (InputStream in = zip.getInputStream(e)) {
                bytes = in.readAllBytes();
            }
        }
        String sha = sha256(bytes);
        TilePatchTransformer.Outcome out = TilePatchTransformer.patch(bytes, owner);
        System.out.println("profile=" + profile);
        System.out.println("classSha256=" + sha);
        System.out.println("anchors=" + out.anchors());
        System.out.println("patched=" + out.patched());
        if (!out.patched()) {
            System.err.println("OFFICIAL JAR FAILED SHAPE GATE");
            System.exit(1);
        }
        String patchedSha = sha256(out.bytes());
        System.out.println("patchedSha256=" + patchedSha);

        // Frame-retention gate: the public 8-arg method has branch targets
        // (VerifyError "Expecting a stackmap frame at branch target" on the
        //  real host if a transform strips them). Re-scan patched bytes and
        //  require that method to still carry stackmap frames.
        final int[] frames = {0};
        new org.objectweb.asm.ClassReader(out.bytes()).accept(
            new org.objectweb.asm.ClassVisitor(org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(
                        int access, String name, String desc, String sig, String[] ex) {
                    if (!name.equals("a") || !desc.equals(
                            "(Ljava/awt/image/BufferedImage;Ljava/awt/Graphics;Ljava/awt/image/BufferedImage;IIDZ)V")) {
                        return null;
                    }
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitFrame(int type, int nLocal, Object[] local,
                                               int nStack, Object[] stack) {
                            frames[0]++;
                        }
                    };
                }
            }, 0);
        System.out.println("publicMethodFrames=" + frames[0]);
        if (frames[0] == 0) {
            System.err.println("PATCHED CLASS LOST STACKMAP FRAMES");
            System.exit(1);
        }

        // Strongest offline gate: define the PATCHED official class in a
        // child-first loader backed by the real JAR so the JVM verifier
        // (-Xverify:all) resolves genuine jp.noids / UtCache types. Mirrors
        // the host failure mode exactly. Execute nothing — the delegate class
        // is intentionally absent here (it arrives via system classpath in
        // the real run; resolution is lazy at first call).
        java.net.URL jarUrl = new java.io.File(args[0]).toURI().toURL();
        java.net.URLClassLoader jarLoader = new java.net.URLClassLoader(
            new java.net.URL[]{jarUrl}, TilePatchOfficialProbe.class.getClassLoader());
        ClassLoader child = new ClassLoader(jarLoader) {
            @Override
            protected Class<?> loadClass(String name, boolean resolve)
                    throws ClassNotFoundException {
                if (name.equals(binaryName)) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> c = findLoadedClass(name);
                        if (c == null) {
                            c = defineClass(name, out.bytes(), 0, out.bytes().length);
                        }
                        if (resolve) resolveClass(c);
                        return c;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
        try {
            Class.forName(binaryName, false, child);
            System.out.println("officialVerify=PASS");
        } catch (Throwable t) {
            System.out.println("officialVerify=FAIL " + t);
            System.err.println("PATCHED OFFICIAL CLASS FAILED VERIFICATION");
            System.exit(1);
        }

        // atlas page-hash hook must apply to the official CTextureAtlas
        byte[] atlasBytes;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry(
                "com/live2d/cubism/doc/model/texture/textureAtlas/CTextureAtlas.class");
            try (InputStream in = zip.getInputStream(e)) {
                atlasBytes = in.readAllBytes();
            }
        }
        byte[] hooked = TilePatchTransformer.instrumentPageHash(
            atlasBytes, TilePatchTransformer.ATLAS_OWNER);
        System.out.println("atlasSha256=" + sha256(atlasBytes));
        System.out.println("atlasHookApplied=" + (hooked != null));
        if (hooked == null) {
            System.err.println("OFFICIAL ATLAS HOOK DID NOT APPLY");
            System.exit(1);
        }
        System.out.println("PROBE PASS");
    }

    private static String sha256(byte[] b) throws Exception {
        byte[] d = MessageDigest.getInstance("SHA-256").digest(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : d) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
