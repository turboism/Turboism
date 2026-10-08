import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import javax.swing.SwingUtilities;

/** Driver-thread persistence, after the native interval and detached snapshot. */
final class ObserverFreeEvidenceWriter {
    private ObserverFreeEvidenceWriter() {}
    static void begin(Path run) throws Exception {
        requireDriverThread();
        Files.writeString(run.resolve("observer-free-command-cpu.tsv"), CommandCpuBoundary.header(),
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        Files.writeString(run.resolve("observer-free-results.tsv"),
                "cycle\tsourceIdBase64\tpointCount\tpositionValues\tindexValues\tpositionsSha256\tindicesSha256\tbeforeEdgeVersion\tcommandEdgeVersion\tindexCacheVersion\tpositionVersion\tvertexCacheVersion\n",
                StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
    }
    static void append(Path run, int cycle, NativeObserverFreeAutoConnect.Observation observed) throws Exception {
        requireDriverThread();
        if (cycle < 1 || cycle > 3 || observed == null || observed.results().size() != 711)
            throw new IllegalStateException("incomplete exact command observation");
        Path cpu = run.resolve("observer-free-command-cpu.tsv");
        Path output = run.resolve("observer-free-results.tsv");
        if (!Files.isRegularFile(cpu, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("evidence target absent or symbolic");
        Files.writeString(cpu, CommandCpuBoundary.row("native-command-before", cycle, observed.before())
                + CommandCpuBoundary.row("native-command-after", cycle, observed.after()),
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
        StringBuilder rows = new StringBuilder();
        for (var r : observed.results()) {
            var a = r.arrays();
            rows.append(cycle).append('\t').append(Base64.getEncoder().encodeToString(
                    r.sourceId().getBytes(StandardCharsets.UTF_8))).append('\t')
                    .append(a.pointCount()).append('\t').append(a.positionValues()).append('\t')
                    .append(a.indexValues()).append('\t').append(a.positionsSha256()).append('\t')
                    .append(a.indicesSha256()).append('\t').append(r.beforeEdgeVersion()).append('\t')
                    .append(a.edgeVersion()).append('\t').append(r.indexCacheVersion()).append('\t')
                    .append(r.positionVersion()).append('\t').append(r.vertexCacheVersion()).append('\n');
        }
        Files.writeString(output, rows.toString(), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
    private static void requireDriverThread() {
        if (SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("evidence I/O prohibited on EDT");
    }
}
