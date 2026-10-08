import java.util.concurrent.atomic.AtomicLong;

public final class CommandCpuBoundarySelfCheck {
    private static int checks;
    private static volatile long sink;
    private CommandCpuBoundarySelfCheck() { }
    private static void require(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }
    private static void refused(CommandCpuBoundary sampler) {
        try { sampler.read(); throw new AssertionError("invalid measurement accepted"); }
        catch (IllegalStateException expected) { checks++; }
        try { sampler.read(); throw new AssertionError("refused sampler reactivated"); }
        catch (IllegalStateException expected) { checks++; }
    }
    public static void main(String[] args) {
        AtomicLong clock = new AtomicLong(100);
        CommandCpuBoundary normal = new CommandCpuBoundary(() -> 42L, clock::getAndIncrement, () -> 1000);
        CommandCpuBoundary.Sample sample = normal.read();
        require(sample.processCpuNanos() == 42 && sample.readStartNanos() == 100
                && sample.readEndNanos() == 101, "read clocks bracket counter");
        require(normal.read().processCpuNanos() == 42, "zero progress is valid counter continuity");
        require(CommandCpuBoundary.header().split("\t").length == 8
                && CommandCpuBoundary.row("auto-connect-start", 1, sample).split("\t").length == 8,
                "evidence schema");
        refused(new CommandCpuBoundary(() -> -1, clock::getAndIncrement, () -> 1000));
        AtomicLong value = new AtomicLong(100);
        CommandCpuBoundary decrease = new CommandCpuBoundary(value::getAndDecrement, clock::getAndIncrement, () -> 1000);
        decrease.read();
        refused(decrease);
        refused(new CommandCpuBoundary(() -> { throw new IllegalStateException("unavailable"); },
                                      clock::getAndIncrement, () -> 1000));
        AtomicLong backwards = new AtomicLong(100);
        refused(new CommandCpuBoundary(() -> 42, backwards::getAndDecrement, () -> 1000));
        AtomicLong epoch = new AtomicLong(1000);
        refused(new CommandCpuBoundary(() -> 42, clock::getAndIncrement, epoch::getAndDecrement));
        CommandCpuBoundary actual = CommandCpuBoundary.system();
        CommandCpuBoundary.Sample first = actual.read();
        long deadline = System.nanoTime() + 2_000_000_000L;
        long mixed = 1;
        CommandCpuBoundary.Sample last;
        do {
            for (int i = 0; i < 100000; i++) mixed = mixed * 31 + i;
            sink = mixed;
            last = actual.read();
        } while (last.processCpuNanos() == first.processCpuNanos() && System.nanoTime() < deadline);
        require(last.processCpuNanos() > first.processCpuNanos(), "actual process CPU counter progresses");
        System.out.println("COMMAND_CPU_BOUNDARY_SELFCHECK_PASS checks=" + checks
                + " cpuDeltaNanos=" + (last.processCpuNanos() - first.processCpuNanos())
                + " readSpanNanos=" + (last.readEndNanos() - last.readStartNanos()));
    }
}
