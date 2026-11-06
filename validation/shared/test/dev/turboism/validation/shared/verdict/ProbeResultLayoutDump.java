package dev.turboism.validation.shared.verdict;

/**
 * Prints a result file the way the probes write it, for the offline layout tests.
 *
 * <p>Arguments: {@code named|indexed}, the terminal key ({@code status} or {@code terminal})
 * and the assertion statuses in recording order. Header, counters, assertion records and
 * terminal line all come from the shared recorder's single publication call, so this dump
 * exercises exactly the tail a probe publishes.</p>
 */
public final class ProbeResultLayoutDump {
    private ProbeResultLayoutDump() {}

    public static void main(final String[] args) {
        final ProbeAssertions.Records records = "indexed".equals(args[0])
            ? ProbeAssertions.Records.INDEXED : ProbeAssertions.Records.NAMED;
        final ProbeAssertions assertions = new ProbeAssertions();
        for (int index = 2; index < args.length; index++) {
            assertions.record("check" + index, "expected", "actual",
                ProbeTerminal.valueOf(args[index]));
        }
        final StringBuilder report = new StringBuilder("hostVersion=test\n");
        assertions.appendPublication(report, records, args[1]);
        System.out.print(report);
    }
}
