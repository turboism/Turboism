package dev.turboism.core.event;

import dev.turboism.core.descriptor.CorePluginDescriptor;
import dev.turboism.core.runtime.DefaultWorkBudgetPolicy;
import dev.turboism.core.runtime.PluginTask;
import dev.turboism.core.runtime.RuntimeScheduler;
import dev.turboism.core.runtime.sidecar.SidecarDispatcher;
import dev.turboism.core.runtime.sidecar.SidecarResult;
import dev.turboism.core.runtime.work.PluginWorkExecutorRegistry;
import dev.turboism.sdk.event.TurboismEvent;
import dev.turboism.sdk.plugin.PluginDescriptor;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublicEventRouteCatalogTest {

    private static final String PROVIDER = "dev.example.provider";
    private static final String CONSUMER = "dev.example.consumer";
    private static final String EVENT_ID = "provider.ready";
    private static final String ABI = PublicEventAbi.sha256(PublicFixtureEvent.class);
    private static final Clock CLOCK = Clock.fixed(
        Instant.parse("2026-08-23T00:00:00Z"),
        ZoneOffset.UTC
    );

    @Test
    void declaredDependentReceivesProviderOwnedSdkEvent() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        ));
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(consumer.key(), PublicFixtureEvent.class, ignored -> delivered.countDown());
        provider.activate();
        consumer.activate();

        broker.publish(provider.key(), new PublicFixtureEvent("ready"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void undeclaredSupertypeSubscriberDoesNotReceivePublicEvent() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner undeclared = broker.admit("dev.example.undeclared");
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(
            undeclared.key(),
            dev.turboism.sdk.event.TurboismEvent.class,
            ignored -> delivered.countDown()
        );
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        undeclared.activate();
        provider.activate();

        broker.publish(provider.key(), new PublicFixtureEvent("ready"));

        org.junit.jupiter.api.Assertions.assertFalse(
            delivered.await(100, TimeUnit.MILLISECONDS)
        );
        scheduler.shutdown();
    }

    @Test
    void undeclaredConsumerCannotSubscribeToPublicEvent() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(descriptor(
            CONSUMER,
            "1.0.0",
            List.of(),
            List.of(),
            List.of()
        ));

        assertThrows(
            IllegalArgumentException.class,
            () -> broker.subscribe(consumer.key(), PublicFixtureEvent.class, ignored -> { })
        );
        scheduler.shutdown();
    }

    @Test
    void consumerCannotPublishProviderOwnedEvent() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        ));
        provider.activate();
        consumer.activate();

        assertThrows(
            IllegalArgumentException.class,
            () -> broker.publish(consumer.key(), new PublicFixtureEvent("forged"))
        );
        scheduler.shutdown();
    }

    @Test
    void incompatibleContractAbiRejectsSubscription() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        broker.admit(providerDescriptor());

        assertThrows(
            IllegalArgumentException.class,
            () -> broker.admit(consumerDescriptor(
                "[1.0.0,2.0.0)",
                "b".repeat(64),
                true
            ))
        );
        scheduler.shutdown();
    }

    @Test
    void requiredImportRejectsAdmissionBeforeProviderLoads() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);

        assertThrows(
            IllegalArgumentException.class,
            () -> broker.admit(consumerDescriptor("[1.0.0,2.0.0)", ABI, true))
        );
        scheduler.shutdown();
    }

    @Test
    void optionalImportMayWaitForProvider() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);

        broker.admit(consumerDescriptor("[1.0.0,2.0.0)", ABI, false));

        scheduler.shutdown();
    }

    @Test
    void duplicateProviderTypeIsRejected() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        broker.admit(providerDescriptor());
        final PluginDescriptor duplicate = descriptor(
            "dev.example.other-provider",
            "1.0.0",
            List.of(),
            List.of(new CorePluginDescriptor.CoreEventExport(
                "other.ready",
                "1.0.0",
                PublicFixtureEvent.class.getName(),
                ABI
            )),
            List.of()
        );

        assertThrows(IllegalArgumentException.class, () -> broker.admit(duplicate));
        scheduler.shutdown();
    }

    @Test
    void providerReloadPreservesDependentSubscriptions() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        ));
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(consumer.key(), PublicFixtureEvent.class, ignored -> delivered.countDown());
        provider.activate();
        consumer.activate();

        provider.beginClosing();
        assertTrue(provider.awaitQuiescence(Duration.ZERO));
        provider.close();
        final RuntimeEventBroker.Owner replacement = broker.admit(providerDescriptor());
        replacement.activate();
        broker.publish(replacement.key(), new PublicFixtureEvent("after-reload"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void admittedReplacementDoesNotRevokeActiveProviderAuthority() throws Exception {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        ));
        final CountDownLatch delivered = new CountDownLatch(1);
        broker.subscribe(consumer.key(), PublicFixtureEvent.class, ignored -> delivered.countDown());
        provider.activate();
        consumer.activate();

        broker.admit(providerDescriptor());
        broker.publish(provider.key(), new PublicFixtureEvent("during-reload"));

        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        scheduler.shutdown();
    }

    @Test
    void replacementBecomesAuthoritativeOnlyAfterActivation() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        provider.activate();
        final RuntimeEventBroker.Owner replacement = broker.admit(providerDescriptor());

        assertDoesNotThrow(
            () -> broker.publish(provider.key(), new PublicFixtureEvent("before-activation"))
        );
        replacement.activate();
        assertThrows(
            IllegalStateException.class,
            () -> broker.publish(provider.key(), new PublicFixtureEvent("stale"))
        );
        assertDoesNotThrow(
            () -> broker.publish(replacement.key(), new PublicFixtureEvent("active"))
        );
        scheduler.shutdown();
    }

    @Test
    void pendingReplacementDoesNotChangeProviderContractUsedForNewConsumers() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        provider.activate();
        broker.admit(providerDescriptor("2.0.0"));

        assertDoesNotThrow(() -> broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        )));
        scheduler.shutdown();
    }

    @Test
    void failedReplacementActivationLeavesPreviousProviderAuthoritative() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner provider = broker.admit(providerDescriptor());
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            true
        ));
        provider.activate();
        consumer.activate();
        final RuntimeEventBroker.Owner replacement = broker.admit(providerDescriptor(
            "2.0.0"
        ));

        assertThrows(IllegalArgumentException.class, replacement::activate);
        assertDoesNotThrow(
            () -> broker.publish(provider.key(), new PublicFixtureEvent("still-active"))
        );
        scheduler.shutdown();
    }

    @Test
    void publicImportRetainsRoutingPolicyWhileProviderIsAbsent() {
        final RuntimeScheduler scheduler = scheduler();
        final RuntimeEventBroker broker = new RuntimeEventBroker(scheduler);
        final RuntimeEventBroker.Owner consumer = broker.admit(consumerDescriptor(
            "[1.0.0,2.0.0)",
            ABI,
            false
        ));
        final RuntimeEventBroker.Owner undeclared = broker.admit("dev.example.undeclared");

        broker.subscribe(consumer.key(), PublicFixtureEvent.class, ignored -> { });
        assertThrows(
            IllegalArgumentException.class,
            () -> broker.subscribe(undeclared.key(), PublicFixtureEvent.class, ignored -> { })
        );
        scheduler.shutdown();
    }

    private static PluginDescriptor providerDescriptor() {
        return providerDescriptor("1.4.0");
    }

    private static PluginDescriptor providerDescriptor(final String version) {
        return descriptor(
            PROVIDER,
            version,
            List.of(),
            List.of(new CorePluginDescriptor.CoreEventExport(
                EVENT_ID,
                "1.2.0",
                PublicFixtureEvent.class.getName(),
                ABI
            )),
            List.of()
        );
    }

    private static PluginDescriptor consumerDescriptor(
        final String contractVersion,
        final String abi,
        final boolean required
    ) {
        return descriptor(
            CONSUMER,
            "1.0.0",
            List.of(new CorePluginDescriptor.CoreDependencyRef(
                PROVIDER,
                "required",
                "[1.0.0,2.0.0)",
                "after",
                Optional.empty()
            )),
            List.of(),
            List.of(new CorePluginDescriptor.CoreEventImport(
                PROVIDER,
                EVENT_ID,
                contractVersion,
                PublicFixtureEvent.class.getName(),
                abi,
                required
            ))
        );
    }

    private static PluginDescriptor descriptor(
        final String id,
        final String version,
        final List<PluginDescriptor.DependencyRef> dependencies,
        final List<PluginDescriptor.EventExport> exports,
        final List<PluginDescriptor.EventImport> imports
    ) {
        return new CorePluginDescriptor(
            id,
            id,
            version,
            "",
            List.of("dev.example.Plugin"),
            "[0.1.0,0.2.0)",
            List.of(new CorePluginDescriptor.CoreAuthor("Example", Optional.empty())),
            "",
            Optional.of("https://example.invalid"),
            List.of(),
            new CorePluginDescriptor.CoreI18n("example.messages", List.of()),
            dependencies,
            List.of(),
            List.of(),
            new CorePluginDescriptor.CoreEnvironment(false, "none"),
            Optional.of("development"),
            List.of(),
            exports,
            imports
        );
    }

    private static RuntimeScheduler scheduler() {
        return new RuntimeScheduler(
            new DefaultWorkBudgetPolicy(),
            new PluginWorkExecutorRegistry(1, 8, ignored -> { }, CLOCK),
            new NoOpSidecarDispatcher(),
            ignored -> { }
        );
    }

    public record PublicFixtureEvent(String value) implements TurboismEvent {
    }

    private static final class NoOpSidecarDispatcher implements SidecarDispatcher {
        @Override
        public CompletionStage<SidecarResult> dispatch(
            final PluginTask task,
            final Runnable callback
        ) {
            return CompletableFuture.completedFuture(SidecarResult.success(""));
        }
    }
}
