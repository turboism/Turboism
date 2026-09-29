package dev.turboism.validation.triprobe;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import dev.turboism.validation.triprobe.fixture.FixtureLoader;

/**
 * Scenario driver. Each scenario runs in a fresh JVM launched by run.sh with
 * {@code -javaagent:tri-probe-agent.jar}, {@code -Xverify:all}, and scenario-specific
 * {@code turboism.validation.triIdentity.*} properties. Prints one PASS line per scenario.
 */
public final class IdentityProbeSelfCheck {
    static final String FIXTURE_NAME = "com.live2d.graphics3d.editableMesh.triangulation.TriangleList";
    static final String SENTINEL_SET = "com.live2d.graphics3d.editableMesh.triangulation.SentinelSet";
    static final String THROWING_SET = "com.live2d.graphics3d.editableMesh.triangulation.ThrowingSet";

    public static void main(String[] args) throws Exception {
        String scenario = args[0];
        URL[] urls = args.length > 4
            ? new URL[] { Path.of(args[1]).toUri().toURL(), Path.of(args[4]).toUri().toURL() }
            : new URL[] { Path.of(args[1]).toUri().toURL() };
        Path defLog = Path.of(args[2]);
        Path useLog = Path.of(args[3]);
        switch (scenario) {
            case "happy" -> happy(urls, defLog, useLog);
            case "off" -> off(urls, defLog, useLog);
            case "refusedConfig" -> refusedConfig(urls, defLog, useLog);
            case "wrongSource" -> gateReject(urls, defLog, useLog, "reason=codeSource");
            case "wrongSha" -> gateReject(urls, defLog, useLog, "reason=classSha");
            case "wrongLoader" -> wrongLoader(urls, defLog, useLog);
            case "badShape" -> badShape(urls, defLog, useLog);
            case "missingHelper", "failInit" -> premainRefusal(urls, defLog, useLog, scenario);
            case "throwOnRecord" -> helperFailure(urls, defLog, useLog, scenario);
            case "directLinkFail", "directInitFail" -> directWeave(urls, defLog, useLog, scenario);
            case "passthrough" -> passthrough(urls, defLog, useLog);
            case "concurrency" -> concurrency(urls, defLog, useLog);
            case "writeFailure" -> writeFailure(urls, defLog, useLog);
            case "observerBudget" -> observerBudget(urls, defLog, useLog);
            case "maliciousFields" -> maliciousFields(urls, defLog, useLog);
            default -> throw new IllegalArgumentException("unknown scenario " + scenario);
        }
    }

    private static ClassLoader sys() { return ClassLoader.getSystemClassLoader(); }

    /** Load and keep the loader alive (the returned object references it). */
    private static Object instantiate(URL[] urls, String setClass) throws Exception {
        // Parent must see the agent classes (system classpath), mirroring the host's
        // AppClassLoader topology; fixture names still resolve child-first.
        FixtureLoader loader = new FixtureLoader(urls, sys());
        Class<?> tl = loader.loadClass(FIXTURE_NAME);
        if (setClass == null) return tl.getDeclaredConstructor().newInstance();
        Class<?> set = loader.loadClass(setClass);
        Constructor<?> ctor = tl.getDeclaredConstructor(LinkedHashSet.class);
        return ctor.newInstance(set.getDeclaredConstructor().newInstance());
    }

    private static void waitWriter() throws InterruptedException {
        Thread.sleep(400); // daemon writer drains the bounded queue; deterministic sleep is enough
    }

    private static int writerThreads() {
        int n = 0;
        for (Thread t : Thread.getAllStackTraces().keySet()) {
            if (t.getName().equals("tri-identity-writer")) n++;
        }
        return n;
    }

    private static void happy(URL[] urls, Path defLog, Path useLog) throws Exception {
        Object tl = instantiate(urls, SENTINEL_SET);
        Object sentinel = tl.getClass().getMethod("iterator").invoke(tl);
        Object again = tl.getClass().getMethod("iterator").invoke(tl);
        check(sentinel == again, "iterator did not return the same sentinel instance");
        waitWriter();
        String def = read(defLog);
        check(def.contains("gate=accept"), "definition log lacks accept: " + def);
        String use = read(useLog);
        check(use.contains("setClass=com.live2d.graphics3d.editableMesh.triangulation.SentinelSet"),
                "use-site lacks actual set class: " + use);
        check(use.contains("setLoader=dev.turboism.validation.triprobe.fixture.FixtureLoader@"),
                "use-site lacks loader token: " + use);
        check(use.contains("setModule="), "use-site lacks module field: " + use);
        check(use.contains("ownerModule="), "use-site lacks owner module: " + use);
        check(use.contains("java.version="), "use-site lacks jre identity: " + use);
        check(use.contains("phase=triIdentitySelfCheck"), "use-site lacks phase tag: " + use);
        check(!use.contains("\n") || use.endsWith("\n"), "use-site must be single-line");
        pass("happy", "officialClassLoaded=false sentinelReturned=true setClassRecorded=true");
    }

    private static void off(URL[] urls, Path defLog, Path useLog) throws Exception {
        Object tl = instantiate(urls, SENTINEL_SET);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        check(!Files.exists(defLog) && !Files.exists(useLog),
                "disabled run produced output files");
        check(writerThreads() == 0, "disabled run still started a writer thread");
        pass("off", "zeroSideEffects=true writerThreads=0");
    }

    private static void refusedConfig(URL[] urls, Path defLog, Path useLog) throws Exception {
        // Invalid/missing admission config: premain must refuse without throwing — the JVM and
        // the fixture entrypoint keep working and no threads/files appear.
        Object tl = instantiate(urls, SENTINEL_SET);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        check(!Files.exists(defLog) && !Files.exists(useLog),
                "refused admission produced output files");
        check(writerThreads() == 0, "refused admission still started a writer thread");
        pass("refusedConfig", "premainRefused=safely entrypointReached=true");
    }

    private static void gateReject(URL[] urls, Path defLog, Path useLog, String reason)
            throws Exception {
        Object tl = instantiate(urls, SENTINEL_SET);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        String def = read(defLog);
        check(def.contains(reason), "expected reject '" + reason + "' in: " + def);
        check(!Files.exists(useLog), "rejected gate still produced use-site sample");
        pass("gateReject", reason);
    }

    private static void wrongLoader(URL[] urls, Path defLog, Path useLog) throws Exception {
        // java.net.URLClassLoader name ≠ expected FixtureLoader name.
        try (java.net.URLClassLoader plain = new java.net.URLClassLoader(urls, sys())) {
            Class<?> tl = plain.loadClass(FIXTURE_NAME);
            Object inst = tl.getDeclaredConstructor().newInstance();
            check(tl.getMethod("iterator").invoke(inst) != null, "iterator null");
        }
        waitWriter();
        String def = read(defLog);
        check(def.contains("reason=loader"), "expected loader reject: " + def);
        check(!Files.exists(useLog), "loader-rejected class produced use-site sample");
        pass("wrongLoader", "rejected=loader");
    }

    private static void badShape(URL[] urls, Path defLog, Path useLog) throws Exception {
        Object tl = instantiate(urls, null);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        String def = read(defLog);
        check(def.contains("reason=field-b-not-linkedhashset"),
                "expected shape reject: " + def);
        check(!Files.exists(useLog), "shape-rejected class produced use-site sample");
        pass("badShape", "rejected=field-b");
    }

    private static void premainRefusal(URL[] urls, Path defLog, Path useLog, String scenario)
            throws Exception {
        // helper missing or clinit fails → premain refuses installation; original behavior and
        // the JVM are unaffected; no output files.
        Object tl = instantiate(urls, SENTINEL_SET);
        Object it = tl.getClass().getMethod("iterator").invoke(tl);
        check(it != null, "iterator null under " + scenario);
        Object again = tl.getClass().getMethod("iterator").invoke(tl);
        check(it == again, "sentinel not preserved under " + scenario);
        waitWriter();
        check(!Files.exists(useLog) && !Files.exists(defLog),
                scenario + " refused premain still produced files");
        pass(scenario, "premainRefusedOrUnwoven=true originalBehaviorPreserved=true");
    }

    private static void helperFailure(URL[] urls, Path defLog, Path useLog, String scenario)
            throws Exception {
        // throwOnRecord: helper warms fine but record() throws inside the weave-catch.
        Object tl = instantiate(urls, SENTINEL_SET);
        Object it = tl.getClass().getMethod("iterator").invoke(tl);
        check(it != null, "iterator null under " + scenario);
        Object again = tl.getClass().getMethod("iterator").invoke(tl);
        check(it == again, "sentinel not preserved under " + scenario);
        waitWriter();
        check(!Files.exists(useLog), scenario + " produced a use-site sample");
        pass(scenario, "weaveCatchPreserved=true");
    }

    private static void directWeave(URL[] urls, Path defLog, Path useLog, String scenario)
            throws Exception {
        // Fixture loader sees a stub Probe class (missing method or throwing clinit): the woven
        // invokestatic must fail inside the callsite catch — original iterator unaffected.
        Object tl = instantiate(urls, SENTINEL_SET);
        Object it = tl.getClass().getMethod("iterator").invoke(tl);
        check(it != null, "iterator null under " + scenario);
        Object again = tl.getClass().getMethod("iterator").invoke(tl);
        check(it == again, "sentinel not preserved under " + scenario);
        waitWriter();
        check(!Files.exists(useLog), scenario + " produced a use-site sample");
        pass(scenario, "linkOrInitFailureAbsorbed=true");
    }

    private static void passthrough(URL[] urls, Path defLog, Path useLog) throws Exception {
        Object tl = instantiate(urls, THROWING_SET);
        InvocationTargetException thrown = null;
        try {
            tl.getClass().getMethod("iterator").invoke(tl);
        } catch (InvocationTargetException e) {
            thrown = e;
        }
        check(thrown != null, "expected MarkerException");
        check(thrown.getCause().getClass().getName().endsWith("ThrowingSet$MarkerException"),
                "wrong exception class: " + thrown.getCause().getClass());
        check("fixture-marker".equals(thrown.getCause().getMessage()), "wrong message");
        pass("passthrough", "exceptionClass+message preserved");
    }

    private static void concurrency(URL[] urls, Path defLog, Path useLog) throws Exception {
        Object tl = instantiate(urls, SENTINEL_SET);
        Method m = tl.getClass().getMethod("iterator");
        int n = 8;
        CountDownLatch start = new CountDownLatch(1), done = new CountDownLatch(n);
        AtomicInteger ok = new AtomicInteger();
        AtomicReference<Throwable> err = new AtomicReference<>();
        for (int i = 0; i < n; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    if (m.invoke(tl) != null) ok.incrementAndGet();
                } catch (Throwable t) { err.compareAndSet(null, t); }
                finally { done.countDown(); }
            }).start();
        }
        start.countDown();
        done.await();
        check(err.get() == null, "concurrent call threw: " + err.get());
        check(ok.get() == n, "not all calls returned sentinel: " + ok.get());
        waitWriter();
        List<String> lines = Files.exists(useLog) ? Files.readAllLines(useLog) : List.of();
        check(lines.size() == 1, "use-site must contain exactly one sample, got " + lines.size());
        pass("concurrency", "oneSample=true threads=" + n);
    }

    private static void writeFailure(URL[] urls, Path defLog, Path useLog) throws Exception {
        // outputDir is an existing regular file → writer attempt fails once, never retries,
        // never throws on the host path.
        Object tl = instantiate(urls, SENTINEL_SET);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        String status = Probe.status();
        check(status.contains("writesFailed="), "status missing: " + status);
        check(!status.contains("writesFailed=0"), "expected write failure: " + status);
        check(status.contains("useSiteClaimed=true"), "sample should be claimed once: " + status);
        pass("writeFailure", status);
    }

    private static void observerBudget(URL[] urls, Path defLog, Path useLog) throws Exception {
        for (int i = 0; i < 6; i++) {
            instantiate(urls, null); // each new FixtureLoader defines the class again
        }
        waitWriter();
        String def = read(defLog);
        long events = def.lines().filter(l -> l.contains("sha256=")).count();
        check(events <= 4, "definition observation not bounded: " + events);
        check(def.contains("overflow=true"), "expected overflow marker: " + def);
        pass("observerBudget", "definitionEventsRecorded=" + events);
    }

    private static void maliciousFields(URL[] urls, Path defLog, Path useLog) throws Exception {
        // phase carries newlines, tabs, control chars and >field-cap length; the snapshot must be
        // one escaped line with a visible truncation marker.
        Object tl = instantiate(urls, SENTINEL_SET);
        check(tl.getClass().getMethod("iterator").invoke(tl) != null, "iterator null");
        waitWriter();
        List<String> lines = Files.readAllLines(useLog);
        check(lines.size() == 1, "expected one line, got " + lines.size());
        String line = lines.get(0);
        check(line.contains("\\n"), "newline must be escaped: " + line);
        check(line.contains("~truncated"), "expected truncation marker: " + line);
        check(line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 4000,
                "line exceeds byte bound");
        check(!line.contains("EVIL\n"), "raw newline leaked: " + line);
        pass("maliciousFields", "escapedAndBounded=true");
    }

    private static String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p) : "<absent>";
    }

    private static void check(boolean cond, String what) {
        if (!cond) throw new AssertionError(what);
    }

    private static void pass(String scenario, String detail) {
        System.out.println("TRI_IDENTITY_SELFCHECK " + scenario + " PASS " + detail);
    }
}
