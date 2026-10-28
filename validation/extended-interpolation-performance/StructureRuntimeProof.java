package dev.turboism.diagnostic.extended;

import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.param.*;
import com.live2d.cubism.doc.model.id.*;
import com.live2d.cubism.doc.model.interpolator.extendedInterpolation.*;
import com.live2d.type.*;
import dev.turboism.adapter.cubism.interpolation.NativeFormLookupBridge;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.lang.reflect.*;
import java.util.*;

/** Real multi-axis native grid creation and rebuild; no Editor interaction claim. */
public final class StructureRuntimeProof {
    record Model(ACParameterControllableSource owner, CExtendedInterpolationExtension extension) {}

    @SuppressWarnings("unchecked")
    static Model create(int kind,int axes,ExtendedInterpolationType type,int inserted,boolean enabled) throws Exception {
        NativeMeshInterpolationBridge.enabled(enabled);
        var model = new CModelSource("interpolation-proof",false);
        var parameters=model.getParameterSourceSet();parameters.setup(model);
        var pairs=new ArrayList<kotlin.Pair<CParameterGuid,float[]>>();
        for(int axis=0;axis<axes;axis++) {
            var p=new CParameterSource();p.setId(new CParameterId("Proof"+axis));
            p.setGuid(new CParameterGuid(new UUID(1,axis)));p.setMinValue(-60);p.setMaxValue(60);p.setDefaultValue(0);p.setup(model);
            parameters.add(p,axis);pairs.add(new kotlin.Pair<>(p.getGuid(),new float[]{-60,0,60}));
        }
        parameters.updateMap();
        int count=(int)Math.pow(3,axes);
        var bank=RuntimeInterpolationProof.bank(kind,count,0,4,27,false);
        var owner=bank.extension().getOwner();owner.set_modelSource(model);
        var ordinary=(List<ACForm>)(List<?>)owner.getKeyforms();ordinary.clear();ordinary.addAll(bank.sources());
        var guids=new ArrayList<CFormGuid>();
        for(int i=0;i<count;i++) {ordinary.get(i).setGuid(new CFormGuid(new UUID(2,i)));guids.add(ordinary.get(i).getGuid());}
        var grid=owner.getKeyformGridSource();grid.setup(owner);
        grid.getClass().getMethod("import",CModelSource.class,List.class,List.class).invoke(grid,model,pairs,guids);
        for(var binding:grid.getKeyformBindings()) {binding.setExtendedInterpolationType(type);binding.setInsertPointCount(inserted);}
        owner.getExtensions().add(bank.extension());
        bank.extension().setupFormStructure(true,true);
        if(bank.extension().getNormalExtendedKeyforms().size()<=count)throw new AssertionError("extended structure absent");
        return new Model(owner,bank.extension());
    }

    static void compare(Model a,Model b,String label) {
        var aa=a.extension.getNormalExtendedKeyforms();var bb=b.extension.getNormalExtendedKeyforms();
        if(aa.size()!=bb.size())throw new AssertionError(label+" size");
        for(int i=0;i<aa.size();i++) {
            float[] x=RuntimeInterpolationProof.xy(aa.get(i)),y=RuntimeInterpolationProof.xy(bb.get(i));
            if(x.length!=y.length)throw new AssertionError(label+" point count");
            for(int p=0;p<x.length;p++)if(Float.floatToRawIntBits(x[p])!=Float.floatToRawIntBits(y[p]))
                throw new AssertionError(label+" coordinates form="+i+" scalar="+p);
            if(!Arrays.equals(RuntimeInterpolationProof.metadata(aa.get(i)),RuntimeInterpolationProof.metadata(bb.get(i))))
                throw new AssertionError(label+" metadata form="+i);
            if(aa.get(i).getEditVersion()!=bb.get(i).getEditVersion())throw new AssertionError(label+" edit version form="+i);
        }
        var ab=a.extension.getExtendedKeyformGridSource().getKeyformBindings();
        var bc=b.extension.getExtendedKeyformGridSource().getKeyformBindings();
        if(ab.size()!=bc.size())throw new AssertionError(label+" axis count");
        for(int i=0;i<ab.size();i++)if(!ab.get(i).getParameterGuid().equals(bc.get(i).getParameterGuid())||!ab.get(i).getKeys().equals(bc.get(i).getKeys()))
            throw new AssertionError(label+" axis keys");
    }

    public static void verify() throws Exception {
        long builds=NativeFormLookupBridge.indexBuilds(),lookups=NativeFormLookupBridge.indexLookups();
        int cases=0;
        for(int kind:new int[]{0,1,2})for(int axes:new int[]{1,2,3})for(var type:new ExtendedInterpolationType[]{ExtendedInterpolationType.ELLIPSE,ExtendedInterpolationType.SNS_CURVE}) {
            Model official=create(kind,axes,type,2,false),optimized=create(kind,axes,type,2,true);
            String label="kind="+kind+" axes="+axes+" type="+type;
            compare(official,optimized,label+" create");
            for(int round=0;round<3;round++) {
                for(Model model:List.of(official,optimized)) {
                    var form=model.owner.getKeyforms().get(round%model.owner.getKeyforms().size());
                    RuntimeInterpolationProof.changeX(form,RuntimeInterpolationProof.xy(form)[0]+.25f);form.setUpdated();
                }
                NativeMeshInterpolationBridge.enabled(false);if(!official.extension.updateInterpolatedForms(false))throw new AssertionError("official changed-source skip");
                NativeMeshInterpolationBridge.enabled(true);if(!optimized.extension.updateInterpolatedForms(false))throw new AssertionError("optimized changed-source skip");
                compare(official,optimized,label+" source edit");
            }
            if(optimized.extension.updateInterpolatedForms(false))throw new AssertionError("unchanged-source refresh");
            for(Model model:List.of(official,optimized)) {
                NativeMeshInterpolationBridge.enabled(model==optimized);
                for(var binding:model.owner.getKeyformGridSource().getKeyformBindings()) {binding.setInsertPointCount(3);binding.setExtendedInterpolationScale(.5f);}
                model.extension.setupFormStructure(true,true);
            }
            compare(official,optimized,label+" point count/scale rebuild");
            cases++;
        }
        exceptionCleanup();
        indexSemantics();
        if(NativeFormLookupBridge.indexBuilds()==builds||NativeFormLookupBridge.indexLookups()==lookups)throw new AssertionError("structure hook did not execute");
        System.out.println("STRUCTURE PASS cases="+cases+" axes=1/2/3 coordinateBitsMetadataEditVersions=true indexBuilds="+(NativeFormLookupBridge.indexBuilds()-builds)+" indexedLookups="+(NativeFormLookupBridge.indexLookups()-lookups));
    }

    @SuppressWarnings("unchecked")
    static void indexSemantics() throws Exception {
        var bank=RuntimeInterpolationProof.bank(0,3,4,4,27,false);
        var owner=bank.extension().getOwner();
        var ordinary=(List<ACForm>)(List<?>)owner.getKeyforms();ordinary.clear();ordinary.addAll(bank.sources());
        var extended=bank.extension().getNormalExtendedKeyforms();extended.addAll(bank.all());
        bank.all().get(1).setGuid(ordinary.get(0).getGuid());
        bank.all().get(2).setGuid(bank.all().get(3).getGuid());
        var find=CExtendedInterpolationExtension.class.getDeclaredMethod("setupFormStructure_Normal$local_findKeyform",
                ACParameterControllableSource.class,CExtendedInterpolationExtension.class,CFormGuid.class);
        find.setAccessible(true);
        var queries=new ArrayList<CFormGuid>();
        for(var form:bank.all())queries.add(form.getGuid());
        queries.add(new CFormGuid(new UUID(999,999)));
        NativeMeshInterpolationBridge.enabled(true);
        long builds=NativeFormLookupBridge.indexBuilds();
        Object token=NativeFormLookupBridge.enter();
        try {
            for(var guid:queries) {
                NativeMeshInterpolationBridge.enabled(false);Object expected=find.invoke(null,owner,bank.extension(),guid);
                NativeMeshInterpolationBridge.enabled(true);Object actual=find.invoke(null,owner,bank.extension(),guid);
                if(actual!=expected)throw new AssertionError("indexed identity differs from native first match");
            }
            if(NativeFormLookupBridge.indexBuilds()!=builds+1)throw new AssertionError("scope built more than one index");
        } finally {NativeFormLookupBridge.exit(token);}
        // A separate rebuild must observe the current banks, with no stale cache.
        extended.clear();
        token=NativeFormLookupBridge.enter();
        try {
            if(find.invoke(null,owner,bank.extension(),bank.all().get(3).getGuid())!=null)
                throw new AssertionError("prior rebuild retained extended forms");
        } finally {NativeFormLookupBridge.exit(token);}
        // Memory budget exhaustion must retain native lookup semantics without indexing.
        for(int i=0;i<65537;i++)extended.add(bank.all().get(3));
        builds=NativeFormLookupBridge.indexBuilds();
        token=NativeFormLookupBridge.enter();
        var beforeBudget=NativeFormLookupBridge.cacheStatistics();
        try {
            if(find.invoke(null,owner,bank.extension(),bank.all().get(3).getGuid())!=bank.all().get(3))
                throw new AssertionError("budget fallback lost native result");
            if(NativeFormLookupBridge.indexBuilds()!=builds)throw new AssertionError("index exceeded budget");
            if(find.invoke(null,owner,bank.extension(),bank.all().get(3).getGuid())!=bank.all().get(3))
                throw new AssertionError("repeat budget fallback lost result");
            var stats=NativeFormLookupBridge.cacheStatistics();
            if(stats.formBudget()!=65536 || stats.budgetFallbacks()!=beforeBudget.budgetFallbacks()+1 ||
               stats.largestObservedBank()<65537 || stats.activeIndexEntries()!=beforeBudget.activeIndexEntries())
                throw new AssertionError("budget diagnostics differ: "+stats);
        } finally {NativeFormLookupBridge.exit(token);}
        if(NativeFormLookupBridge.cacheStatistics().activeScopes()!=0 || NativeFormLookupBridge.cacheStatistics().activeIndexEntries()!=0)
            throw new AssertionError("lookup cache retained active entries after rebuild");
    }

    /** Times native structure construction only; excludes common interpolation evaluation. */
    public static void benchmark() throws Exception {
        var bean=(com.sun.management.ThreadMXBean)java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        var setup=CExtendedInterpolationExtension.class.getDeclaredMethod("setupFormStructure_Normal",ACParameterControllableSource.class,boolean.class);
        setup.setAccessible(true);
        System.out.println("STRUCTURE_BENCH axes,inserted,derivedForms,mode,round,nsPerCall,bytesPerCall");
        for(int axes:new int[]{2,3})for(int inserted:new int[]{4,8}) {
            var model=create(1,axes,ExtendedInterpolationType.ELLIPSE,inserted,false);
            for(boolean enabled:new boolean[]{false,true}) {
                NativeMeshInterpolationBridge.enabled(enabled);
                for(int i=0;i<12;i++)setup.invoke(model.extension,model.owner,true);
                for(int round=0;round<5;round++) {
                    long bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId()),start=System.nanoTime();
                    for(int i=0;i<10;i++)setup.invoke(model.extension,model.owner,true);
                    long elapsed=System.nanoTime()-start;
                    bytes=bean.getThreadAllocatedBytes(Thread.currentThread().getId())-bytes;
                    System.out.printf(Locale.ROOT,"STRUCTURE_BENCH %d,%d,%d,%s,%d,%.1f,%.1f%n",axes,inserted,
                        model.extension.getNormalExtendedKeyforms().size(),enabled?"optimized":"official",round,elapsed/10d,bytes/10d);
                }
            }
        }
    }

    static void exceptionCleanup() throws Exception {
        var bank=RuntimeInterpolationProof.bank(0,3,4,4,27,false);
        var method=CExtendedInterpolationExtension.class.getDeclaredMethod("setupFormStructure_Normal",ACParameterControllableSource.class,boolean.class);
        method.setAccessible(true);
        NativeMeshInterpolationBridge.enabled(true);
        try {method.invoke(bank.extension(),null,false);throw new AssertionError("expected native failure");}
        catch(InvocationTargetException expected) {if(!(expected.getCause() instanceof NullPointerException))throw expected;}
        var scope=NativeFormLookupBridge.class.getDeclaredMethod("hasScope");scope.setAccessible(true);
        if((boolean)scope.invoke(null))throw new AssertionError("scope survived native exception");
    }
}
