package dev.turboism.tests.plugin;

/**
 * The closed set of assertion statuses one Auto Mesh probe run records.
 *
 * <p>{@link #INFO} carries evidence only: it is archived with the run but never decides the verdict,
 * so a diagnostic record cannot turn a passing run into a failing one. Every other status decides
 * the verdict, which is why the type is closed instead of a string.</p>
 */
enum AutoMeshProbeStatus {

    /** The assertion ran and its expectation held. */
    PASS("pass"),

    /** The assertion ran and its expectation did not hold. */
    FAIL("fail"),

    /** The run could not exercise the assertion, so the run proved nothing about it. */
    BLOCKED("blocked"),

    /** The assertion was owed but skipped, so the run cannot claim a pass. */
    SKIPPED("skipped"),

    /** Evidence-only record; it never decides the verdict. */
    INFO("info");

    private final String token;

    AutoMeshProbeStatus(final String token) {
        this.token = token;
    }

    /** @return the lowercase counter suffix this status publishes */
    String token() {
        return token;
    }

    /** @return whether an assertion with this status decides the terminal verdict */
    boolean verdictBearing() {
        return this != INFO;
    }
}
