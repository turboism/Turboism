import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.swing.SwingUtilities;

/** Runs the actual writer with detached own-fixture results; no host startup. */
public final class ObserverFreeEvidenceWriterSelfCheck {
    private static int checks;
    interface Checked { void run() throws Exception; }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks++;
    }
    private static void refuse(Checked action) throws Exception {
        try { action.run(); }
        catch (IllegalStateException | java.nio.file.FileAlreadyExistsException expected) { checks++; return; }
        throw new AssertionError("invalid evidence write accepted");
    }
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]); Files.createDirectory(run);
        List<NativeObserverFreeAutoConnect.Result> results = new ArrayList<>();
        var arrays = MeshResultSnapshot.snapshot(3, 8, new float[] {0,0,1,0,0,1}, new int[] {0,1,2});
        for (int i = 0; i < 711; i++) results.add(new NativeObserverFreeAutoConnect.Result(
                "网格\t\n" + i, arrays, 7, 2, 3, 3));
        var before = new CommandCpuBoundary.Sample(10, 1, 2, 100, 100);
        var after = new CommandCpuBoundary.Sample(20, 3, 4, 101, 101);
        var observed = new NativeObserverFreeAutoConnect.Observation(before, after, List.copyOf(results));
        ObserverFreeEvidenceWriter.begin(run);
        refuse(() -> ObserverFreeEvidenceWriter.begin(run));
        ObserverFreeEvidenceWriter.append(run, 1, observed);
        List<String> rows = Files.readAllLines(run.resolve("observer-free-results.tsv"));
        require(rows.size() == 712 && rows.get(0).split("\t", -1).length == 12, "complete schema");
        for (int i : List.of(0, 710)) {
            String[] row = rows.get(i + 1).split("\t", -1);
            require(row.length == 12 && new String(Base64.getDecoder().decode(row[1]),
                    StandardCharsets.UTF_8).equals(results.get(i).sourceId()), "encoded source identity");
            require(row[5].equals(arrays.positionsSha256()) && row[6].equals(arrays.indicesSha256())
                    && row[7].equals("7") && row[8].equals("8") && row[9].equals("2"), "hash/version evidence");
        }
        List<String> cpu = Files.readAllLines(run.resolve("observer-free-command-cpu.tsv"));
        require(cpu.size() == 3 && cpu.get(1).startsWith("native-command-before\t1\t10\t")
                && cpu.get(2).startsWith("native-command-after\t1\t20\t"), "counter rows preserved");
        refuse(() -> ObserverFreeEvidenceWriter.append(run, 0, observed));
        refuse(() -> ObserverFreeEvidenceWriter.append(run, 1,
                new NativeObserverFreeAutoConnect.Observation(before, after, results.subList(0, 710))));
        final Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> {
            try {
                refuse(() -> ObserverFreeEvidenceWriter.append(run, 2, observed));
                refuse(() -> ObserverFreeEvidenceWriter.begin(run.resolve("edt")));
            } catch (Exception exception) { failure[0] = exception; }
        });
        if (failure[0] != null) throw failure[0];
        require(!Files.exists(run.resolve("edt")), "EDT refusal before I/O");
        require(Files.readAllLines(run.resolve("observer-free-results.tsv")).size() == 712,
                "refused writes add no output rows");
        System.out.println("ObserverFreeEvidenceWriterSelfCheck PASS checks=" + checks);
    }
}
