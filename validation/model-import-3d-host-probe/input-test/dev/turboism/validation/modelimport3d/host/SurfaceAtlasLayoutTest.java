package dev.turboism.validation.modelimport3d.host;

import dev.turboism.sdk.cubism.textureatlas.*;
import java.util.List;
import java.util.Optional;

/** Offline layout invariants only; native UV/raster/Undo checks belong to the host probe. */
public final class SurfaceAtlasLayoutTest {
    public static void main(String[] args) {
        var target = new TextureAtlasLayoutTarget() { };
        var constraints = new TextureAtlasLayoutConstraints(512,512,4,6,2,false,false);
        var image = new TextureAtlasLayoutItem("cube-material",256,128);
        var snapshot = new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",constraints,List.of(image),
            new TextureAtlasLayoutPlan(512,512,1,List.of()));
        var first = SurfaceAtlasLayout.plan(snapshot,false);
        var second = SurfaceAtlasLayout.plan(snapshot,true);
        check(first.placements().get(0).x()==4 && first.placements().get(0).y()==4,"first inset");
        check(second.placements().get(0).x()==252 && second.placements().get(0).y()==380,"reflected inset");
        check(!first.equals(second),"real relocation required");
        var many = new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",constraints,
            List.of(image,new TextureAtlasLayoutItem("other",256,380)),snapshot.currentPlan());
        var plan = SurfaceAtlasLayout.plan(many,false);
        check(plan.pageCount()==2 && plan.placements().size()==2,"all images retained across pages");
        var reordered = new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",constraints,
            List.of(many.items().get(1),image),snapshot.currentPlan());
        check(plan.equals(SurfaceAtlasLayout.plan(reordered,false)),"input order must not alter layout");
        int[] writes={0};
        var service = new TextureAtlasLayoutService() {
            public Optional<TextureAtlasLayoutSnapshot> current() { return Optional.of(snapshot); }
            public TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget actual,TextureAtlasLayoutPlan value) {
                check(actual==target && value.equals(first),"use issued target and complete plan");
                writes[0]++;return TextureAtlasLayoutApplyResult.applied();
            }
        };
        check(SurfaceAtlasLayout.apply(service,false).failureCode().isEmpty() && writes[0]==1,"one apply");
        var huge = new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",constraints,
            List.of(new TextureAtlasLayoutItem("too-wide",Integer.MAX_VALUE,1)),snapshot.currentPlan());
        reject(() -> SurfaceAtlasLayout.plan(huge,false));
        var one = new TextureAtlasLayoutConstraints(512,512,4,6,1,false,false);
        reject(() -> SurfaceAtlasLayout.plan(new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",one,
            many.items(),snapshot.currentPlan()),false));
        reject(() -> SurfaceAtlasLayout.plan(new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",
            TextureAtlasLayoutConstraints.currentPage(512,512,4,true,1),List.of(image),snapshot.currentPlan()),false));
        // The padded source texture is 512x256. A 512px atlas cannot retain
        // its texels and the required margin, even if another page is allowed.
        var padded=new TextureAtlasLayoutItem("padded-source",512,256);
        var tooSmall=new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",constraints,
            List.of(padded),snapshot.currentPlan());
        int[] rejectedWrites={0};
        var rejectingService=new TextureAtlasLayoutService() {
            public Optional<TextureAtlasLayoutSnapshot> current() {return Optional.of(tooSmall);}
            public TextureAtlasLayoutApplyResult apply(TextureAtlasLayoutTarget t,TextureAtlasLayoutPlan p) {
                rejectedWrites[0]++;return TextureAtlasLayoutApplyResult.applied();
            }
        };
        reject(()->SurfaceAtlasLayout.apply(rejectingService,false));
        check(rejectedWrites[0]==0,"oversized padded image must not submit a partial layout");
        var paddedSnapshot=new TextureAtlasLayoutSnapshot(target,"doc","model","atlas",
            new TextureAtlasLayoutConstraints(1024,1024,4,6,2,false,false),
            List.of(padded,new TextureAtlasLayoutItem("second-source",512,256)),
            new TextureAtlasLayoutPlan(1024,1024,1,List.of()));
        for(boolean reflected:new boolean[]{false,true}) {
            var paddedPlan=SurfaceAtlasLayout.plan(paddedSnapshot,reflected);
            check(paddedPlan.pageCount()==1 && paddedPlan.placements().size()==2,"complete padded layout");
            var a=paddedPlan.placements().get(0);var b=paddedPlan.placements().get(1);
            check(a.y()+a.height()+6<=b.y() || b.y()+b.height()+6<=a.y(),"padded image gap");
            for(var placement:paddedPlan.placements()) {
                check(placement.width()==512 && placement.height()==256 && !placement.rotated(),"retain original texel size");
                check(placement.x()>=4 && placement.y()>=4 && placement.x()+512<=1020 && placement.y()+256<=1020,"padded page margin");
                for(double u:new double[]{0,.001,.375,.999,1})for(double v:new double[]{0,.001,.75,.999,1}) {
                    double registeredU=(256*u+1)/512,registeredV=(128*v+1)/256;
                    double atlasU=(placement.x()+512*registeredU)/1024;
                    double atlasV=(placement.y()+256*registeredV)/1024;
                    check(Math.abs((atlasU*1024-placement.x()-1)/256-u)<1e-14
                        && Math.abs((atlasV*1024-placement.y()-1)/128-v)<1e-14,"source UV survives atlas relocation");
                }
            }
        }
        System.out.println("SurfaceAtlasLayout PASS including padded 512x256 images; hostExecuted=false");
    }
    private static void check(boolean value,String message) { if(!value)throw new AssertionError(message); }
    private static void reject(Runnable action) {
        try { action.run(); } catch(IllegalArgumentException expected) { return; }
        throw new AssertionError("invalid plan accepted");
    }
}
