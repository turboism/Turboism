package dev.turboism.validation.dweave;

import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Read-only official-shape verification for T029-DWEAVE: streams the h.class
 * bytes out of the reviewed official jar, checks the pinned allocation-site
 * shape inside c()V, runs the SAME weave the host leg would apply, and
 * verifies the result is an operand-only two-instruction change. Never
 * defines or executes the class.
 */
public final class OfficialProbe {
    private OfficialProbe() {}

    /** Reviewed Live2D_Cubism.jar 5.3.03 — same artifact pinned by
     *  T029-IDENTITY / DMATCH / TRIAB. */
    static final String EXPECTED_JAR_SHA256 =
        "bd0a23b9f21a56271d31e6f7f5aed0202661c4fe12444469d093bcdeb4cbf166";
    /** h.class inside that jar (sha256 of the raw class bytes). */
    static final String EXPECTED_CLASS_SHA256 =
        "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d";
    static final String H_INTERNAL =
        "com/live2d/graphics3d/editableMesh/triangulation/h";

    /** The official weave configuration (javap-pinned): c()V, ArrayList()V
     *  site on ASTORE slot 7, two total pattern sites (slots 7 and 8). */
    static final Weave.Config OFFICIAL = new Weave.Config(
        "c", "()V", "java/util/ArrayList", "()V", 7, 2,
        "dev/turboism/validation/dweave/MatchList");

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: OfficialProbe <official-jar>");
            System.exit(2);
        }
        byte[] bytes;
        try (ZipFile zip = new ZipFile(args[0])) {
            ZipEntry e = zip.getEntry(H_INTERNAL + ".class");
            if (e == null) {
                System.out.println("DWEAVE_OFFICIAL_PROBE SKIP entry-not-found");
                return;
            }
            bytes = zip.getInputStream(e).readAllBytes();
        }
        String sha = sha256(bytes);
        Weave.Plan p = Weave.collect(OFFICIAL, bytes);
        Weave.Result r = Weave.weaveChecked(OFFICIAL, bytes);

        System.out.println("classSha256=" + sha);
        System.out.println("expectedSha256=" + EXPECTED_CLASS_SHA256);
        System.out.println("shaMatch=" + sha.equals(EXPECTED_CLASS_SHA256));
        System.out.println("sites=" + p.sites);
        System.out.println("newArrayListTotal=" + p.listTypeNews);
        System.out.println("weave=" + (r.rejectReason == null
            ? "accepted" : "reject:" + r.rejectReason));
        System.out.println("inputBytesUnchanged="
            + sha256(bytes).equals(sha));

        boolean ok = sha.equals(EXPECTED_CLASS_SHA256)
            && r.rejectReason == null;
        if (ok) {
            // operand-only proof: exactly 2 instruction-stream differences,
            // both inside c()V at the recorded site indices.
            List<String> diffs = InsnDiff.diff(bytes, r.bytes);
            Weave.Site t = r.plan.target;
            ok = diffs.size() == 2
                && diffs.get(0).startsWith("c()V#" + t.newIndex + ":")
                && diffs.get(0).contains("java/util/ArrayList")
                && diffs.get(0).contains("dev/turboism/validation/dweave/MatchList")
                && diffs.get(1).startsWith("c()V#" + t.initIndex + ":")
                && diffs.get(1).contains("java/util/ArrayList.<init>()V")
                && diffs.get(1).contains("dev/turboism/validation/dweave/MatchList.<init>()V");
            System.out.println("insnDiffs=" + diffs);
            // woven bytes re-analyzed: site now owned by MatchList; the
            // Phase-4 site (slot 8) untouched; re-running the same config on
            // woven bytes must reject (nothing left to weave).
            Weave.Plan ml = Weave.collect(new Weave.Config("c", "()V",
                "dev/turboism/validation/dweave/MatchList", "()V", -1, -1,
                "dev/turboism/validation/dweave/MatchList"), r.bytes);
            Weave.Plan al = Weave.collect(new Weave.Config("c", "()V",
                "java/util/ArrayList", "()V", -1, -1, ""), r.bytes);
            Weave.Result reweave = Weave.weaveChecked(OFFICIAL, r.bytes);
            ok = ok && ml.sites.size() == 1 && ml.sites.get(0).astoreSlot == 7
                && al.sites.size() == 1 && al.sites.get(0).astoreSlot == 8
                && reweave.rejectReason != null;
            System.out.println("wovenMatchListSites=" + ml.sites);
            System.out.println("wovenArrayListSites=" + al.sites);
            System.out.println("reweave=" + (reweave.rejectReason == null
                ? "accepted" : "reject:" + reweave.rejectReason));
        }
        System.out.println("officialClassLoaded=false");
        System.out.println(ok ? "DWEAVE_OFFICIAL_PROBE PASS"
                              : "DWEAVE_OFFICIAL_PROBE FAIL");
        if (!ok) System.exit(1);
    }

    static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
