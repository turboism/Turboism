import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.nio.charset.StandardCharsets;

public final class NativeNmtObservationSelfCheck {
    private NativeNmtObservationSelfCheck() {}
    public static void main(String[] args) throws Exception {
        if (args[0].equals("disabled")) {
            try {
                NativeNmtObservation.capture();
                throw new AssertionError("disabled NMT accepted");
            } catch (IllegalStateException expected) {
                System.out.println("PASS_NMT_DISABLED_REFUSAL");
            }
            return;
        }
        Path run = Path.of(args[0]); Files.createDirectories(run);
        var snapshot = NativeNmtObservation.capture();
        assert snapshot.epochEndMillis() >= snapshot.epochStartMillis();
        assert snapshot.nanoEnd() >= snapshot.nanoStart();
        assert snapshot.summary().contains("Java Heap") && snapshot.summary().contains("GC");
        NativeNmtObservation.persist(run, "mesh-baseline-start", 0, snapshot);
        var lines = Files.readAllLines(run.resolve("native-nmt-summary.tsv"));
        assert lines.size() == 2;
        String[] fields = lines.get(1).split("\t", -1);
        assert fields.length == 8;
        assert new String(Base64.getDecoder().decode(fields[6]), StandardCharsets.UTF_8).equals(snapshot.summary());
        assert new String(Base64.getDecoder().decode(fields[7]), StandardCharsets.UTF_8).equals(snapshot.heapInfo());
        Path invalid = run.resolve("invalid"); Files.createDirectories(invalid.resolve("native-nmt-summary.tsv"));
        try {
            NativeNmtObservation.persist(invalid, "mesh-baseline-start", 0, snapshot);
            throw new AssertionError("non-file NMT output accepted");
        } catch (IllegalStateException expected) { /* fail closed */ }
        System.out.println("PASS_REAL_IN_PROCESS_NMT_SUMMARY_WITH_ATTACH_DISABLED");
    }
}
