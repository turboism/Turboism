package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.CubismModel;
import dev.turboism.sdk.cubism.model.ParameterDefinition;
import dev.turboism.sdk.cubism.model.ParameterType;
import dev.turboism.sdk.cubism.model.PartId;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionOptions;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionResult;
import dev.turboism.sdk.cubism.transaction.AuthoringTransactionService;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/** SDK-only prepared import. Preparation is pure; apply owns exactly one authoring root.
 * Native rollback/Undo/Redo correctness remains the runtime's responsibility. */
final class SurfaceImport {
    private SurfaceImport() { }
    static final double IDEAL_TOLERANCE_PIXELS = .05;

    record Request(String name, ParameterId yaw, ParameterId pitch, SurfaceProjection.Camera camera,
            double yawLimit, double pitchLimit, long maxProjectedVertices, java.util.Optional<ParameterId> roll) {
        Request(String name, ParameterId yaw, ParameterId pitch, SurfaceProjection.Camera camera,
                double yawLimit, double pitchLimit, long maxProjectedVertices) {
            this(name,yaw,pitch,camera,yawLimit,pitchLimit,maxProjectedVertices,java.util.Optional.empty());
        }
        Request {
            name = Objects.requireNonNull(name, "name").strip();
            Objects.requireNonNull(yaw, "yaw"); Objects.requireNonNull(pitch, "pitch");
            Objects.requireNonNull(camera, "camera");
            roll=Objects.requireNonNull(roll, "roll");
            if (roll.filter(id->id.equals(yaw) || id.equals(pitch)).isPresent())
                throw new IllegalArgumentException("rotation axes must be distinct");
            if (name.isEmpty() || name.length() > 80 || yaw.equals(pitch)
                    || !Double.isFinite(yawLimit) || !Double.isFinite(pitchLimit)
                    || yawLimit <= 0 || yawLimit > 180 || pitchLimit <= 0 || pitchLimit > 180
                    || maxProjectedVertices < 1 || maxProjectedVertices > SurfaceImportPlan.MAX_PROJECTED_VERTICES) {
                throw new IllegalArgumentException("invalid import name, axes, rotation domain or allocation budget");
            }
        }
    }

    enum VisibilityKind { CLOSED_CONVEX, PLANAR_QUAD, PARALLEL_SHEETS }
    record Visibility(VisibilityKind kind,int sourceSlots,int triangles) {}

    static final class Prepared {
        final Request request;
        final String inputSha256;
        final Visibility visibility;
        final SurfaceImportPlan.Plan geometry;
        final SurfaceBaseColorPlan.Plan appearance;
        final ParallelSheetOrderPlan.Plan sheetOrders;
        private Prepared(Request request, String digest, Visibility visibility,
                SurfaceImportPlan.Plan geometry, SurfaceBaseColorPlan.Plan appearance, ParallelSheetOrderPlan.Plan sheetOrders) {
            this.request=request;this.inputSha256=digest;this.visibility=visibility;
            this.geometry=geometry;this.appearance=appearance;
            this.sheetOrders=sheetOrders;
        }
    }
    record Imported(String inputSha256, PartId partId, ParameterId yaw, ParameterId pitch,
            SurfaceImportGeometry.Created geometry, List<SurfaceImportAppearance.BoundMaterial> materials) {
        Imported { materials=List.copyOf(materials); }
    }
    enum Stage { AXES, GROUP, GEOMETRY, BAKE, MATERIAL, VISIBILITY }
    @FunctionalInterface interface Observer { void completed(Stage stage); }

    static Prepared prepare(byte[] glb, Request request) throws IOException {
        return prepare(glb,request,SurfaceBaseColorPlan.Appearance.SOURCE_MATERIAL);
    }

    /** Explicit face colors aid inspection; source materials remain the default. */
    static Prepared prepare(byte[] glb, Request request, SurfaceBaseColorPlan.Appearance appearanceMode) throws IOException {
        Objects.requireNonNull(request, "request");
        byte[] frozen=Objects.requireNonNull(glb,"glb").clone();
        var surfaces=GlbSurfaceReader.read(frozen);
        return prepareDecoded(frozen,surfaces,request,appearanceMode);
    }

    /** User-entry framing is fitted once; direct probes can retain their explicit camera. */
    static Prepared prepareFramed(byte[] glb,Request request,SurfaceBaseColorPlan.Appearance appearanceMode) throws IOException {
        Objects.requireNonNull(request,"request");
        byte[] frozen=Objects.requireNonNull(glb,"glb").clone();
        var surfaces=GlbSurfaceReader.read(frozen);
        var camera=SurfaceAutoCamera.fit(surfaces,request.camera().centerX(),request.camera().centerY(),400);
        var framed=new Request(request.name(),request.yaw(),request.pitch(),camera,
            request.yawLimit(),request.pitchLimit(),request.maxProjectedVertices(),request.roll());
        return prepareDecoded(frozen,surfaces,framed,appearanceMode);
    }

    private static Prepared prepareDecoded(byte[] frozen,List<GlbSurfaceReader.Surface> surfaces,Request request,
            SurfaceBaseColorPlan.Appearance appearanceMode) throws IOException {
        Visibility visibility;
        boolean sheets=surfaces.size()==2 && surfaces.stream().allMatch(GlbSurfaceReader.Surface::doubleSided);
        if(sheets) {
            visibility=new Visibility(VisibilityKind.PARALLEL_SHEETS,8,4);
        } else if(surfaces.size()==1 && surfaces.get(0).indices().size()==6) {
            var proof=PlanarQuadProof.verify(surfaces);
            visibility=new Visibility(VisibilityKind.PLANAR_QUAD,proof.sourceSlots(),proof.triangles());
        } else {
            var proof=ClosedConvexSurfaceProof.verify(surfaces);
            visibility=new Visibility(VisibilityKind.CLOSED_CONVEX,proof.sourceSlots(),proof.triangles());
        }
        var geometry=SurfaceImportPlan.compile(surfaces, request.camera(), request.yawLimit(), request.pitchLimit(),
            IDEAL_TOLERANCE_PIXELS, request.maxProjectedVertices());
        ParallelSheetOrderPlan.Plan sheetOrders=null;
        if(sheets) {
            var keys=SurfaceKeyformBake.bindingKeys(geometry.grid().yawKeys());
            double transition=Math.max(keys.stream().filter(k->k>0).mapToDouble(Float::doubleValue).min().orElseThrow(),
                -keys.stream().filter(k->k<0).mapToDouble(Float::doubleValue).max().orElseThrow());
            sheetOrders=ParallelSheetOrderPlan.compile(surfaces,request.camera(),request.yawLimit(),request.pitchLimit(),transition);
            if(geometry.patches().size()!=2 || geometry.patches().get(0).surfaceIndex()!=0 || geometry.patches().get(1).surfaceIndex()!=1)
                throw new IllegalArgumentException("parallel sheets require one patch per source primitive");
            ParallelSheetOrderPlan.verifyStoredTransition(sheetOrders,geometry);
        }
        var appearance=SurfaceBaseColorPlan.compile(surfaces,geometry,appearanceMode);
        try {
            return new Prepared(request,HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(frozen)),
                visibility,geometry,appearance,sheetOrders);
        } catch(NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    static AuthoringTransactionResult<Imported> apply(Prepared prepared, Supplier<CubismModel> current,
            AuthoringTransactionService transactions) {
        return apply(prepared,current,transactions,stage -> { });
    }

    static Imported assemble(CubismModel model, Prepared prepared, Observer observer) {
        Objects.requireNonNull(model, "model");
        Objects.requireNonNull(prepared, "prepared");
        Objects.requireNonNull(observer, "observer");
        var request = prepared.request;
        // Check both identities before the first write; never replace existing user parameters.
        if (model.parameters().all().stream().anyMatch(p -> p.id().equals(request.yaw()) || p.id().equals(request.pitch()))) {
            throw new IllegalArgumentException("import parameter ID already exists");
        }
        var yaw = SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().yawKeys());
        var pitch = SurfaceKeyformBake.bindingKeys(prepared.geometry.grid().pitchKeys());
        model.parameters().createMany(List.of(
            new ParameterDefinition(request.yaw(), request.name() + " Yaw", yaw.get(0), 0, yaw.get(yaw.size() - 1), ParameterType.NORMAL, false),
            new ParameterDefinition(request.pitch(), request.name() + " Pitch", pitch.get(0), 0, pitch.get(pitch.size() - 1), ParameterType.NORMAL, false)));
        observer.completed(Stage.AXES);
        var part = model.parts().create(request.name(), null, -1);
        observer.completed(Stage.GROUP);
        var geometry = SurfaceImportGeometry.create(model, prepared.geometry, request.yaw(), request.pitch(), part,
            request.name(), prepared.appearance.patches().stream().map(p -> p.material().textured()
                ? SurfaceImportGeometry.UvMode.EDGE_PADDED_IMAGE_PIXELS : SurfaceImportGeometry.UvMode.FLAT_COLOR_INTERIOR).toList());
        observer.completed(Stage.GEOMETRY);
        geometry.bake(model);
        observer.completed(Stage.BAKE);
        var materials = SurfaceImportAppearance.bind(model, geometry, prepared.appearance);
        observer.completed(Stage.MATERIAL);
        boolean culling = prepared.sheetOrders == null;
        for (var patch : geometry.patches()) model.drawables().find(patch.meshId()).setCulling(culling);
        for (var patch : geometry.patches()) {
            var mesh = model.drawables().find(patch.meshId());
            if (mesh.culling() != culling) throw new IllegalStateException("native culling state was not retained");
            patch.bake().verify(mesh);
        }
        if (prepared.sheetOrders != null) for (int i = 0; i < geometry.patches().size(); i++)
            SurfaceSheetOrderBake.write(model.drawables().find(geometry.patches().get(i).meshId()),
                prepared.sheetOrders, prepared.geometry.patches().get(i).surfaceIndex(), request.yaw());
        observer.completed(Stage.VISIBILITY);
        return new Imported(prepared.inputSha256, part.id(), request.yaw(), request.pitch(), geometry, materials);
    }

    /** Package-only observation points allow the native probe to inject failures in the actual importer. */
    static AuthoringTransactionResult<Imported> apply(Prepared prepared, Supplier<CubismModel> current,
            AuthoringTransactionService transactions, Observer observer) {
        Objects.requireNonNull(prepared,"prepared"); Objects.requireNonNull(current,"current");
        Objects.requireNonNull(transactions,"transactions"); Objects.requireNonNull(observer,"observer");
        return transactions.execute(AuthoringTransactionOptions.of("Import 3D " + prepared.request.name()), () -> {
            return assemble(Objects.requireNonNull(current.get(), "current model"), prepared, observer);
        });
    }
}
