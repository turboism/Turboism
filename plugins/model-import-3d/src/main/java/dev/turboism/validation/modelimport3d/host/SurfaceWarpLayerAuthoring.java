package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.*;
import dev.turboism.sdk.cubism.model.*;
import dev.turboism.sdk.cubism.transaction.*;
import java.util.*;
import java.util.function.Supplier;

/** Experimental chart order binding inside the same source/Warp authoring root. */
final class SurfaceWarpLayerAuthoring {
    private SurfaceWarpLayerAuthoring() { }
    record Prepared(SurfaceWarpAuthoring.Prepared surface,SurfaceWarpLayerPlan.Orders orders,
            List<Float> layerYawKeys,List<Float> layerPitchKeys) {
        Prepared { layerYawKeys=List.copyOf(layerYawKeys);layerPitchKeys=List.copyOf(layerPitchKeys); }
        Prepared(SurfaceWarpAuthoring.Prepared surface,SurfaceWarpLayerPlan.Orders orders) {
            this(surface,orders,surface.yawKeys,surface.pitchKeys);
        }
    }
    // Cubism serializes every layer form's unchanged local positions into one
    // text entry. The real apple's 169 forms overflowed UTF-8 array sizing in
    // SAVE_AS; keep this repeated bank bounded independently of Warp accuracy.
    static final long MAX_REPEATED_LAYER_SLOTS=6_000_000L;
    static Prepared prepareStorageBounded(SurfaceWarpImportCandidate.Prepared candidate) throws java.io.IOException {
        return prepareStorageBounded(candidate,false);
    }
    static Prepared prepareStorageBoundedDerived(SurfaceWarpImportCandidate.Prepared candidate) throws java.io.IOException {
        return prepareStorageBounded(candidate,true);
    }
    private static Prepared prepareStorageBounded(SurfaceWarpImportCandidate.Prepared candidate,boolean derived) throws java.io.IOException {
        var old=candidate.authoring();var split=SurfaceWarpLayerPlan.splitCharts(old.geometry);
        var initial=SurfaceWarpAuthoring.prepare(split,old.request,old.grid,old.maximumErrorPixels);
        var surface=derived?SurfaceWarpAuthoring.derivedImages(initial):initial;
        long slots=surface.images.inputs.stream().mapToLong(m->m.defaultLocal().size()).sum();
        var yaw=surface.yawKeys;var pitch=surface.pitchKeys;
        while(slots*yaw.size()*pitch.size()>MAX_REPEATED_LAYER_SLOTS) {
            if(yaw.size()<=3 || pitch.size()<=3)throw new IllegalArgumentException("layer storage exceeds native serialization budget");
            yaw=coarsen(yaw);pitch=coarsen(pitch);
        }
        return new Prepared(surface,SurfaceWarpLayerPlan.orders(surface.geometry,surface.request.camera(),yaw,pitch),yaw,pitch);
    }
    private static List<Float> coarsen(List<Float> keys) {
        var result=new ArrayList<Float>();for(int i=0;i<keys.size();i+=2)result.add(keys.get(i));
        if(!result.get(result.size()-1).equals(keys.get(keys.size()-1)))result.add(keys.get(keys.size()-1));
        if(!result.contains(0f))result.add(0f);result.sort(Float::compare);
        return List.copyOf(result);
    }
    static Prepared prepare(SurfaceWarpImportCandidate.Prepared candidate) throws java.io.IOException {
        var old=candidate.authoring();var split=SurfaceWarpLayerPlan.splitCharts(old.geometry);
        var prepared=SurfaceWarpAuthoring.prepare(split,old.request,old.grid,old.maximumErrorPixels);
        return new Prepared(prepared,SurfaceWarpLayerPlan.orders(split,old.request.camera(),old.yawKeys,old.pitchKeys));
    }
    static AuthoringTransactionResult<SurfaceWarpAuthoring.Created> apply(Prepared prepared,Supplier<CubismModel> current,
            AuthoringTransactionService transactions,SurfaceWarpAuthoring.Observer observer) {
        return transactions.execute(AuthoringTransactionOptions.of("Import layered 3D "+prepared.surface().request.name()),
            ()->assemble(current.get(),prepared,observer));
    }
    static SurfaceWarpAuthoring.Created assemble(CubismModel model,Prepared prepared,SurfaceWarpAuthoring.Observer observer) {
        var p=prepared.surface();var created=SurfaceWarpAuthoring.assemble(model,p,observer);
        int defaultRow=prepared.layerYawKeys().indexOf(0f)*prepared.layerPitchKeys().size()+prepared.layerPitchKeys().indexOf(0f);
        if(prepared.orders().jointKeys().size()!=prepared.layerYawKeys().size()*prepared.layerPitchKeys().size()
            || prepared.orders().jointKeys().stream().anyMatch(row->row.size()!=created.meshIds().size()))
            throw new IllegalArgumentException("complete joint order bank required");
        var snapshots=new ArrayList<ArtMeshAuthoringSnapshot>();
        for(int mesh=0;mesh<created.meshIds().size();mesh++) {
            var id=created.meshIds().get(mesh);var target=model.drawables().find(id);var before=created.sourceSnapshots().get(mesh);
            int neutral=prepared.orders().jointKeys().get(defaultRow).get(mesh);
            target.setDrawOrder(neutral);
            var neutralSnapshot=new ArtMeshAuthoringSnapshot(before.artMeshId(),before.sourceGuid(),before.sourceGeometry(),before.axes(),before.cells(),
                before.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(f.formGuid(),f.positions(),neutral)).toList(),before.morphKeys());
            require(neutralSnapshot.equals(target.authoringSnapshot()),"neutral order changed source geometry/default local");
            for(int axis=0;axis<2;axis++) {
                var parameter=axis==0?p.request.yaw():p.request.pitch();var keys=axis==0?prepared.layerYawKeys():prepared.layerPitchKeys();
                var points=new ArrayList<ParameterBindingPoint>();for(int key=0;key<keys.size();key++)
                    points.add(new ParameterBindingPoint(new ParameterBindingPointId("LAYER_"+mesh+"_"+axis+"_"+key),keys.get(key)));
                model.parameterBindings(parameter).bind(ParameterBindingTarget.artMesh(id),points);
            }
            var bound=target.authoringSnapshot();
            require(bound.axes().size()==2 && bound.morphKeys().isEmpty() && bound.cells().size()==prepared.layerYawKeys().size()*prepared.layerPitchKeys().size()
                && bound.forms().size()==bound.cells().size(),"complete ordinary layer grid required");
            var axes=new HashMap<ParameterId,ArtMeshAuthoringSnapshot.Axis>();for(var a:bound.axes())require(axes.put(a.parameterId(),a)==null,"duplicate layer axis");
            require(axes.keySet().equals(Set.of(p.request.yaw(),p.request.pitch())) && axes.get(p.request.yaw()).keys().equals(prepared.layerYawKeys())
                && axes.get(p.request.pitch()).keys().equals(prepared.layerPitchKeys()),"layer parameter keys differ");
            var orders=new HashMap<String,Integer>();var poses=new HashSet<List<Float>>();var edits=new ArrayList<ArtMeshKeyformDrawOrder>();
            for(var cell:bound.cells()) {
                var coords=new HashMap<String,Float>();for(var c:cell.coordinates())require(coords.put(c.parameterGuid(),c.value())==null,"duplicate layer coordinate");
                require(coords.size()==2,"complete layer tuple required");
                var y=coords.get(axes.get(p.request.yaw()).parameterGuid());var pitch=coords.get(axes.get(p.request.pitch()).parameterGuid());
                int yi=prepared.layerYawKeys().indexOf(y),pi=prepared.layerPitchKeys().indexOf(pitch);require(yi>=0 && pi>=0 && poses.add(List.of(y,pitch)),"missing/duplicate layer pose");
                int order=prepared.orders().jointKeys().get(yi*prepared.layerPitchKeys().size()+pi).get(mesh);
                require(orders.put(cell.formGuid(),order)==null,"shared layer form");
                // The native binding factory may replace the unbound form GUID.
                // Freeze the complete new grid; the default tuple is protected from batch writes.
                if(y!=0f || pitch!=0f)edits.add(new ArtMeshKeyformDrawOrder(cell.coordinates(),cell.formGuid(),order));
            }
            var common=new ArtMeshAuthoringSnapshot(before.artMeshId(),before.sourceGuid(),before.sourceGeometry(),bound.axes(),bound.cells(),
                bound.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(f.formGuid(),before.forms().get(0).positions(),neutral)).toList(),List.of());
            require(common.equals(bound),"layer binding changed source/local positions/owner");
            target.setOrdinaryKeyformDrawOrders(bound,edits);
            var expected=new ArtMeshAuthoringSnapshot(bound.artMeshId(),bound.sourceGuid(),bound.sourceGeometry(),bound.axes(),bound.cells(),
                bound.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(f.formGuid(),f.positions(),Objects.requireNonNull(orders.get(f.formGuid())))).toList(),bound.morphKeys());
            require(expected.equals(target.authoringSnapshot()),"layer order readback changed protected source/positions/forms");snapshots.add(expected);
        }
        var ordered=new SurfaceWarpAuthoring.Created(created.partId(),created.warpId(),created.meshIds(),snapshots,created.imageBindings(),
            created.maximumParentLocalDrift(),created.parentRoundTripErrorBoundPixels());
        SurfaceWarpAuthoring.verifyInputs(model,ordered,p.images.inputs,Optional.of(created.warpId()));
        SurfaceWarpAuthoring.verifyImageInputs(model,ordered);return ordered;
    }
    private static void require(boolean value,String why){if(!value)throw new IllegalStateException(why);}
}
