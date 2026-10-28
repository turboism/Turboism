package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.util.*;

/** Exact installed-path replay: native snapshots are captured before enabling the candidate. */
public final class CurveCacheRuntimeProof {
    record Snapshot(int[][] coordinates, int[][] metadata, int[] versions) {}
    static int checks;
    public static void main(String[] args) {
        try { verify(); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static Snapshot snapshot(RuntimeInterpolationProof.Bank bank) {
        return new Snapshot(bank.all().stream().map(f -> bits(RuntimeInterpolationProof.xy(f))).toArray(int[][]::new),
                bank.all().stream().map(RuntimeInterpolationProof::metadata).toArray(int[][]::new),RuntimeInterpolationProof.versions(bank));
    }
    static int[] bits(float[] values) {
        int[] result=new int[values.length];
        for(int i=0;i<values.length;i++)result[i]=Float.floatToRawIntBits(values[i]);
        return result;
    }
    static List<CExtendedInterpolationExtension.b> keys(RuntimeInterpolationProof.Bank bank,int step) {
        if(step!=6 && step!=7)return bank.keys();
        var out=new ArrayList<CExtendedInterpolationExtension.b>();
        for(var key:bank.keys())out.add(new CExtendedInterpolationExtension.b(
                new com.live2d.type.CParameterGuid(new UUID(0,1)),key.a(),key.b(),key.c(),key.b()?key.d()*.9f:key.d()));
        return out;
    }
    static void edit(RuntimeInterpolationProof.Bank bank,int step) throws Exception {
        if(step==2)bank.sources().get(0).getClass().getMethod("setOpacity",float.class).invoke(bank.sources().get(0),.123f);
        if(step==3)bank.sources().get(0).setUpdated(); // version-only change must not skip notifications
        if(step==4)RuntimeInterpolationProof.changeX(bank.sources().get(0),RuntimeInterpolationProof.xy(bank.sources().get(0))[0]+.125f); // unversioned source write
        if(step==8)RuntimeInterpolationProof.changeX(bank.sources().get(0),-0f);
        if(step==9)RuntimeInterpolationProof.changeX(bank.sources().get(0),0f);
        if(step==12) {
            bank.all().get(1).getClass().getMethod("setOpacity",float.class).invoke(bank.all().get(1),.9f);
            RuntimeInterpolationProof.changeX(bank.all().get(1),Float.NaN); // old target must still go through native blend/write path
        }
    }
    static ExtendedInterpolationType curve(ExtendedInterpolationType initial,int step) {
        return step>=14 ? (initial==ExtendedInterpolationType.ELLIPSE?ExtendedInterpolationType.SNS_CURVE:ExtendedInterpolationType.ELLIPSE):initial;
    }
    static float scale(int step) {return step==10||step==11?-.5f:step==16?0f:1f;}
    static void check(Snapshot expected,RuntimeInterpolationProof.Bank actual,String label) {
        var got=snapshot(actual);
        if(!Arrays.deepEquals(expected.coordinates,got.coordinates)||!Arrays.deepEquals(expected.metadata,got.metadata)
                ||!Arrays.equals(expected.versions,got.versions)) {
            for(int i=0;i<got.coordinates.length;i++)for(int j=0;j<got.coordinates[i].length;j++)
                if(expected.coordinates[i][j]!=got.coordinates[i][j])System.err.println("MISMATCH form="+i+" coordinate="+j+" native="+Integer.toHexString(expected.coordinates[i][j])+" opt="+Integer.toHexString(got.coordinates[i][j]));
            if(!Arrays.deepEquals(expected.metadata,got.metadata))System.err.println("METADATA MISMATCH");
            if(!Arrays.equals(expected.versions,got.versions))System.err.println("VERSION MISMATCH native="+Arrays.toString(expected.versions)+" opt="+Arrays.toString(got.versions));
            throw new AssertionError("Replay mismatch "+label+" stats="+NativeMeshInterpolationBridge.curveCacheStatistics());
        }
        checks++;
    }
    static List<java.lang.ref.WeakReference<?>> warmAndDrop() {
        NativeMeshInterpolationBridge.enabled(false);
        NativeMeshInterpolationBridge.enabled(true);
        var bank=RuntimeInterpolationProof.bank(1,3,4,16,27,false);
        long hits=NativeMeshInterpolationBridge.curveCacheStatistics().hits();
        for(int repeat=0;repeat<3;repeat++)bank.extension().updateInterpolatedForms_common(bank.all(),bank.sources(),bank.keys(),ExtendedInterpolationType.SNS_CURVE,1f);
        if(NativeMeshInterpolationBridge.curveCacheStatistics().hits()<=hits)throw new AssertionError("Ownership proof did not retain reusable result");
        List<java.lang.ref.WeakReference<?>> refs=new ArrayList<>();
        refs.add(new java.lang.ref.WeakReference<>(bank.extension()));
        refs.add(new java.lang.ref.WeakReference<>(bank.extension().getOwner()));
        for(var form:bank.all())refs.add(new java.lang.ref.WeakReference<>(form));
        return refs;
    }
    static void verify() throws Exception {
        System.setProperty("turboism.meshInterpolation.localRefresh","false");
        int cases=0;
        for(int kind:new int[]{0,1,2})for(var initial:List.of(ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE))
            for(boolean coefficients:new boolean[]{false,true})for(int seed:new int[]{0,27}) {
                NativeMeshInterpolationBridge.enabled(false);
                var nativeBank=RuntimeInterpolationProof.bank(kind,3,4,16,seed,false);
                var optimized=RuntimeInterpolationProof.bank(kind,3,4,16,seed,false);
                List<Snapshot> expected=new ArrayList<>();
                for(int step=0;step<18;step++) {
                    edit(nativeBank,step);
                    System.setProperty("turboism.meshInterpolation.coefficients",Boolean.toString(step==13?!coefficients:coefficients));
                    nativeBank.extension().updateInterpolatedForms_common(nativeBank.all(),nativeBank.sources(),keys(nativeBank,step),curve(initial,step),scale(step));
                    expected.add(snapshot(nativeBank));
                }
                NativeMeshInterpolationBridge.enabled(true);
                long hits=NativeMeshInterpolationBridge.curveCacheStatistics().hits();
                for(int step=0;step<18;step++) {
                    edit(optimized,step);
                    System.setProperty("turboism.meshInterpolation.coefficients",Boolean.toString(step==13?!coefficients:coefficients));
                    optimized.extension().updateInterpolatedForms_common(optimized.all(),optimized.sources(),keys(optimized,step),curve(initial,step),scale(step));
                    check(expected.get(step),optimized,"kind="+kind+" curve="+initial+" coefficients="+coefficients+" seed="+seed+" step="+step);
                }
                if(kind!=2 && NativeMeshInterpolationBridge.curveCacheStatistics().hits()<=hits)throw new AssertionError("No curve cache hit in replay");
                var stats=NativeMeshInterpolationBridge.curveCacheStatistics();
                if(stats.bytes()>stats.byteBudget())throw new AssertionError("Numerical cache exceeded budget");
                cases++;
            }
        List<java.lang.ref.WeakReference<?>> dropped=warmAndDrop();
        if(NativeMeshInterpolationBridge.curveCacheStatistics().entries()==0)throw new AssertionError("No retained numerical entry for ownership proof");
        for(int attempt=0;attempt<20 && dropped.stream().anyMatch(r -> r.get()!=null);attempt++) {System.gc();Thread.sleep(50);}
        if(dropped.stream().anyMatch(r -> r.get()!=null))throw new AssertionError("Numerical cache retained a host model/form");
        System.out.println("CURVE_CACHE_HOST_REFERENCES_COLLECTED count="+dropped.size());
        if(Boolean.getBoolean("extended.proof.installed"))InterpolationProofAgent.verifyPatched();
        NativeMeshInterpolationBridge.enabled(false);
        if(NativeMeshInterpolationBridge.curveCacheStatistics().bytes()!=0)throw new AssertionError("Disable retained numerical cache");
        System.out.println("CURVE_CACHE PASS cases="+cases+" snapshots="+checks+" coordinateBitsMetadataAbsoluteVersions=true stats="+NativeMeshInterpolationBridge.curveCacheStatistics());
    }
}
