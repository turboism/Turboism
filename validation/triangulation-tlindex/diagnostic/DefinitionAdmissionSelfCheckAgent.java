package dev.turboism.validation.tlindex.diagnostic;

import java.lang.instrument.Instrumentation;

/** Installs no transformer and loads no host; instrumentation for owned-JVM tests only. */
public final class DefinitionAdmissionSelfCheckAgent {
    private static Instrumentation instrumentation;
    private DefinitionAdmissionSelfCheckAgent() {}
    public static void premain(String arguments, Instrumentation supplied) { instrumentation = supplied; }
    static Instrumentation instrumentation() { return instrumentation; }
}
