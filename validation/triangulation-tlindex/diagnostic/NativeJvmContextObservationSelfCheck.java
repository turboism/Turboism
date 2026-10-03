import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;

public final class NativeJvmContextObservationSelfCheck {
    private NativeJvmContextObservationSelfCheck() {}

    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]); Files.createDirectories(run);
        boolean enabled = ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
        var before = NativeJvmContextObservation.capture();
        long current = Thread.currentThread().getId();
        assert before.rows().stream().anyMatch(r -> r.kind().equals("heap") && r.second() >= r.first());
        assert before.rows().stream().anyMatch(r -> r.kind().equals("thread") && r.id() == current);
        assert before.epochEndMillis() >= before.epochStartMillis();
        assert before.nanoEnd() >= before.nanoStart();
        assert enabled == ManagementFactory.getThreadMXBean().isThreadCpuTimeEnabled();
        assert NativeJvmContextObservation.safe("a\tb\nc\rd").equals("a b c d");
        var after = NativeJvmContextObservation.capture();
        NativeJvmContextObservation.publish(before, after);
        try {
            NativeJvmContextObservation.publish(before, after);
            throw new AssertionError("overwriting pending context accepted");
        } catch (IllegalStateException expected) { /* refusal preserves first pair */ }
        NativeJvmContextObservation.persistPending(run, 1);
        var lines = Files.readAllLines(run.resolve("native-jvm-context.tsv"));
        assert lines.size() == before.rows().size() + after.rows().size() + 1;
        assert lines.stream().allMatch(line -> line.split("\t", -1).length == 13);
        try {
            NativeJvmContextObservation.persistPending(run, 2);
            throw new AssertionError("absent pair accepted");
        } catch (IllegalStateException expected) { /* no stale snapshot reuse */ }
        Path bad = run.resolve("bad"); Files.createDirectories(bad.resolve("native-jvm-context.tsv"));
        try {
            NativeJvmContextObservation.persist(bad, "test", 0, before);
            throw new AssertionError("non-file evidence accepted");
        } catch (IllegalStateException expected) { /* fail closed */ }
        System.out.println("PASS_JVM_CONTEXT_REAL_MXBEANS_PENDING_LIFECYCLE_AND_WRITER");
    }
}
