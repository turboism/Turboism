package dev.turboism.validation.triweave;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.turboism.validation.kmembership.Weave;

/**
 * Read-only official-shape verification: streams the class bytes from the jar, runs the SAME
 * shape analysis the transformer would apply (candidate weave + capture weave), prints the
 * digest and every check result. Never defines or executes the class.
 */
public final class OfficialShapeProbe {
    private OfficialShapeProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OfficialShapeProbe <official-jar>");
            System.exit(2);
        }
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry(WeaveAbConfig.OFFICIAL_TARGET_INTERNAL + ".class");
            if (e == null) {
                System.out.println("TRI_WEAVE_OFFICIAL_PROBE SKIP entry-not-found");
                return;
            }
            byte[] bytes = zip.getInputStream(e).readAllBytes();
            String sha = AbTransformer.sha256(bytes);
            Weave.Result weave = Weave.weaveChecked(WeaveAbConfig.OFFICIAL_WEAVE, bytes);
            CaptureWeave.Result capture = CaptureWeave.weaveChecked(
                WeaveAbConfig.OFFICIAL_WEAVE.methodName,
                WeaveAbConfig.OFFICIAL_WEAVE.methodDesc,
                WeaveAbConfig.OFFICIAL_CAPTURE_INTERNAL, bytes);
            CaptureWeave.Result captureAfterWeave = weave.rejectReason == null
                ? CaptureWeave.weaveChecked(WeaveAbConfig.OFFICIAL_WEAVE.methodName,
                    WeaveAbConfig.OFFICIAL_WEAVE.methodDesc,
                    WeaveAbConfig.OFFICIAL_CAPTURE_INTERNAL, weave.bytes)
                : null;
            System.out.println("classSha256=" + sha);
            System.out.println("expectedSha256=" + WeaveAbConfig.OFFICIAL_CLASS_SHA256);
            System.out.println("shaMatch=" + sha.equals(WeaveAbConfig.OFFICIAL_CLASS_SHA256));
            System.out.println("candidateWeave=" + (weave.rejectReason == null
                ? "accepted" : "reject:" + weave.rejectReason));
            System.out.println("captureWeave=" + (capture.rejectReason == null
                ? "accepted" : "reject:" + capture.rejectReason));
            System.out.println("captureAfterWeave=" + (captureAfterWeave == null
                ? "not-run" : captureAfterWeave.rejectReason == null
                    ? "accepted" : "reject:" + captureAfterWeave.rejectReason));
            System.out.println("officialClassLoaded=false");
            if (sha.equals(WeaveAbConfig.OFFICIAL_CLASS_SHA256)
                    && weave.rejectReason == null && capture.rejectReason == null
                    && captureAfterWeave != null && captureAfterWeave.rejectReason == null) {
                System.out.println("TRI_WEAVE_OFFICIAL_PROBE PASS");
            } else {
                System.out.println("TRI_WEAVE_OFFICIAL_PROBE FAIL");
                System.exit(1);
            }
        }
    }
}
