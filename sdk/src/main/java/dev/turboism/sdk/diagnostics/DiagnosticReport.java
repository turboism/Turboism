package dev.turboism.sdk.diagnostics;

import java.time.Instant;
import java.util.List;

/**
 * Public diagnostic report view.
 */
public interface DiagnosticReport {

    /** Returns when this report was produced. */
    Instant createdAt();

    /** Returns the problems recorded in this report, in emission order. */
    List<Problem> problems();

    /** One reported problem entry. */
    interface Problem {
        /** Returns the stable machine-readable problem code. */
        String code();

        /** Returns the human-readable problem description. */
        String message();

        /** Returns the path or location the problem refers to. */
        String path();

        /** Returns how severe this problem is. */
        Severity severity();
    }

    /** Severity classification of a reported problem. */
    enum Severity {
        ERROR,
        WARNING,
        INFO
    }
}
