package dev.turboism.validation.triweave;

import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import dev.turboism.validation.kmembership.Weave;

/**
 * Read-only official-shape verification: streams the class bytes from the jar, runs the SAME
 * shape analyses the transformer would apply, prints the digests and every check result.
 * Covers both namespaces: the TRIAB candidate weave + capture weave on TriangleList, and
 * the DWEAVE single-allocation-site weave on h plus the capture weave on TriangleList.
 * Never defines or executes a class.
 */
public final class OfficialShapeProbe {
    private OfficialShapeProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OfficialShapeProbe <official-jar>");
            System.exit(2);
        }
        boolean triOk = false;
        boolean dmOk = false;
        try (ZipFile zip = new ZipFile(args[0])) {
            byte[] tlBytes = entry(zip, WeaveAbConfig.OFFICIAL_TARGET_INTERNAL);
            if (tlBytes != null) triOk = triab(tlBytes);
            byte[] hBytes = entry(zip, WeaveAbConfig.OFFICIAL_H_INTERNAL);
            if (hBytes != null) dmOk = dweave(hBytes, tlBytes);
        }
        System.out.println("officialClassLoaded=false");
        boolean ok = triOk && dmOk;
        System.out.println(ok ? "TRI_WEAVE_OFFICIAL_PROBE PASS triab=true dweave=true"
                              : "TRI_WEAVE_OFFICIAL_PROBE FAIL");
        if (!ok) System.exit(1);
    }

    private static byte[] entry(ZipFile zip, String internal) throws Exception {
        ZipEntry e = zip.getEntry(internal + ".class");
        if (e == null) {
            System.out.println(internal + " entry-not-found");
            return null;
        }
        return zip.getInputStream(e).readAllBytes();
    }

    /** TRIAB: TriangleList sha + membership weave + capture weave (+post-weave capture). */
    private static boolean triab(byte[] bytes) {
        String sha = AbTransformer.sha256(bytes);
        Weave.Result weave = Weave.weaveChecked(WeaveAbConfig.OFFICIAL_WEAVE, bytes);
        CaptureWeave.Result capture = CaptureWeave.weaveChecked(
            WeaveAbConfig.OFFICIAL_CAPTURE_METHOD,
            WeaveAbConfig.OFFICIAL_CAPTURE_DESC,
            WeaveAbConfig.OFFICIAL_CAPTURE_INTERNAL, bytes);
        CaptureWeave.Result captureAfterWeave = weave.rejectReason == null
            ? CaptureWeave.weaveChecked(WeaveAbConfig.OFFICIAL_CAPTURE_METHOD,
                WeaveAbConfig.OFFICIAL_CAPTURE_DESC,
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
        boolean ok = sha.equals(WeaveAbConfig.OFFICIAL_CLASS_SHA256)
            && weave.rejectReason == null && capture.rejectReason == null
            && captureAfterWeave != null && captureAfterWeave.rejectReason == null;
        System.out.println(ok ? "TRIAB_PROBE PASS" : "TRIAB_PROBE FAIL");
        return ok;
    }

    /** DWEAVE: h.class sha + double-pin weave acceptance; the woven bytes re-analyzed
     *  must show a MatchList site on the pinned slot, a leftover ArrayList site on the
     *  second slot, and must reject a re-weave (nothing left to weave). */
    private static boolean dweave(byte[] hBytes, byte[] tlBytes) {
        String sha = AbTransformer.sha256(hBytes);
        dev.turboism.validation.dweave.Weave.Config cfg = WeaveAbConfig.OFFICIAL_DM_WEAVE;
        dev.turboism.validation.dweave.Weave.Plan plan =
            dev.turboism.validation.dweave.Weave.collect(cfg, hBytes);
        dev.turboism.validation.dweave.Weave.Result r =
            dev.turboism.validation.dweave.Weave.weaveChecked(cfg, hBytes);
        System.out.println("dm.classSha256=" + sha);
        System.out.println("dm.expectedSha256=" + WeaveAbConfig.OFFICIAL_H_CLASS_SHA256);
        System.out.println("dm.shaMatch=" + sha.equals(WeaveAbConfig.OFFICIAL_H_CLASS_SHA256));
        System.out.println("dm.sites=" + plan.sites);
        System.out.println("dm.newArrayListTotal=" + plan.listTypeNews);
        System.out.println("dm.weave=" + (r.rejectReason == null
            ? "accepted" : "reject:" + r.rejectReason));
        boolean ok = sha.equals(WeaveAbConfig.OFFICIAL_H_CLASS_SHA256)
            && r.rejectReason == null;
        if (ok) {
            dev.turboism.validation.dweave.Weave.Plan ml =
                dev.turboism.validation.dweave.Weave.collect(
                    new dev.turboism.validation.dweave.Weave.Config("c", "()V",
                        WeaveAbConfig.OFFICIAL_DM_WEAVE.matchListInternal, "()V", -1, -1,
                        WeaveAbConfig.OFFICIAL_DM_WEAVE.matchListInternal), r.bytes);
            dev.turboism.validation.dweave.Weave.Plan al =
                dev.turboism.validation.dweave.Weave.collect(
                    new dev.turboism.validation.dweave.Weave.Config("c", "()V",
                        "java/util/ArrayList", "()V", -1, -1, ""), r.bytes);
            dev.turboism.validation.dweave.Weave.Result reweave =
                dev.turboism.validation.dweave.Weave.weaveChecked(cfg, r.bytes);
            ok = ml.sites.size() == 1 && ml.sites.get(0).astoreSlot == 7
                && al.sites.size() == 1 && al.sites.get(0).astoreSlot == 8
                && reweave.rejectReason != null;
            System.out.println("dm.wovenMatchListSites=" + ml.sites);
            System.out.println("dm.wovenArrayListSites=" + al.sites);
            System.out.println("dm.reweave=" + (reweave.rejectReason == null
                ? "accepted" : "reject:" + reweave.rejectReason));
        }
        // The dm capture target is the same TriangleList b() capture proven above.
        if (tlBytes != null) {
            CaptureWeave.Result dmCapture = CaptureWeave.weaveChecked(
                WeaveAbConfig.OFFICIAL_CAPTURE_METHOD,
                WeaveAbConfig.OFFICIAL_CAPTURE_DESC,
                WeaveAbConfig.OFFICIAL_CAPTURE_INTERNAL, tlBytes);
            System.out.println("dm.captureWeave=" + (dmCapture.rejectReason == null
                ? "accepted" : "reject:" + dmCapture.rejectReason));
            ok = ok && dmCapture.rejectReason == null;
        }
        System.out.println(ok ? "DWEAVE_PROBE PASS" : "DWEAVE_PROBE FAIL");
        return ok;
    }
}
