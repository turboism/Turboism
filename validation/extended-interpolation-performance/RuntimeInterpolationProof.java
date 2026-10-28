package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.ACForm;
import com.live2d.cubism.doc.model.ACParameterControllableSource;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.cubism.doc.model.deformer.warp.*;
import com.live2d.cubism.doc.model.deformer.rotation.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.CParameterGuid;
import com.live2d.doc.CoordType;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.lang.management.ManagementFactory;
import java.util.*;

/** Real official forms/calculators, independently generated paired banks.
 * No model/installation files or Editor session are modified. */
public final class RuntimeInterpolationProof {
    record Bank(CExtendedInterpolationExtension extension, List<ACForm> all,
                List<ACForm> sources, List<CExtendedInterpolationExtension.b> keys) {}
    static volatile float sink;
    static int cases;
    public static void main(String[] args) {
        try { run(args); System.exit(0); }
        catch (Throwable failure) { failure.printStackTrace(); System.exit(1); }
    }
    static float[] xy(ACForm form) {
        if (form instanceof CRotationDeformerForm r) return new float[]{r.getOriginX(),r.getOriginY()};
        return form instanceof CArtMeshForm mesh ? mesh.getPositions() : ((CWarpDeformerForm) form).getPositions();
    }
    static Bank bank(int kind, int anchors, int inserted, int vertices, int seed, boolean degenerate) {
        var random = new Random(seed);
        ACParameterControllableSource owner = kind == 2 ? new CRotationDeformerSource()
                : kind == 1 ? new CWarpDeformerSource() : new CArtMeshSource();
        var extension = new CExtendedInterpolationExtension(owner);
        var all = new ArrayList<ACForm>(); var source = new ArrayList<ACForm>();
        var keys = new ArrayList<CExtendedInterpolationExtension.b>();
        var guid = new CParameterGuid(new UUID(0, 1));
        float[] knots = new float[anchors];
        for (int i = 0; i < anchors; i++) knots[i] = degenerate ? 0 : -60f + i * 120f / (anchors-1) + (i == 0 || i == anchors-1 ? 0 : random.nextFloat()*3f);
        int stride = inserted + 1, count = (anchors-1)*stride+1;
        for (int i = 0; i < count; i++) {
            boolean extra = i % stride != 0;
            int interval = i / stride; float fraction = (i % stride)/(float)stride;
            float value = extra ? knots[interval] + (knots[interval+1]-knots[interval])*fraction : knots[interval];
            float[] positions = new float[vertices*2];
            for (int p = 0; p < positions.length; p++) positions[p] = seed == 0 ? (p%2 == 0 ? -0f : 0f) : random.nextFloat()*100f-50f;
            ACForm form;
            if (kind == 2) {
                var f = new CRotationDeformerForm((CRotationDeformerSource)owner, null, new CoordType("DeformerLocal"));
                f.setOriginX(positions[0]); f.setOriginY(positions[1]);
                f.setAngle(random.nextFloat()*360f-180f); f.setScale(random.nextFloat()*2f);
                f.setReflectX(random.nextBoolean()); f.setReflectY(random.nextBoolean());
                f.setOpacity(random.nextFloat()); form = f;
            } else if (kind == 1) {
                var f = new CWarpDeformerForm((CWarpDeformerSource)owner, null, new CoordType("DeformerLocal"));
                f.setPositions(positions); f.setOpacity(random.nextFloat()); form = f;
            } else form = new CArtMeshForm((CArtMeshSource)owner, random.nextInt(1000), random.nextFloat(), positions, new CoordType("DeformerLocal"));
            try {
                for (String setter : List.of("setMultiplyColor", "setScreenColor"))
                    form.getClass().getMethod(setter, com.live2d.type.CFloatColor.class).invoke(form,
                        new com.live2d.type.CFloatColor(random.nextFloat(), random.nextFloat(), random.nextFloat(), random.nextFloat()));
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
            all.add(form); if (!extra) source.add(form);
            keys.add(new CExtendedInterpolationExtension.b(guid, value, extra, interval, fraction));
        }
        return new Bank(extension, all, source, keys);
    }
    static void changeX(ACForm form,float value) {
        if (form instanceof CRotationDeformerForm r) r.setOriginX(value);
        else xy(form)[0]=value;
    }
    static int[] metadata(ACForm form) {
        try {
            var values = new ArrayList<Integer>();
            for (String method : List.of("getOpacity", "getDrawOrder", "getAngle", "getScale")) {
                try {
                    Object v = form.getClass().getMethod(method).invoke(form);
                    values.add(v instanceof Float f ? Float.floatToRawIntBits(f) : ((Number)v).intValue());
                } catch (NoSuchMethodException absent) { /* form-specific property */ }
            }
            for (String method : List.of("getMultiplyColor", "getScreenColor")) {
                Object color = form.getClass().getMethod(method).invoke(form);
                for (String channel : List.of("getA", "getR", "getG", "getB"))
                    values.add(Float.floatToRawIntBits(((Number)color.getClass().getMethod(channel).invoke(color)).floatValue()));
            }
            if (form instanceof CRotationDeformerForm r) {
                values.add(r.isReflectX()?1:0);values.add(r.isReflectY()?1:0);
            }
            return values.stream().mapToInt(Integer::intValue).toArray();
        } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
    }
    static void compare(Bank official, Bank optimized, int[] beforeA, int[] beforeB, String label) {
        for (int i = 0; i < official.all.size(); i++) {
            ACForm a = official.all.get(i), b = optimized.all.get(i);
            if (a.getEditVersion()-beforeA[i] != b.getEditVersion()-beforeB[i]) throw new AssertionError(label+" version form="+i);
            if (!Arrays.equals(metadata(a), metadata(b))) throw new AssertionError(label+" metadata form="+i);
            float[] aa = xy(a), bb = xy(b);
            for (int p = 0; p < aa.length; p++)
                if (Float.floatToRawIntBits(aa[p]) != Float.floatToRawIntBits(bb[p])) throw new AssertionError(label+" bits form="+i+" scalar="+p+" official="+aa[p]+" optimized="+bb[p]);
        }
    }
    static int[] versions(Bank bank) { return bank.all.stream().mapToInt(ACForm::getEditVersion).toArray(); }
    static void differential(int kind, ExtendedInterpolationType type, int anchors, int inserted, int seed, float scale, boolean degenerate, boolean coefficients) {
        System.setProperty("turboism.meshInterpolation.coefficients", Boolean.toString(coefficients));
        Bank official = bank(kind,anchors,inserted,16,seed,degenerate), optimized = bank(kind,anchors,inserted,16,seed,degenerate);
        String label = "kind="+kind+" type="+type+" anchors="+anchors+" insert="+inserted+" seed="+seed+" scale="+scale+" degenerate="+degenerate+" coefficients="+coefficients;
        for (int round = 0; round < 4; round++) {
            if (seed != 0) for (Bank bank : List.of(official, optimized)) {
                ACForm changed = bank.sources.get(round%anchors);
                changeX(changed, xy(changed)[0] + .125f);
                bank.sources.get(round%anchors).setUpdated();
            }
            int[] a = versions(official), b = versions(optimized);
            NativeMeshInterpolationBridge.enabled(false);
            official.extension.updateInterpolatedForms_common(official.all, official.sources, official.keys, type, scale);
            NativeMeshInterpolationBridge.enabled(true);
            if (Boolean.getBoolean("extended.proof.installed")) {
                long accepted = NativeMeshInterpolationBridge.acceptedCalls(), refused = NativeMeshInterpolationBridge.refusedCalls();
                boolean nonFiniteTarget=optimized.all.stream().anyMatch(f -> {for(float coordinate:xy(f))if(!Float.isFinite(coordinate))return true;return false;});
                optimized.extension.updateInterpolatedForms_common(optimized.all, optimized.sources, optimized.keys, type, scale);
                if (nonFiniteTarget) {
                    if(NativeMeshInterpolationBridge.acceptedCalls()!=accepted || NativeMeshInterpolationBridge.refusedCalls()!=refused+1)
                        throw new AssertionError("non-finite target did not decline before native fallback "+label);
                } else if (NativeMeshInterpolationBridge.acceptedCalls() != accepted + 1) throw new AssertionError("hook miss "+label);
            } else if (!NativeMeshInterpolationBridge.tryUpdate(optimized.extension, optimized.all, optimized.sources, optimized.keys, type, scale)) {
                boolean nonFiniteTarget=optimized.all.stream().anyMatch(f -> {for(float coordinate:xy(f))if(!Float.isFinite(coordinate))return true;return false;});
                if(!nonFiniteTarget)throw new AssertionError("declined "+label);
                NativeMeshInterpolationBridge.enabled(false);
                optimized.extension.updateInterpolatedForms_common(optimized.all,optimized.sources,optimized.keys,type,scale);
                NativeMeshInterpolationBridge.enabled(true);
            }
            compare(official, optimized, a, b, label);
        }
        cases++;
    }
    static void refusal(Bank bank, ExtendedInterpolationType type, float scale) {
        int[] before = versions(bank);
        float[][] copy = bank.all.stream().map(f -> xy(f).clone()).toArray(float[][]::new);
        int[][] metadata = bank.all.stream().map(RuntimeInterpolationProof::metadata).toArray(int[][]::new);
        if (NativeMeshInterpolationBridge.tryUpdate(bank.extension, bank.all, bank.sources, bank.keys, type, scale)) throw new AssertionError("expected refusal");
        if (!Arrays.equals(before,versions(bank))) throw new AssertionError("refusal version mutation");
        for (int i=0;i<copy.length;i++) {
            if (!Arrays.equals(copy[i],xy(bank.all.get(i))) || !Arrays.equals(metadata[i],metadata(bank.all.get(i)))) throw new AssertionError("refusal mutation");
        }
        cases++;
    }
    static void run(String[] args) throws Exception {
        NativeMeshInterpolationBridge.enabled(true);
        if (args.length > 0 && args[0].equals("structure-benchmark")) { StructureRuntimeProof.benchmark(); return; }
        if (args.length > 0 && args[0].equals("benchmark")) { benchmark(args.length > 1 && args[1].equals("official")); return; }
        for (int kind : new int[]{0,1,2}) for (var type : new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE}) {
            for (int anchors : new int[]{3,5}) for (int inserted : new int[]{1,4,16,64}) for (int seed : new int[]{0,27})
                differential(kind,type,anchors,inserted,seed,1f,false,true);
            for (float scale : new float[]{.5f,-1f,0f}) differential(kind,type,3,4,27,scale,false,true);
            differential(kind,type,3,4,27,1f,true,true);
            differential(kind,type,3,4,27,1f,false,false);
            Bank bank=bank(kind,3,4,16,27,false);
            refusal(bank,ExtendedInterpolationType.LINEAR,1f);
            refusal(bank,type,Float.NaN);
            changeX(bank.sources.get(0),Float.POSITIVE_INFINITY);
            refusal(bank,type,1f);
            bank=bank(kind,3,4,16,27,false);changeX(bank.all.get(1),Float.intBitsToFloat(0x7fc00012));refusal(bank,type,1f);
            bank=bank(kind,3,4,16,27,false);bank.all.set(1,bank.all.get(2));refusal(bank,type,1f);
            bank=bank(kind,3,4,16,27,false);bank.sources.set(0,bank.all.get(1));refusal(bank,type,1f);
            Bank foreign=bank(kind,3,4,16,28,false);bank=bank(kind,3,4,16,27,false);bank.sources.set(0,foreign.sources.get(0));refusal(bank,type,1f);
        }
        if (Boolean.getBoolean("extended.proof.installed")) {
            try {
                Class<?> agent = Class.forName("dev.turboism.diagnostic.extended.InterpolationProofAgent");
                agent.getMethod("verifyPatched").invoke(null);
                inspectionProof();
                StructureRuntimeProof.verify();
                agent.getMethod("close").invoke(null);
                Bank afterClose=bank(1,3,4,16,27,false);
                long accepted=NativeMeshInterpolationBridge.acceptedCalls();
                afterClose.extension.updateInterpolatedForms_common(afterClose.all,afterClose.sources,afterClose.keys,ExtendedInterpolationType.SNS_CURVE,1f);
                if(NativeMeshInterpolationBridge.acceptedCalls()!=accepted)throw new AssertionError("close left bridge enabled");
            } catch (ReflectiveOperationException e) { throw new AssertionError(e); }
        }
        NativeMeshInterpolationBridge.enabled(false);
        refusal(bank(0,3,4,16,27,false),ExtendedInterpolationType.ELLIPSE,1f);
        System.out.println("PASS cases="+cases+" changedSourceCoordinateBitsMetadataAndVersionDeltas=true refusalBeforeMutation=true accepted="+NativeMeshInterpolationBridge.acceptedCalls()+" refused="+NativeMeshInterpolationBridge.refusedCalls());
    }
    static void inspectionProof() throws ReflectiveOperationException {
        Bank bank=bank(0,3,4,16,27,false);
        var field=CExtendedInterpolationExtension.class.getDeclaredField("isExtendedInterpolationEnabled");
        field.setAccessible(true);field.setBoolean(bank.extension,true);
        for(boolean morphOnly:new boolean[]{false,true}) {
            bank.extension.getNormalExtendedKeyforms().clear();bank.extension.getMorphTargetExtendedKeyforms().clear();
            var forms=morphOnly?bank.extension.getMorphTargetExtendedKeyforms():bank.extension.getNormalExtendedKeyforms();
            // Isolate the precheck: repeated valid form references measure list-copy
            // overhead only. The detached owner has no bindings, so it returns false.
            for(int i=0;i<10000;i++)forms.add(bank.all.get(0));
            for(boolean enabled:new boolean[]{false,true}) {
                NativeMeshInterpolationBridge.enabled(enabled);
                for(int i=0;i<100;i++)if(bank.extension.updateInterpolatedForms(false))throw new AssertionError("unexpected update");
                var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();
                long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId());
                for(int i=0;i<100;i++)if(bank.extension.updateInterpolatedForms(false))throw new AssertionError("unexpected update");
                bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-bytes;
                System.out.println("INSPECTION morphOnly="+morphOnly+" enabled="+enabled+" bytesPerCall="+bytes/100);
                if(enabled && bytes/100>1000)throw new AssertionError("first inspection still copies full bank");
            }
        }
        NativeMeshInterpolationBridge.enabled(true);
        cases++;
    }
    static void benchmark(boolean officialOnly) {
        var bean=(com.sun.management.ThreadMXBean)ManagementFactory.getThreadMXBean();bean.setThreadAllocatedMemoryEnabled(true);
        System.setProperty("turboism.meshInterpolation.coefficients","true");
        System.out.println("workload,kind,type,mode,round,nsPerCall,bytesPerCall");
        String workload=System.getenv().getOrDefault("EXTENDED_PROOF_WORKLOAD","repeat");
        if(!List.of("repeat","dirty","mixed").contains(workload))throw new IllegalArgumentException(workload);
        for (int kind:new int[]{0,1,2}) for (var type:new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE}) {
            Bank bank=bank(kind,3,64,256,27,false);
            for (String mode:officialOnly?List.of("official"):List.of("official","uncached","prior","optimized")) {
                // In installed mode the official method is transformed too. Disable
                // the bridge for the oracle rather than timing our own hook twice.
                NativeMeshInterpolationBridge.enabled(!mode.equals("official"));
                System.setProperty("turboism.meshInterpolation.curveCache", Boolean.toString(!mode.equals("uncached")));
                System.setProperty("turboism.meshInterpolation.localRefresh",Boolean.toString(mode.equals("optimized")));
                Runnable action=(mode.equals("official") || Boolean.getBoolean("extended.proof.installed"))?()->bank.extension.updateInterpolatedForms_common(bank.all,bank.sources,bank.keys,type,1f):()->{
                    if(!NativeMeshInterpolationBridge.tryUpdate(bank.extension,bank.all,bank.sources,bank.keys,type,1f))throw new AssertionError("benchmark declined");
                };
                int[] calls={0};
                Runnable measured=()->{
                    int call=calls[0]++;
                    if(workload.equals("dirty") || (workload.equals("mixed") && call%4==0)) {
                        ACForm changed=bank.sources.get(call%bank.sources.size());
                        changeX(changed,xy(changed)[0]+.125f);changed.setUpdated();
                    }
                    action.run();
                };
                for(int i=0;i<300;i++)measured.run();
                for(int round=0;round<5;round++) {
                    long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
                    for(int i=0;i<100;i++)measured.run();
                    long elapsed=System.nanoTime()-start;bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-bytes;
                    sink=xy(bank.all.get(1))[0];System.out.printf(Locale.ROOT,"%s,%s,%s,%s,%d,%.1f,%.1f%n",workload,kind,type.name(),mode,round,elapsed/100d,bytes/100d);
                }
            }
        }
    }
}
