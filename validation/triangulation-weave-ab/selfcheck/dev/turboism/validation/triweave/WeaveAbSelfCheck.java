package dev.turboism.validation.triweave;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.kmembership.Weave;
import dev.turboism.validation.triweave.fixture.FixtureLoader;

/**
 * Scenario driver. Each scenario runs in a fresh JVM launched by run.sh with
 * {@code -javaagent:tri-weave-agent.jar}, {@code -Xverify:all} and scenario-specific
 * {@code turboism.validation.triWeave.*} properties. The shadow fixture supplies the
 * official byte shape under its own package; no official class is ever loaded or executed.
 */
public final class WeaveAbSelfCheck {
    static final String TL = "dev.turboism.validation.triweave.shadow.ShadowTriangleList";
    static final String WORLD = "dev.turboism.validation.triweave.shadow.ShadowWorld";

    /** Canonical ordered endpoint sequence of the "shared" input's deduped output. */
    static final String SHARED_SEQ = "1,2;2,3;1,3;2,4;3,4;";
    static final String SINGLE_SEQ = "1,2;2,3;1,3;";

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        URL[] urls = args.length > 5
            ? new URL[] { Path.of(args[1]).toUri().toURL(), Path.of(args[5]).toUri().toURL() }
            : new URL[] { Path.of(args[1]).toUri().toURL() };
        Path defLog = Path.of(args[2]);
        Path dumpLog = Path.of(args[3]);
        Path statusFile = Path.of(args[4]);
        switch (scenario) {
            case "happyDump" -> happy(urls, defLog, dumpLog, statusFile, false);
            case "happyWeave" -> happy(urls, defLog, dumpLog, statusFile, true);
            case "fallbackInit" -> fallbackInit(urls, defLog, dumpLog, statusFile);
            case "failAtN" -> failAtN(urls, defLog, dumpLog, statusFile);
            case "missingHelperDump" -> missingHelperDump(urls, defLog, dumpLog, statusFile);
            case "missingHelperWeave" -> missingHelperWeave(urls, defLog, dumpLog, statusFile);
            case "shapeRejectWeave" -> shapeRejectWeave(urls, defLog, dumpLog, statusFile);
            case "shapeRejectDump" -> shapeRejectDump(urls, defLog, dumpLog, statusFile);
            case "badReturnDump" -> badReturn(urls, defLog, dumpLog, statusFile, false);
            case "badReturnWeave" -> badReturn(urls, defLog, dumpLog, statusFile, true);
            case "wrongSha", "wrongSource" -> gateReject(urls, defLog, dumpLog, statusFile);
            case "wrongLoader" -> wrongLoader(urls, defLog, dumpLog, statusFile);
            case "observerBudget" -> observerBudget(urls, defLog, dumpLog, statusFile);
            case "off" -> off(urls, defLog, dumpLog, statusFile);
            case "refusedConfig" -> refusedConfig(urls, defLog, dumpLog, statusFile);
            case "writeFailure" -> writeFailure(urls, defLog, dumpLog, statusFile);
            case "maliciousFields" -> maliciousFields(urls, defLog, dumpLog, statusFile);
            case "getterFaultDump" -> getterFault(urls, defLog, dumpLog, statusFile);
            case "getterFaultWeave" -> getterFault(urls, defLog, dumpLog, statusFile);
            case "emptyWeave" -> emptyWeave(urls, defLog, dumpLog, statusFile);
            case "nullEdgeWeave" -> nullEdge(urls, defLog, dumpLog, statusFile);
            case "shapePins" -> shapePins(Path.of(args[1]));
            case "codeSourceUnit" -> codeSourceUnit();
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    private static ClassLoader sys() { return ClassLoader.getSystemClassLoader(); }

    private static final class Fx {
        final FixtureLoader loader;
        final Class<?> tl;
        final Class<?> world;
        Fx(URL[] urls) throws Exception {
            loader = new FixtureLoader(urls, sys());
            tl = loader.loadClass(TL);
            world = loader.loadClass(WORLD);
        }
        Object instance(String input) throws Exception {
            Constructor<?> ctor = tl.getDeclaredConstructor(LinkedHashSet.class);
            return ctor.newInstance(world.getMethod("input", String.class).invoke(null, input));
        }
        Object produce(Object inst) throws Exception {
            return tl.getMethod("produce").invoke(inst);
        }
        String stats() throws Exception {
            return (String) world.getMethod("stats").invoke(null);
        }
        void reset() throws Exception {
            world.getMethod("reset").invoke(null);
        }
        String indexSeq(Object k) throws Exception {
            return (String) world.getMethod("indexSeq", Object.class).invoke(null, k);
        }
    }

    private static int stat(String stats, String key) {
        for (String tok : stats.split(" ")) {
            if (tok.startsWith(key + "=")) return Integer.parseInt(tok.split("=")[1]);
        }
        return -1;
    }

    private static void waitWriter() throws InterruptedException {
        Thread.sleep(400); // daemon writer drains the bounded queue; deterministic sleep is enough
    }

    private static String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p) : "<absent>";
    }

    private static List<String> lines(Path p) throws Exception {
        return Files.exists(p) ? Files.readAllLines(p) : List.of();
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String field(String line, String key) {
        for (String tok : line.split(" ")) {
            if (tok.startsWith(key + "=")) return tok.substring(key.length() + 1);
        }
        return null;
    }

    // ------------------------------------------------------------ scenarios

    private static void happy(URL[] urls, Path defLog, Path dumpLog, Path statusFile,
            boolean woven) throws Exception {
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        String lastSeq = null;
        for (int i = 0; i < 5; i++) {
            lastSeq = fx.indexSeq(fx.produce(inst));
        }
        check(SHARED_SEQ.equals(lastSeq), "produce() output sequence mismatch: " + lastSeq);
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=accept"), "definition log lacks accept: " + def);
        check(def.contains("mode=" + (woven ? "dump+weave" : "dump-only")),
            "def log lacks mode: " + def);
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 4, "expected 4 bounded dumps, got " + dumps.size());
        String expectedSha = sha256(SHARED_SEQ);
        int q = -1;
        for (int i = 0; i < dumps.size(); i++) {
            String line = dumps.get(i);
            check(Integer.toString(i + 1).equals(field(line, "seq")),
                "dump seq mismatch: " + line);
            check(expectedSha.equals(field(line, "sha256")), "sha mismatch: " + line);
            check(SHARED_SEQ.equals(field(line, "edges")), "edges mismatch: " + line);
            check("false".equals(field(line, "edgesTruncated")), "unexpected truncation");
            check((woven ? "dump+weave" : "dump-only").equals(field(line, "mode")),
                "mode field mismatch: " + line);
            check("true".equals(field(line, "helperLinked")),
                "helper resolvable under full fixture: " + line);
            q = Integer.parseInt(field(line, "helperQueries"));
        }
        check(q > 0 == woven, "helperQueries " + q + " inconsistent with mode");
        String stats = fx.stats();
        check(stat(stats, "newBoxCalls") == (woven ? 5 : 0), "newBoxCalls: " + stats);
        check(stat(stats, "queries") == (woven ? 30 : 0), "queries: " + stats);
        check(stat(stats, "originalQueries") == (woven ? 0 : 30), "originalQueries: " + stats);
        check(stat(stats, "hits") == (woven ? 5 : 0), "hits: " + stats);
        check(!Files.exists(statusFile), "unexpected status file");
        pass(woven ? "happyWeave" : "happyDump", "dumps=4 shaParity=true " + stats);
    }

    private static void fallbackInit(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        // dump+weave + shadow failNewBox: every call falls back to the original path; the
        // dump records (same collection code) still report identical output digests.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        String lastSeq = null;
        for (int i = 0; i < 2; i++) lastSeq = fx.indexSeq(fx.produce(inst));
        check(SHARED_SEQ.equals(lastSeq), "fallback output mismatch: " + lastSeq);
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 2, "fallback dumps missing: " + dumps.size());
        for (String line : dumps) {
            check(sha256(SHARED_SEQ).equals(field(line, "sha256")),
                "fallback sha mismatch: " + line);
            check("true".equals(field(line, "helperLinked")), "helper must link here: " + line);
            check("0".equals(field(line, "helperQueries")),
                "fallback must not run helper queries: " + line);
        }
        String stats = fx.stats();
        check(stat(stats, "newBoxCalls") == 2, "newBox attempts counted: " + stats);
        check(stat(stats, "queries") == 0, "helper queries must be 0: " + stats);
        check(stat(stats, "originalQueries") == 12, "original fallback 6/call: " + stats);
        check(!Files.exists(statusFile), "fallback must not invalidate the leg");
        pass("fallbackInit", "delegated=6/call shaPreserved=true");
    }

    private static void failAtN(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        // LinkageError at query #5 -> permanent local-null for THIS call only; next call
        // re-inits a fresh box and the helper path resumes.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        fx.produce(inst);
        String stats = fx.stats();
        check(stat(stats, "queries") == 5, "fail@5 helper queries: " + stats);
        check(stat(stats, "originalQueries") == 2, "fail@5 original tail: " + stats);
        check(stat(stats, "newBoxCalls") == 1, "fail@5 newBox: " + stats);
        fx.produce(inst);
        stats = fx.stats();
        check(stat(stats, "queries") == 11, "post-fail resume: " + stats);
        check(stat(stats, "originalQueries") == 2, "post-fail no extra originals: " + stats);
        check(stat(stats, "newBoxCalls") == 2, "post-fail fresh box: " + stats);
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 2, "failAtN dumps: " + dumps.size());
        for (String line : dumps)
            check(sha256(SHARED_SEQ).equals(field(line, "sha256")), "parity: " + line);
        check(!Files.exists(statusFile), "recoverable LinkageError must not invalidate");
        pass("failAtN", "perCallDisable=true recovery=true shaPreserved=true");
    }

    private static void missingHelperDump(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // dump-only must work with no helper at all: baseline dump records, no Helper link.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        check(SHARED_SEQ.equals(fx.indexSeq(fx.produce(inst))), "no-helper dump output");
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 1, "missingHelperDump dumps: " + dumps.size());
        check(sha256(SHARED_SEQ).equals(field(dumps.get(0), "sha256")),
            "sha: " + dumps.get(0));
        check("false".equals(field(dumps.get(0), "helperLinked")),
            "helperLinked must be false: " + dumps.get(0));
        check(!Files.exists(statusFile), "dump-only never needs helper");
        pass("missingHelperDump", "helperAbsent=true dumpsProduced=true");
    }

    private static void missingHelperWeave(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // Woven mode + missing helper -> the leg is INVALID, never silently baseline.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        check(SHARED_SEQ.equals(fx.indexSeq(fx.produce(inst))),
            "unwoven original must still run");
        waitWriter();
        check(lines(dumpLog).isEmpty(), "woven-no-helper must not dump");
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains("helper-unavailable"), "wrong invalid reason: " + status);
        pass("missingHelperWeave", "legInvalid=helper-unavailable silentBaseline=false");
    }

    private static void shapeRejectWeave(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // Woven mode + pinned-correct bytes that fail the candidate shape gate: hard fail.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        check(fx.produce(inst) != null, "badshape produce must still run original");
        waitWriter();
        check(lines(dumpLog).isEmpty(), "shape-rejected woven leg must not dump");
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains("weave-reject"), "expected weave reject: " + status);
        check(status.contains("queries=2"), "expected queries=2 reason: " + status);
        String def = read(defLog);
        check(def.contains("gate=reject"), "def log lacks reject: " + def);
        pass("shapeRejectWeave", "legInvalid=weave-reject noSilentBaseline=true");
    }

    private static void shapeRejectDump(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // dump-only on a candidate-shape-bad class: the baseline never needs the candidate
        // pin — capture still applies and records the actual outputs.
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        Object k = fx.produce(inst);
        check(k != null, "badshape produce must run");
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 1, "dump-only baseline must still dump: " + dumps.size());
        check(!Files.exists(statusFile), "dump-only shape variance is not leg-invalid");
        pass("shapeRejectDump", "baselineObserved=true candidatePinIrrelevant=true");
    }

    private static void badReturn(URL[] urls, Path defLog, Path dumpLog, Path statusFile,
            boolean woven) throws Exception {
        Fx fx = new Fx(urls);
        Object inst = fx.instance("shared");
        check(fx.produce(inst) != null, "badreturn produce must still run");
        waitWriter();
        check(lines(dumpLog).isEmpty(), "bad-return must not dump");
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains(woven ? "weave-reject:areturns=2"
                : "capture-reject:capture-areturns=2"),
            "wrong invalid reason: " + status);
        pass(woven ? "badReturnWeave" : "badReturnDump", "legInvalid=areturns");
    }

    private static void gateReject(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        check(fx.produce(fx.instance("shared")) != null, "class must run unmodified");
        waitWriter();
        check(read(defLog).contains("gate=reject"), "expected gate reject in def log");
        check(lines(dumpLog).isEmpty(), "gate-rejected class must not dump");
        check(!Files.exists(statusFile),
            "identity-gate reject is not a post-admission transform failure");
        pass("gateReject", "rejectedAtIdentityGate=true");
    }

    private static void wrongLoader(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        try (java.net.URLClassLoader plain = new java.net.URLClassLoader(urls, sys())) {
            Class<?> tl = plain.loadClass(TL);
            Object inst = tl.getDeclaredConstructor(LinkedHashSet.class)
                .newInstance(new LinkedHashSet<>());
            check(tl.getMethod("produce").invoke(inst) != null, "produce null");
        }
        waitWriter();
        check(read(defLog).contains("reason=loader"), "expected loader reject");
        check(lines(dumpLog).isEmpty(), "loader-rejected class must not dump");
        pass("wrongLoader", "rejected=loader");
    }

    private static void observerBudget(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        for (int i = 0; i < 6; i++) {
            FixtureLoader l = new FixtureLoader(urls, sys());
            l.loadClass(TL);   // each loader defines the class again
        }
        waitWriter();
        String def = read(defLog);
        long events = def.lines().filter(l -> l.contains("sha256=")).count();
        check(events <= 4, "definition observation not bounded: " + events);
        check(def.contains("overflow=true"), "expected overflow marker: " + def);
        pass("observerBudget", "definitionEventsRecorded=" + events);
    }

    private static void off(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        check(SHARED_SEQ.equals(fx.indexSeq(fx.produce(fx.instance("shared")))),
            "off produce output");
        waitWriter();
        check(!Files.exists(defLog) && !Files.exists(dumpLog) && !Files.exists(statusFile),
            "disabled run produced output files");
        check(writerThreads() == 0, "disabled run still started a writer thread");
        pass("off", "zeroSideEffects=true");
    }

    private static void refusedConfig(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        check(fx.produce(fx.instance("shared")) != null, "produce must work under refusal");
        waitWriter();
        check(!Files.exists(defLog) && !Files.exists(dumpLog) && !Files.exists(statusFile),
            "refused admission produced output files");
        check(writerThreads() == 0, "refused admission still started a writer thread");
        pass("refusedConfig", "premainRefused=safely entrypointReached=true");
    }

    private static void writeFailure(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        check(fx.produce(fx.instance("shared")) != null, "produce must work on write failure");
        waitWriter();
        String status = Sink.status();
        check(status.contains("writesFailed="), "status missing: " + status);
        check(!status.contains("writesFailed=0"), "expected write failure: " + status);
        pass("writeFailure", status);
    }

    private static void maliciousFields(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        Fx fx = new Fx(urls);
        check(fx.produce(fx.instance("shared")) != null, "produce must work");
        waitWriter();
        List<String> lines = lines(dumpLog);
        check(lines.size() == 1, "expected one line, got " + lines.size());
        String line = lines.get(0);
        check(line.contains("EVIL\\nLIT\\\\n"), "newline/backslash-n distinction lost: " + line);
        check(line.contains("\\t"), "tab must be escaped: " + line);
        check(line.contains("\\x01"), "control char must be escaped: " + line);
        check(line.contains("\\s"), "value-internal space must be escaped: " + line);
        check(line.contains("~truncated"), "expected truncation marker: " + line);
        check(line.matches(".*runId=maliciousFields phase=\\S+.*"),
            "field separators mangled: " + line);
        for (String tok : line.split(" ")) {
            check(tok.length() <= 4096 + 32, "field exceeds cap: " + tok.length());
        }
        pass("maliciousFields", "escapedBoundedDistinguishable=true");
    }

    private static void getterFault(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        Object bad = fx.instance("getterFault");
        InvocationTargetException thrown = null;
        try {
            fx.produce(bad);
        } catch (InvocationTargetException e) {
            thrown = e;
        }
        check(thrown != null, "expected getter fault");
        check(thrown.getCause() instanceof RuntimeException
            && "getter-fault-e".equals(thrown.getCause().getMessage()),
            "original exception must propagate unchanged: " + thrown.getCause());
        // a throwing call consumes no dump slot; the next normal return records seq=1
        Object ok = fx.instance("shared");
        check(SHARED_SEQ.equals(fx.indexSeq(fx.produce(ok))), "post-fault produce");
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 1 && "1".equals(field(dumps.get(0), "seq")),
            "throwing call must not consume a capture slot: " + dumps);
        pass("getterFault", "exceptionPassthrough=true captureOnReturnOnly=true");
    }

    private static void emptyWeave(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        Object inst = fx.instance("empty");
        Object k = fx.produce(inst);
        check("".equals(fx.indexSeq(k)), "empty input must produce empty list");
        waitWriter();
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 1, "empty input dumps: " + dumps.size());
        check("0".equals(field(dumps.get(0), "edgeCount")), "edgeCount=0 expected");
        check(sha256("").equals(field(dumps.get(0), "sha256")),
            "empty-sequence sha mismatch");
        String stats = fx.stats();
        check(stat(stats, "newBoxCalls") == 1, "empty input still inits box once: " + stats);
        check(stat(stats, "queries") == 0, "empty input runs no queries: " + stats);
        pass("emptyWeave", "newBox=1 queries=0 shaOfEmpty=true");
    }

    private static void nullEdge(URL[] urls, Path defLog, Path dumpLog, Path statusFile)
            throws Exception {
        Fx fx = new Fx(urls);
        Object inst = fx.instance("nullEdge");
        InvocationTargetException thrown = null;
        try {
            fx.produce(inst);
        } catch (InvocationTargetException e) {
            thrown = e;
        }
        check(thrown != null, "expected null-edge NPE");
        check(thrown.getCause() instanceof NullPointerException,
            "null edge must NPE: " + thrown.getCause());
        check(thrown.getCause().getMessage() != null
            && thrown.getCause().getMessage().contains("Parameter specified as non-null"),
            "intrinsic NPE message: " + thrown.getCause().getMessage());
        String stats = fx.stats();
        check(stat(stats, "queries") == 0, "null edge must not reach helper: " + stats);
        check(stat(stats, "originalQueries") == 1, "null edge takes original once: " + stats);
        pass("nullEdgeWeave", "intrinsicNpePreserved=true noHelperTouch=true");
    }

    // ---------------------------------------------- in-JVM shape-gate negatives

    private static void shapePins(Path fixtureDir) throws Exception {
        byte[] orig = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/triweave/shadow/ShadowTriangleList.class"));
        Weave.Config cfg = WeaveAbConfig.SHADOW_WEAVE;

        Weave.Result ok = Weave.weaveChecked(cfg, orig);
        check(ok.rejectReason == null, "shadow shape must be accepted: " + ok.rejectReason);
        CaptureWeave.Result cap = CaptureWeave.weaveChecked(cfg.methodName, cfg.methodDesc,
            WeaveAbConfig.SHADOW_CAPTURE_INTERNAL, ok.bytes);
        check(cap.rejectReason == null, "capture weave after candidate: " + cap.rejectReason);

        expectReject(orig, cfg, "iconst1-Z", ShapeMutants.zToTrue(orig, cfg), "Z not iconst_0");
        expectReject(orig, cfg, "iload-Z", ShapeMutants.zToIload(orig, cfg), "Z not iconst_0");
        expectReject(orig, cfg, "retargeted-query-desc",
            ShapeMutants.retargetQueryDesc(orig, cfg), "not adjacent to pinned query site");
        expectReject(orig, cfg, "site-count-2",
            ShapeMutants.dropCompleteSite(orig, cfg), "queries=2");
        expectReject(orig, cfg, "anchor-k-slot",
            ShapeMutants.anchorSlot(orig, cfg), "k slot");
        expectReject(orig, cfg, "site-before-anchor",
            ShapeMutants.siteBeforeAnchor(orig, cfg), "before anchor");
        expectReject(orig, cfg, "append-j-slot",
            ShapeMutants.appendJSlot(orig, cfg), "j slot");
        expectReject(orig, cfg, "no-anchor",
            ShapeMutants.noAnchor(orig, cfg), "no k-init anchor");
        pass("shapePins", "eightVariantsRejectedWithObservableReasons=true");
    }

    private static void expectReject(byte[] orig, Weave.Config cfg, String tag,
            byte[] variant, String reasonPart) {
        Weave.Result r = Weave.weaveChecked(cfg, variant);
        check(r.rejectReason != null, tag + " must reject (no reason)");
        check(r.bytes == variant, tag + " reject must return the original array");
        check(r.rejectReason.contains(reasonPart),
            tag + " reason '" + r.rejectReason + "' must hit '" + reasonPart + "'");
        check(Weave.weave(cfg, variant) == variant,
            tag + " weave() also returns the original array");
        System.out.println("SHAPE_REJECT " + tag + " reason=" + r.rejectReason);
    }

    private static void codeSourceUnit() {
        cs(true,  "file:/a/b",  "file:///a/b");
        cs(true,  "file:///a/b", "file:/a/b");
        cs(false, "file:/a/b",  "file://host/a/b");
        cs(false, "file://host/a/b", "file:/a/b");
        cs(false, "file://a/b",  "file:/a/b");
        cs(false, "file:/a/b",  "file:/a/bc");
        cs(false, "file:/a/bc", "file:/a/b");
        cs(false, "file:/a/",   "file:/a");
        cs(false, "http:/a/b",  "file:/a/b");
        cs(false, "file:",      "file:/a");
        cs(false, "file:x",     "file:/x");
        cs(false, "file:/",     "file:/");
        cs(false, "file:/a/../b", "file:/b");
        cs(false, "file:/a%20b", "file:/a b");
        cs(true,  "FILE:/a/b",  "file:/a/b");
        pass("codeSourceUnit", "authority/dotsegment/rawpath/exact-equality verified");
    }

    private static void cs(boolean expected, String exp, String act) {
        boolean got = WeaveAbConfig.codeSourceMatches(exp, act);
        check(got == expected, "codeSourceMatches(" + exp + "," + act + ")=" + got
                + " expected " + expected);
    }

    private static int writerThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("tri-weave-writer")) n++;
        }
        return n;
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String scenario, String detail) {
        System.out.println("TRI_WEAVE_SELFCHECK " + scenario + " PASS " + detail);
    }
}
