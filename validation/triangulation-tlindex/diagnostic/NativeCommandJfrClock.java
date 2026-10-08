import java.lang.reflect.Method;
import javax.swing.SwingUtilities;
import jdk.jfr.Enabled;
import jdk.jfr.Event;
import jdk.jfr.FlightRecorder;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;
import jdk.jfr.Threshold;

/** Same-recording envelope for the pre-resolved invocation and its two CPU reads. */
final class NativeCommandJfrClock {
    static final String EVENT = "dev.turboism.validation.NativeCommandInvocation";
    static final String TOKEN = "T076_JFR_NATIVE_INVOCATION_V1";
    private NativeCommandJfrClock() {}

    @Name(EVENT)
    @Enabled(true)
    @StackTrace(false)
    @Threshold("0 ns")
    static final class Invocation extends Event {
        public String taskId;
        public int cycle;
        public boolean nativeReturned;
        public long beforeCpuNanos;
        public long afterCpuNanos;
        public long beforeReadStartNanos;
        public long beforeReadEndNanos;
        public long afterReadStartNanos;
        public long afterReadEndNanos;
        public long beforeReadStartEpochMillis;
        public long beforeReadEndEpochMillis;
        public long afterReadStartEpochMillis;
        public long afterReadEndEpochMillis;
    }

    static NativeObserverFreeAutoConnect.Interval invokeMeasured(Method command, Object controller,
            Object document, CommandCpuBoundary cpu, int cycle) throws Exception {
        if (!SwingUtilities.isEventDispatchThread() || cycle < 1 || cycle > 3
                || !TOKEN.equals(System.getProperty("turboism.validation.jfrNativeClock.optIn"))
                || !FlightRecorder.isAvailable())
            throw new IllegalStateException("explicit task JFR invocation scope required");
        String task = System.getProperty("turboism.validation.atlasImageShadow.taskId");
        if (task == null || task.isBlank()) throw new IllegalStateException("task identity absent");
        Invocation event = new Invocation();
        if (!event.isEnabled()) throw new IllegalStateException("JFR invocation event disabled");
        event.taskId = task;
        event.cycle = cycle;
        // This JFR clock encloses only the existing pre-resolved reflective invocation
        // and its CPU reads. Preparation and result snapshots remain outside it.
        event.begin();
        NativeObserverFreeAutoConnect.Interval interval;
        try {
            interval = NativeObserverFreeAutoConnect.invokeMeasured(command, controller, document, cpu);
            event.nativeReturned = true;
        } finally {
            event.end();
            // Failure events are preserved too, but cannot authorize a complete result.
            if (!event.nativeReturned) event.commit();
        }
        CommandCpuBoundary.Sample before = interval.before(), after = interval.after();
        event.beforeCpuNanos = before.processCpuNanos();
        event.afterCpuNanos = after.processCpuNanos();
        event.beforeReadStartNanos = before.readStartNanos();
        event.beforeReadEndNanos = before.readEndNanos();
        event.afterReadStartNanos = after.readStartNanos();
        event.afterReadEndNanos = after.readEndNanos();
        event.beforeReadStartEpochMillis = before.readStartEpochMillis();
        event.beforeReadEndEpochMillis = before.readEndEpochMillis();
        event.afterReadStartEpochMillis = after.readStartEpochMillis();
        event.afterReadEndEpochMillis = after.readEndEpochMillis();
        event.commit();
        return interval;
    }
}
