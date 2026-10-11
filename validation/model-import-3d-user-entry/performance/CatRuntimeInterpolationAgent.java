package dev.turboism.diagnostic;

import java.lang.instrument.Instrumentation;
import dev.turboism.adapter.cubism.interpolation.VerifiedMeshInterpolationInstaller;

/** Isolated carrier for the actual reflection-only production candidate. */
public final class CatRuntimeInterpolationAgent {
    public static VerifiedMeshInterpolationInstaller.Installation installation;
    public static void premain(String args, Instrumentation instrumentation) {
        installation = VerifiedMeshInterpolationInstaller.install(instrumentation, System.err::println);
        if (installation == null) throw new IllegalStateException("production candidate installation refused");
    }
}
