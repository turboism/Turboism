package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.model.CubismModelAccess;

import java.util.Objects;

/**
 * Runtime-owned composition root for the verified Core model read backend.
 *
 * <p>The backend owns the borrowed-model source and structural call sites for one admitted host
 * profile. Publishing and clearing borrowed models remain Runtime operations; plugin code receives
 * only {@link CubismModelAccess}. Closing the backend waits for scoped reads, forgets borrowed
 * references, and never closes an Editor-owned Core model.</p>
 */
public final class RuntimeCoreModelBackend implements AutoCloseable {

    private final BorrowedCoreModelSource source;
    private final CoreStructuralTracer tracer;
    private final CubismModelAccess modelAccess;
    private final Object lifecycle = new Object();
    private boolean closed;

    private RuntimeCoreModelBackend(
        final BorrowedCoreModelSource source,
        final CoreStructuralTracer tracer,
        final CorePublicApiProvider provider
    ) {
        this.source = Objects.requireNonNull(source, "source");
        this.tracer = Objects.requireNonNull(tracer, "tracer");
        this.modelAccess = new CoreBackedCubismModelAccess(source, provider, tracer);
    }

    /** Admits one exact Core provider and its complete structural call-site table. */
    public static CoreProviderResult<RuntimeCoreModelBackend> admit(
        final VerifiedMemberResolver resolver,
        final CoreVersionExpectation expectation
    ) {
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(expectation, "expectation");
        final CoreProviderResult<CorePublicApiProvider> providerResult =
            CorePublicApiProviderFactory.admit(resolver, expectation);
        if (!providerResult.isSuccess()) {
            return CoreProviderResult.failed(
                providerResult.failure().orElseThrow()
            );
        }
        final CorePublicApiProvider provider = providerResult.value().orElseThrow();
        final CoreProviderResult<CoreStructuralTracer> tracerResult =
            CoreStructuralTracerFactory.admit(provider, resolver);
        if (!tracerResult.isSuccess()) {
            return CoreProviderResult.failed(
                tracerResult.failure().orElseThrow()
            );
        }
        return CoreProviderResult.success(new RuntimeCoreModelBackend(
            new BorrowedCoreModelSource(),
            tracerResult.value().orElseThrow(),
            provider
        ));
    }

    /** Returns the plugin-facing model access without exposing provider or host objects. */
    public CubismModelAccess modelAccess() {
        return modelAccess;
    }

    /** Publishes the current Editor-owned Core model after verified Runtime acquisition. */
    public void publishBorrowedModel(
        final Object borrowedModel,
        final String modelIdentity
    ) {
        synchronized (lifecycle) {
            requireOpen();
            source.publishBorrowedModel(borrowedModel, modelIdentity);
        }
    }

    /** Clears the current model and invalidates every previously issued SDK object. */
    public void clearBorrowedModel() {
        synchronized (lifecycle) {
            requireOpen();
            source.clearBorrowedModel();
        }
    }

    @Override
    public void close() {
        synchronized (lifecycle) {
            if (closed) {
                return;
            }
            closed = true;
            source.close();
            tracer.close();
        }
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("Runtime Core model backend is closed.");
        }
    }
}
