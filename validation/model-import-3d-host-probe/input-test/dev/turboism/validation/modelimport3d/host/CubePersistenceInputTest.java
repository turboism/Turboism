package dev.turboism.validation.modelimport3d.host;

import java.nio.file.*;
import java.util.List;

/** Profile preparation and actual synthetic import, not SAVE_AS/reopen evidence. */
public final class CubePersistenceInputTest {
    static int checks;
    static void check(boolean ok,String message) {checks++;if(!ok)throw new AssertionError(message);}
    public static void main(String[] args)throws Exception {
        for(int i=0;i<2;i++) {
            var expected=CubePersistenceInput.values()[i];
            byte[] bytes=Files.readAllBytes(Path.of(args[i]));
            if(i==1)check(java.util.Arrays.equals(bytes,CubePersistenceInput.readTexture(new java.io.ByteArrayInputStream(bytes))),"exact packaged texture read");
            else {
                try {CubePersistenceInput.readTexture(new java.io.ByteArrayInputStream(bytes));throw new AssertionError("original substituted as texture");}
                catch(IllegalArgumentException correct) {check(correct.getMessage().equals("packaged persistence texture identity"),"original not a texture resource");}
            }
            var prepared=CubePersistenceInput.prepare(bytes);
            check(CubePersistenceInput.identify(prepared.inputSha256)==expected,"explicit input profile");
            check(expected.uvTransform.equals(i==0?List.of(.5,.25,.5,.25):List.of(.5,1d/512,.5,1d/256)),"UV contract");
            check(expected.imageWidth==(i==0?256:512) && expected.imageHeight==(i==0?128:256)
                && expected.atlasSize==(i==0?512:1024),"image/atlas dimensions");
            check(prepared.appearance.patches().stream().allMatch(p->p.material().textured()==(expected==CubePersistenceInput.SOURCE_TEXTURE)),"source material mode");
            var world=new SurfaceImportTest.World();
            var imported=SurfaceImport.apply(prepared,world::model,world.transactions()).value().orElseThrow();
            if(i==1 && args.length>2) Files.writeString(Path.of(args[2]),NativeReport.json(java.util.Map.of(
                "inputSha256",imported.inputSha256(),"partId",imported.partId().value(),
                "meshIds",imported.geometry().patches().stream().map(p->p.meshId().value()).toList(),
                "written",imported.geometry().patches().stream().map(p->NativeAssertions.snapshot(
                    world.model().drawables().find(p.meshId()).authoringSnapshot())).toList())),StandardOpenOption.CREATE_NEW);
            for(var patch:imported.geometry().patches()) {
                var snapshot=world.model().drawables().find(patch.meshId()).authoringSnapshot();
                var source=patch.source().textureUvs();
                for(int v=0;v<source.size();v++) {
                    var uv=snapshot.sourceGeometry().uvs().get(v);
                    float u=(float)(source.get(v).x()*expected.uvTransform.get(0)+expected.uvTransform.get(1));
                    float w=(float)(source.get(v).y()*expected.uvTransform.get(2)+expected.uvTransform.get(3));
                    check(uv.x()==u && uv.y()==w,"profile agrees with actual registered UV");
                    var pos=snapshot.sourceGeometry().positions().get(v);
                    check(pos.x()==u*expected.imageWidth && pos.y()==w*expected.imageHeight,"registered image pixels");
                }
            }
            bytes[0]^=1;
            try {CubePersistenceInput.readTexture(new java.io.ByteArrayInputStream(bytes));throw new AssertionError("changed resource admitted");}
            catch(IllegalArgumentException correct) {check(correct.getMessage().equals("packaged persistence texture identity"),"changed resource rejected");}
            try {CubePersistenceInput.prepare(bytes);throw new AssertionError("modified input admitted");}
            catch(IllegalArgumentException correct) {check(correct.getMessage().equals("unapproved cube persistence input"),"identity rejected before parsing");}
        }
        for(String hash:new String[]{"", "0".repeat(64), null}) {
            try {CubePersistenceInput.identify(hash);throw new AssertionError("unknown profile admitted");}
            catch(IllegalArgumentException correct) {checks++;}
        }
        System.out.println("CubePersistenceInputTest: "+checks+" checks PASS; hostExecuted=false");
    }
}
