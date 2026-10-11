package dev.turboism.diagnostic;

import com.live2d.cubism.doc.model.*;
import com.live2d.cubism.doc.model.drawable.artMesh.*;
import com.live2d.serialize.*;
import dev.turboism.adapter.cubism.interpolation.DerivedCacheSerializationBridge;
import dev.turboism.adapter.cubism.interpolation.NativeMeshInterpolationBridge;
import java.nio.file.*;
import java.util.*;
import java.security.MessageDigest;
import org.jdom.output.XMLOutputter;

/** Full admitted Cat source serialization and fresh-process reconstruction. No Editor input. */
public final class CatProjectionRoundtripProof {
    static String hash(byte[] bytes)throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    static void string(MessageDigest digest,String value) {
        byte[] bytes=String.valueOf(value).getBytes(java.nio.charset.StandardCharsets.UTF_8);
        CatInterpolationCacheProof.integer(digest,bytes.length);digest.update(bytes);
    }
    static String structure(CModelSource model)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");
        for(var mesh:model.getAllArtMeshes()) {
            digest.update(mesh.getGuid().getUuidString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            for(float[] values:List.of(mesh.getPositions(),mesh.getUvs())) for(float v:values)CatInterpolationCacheProof.integer(digest,Float.floatToRawIntBits(v));
            for(int i:mesh.getIndices())CatInterpolationCacheProof.integer(digest,i);
            CatInterpolationCacheProof.integer(digest,mesh.getCulling()?1:0);
        }
        var output=new XMLOutputter();
        for(var mesh:model.getAllArtMeshes()) {
            var grid=mesh.getKeyformGridSource();
            CatInterpolationCacheProof.integer(digest,grid.getCanOutside()?1:0);
            CatInterpolationCacheProof.integer(digest,grid.getKeyformBindings().size());
            for(var binding:grid.getKeyformBindings()) {
                string(digest,binding.getParameterGuid().getUuidString());
                string(digest,binding.getInterpolationType().name());string(digest,binding.getExtendedInterpolationType().name());
                CatInterpolationCacheProof.integer(digest,binding.getInsertPointCount());
                CatInterpolationCacheProof.integer(digest,Float.floatToRawIntBits(binding.getExtendedInterpolationScale()));
                CatInterpolationCacheProof.integer(digest,binding.isIllegalExtendedInterpolation()?1:0);
                string(digest,binding.getDescription());
                CatInterpolationCacheProof.integer(digest,binding.getKeys().size());
                for(float key:binding.getKeys())CatInterpolationCacheProof.integer(digest,Float.floatToRawIntBits(key));
            }
            CatInterpolationCacheProof.integer(digest,grid.getKeyformsOnGrid().size());
            for(var form:grid.getKeyformsOnGrid()) {
                string(digest,form.b().getUuidString());
                CatInterpolationCacheProof.integer(digest,form.a().a().size());
                for(var key:form.a().a()) {
                    string(digest,key.getParameterGuid().getUuidString());
                    CatInterpolationCacheProof.integer(digest,key.getKeyIndex());
                }
            }
        }
        for(Object value:List.of(model.getParameterSourceSet(),model.getParameterGroupSet(),model.getDeformerSourceSet(),model.getPartSourceSet()))
            digest.update(output.outputString(UUSerialize.INSTANCE.writeXml(value,new HashMap<>())).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }
    public static void main(String[] args) throws Exception {
        try { run(args);System.exit(0); } catch(Throwable failure) { failure.printStackTrace();System.exit(1); }
    }
    static void run(String[] args)throws Exception {
        System.out.println("STAGE_READ_START");
        var model=(CModelSource)UUSerialize.INSTANCE.deserializeFromFile(Path.of(args[1]).toFile(),new HashMap<>(),-1,false);
        System.out.println("STAGE_READ_DONE meshes="+model.getAllArtMeshes().size());
        NativeMeshInterpolationBridge.enabled(true);model.setup();
        System.out.println("STAGE_SETUP_DONE");
        var forms=CatInterpolationCacheProof.forms(model);
        String bank=CatInterpolationCacheProof.bank(forms);System.out.println("STAGE_BANK_DONE");
        String structure=structure(model);System.out.println("STAGE_STRUCTURE_DONE");
        if(model.getAllArtMeshes().size()!=512 || forms.size()!=557568)throw new AssertionError("admitted Cat inventory");
        if(args[0].equals("save")) {
            var gridReferences=model.getAllArtMeshes().stream().map(m->CatInterpolationCacheProof.extension(m).getExtendedKeyformGridSource()).toList();
            var references=model.getAllArtMeshes().stream().map(m->new ArrayList<>(CatInterpolationCacheProof.extension(m).getNormalExtendedKeyforms())).toList();
            int[] versions=forms.stream().mapToInt(ACForm::getEditVersion).toArray();
            DerivedCacheSerializationBridge.enabled(true);
            var peak=new java.util.concurrent.atomic.AtomicLong();
            var measuring=new java.util.concurrent.atomic.AtomicBoolean(true);
            var sampler=new Thread(()->{
                while(measuring.get()) {
                    peak.accumulateAndGet(java.lang.management.ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed(),Math::max);
                    try { Thread.sleep(25); } catch(InterruptedException done) { break; }
                }
            },"cat-save-heap-sampler");
            sampler.setDaemon(true);sampler.start();
            long allocatedBefore=CatInterpolationCacheProof.allocated(),started=System.nanoTime();
            long streamedBefore=dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.streamedArchives();
            System.out.println("BEFORE_SAVE "+DerivedCacheSerializationBridge.diagnostic()+" usedHeap="+(Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory()));
            try {
                if(!UUSerialize.INSTANCE.serializeToFile(Path.of(args[2]).toFile(),model,true,CompressOption.FAST,null,new HashMap<>(),false,-1))throw new AssertionError("official native serialization failed");
            } finally {
                measuring.set(false);sampler.interrupt();sampler.join();
                System.out.println("SAVE_COST elapsedNs="+(System.nanoTime()-started)+" threadAllocatedBytes="+(CatInterpolationCacheProof.allocated()-allocatedBefore)+" sampledHeapPeakBytes="+peak.get());
                System.out.println("AFTER_SAVE "+DerivedCacheSerializationBridge.diagnostic()+" streamedArchives="+dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.streamedArchives());
            }
            if(Boolean.getBoolean("turboism.meshInterpolation.serializationStreaming") && dev.turboism.adapter.cubism.interpolation.NativeXmlStreamingBridge.streamedArchives()!=streamedBefore+1)throw new AssertionError("full streaming path not used");
            if(!CatRuntimeInterpolationAgent.installation.serializationPatched())throw new AssertionError("serialization hooks absent");
            if(DerivedCacheSerializationBridge.projectCache() || !bank.equals(CatInterpolationCacheProof.bank(forms)) || !structure.equals(structure(model)))throw new AssertionError("save changed live source/cache");
            for(int i=0;i<forms.size();i++)if(versions[i]!=forms.get(i).getEditVersion())throw new AssertionError("save changed live version");
            for(int m=0;m<references.size();m++) {
                var ext=CatInterpolationCacheProof.extension(model.getAllArtMeshes().get(m));
                if(ext.getExtendedKeyformGridSource()!=gridReferences.get(m))throw new AssertionError("save changed live derived grid identity");
                var live=ext.getNormalExtendedKeyforms();var before=references.get(m);
                if(live.size()!=before.size())throw new AssertionError("save changed cache size");
                for(int i=0;i<live.size();i++)if(live.get(i)!=before.get(i))throw new AssertionError("save replaced live cache identity");
            }
            Files.writeString(Path.of(args[2]+".bank"),bank+"\n"+structure+"\n");
            System.out.println("PASS_FULL_CAT_PROJECTED_SAVE liveCacheReferencesAndVersionsExact=true bank="+bank+" structure="+structure+" bytes="+Files.size(Path.of(args[2])));
        } else {
            String[] expected=Files.readString(Path.of(args[1]+".bank")).split("\\n");
            if(!bank.equals(expected[0]) || !structure.equals(expected[1]))throw new AssertionError("fresh process differs from saved cache/source bank");
            System.out.println("PASS_FULL_CAT_FRESH_PROCESS_RECONSTRUCTION bank="+bank+" structure="+structure+" forms="+forms.size());
        }
    }
}
