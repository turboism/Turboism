import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Properties;
import javax.swing.SwingUtilities;

/** Exercises the actual generated writer with detached own-fixture evidence. */
public final class CommandReturnWriterSelfCheck {
    private static int checks;
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        checks++;
    }
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]);
        Files.createDirectory(run);
        String id = "source\t\n\u4e09";
        var arrays = MeshResultSnapshot.snapshot(3, 8,
                new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2});
        var row = new NativeCommandReturnSnapshot.Result(id, arrays, 2, 3, 3, 7);
        var producer = new NativeProducerAutoConnect.Observation(true, true, List.of(), "");
        var paired = new NativeCommandReturnAutoConnect.Observation(producer, List.of(row), true, "");
        T040ShadowSceneDriverAgent.persistCommandReturnObservation(run, paired, 1);
        Path table = run.resolve("auto-connect-command-return-results.tsv");
        var lines = Files.readAllLines(table, StandardCharsets.UTF_8);
        require(lines.size() == 2, "one header and one row");
        var columns = lines.get(1).split("\t", -1);
        require(columns.length == 12, "exact row schema");
        require(new String(Base64.getDecoder().decode(columns[1]), StandardCharsets.UTF_8).equals(id),
                "source encoding roundtrip");
        require(columns[2].equals("3") && columns[3].equals("6") && columns[4].equals("3"),
                "full geometry shape");
        require(columns[5].equals(arrays.positionsSha256()) && columns[6].equals(arrays.indicesSha256()),
                "exact result hashes");
        require(columns[7].equals("8") && columns[8].equals("7") && columns[9].equals("2")
                && columns[10].equals("3") && columns[11].equals("3"), "distinct boundary versions");
        var refused = new NativeCommandReturnAutoConnect.Observation(producer, List.of(), false,
                "COMMAND_RETURN_OBSERVATION_FAILED:IllegalStateException");
        T040ShadowSceneDriverAgent.persistCommandReturnObservation(run, refused, 2);
        require(Files.readAllLines(table).size() == 2, "failure does not fabricate rows");
        Properties status = new Properties();
        try (var stream = Files.newInputStream(run.resolve("auto-connect-command-return-status.properties"))) {
            status.load(stream);
        }
        require(status.getProperty("cycle.1.complete").equals("true")
                && status.getProperty("cycle.1.rows").equals("1"), "success status");
        require(status.getProperty("cycle.2.complete").equals("false")
                && status.getProperty("cycle.2.rows").equals("0")
                && status.getProperty("cycle.2.failure").equals(refused.failure()), "failure retained");
        final boolean[] blocked = {false};
        SwingUtilities.invokeAndWait(() -> {
            try { T040ShadowSceneDriverAgent.persistCommandReturnObservation(run, paired, 3); }
            catch (IllegalStateException expected) { blocked[0] = true; }
            catch (Exception unexpected) { throw new AssertionError(unexpected); }
        });
        require(blocked[0] && Files.readAllLines(table).size() == 2, "EDT I/O refused before mutation");
        System.out.println("CommandReturnWriterSelfCheck PASS checks=" + checks + " hostExecuted=false");
    }
}
