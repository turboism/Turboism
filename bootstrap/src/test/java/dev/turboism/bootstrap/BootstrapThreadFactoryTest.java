package dev.turboism.bootstrap;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.JRootPane;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BootstrapThreadFactoryTest {

    @Test
    void bootstrapThreadUsesTheHostSystemContextClassLoader() {
        final Thread thread = BootstrapThreadFactory.create(() -> { });

        assertFalse(thread.isAlive());
        assertSame(ClassLoader.getSystemClassLoader(), thread.getContextClassLoader());
    }

    @Test
    void bootstrapThreadResolvesCoreSwingDelegates() throws Exception {
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure =
            new java.util.concurrent.atomic.AtomicReference<>();
        final Thread thread = BootstrapThreadFactory.create(() -> {
            try {
                SwingUtilities.invokeAndWait(() -> {
                    assertNotNull(new JPanel().getUI());
                    assertNotNull(new JRootPane().getUI());
                    assertNotNull(new JTable().getTableHeader().getUI());
                });
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        });

        thread.start();
        thread.join();
        if (failure.get() != null) {
            throw new AssertionError("Swing delegate construction failed", failure.get());
        }
    }

    @Test
    void bootstrapLoadedAgentCannotSeedSwingWithANullContextClassLoader() throws Exception {
        final String classPath = System.getProperty("java.class.path");
        final String bootstrapClasses = Path.of(
            BootstrapThreadFactory.class.getProtectionDomain().getCodeSource().getLocation().toURI()
        ).toString();
        final String testClasses = Path.of(
            BootstrapThreadContextClassLoaderChild.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI()
        ).toString();
        final Process child = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-Xbootclasspath/a:" + bootstrapClasses + java.io.File.pathSeparator + testClasses,
            "-classpath",
            classPath,
            BootstrapThreadContextClassLoaderChild.class.getName()
        ).redirectErrorStream(true).start();

        assertTrue(child.waitFor(10, TimeUnit.SECONDS));
        final String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, child.exitValue(), output);
    }
}
