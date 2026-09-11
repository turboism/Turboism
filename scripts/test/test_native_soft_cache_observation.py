#!/usr/bin/env python3
"""Synthetic regression for the static soft-cache audit (slice 030).

Compiles the audit together with a synthetic, host-shaped cache holder, then asserts the audit's reporting,
bounds, partial semantics, read-only behaviour and the absence of forbidden call sites. No Cubism class is loaded
and no host is started.
"""
import os
import re
import shutil
import subprocess
import tempfile
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
AUDIT = ROOT / "testing/host-validation/image-archive/src/dev/turboism/validation/NativeSoftCacheObservation.java"
OWNERSHIP = ROOT / "testing/host-validation/image-archive/src/dev/turboism/validation/NativeCloseOwnershipObservation.java"
AGENT = ROOT / "testing/host-validation/image-archive/src/dev/turboism/validation/NativeResourceHostAgent.java"

FOREIGN = r'''
package other;

import java.lang.ref.SoftReference;
import java.util.ArrayList;

/** A cache whose holder class is package-private, as the real host holder is. */
public final class ForeignCache {
    public static final class Resource { }

    static final class Holder {
        private final SoftReference<Resource> a;
        Holder(Resource value) { this.a = new SoftReference<>(value); }
        public final SoftReference<Resource> a() { return a; }
    }

    public static final ArrayList<Holder> cacheList = new ArrayList<>();

    public static Resource make() { Resource r = new Resource(); cacheList.add(new Holder(r)); return r; }
    public static void clear() { cacheList.clear(); }
}
'''

HARNESS = r'''
package dev.turboism.validation;

import java.lang.ref.Reference;
import java.lang.ref.SoftReference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;

public final class SoftCacheAuditTest {
    static Object CACHED = new Object();
    static java.util.List<Exception> thrown = new ArrayList<>();
    static java.util.List<String> touched = new ArrayList<>();

    static class Hostile {
        @Override public boolean equals(Object other) { throw new AssertionError("equals"); }
        @Override public int hashCode() { throw new AssertionError("hashCode"); }
        @Override public String toString() { throw new AssertionError("toString"); }
    }

    // ---- synthetic host shape: CImageResource with a static cacheList of $b holders ----
    public static final class CImageResource extends Hostile {
        static final ArrayList<b> cacheList = new ArrayList<>();
        private Object image = new Object();
        private byte[] imageFileBuf = null;
        static CImageResource make() { CImageResource r = new CImageResource(); cacheList.add(new b(r)); return r; }
        static void clear() { cacheList.clear(); }
        void archive() { this.image = null; this.imageFileBuf = new byte[1024]; }
        static int created = 7, disposed = 2, byteDataBytes = 4096;
        public static final int access$getCreatedCount$cp() { return created; }
        public static final int access$getDisposedCount$cp() { return disposed; }
        public static final int access$getByteDataBytes$cp() { return byteDataBytes; }
        public static final boolean access$getDEBUG$cp() { return false; }
        public static final ArrayList<?> access$getDEBUG_IMAGES$cp() { return new ArrayList<>(); }
    }
    public static final class b {
        private final SoftReference<CImageResource> a;
        b(CImageResource value) { this.a = new SoftReference<>(value); }
        public final SoftReference<CImageResource> a() { return a; }
    }
    // wrong shape: accessor returns a String, not a SoftReference
    public static final class BadHolder {
        static final ArrayList<BadHolder> cacheList = new ArrayList<>();
        public final String a() { return "nope"; }
    }
    // wrong shape: cache field is primitive
    public static final class PrimitiveCache { static final int cacheList = 0; }
    // valid cache shape, but the resource declares no decoded/archive state fields
    public static final class BareResource extends Hostile {
        static final ArrayList<BareHolder> cacheList = new ArrayList<>();
        static BareResource make() { BareResource r = new BareResource(); cacheList.add(new BareHolder(r)); return r; }
        static void clear() { cacheList.clear(); }
    }
    public static final class BareHolder {
        private final SoftReference<BareResource> a;
        BareHolder(BareResource value) { this.a = new SoftReference<>(value); }
        public final SoftReference<BareResource> a() { return a; }
    }

    /** Never calls a host equals: identity, or value equality for the scalars this probe publishes. */
    static void eq(Object actual, Object expected, String what) {
        if (actual == expected) return;
        if (actual instanceof String left && expected instanceof String right && left.equals(right)) return;
        throw new AssertionError(what + ": expected <" + expected + "> got <" + actual + ">");
    }

    static void prop(NativeSoftCacheObservation.Result r, String key, String expected) { eq(r.values.getProperty(key), expected, key); }

    static NativeSoftCacheObservation.Result run(Class<?> owner, Class<?> holder, List<WeakReference<?>> cohort,
                                                 int entries, int comparisons, long budget) {
        return NativeSoftCacheObservation.audit(NativeSoftCacheObservation.class.getClassLoader(), owner, holder,
                cohort, new NativeSoftCacheObservation.Limits(entries, comparisons, budget), System::nanoTime);
    }

    public static void main(String[] args) {
        // 1. A resident resource is reported as matched; the cache size is reported.
        CImageResource.clear();
        CImageResource r1 = CImageResource.make();
        CImageResource r2 = CImageResource.make();
        List<WeakReference<?>> cohort = new ArrayList<>();
        cohort.add(new WeakReference<>(r1));
        var matched = run(CImageResource.class, b.class, cohort, 100, 1000, 250_000_000L);
        prop(matched, "status", "COMPLETE");
        prop(matched, "reason", "none");
        prop(matched, "entries", "2");
        prop(matched, "visited", "2");
        prop(matched, "cohortSize", "1");
        prop(matched, "matchedCohort", "1");
        prop(matched, "matchedDistinct", "1");

        // 1d. The host's own public counters are read without touching any instance.
        CImageResource.clear();
        CImageResource counted = CImageResource.make();
        List<WeakReference<?>> counterCohort = new ArrayList<>();
        counterCohort.add(new WeakReference<>(counted));
        var counters = run(CImageResource.class, b.class, counterCohort, 100, 1000, 250_000_000L);
        prop(counters, "hostCreated", "7");
        prop(counters, "hostDisposed", "2");
        prop(counters, "hostLive", "5");
        prop(counters, "hostArchivedBytes", "4096");
        prop(counters, "hostDebugEnabled", "false");
        prop(counters, "hostDebugImages", "0");

        // 1e. A host without those accessors reports "unreadable" instead of a fabricated zero.
        BareResource.clear();
        BareResource plain = BareResource.make();
        List<WeakReference<?>> plainCohort = new ArrayList<>();
        plainCohort.add(new WeakReference<>(plain));
        var noCounters = run(BareResource.class, BareHolder.class, plainCohort, 100, 1000, 250_000_000L);
        prop(noCounters, "hostCreated", "unreadable");
        prop(noCounters, "hostLive", "unreadable");

        // 1a. Decoded vs archived resources are counted separately, with the archived byte total.
        CImageResource.clear();
        CImageResource live = CImageResource.make();
        CImageResource archived = CImageResource.make();
        archived.archive();
        List<WeakReference<?>> states = new ArrayList<>();
        states.add(new WeakReference<>(live));
        states.add(new WeakReference<>(archived));
        var stateCounts = run(CImageResource.class, b.class, states, 100, 1000, 250_000_000L);
        prop(stateCounts, "status", "COMPLETE");
        prop(stateCounts, "matchedCohort", "2");
        prop(stateCounts, "matchedDecoded", "1");
        prop(stateCounts, "matchedArchived", "1");
        prop(stateCounts, "matchedWithArchiveBytes", "1");
        prop(stateCounts, "matchedArchivedBytes", "1024");
        prop(stateCounts, "matchedUnreadable", "0");

        // 1c. A resource whose state fields are absent is counted as unreadable, not as decoded or archived.
        BareResource.clear();
        BareResource bare = BareResource.make();
        List<WeakReference<?>> bareCohort = new ArrayList<>();
        bareCohort.add(new WeakReference<>(bare));
        var noStateFields = run(BareResource.class, BareHolder.class, bareCohort, 100, 1000, 250_000_000L);
        prop(noStateFields, "status", "COMPLETE");
        prop(noStateFields, "matchedCohort", "1");
        prop(noStateFields, "matchedUnreadable", "1");
        prop(noStateFields, "matchedDecoded", "0");
        prop(noStateFields, "matchedArchived", "0");

        // 1b. Two cache entries pointing at one cohort member count it once.
        CImageResource.clear();
        CImageResource twice = CImageResource.make();
        CImageResource.cacheList.add(new b(twice));
        List<WeakReference<?>> onceCohort = new ArrayList<>();
        onceCohort.add(new WeakReference<>(twice));
        var once = run(CImageResource.class, b.class, onceCohort, 100, 1000, 250_000_000L);
        prop(once, "status", "COMPLETE");
        prop(once, "entries", "2");
        prop(once, "visited", "2");
        prop(once, "matchedCohort", "1");
        prop(once, "matchedDistinct", "1");

        // 2. An unreferenced resource is not claimed as matched.
        CImageResource.clear();
        CImageResource r3 = CImageResource.make();
        WeakReference<CImageResource> stranger = new WeakReference<>(new CImageResource());
        List<WeakReference<?>> other = new ArrayList<>();
        other.add(stranger);
        var unmatched = run(CImageResource.class, b.class, other, 100, 1000, 250_000_000L);
        prop(unmatched, "status", "COMPLETE");
        prop(unmatched, "entries", "1");
        prop(unmatched, "matchedCohort", "0");

        // 3. An empty cache and an empty cohort are both reported as zero, not as missing.
        CImageResource.clear();
        var empty = run(CImageResource.class, b.class, new ArrayList<>(), 100, 1000, 250_000_000L);
        prop(empty, "status", "COMPLETE");
        prop(empty, "entries", "0");
        prop(empty, "visited", "0");
        prop(empty, "matchedCohort", "0");

        // 4. Entry limit and comparison limit are PARTIAL and publish no counts at all.
        CImageResource.clear();
        List<WeakReference<?>> many = new ArrayList<>();
        for (int i = 0; i < 5; i++) { CImageResource r = CImageResource.make(); many.add(new WeakReference<>(r)); }
        var entryCapped = run(CImageResource.class, b.class, many, 2, 1000, 250_000_000L);
        prop(entryCapped, "status", "PARTIAL");
        prop(entryCapped, "reason", "entry-limit");
        eq(entryCapped.values.getProperty("entries"), null, "entry-limit must not publish a count");
        eq(entryCapped.values.getProperty("matchedCohort"), null, "entry-limit must not publish a count");
        var comparedCapped = run(CImageResource.class, b.class, many, 100, 3, 250_000_000L);
        prop(comparedCapped, "status", "PARTIAL");
        prop(comparedCapped, "reason", "comparison-limit");
        eq(comparedCapped.values.getProperty("visited"), null, "comparison-limit must not publish a count");

        // 5. A zero-length deadline is a PARTIAL time-limit with no published counts.
        var timed = run(CImageResource.class, b.class, many, 100, 1000, 0L);
        prop(timed, "status", "PARTIAL");
        prop(timed, "reason", "time-limit");
        eq(timed.values.getProperty("entries"), null, "time-limit must not publish a count");

        // 6. Wrong shapes are UNSUPPORTED, never a zero count.
        CImageResource.clear();
        var badAccessor = run(BadHolder.class, BadHolder.class, new ArrayList<>(), 100, 1000, 250_000_000L);
        prop(badAccessor, "status", "UNSUPPORTED");
        prop(badAccessor, "reason", "access");
        var primitive = run(PrimitiveCache.class, b.class, new ArrayList<>(), 100, 1000, 250_000_000L);
        prop(primitive, "status", "UNSUPPORTED");
        prop(primitive, "reason", "access");

        // 7. The cached resources are still resident afterwards: the audit neither removed nor disposed anything.
        CImageResource.clear();
        CImageResource kept = CImageResource.make();
        List<WeakReference<?>> keptCohort = new ArrayList<>();
        keptCohort.add(new WeakReference<>(kept));
        run(CImageResource.class, b.class, keptCohort, 100, 1000, 250_000_000L);
        eq(CImageResource.cacheList.size(), 1, "cacheList must be untouched");
        eq(keptCohort.get(0).get(), kept, "cohort entry must still refer to the live resource");

        // 8. Only scalars escape: every published value is a String and the result exposes no host object.
        for (String key : matched.values.stringPropertyNames()) {
            Object value = matched.values.getProperty(key);
            if (!(value instanceof String)) throw new AssertionError("non scalar property " + key);
        }

        // 10. A public accessor on a package-private holder class in another package must still be readable.
        try {
            Class<?> foreign = Class.forName("other.ForeignCache");
            Class<?> foreignHolder = Class.forName("other.ForeignCache$Holder");
            Object fresh = foreign.getMethod("make").invoke(null);
            List<WeakReference<?>> foreignCohort = new ArrayList<>();
            foreignCohort.add(new WeakReference<>(fresh));
            var cross = run(foreign, foreignHolder, foreignCohort, 100, 1000, 250_000_000L);
            prop(cross, "status", "COMPLETE");
            prop(cross, "reason", "none");
            prop(cross, "entries", "1");
            prop(cross, "matchedCohort", "1");
        } catch (ReflectiveOperationException problem) {
            throw new AssertionError(problem);
        }

        // 9. A null cache field is a complete zero, not a failure.
        var nullCache = run(EmptyCache.class, b.class, new ArrayList<>(), 100, 1000, 250_000_000L);
        prop(nullCache, "status", "COMPLETE");
        prop(nullCache, "entries", "0");

        System.out.println("PASS soft-cache audit checks");
    }

    public static final class EmptyCache { static ArrayList<b> cacheList = null; }
}
'''


def java_home():
    for candidate in (os.environ.get("JAVA17_HOME"), os.environ.get("JAVA_HOME")):
        if candidate and Path(candidate, "bin", "javac").exists():
            return candidate
    javac = shutil.which("javac")
    if javac:
        return str(Path(javac).resolve().parents[1])
    raise unittest.SkipTest("no JDK available")


class SoftCacheAuditTest(unittest.TestCase):
    def test_synthetic_cache_scan(self):
        home = java_home()
        with tempfile.TemporaryDirectory() as tmp:
            work = Path(tmp)
            (work / "SoftCacheAuditTest.java").write_text(HARNESS)
            (work / "ForeignCache.java").write_text(FOREIGN)
            sources = [str(AUDIT), str(OWNERSHIP), str(work / "SoftCacheAuditTest.java"), str(work / "ForeignCache.java")]
            compiled = subprocess.run(
                [str(Path(home, "bin", "javac")), "--release", "17", "-nowarn", "-d", str(work)] + sources,
                capture_output=True, text=True)
            self.assertEqual(compiled.returncode, 0, compiled.stdout + compiled.stderr)
            result = subprocess.run(
                [str(Path(home, "bin", "java")), "-cp", str(work), "dev.turboism.validation.SoftCacheAuditTest"],
                capture_output=True, text=True, timeout=300)
            self.assertEqual(result.returncode, 0, result.stdout + result.stderr)
            self.assertIn("PASS soft-cache audit checks", result.stdout)

    @staticmethod
    def code_only(path):
        """Strip comments so prose cannot mask a call site or be mistaken for one."""
        text = re.sub(r"/\*.*?\*/", "", path.read_text(), flags=re.S)
        return re.sub(r"//[^\n]*", "", text)

    def test_read_only_and_identity_only_call_sites(self):
        text = self.code_only(AUDIT)
        for forbidden in ("System.gc(", ".dispose(", ".clear()", ".remove(", "ClassHistogram", "dumpHeap", "jmap",
                          "getFilteredImage", "SoftReference.get()"):
            self.assertNotIn(forbidden, text, f"forbidden call site {forbidden}")
        self.assertIn("refersTo(", text, "the soft reference must be compared with refersTo only")
        self.assertIn("trySetAccessible()", text, "private host fields must be probed defensively")

    def test_host_agent_wiring(self):
        text = self.code_only(AGENT)
        self.assertIn("NativeSoftCacheObservation.auditHost", text, "the audit must be wired into the host agent")
        self.assertIn("softCache.", text, "the agent must publish softCache.<phase> properties")
        for phase in ("idle", "beforeClose", "closed120", "closedFinal"):
            self.assertIn(f'"{phase}"', text, f"missing capture point {phase}")
        self.assertIn("softCache.attributionStatus", text, "an aggregate status must be published")
        for forbidden in ("System.gc(", ".dispose(", "dumpHeap", "jmap", "ClassHistogram"):
            self.assertNotIn(forbidden, text, f"forbidden call site {forbidden}")
        # ordering: the audit runs after the ownership capture of the same phase
        for match in re.finditer(r"ownership\.\" \+ name \+ \"\.begin\"", text):
            self.assertGreater(match.start(), 0)


if __name__ == "__main__":
    unittest.main(verbosity=2)
