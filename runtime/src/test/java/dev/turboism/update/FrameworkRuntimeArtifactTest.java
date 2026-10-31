package dev.turboism.update;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboism.core.FrameworkBuildInfo;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class FrameworkRuntimeArtifactTest {
    @TempDir Path temporary;

    public static final class BootProbe {
        public static void main(final String[] args) {
            System.out.println("source-null=" + (FrameworkBuildInfo.class.getProtectionDomain().getCodeSource() == null));
            System.out.println("artifact=" + FrameworkRuntimeArtifact.resolve());
        }
    }

    @Test void bootstrapLoadedRuntimeCanLocateItsJarWithoutCodeSource() throws Exception {
        final Path agent = temporary.resolve("agent.jar");
        try (var zip = new JarOutputStream(Files.newOutputStream(agent))) {
            add(zip, FrameworkBuildInfo.class);
            for (final Class<?> nested : FrameworkBuildInfo.class.getDeclaredClasses()) add(zip, nested);
            add(zip, FrameworkRuntimeArtifact.class);
        }
        final Path output = temporary.resolve("boot-probe.log");
        final Process process = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Xbootclasspath/a:" + agent + File.pathSeparator + System.getProperty("java.class.path"),
                "-cp", System.getProperty("java.class.path"), BootProbe.class.getName())
                .redirectErrorStream(true).redirectOutput(output.toFile()).start();
        if (!process.waitFor(30, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly();
            fail("Bootstrap probe timed out");
        }
        final String log = Files.readString(output);
        assertEquals(0, process.exitValue(), log);
        assertTrue(log.contains("source-null=true"), log);
        assertTrue(log.contains("artifact=" + agent.toAbsolutePath().normalize()), log);
    }

    private static void add(final JarOutputStream zip, final Class<?> type) throws Exception {
        final String entry = type.getName().replace('.', '/') + ".class";
        zip.putNextEntry(new JarEntry(entry));
        try (var input = type.getResourceAsStream("/" + entry)) {
            assertNotNull(input);
            input.transferTo(zip);
        }
        zip.closeEntry();
    }
}
