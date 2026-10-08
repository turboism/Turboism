package dev.turboism.validation.triweave;

import java.nio.file.Path;
import java.util.regex.Pattern;

import dev.turboism.validation.kmembership.Weave;
import dev.turboism.validation.tlindex.TliWeave;

/**
 * System-property contract for the T029 dump+weave agents. Two independent
 * property namespaces share this config:
 *
 * <ul>
 *   <li>{@code turboism.validation.triWeave.*} — T029-TRIAB: the candidate weave and
 *       the return capture both target {@code TriangleList.b()Lk;}.</li>
 *   <li>{@code turboism.validation.dmWeave.*} — T029-DWEAVE: the candidate weave
 *       targets {@code h.c()V} (single {@code new ArrayList} -> MatchList), while
 *       the SAME capture still observes {@code TriangleList.b()Lk;} — a two-class
 *       target set.</li>
 *   <li>{@code turboism.validation.tlWeave.*} — T029-TLINDEX: the candidate weave
 *       rewrites four TriangleList methods (a(l)/b(l)/c() mutators + the a(j)
 *       tryQuery prepend with original-scan fallback), while the SAME capture
 *       observes {@code TriangleList.b()Lk;} — a one-class target like TRIAB.</li>
 * </ul>
 *
 * Enabling more than one namespace is an admission conflict and refuses
 * installation.
 * Every field needed for a gated run is mandatory and validated; invalid
 * admission config must refuse installation, never throw out of premain.
 *
 * Target constants (owner / descriptor / pinned shape) are compile-time
 * constants derived from javap evidence on the reviewed official jar — see
 * DESIGN.md files of triangulation-weave-ab and triangulation-dweave. The only
 * target variation is the {@code *-selfcheck} test profile that lets the offline
 * fixtures drive the SAME code path under their own owner names.
 */
public final class WeaveAbConfig {
    static final String TRI_PREFIX = "turboism.validation.triWeave.";
    static final String DM_PREFIX = "turboism.validation.dmWeave.";
    static final String TLI_PREFIX = "turboism.validation.tlWeave.";

    /** Property key names relative to each namespace prefix. */
    static final String ENABLED = "enabled";
    static final String MODE = "mode";
    static final String PHASE = "phase";
    static final String OUTPUT_DIR = "outputDir";
    static final String RUN_ID = "runId";
    /** SHA-256 of the candidate-weave class (TriangleList for TRIAB, h for DWEAVE). */
    static final String EXPECT_CLASS_SHA = "expectClassSha256";
    /** SHA-256 of the captured class when it differs from the weave class
     *  (DWEAVE only; for TRIAB it must be absent or equal to expectClassSha256). */
    static final String EXPECT_CAPTURE_SHA = "expectCaptureClassSha256";
    static final String EXPECT_LOADER = "expectLoader";
    static final String EXPECT_CODESOURCE = "expectCodeSource";
    static final String CAPTURE_N = "captureN";
    /** Test-only: selects the shadow-fixture target set. Never part of host admission. */
    static final String PROFILE = "profile";

    public static final String MODE_DUMP_ONLY = "dump-only";
    public static final String MODE_DUMP_WEAVE = "dump+weave";
    public static final String MODE_DM_DUMP_ONLY = "dm-dump-only";
    public static final String MODE_DM_DUMP_WEAVE = "dm-dump+weave";
    public static final String MODE_TL_DUMP_ONLY = "tl-dump-only";
    public static final String MODE_TL_DUMP_WEAVE = "tl-dump+weave";

    /** Official 5.3.03 TriangleList.class sha256 (jar bd0a23b9...) — same digest the
     *  T029-IDENTITY probe pinned. */
    static final String OFFICIAL_CLASS_SHA256 =
        "87835641dbc03a7a25ff302dd4f7c74eb9c1ac95b1e1f3a1bc987b9cf833fe29";

    static final String OFFICIAL_TARGET_INTERNAL =
        "com/live2d/graphics3d/editableMesh/triangulation/TriangleList";
    /** javap-verified: {@code b()Lk;}, k ctor {@code ()V}, query {@code k.a(Lj;Z)Z},
     *  append {@code k.a(Lj;)Z}, iterator {@code java/util/LinkedHashSet.iterator}. */
    static final Weave.Config OFFICIAL_WEAVE = new Weave.Config(
        "b",
        "com/live2d/graphics3d/editableMesh/triangulation/k",
        "com/live2d/graphics3d/editableMesh/triangulation/j",
        "a", "a",
        "dev/turboism/validation/triweave/Helper");
    static final String OFFICIAL_CAPTURE_INTERNAL =
        "dev/turboism/validation/triweave/Capture";
    static final String OFFICIAL_CAPTURE_METHOD = "b";
    static final String OFFICIAL_CAPTURE_DESC =
        "()Lcom/live2d/graphics3d/editableMesh/triangulation/k;";

    /** Official 5.3.03 h.class sha256 — javap/unzip read-only derived, never the
     *  TriangleList digest. Same pin as triangulation-dweave/OfficialProbe. */
    static final String OFFICIAL_H_INTERNAL =
        "com/live2d/graphics3d/editableMesh/triangulation/h";
    static final String OFFICIAL_H_CLASS_SHA256 =
        "5aa7031e3726355fde25d6d4412f0a295a3725cb8e510a3076007f3270445f0d";
    /** javap-pinned: c()V, ArrayList()V site on ASTORE slot 7, exactly two pattern
     *  sites total (slots 7 and 8); only slot 7 is woven. */
    static final dev.turboism.validation.dweave.Weave.Config OFFICIAL_DM_WEAVE =
        new dev.turboism.validation.dweave.Weave.Config(
            "c", "()V", "java/util/ArrayList", "()V", 7, 2,
            "dev/turboism/validation/dweave/MatchList");

    /** T029-TLINDEX official binding — javap-verified on the pinned 5.3.03 jar:
     *  l getters {@code a()/b()/c()L TriPoint;}, j getters {@code a()/b()L TriPoint;},
     *  {@code TriPoint.getIndex()I}, LinkedHashSet field {@code b}. The Bridge
     *  helper is JDK-only and ships inside the agent jar. */
    static final TliWeave.Config OFFICIAL_TLI_WEAVE = new TliWeave.Config(
        OFFICIAL_TARGET_INTERNAL,
        "com/live2d/graphics3d/editableMesh/triangulation/j",
        "com/live2d/graphics3d/editableMesh/triangulation/l",
        "com/live2d/graphics3d/editableMesh/triangulation/TriPoint",
        "dev/turboism/validation/tlindex/Bridge",
        "b");

    static final String PROFILE_OFFICIAL = "official";
    static final String PROFILE_SHADOW = "shadow-selfcheck";
    static final String PROFILE_DM_OFFICIAL = "dm-official";
    static final String PROFILE_DM_SHADOW = "dm-shadow-selfcheck";
    static final String PROFILE_TL_OFFICIAL = "tl-official";
    static final String PROFILE_TL_SHADOW = "tl-shadow-selfcheck";

    static final String SHADOW_TARGET_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowTriangleList";
    /** Shadow target names differ on purpose: this proves the weave is driven by the
     *  Config, not by hardcoded fixture names. */
    static final Weave.Config SHADOW_WEAVE = new Weave.Config(
        "produce",
        "dev/turboism/validation/triweave/shadow/ShadowK",
        "dev/turboism/validation/triweave/shadow/ShadowJ",
        "has", "add",
        "dev/turboism/validation/triweave/shadow/ShadowHelper");
    static final String SHADOW_CAPTURE_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowCapture";
    static final String SHADOW_CAPTURE_METHOD = "produce";
    static final String SHADOW_CAPTURE_DESC =
        "()Ldev/turboism/validation/triweave/shadow/ShadowK;";

    /** TLINDEX shadow target: the tlindex own fixture, driven under its own
     *  names through the SAME TliWeave implementation. */
    static final String SHADOW_TL_INTERNAL =
        "dev/turboism/validation/tlindex/own/OwnTri$TList";
    static final TliWeave.Config SHADOW_TLI_WEAVE = new TliWeave.Config(
        SHADOW_TL_INTERNAL,
        "dev/turboism/validation/tlindex/own/OwnTri$E",
        "dev/turboism/validation/tlindex/own/OwnTri$L",
        "dev/turboism/validation/tlindex/own/OwnTri$Pt",
        "dev/turboism/validation/tlindex/Bridge",
        "b");

    static final String SHADOW_H_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowH";
    static final String SHADOW_MATCHLIST_INTERNAL =
        "dev/turboism/validation/triweave/shadow/ShadowMatchList";
    /** Pinned ASTORE slot of the ShadowH Phase-3 site (javap-verified on the
     *  fixture build); second site lives on a different slot, total sites = 2. */
    static final int SHADOW_H_PINNED_SLOT = 1;
    static final dev.turboism.validation.dweave.Weave.Config SHADOW_DM_WEAVE =
        new dev.turboism.validation.dweave.Weave.Config(
            "c", "()V", "java/util/ArrayList", "()V", SHADOW_H_PINNED_SLOT, 2,
            SHADOW_MATCHLIST_INTERNAL);

    static final String DEFAULT_LOADER = "jdk.internal.loader.ClassLoaders$AppClassLoader";
    static final int MAX_CAPTURE = 4;
    static final int DEFAULT_CAPTURE = 4;

    static final Pattern RUN_ID_OK = Pattern.compile("[A-Za-z0-9._-]{1,32}");
    static final Pattern SHA_OK = Pattern.compile("[0-9a-fA-F]{64}");

    /** One class the transformer watches: identity gate + optional candidate weave
     *  + optional return capture. */
    public static final class Target {
        public final String internal;
        public final String expectSha;
        /** KWEAVE membership transform (TRIAB); mutually exclusive with matchListWeave. */
        public final Weave.Config membershipWeave;
        /** DWEAVE single-allocation-site transform; mutually exclusive with membershipWeave. */
        public final dev.turboism.validation.dweave.Weave.Config matchListWeave;
        /** TLINDEX four-method edge-index transform; mutually exclusive with both others. */
        public final TliWeave.Config tliWeave;
        public final String captureMethod;
        public final String captureDesc;
        public final String captureInternal;
        /** Helper internal name the woven path must resolve (null when no weave). */
        public final String candidateHelperInternal;
        /** Per-class definition-event counter for the observe budget. */
        final java.util.concurrent.atomic.AtomicInteger events =
            new java.util.concurrent.atomic.AtomicInteger();
        /** Per-class overflow marker (one line per class). */
        final java.util.concurrent.atomic.AtomicBoolean overflow =
            new java.util.concurrent.atomic.AtomicBoolean();

        Target(String internal, String expectSha, Weave.Config membershipWeave,
                dev.turboism.validation.dweave.Weave.Config matchListWeave,
                String captureMethod, String captureDesc, String captureInternal) {
            this(internal, expectSha, membershipWeave, matchListWeave, null,
                captureMethod, captureDesc, captureInternal);
        }

        Target(String internal, String expectSha, Weave.Config membershipWeave,
                dev.turboism.validation.dweave.Weave.Config matchListWeave,
                TliWeave.Config tliWeave,
                String captureMethod, String captureDesc, String captureInternal) {
            this.internal = internal;
            this.expectSha = expectSha;
            this.membershipWeave = membershipWeave;
            this.matchListWeave = matchListWeave;
            this.tliWeave = tliWeave;
            this.captureMethod = captureMethod;
            this.captureDesc = captureDesc;
            this.captureInternal = captureInternal;
            this.candidateHelperInternal = membershipWeave != null
                ? membershipWeave.helperInternal
                : matchListWeave != null ? matchListWeave.matchListInternal
                : tliWeave != null ? tliWeave.bridgeInternal : null;
        }
    }

    public final boolean enabled;
    /** Both namespaces enabled — ambiguous admission, refuse installation. */
    public final boolean namespaceConflict;
    /** True when the dmWeave namespace drove this config. */
    public final boolean dm;
    /** True when the tlWeave namespace drove this config. */
    public final boolean tl;
    public final String mode;
    public final String phase;
    public final String outputDir;
    public final String runId;
    public final String profile;
    public final String expectClassSha;
    public final String expectCaptureClassSha;
    public final String expectLoader;
    public final String expectCodeSource;
    public final int captureN;

    /** Resolved target set — the only source of transform shape. */
    public final Target[] targets;
    /** Candidate helper of this profile's weave target (Params/helperLinked + premain warm). */
    public final String candidateHelperInternal;

    public WeaveAbConfig() {
        boolean triEnabled = "true".equals(System.getProperty(TRI_PREFIX + ENABLED));
        boolean dmEnabled = "true".equals(System.getProperty(DM_PREFIX + ENABLED));
        boolean tlEnabled = "true".equals(System.getProperty(TLI_PREFIX + ENABLED));
        this.enabled = triEnabled || dmEnabled || tlEnabled;
        int on = (triEnabled ? 1 : 0) + (dmEnabled ? 1 : 0) + (tlEnabled ? 1 : 0);
        this.namespaceConflict = on > 1;
        this.dm = dmEnabled;
        this.tl = tlEnabled;
        String prefix = tl ? TLI_PREFIX : dm ? DM_PREFIX : TRI_PREFIX;

        this.mode = System.getProperty(prefix + MODE, "");
        this.phase = System.getProperty(prefix + PHASE, "unlabelled");
        this.outputDir = System.getProperty(prefix + OUTPUT_DIR, "");
        this.runId = System.getProperty(prefix + RUN_ID, "");
        this.profile = System.getProperty(prefix + PROFILE,
            tl ? PROFILE_TL_OFFICIAL : dm ? PROFILE_DM_OFFICIAL : PROFILE_OFFICIAL);
        this.expectClassSha = System.getProperty(prefix + EXPECT_CLASS_SHA,
            defaultWeaveSha());
        this.expectCaptureClassSha = System.getProperty(prefix + EXPECT_CAPTURE_SHA,
            defaultCaptureSha());
        this.expectLoader = System.getProperty(prefix + EXPECT_LOADER,
            shadowProfile() ? "" : DEFAULT_LOADER);
        this.expectCodeSource = System.getProperty(prefix + EXPECT_CODESOURCE, "");
        int n;
        try {
            n = Integer.parseInt(System.getProperty(prefix + CAPTURE_N,
                Integer.toString(DEFAULT_CAPTURE)));
        } catch (NumberFormatException e) {
            n = -1;
        }
        this.captureN = n;
        this.targets = buildTargets();
        String helper = null;
        for (Target t : targets) {
            if (t.candidateHelperInternal != null) { helper = t.candidateHelperInternal; break; }
        }
        this.candidateHelperInternal = helper;
    }

    private boolean shadowProfile() {
        return PROFILE_SHADOW.equals(profile) || PROFILE_DM_SHADOW.equals(profile)
            || PROFILE_TL_SHADOW.equals(profile);
    }

    private String defaultWeaveSha() {
        if (shadowProfile()) return "";
        return dm ? OFFICIAL_H_CLASS_SHA256 : OFFICIAL_CLASS_SHA256;
    }

    private String defaultCaptureSha() {
        // TRIAB + TLINDEX: the captured class IS the weave class — same sha in
        // every profile, so expectCaptureClassSha256 only needs to be passed to
        // diverge (refused).
        if (!dm) return expectClassSha;
        if (PROFILE_DM_SHADOW.equals(profile)) return "";
        // DWEAVE: capture is on TriangleList — independent identity digest.
        return OFFICIAL_CLASS_SHA256;
    }

    private Target[] buildTargets() {
        if (dm) {
            if (PROFILE_DM_SHADOW.equals(profile)) {
                return new Target[] {
                    new Target(SHADOW_H_INTERNAL, expectClassSha, null, SHADOW_DM_WEAVE,
                        null, null, null),
                    new Target(SHADOW_TARGET_INTERNAL, expectCaptureClassSha, null, null,
                        SHADOW_CAPTURE_METHOD, SHADOW_CAPTURE_DESC, SHADOW_CAPTURE_INTERNAL),
                };
            }
            return new Target[] {
                new Target(OFFICIAL_H_INTERNAL, expectClassSha, null, OFFICIAL_DM_WEAVE,
                    null, null, null),
                new Target(OFFICIAL_TARGET_INTERNAL, expectCaptureClassSha, null, null,
                    OFFICIAL_CAPTURE_METHOD, OFFICIAL_CAPTURE_DESC, OFFICIAL_CAPTURE_INTERNAL),
            };
        }
        if (tl) {
            if (PROFILE_TL_SHADOW.equals(profile)) {
                // fixture target: weave only — the tlindex WeaveSelfCheck covers
                // output parity at class level; the agent leg proves plumbing.
                return new Target[] {
                    new Target(SHADOW_TL_INTERNAL, expectClassSha, null, null,
                        SHADOW_TLI_WEAVE, null, null, null),
                };
            }
            return new Target[] {
                new Target(OFFICIAL_TARGET_INTERNAL, expectClassSha, null, null,
                    OFFICIAL_TLI_WEAVE,
                    OFFICIAL_CAPTURE_METHOD, OFFICIAL_CAPTURE_DESC, OFFICIAL_CAPTURE_INTERNAL),
            };
        }
        boolean shadow = PROFILE_SHADOW.equals(profile);
        return new Target[] {
            new Target(
                shadow ? SHADOW_TARGET_INTERNAL : OFFICIAL_TARGET_INTERNAL,
                expectClassSha,
                shadow ? SHADOW_WEAVE : OFFICIAL_WEAVE,
                null,
                shadow ? SHADOW_CAPTURE_METHOD : OFFICIAL_CAPTURE_METHOD,
                shadow ? SHADOW_CAPTURE_DESC : OFFICIAL_CAPTURE_DESC,
                shadow ? SHADOW_CAPTURE_INTERNAL : OFFICIAL_CAPTURE_INTERNAL),
        };
    }

    /**
     * Admission validation; returns a reject reason or null. Missing/invalid config is a safe
     * refusal — the premain reports it and installs nothing.
     */
    String validate() {
        if (!enabled) return "disabled";
        if (namespaceConflict) return "namespaces-conflict";
        boolean profileOk = tl
            ? (PROFILE_TL_OFFICIAL.equals(profile) || PROFILE_TL_SHADOW.equals(profile))
            : dm
            ? (PROFILE_DM_OFFICIAL.equals(profile) || PROFILE_DM_SHADOW.equals(profile))
            : (PROFILE_OFFICIAL.equals(profile) || PROFILE_SHADOW.equals(profile));
        if (!profileOk) return "profile-invalid";
        String weaveMode = tl ? MODE_TL_DUMP_WEAVE : dm ? MODE_DM_DUMP_WEAVE : MODE_DUMP_WEAVE;
        String onlyMode = tl ? MODE_TL_DUMP_ONLY : dm ? MODE_DM_DUMP_ONLY : MODE_DUMP_ONLY;
        if (!onlyMode.equals(mode) && !weaveMode.equals(mode))
            return mode.isEmpty() ? "mode-missing" : "mode-invalid";
        if (outputDir.isEmpty()) return "outputDir-missing";
        try {
            Path p = Path.of(outputDir);
            if (!p.isAbsolute()) return "outputDir-not-absolute";
        } catch (RuntimeException e) {
            return "outputDir-invalid:" + e.getClass().getSimpleName();
        }
        if (runId.isEmpty() || !RUN_ID_OK.matcher(runId).matches()) return "runId-invalid";
        if (!SHA_OK.matcher(expectClassSha).matches()) return "expectClassSha256-invalid";
        if (!SHA_OK.matcher(expectCaptureClassSha).matches())
            return "expectCaptureClassSha256-invalid";
        if (!dm && !expectClassSha.equalsIgnoreCase(expectCaptureClassSha))
            return "expectCaptureClassSha256-conflict";   // tri+tl: weave target IS capture target
        if (expectLoader.isEmpty()) return "expectLoader-missing";
        if (expectCodeSource.isEmpty()) return "expectCodeSource-missing";
        if (normalizeCodeSource(expectCodeSource) == null) return "expectCodeSource-invalid";
        if (captureN < 1 || captureN > MAX_CAPTURE) return "captureN-out-of-range";
        return null;
    }

    boolean woven() {
        return MODE_DUMP_WEAVE.equals(mode) || MODE_DM_DUMP_WEAVE.equals(mode)
            || MODE_TL_DUMP_WEAVE.equals(mode);
    }

    /** True for the official (non-fixture) profiles of any namespace. */
    boolean officialProfile() {
        return PROFILE_OFFICIAL.equals(profile) || PROFILE_DM_OFFICIAL.equals(profile)
            || PROFILE_TL_OFFICIAL.equals(profile);
    }

    /**
     * CodeSource comparison rule — identical to the T029-IDENTITY contract: real URI parsing,
     * absolute {@code file} scheme only, no authority/user-info/port/query/fragment, no dot
     * segments, canonical form {@code file:<rawPath>}.
     */
    static String normalizeCodeSource(String externalForm) {
        if (externalForm == null) return null;
        java.net.URI uri;
        try {
            uri = new java.net.URI(externalForm);
        } catch (java.net.URISyntaxException e) {
            return null;
        }
        if (!uri.isAbsolute()) return null;
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("file")) return null;
        String authority = uri.getRawAuthority();
        if (authority != null && !authority.isEmpty()) return null; // file://host/... rejected
        if (uri.getRawUserInfo() != null || uri.getPort() != -1
                || uri.getRawQuery() != null || uri.getRawFragment() != null) {
            return null;
        }
        String path = uri.getRawPath();
        if (path == null || !path.startsWith("/") || path.length() < 2) return null;
        for (String seg : path.split("/")) {
            if (seg.equals(".") || seg.equals("..")) return null;
        }
        return "file:" + path;
    }

    /** Exact canonical-form equality on both sides; invalid input never matches. */
    static boolean codeSourceMatches(String expectedExternalForm, String actualExternalForm) {
        String expected = normalizeCodeSource(expectedExternalForm);
        String actual = normalizeCodeSource(actualExternalForm);
        return expected != null && expected.equals(actual);
    }

    Path definitionLog() {
        return Path.of(outputDir, "tri-weave-def-" + runId + ".log");
    }

    Path dumpLog() {
        return Path.of(outputDir, "tri-weave-dump-" + runId + ".log");
    }

    Path statusFile() {
        return Path.of(outputDir, "tri-weave-status-" + runId + ".txt");
    }
}
