package dev.turboism.core.event;

import dev.turboism.sdk.event.EventPriority;
import dev.turboism.sdk.event.EventSubscriberRegistrar;
import dev.turboism.sdk.event.GeneratedSubscriberCatalog;
import dev.turboism.sdk.event.SubscribeEvent;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.failure.FailureBoundary;
import dev.turboism.sdk.failure.NoFailureInterception;
import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GeneratedSubscriberCatalogLoaderTest {
    @Test
    void bootstrapLoadedEntrypointFallsBackToReflection() {
        final List<EventSubscriberDescriptor> descriptors =
            new GeneratedSubscriberCatalogLoader().inspect(List.of(new Subscriber()), null);

        assertEquals(1, descriptors.size());
        assertEquals("on", descriptors.get(0).method().getName());
    }

    @Test
    void fallsBackToReflectionWhenPluginHasNoGeneratedCatalog() throws Exception {
        try (URLClassLoader loader = new URLClassLoader(new URL[0], getClass().getClassLoader())) {
            final Subscriber subscriber = new Subscriber();
            final List<EventSubscriberDescriptor> descriptors =
                new GeneratedSubscriberCatalogLoader().inspect(List.of(subscriber), loader);

            assertEquals(1, descriptors.size());
            assertEquals("on", descriptors.get(0).method().getName());
        }
    }

    @Test
    void loadsExactGeneratedCatalogWithoutReflectiveMethod() throws Exception {
        final Path root = Files.createTempDirectory("generated-subscriber-loader");
        final String service = GeneratedSubscriberCatalog.class.getName();
        final Path serviceFile = root.resolve("META-INF/services").resolve(service);
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, FixtureCatalog.class.getName() + System.lineSeparator());
        final URL testClasses = FixtureSubscriber.class.getProtectionDomain()
            .getCodeSource().getLocation();
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{root.toUri().toURL(), testClasses},
            getClass().getClassLoader()
        ) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
                if (name.startsWith(GeneratedSubscriberCatalogLoaderTest.class.getName() + "$Fixture")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        }) {
            final Object subscriber = Class.forName(
                FixtureSubscriber.class.getName(),
                true,
                loader
            ).getDeclaredConstructor().newInstance();
            final List<EventSubscriberDescriptor> descriptors =
                new GeneratedSubscriberCatalogLoader().inspect(List.of(subscriber), loader);

            assertEquals(1, descriptors.size());
            assertEquals("on", descriptors.get(0).method().getName());
            assertEquals("event.generated", descriptors.get(0).failureBoundary());
            org.junit.jupiter.api.Assertions.assertTrue(
                descriptors.get(0).noFailureInterception()
            );
            assertEquals(EventPriority.HIGH, descriptors.get(0).priority());
        }
    }

    @Test
    void rejectsDuplicateCatalogProvidersForOneEntrypoint() throws Exception {
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> inspectWithProviders(FixtureCatalog.class, DuplicateFixtureCatalog.class)
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            failure.getMessage().contains("Duplicate generated subscriber catalog")
        );
    }

    @Test
    void rejectsMalformedServiceProviderMetadata() throws Exception {
        final Path root = Files.createTempDirectory("malformed-subscriber-loader");
        final Path serviceFile = root.resolve("META-INF/services").resolve(
            GeneratedSubscriberCatalog.class.getName()
        );
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(serviceFile, "not a valid provider name!" + System.lineSeparator());
        try (URLClassLoader loader = new URLClassLoader(
            new URL[]{root.toUri().toURL()},
            getClass().getClassLoader()
        )) {
            final IllegalArgumentException failure = assertThrows(
                IllegalArgumentException.class,
                () -> new GeneratedSubscriberCatalogLoader().inspect(
                    List.of(new Subscriber()),
                    loader
                )
            );
            assertEquals("Generated subscriber catalog is invalid", failure.getMessage());
        }
    }

    @Test
    void rejectsNonContiguousGeneratedMethodOrdinals() throws Exception {
        final IllegalArgumentException failure = assertThrows(
            IllegalArgumentException.class,
            () -> inspectWithProviders(NonContiguousFixtureCatalog.class)
        );
        org.junit.jupiter.api.Assertions.assertTrue(
            failure.getMessage().contains("method ordinals must be contiguous")
        );
    }

    private List<EventSubscriberDescriptor> inspectWithProviders(
        final Class<?>... providerTypes
    ) throws Exception {
        final Path root = Files.createTempDirectory("generated-subscriber-loader");
        final Path serviceFile = root.resolve("META-INF/services").resolve(
            GeneratedSubscriberCatalog.class.getName()
        );
        Files.createDirectories(serviceFile.getParent());
        Files.writeString(
            serviceFile,
            java.util.Arrays.stream(providerTypes)
                .map(Class::getName)
                .collect(java.util.stream.Collectors.joining(System.lineSeparator()))
                + System.lineSeparator()
        );
        final URL testClasses = FixtureSubscriber.class.getProtectionDomain()
            .getCodeSource().getLocation();
        try (URLClassLoader loader = fixtureLoader(root, testClasses)) {
            final Object subscriber = Class.forName(
                FixtureSubscriber.class.getName(),
                true,
                loader
            ).getDeclaredConstructor().newInstance();
            return new GeneratedSubscriberCatalogLoader().inspect(List.of(subscriber), loader);
        }
    }

    private URLClassLoader fixtureLoader(final Path root, final URL testClasses) throws Exception {
        return new URLClassLoader(
            new URL[]{root.toUri().toURL(), testClasses},
            getClass().getClassLoader()
        ) {
            @Override
            protected Class<?> loadClass(final String name, final boolean resolve)
                throws ClassNotFoundException {
                if (name.startsWith(GeneratedSubscriberCatalogLoaderTest.class.getName() + "$Fixture")
                    || name.startsWith(GeneratedSubscriberCatalogLoaderTest.class.getName()
                        + "$DuplicateFixture")
                    || name.startsWith(GeneratedSubscriberCatalogLoaderTest.class.getName()
                        + "$NonContiguousFixture")) {
                    synchronized (getClassLoadingLock(name)) {
                        Class<?> loaded = findLoadedClass(name);
                        if (loaded == null) loaded = findClass(name);
                        if (resolve) resolveClass(loaded);
                        return loaded;
                    }
                }
                return super.loadClass(name, resolve);
            }
        };
    }

    public static final class Subscriber {
        @SubscribeEvent public void on(final TestEvent event) { }
    }

    public record TestEvent(String value) implements TurboismEvent { }

    public static final class FixtureSubscriber {
        @FailureBoundary("event.generated")
        @NoFailureInterception
        public void on(final TestEvent event) { }
    }

    public static final class FixtureCatalog implements GeneratedSubscriberCatalog {
        @Override public Class<?> entrypointType() { return FixtureSubscriber.class; }
        @Override public void register(
            final Object entrypoint,
            final EventSubscriberRegistrar registrar
        ) {
            final FixtureSubscriber target = (FixtureSubscriber) entrypoint;
            registrar.register(
                TestEvent.class,
                EventPriority.HIGH,
                0,
                FixtureSubscriber.class.getName() + "#on(" + TestEvent.class.getName() + "):void",
                target::on
            );
        }
    }

    public static final class DuplicateFixtureCatalog implements GeneratedSubscriberCatalog {
        @Override public Class<?> entrypointType() { return FixtureSubscriber.class; }
        @Override public void register(
            final Object entrypoint,
            final EventSubscriberRegistrar registrar
        ) {
            final FixtureSubscriber target = (FixtureSubscriber) entrypoint;
            registrar.register(
                TestEvent.class,
                EventPriority.HIGH,
                0,
                FixtureSubscriber.class.getName() + "#on(" + TestEvent.class.getName() + "):void",
                target::on
            );
        }
    }

    public static final class NonContiguousFixtureCatalog implements GeneratedSubscriberCatalog {
        @Override public Class<?> entrypointType() { return FixtureSubscriber.class; }
        @Override public void register(
            final Object entrypoint,
            final EventSubscriberRegistrar registrar
        ) {
            final FixtureSubscriber target = (FixtureSubscriber) entrypoint;
            registrar.register(
                TestEvent.class,
                EventPriority.HIGH,
                2,
                FixtureSubscriber.class.getName() + "#on(" + TestEvent.class.getName() + "):void",
                target::on
            );
        }
    }
}
