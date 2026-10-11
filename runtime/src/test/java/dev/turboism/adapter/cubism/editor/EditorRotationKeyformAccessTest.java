package dev.turboism.adapter.cubism.editor;

import dev.turboism.mapping.verification.StaticSelector;
import dev.turboism.mapping.verification.TestVerifiedResolvers;
import dev.turboism.mapping.verification.VerifiedMemberResolver;
import dev.turboism.mapping.verification.selector.EditorRotationAuthoringSelectorContract;
import dev.turboism.sdk.cubism.id.DeformerId;
import dev.turboism.sdk.cubism.model.RotationDeformerForm;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot;
import dev.turboism.sdk.cubism.model.RotationAuthoringSnapshot.Coordinate;
import dev.turboism.sdk.cubism.model.RotationKeyformForm;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static dev.turboism.adapter.cubism.editor.EditorArtMeshAuthoringReadAccessTest.*;
import static org.junit.jupiter.api.Assertions.*;

class EditorRotationKeyformAccessTest {
    static final DeformerId ID=new DeformerId("warp");
    static final RotationDeformerForm POINTS=new RotationDeformerForm(180,2,3,1,false,false);
    static RotationKeyformForm edit(float x,float y,String guid){return new RotationKeyformForm(List.of(new Coordinate("y",y),new Coordinate("x",x)),guid,POINTS);}

    @Test void readsStoredFormsWithoutCurrentFormOrSliderSelectorsAndWritesOnlyNamedCells() {
        var f=new Fixture();var before=f.read();
        assertEquals(9,before.forms().size());assertEquals(9,before.cells().size());
        assertEquals(before.axes().get(0).parameterId(),before.axes().get(1).parameterId());
        f.write(before,List.of(edit(1,1,"f8"),edit(-1,-1,"f0")));
        var after=f.read();assertEquals(1,f.writes);
        assertEquals(before.axes(),after.axes());assertEquals(before.cells(),after.cells());
        for(int i=0;i<before.forms().size();i++) {
            var old=before.forms().get(i);var now=after.forms().get(i);
            if(List.of("f8","f0").contains(old.formGuid()))assertEquals(POINTS,now.value());else assertEquals(old,now);
        }
        f.write(after,List.of(edit(1,1,"f8")));assertEquals(1,f.writes);
        f.source.forms.get(0).angle=999;
        assertNotEquals(999,before.forms().get(0).value().angle());
        assertThrows(UnsupportedOperationException.class,()->before.forms().clear());
    }
    @Test void rejectsDefaultSharedWrongGuidIncompleteDuplicateAndOffGridTargetsBeforeWriter() {
        var invalid=List.of(List.of(edit(0,0,"f4")),List.of(edit(1,1,"f7")),List.of(edit(.5f,1,"f8")),
            List.of(edit(1,1,"f8"),edit(1,1,"f8")),List.of(new RotationKeyformForm(List.of(new Coordinate("x",1)),"f8",POINTS)),
            List.of(new RotationKeyformForm(List.of(new Coordinate("x",1),new Coordinate("x",1)),"f8",POINTS)));
        for(var input:invalid){var f=new Fixture();assertThrows(IllegalArgumentException.class,()->f.write(f.read(),input));assertEquals(0,f.writes);}
        var shared=new Fixture();shared.source.base.grid.rows.get("f8").add(shared.source.base.grid.rows.remove("f7").get(0));
        assertThrows(IllegalArgumentException.class,()->shared.write(shared.read(),List.of(edit(1,1,"f8"))));assertEquals(0,shared.writes);
        var missing=new Fixture();missing.source.base.grid.bindings.get(0).parameter.defaultValue=.25f;
        assertThrows(IllegalArgumentException.class,()->missing.write(missing.read(),List.of(edit(1,1,"f8"))));
    }
    @Test void rejectsStaleSnapshotAndReusedNativeGuidDuringWriteWithoutTouchingReplacement() {
        var f=new Fixture();var before=f.read();f.source.baseAngle=2;
        assertThrows(RuntimeException.class,()->f.write(before,List.of(edit(1,1,"f8"))));assertEquals(0,f.writes);
        var replaced=new Fixture();var old=replaced.read();
        replaced.beforeMutation=()->replaced.source.forms.set(8,new RotationForm("f8",8));
        assertThrows(IllegalStateException.class,()->replaced.write(old,List.of(edit(1,1,"f8"))));
        assertNotEquals(POINTS,replaced.read().forms().get(8).value());
    }
    @Test void midBatchSetterFailureAndPostconditionFailureRestoreCompleteBeforeImage() {
        var f=new Fixture();var before=f.read();
        f.source.forms.get(8).afterSet=()->{throw new IllegalStateException("injected setter failure");};
        assertThrows(RuntimeException.class,()->f.write(before,List.of(edit(-1,-1,"f0"),edit(1,1,"f8"))));
        assertEquals(before,f.read());
        var post=new Fixture();var original=post.read();post.afterMutation=()->post.source.forms.get(7).angle=888;
        assertThrows(IllegalStateException.class,()->post.write(original,List.of(edit(1,1,"f8"))));assertEquals(original,post.read());
    }
    @Test void capabilitiesVersionTransactionAndMorphRefuseBeforeWriter() {
        for(String capability:EditorRotationAuthoringSelectorContract.CAPABILITY_IDS) {
            var f=new Fixture();f.resolver=resolver("5.3.02",capability,"",false);assertThrows(UnsupportedOperationException.class,f::read);
        }
        for(String alias:EditorRotationAuthoringSelectorContract.READ_ALIASES) {
            var f=new Fixture();f.resolver=resolver("5.3.02","",alias,false);assertThrows(UnsupportedOperationException.class,f::read);
        }
        for(String alias:EditorRotationAuthoringSelectorContract.WRITE_ALIASES) {
            var f=new Fixture();var before=f.read();f.resolver=resolver("5.3.02","",alias,false);
            assertThrows(UnsupportedOperationException.class,()->f.write(before,List.of(edit(1,1,"f8"))));assertEquals(0,f.writes);
        }
        var deny=new Fixture();var before=deny.read();deny.resolver=resolver("5.3.02","","",true);
        assertThrows(UnsupportedOperationException.class,()->deny.write(before,List.of(edit(1,1,"f8"))));
        var transaction=new Fixture();transaction.requireTransaction=()->{throw new IllegalStateException("no scope");};
        assertThrows(IllegalStateException.class,()->transaction.write(transaction.read(),List.of(edit(1,1,"f8"))));assertEquals(0,transaction.writes);
        for(String version:List.of("5.4.00")){var f=new Fixture();f.resolver=resolver(version,"","",false);assertThrows(UnsupportedOperationException.class,f::read);}
        var morph=new Fixture();morph.source.base.targets.targets.add(new Target(new Guid("f8"),null));assertThrows(UnsupportedOperationException.class,morph::read);
    }
    @Test void offThreadReadsFail() {
        var f=new Fixture();
        assertThrows(IllegalStateException.class,()->new EditorRotationAuthoringReadAccess(f.resolver).read(ID,f.source));
    }
    @Test void protectedDefaultCollateralChangeIsNeverSilentlyRepaired() {
        var f=new Fixture();var before=f.read();f.afterMutation=()->f.source.forms.get(4).angle=888;
        assertThrows(IllegalStateException.class,()->f.write(before,List.of(edit(1,1,"f8"))));
        assertEquals(888,f.read().forms().get(4).value().angle());
    }
    public static final class RotationForm {
        final Guid guid;float angle,x,y,scale=1;boolean rx,ry;Runnable afterSet=()->{};
        RotationForm(String guid,float angle){this.guid=new Guid(guid);this.angle=angle;}
        public Guid guid(){return guid;}public float angle(){return angle;}public float x(){return x;}public float y(){return y;}
        public float scale(){return scale;}public boolean rx(){return rx;}public boolean ry(){return ry;}
        public void setAngle(float v){angle=v;afterSet.run();}public void setX(float v){x=v;}public void setY(float v){y=v;}
        public void setScale(float v){scale=v;}public void setRx(boolean v){rx=v;}public void setRy(boolean v){ry=v;}
    }
    public static final class RotationSource {
        final Source base=new Source();float baseAngle;final List<RotationForm> forms=new ArrayList<>();
        public Guid guid(){return base.guid();}public Grid grid(){return base.grid();}
        public List<RotationForm> forms(){return forms;}public Targets targets(){return base.targets();}
        public float baseAngle(){return baseAngle;}
    }
    private static VerifiedMemberResolver resolver(String version,String missingCapability,String missingAlias,boolean denyWrite) {
        var capabilities=new HashSet<>(EditorRotationAuthoringSelectorContract.CAPABILITY_IDS);
        if(!denyWrite)capabilities.add(EditorRotationAuthoringSelectorContract.WRITE_CAPABILITY_ID);capabilities.remove(missingCapability);
        var selectors=new ArrayList<StaticSelector>();
        for(var selector:EditorArtMeshAuthoringReadAccessTest.selectors())
            if(!selector.alias().contains("art-mesh") && !selector.alias().contains("drawable-form")
                    && !selector.alias().equals("cubism.editor-history.semantic.form.guid")
                    && !selector.alias().equals("cubism.editor-model.parameter-controllable.keyform-grid")
                    && !selector.alias().equals("cubism.editor-model.parameter-controllable.morph-target-set"))selectors.add(selector);
        selectors.add(method("cubism.editor-model.deformer-source.guid",RotationSource.class,"guid"));
        selectors.add(method("cubism.editor-model.parameter-controllable.keyform-grid",RotationSource.class,"grid"));
        selectors.add(method("cubism.editor-model.parameter-controllable.morph-target-set",RotationSource.class,"targets"));
        selectors.add(method("cubism.editor-model.rotation-source.keyforms",RotationSource.class,"forms"));
        selectors.add(method("cubism.editor-model.rotation-source.base-angle",RotationSource.class,"baseAngle"));
        selectors.add(method("cubism.editor-history.semantic.form.guid",RotationForm.class,"guid"));
        for(String[] member:List.of(new String[]{"angle","angle","setAngle"},new String[]{"origin-x","x","setX"},new String[]{"origin-y","y","setY"},new String[]{"scale","scale","setScale"},new String[]{"reflect-x","rx","setRx"},new String[]{"reflect-y","ry","setRy"})) {
            selectors.add(method("cubism.editor-model.rotation-form."+member[0],RotationForm.class,member[1]));
            selectors.add(method("cubism.editor-model.rotation-form.set-"+member[0],RotationForm.class,member[2],member[0].startsWith("reflect")?boolean.class:float.class));
        }
        for(String alias:EditorRotationAuthoringSelectorContract.WRITE_ALIASES)if(selectors.stream().noneMatch(s->s.alias().equals(alias)))selectors.add(method(alias,RotationSource.class,"guid"));
        return TestVerifiedResolvers.create(version,EditorRotationAuthoringSelectorContract.ADAPTER_SLICE_ID,capabilities,
            selectors.stream().filter(s->!s.alias().equals(missingAlias)).toList(),RotationSource.class.getClassLoader());
    }
    private static final class Fixture {
        final RotationSource source=new RotationSource();VerifiedMemberResolver resolver=resolver("5.3.02","","",false);
        int writes;Runnable beforeMutation=()->{},afterMutation=()->{},requireTransaction=()->{};
        Fixture(){source.base.targets.targets.clear();source.base.forms.removeIf(f->f.guid.text().startsWith("m"));
            var last=source.base.grid.rows.get("f0").remove(1);source.base.grid.rows.put("f8",new ArrayList<>(List.of(last)));source.base.forms.add(new StoredForm("f8",8));
            for(var form:source.base.forms)source.forms.add(new RotationForm(form.guid.text(),0));}
        RotationAuthoringSnapshot read(){return EditorHostThread.dispatch("test Warp read",()->new EditorRotationAuthoringReadAccess(resolver).read(ID,source));}
        void write(RotationAuthoringSnapshot before,List<RotationKeyformForm> edits) {
            EditorRotationKeyformAccess.writeBatch(resolver,ID,()->source,before,edits,requireTransaction,
                (form,property,label,mutation,applied,compensation,restored)->{
                    writes++;beforeMutation.run();
                    try{mutation.run();afterMutation.run();if(!applied.getAsBoolean())throw new IllegalStateException("postcondition");}
                    catch(RuntimeException failure){if(!restored.getAsBoolean())compensation.run();assertTrue(restored.getAsBoolean());throw failure;}
                });
        }
    }
}
