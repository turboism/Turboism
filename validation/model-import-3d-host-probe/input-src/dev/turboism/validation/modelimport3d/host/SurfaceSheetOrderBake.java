package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.id.ParameterId;
import dev.turboism.sdk.cubism.model.*;
import java.util.*;

/** Ordinary keyform order writes; caller owns the import transaction and rollback. */
final class SurfaceSheetOrderBake {
    private SurfaceSheetOrderBake() {}
    static void write(Drawable mesh,ParallelSheetOrderPlan.Plan plan,int surface,ParameterId yaw) {
        var before=mesh.authoringSnapshot();
        var axis=before.axes().stream().filter(a->a.parameterId().equals(yaw)).findFirst().orElseThrow();
        var orders=new HashMap<String,Integer>();
        for(var cell:before.cells()) {
            float value=cell.coordinates().stream().filter(c->c.parameterGuid().equals(axis.parameterGuid())).findFirst().orElseThrow().value();
            int order=plan.drawOrder(surface,value);
            if(orders.putIfAbsent(cell.formGuid(),order)!=null)throw new IllegalArgumentException("sheet form alias");
        }
        if(before.forms().stream().anyMatch(f->f.drawOrder()!=500))throw new IllegalStateException("sheet neutral order must be 500");
        var expected=new ArtMeshAuthoringSnapshot(before.artMeshId(),before.sourceGuid(),before.sourceGeometry(),
            before.axes(),before.cells(),before.forms().stream().map(f->new ArtMeshAuthoringSnapshot.Form(
                f.formGuid(),f.positions(),orders.get(f.formGuid()))).toList(),before.morphKeys());
        for(var cell:before.cells()) {
            int order=orders.get(cell.formGuid());
            if(order!=500)mesh.ordinaryKeyform(cell.coordinates()).setDrawOrder(order);
        }
        if(!mesh.authoringSnapshot().equals(expected))throw new IllegalStateException("sheet order readback/protected state differs");
    }
}
