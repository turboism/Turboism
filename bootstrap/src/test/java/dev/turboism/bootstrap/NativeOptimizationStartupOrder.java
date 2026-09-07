package dev.turboism.bootstrap;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import static org.junit.jupiter.api.Assertions.*;

/** Checks actual compiled call ordering without exporting ASM outside :runtime. */
final class NativeOptimizationStartupOrder {
    private NativeOptimizationStartupOrder() { }

    static void assertBeforeRuntime(String install, String cleanup) throws Exception {
        var javap = ToolProvider.findFirst("javap").orElseThrow();
        var output = new StringWriter();
        var errors = new StringWriter();
        String classes = Path.of(TurboismAgent.class.getProtectionDomain().getCodeSource().getLocation().toURI()).toString();
        assertEquals(0, javap.run(new PrintWriter(output), new PrintWriter(errors), "-p", "-c", "-classpath", classes,
            TurboismAgent.class.getName()), errors.toString());
        String text = output.toString();
        int method = text.indexOf("private static void start(");
        assertTrue(method >= 0);
        int next = text.indexOf("\n  private ", method + 1);
        assertTrue(next > method);
        String body = text.substring(method, next);
        int admission = body.indexOf("admitsFullRuntime:"), installation = body.indexOf(install + ":");
        assertTrue(admission >= 0 && installation > admission, "exact admission precedes native hook");
        assertEquals(installation, body.lastIndexOf(install + ":"), "install exactly once");
        assertTrue(installation < body.indexOf("startPreviewRuntime:"), "hook precedes initial document loading");
        assertTrue(body.contains(cleanup + ":"), "startup failure closes native hook");
    }
}
