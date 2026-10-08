import java.lang.management.ManagementFactory;
import java.util.function.LongSupplier;

/** Diagnostic process-wide CPU counter; unavailable readings refuse measurement. */
final class CommandCpuBoundary {
    record Sample(long processCpuNanos, long readStartNanos, long readEndNanos,
                  long readStartEpochMillis, long readEndEpochMillis) { }
    private final LongSupplier counter;
    private final LongSupplier nanos;
    private final LongSupplier epochMillis;
    private long previousCpu = -1;
    private boolean failed;

    CommandCpuBoundary(LongSupplier counter, LongSupplier nanos, LongSupplier epochMillis) {
        this.counter = counter;
        this.nanos = nanos;
        this.epochMillis = epochMillis;
    }

    static CommandCpuBoundary system() {
        final java.lang.management.OperatingSystemMXBean bean = ManagementFactory.getOperatingSystemMXBean();
        if (!(bean instanceof com.sun.management.OperatingSystemMXBean process))
            throw new IllegalStateException("process CPU counter unavailable");
        return new CommandCpuBoundary(process::getProcessCpuTime, System::nanoTime, System::currentTimeMillis);
    }

    Sample read() {
        if (failed) throw new IllegalStateException("process CPU measurement already refused");
        failed = true; // An unavailable/throwing/invalid read permanently refuses this sampler.
        final long start = nanos.getAsLong();
        final long startEpoch = epochMillis.getAsLong();
        final long cpu = counter.getAsLong();
        final long endEpoch = epochMillis.getAsLong();
        final long end = nanos.getAsLong();
        if (cpu < 0 || cpu < previousCpu || end < start || endEpoch < startEpoch)
            throw new IllegalStateException("invalid process CPU counter or read clocks");
        previousCpu = cpu;
        failed = false;
        return new Sample(cpu, start, end, startEpoch, endEpoch);
    }

    static String header() {
        return "phase\toperation\tprocessCpuNanos\treadStartNanos\treadEndNanos"
            + "\treadStartEpochMillis\treadEndEpochMillis\tosName\n";
    }

    static String row(String phase, int operation, Sample value) {
        return phase + "\t" + operation + "\t" + value.processCpuNanos() + "\t"
            + value.readStartNanos() + "\t" + value.readEndNanos() + "\t"
            + value.readStartEpochMillis() + "\t" + value.readEndEpochMillis() + "\t"
            + System.getProperty("os.name", "UNKNOWN").replace('\t', '_').replace('\n', '_').replace('\r', '_') + "\n";
    }
}
