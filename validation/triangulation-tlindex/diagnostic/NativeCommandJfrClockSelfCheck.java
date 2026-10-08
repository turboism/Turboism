import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import javax.swing.SwingUtilities;
import jdk.jfr.Event;
import jdk.jfr.Name;
import jdk.jfr.Recording;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

/** Real JFR recording verifies scope even when Java's supplied epoch clock is offset. */
public final class NativeCommandJfrClockSelfCheck {
    private static int checks;
    @Name("dev.turboism.validation.ScopeFixture")
    static final class Marker extends Event { public String phase; }
    public static final class Command {
        public void run(Object document) { marker("inside"); }
        public void fail(Object document) { throw new IllegalArgumentException("fixture failure"); }
    }
    static void marker(String phase) { Marker event = new Marker(); event.phase = phase; event.commit(); }
    static void check(boolean ok) { checks++; if (!ok) throw new AssertionError("check " + checks); }
    static CommandCpuBoundary cpu() {
        AtomicLong count = new AtomicLong();
        return new CommandCpuBoundary(count::incrementAndGet, System::nanoTime,
                () -> System.currentTimeMillis() + 5000);
    }
    public static void main(String[] args) throws Exception {
        Path path = Path.of(args[0]);
        System.setProperty("turboism.validation.atlasImageShadow.taskId", "offline-scope-fixture");
        Command command = new Command();
        var method = Command.class.getMethod("run", Object.class);
        try (Recording recording = new Recording()) {
            recording.enable(NativeCommandJfrClock.EVENT).withoutStackTrace();
            recording.enable("dev.turboism.validation.ScopeFixture").withoutStackTrace();
            recording.start();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    try { NativeCommandJfrClock.invokeMeasured(method, command, command, cpu(), 1);
                          throw new AssertionError("missing opt-in admitted"); }
                    catch (IllegalStateException expected) { check(true); }
                    System.setProperty("turboism.validation.jfrNativeClock.optIn", NativeCommandJfrClock.TOKEN);
                    marker("before");
                    var interval = NativeCommandJfrClock.invokeMeasured(method, command, command, cpu(), 1);
                    check(interval.before().processCpuNanos() == 1 && interval.after().processCpuNanos() == 2);
                    marker("after");
                    try { NativeCommandJfrClock.invokeMeasured(Command.class.getMethod("fail", Object.class),
                                command, command, cpu(), 2); throw new AssertionError("failure swallowed"); }
                    catch (IllegalArgumentException expected) { check(true); }
                    try { NativeCommandJfrClock.invokeMeasured(method, command, command, cpu(), 0);
                          throw new AssertionError("invalid cycle admitted"); }
                    catch (IllegalStateException expected) { check(true); }
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            recording.stop();
            recording.dump(path);
        }
        List<RecordedEvent> events = RecordingFile.readAllEvents(path);
        var invocations = events.stream().filter(e -> e.getEventType().getName().equals(NativeCommandJfrClock.EVENT)).toList();
        check(invocations.size() == 2);
        RecordedEvent invocation = invocations.stream().filter(e -> e.getInt("cycle") == 1).findFirst().orElseThrow();
        check(invocation.getBoolean("nativeReturned"));
        check(invocation.getString("taskId").equals("offline-scope-fixture"));
        check(invocation.getLong("beforeCpuNanos") == 1 && invocation.getLong("afterCpuNanos") == 2);
        check(invocation.getLong("beforeReadStartEpochMillis") - invocation.getStartTime().toEpochMilli() >= 4000);
        check(invocation.getThread().getJavaName().startsWith("AWT-EventQueue"));
        for (RecordedEvent event : events) if (event.getEventType().getName().equals("dev.turboism.validation.ScopeFixture")) {
            boolean inside = !event.getStartTime().isBefore(invocation.getStartTime())
                    && !event.getStartTime().isAfter(invocation.getEndTime());
            check(inside == event.getString("phase").equals("inside"));
        }
        check(!invocations.stream().filter(e -> e.getInt("cycle") == 2).findFirst().orElseThrow().getBoolean("nativeReturned"));
        try { NativeCommandJfrClock.invokeMeasured(method, command, command, cpu(), 3);
              throw new AssertionError("non-EDT admitted"); }
        catch (IllegalStateException expected) { check(true); }
        SwingUtilities.invokeAndWait(() -> {
            try { NativeCommandJfrClock.invokeMeasured(method, command, command, cpu(), 3);
                  throw new AssertionError("disabled recording admitted"); }
            catch (IllegalStateException expected) { check(true); }
            catch (Exception failure) { throw new RuntimeException(failure); }
        });
        List<NativeObserverFreeAutoConnect.Interval> completeIntervals = new ArrayList<>();
        Path completePath = Path.of(path + ".complete.jfr");
        try (Recording complete = new Recording()) {
            complete.enable(NativeCommandJfrClock.EVENT).withoutStackTrace();
            complete.start();
            SwingUtilities.invokeAndWait(() -> {
                try {
                    for (int cycle = 1; cycle <= 3; cycle++)
                        completeIntervals.add(NativeCommandJfrClock.invokeMeasured(
                                method, command, command, cpu(), cycle));
                } catch (Exception failure) { throw new RuntimeException(failure); }
            });
            complete.stop();
            complete.dump(completePath);
        }
        check(completeIntervals.size() == 3);
        check(RecordingFile.readAllEvents(completePath).stream().filter(
                e -> e.getEventType().getName().equals(NativeCommandJfrClock.EVENT)).count() == 3);
        StringBuilder rows = new StringBuilder(CommandCpuBoundary.header());
        for (int i = 0; i < completeIntervals.size(); i++) {
            rows.append(CommandCpuBoundary.row("native-command-before", i + 1, completeIntervals.get(i).before()));
            rows.append(CommandCpuBoundary.row("native-command-after", i + 1, completeIntervals.get(i).after()));
        }
        Files.writeString(Path.of(path + ".complete.tsv"), rows,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        System.out.println("NativeCommandJfrClockSelfCheck PASS checks=" + checks);
    }
}
