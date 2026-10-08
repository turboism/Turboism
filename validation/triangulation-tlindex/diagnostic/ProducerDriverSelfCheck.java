import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

/** Executes the generated driver's actual evidence writer, not a parallel imitation. */
public final class ProducerDriverSelfCheck {
    private static int checks;
    private ProducerDriverSelfCheck() {}
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]); Files.createDirectory(run);
        Files.writeString(run.resolve("auto-connect-producer-results.tsv"),
            "cycle\tinvocation\tsourceIdBase64\tthreadId\tstartedNanos\treturnedNanos\tstatus\tpointCount\tedgeVersion\tpositionValues\tindexValues\tpositionsSha256\tindicesSha256\tfailureBase64\n",
            StandardCharsets.UTF_8);
        Files.writeString(run.resolve("auto-connect-producer-status.properties"), "recorder=PRODUCER_ENTRY_RETURN_V1\n");
        String id = "source\t汉字\n";
        String failure = "native\tfailed\n";
        var result = MeshResultSnapshot.snapshot(3, 370, new float[] {0, 0, 1, 0, 0, 1}, new int[] {0, 1, 2});
        var success = new MeshProducerRecorder.Event(1, 1, id, 2, 3, 4, result, null);
        var failed = new MeshProducerRecorder.Event(2, 1, id, 2, 5, 6, null, failure);
        var observation = new NativeProducerAutoConnect.Observation(true, false, List.of(success, failed), failure);
        T040ShadowSceneDriverAgent.persistProducerObservation(run, observation, 1, "INITIAL_DEFINITION_WOVEN");
        List<String> rows = Files.readAllLines(run.resolve("auto-connect-producer-results.tsv"));
        require(rows.size() == 3);
        String[] first = rows.get(1).split("\t", -1), last = rows.get(2).split("\t", -1);
        require(first.length == 14 && last.length == 14);
        require(decoded(first[2]).equals(id) && decoded(last[2]).equals(id));
        require(first[6].equals("PASS") && first[7].equals("3") && first[10].equals("3") && first[13].isEmpty());
        require(first[11].equals(result.positionsSha256()) && first[12].equals(result.indicesSha256()));
        require(last[6].equals("FAIL") && last[7].equals("-1") && decoded(last[13]).equals(failure));
        String status = Files.readString(run.resolve("auto-connect-producer-status.properties"));
        require(status.contains("cycle.1.status=FAIL\n") && status.contains("cycle.1.nativeCommandReturned=true\n"));
        require(status.contains("cycle.1.hookStatus=INITIAL_DEFINITION_WOVEN\n"));
        require(status.contains("cycle.1.failureBase64=" + Base64.getEncoder().encodeToString(failure.getBytes(StandardCharsets.UTF_8)) + "\n"));
        var complete = new NativeProducerAutoConnect.Observation(true, true,
            List.of(new MeshProducerRecorder.Event(1, 2, id, 2, 7, 8, result, null)), "");
        T040ShadowSceneDriverAgent.persistProducerObservation(run, complete, 2, "INITIAL_DEFINITION_WOVEN");
        require(Files.readAllLines(run.resolve("auto-connect-producer-results.tsv")).size() == 4);
        require(Files.readString(run.resolve("auto-connect-producer-status.properties")).contains("cycle.2.status=PASS\n"));
        System.out.println("Generated producer evidence writer PASS: " + checks + " hostExecuted=false");
    }
    private static String decoded(String value) { return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8); }
    private static void require(boolean value) { if (!value) throw new AssertionError("check failed"); checks++; }
}
