package dev.turboism.validation.triprobe;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Read-only official-shape verification: streams the class bytes from the jar, runs the same
 * structural gate the transformer would apply, prints the digest. Never defines or executes. */
public final class OfficialShapeProbe {
    private OfficialShapeProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OfficialShapeProbe <official-jar>");
            System.exit(2);
        }
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry(ProbeConfig.TARGET_INTERNAL + ".class");
            if (e == null) {
                System.out.println("TRI_IDENTITY_OFFICIAL_PROBE SKIP entry-not-found");
                return;
            }
            byte[] bytes = zip.getInputStream(e).readAllBytes();
            String sha = DefinitionObserver.sha256(bytes);
            TriangleListShape.Result shape = TriangleListShape.check(bytes);
            System.out.println("classSha256=" + sha);
            System.out.println("expectedSha256=" + ProbeConfig.OFFICIAL_CLASS_SHA256);
            System.out.println("shaMatch=" + sha.equals(ProbeConfig.OFFICIAL_CLASS_SHA256));
            System.out.println("shape=" + shape.reason + " accepted=" + shape.accepted);
            System.out.println("officialClassLoaded=false");
            if (sha.equals(ProbeConfig.OFFICIAL_CLASS_SHA256) && shape.accepted) {
                System.out.println("TRI_IDENTITY_OFFICIAL_PROBE PASS");
            } else {
                System.out.println("TRI_IDENTITY_OFFICIAL_PROBE FAIL");
                System.exit(1);
            }
        }
    }
}
