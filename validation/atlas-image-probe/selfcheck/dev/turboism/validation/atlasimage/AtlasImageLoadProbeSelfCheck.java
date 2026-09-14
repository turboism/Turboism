package dev.turboism.validation.atlasimage;

import java.io.ByteArrayInputStream;
import java.lang.instrument.Instrumentation;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.Map;
import java.util.Properties;

/** Runs only project-owned synthetic classes; never loads Cubism classes. */
public final class AtlasImageLoadProbeSelfCheck {
    private static final String TARGET = "dev/turboism/validation/atlasimage/SyntheticLoadTarget";
    private static AtlasImageLoadProbeAgent.Recorder live;
    private static int checks;

    private AtlasImageLoadProbeSelfCheck() {}

    public static void premain(final String ignored, final Instrumentation instrumentation) throws Exception {
        final String hash;
        try (var in = AtlasImageLoadProbeSelfCheck.class.getResourceAsStream("/" + TARGET + ".class")) {
            hash = AtlasImageLoadProbeAgent.streamHash(in);
        }
        final String origin = AtlasImageLoadProbeSelfCheck.class.getProtectionDomain()
            .getCodeSource().getLocation().toURI().toString();
        live = new AtlasImageLoadProbeAgent.Recorder(Map.of(TARGET, hash), origin, TARGET);
        instrumentation.addTransformer(live, false);
        for (Class<?> type : instrumentation.getAllLoadedClasses()) live.alreadyLoaded(type);
    }

    public static void main(final String[] args) throws Exception {
        check(live != null, "synthetic javaagent installed");
        check("BLOCKED".equals(live.snapshot().getProperty("status")), "unobserved blocks");
        Class.forName(TARGET.replace('/', '.'));
        check("PASS".equals(live.snapshot().getProperty("status")), "real JVM first definition observed");
        check("false".equals(live.snapshot().getProperty("guardInstalled")), "no guard claim");
        check("NOT_EVALUATED".equals(live.snapshot().getProperty("optimizationReadiness")), "no readiness claim");
        final byte[] bytes = {1, 2, 3};
        final String hash = AtlasImageLoadProbeAgent.streamHash(new ByteArrayInputStream(bytes));
        final Path temp = Files.createTempDirectory("atlas-probe-selfcheck-");
        final String origin = temp.resolve("fake.jar").toUri().toString();
        final ProtectionDomain domain = new ProtectionDomain(
            new CodeSource(java.net.URI.create(origin).toURL(), (java.security.cert.Certificate[]) null), null);
        var good = recorder(hash, origin);
        check(good.transform(null, "target", null, domain, bytes) == null, "observer never transforms");
        check(java.util.Arrays.equals(bytes, new byte[]{1, 2, 3}), "input bytes unchanged");
        check(good.snapshot().getProperty("event.0").contains("sha256=" + hash), "actual byte hash reported");
        check(good.snapshot().getProperty("event.0").contains("source="), "actual source reported");
        check("NOT_OBSERVED".equals(good.snapshot().getProperty("definitionSucceeded")), "definition success not claimed");
        check("NOT_OBSERVED".equals(good.snapshot().getProperty("retransformationCoverage")), "no retransformation claim");
        check("PASS".equals(good.snapshot().getProperty("status")), "matching identity");
        var wrong = recorder(hash, origin);
        wrong.transform(null, "target", null, domain, new byte[]{9});
        blocked(wrong, "wrong bytes");
        wrong = recorder(hash, origin + "-other");
        wrong.transform(null, "target", null, domain, bytes);
        blocked(wrong, "wrong source");
        wrong = recorder(hash, origin);
        wrong.transform(null, "target", null, null, bytes);
        blocked(wrong, "missing source");
        wrong = recorder(hash, origin);
        wrong.transform(null, "target", String.class, domain, bytes);
        blocked(wrong, "redefinition");
        good.alreadyLoaded("target");
        blocked(good, "late/racing initial loaded snapshot");
        var bounded = recorder(hash, origin);
        for (int i = 0; i < 70; i++) bounded.transform(null, "target", null, domain, bytes);
        blocked(bounded, "overflow");
        check("64".equals(bounded.snapshot().getProperty("eventCount")), "bounded events");
        var ended = recorder(hash, origin);
        ended.end();
        ended.transform(null, "target", null, domain, bytes);
        check("0".equals(ended.snapshot().getProperty("eventCount")), "ended observer ignores new events");
        var ignored = recorder(hash, origin);
        ignored.transform(null, "unrelated", null, domain, bytes);
        check("0".equals(ignored.snapshot().getProperty("eventCount")), "unrelated class ignored");
        check("run-001".equals(AtlasImageLoadProbeAgent.validRunId("run-001")), "run ID allowed");
        try { AtlasImageLoadProbeAgent.validRunId("../escape"); throw new AssertionError("run ID accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        try { AtlasImageLoadProbeAgent.required(new Properties(), "missing"); throw new AssertionError("missing accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        final Path run = temp.resolve("run-001");
        Files.createDirectory(run);
        try { Files.createDirectory(run); throw new AssertionError("duplicate output accepted"); }
        catch (java.nio.file.FileAlreadyExistsException expected) { checks++; }
        final Path output = run.resolve("result.properties");
        AtlasImageLoadProbeAgent.write(output, live.snapshot());
        final Properties persisted = new Properties();
        try (var in = Files.newInputStream(output)) { persisted.load(in); }
        check("PASS".equals(persisted.getProperty("status")), "result readback");
        final var loaded = new AtlasImageLoadProbeAgent.Recorder(
            Map.of("java/lang/String", "unused"), origin, "java/lang/String");
        loaded.alreadyLoaded(String.class);
        check(loaded.snapshot().getProperty("event.0").contains("runtimeByteHash=NOT_OBSERVED"),
            "already loaded byte hash not invented");
        check(AtlasImageLoadProbeAgent.targets("5203").contains("com/live2d/util/e/g"), "5203 target");
        check(AtlasImageLoadProbeAgent.targets("5303").contains("com/live2d/util/f/g"), "5303 target");
        Files.delete(output); Files.delete(run); Files.delete(temp);
        System.out.println("ATLAS_IMAGE_PROBE_SELFCHECK PASS checks=" + checks + " hostExecuted=false");
    }

    private static AtlasImageLoadProbeAgent.Recorder recorder(final String hash, final String origin) {
        return new AtlasImageLoadProbeAgent.Recorder(Map.of("target", hash), origin, "target");
    }
    private static void blocked(final AtlasImageLoadProbeAgent.Recorder recorder, final String label) {
        check("BLOCKED".equals(recorder.snapshot().getProperty("status")), label);
    }
    private static void check(final boolean condition, final String label) {
        if (!condition) throw new AssertionError(label);
        checks++;
    }
}

final class SyntheticLoadTarget {
    private SyntheticLoadTarget() {}
}
