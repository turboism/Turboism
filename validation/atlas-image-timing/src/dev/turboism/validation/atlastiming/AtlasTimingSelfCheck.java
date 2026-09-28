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
                "throwingUpdate", "(ZLjava/lang/Object;)V", AtlasTimingTargets.UPDATE_TEXTURE),
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
        check(outcome.matches().get("throwingUpdate(ZLjava/lang/Object;)V") == 1,
            "throwingUpdate must match exactly once");
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

        // The five T029-P3A metrics weave and count on a fixture mirroring the
        // updateMesh internals chain; a wrong descriptor must not be marked woven.
        final byte[] meshOriginal = Files.readAllBytes(fixtureDir.resolve(
            "dev/turboism/validation/atlastiming/fixture/FixtureMesh.class"));
        final String meshOwner = "dev/turboism/validation/atlastiming/fixture/FixtureMesh";
        final List<AtlasTimingTargets.Target> meshTargets = List.of(
            new AtlasTimingTargets.Target(
                meshOwner, "updateVertices", "()V", AtlasTimingTargets.UPDATE_VERTICES),
            new AtlasTimingTargets.Target(
                meshOwner, "updateIndices", "(Ljava/lang/Object;)V",
                AtlasTimingTargets.UPDATE_INDICES),
            new AtlasTimingTargets.Target(
                meshOwner, "delaunayCompute", "()Ljava/util/List;",
                AtlasTimingTargets.DELAUNAY_COMPUTE),
            new AtlasTimingTargets.Target(
                meshOwner, "delaunayApply", "(Ljava/util/List;ZLjava/lang/Object;)V",
                AtlasTimingTargets.DELAUNAY_APPLY),
            new AtlasTimingTargets.Target(
                meshOwner, "autoTriangulate", "(Ljava/lang/Object;)V",
                AtlasTimingTargets.AUTO_TRIANGULATE),
            new AtlasTimingTargets.Target(
                meshOwner, "updateIndices", "(I)V", AtlasTimingTargets.UPDATE_INDICES));
        final AtlasTimingTransformer.Outcome meshOutcome = AtlasTimingTransformer.instrument(
            meshOriginal, meshOwner, meshTargets);
        check(meshOutcome.bytes() != null, "mesh fixture instrumented bytes must be produced");
        check(meshOutcome.matches().get("updateVertices()V") == 1,
            "updateVertices must match exactly once");
        check(meshOutcome.matches().get("updateIndices(Ljava/lang/Object;)V") == 1,
            "updateIndices must match exactly once");
        check(meshOutcome.matches().get("delaunayCompute()Ljava/util/List;") == 1,
            "delaunayCompute must match exactly once");
        check(meshOutcome.matches().get("delaunayApply(Ljava/util/List;ZLjava/lang/Object;)V") == 1,
            "delaunayApply must match exactly once");
        check(meshOutcome.matches().get("autoTriangulate(Ljava/lang/Object;)V") == 1,
            "autoTriangulate must match exactly once");
        check(!meshOutcome.matches().containsKey("updateIndices(I)V"),
            "a wrong descriptor must not be marked woven");

        // Define the woven fixture on an isolated loader and invoke the nested path.
        final class Loader extends ClassLoader {
            Class<?> define(final String name, final byte[] bytes) {
                return defineClass(name, bytes, 0, bytes.length);
            }
        }
        final Object woven = new Loader()
            .define("dev.turboism.validation.atlastiming.fixture.FixtureAtlas", outcome.bytes())
            .getDeclaredConstructor().newInstance();
        final Method update = woven.getClass().getMethod("updateTexture", boolean.class, Object.class);
        update.invoke(woven, true, new Object());
        update.invoke(woven, false, new Object());
        final Method catching = woven.getClass().getMethod("catchingPath");
        catching.invoke(woven);
        final Method setup = woven.getClass().getMethod("setupCacheImage", boolean.class, Object.class);
        setup.invoke(woven, true, new Object());

        final Object wovenMesh = new Loader()
            .define("dev.turboism.validation.atlastiming.fixture.FixtureMesh",
                meshOutcome.bytes())
            .getDeclaredConstructor().newInstance();
        wovenMesh.getClass().getMethod("driveMesh", Object.class).invoke(wovenMesh, new Object());
        // A woven ARETURN must pass the real returned object through unchanged.
        final Object returned = wovenMesh.getClass()
            .getMethod("delaunayCompute").invoke(wovenMesh);
        final Object sentinel = wovenMesh.getClass()
            .getField("SENTINEL").get(null);
        check(returned == sentinel,
            "woven ARETURN must return the exact sentinel instance");

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
        final java.util.Properties summary = new java.util.Properties();
        summary.load(new java.io.StringReader(
            Files.readString(output.resolve("timing-summary.properties"))));
        check("2".equals(summary.getProperty("metric.updateTexture.count")),
            "summary must count updateTexture exactly twice");
        check("0".equals(summary.getProperty("metric.updateMesh.count")),
            "thrown method must record enter but no exit");
        check("1".equals(summary.getProperty("unpaired")),
            "the stale throwingPath entry must surface as unpaired when catchingPath exits");
        check(summary.getProperty("targets") != null, "summary must carry target states");
        check(summary.getProperty("blocked") != null, "summary must carry blocked state");
        // driveMesh counts each chain member once; the direct sentinel call adds a second
        // delaunayCompute record.
        final String[][] expectedCounts = {
            {"updateVertices", "1"}, {"updateIndices", "1"}, {"delaunayCompute", "2"},
            {"delaunayApply", "1"}, {"autoTriangulate", "1"},
        };
        for (final String[] expected : expectedCounts) {
            check(expected[1].equals(summary.getProperty("metric." + expected[0] + ".count")),
                "summary must count " + expected[0] + " exactly " + expected[1] + " time(s)");
        }

        // Unrelated class is left alone.
        final AtlasTimingTransformer.Outcome unrelated = AtlasTimingTransformer.instrument(
            original, "dev/turboism/validation/atlastiming/fixture/Other", fixtureTargets);
        check(unrelated.bytes() == null, "unrelated class must pass through");

        // --- T029-STACK: independent bounded entry-side stack sampling ---------------
        // Everything above ran with no engine: disabled sampling must have zero footprint.
        check(StackSamples.state().startsWith("disabled"),
            "stack sampling must start disabled, got " + StackSamples.state());
        check(!threadExists("turboism-stack-samples"),
            "disabled sampling must not create the writer thread");
        check(!Files.exists(output.resolve(StackSamples.RECORDS_FILE)),
            "disabled sampling must not create the records file");

        final Path stackDir = output.resolve("stack-evidence");
        StackSamples.enableForTest(512, 512, stackDir, null, null, null);
        // id0 + nested id1 = 2 samples; the id5/id6 path must not be sampled at all.
        update.invoke(woven, true, new Object());
        catching.invoke(woven);
        // A sampled entry that exits by throwing still lands as entry-side evidence.
        try {
            woven.getClass().getMethod("throwingUpdate", boolean.class, Object.class)
                .invoke(woven, true, new Object());
            check(false, "throwingUpdate must throw");
        } catch (java.lang.reflect.InvocationTargetException expected) {
            // Expected fixture failure.
        }
        // Depth beyond the cap must be truncated, not dropped or unbounded.
        woven.getClass().getMethod("deepDrive", int.class).invoke(woven, 140);
        // A non-EDT-named thread records its own tid/thread name on the stack line.
        final Thread markerWorker = new Thread(() -> {
            try {
                update.invoke(woven, false, new Object());
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }, "t029-marker-worker");
        markerWorker.start();
        markerWorker.join();

        final Path stackRecords = stackDir.resolve(StackSamples.RECORDS_FILE);
        final Path stackStatus = stackDir.resolve(StackSamples.STATUS_FILE);
        final String stackBody = awaitStatus(stackStatus, "written", 7L);
        check(stackBody != null, "stack status must reach written=7");
        check(Files.isRegularFile(stackRecords), "stack records file must exist");
        final List<String> stackLines = Files.exists(stackRecords)
            ? Files.readAllLines(stackRecords) : List.of();
        check(stackLines.size() == 7,
            "expected 7 stack records, got " + stackLines.size());
        final java.util.Set<String> seqs = new java.util.HashSet<>();
        boolean sawThrowing = false;
        boolean sawTruncated = false;
        int workerLines = 0;
        for (final String line : stackLines) {
            final int seqAt = line.indexOf("seq=");
            if (seqAt >= 0) {
                seqs.add(line.substring(seqAt + 4, line.indexOf(' ', seqAt)));
            }
            check(line.contains("metric=updateTexture")
                    || line.contains("metric=setupCacheImage"),
                "stack line must only carry sampled metric ids 0/1: " + line);
            if (line.contains("FixtureAtlas.throwingUpdate")) {
                sawThrowing = true;
            }
            if (line.contains("truncated=1") && line.contains("FixtureAtlas.deepDrive")) {
                sawTruncated = true;
            }
            if (line.contains("thread=\"t029-marker-worker\"")) {
                workerLines++;
            }
        }
        check(seqs.size() == stackLines.size(),
            "seq must be a unique reservation id, got " + seqs.size());
        check(sawThrowing,
            "an exceptional exit must still produce entry-side stack evidence");
        check(sawTruncated, "deep stacks must be truncated at the depth cap");
        check(workerLines == 2,
            "worker-thread entry must record 2 lines, got " + workerLines);

        // Over-budget: reservations stop at the cap; dropped budget is counted, not sampled.
        final Path smallDir = output.resolve("stack-budget");
        final StackSamples.Engine small =
            StackSamples.enableForTest(3, 512, smallDir, null, null, null);
        update.invoke(woven, true, new Object());
        update.invoke(woven, false, new Object());
        update.invoke(woven, true, new Object());
        java.util.Map<String, Long> snap = small.snapshot();
        check(snap.get("attempted") == 6L, "attempted must count all calls, got "
            + snap.get("attempted"));
        check(snap.get("reserved") == 3L, "reserved must cap at the budget, got "
            + snap.get("reserved"));
        check(snap.get("droppedBudget") == 3L, "over-budget drops must be counted, got "
            + snap.get("droppedBudget"));

        // Full queue: the gated writer holds the drain closed so the offer-fail path is
        // deterministic — no timing race.
        final Path fullDir = output.resolve("stack-queue");
        final java.util.concurrent.CountDownLatch gate = new java.util.concurrent.CountDownLatch(1);
        final StackSamples.Engine gated =
            StackSamples.enableForTest(16, 1, fullDir, null, gate, null);
        update.invoke(woven, true, new Object());
        update.invoke(woven, false, new Object());
        snap = gated.snapshot();
        check(snap.get("reserved") == 4L, "gated run must reserve 4, got "
            + snap.get("reserved"));
        check(snap.get("queued") == 1L, "capacity-1 queue must accept one, got "
            + snap.get("queued"));
        check(snap.get("droppedQueue") == 3L, "queue overflow must be counted, got "
            + snap.get("droppedQueue"));
        gate.countDown();
        check(awaitStatus(fullDir.resolve(StackSamples.STATUS_FILE), "written", 1L) != null,
            "gated writer must drain the single queued record");

        // IO loss: records file path is a directory, so every write fails and is counted.
        final Path ioDir = output.resolve("stack-io");
        Files.createDirectories(ioDir.resolve(StackSamples.RECORDS_FILE));
        final StackSamples.Engine ioBroken =
            StackSamples.enableForTest(16, 8, ioDir, null, null, null);
        update.invoke(woven, true, new Object());
        final String ioStatus = awaitStatus(ioDir.resolve(StackSamples.STATUS_FILE),
            "unconfirmedWrites", 2L);
        check(ioStatus != null, "unconfirmed writes must surface in status");
        check(ioStatus != null && ioStatus.contains("incomplete=1"),
            "a run with unconfirmed writes must be marked incomplete");
        check(ioBroken.snapshot().get("written") == 0L,
            "no record may be counted written when writes fail");

        // Sampling collector failure: counted, swallowed, and pairing/call records intact.
        final Path failDir = output.resolve("stack-sampleerror");
        final StackSamples.Engine broken =
            StackSamples.enableForTest(16, 8, failDir,
                () -> { throw new IllegalStateException("injected collector failure"); }, null, null);
        update.invoke(woven, true, new Object());
        snap = broken.snapshot();
        check(snap.get("sampleError") == 2L, "collector failures must count, got "
            + snap.get("sampleError"));
        check(snap.get("queued") == 0L, "failed samples must not be queued");
        check(snap.get("incomplete") == 1L, "sample errors must mark the run incomplete");

        // Deterministic offer/consume interleave: the writer gate holds the drain closed
        // while samples queue, then the sink gate parks the writer inside the write call so
        // snapshots observe a mid-write state exactly — no timing race.
        final Path interleaveDir = output.resolve("stack-interleave");
        final java.util.concurrent.CountDownLatch drainGate =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch sinkEntered =
            new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch sinkRelease =
            new java.util.concurrent.CountDownLatch(1);
        final StackSamples.Engine interleaved = StackSamples.enableForTest(64, 64,
            interleaveDir, null, drainGate, batch -> {
                sinkEntered.countDown();
                try {
                    sinkRelease.await();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
                Files.write(interleaveDir.resolve(StackSamples.RECORDS_FILE), batch,
                    java.nio.charset.StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND);
            });
        update.invoke(woven, true, new Object());
        update.invoke(woven, false, new Object());
        snap = interleaved.snapshot();
        check(snap.get("queued") == 4L && snap.get("dequeued") == 0L,
            "gated writer must not have consumed yet, got " + snap);
        drainGate.countDown();
        check(sinkEntered.await(15, java.util.concurrent.TimeUnit.SECONDS),
            "writer must reach the gated sink");
        // Writer holds the whole batch mid-write: inWrite=4, everything conserved.
        snap = interleaved.snapshot();
        checkConserved(snap);
        check(snap.get("inWrite") == 4L && snap.get("dequeued") == 4L
                && snap.get("pending") == 0L,
            "mid-write batch must be inWrite, got " + snap);
        // A concurrent offer lands while the writer is blocked inside the write call.
        update.invoke(woven, true, new Object());
        snap = interleaved.snapshot();
        checkConserved(snap);
        check(snap.get("queued") == 6L && snap.get("dequeued") == 4L
                && snap.get("pending") == 2L,
            "offer during mid-write must stay consistent, got " + snap);
        sinkRelease.countDown();
        // Concurrent snapshots during production must never go negative or lose balance.
        final Thread producer = new Thread(() -> {
            try {
                for (int i = 0; i < 20; i++) {
                    update.invoke(woven, true, new Object());
                }
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException(failure);
            }
        }, "t029-producer");
        producer.start();
        for (int i = 0; i < 200 && producer.isAlive(); i++) {
            checkConserved(interleaved.snapshot());
        }
        producer.join();
        check(awaitStatus(interleaveDir.resolve(StackSamples.STATUS_FILE),
            "written", 46L) != null, "interleave run must drain all 46 records");

        // Writer death: a non-IO sink failure kills the daemon; the terminal reason is
        // observable in memory and the run is marked incomplete — disk state unguaranteed.
        final Path termDir = output.resolve("stack-terminal");
        final StackSamples.Engine dying = StackSamples.enableForTest(16, 8, termDir,
            null, null, batch -> { throw new AssertionError("injected writer death"); });
        update.invoke(woven, true, new Object());
        check(awaitTerminal(dying), "writer death must surface a terminal reason");
        snap = dying.snapshot();
        check(snap.get("terminated") == 1L && snap.get("incomplete") == 1L,
            "dead writer must report terminated+incomplete, got " + snap);
        checkConserved(snap);

        // Second flush cycle so every leg above is on disk before the final counts.
        AtlasTimingProbe.flush();
        Thread.sleep(600);
        AtlasTimingProbe.flush();
        final List<String> allCalls =
            Files.readAllLines(output.resolve("timing-calls.txt"));
        final long totalUpdates =
            allCalls.stream().filter(l -> l.contains("call updateTexture")).count();
        final long totalSetups =
            allCalls.stream().filter(l -> l.contains("call setupCacheImage")).count();
        check(totalUpdates == 36L,
            "timing pairing must be unaffected by sampling, updateTexture=" + totalUpdates);
        check(totalSetups == 37L,
            "timing pairing must be unaffected by sampling, setupCacheImage=" + totalSetups);

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

    /** Every counter must be non-negative and the transfer chain must conserve. */
    private static void checkConserved(final java.util.Map<String, Long> snap) {
        for (final java.util.Map.Entry<String, Long> entry : snap.entrySet()) {
            check(entry.getValue() >= 0,
                "counter " + entry.getKey() + " must never be negative: " + snap);
        }
        check(snap.get("reserved")
                == snap.get("sampleError") + snap.get("droppedQueue") + snap.get("queued")
                    + snap.get("inFlight"),
            "reservation conservation violated: " + snap);
        check(snap.get("queued") == snap.get("dequeued") + snap.get("pending"),
            "queue conservation violated: " + snap);
        check(snap.get("dequeued")
                == snap.get("written") + snap.get("unconfirmedWrites") + snap.get("inWrite"),
            "write conservation violated: " + snap);
    }

    private static boolean awaitTerminal(final StackSamples.Engine engine)
            throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 15_000L;
        while (System.currentTimeMillis() < deadline) {
            if (engine.terminal() != null) {
                return true;
            }
            Thread.sleep(25);
        }
        return false;
    }

    private static boolean threadExists(final String name) {
        for (final Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return true;
            }
        }
        return false;
    }

    /** Bounded wait for the single-writer status file to reach a counter value. */
    private static String awaitStatus(final Path status, final String key, final long expected)
            throws java.io.IOException, InterruptedException {
        final long deadline = System.currentTimeMillis() + 15_000L;
        String body = null;
        while (System.currentTimeMillis() < deadline) {
            if (Files.isRegularFile(status)) {
                body = Files.readString(status);
                final java.util.Properties props = new java.util.Properties();
                props.load(new java.io.StringReader(body));
                final String value = props.getProperty(key);
                if (value != null && Long.parseLong(value) >= expected) {
                    return body;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }
}
