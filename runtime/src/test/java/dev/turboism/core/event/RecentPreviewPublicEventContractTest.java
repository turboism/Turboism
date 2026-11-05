package dev.turboism.core.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.core.descriptor.PluginDescriptorParser;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.sdk.cubism.recentfile.RecentFileId;
import dev.turboism.sdk.cubism.recentpreview.RecentPreviewCapturedEvent;
import dev.turboism.sdk.cubism.screenshot.ScreenshotImage;
import dev.turboism.sdk.plugin.PluginDescriptor;
import dev.turboism.testkit.RuntimeTestKit;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class RecentPreviewPublicEventContractTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC);
    private static final byte[] PNG = java.util.Base64.getDecoder()
            .decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=");

    @Test
    void officialDescriptorExportsAUsableDependentEventContract() throws Exception {
        final PluginDescriptor provider = parseOfficialDescriptor();
        assertEquals(1, provider.eventExports().size());
        final PluginDescriptor.EventExport export = provider.eventExports().get(0);
        assertEquals("thumbnail.captured", export.id());
        assertEquals(RecentPreviewCapturedEvent.class.getName(), export.eventType());

        final RuntimeScheduler scheduler = RuntimeTestKit.scheduler(CLOCK, 1, 8);
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner providerOwner = broker.admit(provider);
        final RuntimeEventBroker.Owner consumerOwner = broker.admit(consumerDescriptor(provider, export));
        final CountDownLatch delivered = new CountDownLatch(1);
        final AtomicReference<RecentPreviewCapturedEvent> received = new AtomicReference<>();
        broker.subscribe(consumerOwner.key(), RecentPreviewCapturedEvent.class, event -> {
            received.set(event);
            delivered.countDown();
        });
        providerOwner.activate();
        consumerOwner.activate();

        final RecentFileId id = new RecentFileId("recent-1");
        final RecentPreviewCapturedEvent event =
                new RecentPreviewCapturedEvent(id, "model.cmo3", new ScreenshotImage(1, 1, PNG));
        broker.publish(providerOwner.key(), event);

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertEquals(id, received.get().id());
        assertEquals("model.cmo3", received.get().displayName());
        scheduler.shutdown();
    }

    private static PluginDescriptor parseOfficialDescriptor() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IllegalStateException("repository root was not found");
        }
        final Path descriptor = root.resolve("plugins/recent-preview/src/main/resources/META-INF/turboism/plugin.json");
        try (InputStream source = Files.newInputStream(descriptor)) {
            return new PluginDescriptorParser().parse(source);
        }
    }

    private static PluginDescriptor consumerDescriptor(
            final PluginDescriptor provider, final PluginDescriptor.EventExport export) {
        return new CorePluginDescriptor(
                "dev.example.recent-preview-consumer",
                "Recent Preview Consumer",
                "1.0.0",
                "",
                List.of("dev.example.ConsumerPlugin"),
                "[0.67.0,1.0.0)",
                List.of(new CorePluginDescriptor.CoreAuthor("Example", Optional.empty())),
                "Project License",
                Optional.empty(),
                List.of(),
                new CorePluginDescriptor.CoreI18n("example.messages", List.of()),
                List.of(new CorePluginDescriptor.CoreDependencyRef(
                        provider.id(), "required", "[0.68.0,1.0.0)", "after", Optional.empty())),
                List.of(),
                List.of(),
                new CorePluginDescriptor.CoreEnvironment(false, "none"),
                Optional.empty(),
                List.of(),
                List.of(),
                List.of(new CorePluginDescriptor.CoreEventImport(
                        provider.id(), export.id(), "[1.0.0,2.0.0)", export.eventType(), export.abiSha256(), true)),
                List.of());
    }
}
