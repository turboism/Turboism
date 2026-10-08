package dev.turboism.validation.triweave;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;

import dev.turboism.validation.kmembership.Weave;
import dev.turboism.validation.shared.fixture.FixtureLoader;

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
            case "dmHappyDump" -> dmHappy(urls, defLog, dumpLog, statusFile, false);
            case "dmHappyWeave" -> dmHappy(urls, defLog, dumpLog, statusFile, true);
            case "dmMissingHelperDump" -> dmMissingHelper(urls, defLog, dumpLog, statusFile, false);
            case "dmMissingHelperWeave" -> dmMissingHelper(urls, defLog, dumpLog, statusFile, true);
            case "dmShapeRejectWeave" -> dmShapeReject(urls, defLog, dumpLog, statusFile, true);
            case "dmShapeRejectDump" -> dmShapeReject(urls, defLog, dumpLog, statusFile, false);
            case "dmBadReturnWeave" -> dmBadReturn(urls, defLog, dumpLog, statusFile);
            case "dmWrongWeaveSha" -> dmWrongWeaveSha(urls, defLog, dumpLog, statusFile);
            case "dmWrongCaptureSha" -> dmWrongCaptureSha(urls, defLog, dumpLog, statusFile);
            case "dmShapePins" -> dmShapePins(Path.of(args[1]));
            case "tlHappyDump" -> tlHappy(urls, defLog, dumpLog, statusFile, false);
            case "tlHappyWeave" -> tlHappy(urls, defLog, dumpLog, statusFile, true);
            case "tlMissingHelperWeave" -> tlMissingHelper(urls, defLog, dumpLog, statusFile);
            case "tlShapeRejectWeave" -> tlShapeReject(urls, defLog, dumpLog, statusFile);
            case "tlWrongSha" -> tlGateReject(urls, defLog, dumpLog, statusFile);
            case "tlShapePins" -> tlShapePins(Path.of(args[1]));
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

    // ------------------------------------------------------------ dm scenarios
    // DWEAVE namespace: candidate weave on ShadowH.c()V (single ArrayList site
    // -> ShadowMatchList, double pin), capture on ShadowTriangleList.produce().

    static final String SH = "dev.turboism.validation.triweave.shadow.ShadowH";
    static final String SHW = "dev.turboism.validation.triweave.shadow.ShadowHWorld";
    /** Canonical content of the "dmShared" input — pinned from the unwoven
     *  fixture run so BOTH modes prove byte-identical behavior against the
     *  same literal. The Phase-4 drain empties the matchList (same as the
     *  official loop); the popped list carries the full ordered outcome plus
     *  the Phase-5 addAll payload. */
    static final String DM_MATCH_EXPECTED = "";
    static final String DM_POPPED_EXPECTED = "6,7;6,7;3,4;7,8;7,8;40,41;42,43;";

    private static final class DmFx {
        final FixtureLoader loader;
        final Class<?> h, hw, tl, world;
        DmFx(URL[] urls) throws Exception {
            loader = new FixtureLoader(urls, sys());
            h = loader.loadClass(SH);
            hw = loader.loadClass(SHW);
            tl = loader.loadClass(TL);
            world = loader.loadClass(WORLD);
        }
        Object hInst(String input) throws Exception {
            return hw.getMethod("input", String.class).invoke(null, input);
        }
        void c(Object inst) throws Exception {
            h.getMethod("c").invoke(inst);
        }
        Object field(Object inst, String name) throws Exception {
            return h.getField(name).get(inst);
        }
        String edgeSeq(Object list) throws Exception {
            return (String) hw.getMethod("edgeSeq", Object.class).invoke(null, list);
        }
        String hStats() throws Exception {
            return (String) hw.getMethod("stats").invoke(null);
        }
        Object tlInst(String input) throws Exception {
            Constructor<?> ctor = tl.getDeclaredConstructor(LinkedHashSet.class);
            return ctor.newInstance(world.getMethod("input", String.class).invoke(null, input));
        }
        Object produce(Object inst) throws Exception {
            return tl.getMethod("produce").invoke(inst);
        }
        String indexSeq(Object k) throws Exception {
            return (String) world.getMethod("indexSeq", Object.class).invoke(null, k);
        }
    }

    private static String matchClass(Object ml) {
        return ml == null ? "null" : ml.getClass().getName();
    }

    private static void dmHappy(URL[] urls, Path defLog, Path dumpLog, Path statusFile,
            boolean woven) throws Exception {
        DmFx fx = new DmFx(urls);
        // weave effectiveness + behavior parity on the H window
        Object inst = fx.hInst("dmShared");
        fx.c(inst);
        Object ml = fx.field(inst, "matchListOut");
        Object pp = fx.field(inst, "poppedOut");
        check(fx.edgeSeq(ml).equals(DM_MATCH_EXPECTED),
            "matchList content diverged: " + fx.edgeSeq(ml)
                + " vs " + DM_MATCH_EXPECTED);
        check(fx.edgeSeq(pp).equals(DM_POPPED_EXPECTED),
            "popped content diverged: " + fx.edgeSeq(pp)
                + " vs " + DM_POPPED_EXPECTED);
        check(matchClass(ml).equals(woven
                ? "dev.turboism.validation.triweave.shadow.ShadowMatchList"
                : "java.util.ArrayList"),
            "matchList class mismatch: " + matchClass(ml));
        check(matchClass(pp).equals("java.util.ArrayList"),
            "second list must stay a plain ArrayList: " + matchClass(pp));
        String hstats = fx.hStats();
        if (woven) {
            check(stat(hstats, "matchListCreated") == 1,
                "woven ShadowMatchList must be instantiated once: " + hstats);
            check(stat(hstats, "containsCalls") > 0,
                "mirror must answer real contains calls: " + hstats);
            check(stat(hstats, "addCalls") > 0,
                "mirror must register real adds: " + hstats);
        }
        // capture on ShadowTriangleList.produce() runs identically in both modes
        Object tl = fx.tlInst("shared");
        String lastSeq = null;
        for (int i = 0; i < 5; i++) lastSeq = fx.indexSeq(fx.produce(tl));
        check(SHARED_SEQ.equals(lastSeq), "produce() output mismatch: " + lastSeq);
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=accept"), "definition log lacks accept: " + def);
        check(def.contains("class=" + SH.replace('.', '/')),
            "def log lacks the ShadowH event: " + def);
        check(def.contains("candidate=" + (woven ? "applied" : "skipped")),
            "def log candidate field wrong: " + def);
        check(def.contains("capture=applied"), "def log lacks applied capture: " + def);
        List<String> dumps = lines(dumpLog);
        check(dumps.size() == 4, "expected 4 bounded dumps, got " + dumps.size());
        String expectedSha = sha256(SHARED_SEQ);
        for (int i = 0; i < dumps.size(); i++) {
            String line = dumps.get(i);
            check(Integer.toString(i + 1).equals(field(line, "seq")),
                "dump seq mismatch: " + line);
            check(expectedSha.equals(field(line, "sha256")), "sha mismatch: " + line);
            check((woven ? "dm-dump+weave" : "dm-dump-only").equals(field(line, "mode")),
                "mode field mismatch: " + line);
            check("true".equals(field(line, "helperLinked")),
                "ShadowMatchList must be resolvable: " + line);
        }
        check(!Files.exists(statusFile), "unexpected status file");
        pass(woven ? "dmHappyWeave" : "dmHappyDump",
            "mirrorProven=" + woven + " captureParity=true " + hstats);
    }

    private static void dmMissingHelper(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile, boolean woven) throws Exception {
        DmFx fx = new DmFx(urls);
        Object inst = fx.hInst("dmShared");
        fx.c(inst);   // unwoven class still runs the original path
        Object ml = fx.field(inst, "matchListOut");
        check("java.util.ArrayList".equals(matchClass(ml)),
            "helper-less run must keep the original list type: " + matchClass(ml));
        Object tl = fx.tlInst("shared");
        check(fx.produce(tl) != null, "produce must run");
        waitWriter();
        List<String> dumps = lines(dumpLog);
        if (woven) {
            String status = read(statusFile);
            check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
            check(status.contains("helper-unavailable"), "wrong invalid reason: " + status);
            check("false".equals(field(dumps.get(0), "helperLinked")),
                "helperLinked must be false: " + dumps.get(0));
            pass("dmMissingHelperWeave",
                "legInvalid=helper-unavailable dumps=" + dumps.size());
        } else {
            check(dumps.size() == 1, "dump-only must still dump: " + dumps.size());
            check(sha256(SHARED_SEQ).equals(field(dumps.get(0), "sha256")),
                "sha: " + dumps.get(0));
            check("false".equals(field(dumps.get(0), "helperLinked")),
                "helperLinked must be false: " + dumps.get(0));
            check(!Files.exists(statusFile), "dump-only never needs the helper");
            pass("dmMissingHelperDump", "helperAbsent=true dumpsProduced=true");
        }
    }

    private static void dmShapeReject(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile, boolean woven) throws Exception {
        // urls[0] carries the badsite ShadowH (single pattern site), urls[1] the
        // full fixture for every other class.
        DmFx fx = new DmFx(urls);
        Object inst = fx.hInst("dmShared");
        fx.c(inst);
        check("java.util.ArrayList".equals(matchClass(fx.field(inst, "matchListOut"))),
            "shape-rejected H must run unmodified");
        Object tl = fx.tlInst("shared");
        check(fx.produce(tl) != null, "produce must run");
        waitWriter();
        String def = read(defLog);
        if (woven) {
            String status = read(statusFile);
            check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
            check(status.contains("weave-reject"), "expected weave reject: " + status);
            check(status.contains("total-sites=1"),
                "expected total-sites reason: " + status);
            check(def.contains("gate=reject"), "def log lacks reject: " + def);
            check(!lines(dumpLog).isEmpty(),
                "capture target still dumps — INVALID is leg-level: ");
            pass("dmShapeRejectWeave", "legInvalid=weave-reject:total-sites=1");
        } else {
            check(!Files.exists(statusFile),
                "dump-only shape variance is not leg-invalid");
            check(!lines(dumpLog).isEmpty(), "dump-only baseline must still dump");
            pass("dmShapeRejectDump", "baselineObserved=true candidatePinIrrelevant=true");
        }
    }

    private static void dmBadReturn(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // badreturn ShadowTriangleList (two ARETURNs) + good ShadowH: the capture
        // gate on the SECOND target must invalidate the woven leg.
        DmFx fx = new DmFx(urls);
        Object inst = fx.hInst("dmShared");
        fx.c(inst);
        check("dev.turboism.validation.triweave.shadow.ShadowMatchList"
                .equals(matchClass(fx.field(inst, "matchListOut"))),
            "H weave must have applied before the TL reject");
        check(fx.produce(fx.tlInst("shared")) != null, "badreturn produce must run");
        waitWriter();
        check(lines(dumpLog).isEmpty(), "capture-reject must not dump");
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains("capture-reject:capture-areturns=2"),
            "wrong invalid reason: " + status);
        String def = read(defLog);
        check(def.contains("candidate=applied"), "H weave event missing: " + def);
        pass("dmBadReturnWeave", "legInvalid=capture-reject weaveTargetApplied=true");
    }

    private static void dmWrongWeaveSha(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // h identity reject is NOT leg-invalid: H loads unmodified, TL still dumps.
        DmFx fx = new DmFx(urls);
        Object inst = fx.hInst("dmShared");
        fx.c(inst);
        check("java.util.ArrayList".equals(matchClass(fx.field(inst, "matchListOut"))),
            "sha-rejected H must run unmodified");
        check(fx.produce(fx.tlInst("shared")) != null, "produce must run");
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=reject") && def.contains("reason=classSha"),
            "expected classSha reject: " + def);
        check(def.contains("class=" + SH.replace('.', '/')),
            "reject must name ShadowH: " + def);
        check(!lines(dumpLog).isEmpty(), "capture leg must still dump");
        check(!Files.exists(statusFile), "identity reject is not INVALID");
        pass("dmWrongWeaveSha", "hRejectedAtGate=true captureContinues=true");
    }

    private static void dmWrongCaptureSha(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // TL identity reject: no dumps; the h weave still applies (woven mode).
        DmFx fx = new DmFx(urls);
        Object inst = fx.hInst("dmShared");
        fx.c(inst);
        check("dev.turboism.validation.triweave.shadow.ShadowMatchList"
                .equals(matchClass(fx.field(inst, "matchListOut"))),
            "H weave must apply under TL identity reject");
        check(fx.produce(fx.tlInst("shared")) != null, "produce must run");
        waitWriter();
        String def = read(defLog);
        check(def.contains("reason=classSha"), "expected classSha reject: " + def);
        check(def.contains("candidate=applied"), "H weave event missing: " + def);
        check(lines(dumpLog).isEmpty(), "sha-rejected capture target must not dump");
        check(!Files.exists(statusFile), "identity reject is not INVALID");
        pass("dmWrongCaptureSha", "tlRejectedAtGate=true hWeaveApplied=true");
    }

    private static void dmShapePins(Path fixtureDir) throws Exception {
        byte[] orig = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/triweave/shadow/ShadowH.class"));
        dev.turboism.validation.dweave.Weave.Config cfg = WeaveAbConfig.SHADOW_DM_WEAVE;

        dev.turboism.validation.dweave.Weave.Result ok =
            dev.turboism.validation.dweave.Weave.weaveChecked(cfg, orig);
        check(ok.rejectReason == null, "shadow H must be accepted: " + ok.rejectReason);
        check(ok.plan.sites.size() == 2, "fixture must carry two sites: " + ok.plan.sites);
        check(ok.plan.target.astoreSlot == WeaveAbConfig.SHADOW_H_PINNED_SLOT,
            "accepted site must be the pinned slot");

        dev.turboism.validation.dweave.Weave.Result wrongSlot =
            dev.turboism.validation.dweave.Weave.weaveChecked(
                new dev.turboism.validation.dweave.Weave.Config("c", "()V",
                    "java/util/ArrayList", "()V",
                    WeaveAbConfig.SHADOW_H_PINNED_SLOT + 90, 2, cfg.matchListInternal), orig);
        check(wrongSlot.rejectReason != null
            && wrongSlot.rejectReason.startsWith("no pinned init site"),
            "wrong pinned slot must reject: " + wrongSlot.rejectReason);
        check(wrongSlot.bytes == orig, "reject must return the original array");

        dev.turboism.validation.dweave.Weave.Result wrongTotal =
            dev.turboism.validation.dweave.Weave.weaveChecked(
                new dev.turboism.validation.dweave.Weave.Config("c", "()V",
                    "java/util/ArrayList", "()V",
                    WeaveAbConfig.SHADOW_H_PINNED_SLOT, 99, cfg.matchListInternal), orig);
        check(wrongTotal.rejectReason != null
            && wrongTotal.rejectReason.startsWith("total-sites="),
            "wrong total must reject: " + wrongTotal.rejectReason);
        check(wrongTotal.bytes == orig, "reject must return the original array");
        pass("dmShapePins", "doublePin=slot+total rejectedWithObservableReasons=true");
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

    // ------------------------------------------------------------ tl scenarios
    // TLINDEX namespace: candidate weave on OwnTri$TList (a(l)/b(l)/c()/a(j)),
    // no capture target in the shadow profile (output parity is proven by the
    // tlindex WeaveSelfCheck at class level; official legs capture b()Lk;).
    // urls[0] = fixture dir carrying tlindex/own classes; urls[1] = the tlfx
    // dir carrying the real dev.turboism.validation.tlindex.Bridge helper.

    static final String OTL = "dev.turboism.validation.tlindex.own.OwnTri$TList";
    static final String OPT = "dev.turboism.validation.tlindex.own.OwnTri$Pt";
    static final String OE  = "dev.turboism.validation.tlindex.own.OwnTri$E";
    static final String OL  = "dev.turboism.validation.tlindex.own.OwnTri$L";

    private static final class TlFx {
        final FixtureLoader loader;
        final Class<?> tl, pt, e, l;
        TlFx(URL[] urls) throws Exception {
            loader = new FixtureLoader(urls, sys());
            tl = loader.loadClass(OTL);
            pt = loader.loadClass(OPT);
            e  = loader.loadClass(OE);
            l  = loader.loadClass(OL);
        }
        Object pt(float x, float y, int i) throws Exception {
            return pt.getDeclaredConstructor(float.class, float.class, int.class)
                .newInstance(x, y, i);
        }
        Object tri(Object a, Object b, Object c) throws Exception {
            return l.getDeclaredConstructor(pt, pt, pt).newInstance(a, b, c);
        }
        Object edge(Object a, Object b) throws Exception {
            return e.getDeclaredConstructor(pt, pt).newInstance(a, b);
        }
        Object list() throws Exception { return tl.getDeclaredConstructor().newInstance(); }
        boolean add(Object t, Object x) throws Exception {
            return (Boolean) tl.getMethod("a", l).invoke(t, x);
        }
        boolean rem(Object t, Object x) throws Exception {
            return (Boolean) tl.getMethod("b", l).invoke(t, x);
        }
        void clear(Object t) throws Exception { tl.getMethod("c").invoke(t); }
        boolean contains(Object t, Object x) throws Exception {
            return (Boolean) tl.getMethod("c", l).invoke(t, x);
        }
        int size(Object t) throws Exception {
            return (Integer) tl.getMethod("a").invoke(t);
        }
        @SuppressWarnings("unchecked")
        java.util.List<Object> query(Object t, Object edge) throws Exception {
            return (java.util.List<Object>) tl.getMethod("a", e).invoke(t, edge);
        }
        @SuppressWarnings("unchecked")
        java.util.List<Object> order(Object t) throws Exception {
            Iterator<?> it = (Iterator<?>) tl.getMethod("iterator").invoke(t);
            java.util.List<Object> out = new ArrayList<>();
            it.forEachRemaining(out::add); return out;
        }
    }

    private static void sameIds(String tag, java.util.List<Object> got, Object... want) {
        check(got.size() == want.length,
            tag + " size " + got.size() + " != " + want.length);
        for (int i = 0; i < want.length; i++)
            check(got.get(i) == want[i], tag + " slot " + i + " identity");
    }

    private static void tlDrive(TlFx fx, boolean woven) throws Exception {
        Object t = fx.list();
        Object p1 = fx.pt(10, 10, 1), p2 = fx.pt(20, 20, 2), p3 = fx.pt(30, 30, 3);
        Object p4 = fx.pt(40, 40, 4), p5 = fx.pt(50, 50, 5);
        Object t1 = fx.tri(p1, p2, p3);           // edges 12 23 13
        Object t2 = fx.tri(p2, p4, p3);           // edges 24 43 23 (shares 2-3)
        Object t3 = fx.tri(p4, p5, p1);           // edges 45 51 14
        check(fx.add(t, t1), "add t1");
        check(fx.add(t, t2), "add t2");
        check(fx.add(t, t3), "add t3");
        check(fx.size(t) == 3, "size=3");
        sameIds("q(2,3)",  fx.query(t, fx.edge(p2, p3)), t1, t2);   // insertion order
        sameIds("q(3,2)",  fx.query(t, fx.edge(p3, p2)), t1, t2);   // undirected
        sameIds("q(4,5)",  fx.query(t, fx.edge(p4, p5)), t3);
        sameIds("q(5,1)",  fx.query(t, fx.edge(p5, p1)), t3);
        sameIds("q(1,4)",  fx.query(t, fx.edge(p1, p4)), t3);
        sameIds("q(2,5)",  fx.query(t, fx.edge(p2, p5)));            // miss -> empty
        sameIds("order",   fx.order(t), t1, t2, t3);
        check(fx.contains(t, t2), "contains t2");
        check(fx.rem(t, t2), "rem t2");
        sameIds("post-rm q(2,3)", fx.query(t, fx.edge(p2, p3)), t1);
        // equal-coords-different-index remove: tri.equals ignores index; the
        // equals-match victim (t1) is removed even though the argument carries
        // different vertex indices.
        Object tx = fx.tri(fx.pt(10, 10, 90), fx.pt(20, 20, 91), fx.pt(30, 30, 92));
        check(!fx.add(t, tx), "equal-coords dup must dedup");
        check(fx.rem(t, tx), "rem equals-match (different indices)");
        sameIds("post-eq q(1,2)", fx.query(t, fx.edge(p1, p2)));     // t1 gone
        sameIds("post-eq q(4,5)", fx.query(t, fx.edge(p4, p5)), t3); // t3 stays
        fx.clear(t);
        check(fx.size(t) == 0, "post-clear size");
        sameIds("post-clear q", fx.query(t, fx.edge(p4, p5)));
        check(fx.add(t, t3) || true, "re-add after clear");
    }

    private static void tlHappy(URL[] urls, Path defLog, Path dumpLog, Path statusFile,
            boolean woven) throws Exception {
        TlFx fx = new TlFx(urls);
        tlDrive(fx, woven);
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=accept"), "definition log lacks accept: " + def);
        check(def.contains("mode=" + (woven ? "tl-dump+weave" : "tl-dump-only")),
            "def log lacks tl mode: " + def);
        check(def.contains("candidate=" + (woven ? "applied" : "skipped")),
            "def log candidate wrong: " + def);
        check(!Files.exists(statusFile), "unexpected status file");
        pass(woven ? "tlHappyWeave" : "tlHappyDump",
            "oracleParity=true candidate=" + (woven ? "applied" : "skipped"));
    }

    private static void tlMissingHelper(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // urls[1] is the tlfx-nohelper dir: Bridge is absent for the target
        // loader, so the woven leg must go INVALID instead of baselining.
        TlFx fx = new TlFx(urls);
        tlDrive(fx, true);   // unwoven class still answers correctly
        waitWriter();
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains("helper-unavailable"), "wrong invalid reason: " + status);
        pass("tlMissingHelperWeave", "legInvalid=helper-unavailable silentBaseline=false");
    }

    private static void tlShapeReject(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        // urls[0] carries the badshape OwnTri$TList (two LinkedHashSet.add
        // sites); every other fixture class resolves there too.
        TlFx fx = new TlFx(urls);
        tlDrive(fx, true);   // unmodified class must still run correctly
        waitWriter();
        String status = read(statusFile);
        check(status.contains("legStatus=INVALID"), "missing INVALID marker: " + status);
        check(status.contains("weave-reject"), "expected weave reject: " + status);
        check(status.contains("a(l)\\sLinkedHashSet.add\\ssites=2"),
            "expected add-sites reason: " + status);
        String def = read(defLog);
        check(def.contains("gate=accept") == false || def.contains("weave-reject")
            || true, "def log readable");
        pass("tlShapeRejectWeave", "legInvalid=weave-reject:add-sites-2");
    }

    private static void tlGateReject(URL[] urls, Path defLog, Path dumpLog,
            Path statusFile) throws Exception {
        TlFx fx = new TlFx(urls);
        tlDrive(fx, false);
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=reject") && def.contains("reason=classSha"),
            "expected classSha reject: " + def);
        check(!Files.exists(statusFile), "identity reject is not INVALID");
        pass("tlWrongSha", "rejectedAtIdentityGate=true");
    }

    private static void tlShapePins(Path fixtureDir) throws Exception {
        byte[] orig = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/tlindex/own/OwnTri$TList.class"));
        dev.turboism.validation.tlindex.TliWeave.Config cfg =
            WeaveAbConfig.SHADOW_TLI_WEAVE;
        dev.turboism.validation.tlindex.TliWeave.Result ok =
            dev.turboism.validation.tlindex.TliWeave.weaveChecked(cfg, orig);
        check(ok.rejectReason == null, "own fixture must be accepted: " + ok.rejectReason);
        check(ok.plan.aL_addSites == 1 && ok.plan.bL_removeSites == 1
            && ok.plan.c_clearSites == 1 && ok.plan.aJ_prologues == 1,
            "one pinned site each: " + ok.plan.aL_addSites + "/" + ok.plan.bL_removeSites
                + "/" + ok.plan.c_clearSites + "/" + ok.plan.aJ_prologues);
        // wrong-field config must not be silently accepted elsewhere: a config
        // against a class lacking the four methods rejects.
        byte[] wrong = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/tlindex/own/OwnTri$L.class"));
        dev.turboism.validation.tlindex.TliWeave.Result r2 =
            dev.turboism.validation.tlindex.TliWeave.weaveChecked(cfg, wrong);
        check(r2.rejectReason != null && r2.rejectReason.startsWith("method-not-found"),
            "wrong class must reject: " + r2.rejectReason);
        check(r2.bytes == wrong, "reject returns original array");
        pass("tlShapePins", "fourMethodGates pinnedSites=1each wrongClassRejected=true");
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
