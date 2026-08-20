package dev.turboism.core.plugin.context;

import dev.turboism.sdk.Cubism;
import dev.turboism.sdk.cubism.CubismApiUnavailableException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CubismApiAvailabilityInterceptorTest {

    @Test
    void rejectsNarrowMethodBeforeDelegateOnUnsupportedHost() {
        final AtomicInteger calls = new AtomicInteger();
        final Example delegate = new ExampleImpl(calls);
        final Example proxy = proxy(delegate, Optional.of("5.2.03"));

        final CubismApiUnavailableException failure = assertThrows(
            CubismApiUnavailableException.class,
            proxy::only5302
        );

        assertEquals(0, calls.get());
        assertEquals(Optional.of("5.2.03"), failure.activeVersion());
        assertEquals(List.of("5.3.02"), failure.supportedVersions());
    }

    @Test
    void failsClosedWithoutVerifiedEditorVersion() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.empty());

        final CubismApiUnavailableException failure = assertThrows(
            CubismApiUnavailableException.class,
            proxy::shared
        );

        assertEquals(Optional.empty(), failure.activeVersion());
    }

    @Test
    void recursivelyWrapsOptionalListAndCompletionStageResults() {
        final Example proxy = proxy(new ExampleImpl(new AtomicInteger()), Optional.of("5.2.03"));

        assertThrows(CubismApiUnavailableException.class, () -> proxy.optional().orElseThrow().only5302());
        assertThrows(CubismApiUnavailableException.class, () -> proxy.list().get(0).only5302());
        assertThrows(
            CubismApiUnavailableException.class,
            () -> proxy.stage().toCompletableFuture().join().only5302()
        );
    }

    @Test
    void unwrapsOwnedProxyArgumentsBeforeDelegateInvocation() {
        final AtomicReference<Example> received = new AtomicReference<>();
        final ExampleImpl delegate = new ExampleImpl(new AtomicInteger()) {
            @Override public void accept(final Example example) { received.set(example); }
        };
        final Example proxy = proxy(delegate, Optional.of("5.3.02"));
        final Example nested = proxy.optional().orElseThrow();

        proxy.accept(nested);

        assertNotSame(delegate, nested);
        assertSame(delegate, received.get());
    }

    private static Example proxy(final Example delegate, final Optional<String> version) {
        return (Example) new CubismApiAvailabilityInterceptor(() -> version)
            .wrapForTesting(delegate, Example.class);
    }

    @Cubism({"5.2.03", "5.3.02"})
    interface Example {
        void shared();

        @Cubism("5.3.02")
        void only5302();

        Optional<Example> optional();

        List<Example> list();

        CompletionStage<Example> stage();

        void accept(Example example);
    }

    static class ExampleImpl implements Example {
        private final AtomicInteger calls;

        ExampleImpl(final AtomicInteger calls) {
            this.calls = calls;
        }

        @Override public void shared() { calls.incrementAndGet(); }

        @Override public void only5302() { calls.incrementAndGet(); }

        @Override public Optional<Example> optional() { return Optional.of(this); }

        @Override public List<Example> list() { return List.of(this); }

        @Override public CompletionStage<Example> stage() {
            return CompletableFuture.completedFuture(this);
        }

        @Override public void accept(final Example example) { }
    }
}
