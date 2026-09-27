package dev.turboism.bootstrap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Checks actual compiled call ordering without exporting ASM outside :runtime.
 *
 * <p>The hand-wired agent enforced per-hook ordering by construction; the
 * declarative agent enforces it uniformly, so this helper pins the structural
 * invariants every native optimization hook relies on: the verified host is
 * resolved before the {@code HOST_RESOLVED} phase installs, that phase runs
 * before the preview runtime starts loading the initial document, a failed
 * runtime start closes the hooks installed in that phase, and contributor
 * admission is checked before installation.</p>
 */
final class NativeOptimizationStartupOrder {
    private NativeOptimizationStartupOrder() { }

    static void assertBeforeRuntime() throws Exception {
        var javap = ToolProvider.findFirst("javap").orElseThrow();
        String classes = Path.of(TurboismAgent.class.getProtectionDomain().getCodeSource()
            .getLocation().toURI()).toString();

        String start = methodBody(javap, classes, "private static void start(");
        int resolution = start.indexOf("resolveHost:");
        int installation = start.indexOf("installPhase:");
        int runtimeStart = start.indexOf("startPreviewRuntime:");
        assertTrue(resolution >= 0, "the verified host is resolved in start()");
        assertTrue(installation > resolution, "exact host resolution precedes the install phase");
        assertTrue(installation < runtimeStart, "host-resolved hooks precede initial document loading");
        assertTrue(start.indexOf("closePhase:") > runtimeStart,
            "startup failure closes the host-resolved hooks");

        String installPhaseHook = methodBody(
            javap, classes, "private static boolean installPhaseHook(");
        assertTrue(
            installPhaseHook.indexOf("admitted:") >= 0
                && installPhaseHook.indexOf("install:") > installPhaseHook.indexOf("admitted:"),
            "contributor admission is checked before installation");
    }

    private static String methodBody(ToolProvider javap, String classes, String signature)
            throws Exception {
        var output = new StringWriter();
        var errors = new StringWriter();
        assertEquals(0, javap.run(new PrintWriter(output), new PrintWriter(errors),
            "-p", "-c", "-classpath", classes, TurboismAgent.class.getName()), errors.toString());
        String text = output.toString();
        int method = text.indexOf(signature);
        assertTrue(method >= 0, "missing method: " + signature);
        // The next member declaration sits at exactly two spaces of indent;
        // bytecode lines carry at least four.
        var declarations = java.util.regex.Pattern.compile("(?m)^  \\S").matcher(text);
        int end = text.length();
        while (declarations.find()) {
            if (declarations.start() > method) {
                end = declarations.start();
                break;
            }
        }
        assertTrue(end < text.length(), "unterminated method: " + signature);
        return text.substring(method, end);
    }
}
