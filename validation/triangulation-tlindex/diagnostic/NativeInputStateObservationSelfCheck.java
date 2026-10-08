import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import javax.swing.SwingUtilities;

public final class NativeInputStateObservationSelfCheck {
    private NativeInputStateObservationSelfCheck() { }
    private static void require(boolean condition) { if (!condition) throw new AssertionError(); }
    interface Checked { void run() throws Exception; }
    private static void refuse(Checked action) throws Exception {
        try { action.run(); } catch (IllegalStateException expected) { return; }
        throw new AssertionError("invalid input state accepted");
    }
    private static void edt(Checked action) throws Exception {
        Exception[] failure = {null};
        SwingUtilities.invokeAndWait(() -> { try { action.run(); } catch (Exception e) { failure[0] = e; } });
        if (failure[0] != null) throw failure[0];
    }
    public static void main(String[] args) throws Exception {
        Path run = Path.of(args[0]); Files.createDirectory(run);
        var s = new NativeInputStateObservation.Snapshot("widget\t\n", "工具", List.of("view\nstate"));
        Object hostile = new Object() { @Override public String toString() { throw new AssertionError("callback"); } };
        require(NativeInputStateObservation.rawScalars(hostile).startsWith(hostile.getClass().getName() + "@"));
        refuse(() -> NativeInputStateObservation.capture(null));
        refuse(() -> NativeInputStateObservation.publish(s, s));
        refuse(() -> NativeInputStateObservation.persist(run, 1));
        edt(() -> {
            NativeInputStateObservation.publish(s, s);
            refuse(() -> NativeInputStateObservation.publish(s, s));
            refuse(() -> NativeInputStateObservation.persist(run, 1));
        });
        NativeInputStateObservation.persist(run, 1);
        refuse(() -> NativeInputStateObservation.persist(run, 1));
        edt(() -> NativeInputStateObservation.publish(s, s));
        refuse(() -> NativeInputStateObservation.persist(run, 0));
        NativeInputStateObservation.persist(run, 2);
        List<String> rows = Files.readAllLines(run.resolve("native-input-state.tsv"));
        require(rows.size() == 5);
        for (int i = 1; i < rows.size(); i++) {
            String[] row = rows.get(i).split("\t", -1); require(row.length == 5);
            require(new String(Base64.getDecoder().decode(row[2]), StandardCharsets.UTF_8).equals(s.widget()));
            require(new String(Base64.getDecoder().decode(row[3]), StandardCharsets.UTF_8).equals(s.subtool()));
            require(new String(Base64.getDecoder().decode(row[4]), StandardCharsets.UTF_8).equals(s.views().get(0)));
        }
        System.out.println("NativeInputStateObservationSelfCheck PASS callback-free/thread/handoff/encoding controls");
    }
}
