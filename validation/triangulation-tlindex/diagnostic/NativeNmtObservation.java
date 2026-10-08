import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import javax.management.ObjectName;

/** Independent task diagnostic. Uses the local platform MBean, never attach or GC. */
final class NativeNmtObservation {
    private NativeNmtObservation() {}
    record Snapshot(long epochStartMillis, long epochEndMillis, long nanoStart, long nanoEnd, String summary, String heapInfo) {}

    static Snapshot capture() throws Exception {
        long epoch = System.currentTimeMillis();
        long nano = System.nanoTime();
        Object value = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "vmNativeMemory",
                new Object[] {new String[] {"summary", "scale=KB"}}, new String[] {"[Ljava.lang.String;"});
        if (!(value instanceof String text) || !text.contains("Native Memory Tracking:")
                || !text.contains("Total: reserved=") || text.contains("Native memory tracking is not enabled")) {
            throw new IllegalStateException("in-process NMT summary unavailable");
        }
        Object heap = ManagementFactory.getPlatformMBeanServer().invoke(
                new ObjectName("com.sun.management:type=DiagnosticCommand"), "gcHeapInfo",
                new Object[0], new String[0]);
        if (!(heap instanceof String heapText) || !heapText.contains("garbage-first heap")
                || !heapText.matches("(?s).*\\[0x[0-9a-fA-F]+, 0x[0-9a-fA-F]+\\).*")) {
            throw new IllegalStateException("G1 heap range unavailable");
        }
        return new Snapshot(epoch, System.currentTimeMillis(), nano, System.nanoTime(), text, heapText);
    }

    static void persist(Path run, String phase, int operation, Snapshot value) throws Exception {
        Path target = run.resolve("native-nmt-summary.tsv");
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Files.writeString(target,
                    "phase\toperation\tepochStartMillis\tepochEndMillis\tnanoStart\tnanoEnd\tsummaryBase64\theapInfoBase64\n",
                    StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
        }
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalStateException("NMT evidence must be a regular file");
        }
        if (!phase.matches("[a-z-]+")) throw new IllegalArgumentException("invalid NMT phase");
        Files.writeString(target, phase + "\t" + operation + "\t" + value.epochStartMillis() + "\t"
                + value.epochEndMillis() + "\t" + value.nanoStart() + "\t" + value.nanoEnd() + "\t"
                + Base64.getEncoder().encodeToString(value.summary().getBytes(StandardCharsets.UTF_8)) + "\t"
                + Base64.getEncoder().encodeToString(value.heapInfo().getBytes(StandardCharsets.UTF_8)) + "\n",
                StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }
}
