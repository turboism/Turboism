package dev.turboism.validation.shared.verdict;

/**
 * Prints a result file the way the probes write it, for the offline layout tests.
 *
 * <p>Arguments: {@code named|indexed}, the terminal key ({@code status} or {@code terminal})
 * and the assertion statuses in recording order. The counters, the assertion records and the
 * terminal line come from the shared recorder in the order every probe uses: counters first,
 * assertions next, the terminal line last.</p>
 */
public final class ProbeResultLayoutDump {
    private ProbeResultLayoutDump() {}

    public static void main(final String[] args) {
        final boolean indexed = "indexed".equals(args[0]);
        final String terminalKey = args[1];
        final ProbeAssertions assertions = new ProbeAssertions();
        for (int index = 2; index < args.length; index++) {
            assertions.record("check" + index, "expected", "actual", args[index]);
        }
        final StringBuilder report = new StringBuilder("hostVersion=test\n");
        assertions.appendCounters(report);
        if (indexed) {
            assertions.appendIndexed(report, true);
        } else {
            assertions.appendNamed(report);
        }
        report.append(terminalKey).append('=').append(assertions.terminal().name()).append('\n');
        System.out.print(report);
    }
}
