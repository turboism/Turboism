package dev.turboism.core.event;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The author-facing {@link PublicEventAbiCli} must print exactly the digests the
 * runtime recomputes at admission, loading the artifact through the same
 * restricted contract class loader.
 */
class PublicEventAbiCliTest {

    private static final String EVENT_TYPE = "com.acme.events.Greeting";

    @TempDir
    Path temporary;

    @Test
    void artifactRunPrintsAbiAndArtifactDigests() throws Exception {
        final Path artifact = contractArtifact(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(com.acme.events.GreetingPayload payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """,
            "com.acme.events.GreetingPayload", """
                package com.acme.events;
                public record GreetingPayload(String text) {}
                """
        ));
        // The expected ABI digest computed against a plain loader must equal the
        // digest the CLI computes through the restricted contract loader.
        final String expectedAbi;
        try (var loader = new java.net.URLClassLoader(
            new java.net.URL[]{artifact.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            expectedAbi = PublicEventAbi.sha256(
                Class.forName(EVENT_TYPE, false, loader)
            );
        }

        final Result result = runCli("--artifact", artifact.toString(), EVENT_TYPE);

        assertEquals(0, result.exit(), result.stderr());
        assertTrue(
            result.stdout().contains(EVENT_TYPE + " " + expectedAbi),
            result.stdout()
        );
        assertTrue(
            result.stdout().contains(
                "artifactSha256 " + sha256Hex(Files.readAllBytes(artifact))
            ),
            result.stdout()
        );
    }

    @Test
    void sdkTypeRunPrintsAbiWithoutArtifact() throws Exception {
        final String sdkType = "dev.turboism.sdk.appearance.AppearanceChangedEvent";
        final String expected = PublicEventAbi.sha256(
            Class.forName(sdkType)
        );

        final Result result = runCli(sdkType);

        assertEquals(0, result.exit(), result.stderr());
        assertTrue(result.stdout().contains(sdkType + " " + expected), result.stdout());
    }

    @Test
    void typeNotOwnedByArtifactFails() throws Exception {
        final Path artifact = contractArtifact(Map.of(
            EVENT_TYPE, """
                package com.acme.events;
                public record Greeting(String payload)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ));

        final Result result = runCli(
            "--artifact", artifact.toString(), "com.acme.events.Absent"
        );

        assertEquals(1, result.exit());
        assertTrue(result.stderr().contains("not owned by the contract artifact"));
    }

    @Test
    void artifactViolatingTheClosureFails() throws Exception {
        // The payload type is compiled but deliberately not packaged, so the
        // record component cannot resolve inside the contract closure.
        final Path hidden = compile(Map.of(
            "dev.acme.privatepkg.Hidden", """
                package dev.acme.privatepkg;
                public record Hidden(String secret) {}
                """
        ));
        final Path classes = compile(Map.of(
            "com.acme.events.LeakyEvent", """
                package com.acme.events;
                public record LeakyEvent(dev.acme.privatepkg.Hidden leaked)
                    implements dev.turboism.sdk.event.EventBus.TurboismEvent {}
                """
        ), hidden);
        final Path artifact = jar(temporary.resolve("leaky.jar"), classes);

        final Result result = runCli(
            "--artifact", artifact.toString(), "com.acme.events.LeakyEvent"
        );

        assertEquals(1, result.exit());
        assertTrue(result.stderr().contains("closure"), result.stderr());
    }

    // -- fixtures -------------------------------------------------------------

    private record Result(int exit, String stdout, String stderr) {
    }

    private Result runCli(final String... arguments) throws Exception {
        final List<String> command = new ArrayList<>(List.of(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-cp", System.getProperty("java.class.path"),
            "dev.turboism.core.event.PublicEventAbiCli"
        ));
        command.addAll(List.of(arguments));
        final Process process = new ProcessBuilder(command).start();
        final String stdout = new String(
            process.getInputStream().readAllBytes(), StandardCharsets.UTF_8
        );
        final String stderr = new String(
            process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8
        );
        return new Result(process.waitFor(), stdout, stderr);
    }

    private Path contractArtifact(final Map<String, String> sources)
        throws IOException {
        return jar(
            temporary.resolve("contract-" + System.nanoTime() + ".jar"),
            compile(sources)
        );
    }

    private Path compile(final Map<String, String> sources, final Path... extra)
        throws IOException {
        final Path sourceRoot = Files.createDirectories(
            temporary.resolve("src-" + System.nanoTime())
        );
        final Path classes = Files.createDirectories(
            temporary.resolve("classes-" + System.nanoTime())
        );
        final List<String> files = new ArrayList<>();
        for (final Map.Entry<String, String> source : sources.entrySet()) {
            final Path file = sourceRoot.resolve(
                source.getKey().replace('.', '/') + ".java"
            );
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue(), StandardCharsets.UTF_8);
            files.add(file.toString());
        }
        final StringBuilder classpath = new StringBuilder(
            System.getProperty("java.class.path")
        );
        for (final Path entry : extra) {
            classpath.append(java.io.File.pathSeparator).append(entry);
        }
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final List<String> arguments = new ArrayList<>(List.of(
            "-classpath", classpath.toString(),
            "-d", classes.toString()
        ));
        arguments.addAll(files);
        if (compiler.run(null, null, null, arguments.toArray(new String[0])) != 0) {
            throw new IllegalStateException("fixture compilation failed");
        }
        return classes;
    }

    private static Path jar(final Path target, final Path classes) throws IOException {
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(target));
             var paths = Files.walk(classes)) {
            for (final Path file : paths.filter(Files::isRegularFile)
                .sorted(Comparator.naturalOrder()).toList()) {
                output.putNextEntry(new JarEntry(
                    classes.relativize(file).toString().replace('\\', '/')
                ));
                output.write(Files.readAllBytes(file));
                output.closeEntry();
            }
        }
        return target;
    }

    private static String sha256Hex(final byte[] bytes) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
        return HexFormat.of().formatHex(digest.digest(bytes));
    }
}
