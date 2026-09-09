package dev.turboism.validation.textures;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.psd.PsdExportResult;
import java.lang.reflect.Proxy;
import java.util.*;

/** Offline smoke-assertion tests; never loads or launches Cubism. */
public final class TextureRelationsHostProbeTest {
    public static void main(String[] args) {
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
        TextureRelationsHostProbe.validateExportObservation(new PsdExportResult(PsdExportResult.Status.FAILED,
            observation, rawId, Optional.empty(), Optional.empty()));
        for (final var invalid : List.of(
            new PsdExportResult(PsdExportResult.Status.UNAVAILABLE, observation, rawId, Optional.empty(), Optional.empty()),
            new PsdExportResult(PsdExportResult.Status.FAILED, observation.replace("structure=true", "structure=false"),
                rawId, Optional.empty(), Optional.empty()),
            new PsdExportResult(PsdExportResult.Status.FAILED, "not observed", rawId, Optional.empty(), Optional.empty()))) {
            try { TextureRelationsHostProbe.validateExportObservation(invalid); }
            catch (IllegalStateException expected) { continue; }
            throw new AssertionError("Expected export observation rejection");
        }
        System.out.println("PASS: 4 export-observation assertion cases; not host evidence");
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
