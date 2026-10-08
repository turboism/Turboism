import java.nio.file.Files;
import java.nio.file.Path;

/** Owned Linux fixture: lets the independent parent inspect the real reported heap mapping. */
public final class NativeHeapPageSelfCheck {
    private NativeHeapPageSelfCheck() {}
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]); Files.createDirectories(run);
        NativeNmtObservation.persist(run, "mesh-baseline-start", 0, NativeNmtObservation.capture());
        Thread.sleep(5000L);
    }
}
