package dev.turboism.validation.atlastiming;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Offline self-check for the timing weave. Defines and executes only this module's own fixture
 * classes; official Cubism bytes are never loaded or executed here.
 */
public final class AtlasTimingSelfCheck {
    private AtlasTimingSelfCheck() {
    }

    private static int failures;

    public static void main(final String[] args) throws Exception {
        final Path fixtureDir = Path.of(args[0]);
        final Path output = Path.of(args[1]);
        Files.createDirectories(output);
        System.setProperty("turboism.validation.atlasTiming.output", output.toString());

        final byte[] original = Files.readAllBytes(
            fixtureDir.resolve("dev/turboism/validation/atlastiming/fixture/FixtureAtlas.class"));

        final List<AtlasTimingTargets.Target> fixtureTargets = List.of(
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas",
                "updateTexture", "(ZLjava/lang/Object;)V", AtlasTimingTargets.UPDATE_TEXTURE),
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas",
                "setupCacheImage", "(ZLjava/lang/Object;)V", AtlasTimingTargets.SETUP_CACHE_IMAGE),
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas",
                "throwingPath", "()V", AtlasTimingTargets.UPDATE_MESH),
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas",
                "catchingPath", "()V", AtlasTimingTargets.SETUP_EDIT_LAYER),
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas",
                "missingMethod", "()V", AtlasTimingTargets.EDITOR_INIT)
        );

        final AtlasTimingTransformer.Outcome outcome = AtlasTimingTransformer.instrument(
            original, "dev/turboism/validation/atlastiming/fixture/FixtureAtlas", fixtureTargets);
        check(outcome.bytes() != null, "instrumented bytes must be produced");
        check(outcome.matches().get("updateTexture(ZLjava/lang/Object;)V") == 1,
            "updateTexture must match exactly once");
        check(outcome.matches().get("setupCacheImage(ZLjava/lang/Object;)V") == 1,
            "setupCacheImage must match exactly once");
        check(!outcome.matches().containsKey("missingMethod()V"),
            "absent method must not be marked woven");

        // An abstract target is marked ABSTRACT and produces no weave.
        final byte[] abstractOwner = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/atlastiming/fixture/FixtureAtlas$AbstractBase.class"));
        final List<AtlasTimingTargets.Target> abstractTargets = List.of(
            new AtlasTimingTargets.Target(
                "dev/turboism/validation/atlastiming/fixture/FixtureAtlas$AbstractBase",
                "notConcrete", "()V", AtlasTimingTargets.EDITOR_BATCH));
        final AtlasTimingTransformer.Outcome abstractOutcome = AtlasTimingTransformer.instrument(
            abstractOwner,
            "dev/turboism/validation/atlastiming/fixture/FixtureAtlas$AbstractBase",
            abstractTargets);
        check(abstractOutcome.matches().get("notConcrete()V") == -1,
            "abstract method must be marked, not woven");
        check(abstractOutcome.bytes() == null, "abstract-only class must not be rewritten");

        // Define the woven fixture on an isolated loader and invoke the nested path.
        final class Loader extends ClassLoader {
            Class<?> define(final byte[] bytes) {
                return defineClass("dev.turboism.validation.atlastiming.fixture.FixtureAtlas",
                    bytes, 0, bytes.length);
            }
        }
        final Object woven = new Loader().define(outcome.bytes())
            .getDeclaredConstructor().newInstance();
        final Method update = woven.getClass().getMethod("updateTexture", boolean.class, Object.class);
        update.invoke(woven, true, new Object());
        update.invoke(woven, false, new Object());
        final Method catching = woven.getClass().getMethod("catchingPath");
        catching.invoke(woven);
        final Method setup = woven.getClass().getMethod("setupCacheImage", boolean.class, Object.class);
        setup.invoke(woven, true, new Object());

        AtlasTimingProbe.flush();
        Thread.sleep(600);
        AtlasTimingProbe.flush();

        final List<String> calls = Files.readAllLines(output.resolve("timing-calls.txt"));
        final long updates = calls.stream().filter(l -> l.contains("call updateTexture")).count();
        final long setups = calls.stream().filter(l -> l.contains("call setupCacheImage")).count();
        final long catches = calls.stream().filter(l -> l.contains("call setupEditLayer")).count();
        check(updates == 2, "expected 2 updateTexture records, got " + updates);
        check(setups == 3, "expected 3 setupCacheImage records, got " + setups);
        check(catches == 1, "expected 1 catchingPath record, got " + catches);
        final String summary = Files.readString(output.resolve("timing-summary.properties"));
        check(summary.contains("metric.updateTexture.count=2"), "summary must count updateTexture");
        check(summary.contains("metric.updateMesh.count=0"),
            "thrown method must record enter but no exit");
        check(summary.contains("unpaired=1"),
            "the stale throwingPath entry must surface as unpaired when catchingPath exits");
        check(summary.contains("targets="), "summary must carry target states");
        check(summary.contains("blocked="), "summary must carry blocked state");

        // Unrelated class is left alone.
        final AtlasTimingTransformer.Outcome unrelated = AtlasTimingTransformer.instrument(
            original, "dev/turboism/validation/atlastiming/fixture/Other", fixtureTargets);
        check(unrelated.bytes() == null, "unrelated class must pass through");

        if (failures > 0) {
            System.out.println("ATLAS_TIMING_SELFCHECK FAILED failures=" + failures);
            System.exit(1);
        }
        System.out.println("ATLAS_TIMING_SELFCHECK PASS"
            + " updateCalls=" + updates + " setupCalls=" + setups
            + " officialClassLoaded=false officialExecuted=false");
    }

    private static void check(final boolean condition, final String message) {
        if (!condition) {
            failures++;
            System.out.println("  FAIL " + message);
        }
    }
}
