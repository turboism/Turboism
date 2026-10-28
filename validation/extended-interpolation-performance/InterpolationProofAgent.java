package dev.turboism.diagnostic.extended;

import dev.turboism.adapter.cubism.interpolation.VerifiedMeshInterpolationInstaller;
import java.lang.instrument.Instrumentation;

/** Isolated JVM proof of the actual production install/transform/close path. */
public final class InterpolationProofAgent {
    private static VerifiedMeshInterpolationInstaller.Installation installation;
    public static void premain(String args, Instrumentation instrumentation) {
        installation = VerifiedMeshInterpolationInstaller.install(instrumentation, System.out::println, true, false);
        if (installation == null) throw new AssertionError("installation declined");
        System.setProperty("extended.proof.installed", "true");
    }
    public static void verifyPatched() {
        if (installation == null || !installation.patched()) throw new AssertionError("target class not transformed");
    }
    public static void close() {
        installation.close();
    }
}
