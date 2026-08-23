package dev.turboism.eventprocessor;

import org.junit.jupiter.api.Test;

import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SubscribeEventProcessorTest {
    @Test
    void generatesDirectCatalogAndServiceProvider() throws Exception {
        final Compilation result = compile("""
            package fixture;
            import dev.turboism.sdk.event.SubscribeEvent;
            import dev.turboism.sdk.event.TurboismEvent;
            public final class Subscriber {
                @SubscribeEvent public void on(TestEvent event) { }
                public record TestEvent(String value) implements TurboismEvent { }
            }
            """);

        assertTrue(result.succeeded());
        assertTrue(Files.isRegularFile(result.classes().resolve(
            "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
        )));
        assertTrue(Files.isRegularFile(result.generated().resolve(
            "fixture/Subscriber__TurboismSubscriberCatalog.java"
        )));
        final String generated = Files.readString(result.generated().resolve(
            "fixture/Subscriber__TurboismSubscriberCatalog.java"
        ));
        assertTrue(generated.contains("target::on"));
    }

    @Test
    void includesInheritedSubscribersInCanonicalOrder() throws Exception {
        final Compilation result = compile("""
            package fixture;
            import dev.turboism.sdk.event.EventPriority;
            import dev.turboism.sdk.event.SubscribeEvent;
            import dev.turboism.sdk.event.TurboismEvent;
            class BaseSubscriber {
                @SubscribeEvent(priority = EventPriority.LOW)
                public void zeta(Subscriber.TestEvent event) { }
            }
            public final class Subscriber extends BaseSubscriber {
                @SubscribeEvent(priority = EventPriority.HIGH)
                public void alpha(TestEvent event) { }
                public record TestEvent(String value) implements TurboismEvent { }
            }
            """);

        assertTrue(result.succeeded());
        final String generated = Files.readString(result.generated().resolve(
            "fixture/Subscriber__TurboismSubscriberCatalog.java"
        ));
        assertTrue(generated.contains("target::zeta"));
        assertTrue(generated.contains("target::alpha"));
        assertTrue(generated.indexOf("target::zeta") < generated.indexOf("target::alpha"));
        assertTrue(generated.contains("EventPriority.LOW, 0"));
        assertTrue(generated.contains("EventPriority.HIGH, 1"));
    }

    @Test
    void writesServiceProvidersInDeterministicOrder() throws Exception {
        final Compilation result = compile(Map.of(
            "fixture/ZSubscriber.java", """
                package fixture;
                import dev.turboism.sdk.event.SubscribeEvent;
                import dev.turboism.sdk.event.TurboismEvent;
                public final class ZSubscriber {
                    @SubscribeEvent public void on(TestEvent event) { }
                    public record TestEvent(String value) implements TurboismEvent { }
                }
                """,
            "fixture/ASubscriber.java", """
                package fixture;
                import dev.turboism.sdk.event.SubscribeEvent;
                public final class ASubscriber {
                    @SubscribeEvent public void on(ZSubscriber.TestEvent event) { }
                }
                """
        ));

        assertTrue(result.succeeded());
        assertEquals(
            List.of(
                "fixture.ASubscriber__TurboismSubscriberCatalog",
                "fixture.ZSubscriber__TurboismSubscriberCatalog"
            ),
            Files.readAllLines(result.classes().resolve(
                "META-INF/services/dev.turboism.sdk.event.GeneratedSubscriberCatalog"
            ))
        );
    }

    @Test
    void rejectsInvalidSubscriberAtCompileTime() throws Exception {
        final Compilation result = compile("""
            package fixture;
            import dev.turboism.sdk.event.SubscribeEvent;
            public final class Subscriber {
                @SubscribeEvent private boolean invalid(String value) { return false; }
            }
            """);

        assertFalse(result.succeeded());
        assertTrue(result.diagnostics().getDiagnostics().stream().anyMatch(diagnostic ->
            diagnostic.getMessage(java.util.Locale.ROOT).contains("subscriber")
        ));
    }

    private static Compilation compile(final String source) throws Exception {
        return compile(Map.of("fixture/Subscriber.java", source));
    }

    private static Compilation compile(final Map<String, String> sourcesByPath) throws Exception {
        final Path root = Files.createTempDirectory("turboism-event-processor");
        final Path sources = root.resolve("src");
        final Path classes = root.resolve("classes");
        final Path generated = root.resolve("generated");
        Files.createDirectories(sources);
        Files.createDirectories(classes);
        Files.createDirectories(generated);
        final List<Path> sourceFiles = new java.util.ArrayList<>();
        for (Map.Entry<String, String> source : sourcesByPath.entrySet()) {
            final Path file = sources.resolve(source.getKey());
            Files.createDirectories(file.getParent());
            Files.writeString(file, source.getValue());
            sourceFiles.add(file);
        }
        sourceFiles.sort(Comparator.comparing(Path::toString));
        final JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        final DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        try (StandardJavaFileManager files = compiler.getStandardFileManager(
            diagnostics,
            java.util.Locale.ROOT,
            java.nio.charset.StandardCharsets.UTF_8
        )) {
            files.setLocationFromPaths(StandardLocation.CLASS_OUTPUT, List.of(classes));
            files.setLocationFromPaths(StandardLocation.SOURCE_OUTPUT, List.of(generated));
            final Iterable<? extends JavaFileObject> units = files.getJavaFileObjectsFromPaths(
                sourceFiles
            );
            final boolean succeeded = compiler.getTask(
                null,
                files,
                diagnostics,
                List.of("--release", "17", "-classpath", System.getProperty("java.class.path")),
                null,
                units
            ).call();
            return new Compilation(succeeded, classes, generated, diagnostics);
        }
    }

    private record Compilation(
        boolean succeeded,
        Path classes,
        Path generated,
        DiagnosticCollector<JavaFileObject> diagnostics
    ) { }
}
