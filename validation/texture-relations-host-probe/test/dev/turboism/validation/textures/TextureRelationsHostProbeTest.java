package dev.turboism.validation.textures;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.psd.PsdEditFile;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import dev.turboism.sdk.cubism.psd.PsdFileOperationResult;
import dev.turboism.sdk.cubism.psd.PsdFileRevision;
import dev.turboism.sdk.plugin.Registration;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Offline smoke-assertion tests; never loads or launches Cubism. */
public final class TextureRelationsHostProbeTest {
    public static void main(String[] args) throws Exception {
        if (!"5.0.00".equals(TextureRelationsHostProbe.expectedVersion("5000"))
                || !"5.3.04".equals(TextureRelationsHostProbe.expectedVersion("5304")))
            throw new AssertionError("wrong exact host label");
        try { TextureRelationsHostProbe.expectedVersion("5305"); throw new AssertionError("unknown host accepted"); }
        catch (IllegalArgumentException expected) { }
        rejects(null);
        rejects(TextureRelationsSnapshot.unavailable());
        rejects(new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "test", 1, 1, List.of(), List.of(), List.of(), List.of()));
        final var rawId = new RawImageId("raw");
        final var modelId = new ModelImageId("image");
        final var meshId = new ArtMeshId("mesh");
        final var raw = new RawImageDetails(stub(RawTexture.class, rawId), RawImageDetails.SourceKind.UNKNOWN,
            List.of(), false, Optional.empty(), Optional.empty(), Optional.empty());
        final var image = new ModelImageRelation(modelId, stub(ModelImageEntry.class, modelId),
            List.of(rawId), Optional.of(rawId), Map.of(), List.of(meshId));
        final var mesh = new ArtMeshTextureInputs(meshId, List.of(TextureInputBinding.modelImage(modelId)),
            OptionalInt.of(0));
        TextureRelationsHostProbe.validate(snapshot(List.of(raw), List.of(image), List.of(mesh)));
        rejects(snapshot(List.of(raw, raw), List.of(image), List.of(mesh)));
        rejects(snapshot(List.of(raw), List.of(image, image), List.of(mesh)));
        rejects(snapshot(List.of(raw), List.of(image), List.of(mesh, mesh)));
        rejects(snapshot(List.of(raw), List.of(image), List.of(new ArtMeshTextureInputs(meshId,
            List.of(TextureInputBinding.modelImage(new ModelImageId("missing"))), OptionalInt.of(0)))));
        rejects(snapshot(List.of(raw), List.of(image), List.of(new ArtMeshTextureInputs(meshId,
            List.of(TextureInputBinding.unknown()), OptionalInt.empty()))));
        System.out.println("PASS: 9 offline smoke assertion cases; not host evidence");
        final String observation = "PSD_NATIVE_EXPORT;status=READABLE_UNVERIFIED;integrity=MATCHED_UNVERIFIED;readable=true;structure=true";
        TextureRelationsHostProbe.validateExportObservation(new PsdExportResult(PsdExportResult.Status.EXPORTED,
            observation, rawId, Optional.of(editFile()), Optional.of(new PsdFileRevision() { })));
        for (final String integrity : List.of("MATCHED_UNVERIFIED", "MISMATCH", "UNAVAILABLE")) {
            TextureRelationsHostProbe.validateExportObservation(new PsdExportResult(PsdExportResult.Status.EXPORTED,
                observation.replace("MATCHED_UNVERIFIED", integrity).replace("structure=true", "structure=false"),
                rawId, Optional.of(editFile()), Optional.of(new PsdFileRevision() { })));
        }
        for (final var invalid : List.of(
            new PsdExportResult(PsdExportResult.Status.FAILED, observation, rawId, Optional.empty(), Optional.empty()),
            new PsdExportResult(PsdExportResult.Status.UNAVAILABLE, observation, rawId, Optional.empty(), Optional.empty()),
            new PsdExportResult(PsdExportResult.Status.FAILED, observation.replace("readable=true", "readable=false"),
                rawId, Optional.empty(), Optional.empty()),
            new PsdExportResult(PsdExportResult.Status.FAILED, "not observed", rawId, Optional.empty(), Optional.empty()))) {
            try { TextureRelationsHostProbe.validateExportObservation(invalid); }
            catch (IllegalStateException expected) { continue; }
            throw new AssertionError("Expected export observation rejection");
        }
        System.out.println("PASS: 8 readable-v2 export-observation assertion cases; not host evidence");
        final String saved = "PSD_NATIVE_EXPORT;status=SAVED_UNVERIFIED;integrity=UNAVAILABLE;readable=true;structure=false";
        final int[] stopCalls = {0};
        final var savedResult = new PsdExportResult(PsdExportResult.Status.EXPORTED, saved, rawId,
            Optional.of(stoppableFile(stopCalls, false)), Optional.of(new PsdFileRevision() { }));
        TextureRelationsHostProbe.finishExportObservation(savedResult, new Properties(), true);
        if (stopCalls[0] != 1) throw new AssertionError("successful save did not stop exactly once");
        try {
            TextureRelationsHostProbe.finishExportObservation(savedResult, new Properties(), false);
            throw new AssertionError("save-only silently satisfied readable profile");
        } catch (IllegalStateException expected) { }
        if (stopCalls[0] != 2) throw new AssertionError("failed assertion leaked issued handle");
        for (String invalid : List.of(saved.replace("readable=true", "readable=false"),
                saved.replace("structure=false", "structure=true"), saved.replace("UNAVAILABLE", "MISMATCH"), observation)) {
            try {
                TextureRelationsHostProbe.validateSavedExportObservation(new PsdExportResult(
                    PsdExportResult.Status.EXPORTED, invalid, rawId, Optional.of(editFile()),
                    Optional.of(new PsdFileRevision() { })));
                throw new AssertionError("invalid save-only observation accepted");
            } catch (IllegalStateException expected) { }
        }
        final var stopFailure = new PsdExportResult(PsdExportResult.Status.EXPORTED, saved, rawId,
            Optional.of(stoppableFile(stopCalls, true)), Optional.of(new PsdFileRevision() { }));
        try {
            TextureRelationsHostProbe.finishExportObservation(stopFailure, new Properties(), false);
            throw new AssertionError("invalid readable observation accepted");
        } catch (IllegalStateException expected) {
            if (expected.getSuppressed().length != 1) throw new AssertionError("cleanup failure lost primary assertion", expected);
        }
        try {
            TextureRelationsHostProbe.finishExportObservation(stopFailure, new Properties(), true);
            throw new AssertionError("failed cleanup passed");
        } catch (java.util.concurrent.ExecutionException expected) { }
        System.out.println("PASS: save-only isolation, strict diagnostics, stop-on-failure and cleanup failure preservation");
    }

    static PsdEditFile stoppableFile(int[] calls, boolean fail) {
        return new PsdEditFile() {
            @Override public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                throw new AssertionError("external open not authorized by this scenario");
            }
            @Override public Registration observeSaves(Consumer<PsdFileRevision> listener) {
                throw new AssertionError("external save not part of this scenario");
            }
            @Override public CompletionStage<PsdFileOperationResult> stop() {
                calls[0]++;
                if (fail) return java.util.concurrent.CompletableFuture.failedFuture(new IllegalStateException("stop failed"));
                return java.util.concurrent.CompletableFuture.completedFuture(
                    new PsdFileOperationResult(PsdFileOperationResult.Status.STOPPED, "stopped"));
            }
        };
    }

    static PsdEditFile editFile() {
        return new PsdEditFile() {
            @Override public CompletionStage<PsdFileOperationResult> openInDefaultApplication() {
                throw new AssertionError("open must not run in an offline assertion");
            }
            @Override public Registration observeSaves(Consumer<PsdFileRevision> listener) {
                throw new AssertionError("observe must not run in an offline assertion");
            }
            @Override public CompletionStage<PsdFileOperationResult> stop() {
                throw new AssertionError("stop must not run in an offline assertion");
            }
        };
    }
    static TextureRelationsSnapshot snapshot(List<RawImageDetails> raw, List<ModelImageRelation> images,
        List<ArtMeshTextureInputs> meshes) {
        return new TextureRelationsSnapshot(TextureRelationsSnapshot.Availability.AVAILABLE,
            "test", 1, 1, raw, images, List.of(), meshes);
    }
    static void rejects(TextureRelationsSnapshot value) {
        try { TextureRelationsHostProbe.validate(value); }
        catch (IllegalStateException expected) { return; }
        throw new AssertionError("Expected rejection");
    }
    static <T> T stub(Class<T> type, Object id) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, args) -> switch (method.getName()) {
                case "id" -> id;
                case "name" -> "fixture";
                case "widthPixels", "heightPixels" -> 1;
                default -> throw new AssertionError("Unexpected fixture accessor " + method);
            }));
    }
}
