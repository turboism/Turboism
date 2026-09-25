package dev.turboism.validation.modelupdate;

import java.nio.file.Files;

/** Constructor-level factor admission; never touches windows or GL. */
public final class NativeInteractionWorkloadFactorTest {
    public static void main(String[] args) throws Exception {
        final String factorKey = "turboism.validation.modelUpdateFactor";
        final String prior = System.getProperty(factorKey);
        try {
            final var state = Files.createTempDirectory("interaction-factor");
            for (String factor : new String[] {"uniformHook", "matrixScratch", "uploadElision", "redundantState", "inputPath", "canvasComposite"}) {
                System.setProperty(factorKey, factor);
                new NativeInteractionWorkload("fixture.cmo3", state, "pan");
                new NativeInteractionWorkload("fixture.cmo3", state, "artmesh");
            }
            System.setProperty(factorKey, "uploadElision");
            checkRejects(" ", "pan", "blank fixture");
            checkRejects("fixture.cmo3", "resize", "non-native interaction kind");
            checkRejects("fixture.cmo3", "", "empty interaction kind");
            System.setProperty(factorKey, "bogus");
            checkRejects("fixture.cmo3", "pan", "unknown factor");
            System.out.println("NativeInteractionWorkloadFactorTest PASS (uniformHook, matrixScratch, uploadElision, redundantState, inputPath, canvasComposite)");
        } finally {
            if (prior == null) System.clearProperty(factorKey);
            else System.setProperty(factorKey, prior);
        }
    }

    private static void checkRejects(String fixture, String kind, String label) throws Exception {
        try {
            new NativeInteractionWorkload(fixture, Files.createTempDirectory("x"), kind);
            throw new AssertionError("accepted " + label + ": " + fixture + "/" + kind);
        } catch (IllegalArgumentException expected) { }
    }
}
