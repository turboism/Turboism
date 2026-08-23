package dev.turboism.adapter.cubism.core;

import dev.turboism.mapping.verification.selector.OwnedMocSelectorContract;
import dev.turboism.mapping.verification.VerifiedAccessException;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.sdk.cubism.core.MocConsistency;
import dev.turboism.sdk.cubism.core.MocData;
import dev.turboism.sdk.cubism.core.MocLoader;
import dev.turboism.sdk.cubism.core.MocVersion;
import dev.turboism.sdk.cubism.core.OwnedCanvasInfo;
import dev.turboism.sdk.cubism.core.OwnedDeformer;
import dev.turboism.sdk.cubism.core.OwnedDrawable;
import dev.turboism.sdk.cubism.core.OwnedGlue;
import dev.turboism.sdk.cubism.core.OwnedMoc;
import dev.turboism.sdk.cubism.core.OwnedModel;
import dev.turboism.sdk.cubism.core.OwnedParameter;
import dev.turboism.sdk.cubism.core.OwnedPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Runtime implementation of the owned-Moc {@link MocLoader} projection.
 *
 * <p>Builds plugin-owned Core models from {@code .moc3} bytes through the verified
 * Core public API. The structural read surface (canvas, parameters, parts, drawables,
 * glues, deformers) reuses {@link CoreCallSiteTable}; lifecycle (instantiate, update,
 * close, native handles) goes through {@link CorePublicApiProvider}. No Core write
 * member is exposed.</p>
 *
 * <p>Fail-closed: construction requires the provider, the resolver, and the additive
 * owned-Moc selector evidence for the exact artifact profile.</p>
 */
final class OwnedMocRuntime implements MocLoader {

    private final CorePublicApiProvider provider;
    private final CoreCallSiteTable callSites;
    private final Runnable freshness;
    private final int mocByteQuota;

    private OwnedMocRuntime(
        final CorePublicApiProvider provider,
        final CoreCallSiteTable callSites,
        final Runnable freshness,
        final int mocByteQuota
    ) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.callSites = Objects.requireNonNull(callSites, "callSites");
        this.freshness = Objects.requireNonNull(freshness, "freshness");
        if (mocByteQuota < 1) {
            throw new IllegalArgumentException("mocByteQuota must be positive");
        }
        this.mocByteQuota = mocByteQuota;
    }

    static CoreProviderResult<OwnedMocRuntime> admit(
        final CorePublicApiProvider provider,
        final VerifiedMemberResolver resolver,
        final Runnable freshness,
        final int mocByteQuota
    ) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(resolver, "resolver");
        Objects.requireNonNull(freshness, "freshness");
        if (!provider.available()) {
            return failed(
                CoreProviderFailure.Code.ADAPTER_UNAVAILABLE,
                "Core public API provider is unavailable."
            );
        }
        if (!resolver.authorizesFeature(
            OwnedMocSelectorContract.ADAPTER_SLICE_ID,
            OwnedMocSelectorContract.CAPABILITY_ID,
            OwnedMocSelectorContract.REQUIRED_ALIASES
        )) {
            return failed(
                CoreProviderFailure.Code.EVIDENCE_REJECTED,
                "Verified resolver does not authorize the owned-Moc selector contract."
            );
        }
        try {
            final CoreCallSiteTable callSites = CoreCallSiteTable.bind(
                resolver,
                provider.artifactProfile()
            );
            return CoreProviderResult.success(new OwnedMocRuntime(
                provider,
                callSites,
                freshness,
                mocByteQuota
            ));
        } catch (VerifiedAccessException exception) {
            return failed(
                exception.failureKind() == VerifiedAccessException.FailureKind.RESOLUTION
                    ? CoreProviderFailure.Code.RESOLUTION_FAILED
                    : CoreProviderFailure.Code.INVOCATION_FAILED,
                "Core owned-Moc call sites could not be bound safely."
            );
        } catch (RuntimeException exception) {
            return failed(
                CoreProviderFailure.Code.RESOLUTION_FAILED,
                "Core owned-Moc call-site admission failed safely."
            );
        }
    }

    @Override
    public OwnedMoc load(final MocData data) {
        final MocData value = Objects.requireNonNull(data, "data");
        if (value.size() > mocByteQuota) {
            throw new IllegalArgumentException(
                "MOC data exceeds the configured byte quota of " + mocByteQuota + "."
            );
        }
        freshness.run();
        final byte[] bytes = value.toByteArray();
        final MocVersion version = normalizeVersion(requireValue(
            provider.mocVersion(bytes.clone()),
            "Core MOC version"
        ));
        final boolean consistent = requireValue(
            provider.hasMocConsistency(bytes.clone()),
            "Core MOC consistency"
        );
        final Object rawMoc = requireValue(
            provider.instantiateMoc(bytes.clone()),
            "Core MOC instantiation"
        );
        return new OwnedMocImpl(
            rawMoc,
            version,
            consistent ? MocConsistency.CONSISTENT : MocConsistency.INCONSISTENT
        );
    }

    private static MocVersion normalizeVersion(final int version) {
        return switch (version) {
            case 1 -> MocVersion.V3_0;
            case 2 -> MocVersion.V3_3;
            case 3 -> MocVersion.V4_0;
            case 4 -> MocVersion.V4_2;
            case 5 -> MocVersion.V5_0;
            case 6 -> MocVersion.V5_3;
            default -> MocVersion.UNKNOWN;
        };
    }

    private final class OwnedMocImpl implements OwnedMoc {

        private final Object rawMoc;
        private final MocVersion version;
        private final MocConsistency consistency;
        private boolean closed;

        private OwnedMocImpl(
            final Object rawMoc,
            final MocVersion version,
            final MocConsistency consistency
        ) {
            this.rawMoc = Objects.requireNonNull(rawMoc, "rawMoc");
            this.version = Objects.requireNonNull(version, "version");
            this.consistency = Objects.requireNonNull(consistency, "consistency");
        }

        @Override
        public MocVersion version() {
            return version;
        }

        @Override
        public MocConsistency consistency() {
            return consistency;
        }

        @Override
        public long nativeHandle() {
            requireOpen();
            return requireValue(provider.mocNativeHandle(rawMoc), "Core MOC native handle");
        }

        @Override
        public OwnedModel instantiateModel() {
            requireOpen();
            final Object rawModel = requireValue(
                provider.instantiateOwnedModel(rawMoc),
                "Core owned model instantiation"
            );
            return new OwnedModelImpl(rawModel);
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            requireValue(provider.closeOwnedMoc(rawMoc), "Core owned MOC close");
            closed = true;
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Owned MOC is closed.");
            }
        }
    }

    private final class OwnedModelImpl implements OwnedModel {

        private final Object rawModel;
        private boolean closed;

        private OwnedModelImpl(final Object rawModel) {
            this.rawModel = Objects.requireNonNull(rawModel, "rawModel");
        }

        @Override
        public long nativeHandle() {
            requireOpen();
            return requireValue(provider.modelNativeHandle(rawModel), "Core model native handle");
        }

        @Override
        public OwnedCanvasInfo canvasInfo() {
            return toCanvas(snapshot().canvas());
        }

        @Override
        public List<OwnedParameter> parameters() {
            final List<OwnedParameter> values = new ArrayList<>();
            for (CoreParameterDefinition parameter : snapshot().parameters()) {
                values.add(new OwnedParameter(
                    parameter.id(),
                    parameter.typeNumber(),
                    parameter.minimumValue(),
                    parameter.maximumValue(),
                    parameter.defaultValue(),
                    parameter.currentValue(),
                    List.copyOf(parameter.keyValues()),
                    parameter.repeat()
                ));
            }
            return List.copyOf(values);
        }

        @Override
        public List<OwnedPart> parts() {
            final List<OwnedPart> values = new ArrayList<>();
            for (CorePartDefinition part : snapshot().parts()) {
                values.add(new OwnedPart(part.id(), part.opacity(), part.parentIndex()));
            }
            return List.copyOf(values);
        }

        @Override
        public List<OwnedDrawable> drawables() {
            final List<OwnedDrawable> values = new ArrayList<>();
            for (CoreDrawableDefinition drawable : snapshot().drawables()) {
                values.add(new OwnedDrawable(
                    drawable.id(),
                    drawable.constantFlag(),
                    drawable.dynamicFlag(),
                    drawable.blendMode(),
                    drawable.textureIndex(),
                    drawable.drawOrder(),
                    drawable.renderOrder(),
                    drawable.opacity(),
                    List.copyOf(drawable.masks()),
                    List.copyOf(drawable.vertexPositions()),
                    List.copyOf(drawable.vertexUvs()),
                    List.copyOf(drawable.indices()),
                    drawable.multiplyColor(),
                    drawable.screenColor(),
                    drawable.parentPartIndex(),
                    drawable.parentDeformerIndex(),
                    List.copyOf(drawable.parameters())
                ));
            }
            return List.copyOf(values);
        }

        @Override
        public List<OwnedGlue> glues() {
            final List<OwnedGlue> values = new ArrayList<>();
            for (CoreGlueDefinition glue : snapshot().glues()) {
                values.add(new OwnedGlue(
                    glue.id(),
                    glue.drawableA(),
                    glue.drawableB(),
                    List.copyOf(glue.parameters())
                ));
            }
            return List.copyOf(values);
        }

        @Override
        public List<OwnedDeformer> deformers() {
            final List<OwnedDeformer> values = new ArrayList<>();
            for (CoreDeformerDefinition deformer : snapshot().deformers()) {
                values.add(new OwnedDeformer(
                    deformer.id(),
                    deformer.parentDeformerIndex(),
                    List.copyOf(deformer.parameters())
                ));
            }
            return List.copyOf(values);
        }

        @Override
        public void update() {
            requireOpen();
            requireValue(provider.updateOwnedModel(rawModel), "Core owned model update");
        }

        @Override
        public void close() {
            if (closed) {
                return;
            }
            requireValue(provider.closeOwnedModel(rawModel), "Core owned model close");
            closed = true;
        }

        private CoreStructuralSnapshot snapshot() {
            requireOpen();
            try {
                return callSites.project(
                    rawModel,
                    0L,
                    "owned-moc",
                    provider.providerId(),
                    provider.artifactProfile()
                );
            } catch (CoreStructuralValidationException | IllegalArgumentException exception) {
                throw new IllegalStateException(
                    "Core owned model read failed safely: " + exception
                );
            }
        }

        private void requireOpen() {
            if (closed) {
                throw new IllegalStateException("Owned model is closed.");
            }
        }
    }

    private OwnedCanvasInfo toCanvas(final CoreCanvasSnapshot canvas) {
        return new OwnedCanvasInfo(
            canvas.widthPixels(),
            canvas.heightPixels(),
            canvas.originXPixels(),
            canvas.originYPixels(),
            canvas.pixelsPerUnit()
        );
    }

    private static <T> T requireValue(
        final CoreProviderResult<T> result,
        final String feature
    ) {
        final CoreProviderFailure failure = Objects.requireNonNull(result, "result")
            .failure().orElse(null);
        if (failure == null) {
            return result.value().orElseThrow();
        }
        if (failure.code() == CoreProviderFailure.Code.ADAPTER_UNAVAILABLE
            || failure.code() == CoreProviderFailure.Code.EVIDENCE_REJECTED) {
            throw new UnsupportedOperationException(feature + " is unavailable.");
        }
        throw new IllegalStateException(feature + " failed: " + failure.code());
    }

    private static <T> CoreProviderResult<T> failed(
        final CoreProviderFailure.Code code,
        final String message
    ) {
        return CoreProviderResult.failed(new CoreProviderFailure(code, message));
    }
}
